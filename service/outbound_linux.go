//go:build linux

package main

import (
	"errors"
	"net"

	"github.com/vishvananda/netlink"
)

var errAdapterDown = errors.New("adapter is down")

// Xray's auto mode scores interface names, not routes, and a container bridge can beat the Wi-Fi
// that actually carries the internet. Resolve the actual Linux default route before installing
// TUN routes, then give Xray the exact Go interface name it compares against.
func outboundInterface(selected string) (string, error) {
	if selected != "" {
		return validateSelectedAdapter(selected)
	}
	routes, err := netlink.RouteList(nil, netlink.FAMILY_V4)
	if err != nil {
		return "", err
	}
	var best *netlink.Route
	for i := range routes {
		route := &routes[i]
		if route.Dst != nil || route.LinkIndex <= 0 {
			continue
		}
		iface, err := net.InterfaceByIndex(route.LinkIndex)
		if err != nil || iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 || ownAdapter(iface.Name) {
			continue
		}
		// Lower metric wins; a tie keeps the first, which netlink lists in kernel order.
		if best == nil || route.Priority < best.Priority {
			best = route
		}
	}
	if best == nil {
		return "", errors.New("no active IPv4 default route")
	}
	iface, err := net.InterfaceByIndex(best.LinkIndex)
	if err != nil {
		return "", err
	}
	return iface.Name, nil
}
