//go:build linux

package main

import (
	"errors"
	"net"

	"github.com/vishvananda/netlink"
)

var errAdapterDown = errors.New("adapter is down")

func chooseLinuxOutboundRoute(routes []netlink.Route, lookup func(int) (*net.Interface, error)) (string, error) {
	var best *netlink.Route
	var bestName string
	for i := range routes {
		route := &routes[i]
		if route.LinkIndex <= 0 {
			continue
		}
		if route.Dst != nil {
			if ones, _ := route.Dst.Mask.Size(); ones != 0 {
				continue
			}
		}
		iface, err := lookup(route.LinkIndex)
		if err != nil || iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 || ownAdapter(iface.Name) {
			continue
		}
		// Lower metric wins; a tie keeps the first, which netlink lists in kernel order.
		if best == nil || route.Priority < best.Priority {
			best = route
			bestName = iface.Name
		}
	}
	if best == nil {
		return "", errors.New("no active IPv4 default route")
	}
	return bestName, nil
}

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
	return chooseLinuxOutboundRoute(routes, net.InterfaceByIndex)
}
