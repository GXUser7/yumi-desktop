//go:build darwin

package main

import (
	"errors"
	"net"
	"testing"

	"golang.org/x/net/route"
	"golang.org/x/sys/unix"
)

func TestChooseDarwinOutboundRoute(t *testing.T) {
	ifaces := map[int]*net.Interface{
		1: {Index: 1, Name: "lo0", Flags: net.FlagUp | net.FlagLoopback},
		2: {Index: 2, Name: "utun69", Flags: net.FlagUp},
		3: {Index: 3, Name: "en0", Flags: net.FlagUp},
		4: {Index: 4, Name: "en1", Flags: net.FlagUp},
		5: {Index: 5, Name: "en2", Flags: 0},
	}
	lookup := func(idx int) (*net.Interface, error) {
		iface, ok := ifaces[idx]
		if !ok {
			return nil, errors.New("missing")
		}
		return iface, nil
	}

	messages := []route.Message{
		// Not RTF_UP -> skipped
		&route.RouteMessage{
			Flags: 0,
			Index: 3,
			Addrs: []route.Addr{
				unix.RTAX_DST:     &route.Inet4Addr{IP: [4]byte{0, 0, 0, 0}},
				unix.RTAX_GATEWAY: &route.Inet4Addr{IP: [4]byte{192, 168, 1, 1}},
				unix.RTAX_NETMASK: &route.Inet4Addr{IP: [4]byte{0, 0, 0, 0}},
			},
		},
		// Non-zero netmask (0.0.0.0/8) -> skipped
		&route.RouteMessage{
			Flags: unix.RTF_UP,
			Index: 4,
			Addrs: []route.Addr{
				unix.RTAX_DST:     &route.Inet4Addr{IP: [4]byte{0, 0, 0, 0}},
				unix.RTAX_GATEWAY: &route.Inet4Addr{IP: [4]byte{192, 168, 1, 1}},
				unix.RTAX_NETMASK: &route.Inet4Addr{IP: [4]byte{255, 0, 0, 0}},
			},
		},
		// Own tunnel interface -> skipped
		&route.RouteMessage{
			Flags: unix.RTF_UP,
			Index: 2,
			Addrs: []route.Addr{
				unix.RTAX_DST:     &route.Inet4Addr{IP: [4]byte{0, 0, 0, 0}},
				unix.RTAX_GATEWAY: &route.LinkAddr{Index: 2},
				unix.RTAX_NETMASK: &route.Inet4Addr{IP: [4]byte{0, 0, 0, 0}},
			},
		},
		// Valid default route with Inet4Addr gateway and rm.Index -> selected!
		&route.RouteMessage{
			Flags: unix.RTF_UP | unix.RTF_GATEWAY,
			Index: 3,
			Addrs: []route.Addr{
				unix.RTAX_DST:     &route.Inet4Addr{IP: [4]byte{0, 0, 0, 0}},
				unix.RTAX_GATEWAY: &route.Inet4Addr{IP: [4]byte{192, 168, 1, 1}},
				unix.RTAX_NETMASK: &route.Inet4Addr{IP: [4]byte{0, 0, 0, 0}},
			},
		},
	}

	got, err := chooseDarwinOutboundRoute(messages, lookup)
	if err != nil || got != "en0" {
		t.Fatalf("got %q, %v; want en0", got, err)
	}

	// Fallback to LinkAddr.Index when rm.Index == 0 and RTAX_NETMASK omitted.
	linkFallback := []route.Message{
		&route.RouteMessage{
			Flags: unix.RTF_UP,
			Index: 0,
			Addrs: []route.Addr{
				unix.RTAX_DST:     &route.Inet4Addr{IP: [4]byte{0, 0, 0, 0}},
				unix.RTAX_GATEWAY: &route.LinkAddr{Index: 4},
			},
		},
	}
	got, err = chooseDarwinOutboundRoute(linkFallback, lookup)
	if err != nil || got != "en1" {
		t.Fatalf("LinkAddr fallback failed: got %q, %v", got, err)
	}

	if _, err := chooseDarwinOutboundRoute(nil, lookup); err == nil {
		t.Fatal("expected error when no default route exists")
	}
}
