//go:build windows

package main

import (
	"net/netip"

	"github.com/GXUser7/yumi-desktop/service/firewall"
	"golang.org/x/sys/windows"
)

var dnsFlushResolverCache = windows.NewLazySystemDLL("dnsapi.dll").NewProc("DnsFlushResolverCache")

type tunnelDNSProtection struct {
	open       func() (firewall.Firewall, error)
	openTunnel func(blockIPv6, bypassLAN bool) (firewall.Firewall, error)
	flush      func()
	guard      firewall.Firewall
	blockIPv6  bool
	bypassLAN  bool
}

func newTunnelDNSProtection(policy ...bool) *tunnelDNSProtection {
	var blockIPv6, bypassLAN bool
	if len(policy) > 0 {
		blockIPv6 = policy[0]
	}
	if len(policy) > 1 {
		bypassLAN = policy[1]
	}
	return &tunnelDNSProtection{
		openTunnel: func(blockV6, lan bool) (firewall.Firewall, error) {
			return firewall.ProtectTunnel([]netip.Addr{netip.MustParseAddr(tunDNSV4)}, blockV6, lan)
		},
		flush:     func() { _, _, _ = dnsFlushResolverCache.Call() },
		blockIPv6: blockIPv6,
		bypassLAN: bypassLAN,
	}
}

func (p *tunnelDNSProtection) configure(blockIPv6, bypassLAN bool) {
	if p.guard != nil && (p.blockIPv6 != blockIPv6 || p.bypassLAN != bypassLAN) {
		p.close()
	}
	p.blockIPv6 = blockIPv6
	p.bypassLAN = bypassLAN
}

func (p *tunnelDNSProtection) enable() error {
	if p.guard != nil {
		return nil
	}
	var guard firewall.Firewall
	var err error
	if p.open != nil {
		guard, err = p.open()
	} else {
		openFn := p.openTunnel
		if openFn == nil {
			openFn = func(blockV6, lan bool) (firewall.Firewall, error) {
				return firewall.ProtectTunnel([]netip.Addr{netip.MustParseAddr(tunDNSV4)}, blockV6, lan)
			}
		}
		guard, err = openFn(p.blockIPv6, p.bypassLAN)
	}
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
