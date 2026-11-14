//go:build windows

package main

import "testing"

func TestWorkerAdapterSurvivesVPNOffAndShieldRelease(t *testing.T) {
	s := &supervisor{generation: 7}
	if got := s.recordWorkerStatus(7, Status{Version: 1, State: "connected", TunLUID: 42}); got != 42 {
		t.Fatal("current worker adapter was not accepted")
	}
	// Releasing an absent shield is unprivileged. A retained worker must still be known when
	// the next connection raises its real shield, rather than using zero and blocking its TUN.
	s.releaseShield()
	if s.shieldLUID != 0 || s.adapterLUID != 42 {
		t.Fatal("VPN-off discarded the forwarding adapter identity")
	}
	s.recordWorkerStatus(7, Status{Version: 1, State: "disconnected"})
	if s.adapterLUID != 42 || s.snapshot().TunLUID != 0 {
		t.Fatal("off status lost the adapter or exposed its private identifier")
	}
	s.stop()
	if s.adapterLUID != 0 {
		t.Fatal("stopped worker left a reusable adapter identity")
	}
}

func TestReplacedWorkerCannotChangeStatusOrAdapter(t *testing.T) {
	s := &supervisor{generation: 8}
	s.recordWorkerStatus(8, Status{Version: 1, State: "connected", NodeID: "current", TunLUID: 42})
	if got := s.recordWorkerStatus(7, Status{Version: 1, State: "failed", NodeID: "stale", TunLUID: 99}); got != 0 {
		t.Fatal("stale worker adapter was accepted")
	}
	if s.adapterLUID != 42 || s.status.State != "connected" || s.status.NodeID != "current" {
		t.Fatal("stale worker overwrote the active connection")
	}
}
