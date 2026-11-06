//go:build windows

package main

import (
    "bufio"
    "context"
    "encoding/binary"
    "encoding/json"
    "errors"
    "flag"
    "io"
    "net"
    "net/http"
    "os"
    "os/exec"
    "path/filepath"
    "sync"
    "sync/atomic"
    "syscall"
    "time"

    "github.com/GXUser7/yumi-desktop/service/firewall"
    "github.com/Microsoft/go-winio"
    "golang.org/x/sys/windows"
    "golang.org/x/sys/windows/svc"
    "golang.org/x/sys/windows/svc/mgr"
)

const serviceName="YumiTunnel"
const pipeName=`\\.\pipe\YumiTunnel-v1`

type supervisor struct {
    mu sync.Mutex
    commands sync.Mutex
    status Status
    child *exec.Cmd
    stdin io.WriteCloser
    done chan struct{}
    generation uint64

    // The kill switch. Its own lock, taken before mu whenever both are needed and never while mu
    // is held, because installing filters is a round trip to the filtering engine and the status
    // poll must not wait behind it. shieldUp mirrors shield != nil for the status, which reads it
    // under mu and so cannot take shieldMu.
    shieldMu sync.Mutex
    shield *firewall.Firewall
    shieldLUID uint64
    shieldLAN bool
    shieldUp atomic.Bool

    // Where the routing databases live, and the download in flight; see geo.go. Guarded by mu.
    geoDir string
    geoUpdating bool
    geoError string
}

func (s *supervisor) snapshot() Status {
    s.mu.Lock()
    status:=s.status
    status.GeoUpdating=s.geoUpdating;status.GeoError=s.geoError
    dir:=s.geoDir
    s.mu.Unlock()
    geo:=inspectGeo(dir)
    status.GeoReady=geo.Ready;status.GeoBytes=geo.Bytes;status.GeoUpdatedAt=geo.UpdatedAt
    status.KillSwitch=s.shieldUp.Load()
    status.TunLUID=0
    return status
}

/*
Raises the kill switch, or moves it onto a new adapter.

The new filters go in before the old ones come out, so there is no instant without a kill switch;
while both are installed the old one still blocks an adapter it does not know, which costs a few
milliseconds of nothing rather than a few milliseconds of leak.
*/
func (s *supervisor) raiseShield(luid uint64, lan bool) error {
    s.shieldMu.Lock();defer s.shieldMu.Unlock()
    if s.shield!=nil && s.shieldLUID==luid && s.shieldLAN==lan { return nil }
    next,err:=firewall.Enable(luid,lan)
    if err!=nil { return err }
    s.shield.Close()
    s.shield,s.shieldLUID,s.shieldLAN=next,luid,lan
    s.shieldUp.Store(true)
    return nil
}

func (s *supervisor) releaseShield() {
    s.shieldMu.Lock();defer s.shieldMu.Unlock()
    s.shield.Close()
    s.shield=nil;s.shieldLUID=0
    s.shieldUp.Store(false)
}

/*
The worker's adapter, as soon as the worker reports it: the kill switch is moved onto it if it is up
and this is still the current tunnel. Wintun names the adapter by a GUID derived from its name, so
this is normally the same interface as last time and nothing changes.
*/
func (s *supervisor) adoptAdapter(generation, luid uint64) {
    s.shieldMu.Lock()
    up:=s.shield!=nil;lan:=s.shieldLAN;same:=s.shieldLUID==luid
    s.shieldMu.Unlock()
    s.mu.Lock();current:=s.generation==generation;s.mu.Unlock()
    if !up || same || !current { return }
    // A failure leaves the old filters in place, which block this adapter as well: the tunnel then
    // carries nothing rather than something leaking around it. It is written down for whoever
    // reads the journal.
    if err:=s.raiseShield(luid,lan);err!=nil { journalLine("kill switch could not follow the adapter: "+detailOf(err)) }
}

/** Starts a database download unless one is already running; the status reports how it went. */
func (s *supervisor) updateGeo() {
    s.mu.Lock()
    if s.geoUpdating || s.geoDir=="" { s.mu.Unlock();return }
    s.geoUpdating=true;s.geoError="";dir:=s.geoDir
    s.mu.Unlock()
    go func(){
        ctx,cancel:=context.WithTimeout(context.Background(),10*time.Minute);defer cancel()
        err:=secureDirectory(dir)
        if err==nil { err=downloadGeo(ctx,&http.Client{Timeout:8*time.Minute},dir,geoAssets) }
        s.mu.Lock();s.geoUpdating=false;if err!=nil{s.geoError=detailOf(err)};s.mu.Unlock()
    }()
}

func (s *supervisor) stop() {
    s.mu.Lock();child,input,done:=s.child,s.stdin,s.done
    s.generation++;s.child=nil;s.stdin=nil;s.done=nil
    s.status=Status{Version:1,State:"disconnecting"};s.mu.Unlock()
    if input!=nil { _=input.Close() }
    if child!=nil { select {case <-done:case <-time.After(8*time.Second):_=child.Process.Kill();<-done} }
    s.mu.Lock();s.status=Status{Version:1,State:"disconnected"};s.mu.Unlock()
}

func (s *supervisor) connect(r Request) error {
    s.stop()
    port,err:=freePort();if err!=nil{return errors.New("probe_port_unavailable")}
    // The kill switch goes up before the worker starts and stays up through whatever happens to
    // it; only a disconnect lowers it. Turning the setting off takes effect on the next connect.
    if r.Options.KillSwitch {
        s.shieldMu.Lock();luid:=s.shieldLUID;s.shieldMu.Unlock()
        if err:=s.raiseShield(luid,r.Options.BypassLAN);err!=nil{return errorWithDetail{"kill_switch_failed",err}}
    } else {
        s.releaseShield()
    }
    initial:=Status{Version:1,State:"connecting",NodeID:r.NodeID,ProbePort:port,ProbeUser:randomSecret(),ProbePassword:randomSecret()}
    s.mu.Lock();dir:=s.geoDir;s.mu.Unlock()
    geo:=r.Options.BypassRussia && inspectGeo(dir).Ready
    config:=buildConfig(r,port,initial.ProbeUser,initial.ProbePassword,geo)
    exe,err:=os.Executable();if err!=nil{return errors.New("worker_unavailable")}
    child:=exec.Command(exe,"--worker")
    child.SysProcAttr=&syscall.SysProcAttr{HideWindow:true}
    // Where the core looks for geoip.dat and geosite.dat; the name is the core's own
    // (common/platform: xray.location.asset, spelt as an environment variable).
    if dir!="" { child.Env=append(os.Environ(),"XRAY_LOCATION_ASSET="+dir) }
    // Everything the worker says on stderr - a Go panic, a Wintun driver refusing to load, the
    // core's own complaints - used to go to io.Discard. That is the one thing a tunnel that will
    // not start must not do: the app can only report "could not start", and the sentence that
    // says why existed for a moment and was thrown away. Failing to open the journal is not worth
    // failing a connection over, so a broken log falls back to discarding as before.
    child.Stderr=workerJournal()
    input,err:=child.StdinPipe();if err!=nil{return errors.New("worker_unavailable")}
    output,err:=child.StdoutPipe();if err!=nil{input.Close();return errors.New("worker_unavailable")}
    if err=child.Start();err!=nil{input.Close();output.Close();return errors.New("worker_unavailable")}
    done:=make(chan struct{})
    s.mu.Lock();s.child=child;s.stdin=input;s.done=done;s.status=initial;generation:=s.generation;s.mu.Unlock()
    go func(){
        scanner:=bufio.NewScanner(output);scanner.Buffer(make([]byte,4096),maxFrame)
        for scanner.Scan(){
            var status Status
            if json.Unmarshal(scanner.Bytes(),&status)==nil && status.Version==1 {
                luid:=status.TunLUID;status.TunLUID=0
                s.mu.Lock();if s.generation==generation{s.status=status};s.mu.Unlock()
                if luid!=0 { s.adoptAdapter(generation,luid) }
            }
        }
        _=child.Wait()
        s.mu.Lock();if s.generation==generation && s.status.State!="failed" {s.status=Status{Version:1,State:"failed",Error:"worker_stopped"}};s.mu.Unlock()
        close(done)
    }()
    err=json.NewEncoder(input).Encode(obj{"config":config,"status":initial})
    if err!=nil{s.stop();return errors.New("worker_unavailable")}
    return nil
}

func (s *supervisor) handle(r Request) Status {
    if r.Command=="status" { return s.snapshot() }
    s.commands.Lock();defer s.commands.Unlock()
    switch r.Command {
    case "disconnect":s.stop();s.releaseShield()
    case "geo_update":s.updateGeo()
    case "connect":if err:=s.connect(r);err!=nil{
        failed:=Status{Version:1,State:"failed",Error:err.Error(),KillSwitch:s.shieldUp.Load()}
        var detailed errorWithDetail
        if errors.As(err,&detailed){failed.Error=detailed.code;failed.Detail=detailOf(detailed.cause)}
        s.mu.Lock();s.status=failed;s.mu.Unlock()
        return s.snapshot()
    }
    }
    return s.snapshot()
}

/**
 * Where the worker's stderr goes, capped and reopened each time.
 *
 * Under ProgramData rather than beside the executable: the service runs as LocalSystem and could
 * write into Program Files, but a program directory that changes under a running install is the
 * kind of thing installers and anti-malware both take badly.
 *
 * Truncated rather than rotated once it passes a megabyte. A ring of files would be the tidier
 * answer and this is a log nobody reads until something breaks, at which point what matters is
 * the last failure and not the previous hundred.
 */
func journalLine(line string) {
    if w,ok:=workerJournal().(*os.File);ok { _,_=w.WriteString(line+"\n");_=w.Close() }
}

/** A failure code for the app, carrying the sentence the library wrote for the detail line. */
type errorWithDetail struct{ code string; cause error }
func (e errorWithDetail) Error() string { return e.code }

/*
The routing databases' directory, under ProgramData and closed to everyone but SYSTEM and
administrators for writing.

ProgramData lets ordinary users create files in its subfolders by default, and the core run as
SYSTEM parses whatever it finds here. Nothing a database says can run code, but it can decide where
traffic goes, and that is not a decision for any account on the machine to make by dropping a file.
*/
func geoDirectory() string {
    root:=os.Getenv("ProgramData");if root==""{return ""}
    return filepath.Join(root,"Yumi Desktop","geo")
}

func secureDirectory(dir string) error {
    sd,err:=windows.SecurityDescriptorFromString("D:P(A;OICI;GA;;;SY)(A;OICI;GA;;;BA)(A;OICI;GRGX;;;BU)")
    if err!=nil{return err}
    dacl,_,err:=sd.DACL();if err!=nil{return err}
    if err=os.MkdirAll(dir,0o755);err!=nil{return err}
    return windows.SetNamedSecurityInfo(dir,windows.SE_FILE_OBJECT,
        windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION,nil,nil,dacl,nil)
}

func workerJournal() io.Writer {
    root:=os.Getenv("ProgramData");if root==""{return io.Discard}
    dir:=filepath.Join(root,"Yumi Desktop")
    if os.MkdirAll(dir,0o755)!=nil{return io.Discard}
    path:=filepath.Join(dir,"worker.log")
    if info,err:=os.Stat(path);err==nil && info.Size()>1<<20 { _=os.Remove(path) }
    file,err:=os.OpenFile(path,os.O_CREATE|os.O_WRONLY|os.O_APPEND,0o644);if err!=nil{return io.Discard}
    _,_=file.WriteString("\n--- "+time.Now().Format(time.RFC3339)+" ---\n")
    return file
}

/** This process's own account, which is the whole question the owner SID answers. */
func currentOwner() (string,error) {
    token:=windows.GetCurrentProcessToken()
    user,err:=token.GetTokenUser();if err!=nil{return "",err}
    return user.User.Sid.String(),nil
}

func authenticated(conn net.Conn,owner string) bool {
    fd,ok:=conn.(interface{Fd() uintptr});if !ok{return false}
    var pid,session uint32
    if windows.GetNamedPipeClientProcessId(windows.Handle(fd.Fd()),&pid)!=nil{return false}
    if windows.ProcessIdToSessionId(pid,&session)!=nil || session==0{return false}
    process,err:=windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION,false,pid);if err!=nil{return false};defer windows.CloseHandle(process)
    var token windows.Token
    if windows.OpenProcessToken(process,windows.TOKEN_QUERY,&token)!=nil{return false};defer token.Close()
    user,err:=token.GetTokenUser();return err==nil && user.User.Sid.String()==owner
}

func serveClient(conn net.Conn,owner string,s *supervisor) {
    defer conn.Close()
    _=conn.SetDeadline(time.Now().Add(15*time.Second))
    if !authenticated(conn,owner){return}
    var size uint32
    if binary.Read(conn,binary.BigEndian,&size)!=nil || size==0 || size>maxFrame{return}
    data:=make([]byte,size);if _,err:=io.ReadFull(conn,data);err!=nil{return}
    request,err:=decodeRequest(data)
    var status Status
    if err!=nil{status=Status{Version:1,State:"failed",Error:err.Error()}}else{status=s.handle(request)}
    reply,_:=json.Marshal(status)
    _=binary.Write(conn,binary.BigEndian,uint32(len(reply)));_,_=conn.Write(reply)
}

type tunnelService struct{owner string}
func (t *tunnelService) Execute(_ []string,requests <-chan svc.ChangeRequest,changes chan<- svc.Status)(bool,uint32){
    changes<-svc.Status{State:svc.StartPending}
    // Owner-only access and remote-client rejection are both enforced by the pipe implementation.
    listener,err:=winio.ListenPipe(pipeName,&winio.PipeConfig{SecurityDescriptor:"D:P(A;;GA;;;SY)(A;;GA;;;"+t.owner+")",InputBufferSize:65536,OutputBufferSize:65536})
    if err!=nil{return true,1}
    s:=&supervisor{status:Status{Version:1,State:"disconnected"},geoDir:geoDirectory()}
    defer func(){listener.Close();s.commands.Lock();defer s.commands.Unlock();s.stop();s.releaseShield()}()
    go func(){for{conn,err:=listener.Accept();if err!=nil{return};go serveClient(conn,t.owner,s)}}()
    current:=svc.Status{State:svc.Running,Accepts:svc.AcceptStop|svc.AcceptShutdown}
    changes<-current
    for request:=range requests{switch request.Cmd{case svc.Interrogate:changes<-current;case svc.Stop,svc.Shutdown:changes<-svc.Status{State:svc.StopPending};return false,0}}
    return false,0
}

func install(owner string) error {
    // Falling back to this process's own account is a last resort, and a poor one: during an
    // install the process is whoever answered the UAC prompt, which on a machine where that is a
    // separate administrator account is the wrong person entirely. The service then runs for
    // somebody who never uses it, and the app - started as the real user - is refused by its own
    // service while `services.msc` cheerfully reports it running. The installer passes the SID it
    // collected from the original user; see Yumi.iss.
    if owner==""{found,err:=currentOwner();if err!=nil{return err};owner=found}
    if _,err:=windows.StringToSid(owner);err!=nil{return err}
    m,err:=mgr.Connect();if err!=nil{return err};defer m.Disconnect()
    exe,err:=os.Executable();if err!=nil{return err}
    // An unrestricted service SID puts NT SERVICE\YumiTunnel into the token the service and its
    // worker run with, which is what the kill switch's permit for this executable is scoped to.
    config:=mgr.Config{DisplayName:"Yumi VPN tunnel",StartType:mgr.StartAutomatic,Description:"Local tunnel service for Yumi Desktop",SidType:windows.SERVICE_SID_TYPE_UNRESTRICTED}
    service,err:=m.OpenService(serviceName)
    if err==nil{
        defer service.Close()
        config.BinaryPathName=`"`+exe+`" --service --owner `+owner
        if err=service.UpdateConfig(config);err!=nil{return err}
        // A running service keeps the arguments it was started with, so a new owner does not take
        // effect until it is restarted. This path is not only the installer's: the app calls it to
        // re-register the service to the account actually using it, and there the service is
        // certainly running. Stopping a service that was not running is not an error worth having.
        halt(service)
    }else{
        service,err=m.CreateService(serviceName,exe,config,"--service","--owner",owner)
        if err!=nil{return err};defer service.Close()
    }
    // Someone else winning the race to start it is the outcome we wanted anyway.
    if err=service.Start();err!=nil && !errors.Is(err,windows.ERROR_SERVICE_ALREADY_RUNNING){return err}
    return nil
}

/** Stops a service and waits for it to admit that it stopped. Best effort throughout. */
func halt(s *mgr.Service) {
    if _,err:=s.Control(svc.Stop);err!=nil{return}
    for i:=0;i<50;i++{
        status,err:=s.Query();if err!=nil||status.State==svc.Stopped{return}
        time.Sleep(200*time.Millisecond)
    }
}

func removeService() error{
    m,err:=mgr.Connect();if err!=nil{return err};defer m.Disconnect()
    s,err:=m.OpenService(serviceName);if err!=nil{return nil};defer s.Close()
    halt(s)
    return s.Delete()
}

func main(){
    isWorker:=flag.Bool("worker",false,"");isService:=flag.Bool("service",false,"")
    installFlag:=flag.Bool("install",false,"");removeFlag:=flag.Bool("uninstall",false,"")
    // Two sinks for one answer. The app runs this as itself and reads stdout; the installer runs
    // it through ExecAsOriginalUser, which reports an exit code and nothing else, so that path
    // needs a file to read afterwards. Neither touches the service or needs any privilege.
    printOwner:=flag.Bool("print-owner",false,"");ownerOut:=flag.String("owner-out","","")
    owner:=flag.String("owner","","");flag.Parse()
    var err error
    switch{case *isWorker:worker();return
    case *printOwner || *ownerOut!="":
        sid,e:=currentOwner();if e!=nil{err=e;break}
        if *ownerOut!=""{err=os.WriteFile(*ownerOut,[]byte(sid),0o644)}else{_,err=os.Stdout.WriteString(sid)}
    case *installFlag:err=install(*owner);case *removeFlag:err=removeService();case *isService:
        if _,err=windows.StringToSid(*owner);err==nil{err=svc.Run(serviceName,&tunnelService{*owner})}
    default:os.Exit(2)}
    if err!=nil{os.Stderr.WriteString("Yumi service operation failed.\n");os.Exit(1)}
}
