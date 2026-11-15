package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"reflect"
	"sync"
	"sync/atomic"
	"time"

	"github.com/GXUser7/yumi-desktop/service/firewall"
)

/*
The supervisor: everything the tunnel service does once it is listening, on every platform.

The platform seam runs underneath this file through a handful of functions, each defined once per
operating system beside it: how connections arrive (listenIPC), who is allowed to make them
(authenticated), where state lives on disk (geoDirectory, workerJournal, secureDirectory), which
adapter the tunnel made (the worker's status frame), how the kill switch is written into the
network stack (firewall.Enable), and how the machine's timezone and location are moved
(applyServerLocation and its restore). Nothing here knows which side it is on.

Everything else — the request handling, the worker lifecycle, the hot switch, the geo databases,
the connection state the app polls once a second — is the same program on every OS, and that is
the point: the parts that could disagree about what a VPN is live in one place.
*/

type supervisor struct {
	mu sync.Mutex
	commands sync.Mutex
	status Status
	child *exec.Cmd
	stdin io.WriteCloser
	done chan struct{}
	generation uint64
	options *Options
	geoEnabled bool
	outboundName string
	// The live worker's adapter identity outlives a firewall session while VPN is switched off.
	// Guarded by mu; unlike the shield's copy it is cleared only with the worker. Windows speaks
	// LUID, unix speaks interface name; each side fills its own half and reads its own half.
	adapterLUID uint64
	adapterName string

	// The kill switch. Its own lock, taken before mu whenever both are needed and never while mu
	// is held, because installing filters is a round trip to the filtering engine and the status
	// poll must not wait behind it. shieldUp mirrors shield != nil for the status, which reads it
	// under mu and so cannot take shieldMu.
	shieldMu sync.Mutex
	shield firewall.Firewall
	shieldLUID uint64
	shieldName string
	shieldLAN bool
	shieldUp atomic.Bool

	// The location mode as applied to the machine, plus the country the current connect carries
	// for it. Guarded by mu; applyLocationMode deduplicates on sysActive+sysCountry so the
	// worker's once-a-second connected frame costs a comparison and nothing more.
	sysActive bool
	sysCountry string
	sysTimezone string
	sysNote string
	pendingCountry string

	// Where the routing databases live, and the download in flight; see geo.go. Guarded by mu.
	geoDir string
	geoUpdating bool
	geoError string
}

func newSupervisor() *supervisor {
	return &supervisor{status: Status{Version: 1, State: "disconnected"}, geoDir: geoDirectory()}
}

func (s *supervisor) snapshot() Status {
    s.mu.Lock()
    status:=s.status
    status.GeoUpdating=s.geoUpdating;status.GeoError=s.geoError
    dir:=s.geoDir
    status.SysTimezone=s.sysTimezone;status.SysNote=s.sysNote
    s.mu.Unlock()
    geo:=inspectGeo(dir)
    status.GeoReady=geo.Ready;status.GeoBytes=geo.Bytes;status.GeoUpdatedAt=geo.UpdatedAt
    status.KillSwitch=s.shieldUp.Load()
    status.TunLUID=0
    status.TunName=""
    status.Adapters=nil
    return status
}

// Keep the adapter identity even when the shield is down; a resumed worker already owns its
// routes and must be permitted immediately, without waiting for another periodic status frame.
func (s *supervisor) recordWorkerStatus(generation uint64, status Status) firewall.TunRef {
    s.mu.Lock();defer s.mu.Unlock()
    if s.generation!=generation { return firewall.TunRef{} }
    ref := firewall.TunRef{LUID: status.TunLUID, Name: status.TunName}
    status.TunLUID=0
    status.TunName=""
    s.status=status
    if ref.LUID!=0 { s.adapterLUID=ref.LUID }
    if ref.Name!="" { s.adapterName=ref.Name }
    return ref
}

/*
Raises the kill switch, or moves it onto a new adapter.

The new filters go in before the old ones come out, so there is no instant without a kill switch;
while both are installed the old one still blocks an adapter it does not know, which costs a few
milliseconds of nothing rather than a few milliseconds of leak.
*/
func (s *supervisor) raiseShield(ref firewall.TunRef, lan bool) error {
    s.shieldMu.Lock();defer s.shieldMu.Unlock()
    return s.raiseShieldLocked(ref,lan)
}

func (s *supervisor) raiseShieldLocked(ref firewall.TunRef, lan bool) error {
    if s.shield!=nil && s.shieldLUID==ref.LUID && s.shieldName==ref.Name && s.shieldLAN==lan { return nil }
    next,err:=firewall.Enable(ref,lan)
    if err!=nil { return err }
    if s.shield!=nil { s.shield.Close() }
    s.shield,s.shieldLUID,s.shieldName,s.shieldLAN=next,ref.LUID,ref.Name,lan
    s.shieldUp.Store(true)
    return nil
}

func (s *supervisor) releaseShield() {
    s.shieldMu.Lock();defer s.shieldMu.Unlock()
    if s.shield!=nil { s.shield.Close() }
    s.shield=nil;s.shieldLUID=0;s.shieldName=""
    s.shieldUp.Store(false)
}

/*
The worker's adapter, as soon as the worker reports it: the kill switch is moved onto it if it is
up and this is still the current tunnel. On Windows the wintun adapter keeps its GUID-derived
identity across runs, so this is normally the same interface as last time and nothing changes; on
unix the core names the device itself and the report is how the shield learns it.
*/
func (s *supervisor) adoptAdapter(generation uint64, ref firewall.TunRef) {
    s.shieldMu.Lock()
    s.mu.Lock();current:=s.generation==generation;s.mu.Unlock()
    // Keep the check and replacement under the same shield lock. An explicit disconnect may
    // otherwise lower the shield after this check, only for this status frame to raise it again.
    if s.shield==nil || (s.shieldLUID==ref.LUID && s.shieldName==ref.Name) || !current { s.shieldMu.Unlock();return }
    // A failure leaves the old filters in place, which block this adapter as well: the tunnel then
    // carries nothing rather than something leaking around it. It is written down for whoever
    // reads the journal.
    err:=s.raiseShieldLocked(ref,s.shieldLAN)
    s.shieldMu.Unlock()
    if err!=nil { journalLine("kill switch could not follow the adapter: "+detailOf(err)) }
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
    s.generation++;s.child=nil;s.stdin=nil;s.done=nil;s.options=nil;s.adapterLUID=0;s.adapterName=""
    s.status=Status{Version:1,State:"disconnecting"};s.mu.Unlock()
    if input!=nil { _=input.Close() }
    if child!=nil { select {case <-done:case <-time.After(8*time.Second):_=child.Process.Kill();<-done} }
    s.releaseLocationMode()
    s.mu.Lock();s.status=Status{Version:1,State:"disconnected"};s.mu.Unlock()
}

func (s *supervisor) connect(r Request) error {
	s.mu.Lock()
	dir := s.geoDir
	s.mu.Unlock()
	geo := r.Options.BypassRussia && inspectGeo(dir).Ready
	outbound, outboundErr := outboundInterface(r.Options.OutboundAdapter)
	if outboundErr != nil {
		if r.Options.OutboundAdapter != "" {
			return errorWithDetail{"adapter_unavailable", outboundErr}
		}
		outbound = ""
	}
	s.mu.Lock()
	hot := s.child != nil && reflect.DeepEqual(s.options, r.Options) && s.geoEnabled == geo && s.outboundName == outbound &&
		(s.status.State == "connected" || s.status.State == "disconnected")
	previous, currentInput, workerRef := s.status, s.stdin, firewall.TunRef{LUID: s.adapterLUID, Name: s.adapterName}
	// The country rides outside Options, so a hot switch to a server across a border changes
	// nothing the DeepEqual above looks at; the mode follows it live below.
	s.pendingCountry = r.ServerCountry
	s.mu.Unlock()
	if hot {
		if r.Options.KillSwitch {
			if err := s.raiseShield(workerRef, r.Options.BypassLAN); err != nil {
				return errorWithDetail{"kill_switch_failed", err}
			}
		} else {
			s.releaseShield()
		}
		initial := switchingStatus(previous,r.NodeID)
		config := buildConfig(r, initial.ProbePort, initial.ProbeUser, initial.ProbePassword, geo, outbound)
		// A live switch stays connected with the old server until the worker atomically
		// publishes the new one. Do not reset counters, the timer or the UI's rate history.
		if previous.State != "connected" { s.mu.Lock();s.status=initial;s.mu.Unlock() }
		if err := json.NewEncoder(currentInput).Encode(obj{"config": config, "status": initial}); err != nil {
			return errors.New("worker_unavailable")
		}
		// The worker has accepted the new server while staying connected, so the mode can follow
		// right now instead of waiting for a frame that will not change the verdict.
		if r.Options.MatchLocation { s.applyLocationMode() } else { s.releaseLocationMode() }
		return nil
	}
	s.stop()
	port, err := freePort()
	if err != nil {
		return errors.New("probe_port_unavailable")
	}
	// The kill switch goes up before the worker starts and stays up through whatever happens to
	// it; only a disconnect lowers it. Turning the setting off takes effect on the next connect.
	if r.Options.KillSwitch {
		s.shieldMu.Lock()
		ref := firewall.TunRef{LUID: s.shieldLUID, Name: s.shieldName}
		s.shieldMu.Unlock()
		if err := s.raiseShield(ref, r.Options.BypassLAN); err != nil {
			return errorWithDetail{"kill_switch_failed", err}
		}
	} else {
		s.releaseShield()
	}
	initial := Status{Version: 1, State: "connecting", NodeID: r.NodeID, ProbePort: port, ProbeUser: randomSecret(), ProbePassword: randomSecret()}
	config := buildConfig(r, port, initial.ProbeUser, initial.ProbePassword, geo, outbound)
	exe, err := os.Executable()
	if err != nil {
		return errors.New("worker_unavailable")
	}
	child := exec.Command(exe, "--worker")
	child.SysProcAttr = childProcAttr()
	// Where the core looks for geoip.dat and geosite.dat; the name is the core's own
	// (common/platform: xray.location.asset, spelt as an environment variable).
	if dir != "" {
		child.Env = append(os.Environ(), "XRAY_LOCATION_ASSET="+dir)
	}
	// Everything the worker says on stderr - a Go panic, a driver refusing to load, the core's
	// own complaints - used to go to io.Discard. That is the one thing a tunnel that will not
	// start must not do: the app can only report "could not start", and the sentence that says
	// why existed for a moment and was thrown away. Failing to open the journal is not worth
	// failing a connection over, so a broken log falls back to discarding as before.
	child.Stderr = workerJournal()
	input, err := child.StdinPipe()
	if err != nil {
		return errors.New("worker_unavailable")
	}
	output, err := child.StdoutPipe()
	if err != nil {
		input.Close()
		return errors.New("worker_unavailable")
	}
	if err = child.Start(); err != nil {
		input.Close()
		output.Close()
		return errors.New("worker_unavailable")
	}
	done := make(chan struct{})
	s.mu.Lock()
	s.child = child
	s.stdin = input
	s.done = done
	s.status = initial
	s.options = r.Options
	s.geoEnabled = geo
	s.outboundName = outbound
	s.pendingCountry = r.ServerCountry
	generation := s.generation
	s.mu.Unlock()
	go func() {
		scanner := bufio.NewScanner(output)
		scanner.Buffer(make([]byte, 4096), maxFrame)
		for scanner.Scan() {
			var status Status
			if json.Unmarshal(scanner.Bytes(), &status) == nil && status.Version == 1 {
				if ref := s.recordWorkerStatus(generation, status); ref.LUID != 0 || ref.Name != "" {
					s.adoptAdapter(generation, ref)
				}
				// The first frame that says the tunnel carries traffic is when the machine may
				// start looking like it sits where the server does. The mode's own
				// deduplication turns every later frame into a comparison.
				if status.State == "connected" {
					go func() { s.commands.Lock(); s.applyLocationMode(); s.commands.Unlock() }()
				}
			}
		}
		_ = child.Wait()
		s.mu.Lock()
		current := s.generation == generation
		s.mu.Unlock()
		if current {
			// A worker that died on its own leaves the machine moved; put it back. A deliberate
			// stop has already restored by the time the generation moves.
			s.releaseLocationMode()
		}
		s.mu.Lock()
		if s.generation == generation {
			s.adapterLUID = 0
			s.adapterName = ""
			if s.status.State != "failed" {
				s.status = Status{Version: 1, State: "failed", Error: "worker_stopped"}
			}
		}
		s.mu.Unlock()
		close(done)
	}()
	err = json.NewEncoder(input).Encode(obj{"config": config, "status": initial})
	if err != nil {
		s.stop()
		return errors.New("worker_unavailable")
	}
	return nil
}

func (s *supervisor) handle(r Request) Status {
	if r.Command == "status" {
		return s.snapshot()
	}
	if r.Command == "adapters" {
		s.mu.Lock()
		status := s.status
		s.mu.Unlock()
		status.Adapters = adapterList()
		status.TunLUID = 0
		status.TunName = ""
		status.KillSwitch = s.shieldUp.Load()
		return status
	}
	s.commands.Lock()
	defer s.commands.Unlock()
	switch r.Command {
	case "disconnect":
		s.mu.Lock()
		input := s.stdin
		running := s.child != nil
		already := s.status.State == "disconnected"
		s.mu.Unlock()
		if running && !already {
			s.mu.Lock();s.status.State="disconnecting";s.mu.Unlock()
			if err := json.NewEncoder(input).Encode(obj{"disconnect": true}); err != nil { s.stop() }
		} else if !running {
			s.stop()
		}
		s.releaseShield()
		s.releaseLocationMode()
	case "geo_update":
		s.updateGeo()
	case "connect":
		if err := s.connect(r); err != nil {
			failed := Status{Version: 1, State: "failed", Error: err.Error(), KillSwitch: s.shieldUp.Load()}
			var detailed errorWithDetail
			if errors.As(err, &detailed) {
				failed.Error = detailed.code
				failed.Detail = detailOf(detailed.cause)
			}
			s.mu.Lock()
			s.status = failed
			s.mu.Unlock()
			return s.snapshot()
		}
	}
	return s.snapshot()
}

/*
Puts the machine where the current server is, if the location mode is on and has not got there
yet. Serialized by s.commands like every other change to the system, which is why the scanner
goroutine hands this to one: a disconnect arriving mid-apply must win, not queue behind it.
*/
func (s *supervisor) applyLocationMode() {
    s.mu.Lock()
    wanted := s.options != nil && s.options.MatchLocation
    country := s.pendingCountry
    s.mu.Unlock()
    if !wanted { return }
    s.mu.Lock()
    done := s.sysActive && s.sysCountry == country
    s.mu.Unlock()
    if done { return }
    applied, err := applyServerLocation(country)
    s.mu.Lock()
    s.sysActive = true; s.sysCountry = country
    if err != nil { s.sysNote = detailOf(err) } else { s.sysNote = ""; s.sysTimezone = applied }
    s.mu.Unlock()
    if err != nil { journalLine("server location: " + detailOf(err)) }
}

// Gives the machine back to itself. Idempotent and cheap when the mode was never applied, which
// is what makes it safe to call from every teardown path there is.
func (s *supervisor) releaseLocationMode() {
    s.mu.Lock()
    active := s.sysActive
    s.sysActive = false; s.sysCountry = ""; s.sysTimezone = ""; s.sysNote = ""
    s.mu.Unlock()
    if !active { return }
    if err := restoreServerLocation(); err != nil { journalLine("server location restore: " + detailOf(err)) }
}

/** Where the worker's stderr goes, capped and reopened each time. See the platform journals. */
func journalLine(line string) {
    if w,ok:=workerJournal().(*os.File);ok { _,_=w.WriteString(line+"\n");_=w.Close() }
}

/** A failure code for the app, carrying the sentence the library wrote for the detail line. */
type errorWithDetail struct{ code string; cause error }
func (e errorWithDetail) Error() string { return e.code }

/*
One client connection: authenticated, one request frame in, one status frame out. The deadline
covers the whole exchange, so a client that stops answering cannot hold the handler.
*/
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

// The accept loop every platform runs once its listener is up.
func serveUntilClosed(listener net.Listener, owner string, s *supervisor) {
    go func(){for{conn,err:=listener.Accept();if err!=nil{return};go serveClient(conn,owner,s)}}()
}

// The teardown every platform runs when the service ends: stop the tunnel, drop the shield, give
// the machine back. Serialized, because a shutdown racing a connect must win.
func shutdownSupervisor(s *supervisor) {
    s.commands.Lock();defer s.commands.Unlock()
    s.stop();s.releaseShield()
}

// childProcAttr is per-OS: Windows keeps a console off the worker's desktop session, unix needs
// nothing and answers nil.
