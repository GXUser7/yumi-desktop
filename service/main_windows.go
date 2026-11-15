//go:build windows

package main

import (
	"errors"
	"flag"
	"io"
	"net"
	"os"
	"path/filepath"
	"syscall"
	"time"

	"github.com/Microsoft/go-winio"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
)

const serviceName="YumiTunnel"
const pipeName=`\\.\pipe\YumiTunnel-v1`

// The tunnel's own adapter, as wintun names it. The same name every run: wintun derives a stable
// GUID from it, which is why the kill switch usually re-finds last run's interface.
func tunAdapterName() string { return "Yumi" }

// A console window flashing behind the worker is exactly the kind of thing someone notices.
func childProcAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{HideWindow: true}
}

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

/*
Where the worker's stderr goes, capped and reopened each time.

Under ProgramData rather than beside the executable: the service runs as LocalSystem and could
write into Program Files, but a program directory that changes under a running install is the
kind of thing installers and anti-malware both take badly.

Truncated rather than rotated once it passes a megabyte. A ring of files would be the tidier
answer and this is a log nobody reads until something breaks, at which point what matters is
the last failure and not the previous hundred.
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

// Owner-only access and remote-client rejection are both enforced by the pipe implementation.
func listenIPC(owner string) (net.Listener,error) {
    return winio.ListenPipe(pipeName,&winio.PipeConfig{SecurityDescriptor:"D:P(A;;GA;;;SY)(A;;GA;;;"+owner+")",InputBufferSize:65536,OutputBufferSize:65536})
}

type tunnelService struct{owner string}
func (t *tunnelService) Execute(_ []string,requests <-chan svc.ChangeRequest,changes chan<- svc.Status)(bool,uint32){
    changes<-svc.Status{State:svc.StartPending}
    // A previous life of this service may have died with the location mode applied and its
    // snapshot still on disk. Before anything else, the machine goes back to how that snapshot
    // found it.
    restoreAbandonedLocation()
    listener,err:=listenIPC(t.owner)
    if err!=nil{return true,1}
    s:=newSupervisor()
    defer shutdownSupervisor(s)
    serveUntilClosed(listener,t.owner,s)
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
