//go:build windows

package main

import (
	"errors"
	"net"
	"sort"

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
		if route.name == "" || ownAdapter(route.name) {
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
//
// An adapter chosen by hand wins over that guess. It is looked up rather than trusted: a name in
// a settings file can outlive the adapter it names, and a tunnel whose outbound interface does
// not exist would come up and then carry nothing.
func outboundInterface(selected string) (string, error) {
	if selected != "" {
		iface, err := net.InterfaceByName(selected)
		if err != nil {
			return "", err
		}
		if iface.Flags&net.FlagUp == 0 {
			return "", errors.New("adapter is down")
		}
		return iface.Name, nil
	}
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

// The adapters a person can reasonably mean by "where the internet comes from": up, not Yumi's
// own tunnel, and holding an IPv4 address. The latter is what keeps loopback, Teredo and the
// filter-driver duplicates of one physical card out of the list.
//
// Names come from Go's interface table because that is the table Xray compares against. Java's
// are ethernet_32768 and the like and would never match.
func adapterList() []Adapter {
	defaultName, _ := outboundInterface("")
	interfaces, err := net.Interfaces()
	if err != nil {
		return []Adapter{}
	}
	adapters := []Adapter{}
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 || ownAdapter(iface.Name) {
			continue
		}
		address := firstIPv4(iface)
		if address == "" {
			continue
		}
		adapters = append(adapters, Adapter{Name: iface.Name, Address: address, Default: iface.Name == defaultName})
	}
	sort.Slice(adapters, func(i, j int) bool {
		if adapters[i].Default != adapters[j].Default {
			return adapters[i].Default
		}
		return adapters[i].Name < adapters[j].Name
	})
	return adapters
}

func firstIPv4(iface net.Interface) string {
	addresses, err := iface.Addrs()
	if err != nil {
		return ""
	}
	for _, raw := range addresses {
		var ip net.IP
		switch address := raw.(type) {
		case *net.IPNet:
			ip = address.IP
		case *net.IPAddr:
			ip = address.IP
		}
		ip4 := ip.To4()
		if ip4 == nil || ip4.IsLoopback() || ip4.IsLinkLocalUnicast() {
			continue
		}
		return ip4.String()
	}
	return ""
}
