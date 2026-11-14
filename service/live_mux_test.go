package main

import (
	"context"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/transport"
	"github.com/xtls/xray-core/transport/pipe"
)

// Mirrors the pinned mux client's lifetime contract: its pipe-copy goroutine does not stop just
// because Dispatch sees ctx.Done, and a bare *pipe.Reader makes Dispatch return immediately.
type muxLikeOutbound struct {
	entered chan context.Context
	pumpEnd chan error
	closed  atomic.Int32
}

func newMuxLikeOutbound() *muxLikeOutbound {
	return &muxLikeOutbound{entered: make(chan context.Context, 1), pumpEnd: make(chan error, 1)}
}
func (*muxLikeOutbound) Tag() string                          { return "proxy-node" }
func (*muxLikeOutbound) Start() error                         { return nil }
func (*muxLikeOutbound) SenderSettings() *serial.TypedMessage { return nil }
func (*muxLikeOutbound) ProxySettings() *serial.TypedMessage  { return nil }
func (h *muxLikeOutbound) Close() error                       { h.closed.Add(1); return nil }
func (h *muxLikeOutbound) Dispatch(ctx context.Context, link *transport.Link) {
	done := make(chan struct{})
	go func() {
		err := buf.Copy(link.Reader, buf.Discard)
		h.pumpEnd <- err
		close(done)
	}()
	h.entered <- ctx
	if _, asynchronous := link.Reader.(*pipe.Reader); asynchronous {
		return
	}
	select {
	case <-ctx.Done():
	case <-done:
	}
}

type muxFlow struct {
	upWriter *pipe.Writer
	downEnd  chan error
	dispatch chan struct{}
	context  context.Context
}

func startMuxFlow(t *testing.T, live *liveOutbound, handler *muxLikeOutbound) muxFlow {
	t.Helper()
	upReader, upWriter := pipe.New()
	downReader, downWriter := pipe.New()
	flow := muxFlow{upWriter: upWriter, downEnd: make(chan error, 1), dispatch: make(chan struct{})}
	go func() {
		_, err := downReader.ReadMultiBuffer()
		flow.downEnd <- err
	}()
	go func() {
		live.Dispatch(context.Background(), &transport.Link{Reader: upReader, Writer: downWriter})
		close(flow.dispatch)
	}()
	flow.context = await(t, handler.entered)
	t.Cleanup(func() { upReader.Interrupt(); downWriter.Interrupt() })
	return flow
}

func assertMuxFlowStopped(t *testing.T, flow muxFlow, handler *muxLikeOutbound) {
	t.Helper()
	await(t, flow.dispatch)
	if err := await(t, handler.pumpEnd); err == nil {
		t.Fatal("cancelled mux uplink must terminate with its pipe interrupted")
	}
	if err := await(t, flow.downEnd); err == nil {
		t.Fatal("cancelled mux downlink must be interrupted as well")
	}
}

func TestLiveMuxSessionSurvivesSwitchAndStopsItsAsyncPumpOnDisconnect(t *testing.T) {
	first := newMuxLikeOutbound()
	live := newLiveOutbound(first)
	defer live.Close()
	flow := startMuxFlow(t, live, first)
	if err := live.replace(heldHandler(), false); err != nil {
		t.Fatal(err)
	}
	select {
	case <-flow.context.Done():
		t.Fatal("changing server cancelled the old mux flow")
	case <-flow.dispatch:
		t.Fatal("mux scheduling was mistaken for the end of its flow")
	default:
	}
	if first.closed.Load() != 0 {
		t.Fatal("the old mux handler was retired while its flow was still live")
	}
	buffer := buf.New()
	_, _ = buffer.WriteString("existing flow still carries data")
	if err := flow.upWriter.WriteMultiBuffer(buf.MultiBuffer{buffer}); err != nil {
		t.Fatalf("server change interrupted an established mux stream: %v", err)
	}
	if err := live.replace(heldHandler(), true); err != nil {
		t.Fatal(err)
	}
	assertMuxFlowStopped(t, flow, first)
}

func TestClosingLiveOutboundStopsAsyncMuxFlowsIncludingDirectLeases(t *testing.T) {
	first, direct := newMuxLikeOutbound(), newMuxLikeOutbound()
	live := newLiveOutbound(first)
	defer live.Close()
	proxyFlow := startMuxFlow(t, live, first)
	if err := live.replace(direct, true); err != nil {
		t.Fatal(err)
	}
	assertMuxFlowStopped(t, proxyFlow, first)
	directFlow := startMuxFlow(t, live, direct)
	if err := live.Close(); err != nil {
		t.Fatal(err)
	}
	assertMuxFlowStopped(t, directFlow, direct)
	deadline := time.Now().Add(time.Second)
	for direct.closed.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if direct.closed.Load() != 1 {
		t.Fatal("direct handler must be closed exactly once at shutdown")
	}
}
