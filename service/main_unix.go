//go:build unix

package main

import (
	"errors"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"syscall"
	"time"

	"github.com/GXUser7/yumi-desktop/service/firewall"
)

const serviceName = "app.yumi.desktop.tunnel"

// The tunnel's own adapter. Linux lets a device carry any name; macOS requires utunN, so the
// tunnel claims a high unit number that nothing else is likely to take. The kill switch and the
// route picker both read this, which is why the two spellings never meet outside this function.
func tunAdapterName() string {
	if isDarwin() {
		return "utun69"
	}
	return "Yumi"
}

func isDarwin() bool { return runtime.GOOS == "darwin" }

// A worker needs no console games on unix; the indirection exists because syscall.SysProcAttr
// differs per platform and the shared supervisor wants one answer.
func childProcAttr() *syscall.SysProcAttr { return nil }

// Where the service keeps what belongs to it: journals, geo databases, the crash snapshot.
// Only the daemon (root) writes here; the app never names a path, it only asks. The override
// exists for the tests and for running the daemon in a sandbox that owns its own tree.
func systemStateDirectory() string {
	if dir := os.Getenv("YUMI_STATE_DIR"); dir != "" {
		return dir
	}
	if isDarwin() {
		return "/Library/Application Support/Yumi Desktop"
	}
	return "/var/lib/yumi-desktop"
}

func dataRoot() string { return systemStateDirectory() }

func geoDirectory() string {
	root := systemStateDirectory()
	if root == "" {
		return ""
	}
	return filepath.Join(root, "geo")
}

// The journal sits in the state directory for the same reason it sits under ProgramData on
// Windows: a program directory that changes under a running install is the kind of thing
// installers and package managers both take badly.
func workerJournal() *os.File {
	journalMu.Lock()
	defer journalMu.Unlock()
	root := systemStateDirectory()
	if root == "" {
		return nil
	}
	if os.MkdirAll(root, 0o755) != nil {
		return nil
	}
	if dirInfo, err := os.Lstat(root); err != nil || dirInfo.Mode()&os.ModeSymlink != 0 || !dirInfo.IsDir() {
		return nil
	}
	path := filepath.Join(root, "worker.log")
	if info, err := os.Lstat(path); err == nil {
		if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
			return nil
		}
		if info.Size() > 1<<20 {
			_ = os.Rename(path, path+".1")
		}
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND|syscall.O_NOFOLLOW, 0o644)
	if err != nil {
		return nil
	}
	_, _ = file.WriteString("\n--- " + time.Now().Format(time.RFC3339) + " ---\n")
	return file
}

// Only the daemon and the administrators may write what the core parses; on unix that is a
// directory the daemon created as root, which is exactly that.
func secureDirectory(dir string) error {
	if dir == "" {
		return errors.New("empty directory")
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	info, err := os.Lstat(dir)
	if err != nil {
		return err
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.IsDir() {
		return errors.New("directory is not regular")
	}
	return nil
}

/** This process's own account, as a uid in decimal — the unix answer to the owner SID. */
func currentOwner() (string, error) {
	return strconv.Itoa(os.Getuid()), nil
}

func socketPath() string {
	if isDarwin() {
		return "/var/run/yumi/tunnel.sock"
	}
	return "/run/yumi/tunnel.sock"
}

/*
The unix socket stands in for the named pipe. It lives in a root-owned directory, is created
fresh on every start — /run and /var/run are tmpfs on Linux, so yesterday's socket is gone with
the boot — and is handed to the owner, whose uid the peer check below enforces properly.
*/
func listenIPC(owner string) (net.Listener, error) {
	uid, err := strconv.Atoi(owner)
	if err != nil {
		return nil, fmt.Errorf("bad owner uid %q", owner)
	}
	path := socketPath()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, err
	}
	// A socket that answers belongs to a daemon that is already running; taking it away would
	// leave that daemon deaf. Fail loudly instead.
	if conn, err := net.DialTimeout("unix", path, 500*time.Millisecond); err == nil {
		conn.Close()
		return nil, errors.New("another tunnel service is already listening")
	} else {
		_ = os.Remove(path)
	}
	listener, err := net.Listen("unix", path)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(path, 0o660); err != nil {
		listener.Close()
		return nil, err
	}
	if err := os.Chown(path, uid, -1); err != nil {
		listener.Close()
		return nil, err
	}
	return listener, nil
}

/** The one rule: a connection from the owner's uid, and from nobody else. */
func authenticated(conn net.Conn, owner string) bool {
	uid, err := strconv.Atoi(owner)
	if err != nil {
		return false
	}
	peer, ok := peerUID(conn)
	return ok && peer == uid
}

func runDaemon(owner string) error {
	if _, err := strconv.Atoi(owner); err != nil {
		found, err := currentOwner()
		if err != nil {
			return err
		}
		owner = found
	}
	// A previous life of this daemon may have died with the location mode applied and its
	// snapshot still on disk. Before anything else, the machine goes back to how that snapshot
	// found it.
	listener, err := listenIPC(owner)
	if err != nil {
		return err
	}
	defer listener.Close()
	if err := firewall.CleanupLeftovers(); err != nil {
		return err
	}
	restoreWorkerNetwork()
	restoreAbandonedLocation()
	s := newSupervisor()
	defer shutdownSupervisor(s)
	serveUntilClosed(listener, owner, s)
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	<-signals
	return nil
}

/*
--install registers the daemon with the machine's own service manager and starts it, the way the
Windows build registers with the service control manager. Both managers get the owner in the
arguments, so the socket belongs to whoever will actually use the tunnel and not to whoever
answered the elevation prompt — the app passes the uid it is running as.
*/
func install(owner string) error {
	if os.Geteuid() != 0 {
		return errors.New("install requires administrator privileges")
	}
	if owner == "" {
		found, err := currentOwner()
		if err != nil {
			return err
		}
		owner = found
	}
	if uid, err := strconv.ParseUint(owner, 10, 32); err != nil || uid > 4294967294 {
		return fmt.Errorf("bad owner uid %q", owner)
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	helper := installedServicePath()
	if err := stageServiceBinary(exe, helper); err != nil {
		return err
	}
	exe = helper
	if isDarwin() {
		return installLaunchd(exe, owner)
	}
	return installSystemd(exe, owner)
}

func installSystemd(exe, owner string) error {
	const unit = "yumi-tunnel.service"
	body := fmt.Sprintf(`[Unit]
Description=Yumi VPN tunnel
Documentation=https://github.com/GXUser7/yumi-desktop
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=%q --service --owner %s
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
`, exe, owner)
	path := "/etc/systemd/system/" + unit
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		return err
	}
	if out, err := runSystemCommand("systemctl", "daemon-reload"); err != nil {
		return fmt.Errorf("daemon-reload: %s", out)
	}
	// A restart covers the update path, where the unit already existed with an old owner in it.
	if out, err := runSystemCommand("systemctl", "restart", unit); err != nil {
		return fmt.Errorf("restart: %s", out)
	}
	if out, err := runSystemCommand("systemctl", "enable", unit); err != nil {
		return fmt.Errorf("enable: %s", out)
	}
	return nil
}

func installLaunchd(exe, owner string) error {
	body := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>%s</string>
    <key>ProgramArguments</key>
    <array>
        <string>%s</string>
        <string>--service</string>
        <string>--owner</string>
        <string>%s</string>
    </array>
    <key>KeepAlive</key><true/>
    <key>RunAtLoad</key><true/>
</dict>
</plist>
`, serviceName, exe, owner)
	path := "/Library/LaunchDaemons/" + serviceName + ".plist"
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		return err
	}
	// Bootout first so an update replaces a running daemon instead of colliding with it; the
	// error when nothing was loaded is the good outcome and is ignored.
	_, _ = runSystemCommand("launchctl", "bootout", "system/"+serviceName)
	if out, err := runSystemCommand("launchctl", "bootstrap", "system", path); err != nil {
		return fmt.Errorf("bootstrap: %s", out)
	}
	return nil
}

func removeService() error {
	var err error
	if isDarwin() {
		_, _ = runSystemCommand("launchctl", "bootout", "system/"+serviceName)
		if e := os.Remove("/Library/LaunchDaemons/" + serviceName + ".plist"); e != nil && !errors.Is(e, os.ErrNotExist) {
			err = errors.Join(err, e)
		}
	} else {
		const unit = "yumi-tunnel.service"
		_, _ = runSystemCommand("systemctl", "disable", "--now", unit)
		if e := os.Remove("/etc/systemd/system/" + unit); e != nil && !errors.Is(e, os.ErrNotExist) {
			err = errors.Join(err, e)
		} else {
			_, _ = runSystemCommand("systemctl", "daemon-reload")
		}
	}
	err = errors.Join(err, firewall.CleanupLeftovers())
	restoreWorkerNetwork()
	restoreAbandonedLocation()
	if helper := installedServicePath(); helper != "" {
		if e := os.Remove(helper); e != nil && !errors.Is(e, os.ErrNotExist) {
			err = errors.Join(err, e)
		}
	}
	return err
}

func main() {
	isWorker := flag.Bool("worker", false, "")
	isService := flag.Bool("service", false, "")
	installFlag := flag.Bool("install", false, "")
	removeFlag := flag.Bool("uninstall", false, "")
	printOwner := flag.Bool("print-owner", false, "")
	ownerOut := flag.String("owner-out", "", "")
	owner := flag.String("owner", "", "")
	flag.Parse()
	var err error
	switch {
	case *isWorker:
		worker()
		return
	case *printOwner || *ownerOut != "":
		id, e := currentOwner()
		if e != nil {
			err = e
			break
		}
		if *ownerOut != "" {
			err = os.WriteFile(*ownerOut, []byte(id), 0o644)
		} else {
			_, err = os.Stdout.WriteString(id)
		}
	case *installFlag:
		err = install(*owner)
	case *removeFlag:
		err = removeService()
	case *isService:
		err = runDaemon(*owner)
	default:
		os.Exit(2)
	}
	if err != nil {
		os.Stderr.WriteString("Yumi service operation failed.\n")
		os.Exit(1)
	}
}
