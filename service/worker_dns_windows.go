//go:build windows

package main

import (
	"net/netip"

	"github.com/GXUser7/yumi-desktop/service/firewall"
	"golang.org/x/sys/windows"
)

var dnsFlushResolverCache = windows.NewLazySystemDLL("dnsapi.dll").NewProc("DnsFlushResolverCache")

type tunnelDNSProtection struct {
	open  func() (firewall.Firewall, error)
	flush func()
	guard firewall.Firewall
}

func newTunnelDNSProtection() *tunnelDNSProtection {
	return &tunnelDNSProtection{
		open: func() (firewall.Firewall, error) {
			return firewall.ProtectDNS([]netip.Addr{netip.MustParseAddr(tunDNSV4)})
		},
		flush: func() { _, _, _ = dnsFlushResolverCache.Call() },
	}
}

func (p *tunnelDNSProtection) enable() error {
	if p.guard != nil {
		return nil
	}
	guard, err := p.open()
	if err != nil {
		return err
	}
	p.guard = guard
	p.flush()
	return nil
}

func (p *tunnelDNSProtection) close() {
	if p.guard != nil {
		p.guard.Close()
		p.guard = nil
		p.flush()
	}
}
