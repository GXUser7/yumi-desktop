//go:build windows

package main

import (
	"errors"
	"net"
	"strings"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

type outboundRoute struct {
	name            string
	index           uint32
	routeMetric     uint32
	interfaceMetric uint32
}

func chooseOutboundRoute(routes []outboundRoute) (string, error) {
	var best *outboundRoute
	for i := range routes {
		route := &routes[i]
		if route.name == "" || strings.EqualFold(route.name, "Yumi") || strings.HasPrefix(strings.ToLower(route.name), "yumi-") {
			continue
		}
		metric := uint64(route.routeMetric) + uint64(route.interfaceMetric)
		if best == nil || metric < uint64(best.routeMetric)+uint64(best.interfaceMetric) ||
			(metric == uint64(best.routeMetric)+uint64(best.interfaceMetric) && route.index < best.index) {
			best = route
		}
	}
	if best == nil {
		return "", errors.New("no active IPv4 default route")
	}
	return best.name, nil
}

// Xray's auto mode scores interface names and 192.168 addresses, not routes. A localized Wi-Fi
// name can therefore lose to VirtualBox Host-Only. Resolve the actual Windows default route before
// installing TUN routes, then give Xray the exact Go interface name it compares against.
func outboundInterface() (string, error) {
	routes, err := winipcfg.GetIPForwardTable2(windows.AF_INET)
	if err != nil {
		return "", err
	}
	candidates := []outboundRoute{}
	for _, route := range routes {
		prefix := route.DestinationPrefix.Prefix()
		if !prefix.IsValid() || prefix.Bits() != 0 || route.Loopback {
			continue
		}
		iface, err := net.InterfaceByIndex(int(route.InterfaceIndex))
		if err != nil || iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		ipif, err := route.InterfaceLUID.IPInterface(windows.AF_INET)
		if err != nil || !ipif.Connected || ipif.DisableDefaultRoutes {
			continue
		}
		candidates = append(candidates, outboundRoute{iface.Name, route.InterfaceIndex, route.Metric, ipif.Metric})
	}
	return chooseOutboundRoute(candidates)
}
