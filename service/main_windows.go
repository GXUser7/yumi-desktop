//go:build windows

package main

import (
	"errors"
	"flag"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"syscall"
	"time"
	"unsafe"

	"github.com/Microsoft/go-winio"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
)

const serviceName = "YumiTunnel"
const pipeName = `\\.\pipe\YumiTunnel-v1`
const securityMandatoryMediumRID = 0x2000

// The tunnel's own adapter, as wintun names it. The same name every run: wintun derives a stable
// GUID from it, which is why the kill switch usually re-finds last run's interface.
func tunAdapterName() string { return "Yumi" }

// A console window flashing behind the worker is exactly the kind of thing someone notices.
func childProcAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{HideWindow: true}
}

func programDataRoot() string {
	root := os.Getenv("ProgramData")
	if root == "" {
		return ""
	}
	return filepath.Join(root, "Yumi Desktop")
}

func dataRoot() string { return programDataRoot() }

/*
The routing databases' directory, under ProgramData and closed to everyone but SYSTEM and
administrators for writing.

ProgramData lets ordinary users create files in its subfolders by default, and the core run as
SYSTEM parses whatever it finds here. Nothing a database says can run code, but it can decide where
traffic goes, and that is not a decision for any account on the machine to make by dropping a file.
*/
func geoDirectory() string {
	root := programDataRoot()
	if root == "" {
		return ""
	}
	return filepath.Join(root, "geo")
}

func enableProcessPrivileges(names ...string) {
	var token windows.Token
	if err := windows.OpenProcessToken(windows.CurrentProcess(), windows.TOKEN_ADJUST_PRIVILEGES|windows.TOKEN_QUERY, &token); err != nil {
		return
	}
	defer token.Close()
	for _, name := range names {
		ptr, err := windows.UTF16PtrFromString(name)
		if err != nil {
			continue
		}
		var luid windows.LUID
		if err := windows.LookupPrivilegeValue(nil, ptr, &luid); err != nil {
			continue
		}
		tp := windows.Tokenprivileges{
			PrivilegeCount: 1,
			Privileges: [1]windows.LUIDAndAttributes{{
				Luid:       luid,
				Attributes: windows.SE_PRIVILEGE_ENABLED,
			}},
		}
		_ = windows.AdjustTokenPrivileges(token, false, &tp, 0, nil, nil)
	}
}

func openDirectoryNoReparse(dir string, access uint32) (windows.Handle, error) {
	ptr, err := windows.UTF16PtrFromString(dir)
	if err != nil {
		return 0, err
	}
	handle, err := windows.CreateFile(
		ptr,
		access,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE,
		nil,
		windows.OPEN_EXISTING,
		windows.FILE_FLAG_BACKUP_SEMANTICS|windows.FILE_FLAG_OPEN_REPARSE_POINT,
		0,
	)
	if err != nil {
		return 0, err
	}
	var info windows.ByHandleFileInformation
	if err := windows.GetFileInformationByHandle(handle, &info); err != nil {
		_ = windows.CloseHandle(handle)
		return 0, err
	}
	if info.FileAttributes&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0 {
		_ = windows.CloseHandle(handle)
		return 0, errors.New("directory is a reparse point")
	}
	if info.FileAttributes&windows.FILE_ATTRIBUTE_DIRECTORY == 0 {
		_ = windows.CloseHandle(handle)
		return 0, errors.New("path is not a directory")
	}
	return handle, nil
}

func secureDirectory(dir string) error {
	if dir == "" {
		return errors.New("empty directory")
	}
	sd, err := windows.SecurityDescriptorFromString("O:SYG:SYD:P(A;OICI;GA;;;SY)(A;OICI;GA;;;BA)(A;OICI;GRGX;;;BU)")
	if err != nil {
		return err
	}
	owner, _, err := sd.Owner()
	if err != nil {
		return err
	}
	group, _, err := sd.Group()
	if err != nil {
		return err
	}
	dacl, _, err := sd.DACL()
	if err != nil {
		return err
	}
	if err = os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	enableProcessPrivileges("SeTakeOwnershipPrivilege", "SeRestorePrivilege")
	handle, err := openDirectoryNoReparse(dir, windows.READ_CONTROL|windows.WRITE_DAC|windows.WRITE_OWNER)
	if err != nil {
		return err
	}
	defer windows.CloseHandle(handle)
	err = windows.SetSecurityInfo(
		handle,
		windows.SE_FILE_OBJECT,
		windows.OWNER_SECURITY_INFORMATION|windows.GROUP_SECURITY_INFORMATION|windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION,
		owner,
		group,
		dacl,
		nil,
	)
	runtime.KeepAlive(sd)
	return err
}

func openJournalHandleNoReparse(path string) (windows.Handle, windows.ByHandleFileInformation, error) {
	var info windows.ByHandleFileInformation
	ptr, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return 0, info, err
	}
	handle, err := windows.CreateFile(
		ptr,
		windows.FILE_APPEND_DATA|windows.FILE_READ_ATTRIBUTES|windows.FILE_WRITE_ATTRIBUTES|windows.SYNCHRONIZE,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE,
		nil,
		windows.OPEN_ALWAYS,
		windows.FILE_ATTRIBUTE_NORMAL|windows.FILE_FLAG_OPEN_REPARSE_POINT,
		0,
	)
	if err != nil {
		return 0, info, err
	}
	if err := windows.GetFileInformationByHandle(handle, &info); err != nil {
		_ = windows.CloseHandle(handle)
		return 0, info, err
	}
	if info.FileAttributes&(windows.FILE_ATTRIBUTE_REPARSE_POINT|windows.FILE_ATTRIBUTE_DIRECTORY) != 0 {
		_ = windows.CloseHandle(handle)
		return 0, info, errors.New("journal is not a regular file")
	}
	return handle, info, nil
}

/*
Where the worker's stderr goes, capped and rotated each time.

Under ProgramData rather than beside the executable: the service runs as LocalSystem and could
write into Program Files, but a program directory that changes under a running install is the
kind of thing installers and anti-malware both take badly.
*/
func workerJournal() *os.File {
	journalMu.Lock()
	defer journalMu.Unlock()
	dir := programDataRoot()
	if dir == "" {
		return nil
	}
	if os.MkdirAll(dir, 0o755) != nil {
		return nil
	}
	dirHandle, err := openDirectoryNoReparse(dir, windows.FILE_READ_ATTRIBUTES)
	if err != nil {
		return nil
	}
	_ = windows.CloseHandle(dirHandle)

	path := filepath.Join(dir, "worker.log")
	handle, info, err := openJournalHandleNoReparse(path)
	if err != nil {
		return nil
	}
	size := (uint64(info.FileSizeHigh) << 32) | uint64(info.FileSizeLow)
	if size > 1<<20 {
		_ = windows.CloseHandle(handle)
		_ = os.Rename(path, path+".1")
		handle, _, err = openJournalHandleNoReparse(path)
		if err != nil {
			return nil
		}
	}
	file := os.NewFile(uintptr(handle), path)
	if file == nil {
		_ = windows.CloseHandle(handle)
		return nil
	}
	_, _ = file.WriteString("\n--- " + time.Now().Format(time.RFC3339) + " ---\n")
	return file
}

func openWorkerJournalRead() (*os.File, error) {
	dir := programDataRoot()
	if dir == "" {
		return nil, os.ErrNotExist
	}
	dirHandle, err := openDirectoryNoReparse(dir, windows.FILE_READ_ATTRIBUTES)
	if err != nil {
		return nil, err
	}
	_ = windows.CloseHandle(dirHandle)
	path := filepath.Join(dir, "worker.log")
	ptr, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return nil, err
	}
	handle, err := windows.CreateFile(
		ptr,
		windows.GENERIC_READ,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE,
		nil,
		windows.OPEN_EXISTING,
		windows.FILE_ATTRIBUTE_NORMAL|windows.FILE_FLAG_OPEN_REPARSE_POINT,
		0,
	)
	if err != nil {
		return nil, err
	}
	var info windows.ByHandleFileInformation
	if err := windows.GetFileInformationByHandle(handle, &info); err != nil {
		_ = windows.CloseHandle(handle)
		return nil, err
	}
	if info.FileAttributes&(windows.FILE_ATTRIBUTE_REPARSE_POINT|windows.FILE_ATTRIBUTE_DIRECTORY) != 0 {
		_ = windows.CloseHandle(handle)
		return nil, errors.New("journal is not a regular file")
	}
	file := os.NewFile(uintptr(handle), path)
	if file == nil {
		_ = windows.CloseHandle(handle)
		return nil, errors.New("open journal failed")
	}
	return file, nil
}

/** This process's own account, which is the whole question the owner SID answers. */
func currentOwner() (string, error) {
	token := windows.GetCurrentProcessToken()
	user, err := token.GetTokenUser()
	if err != nil {
		return "", err
	}
	return user.User.Sid.String(), nil
}

func tokenIntegrityRID(token windows.Token) (uint32, bool) {
	var needed uint32
	err := windows.GetTokenInformation(token, windows.TokenIntegrityLevel, nil, 0, &needed)
	if needed == 0 || (err != nil && !errors.Is(err, windows.ERROR_INSUFFICIENT_BUFFER)) {
		return 0, false
	}
	buf := make([]byte, needed)
	if err := windows.GetTokenInformation(token, windows.TokenIntegrityLevel, &buf[0], needed, &needed); err != nil {
		return 0, false
	}
	label := (*windows.Tokenmandatorylabel)(unsafe.Pointer(&buf[0]))
	sid := label.Label.Sid
	if sid == nil || !sid.IsValid() || sid.SubAuthorityCount() == 0 || sid.IdentifierAuthority() != windows.SECURITY_MANDATORY_LABEL_AUTHORITY {
		runtime.KeepAlive(buf)
		return 0, false
	}
	rid := sid.SubAuthority(uint32(sid.SubAuthorityCount()) - 1)
	runtime.KeepAlive(buf)
	return rid, true
}

func authenticated(conn net.Conn, owner string) bool {
	fd, ok := conn.(interface{ Fd() uintptr })
	if !ok {
		return false
	}
	var pid, session uint32
	if windows.GetNamedPipeClientProcessId(windows.Handle(fd.Fd()), &pid) != nil {
		return false
	}
	if windows.ProcessIdToSessionId(pid, &session) != nil || session == 0 {
		return false
	}
	process, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return false
	}
	defer windows.CloseHandle(process)
	var token windows.Token
	if windows.OpenProcessToken(process, windows.TOKEN_QUERY, &token) != nil {
		return false
	}
	defer token.Close()
	user, err := token.GetTokenUser()
	if err != nil || user.User.Sid.String() != owner {
		return false
	}
	rid, ok := tokenIntegrityRID(token)
	return ok && rid >= securityMandatoryMediumRID
}

// Owner-only access (without WRITE_DAC/WRITE_OWNER/FILE_CREATE_PIPE_INSTANCE) and remote-client
// rejection are both enforced by the pipe implementation.
func listenIPC(owner string) (net.Listener, error) {
	return winio.ListenPipe(pipeName, &winio.PipeConfig{
		SecurityDescriptor: "D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;0x12019b;;;" + owner + ")",
		InputBufferSize:    65536,
		OutputBufferSize:   65536,
	})
}

func ensureProgramDataTree() error {
	root := programDataRoot()
	if root == "" {
		return errors.New("ProgramData is not set")
	}
	if err := secureDirectory(root); err != nil {
		return err
	}
	if stateDir := filepath.Dir(sysStatePath()); stateDir != "" {
		if err := secureDirectory(stateDir); err != nil {
			return err
		}
	}
	if geoDir := geoDirectory(); geoDir != "" {
		if err := secureDirectory(geoDir); err != nil {
			return err
		}
	}
	return nil
}

type tunnelService struct{ owner string }

func (t *tunnelService) Execute(_ []string, requests <-chan svc.ChangeRequest, changes chan<- svc.Status) (bool, uint32) {
	changes <- svc.Status{State: svc.StartPending}
	if err := ensureProgramDataTree(); err != nil {
		return true, 1
	}
	// A previous life of this service may have died with the location mode applied and its
	// snapshot still on disk. Before anything else, the machine goes back to how that snapshot
	// found it.
	restoreAbandonedLocation()
	listener, err := listenIPC(t.owner)
	if err != nil {
		return true, 1
	}
	defer listener.Close()
	s := newSupervisor()
	defer shutdownSupervisor(s)
	serveUntilClosed(listener, t.owner, s)
	current := svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
	changes <- current
	for request := range requests {
		switch request.Cmd {
		case svc.Interrogate:
			changes <- current
		case svc.Stop, svc.Shutdown:
			changes <- svc.Status{State: svc.StopPending}
			return false, 0
		}
	}
	return false, 0
}

func install(owner string) error {
	// Falling back to this process's own account is a last resort, and a poor one: during an
	// install the process is whoever answered the UAC prompt, which on a machine where that is a
	// separate administrator account is the wrong person entirely. The service then runs for
	// somebody who never uses it, and the app - started as the real user - is refused by its own
	// service while `services.msc` cheerfully reports it running. The installer passes the SID it
	// collected from the original user; see Yumi.iss.
	if owner == "" {
		found, err := currentOwner()
		if err != nil {
			return err
		}
		owner = found
	}
	if _, err := windows.StringToSid(owner); err != nil {
		return err
	}
	if err := ensureProgramDataTree(); err != nil {
		return err
	}
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	// An unrestricted service SID puts NT SERVICE\YumiTunnel into the token the service and its
	// worker run with, which is what the kill switch's permit for this executable is scoped to.
	config := mgr.Config{DisplayName: "Yumi VPN tunnel", StartType: mgr.StartAutomatic, Description: "Local tunnel service for Yumi Desktop", SidType: windows.SERVICE_SID_TYPE_UNRESTRICTED}
	service, err := m.OpenService(serviceName)
	if err == nil {
		defer service.Close()
		config.BinaryPathName = `"` + exe + `" --service --owner ` + owner
		if err = service.UpdateConfig(config); err != nil {
			return err
		}
		// A running service keeps the arguments it was started with, so a new owner does not take
		// effect until it is restarted. This path is not only the installer's: the app calls it to
		// re-register the service to the account actually using it, and there the service is
		// certainly running. Stopping a service that was not running is not an error worth having.
		halt(service)
	} else {
		service, err = m.CreateService(serviceName, exe, config, "--service", "--owner", owner)
		if err != nil {
			return err
		}
		defer service.Close()
	}
	// Someone else winning the race to start it is the outcome we wanted anyway.
	if err = service.Start(); err != nil && !errors.Is(err, windows.ERROR_SERVICE_ALREADY_RUNNING) {
		return err
	}
	return nil
}

/** Stops a service and waits for it to admit that it stopped. Best effort throughout. */
func halt(s *mgr.Service) {
	if _, err := s.Control(svc.Stop); err != nil {
		return
	}
	for i := 0; i < 50; i++ {
		status, err := s.Query()
		if err != nil || status.State == svc.Stopped {
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
}

func removeService() error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	var deleteErr error
	if s, err := m.OpenService(serviceName); err == nil {
		halt(s)
		deleteErr = s.Delete()
		_ = s.Close()
	}
	restoreWorkerNetwork()
	restoreAbandonedLocation()
	cleanAdapter()
	return deleteErr
}

func main() {
	isWorker := flag.Bool("worker", false, "")
	isProbe := flag.Bool("probe", false, "")
	isService := flag.Bool("service", false, "")
	installFlag := flag.Bool("install", false, "")
	removeFlag := flag.Bool("uninstall", false, "")
	// Two sinks for one answer. The app runs this as itself and reads stdout; the installer runs
	// it through ExecAsOriginalUser, which reports an exit code and nothing else, so that path
	// needs a file to read afterwards. Neither touches the service or needs any privilege.
	printOwner := flag.Bool("print-owner", false, "")
	ownerOut := flag.String("owner-out", "", "")
	owner := flag.String("owner", "", "")
	flag.Parse()
	var err error
	switch {
	case *isWorker:
		worker()
		return
	case *isProbe:
		err = runProbeCLI()
	case *printOwner || *ownerOut != "":
		sid, e := currentOwner()
		if e != nil {
			err = e
			break
		}
		if *ownerOut != "" {
			err = os.WriteFile(*ownerOut, []byte(sid), 0o644)
		} else {
			_, err = os.Stdout.WriteString(sid)
		}
	case *installFlag:
		err = install(*owner)
	case *removeFlag:
		err = removeService()
	case *isService:
		if _, err = windows.StringToSid(*owner); err == nil {
			err = svc.Run(serviceName, &tunnelService{*owner})
		}
	default:
		os.Exit(2)
	}
	if err != nil {
		os.Stderr.WriteString("Yumi service operation failed.\n")
		os.Exit(1)
	}
}
