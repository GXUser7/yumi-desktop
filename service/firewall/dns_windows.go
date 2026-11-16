//go:build windows

package firewall

import "net/netip"

// ProtectDNS keeps Windows' multi-adapter resolver from querying the physical adapter's DNS
// (often an on-link router, which never enters TUN). It is independent of the kill switch and
// deliberately has no LAN exception. Only DNS is restricted; direct application flows survive.
// The service itself may still resolve the proxy endpoint before a tunnel is available.
func ProtectDNS(servers []netip.Addr) (Firewall, error) {
	session, err := createWfpSession()
	if err != nil {
		return nil, err
	}
	err = runTransaction(session, func(session uintptr) error {
		objects, err := registerBaseObjects(session)
		if err != nil {
			return err
		}
		if err = permitWireGuardService(session, objects, 15); err != nil {
			return err
		}
		if err = permitLoopback(session, objects, 13); err != nil {
			return err
		}
		return blockDNS(servers, session, objects, 12, 10)
	})
	if err != nil {
		fwpmEngineClose0(session)
		return nil, err
	}
	return &wfpShield{session: session}, nil
}
