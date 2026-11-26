package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtls/xray-core/common"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/net/cnc"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/core"
	xoutbound "github.com/xtls/xray-core/features/outbound"
	confserial "github.com/xtls/xray-core/infra/conf/serial"
	"github.com/xtls/xray-core/transport"
	"github.com/xtls/xray-core/transport/pipe"
)

type OutboundGroup struct {
	Members         []map[string]any        `json:"members"`
	Strategy        string                  `json:"strategy"`
	MaxDelayMillis  int                     `json:"maxDelayMillis"`
	MemberFragments map[string]FragmentSpec `json:"memberFragments,omitempty"`
}

// One selected row can contain several provider-ordered routes. These are measured with a real
// HTTP request THROUGH each outbound, not by its open TCP port, matching mobile GroupSelector.
// Flows keep the handler they started with when the chosen group member changes.
type groupOutbound struct {
	members     []xoutbound.Handler
	spec        OutboundGroup
	selected    atomic.Int32
	latency     atomic.Int32
	cancel      context.CancelFunc
	done        chan struct{}
	close       sync.Once
	refreshMu   sync.Mutex
	probeURL    string
	initialWait time.Duration
}

func pickGroupMember(spec OutboundGroup, delays map[int]int) int {
	fastest, best := -1, int(^uint(0)>>1)
	for i := range spec.Members {
		delay, ok := delays[i]
		if !ok || delay <= 0 {
			continue
		}
		if spec.Strategy == "cost" && (spec.MaxDelayMillis == 0 || delay <= spec.MaxDelayMillis) {
			return i
		}
		if delay < best {
			fastest, best = i, delay
		}
	}
	return fastest
}

func prepareGroupOutbound(instance *core.Instance, spec *OutboundGroup, first xoutbound.Handler) (xoutbound.Handler, error) {
	if spec == nil {
		return first, nil
	}
	group := &groupOutbound{spec: *spec, probeURL: "https://cp.cloudflare.com/generate_204"}
	group.members = append(group.members, first)
	for _, outbound := range spec.Members[1:] {
		member := cloneJSONMap(outbound)
		if member == nil {
			member = obj{}
		}
		origTag, _ := outbound["tag"].(string)
		outbounds := []obj{member}
		if spec.MemberFragments != nil {
			if frag, ok := spec.MemberFragments[origTag]; ok {
				fragTag := fragmentTag(frag)
				attachFragmentDialerProxy(member, fragTag)
				outbounds = append(outbounds, buildFragmentOutbound(fragTag, frag))
			}
		}
		member["tag"] = "proxy-node"
		data, err := json.Marshal(obj{"outbounds": outbounds})
		if err != nil {
			group.Close()
			return nil, err
		}
		config, err := confserial.LoadJSONConfig(bytes.NewReader(data))
		if err != nil {
			group.Close()
			return nil, err
		}
		if err := ensureFragmentOutbounds(instance, config); err != nil {
			group.Close()
			return nil, err
		}
		handler, err := prepareLiveOutbound(instance, config)
		if err != nil {
			group.Close()
			return nil, err
		}
		group.members = append(group.members, handler)
	}
	return group, nil
}

func (g *groupOutbound) Tag() string { return "proxy-node" }
func (g *groupOutbound) SenderSettings() *serial.TypedMessage {
	return g.members[int(g.selected.Load())].SenderSettings()
}
func (g *groupOutbound) ProxySettings() *serial.TypedMessage {
	return g.members[int(g.selected.Load())].ProxySettings()
}
func (g *groupOutbound) Dispatch(ctx context.Context, link *transport.Link) {
	g.members[int(g.selected.Load())].Dispatch(ctx, link)
}
func (g *groupOutbound) Start() error {
	for _, member := range g.members {
		if err := member.Start(); err != nil {
			return err
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	g.cancel, g.done = cancel, make(chan struct{})
	initialDone := make(chan struct{})
	go func() {
		defer close(initialDone)
		g.refresh(ctx)
	}()
	wait := 4 * time.Second
	if g.initialWait > 0 {
		wait = g.initialWait
	}
	timer := time.NewTimer(wait)
	select {
	case <-initialDone:
		timer.Stop()
	case <-timer.C:
	}
	go func() {
		defer close(g.done)
		<-initialDone
		tick := time.NewTicker(15 * time.Second)
		defer tick.Stop()
		looked := time.Now()
		for {
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
			}
			index := int(g.selected.Load())
			delay := g.measure(ctx, g.members[index])
			if delay <= 0 || time.Since(looked) >= 120*time.Second {
				g.refresh(ctx)
				looked = time.Now()
			} else {
				g.latency.Store(int32(delay))
			}
		}
	}()
	return nil
}
func (g *groupOutbound) Retire() {
	if g.cancel != nil {
		g.cancel()
	}
}
func (g *groupOutbound) Close() error {
	g.Retire()
	if g.done != nil {
		<-g.done
	}
	var err error
	g.close.Do(func() {
		for _, member := range g.members {
			err = errors.Join(err, member.Close())
		}
	})
	return err
}
func (g *groupOutbound) refresh(ctx context.Context) {
	g.refreshMu.Lock()
	defer g.refreshMu.Unlock()
	var wg sync.WaitGroup
	var mu sync.Mutex
	delays := map[int]int{}
	slots := make(chan struct{}, 8)
	for i, member := range g.members {
		wg.Add(1)
		go func(index int, handler xoutbound.Handler) {
			defer wg.Done()
			select {
			case slots <- struct{}{}:
			case <-ctx.Done():
				return
			}
			defer func() { <-slots }()
			if delay := g.measure(ctx, handler); delay > 0 {
				mu.Lock()
				delays[index] = delay
				mu.Unlock()
			}
		}(i, member)
	}
	wg.Wait()
	if ctx.Err() != nil {
		return
	}
	if pick := pickGroupMember(g.spec, delays); pick >= 0 {
		g.selected.Store(int32(pick))
		g.latency.Store(int32(delays[pick]))
	} else {
		g.latency.Store(0)
	}
}

func (g *groupOutbound) measure(parent context.Context, handler xoutbound.Handler) int {
	ctx, cancel := context.WithTimeout(parent, 5*time.Second)
	defer cancel()
	transport := &http.Transport{TLSHandshakeTimeout: 5 * time.Second, DisableKeepAlives: true,
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			dest, err := xnet.ParseDestination(network + ":" + address)
			if err != nil {
				return nil, err
			}
			return dialHandler(ctx, handler, dest), nil
		}}
	defer transport.CloseIdleConnections()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, g.probeURL, nil)
	if err != nil {
		return 0
	}
	started := time.Now()
	response, err := (&http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}).Do(request)
	if err != nil {
		return 0
	}
	response.Body.Close()
	if response.StatusCode != http.StatusNoContent {
		return 0
	}
	return int(time.Since(started).Milliseconds()) + 1
}

func dialHandler(parent context.Context, handler xoutbound.Handler, dest xnet.Destination) net.Conn {
	ctx, cancel := context.WithCancel(parent)
	ctx = session.ContextWithContent(ctx, &session.Content{})
	ctx = session.ContextWithOutbounds(ctx, []*session.Outbound{{Target: dest, Tag: handler.Tag()}})
	upReader, upWriter := pipe.New(pipe.WithSizeLimit(64 * 1024))
	downReader, downWriter := pipe.New(pipe.WithSizeLimit(64 * 1024))
	stop := context.AfterFunc(ctx, func() {
		common.Interrupt(upReader)
		common.Interrupt(downReader)
		common.Interrupt(upWriter)
		common.Interrupt(downWriter)
	})
	conn := cnc.NewConnection(cnc.ConnectionInputMulti(upWriter), cnc.ConnectionOutputMulti(downReader),
		cnc.ConnectionOnClose(closeFunc(func() error { stop(); cancel(); return nil })))
	go handler.Dispatch(ctx, &transport.Link{Reader: leaseReader{upReader}, Writer: downWriter})
	return conn
}

type closeFunc func() error

func (f closeFunc) Close() error { return f() }

func (l *liveOutbound) groupStatus(status Status) Status {
	l.mu.Lock()
	defer l.mu.Unlock()
	status.GroupMember, status.GroupMembers, status.GroupLatency = 0, 0, 0
	if group, ok := l.current.handler.(*groupOutbound); ok {
		status.GroupMember = int(group.selected.Load()) + 1
		status.GroupMembers = len(group.members)
		status.GroupLatency = int(group.latency.Load())
	}
	return status
}
