//go:build windows

/* SPDX-License-Identifier: MIT
 *
 * Copyright (C) 2019-2026 WireGuard LLC. All Rights Reserved.
 * Adapted for Yumi Desktop: a session is a value rather than a package global, so a new one can be
 * installed before the old one is closed, and the local network can be let through.
 */

package firewall

import (
	"unsafe"

	"golang.org/x/sys/windows"
)

type wfpObjectInstaller func(uintptr) error

// Fundamental Yumi-specific WFP objects.
type baseObjects struct {
	provider windows.GUID
	filters  windows.GUID
}

/*
Firewall is one installed kill switch: a dynamic WFP session holding a block-everything filter and
the handful of permits that keep the tunnel itself alive.

Dynamic means Windows removes every filter in it the moment the session handle closes, including
when the process holding it dies. That is the property that makes this safe to ship: a crash of the
service can never leave a machine without a network, at worst without a kill switch.
*/
type Firewall struct {
	session uintptr
}

func createWfpSession() (uintptr, error) {
	sessionDisplayData, err := createWtFwpmDisplayData0("Yumi", "Yumi kill switch")
	if err != nil {
		return 0, wrapErr(err)
	}

	session := wtFwpmSession0{
		displayData:          *sessionDisplayData,
		flags:                cFWPM_SESSION_FLAG_DYNAMIC,
		txnWaitTimeoutInMSec: windows.INFINITE,
	}

	sessionHandle := uintptr(0)

	err = fwpmEngineOpen0(nil, cRPC_C_AUTHN_WINNT, nil, &session, unsafe.Pointer(&sessionHandle))
	if err != nil {
		return 0, wrapErr(err)
	}

	return sessionHandle, nil
}

func registerBaseObjects(session uintptr) (*baseObjects, error) {
	bo := &baseObjects{}
	var err error
	bo.provider, err = windows.GenerateGUID()
	if err != nil {
		return nil, wrapErr(err)
	}
	bo.filters, err = windows.GenerateGUID()
	if err != nil {
		return nil, wrapErr(err)
	}

	{
		displayData, err := createWtFwpmDisplayData0("Yumi", "Yumi provider")
		if err != nil {
			return nil, wrapErr(err)
		}
		provider := wtFwpmProvider0{
			providerKey: bo.provider,
			displayData: *displayData,
		}
		err = fwpmProviderAdd0(session, &provider, 0)
		if err != nil {
			return nil, wrapErr(err)
		}
	}

	{
		displayData, err := createWtFwpmDisplayData0("Yumi filters", "Permissive and blocking filters")
		if err != nil {
			return nil, wrapErr(err)
		}
		sublayer := wtFwpmSublayer0{
			subLayerKey: bo.filters,
			displayData: *displayData,
			providerKey: &bo.provider,
			weight:      ^uint16(0),
		}
		err = fwpmSubLayerAdd0(session, &sublayer, 0)
		if err != nil {
			return nil, wrapErr(err)
		}
	}

	return bo, nil
}

/*
Enable installs a kill switch that lets through only: this executable (the service and its worker,
which carry the tunnel and the traffic routed around it), loopback, DHCP and neighbour discovery,
the tunnel adapter identified by luid, and — when permitLAN is set — the private ranges of the local
network. Everything else is blocked.

A luid of zero is accepted and matches no interface; it is what is passed while the adapter does
not exist yet, and the tunnel's own traffic still flows because it leaves from this executable.

Several may be enabled at once. Moving the kill switch onto a new adapter is done by enabling the
new one and only then closing the old, so there is no instant at which neither is installed.
*/
func Enable(luid uint64, permitLAN bool) (*Firewall, error) {
	session, err := createWfpSession()
	if err != nil {
		return nil, wrapErr(err)
	}

	objectInstaller := func(session uintptr) error {
		baseObjects, err := registerBaseObjects(session)
		if err != nil {
			return wrapErr(err)
		}

		err = permitWireGuardService(session, baseObjects, 15)
		if err != nil {
			return wrapErr(err)
		}

		err = permitLoopback(session, baseObjects, 13)
		if err != nil {
			return wrapErr(err)
		}

		if luid != 0 {
			err = permitTunInterface(session, baseObjects, 12, luid)
			if err != nil {
				return wrapErr(err)
			}
		}

		err = permitDHCPIPv4(session, baseObjects, 12)
		if err != nil {
			return wrapErr(err)
		}

		err = permitDHCPIPv6(session, baseObjects, 12)
		if err != nil {
			return wrapErr(err)
		}

		err = permitNdp(session, baseObjects, 12)
		if err != nil {
			return wrapErr(err)
		}

		if permitLAN {
			err = permitLocalNetwork(session, baseObjects, 12)
			if err != nil {
				return wrapErr(err)
			}
		}

		err = blockAll(session, baseObjects, 0)
		if err != nil {
			return wrapErr(err)
		}

		return nil
	}

	err = runTransaction(session, objectInstaller)
	if err != nil {
		fwpmEngineClose0(session)
		return nil, wrapErr(err)
	}

	return &Firewall{session: session}, nil
}

// Close removes every filter this kill switch installed. Safe to call more than once.
func (f *Firewall) Close() {
	if f == nil || f.session == 0 {
		return
	}
	fwpmEngineClose0(f.session)
	f.session = 0
}
