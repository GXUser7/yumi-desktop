package main

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/transport"
)

type heldOutbound struct {
	entered    chan context.Context
	ended      chan struct{}
	closes     atomic.Int32
	startError error
}

func heldHandler() *heldOutbound {
	return &heldOutbound{entered: make(chan context.Context, 8), ended: make(chan struct{}, 8)}
}
func (h *heldOutbound) Tag() string                          { return "proxy-node" }
func (h *heldOutbound) Start() error                         { return h.startError }
func (h *heldOutbound) Close() error                         { h.closes.Add(1); return nil }
func (h *heldOutbound) SenderSettings() *serial.TypedMessage { return nil }
func (h *heldOutbound) ProxySettings() *serial.TypedMessage  { return nil }
func (h *heldOutbound) Dispatch(ctx context.Context, _ *transport.Link) {
	h.entered <- ctx
	<-ctx.Done()
	h.ended <- struct{}{}
}

func await[T any](t *testing.T, ch <-chan T) T {
	t.Helper()
	select {
	case v := <-ch:
		return v
	case <-time.After(3 * time.Second):
		t.Fatal("flow did not complete")
		var zero T
		return zero
	}
}

func startHeld(t *testing.T, live *liveOutbound, h *heldOutbound) (context.Context, context.CancelFunc) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	go live.Dispatch(ctx, &transport.Link{})
	return await(t, h.entered), cancel
}

func TestLiveSwitchCancelsOldVPNFlowsAndRetiresTheirHandlers(t *testing.T) {
	first, second := heldHandler(), heldHandler()
	live := newLiveOutbound(first)
	defer live.Close()
	old, finishOld := startHeld(t, live, first)
	if err := live.replace(second, false); err != nil {
		t.Fatal(err)
	}
	await(t, old.Done())
	_, finishNew := startHeld(t, live, second)
	finishOld()
	await(t, first.ended)
	deadline := time.Now().Add(time.Second)
	for first.closes.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if first.closes.Load() != 1 {
		t.Fatal("drained handler was not retired exactly once")
	}
	finishNew()
	await(t, second.ended)
}

func TestDisconnectCancelsVPNButKeepsDirectSessionsAcrossReconnect(t *testing.T) {
	first, direct, next, lastDirect := heldHandler(), heldHandler(), heldHandler(), heldHandler()
	live := newLiveOutbound(first)
	defer live.Close()
	_, finishVPN := startHeld(t, live, first)
	defer finishVPN()
	if err := live.replace(direct, true); err != nil {
		t.Fatal(err)
	}
	await(t, first.ended)
	bypass, finishBypass := startHeld(t, live, direct)
	defer finishBypass()
	if err := live.replace(next, false); err != nil {
		t.Fatal(err)
	}
	_, finishNext := startHeld(t, live, next)
	defer finishNext()
	if err := live.replace(lastDirect, true); err != nil {
		t.Fatal(err)
	}
	await(t, next.ended)
	select {
	case <-bypass.Done():
		t.Fatal("direct session was interrupted by VPN switching")
	default:
	}
	if direct.closes.Load() != 0 {
		t.Fatal("active direct handler was closed")
	}
	finishBypass()
	await(t, direct.ended)
}

func TestFailedReplacementLeavesCurrentServerAndSessionsUntouched(t *testing.T) {
	first, bad := heldHandler(), heldHandler()
	bad.startError = errors.New("unsupported transport")
	live := newLiveOutbound(first)
	defer live.Close()
	old, finish := startHeld(t, live, first)
	defer finish()
	if err := live.replace(bad, false); err == nil {
		t.Fatal("failed candidate was accepted")
	}
	select {
	case <-old.Done():
		t.Fatal("failed replacement interrupted current server")
	default:
	}
	if first.closes.Load() != 0 || bad.closes.Load() != 1 {
		t.Fatal("wrong handler was closed")
	}
	_, finishSecond := startHeld(t, live, first)
	finishSecond()
	await(t, first.ended)
}
