//go:build windows

package main

import (
	"encoding/binary"
	"encoding/json"
	"io"
	"os/exec"
	"testing"
	"time"

	"github.com/Microsoft/go-winio"
	"golang.org/x/sys/windows"
)

func TestAuthenticatedStatusOverNamedPipe(t *testing.T) {
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		t.Fatal(err)
	}
	owner := user.User.Sid.String()
	path := `\\.\pipe\YumiTest-` + randomSecret()
	listener, err := winio.ListenPipe(path, &winio.PipeConfig{
		SecurityDescriptor: "D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;0x12019b;;;" + owner + ")",
	})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	s := &supervisor{status: Status{Version: 1, State: "disconnected"}}
	done := make(chan struct{})
	go func() {
		defer close(done)
		c, e := listener.Accept()
		if e == nil {
			serveClient(c, owner, s)
		}
	}()
	timeout := time.Second
	conn, err := winio.DialPipe(path, &timeout)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	request := []byte(`{"version":1,"command":"status"}`)
	if err = binary.Write(conn, binary.BigEndian, uint32(len(request))); err != nil {
		t.Fatal(err)
	}
	if _, err = conn.Write(request); err != nil {
		t.Fatal(err)
	}
	var size uint32
	if err = binary.Read(conn, binary.BigEndian, &size); err != nil {
		t.Fatal(err)
	}
	data := make([]byte, size)
	if _, err = io.ReadFull(conn, data); err != nil {
		t.Fatal(err)
	}
	var status Status
	if json.Unmarshal(data, &status) != nil || status.State != "disconnected" {
		t.Fatal("invalid reply")
	}
	<-done
}

func TestServeClientDecodeFailureIncludesKillSwitchState(t *testing.T) {
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		t.Fatal(err)
	}
	owner := user.User.Sid.String()
	path := `\\.\pipe\YumiTest-` + randomSecret()
	listener, err := winio.ListenPipe(path, &winio.PipeConfig{
		SecurityDescriptor: "D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;0x12019b;;;" + owner + ")",
	})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	s := &supervisor{status: Status{Version: 1, State: "connected"}}
	s.shieldUp.Store(true)
	done := make(chan struct{})
	go func() {
		defer close(done)
		c, e := listener.Accept()
		if e == nil {
			serveClient(c, owner, s)
		}
	}()
	timeout := time.Second
	conn, err := winio.DialPipe(path, &timeout)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	request := []byte(`{"version":99,"command":"status"}`)
	if err = binary.Write(conn, binary.BigEndian, uint32(len(request))); err != nil {
		t.Fatal(err)
	}
	if _, err = conn.Write(request); err != nil {
		t.Fatal(err)
	}
	var size uint32
	if err = binary.Read(conn, binary.BigEndian, &size); err != nil {
		t.Fatal(err)
	}
	data := make([]byte, size)
	if _, err = io.ReadFull(conn, data); err != nil {
		t.Fatal(err)
	}
	var status Status
	if json.Unmarshal(data, &status) != nil || status.State != "failed" || !status.KillSwitch {
		t.Fatalf("expected failed status with KillSwitch=true, got %+v", status)
	}
	<-done
}

func TestIdleStopDoesNotStartWorker(t *testing.T) {
	s := &supervisor{status: Status{Version: 1, State: "disconnected"}}
	s.stop()
	s.stop()
	if s.child != nil || s.snapshot().State != "disconnected" {
		t.Fatal("idle stop changed worker state")
	}
}

func TestDisconnectAlwaysStopsWorkerBumpsGenerationAndReleasesShieldAndLocation(t *testing.T) {
	t.Setenv("ProgramData", t.TempDir())
	guard := &testDNSGuard{}
	done := make(chan struct{})
	cmd := exec.Command("cmd.exe", "/c", "more")
	cmd.SysProcAttr = childProcAttr()
	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	go func() {
		_ = cmd.Wait()
		close(done)
	}()

	req := fixture()
	s := &supervisor{
		generation:  3,
		child:       cmd,
		stdin:       stdin,
		done:        done,
		options:     req.Options,
		lastRequest: &req,
		status:      Status{Version: 1, State: "connected", NodeID: "fixture"},
		shield:      guard,
		sysActive:   true,
		sysCountry:  "DE",
	}
	s.shieldUp.Store(true)

	reply := s.handle(Request{Version: 1, Command: "disconnect"})
	if reply.State != "disconnected" || reply.KillSwitch {
		t.Fatalf("unexpected disconnect status: %+v", reply)
	}
	if s.generation != 4 {
		t.Fatalf("expected generation=4 after disconnect, got %d", s.generation)
	}
	if s.child != nil || s.lastRequest != nil || s.options != nil {
		t.Fatal("disconnect left worker or lastRequest populated")
	}
	if guard.closed != 1 {
		t.Fatalf("expected shield closed once, got %d", guard.closed)
	}
	if s.sysActive {
		t.Fatal("disconnect left location mode active")
	}
}

func TestMonitorDefaultRouteRebindsWhenAdapterChanges(t *testing.T) {
	adapter := "Wi-Fi"
	roamed := make(chan Request, 1)
	done := make(chan struct{})
	defer close(done)
	req := fixture()
	s := &supervisor{
		generation:           11,
		status:               Status{Version: 1, State: "connected"},
		options:              req.Options,
		lastRequest:          &req,
		outboundName:         "Wi-Fi",
		routeMonitorInterval: 10 * time.Millisecond,
		resolveDefaultOutbound: func(string) (string, error) {
			return adapter, nil
		},
		onRouteRoam: func(r Request) error {
			roamed <- r
			return nil
		},
	}
	go s.monitorDefaultRoute(11, done, "Wi-Fi")
	time.Sleep(25 * time.Millisecond)
	select {
	case r := <-roamed:
		t.Fatalf("roamed unexpectedly without adapter change: %+v", r)
	default:
	}

	adapter = "Ethernet"
	select {
	case r := <-roamed:
		if r.NodeID != req.NodeID {
			t.Fatalf("unexpected roamed request: %+v", r)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("monitorDefaultRoute did not trigger reconnect after adapter changed")
	}
}
