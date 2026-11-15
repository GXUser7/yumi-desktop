//go:build unix

package main

import (
	"net"
	"sort"
)

// The adapters a person can reasonably mean by "where the internet comes from": up, not the
// tunnel's own device, and holding an IPv4 address. The latter is what keeps loopback and the
// half-configured virtual bridges out of the list. Shared by Linux and macOS; only the way the
// default route is found differs.
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

// An adapter chosen by hand is looked up rather than trusted: a name in a settings file can
// outlive the adapter it names, and a tunnel whose outbound interface does not exist would come
// up and then carry nothing.
func validateSelectedAdapter(selected string) (string, error) {
	iface, err := net.InterfaceByName(selected)
	if err != nil {
		return "", err
	}
	if iface.Flags&net.FlagUp == 0 {
		return "", errAdapterDown
	}
	return iface.Name, nil
}
