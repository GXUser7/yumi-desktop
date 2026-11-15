//go:build unix

package main

import "testing"

// The unix twin of the Windows adapter test: the worker reports a name rather than a LUID, and
// everything downstream of recordWorkerStatus has to treat it as the same kind of promise.
func TestWorkerAdapterSurvivesVPNOffAndShieldRelease(t *testing.T) {
	s := &supervisor{generation: 7}
	if got := s.recordWorkerStatus(7, Status{Version: 1, State: "connected", TunName: "utun69"}); got.Name != "utun69" {
		t.Fatal("current worker adapter was not accepted")
	}
	s.releaseShield()
	if s.shieldName != "" || s.adapterName != "utun69" {
		t.Fatal("VPN-off discarded the forwarding adapter identity")
	}
	s.recordWorkerStatus(7, Status{Version: 1, State: "disconnected"})
	if s.adapterName != "utun69" || s.snapshot().TunName != "" {
		t.Fatal("off status lost the adapter or exposed its private identifier")
	}
	s.stop()
	if s.adapterName != "" {
		t.Fatal("stopped worker left a reusable adapter identity")
	}
}

func TestReplacedWorkerCannotChangeStatusOrAdapter(t *testing.T) {
	s := &supervisor{generation: 8}
	s.recordWorkerStatus(8, Status{Version: 1, State: "connected", NodeID: "current", TunName: "Yumi"})
	if got := s.recordWorkerStatus(7, Status{Version: 1, State: "failed", NodeID: "stale", TunName: "utun5"}); got.Name != "" {
		t.Fatal("stale worker adapter was accepted")
	}
	if s.adapterName != "Yumi" || s.status.State != "connected" || s.status.NodeID != "current" {
		t.Fatal("stale worker overwrote the active connection")
	}
}
