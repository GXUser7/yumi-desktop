//go:build darwin

package main

import (
	"errors"
	"net"

	"golang.org/x/net/route"
	"golang.org/x/sys/unix"
)

var errAdapterDown = errors.New("adapter is down")

// Xray's auto mode scores interface names, not routes, so the actual default route is resolved
// here and given to Xray as the exact interface name it binds sockets to. The route socket hands
// over the whole table; the interesting entries are the ones whose destination is 0.0.0.0 and
// whose gateway is an interface rather than another router. BSD route messages carry no usable
// metric, so among the (almost always one) interface-defaults the first wins.
func outboundInterface(selected string) (string, error) {
	if selected != "" {
		return validateSelectedAdapter(selected)
	}
	rib, err := route.FetchRIB(unix.AF_INET, route.RIBTypeRoute, 0)
	if err != nil {
		return "", err
	}
	messages, err := route.ParseRIB(route.RIBTypeRoute, rib)
	if err != nil {
		return "", err
	}
	for _, message := range messages {
		rm, ok := message.(*route.RouteMessage)
		if !ok || len(rm.Addrs) <= unix.RTAX_GATEWAY {
			continue
		}
		dst, ok := rm.Addrs[unix.RTAX_DST].(*route.Inet4Addr)
		if !ok || dst.IP != [4]byte{} {
			continue
		}
		gateway, ok := rm.Addrs[unix.RTAX_GATEWAY].(*route.LinkAddr)
		if !ok || gateway.Index <= 0 {
			continue
		}
		iface, err := net.InterfaceByIndex(gateway.Index)
		if err != nil || iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 || ownAdapter(iface.Name) {
			continue
		}
		return iface.Name, nil
	}
	return "", errors.New("no active IPv4 default route")
}
