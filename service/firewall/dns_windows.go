//go:build windows

package firewall

import "net/netip"

// ProtectTunnel keeps Windows' multi-adapter resolver from querying physical adapters' DNS,
// and when blockIPv6 is true blocks non-loopback (and non-LAN if bypassLAN) IPv6 traffic so
// dual-stack browsers fall back cleanly to IPv4 inside TUN without ::/1 routes.
func ProtectTunnel(servers []netip.Addr, blockIPv6 bool, bypassLAN bool) (Firewall, error) {
	return protectTunnelWithBackend(defaultWFPBackend, servers, blockIPv6, bypassLAN)
}

// ProtectDNS keeps Windows' multi-adapter resolver from querying the physical adapter's DNS
// (often an on-link router, which never enters TUN). It is independent of the kill switch and
// deliberately has no LAN exception. Only DNS is restricted; direct application flows survive.
// The service itself may still resolve the proxy endpoint before a tunnel is available.
func ProtectDNS(servers []netip.Addr) (Firewall, error) {
	return ProtectTunnel(servers, false, false)
}

func protectTunnelWithBackend(backend wfpBackend, servers []netip.Addr, blockIPv6 bool, bypassLAN bool) (Firewall, error) {
	session, err := backend.openSession()
	if err != nil {
		return nil, err
	}
	err = backend.runTx(session, func(session uintptr) error {
		objects, err := backend.registerBase(session)
		if err != nil {
			return err
		}
		if err = permitWireGuardService(backend, session, objects, 15); err != nil {
			return err
		}
		if err = permitLoopback(backend, session, objects, 13); err != nil {
			return err
		}
		if err = blockDNS(backend, servers, session, objects, 12, 10); err != nil {
			return err
		}
		if blockIPv6 {
			if bypassLAN {
				if err = permitLocalNetworkV6(backend, session, objects, 9); err != nil {
					return err
				}
				if err = permitNdp(backend, session, objects, 9); err != nil {
					return err
				}
			}
			if err = blockAllIPv6(backend, session, objects, 8); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		_ = backend.closeSession(session)
		return nil, err
	}
	return &wfpShield{session: session, closeSession: backend.closeSession}, nil
}
