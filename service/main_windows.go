//go:build windows

package main

import (
    "bufio"
    "encoding/binary"
    "encoding/json"
    "errors"
    "flag"
    "io"
    "net"
    "os"
    "os/exec"
    "path/filepath"
    "sync"
    "syscall"
    "time"

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
}

func (s *supervisor) snapshot() Status { s.mu.Lock();defer s.mu.Unlock();return s.status }
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
    initial:=Status{Version:1,State:"connecting",NodeID:r.NodeID,ProbePort:port,ProbeUser:randomSecret(),ProbePassword:randomSecret()}
    config:=buildConfig(r,port,initial.ProbeUser,initial.ProbePassword)
    exe,err:=os.Executable();if err!=nil{return errors.New("worker_unavailable")}
    child:=exec.Command(exe,"--worker")
    child.SysProcAttr=&syscall.SysProcAttr{HideWindow:true}
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
                s.mu.Lock();if s.generation==generation{s.status=status};s.mu.Unlock()
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
    case "disconnect":s.stop()
    case "connect":if err:=s.connect(r);err!=nil{return Status{Version:1,State:"failed",Error:err.Error()}}
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
    s:=&supervisor{status:Status{Version:1,State:"disconnected"}}
    defer func(){listener.Close();s.commands.Lock();defer s.commands.Unlock();s.stop()}()
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
    config:=mgr.Config{DisplayName:"Yumi VPN tunnel",StartType:mgr.StartAutomatic,Description:"Local tunnel service for Yumi Desktop"}
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
