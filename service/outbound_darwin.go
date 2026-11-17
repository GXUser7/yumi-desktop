//go:build darwin

package main

import (
	"errors"
	"net"

	"golang.org/x/net/route"
	"golang.org/x/sys/unix"
)

var errAdapterDown = errors.New("adapter is down")

func chooseDarwinOutboundRoute(messages []route.Message, lookup func(int) (*net.Interface, error)) (string, error) {
	for _, message := range messages {
		rm, ok := message.(*route.RouteMessage)
		if !ok || rm.Flags&unix.RTF_UP == 0 || len(rm.Addrs) <= unix.RTAX_DST {
			continue
		}
		dst, ok := rm.Addrs[unix.RTAX_DST].(*route.Inet4Addr)
		if !ok || dst.IP != [4]byte{} {
			continue
		}
		if len(rm.Addrs) > unix.RTAX_NETMASK && rm.Addrs[unix.RTAX_NETMASK] != nil {
			mask, ok := rm.Addrs[unix.RTAX_NETMASK].(*route.Inet4Addr)
			if !ok {
				continue
			}
			if ones, _ := net.IPMask(mask.IP[:]).Size(); ones != 0 {
				continue
			}
		}
		index := int(rm.Index)
		if index <= 0 && len(rm.Addrs) > unix.RTAX_GATEWAY {
			if gateway, ok := rm.Addrs[unix.RTAX_GATEWAY].(*route.LinkAddr); ok {
				index = gateway.Index
			}
		}
		if index <= 0 {
			continue
		}
		iface, err := lookup(index)
		if err != nil || iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 || ownAdapter(iface.Name) {
			continue
		}
		return iface.Name, nil
	}
	return "", errors.New("no active IPv4 default route")
}

// Xray's auto mode scores interface names, not routes, so the actual default route is resolved
// here and given to Xray as the exact interface name it binds sockets to.
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
	return chooseDarwinOutboundRoute(messages, net.InterfaceByIndex)
}
