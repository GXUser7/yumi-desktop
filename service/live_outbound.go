package main

import (
	"context"
	"errors"
	"sync"

	"github.com/xtls/xray-core/common"
	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/serial"
	xoutbound "github.com/xtls/xray-core/features/outbound"
	"github.com/xtls/xray-core/transport"
)

// liveOutbound keeps the TUN and the direct outbound alive when the chosen server changes.
// Switching servers cancels VPN flows so persistent browser/DoH connections cannot retain the
// previous exit country. Direct leases (including the VPN-off forwarder) survive every switch.
type liveOutbound struct {
	mu      sync.Mutex
	current *outboundLease
	leases  map[*outboundLease]struct{}
	closed  bool
}

type outboundLease struct {
	handler  xoutbound.Handler
	direct   bool
	retired  bool
	sessions map[*context.CancelFunc]struct{}
	close    sync.Once
}

func newLiveOutbound(handler xoutbound.Handler) *liveOutbound {
	lease := &outboundLease{handler: handler, sessions: make(map[*context.CancelFunc]struct{})}
	return &liveOutbound{current: lease, leases: map[*outboundLease]struct{}{lease: {}}}
}

func (l *outboundLease) shutdown() { l.close.Do(func() { _ = l.handler.Close() }) }

func (l *liveOutbound) Tag() string  { return "proxy-node" }
func (l *liveOutbound) Start() error { return l.current.handler.Start() }
func (l *liveOutbound) SenderSettings() *serial.TypedMessage {
	l.mu.Lock()
	handler := l.current.handler
	l.mu.Unlock()
	return handler.SenderSettings()
}
func (l *liveOutbound) ProxySettings() *serial.TypedMessage {
	l.mu.Lock()
	handler := l.current.handler
	l.mu.Unlock()
	return handler.ProxySettings()
}

func (l *liveOutbound) Dispatch(ctx context.Context, link *transport.Link) {
	ctx, cancel := context.WithCancel(ctx)
	l.mu.Lock()
	if l.closed {
		l.mu.Unlock()
		cancel()
		common.Interrupt(link.Reader)
		common.Interrupt(link.Writer)
		return
	}
	lease := l.current
	lease.sessions[&cancel] = struct{}{}
	l.mu.Unlock()
	// Mux sessions may continue their pipe-copy goroutine after Dispatch observes cancellation.
	// Interrupt both original ends as well, so an explicit VPN disconnect terminates the flow.
	var interruptOnce sync.Once
	interrupt := func() {
		interruptOnce.Do(func() {
			common.Interrupt(link.Reader)
			common.Interrupt(link.Writer)
		})
	}
	stopInterrupt := context.AfterFunc(ctx, interrupt)
	defer func() {
		stopInterrupt()
		if ctx.Err() != nil {
			interrupt()
		}
		cancel()
		l.mu.Lock()
		delete(lease.sessions, &cancel)
		closeLease := lease.retired && len(lease.sessions) == 0
		if closeLease {
			delete(l.leases, lease)
		}
		l.mu.Unlock()
		if closeLease {
			lease.shutdown()
		}
	}()
	// Xray mux deliberately dispatches a *pipe.Reader asynchronously. Its documented alternate
	// reader path waits for the mux session to finish; use that path so this lease represents
	// the actual flow lifetime, rather than just the time spent scheduling it.
	leasedLink := *link
	leasedLink.Reader = leaseReader{link.Reader}
	lease.handler.Dispatch(ctx, &leasedLink)
}

type leaseReader struct{ buf.Reader }

func (r leaseReader) Close() error { return common.Close(r.Reader) }
func (r leaseReader) Interrupt()   { common.Interrupt(r.Reader) }

func (l *liveOutbound) replace(next xoutbound.Handler, direct bool) error {
	// Construct and start before publishing; a bad candidate must not disturb the old server.
	if err := next.Start(); err != nil {
		_ = next.Close()
		return err
	}
	lease := &outboundLease{handler: next, direct: direct, sessions: make(map[*context.CancelFunc]struct{})}
	var closing []*outboundLease
	var cancelling []context.CancelFunc
	l.mu.Lock()
	if l.closed {
		l.mu.Unlock()
		lease.shutdown()
		return errors.New("outbound closed")
	}
	l.current.retired = true
	if group, ok := l.current.handler.(interface{ Retire() }); ok {
		group.Retire()
	}
	l.current = lease
	l.leases[lease] = struct{}{}
	for old := range l.leases {
		if old.retired && len(old.sessions) == 0 {
			delete(l.leases, old)
			closing = append(closing, old)
		}
		if old.retired && !old.direct {
			for cancel := range old.sessions {
				cancelling = append(cancelling, *cancel)
			}
		}
	}
	l.mu.Unlock()
	for _, cancel := range cancelling {
		cancel()
	}
	for _, old := range closing {
		old.shutdown()
	}
	return nil
}

func (l *liveOutbound) Close() error {
	var leases []*outboundLease
	var cancelling []context.CancelFunc
	l.mu.Lock()
	l.closed = true
	for lease := range l.leases {
		lease.retired = true
		leases = append(leases, lease)
		for cancel := range lease.sessions {
			cancelling = append(cancelling, *cancel)
		}
	}
	l.leases = make(map[*outboundLease]struct{})
	l.mu.Unlock()
	for _, cancel := range cancelling {
		cancel()
	}
	for _, lease := range leases {
		lease.shutdown()
	}
	return nil
}
