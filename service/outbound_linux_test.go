//go:build linux

package main

import (
	"errors"
	"net"
	"testing"

	"github.com/vishvananda/netlink"
)

func TestChooseLinuxOutboundRoute(t *testing.T) {
	ifaces := map[int]*net.Interface{
		1: {Index: 1, Name: "lo", Flags: net.FlagUp | net.FlagLoopback},
		2: {Index: 2, Name: "Yumi", Flags: net.FlagUp},
		3: {Index: 3, Name: "eth0", Flags: net.FlagUp},
		4: {Index: 4, Name: "wlan0", Flags: net.FlagUp},
		5: {Index: 5, Name: "eth1", Flags: 0},
	}
	lookup := func(idx int) (*net.Interface, error) {
		iface, ok := ifaces[idx]
		if !ok {
			return nil, errors.New("missing")
		}
		return iface, nil
	}

	_, subnet24, _ := net.ParseCIDR("192.168.1.0/24")
	_, zeroNet, _ := net.ParseCIDR("0.0.0.0/0")

	routes := []netlink.Route{
		{LinkIndex: 1, Dst: nil, Priority: 1},       // loopback skipped
		{LinkIndex: 2, Dst: zeroNet, Priority: 1},   // own Yumi adapter skipped
		{LinkIndex: 5, Dst: zeroNet, Priority: 1},   // down interface skipped
		{LinkIndex: 3, Dst: subnet24, Priority: 1},  // non-default /24 skipped
		{LinkIndex: 4, Dst: zeroNet, Priority: 600}, // explicit 0.0.0.0/0 accepted
		{LinkIndex: 3, Dst: nil, Priority: 100},     // lower priority metric wins
	}
	got, err := chooseLinuxOutboundRoute(routes, lookup)
	if err != nil || got != "eth0" {
		t.Fatalf("got %q, %v; want eth0", got, err)
	}

	// Verify explicit 0.0.0.0/0 route works even without Dst == nil.
	got, err = chooseLinuxOutboundRoute([]netlink.Route{{LinkIndex: 4, Dst: zeroNet, Priority: 50}}, lookup)
	if err != nil || got != "wlan0" {
		t.Fatalf("explicit 0.0.0.0/0 route failed: got %q, %v", got, err)
	}

	if _, err := chooseLinuxOutboundRoute(nil, lookup); err == nil {
		t.Fatal("expected error when no default route exists")
	}
}
