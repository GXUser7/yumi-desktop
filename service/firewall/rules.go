//go:build windows

/* SPDX-License-Identifier: MIT
 *
 * Copyright (C) 2019-2026 WireGuard LLC. All Rights Reserved.
 */

package firewall

import (
	"encoding/binary"
	"errors"
	"net/netip"
	"runtime"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Known addresses.
var (
	linkLocal = wtFwpV6AddrAndMask{[16]uint8{0xfe, 0x80}, 10}

	linkLocalDHCPMulticast = wtFwpByteArray16{[16]uint8{0xFF, 0x02, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x1, 0x0, 0x2}}
	siteLocalDHCPMulticast = wtFwpByteArray16{[16]uint8{0xFF, 0x05, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x1, 0x0, 0x3}}

	linkLocalRouterMulticast = wtFwpByteArray16{[16]uint8{0xFF, 0x02, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x2}}
)

func permitTunInterface(backend wfpBackend, session uintptr, baseObjects *baseObjects, weight uint8, ifLUID uint64) error {
	ifaceCondition := wtFwpmFilterCondition0{
		fieldKey:  cFWPM_CONDITION_IP_LOCAL_INTERFACE,
		matchType: cFWP_MATCH_EQUAL,
		conditionValue: wtFwpConditionValue0{
			_type: cFWP_UINT64,
			value: (uintptr)(unsafe.Pointer(&ifLUID)),
		},
	}
	forwardCondition := wtFwpmFilterCondition0{
		fieldKey:  cFWPM_CONDITION_IP_FORWARD_INTERFACE,
		matchType: cFWP_MATCH_EQUAL,
		conditionValue: wtFwpConditionValue0{
			_type: cFWP_UINT64,
			value: (uintptr)(unsafe.Pointer(&ifLUID)),
		},
	}

	filter := wtFwpmFilter0{
		providerKey:         &baseObjects.provider,
		subLayerKey:         baseObjects.filters,
		weight:              filterWeight(weight),
		numFilterConditions: 1,
		action: wtFwpmAction0{
			_type: cFWP_ACTION_PERMIT,
		},
	}

	for _, def := range []struct {
		name      string
		layer     windows.GUID
		condition *wtFwpmFilterCondition0
	}{
		{"Permit outbound IPv4 traffic on TUN", cFWPM_LAYER_ALE_AUTH_CONNECT_V4, &ifaceCondition},
		{"Permit inbound IPv4 traffic on TUN", cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4, &ifaceCondition},
		{"Permit outbound IPv6 traffic on TUN", cFWPM_LAYER_ALE_AUTH_CONNECT_V6, &ifaceCondition},
		{"Permit inbound IPv6 traffic on TUN", cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6, &ifaceCondition},
		{"Permit outbound forwarded IPv4 traffic to TUN", cFWPM_LAYER_IPFORWARD_V4, &forwardCondition},
		{"Permit inbound forwarded IPv4 traffic from TUN", cFWPM_LAYER_IPFORWARD_V4, &ifaceCondition},
		{"Permit outbound forwarded IPv6 traffic to TUN", cFWPM_LAYER_IPFORWARD_V6, &forwardCondition},
		{"Permit inbound forwarded IPv6 traffic from TUN", cFWPM_LAYER_IPFORWARD_V6, &ifaceCondition},
	} {
		displayData, err := createWtFwpmDisplayData0(def.name, "")
		if err != nil {
			return wrapErr(err)
		}

		filter.displayData = *displayData
		filter.layerKey = def.layer
		filter.filterCondition = def.condition

		if err := backend.addFilter(session, &filter); err != nil {
			return wrapErr(err)
		}
	}

	runtime.KeepAlive(&ifLUID)
	runtime.KeepAlive(ifaceCondition)
	runtime.KeepAlive(forwardCondition)
	return nil
}

func permitWireGuardService(backend wfpBackend, session uintptr, baseObjects *baseObjects, weight uint8) error {
	var conditions [2]wtFwpmFilterCondition0

	//
	// First condition is the exe path of the current process.
	//
	appID, freeAppID, err := backend.currentAppID()
	if err != nil {
		return wrapErr(err)
	}
	if freeAppID != nil {
		defer freeAppID()
	}

	conditions[0] = wtFwpmFilterCondition0{
		fieldKey:  cFWPM_CONDITION_ALE_APP_ID,
		matchType: cFWP_MATCH_EQUAL,
		conditionValue: wtFwpConditionValue0{
			_type: cFWP_BYTE_BLOB_TYPE,
			value: uintptr(unsafe.Pointer(appID)),
		},
	}

	//
	// Second condition is the SECURITY_DESCRIPTOR of the current process.
	// This prevents other processes hosted in the same exe from matching this filter.
	//
	sd, err := getCurrentProcessSecurityDescriptor()
	if err != nil {
		return wrapErr(err)
	}

	sdBlob := wtFwpByteBlob{sd.Length(), (*byte)(unsafe.Pointer(sd))}
	conditions[1] = wtFwpmFilterCondition0{
		fieldKey:  cFWPM_CONDITION_ALE_USER_ID,
		matchType: cFWP_MATCH_EQUAL,
		conditionValue: wtFwpConditionValue0{
			_type: cFWP_SECURITY_DESCRIPTOR_TYPE,
			value: uintptr(unsafe.Pointer(&sdBlob)),
		},
	}

	//
	// Assemble the filter.
	//
	filter := wtFwpmFilter0{
		providerKey:         &baseObjects.provider,
		subLayerKey:         baseObjects.filters,
		weight:              filterWeight(weight),
		flags:               cFWPM_FILTER_FLAG_CLEAR_ACTION_RIGHT,
		numFilterConditions: uint32(len(conditions)),
		filterCondition:     (*wtFwpmFilterCondition0)(unsafe.Pointer(&conditions)),
		action: wtFwpmAction0{
			_type: cFWP_ACTION_PERMIT,
		},
	}

	//
	// #1 Permit outbound IPv4 traffic.
	//
	{
		displayData, err := createWtFwpmDisplayData0("Permit unrestricted outbound traffic for WireGuard service (IPv4)", "")
		if err != nil {
			return wrapErr(err)
		}

		filter.displayData = *displayData
		filter.layerKey = cFWPM_LAYER_ALE_AUTH_CONNECT_V4

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	//
	// #2 Permit inbound IPv4 traffic.
	//
	{
		displayData, err := createWtFwpmDisplayData0("Permit unrestricted inbound traffic for WireGuard service (IPv4)", "")
		if err != nil {
			return wrapErr(err)
		}

		filter.displayData = *displayData
		filter.layerKey = cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	//
	// #3 Permit outbound IPv6 traffic.
	//
	{
		displayData, err := createWtFwpmDisplayData0("Permit unrestricted outbound traffic for WireGuard service (IPv6)", "")
		if err != nil {
			return wrapErr(err)
		}

		filter.displayData = *displayData
		filter.layerKey = cFWPM_LAYER_ALE_AUTH_CONNECT_V6

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	//
	// #4 Permit inbound IPv6 traffic.
	//
	{
		displayData, err := createWtFwpmDisplayData0("Permit unrestricted inbound traffic for WireGuard service (IPv6)", "")
		if err != nil {
			return wrapErr(err)
		}

		filter.displayData = *displayData
		filter.layerKey = cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	runtime.KeepAlive(sdBlob)
	runtime.KeepAlive(sd)
	return nil
}

func permitLoopback(backend wfpBackend, session uintptr, baseObjects *baseObjects, weight uint8) error {
	condition := wtFwpmFilterCondition0{
		fieldKey:  cFWPM_CONDITION_FLAGS,
		matchType: cFWP_MATCH_FLAGS_ALL_SET,
		conditionValue: wtFwpConditionValue0{
			_type: cFWP_UINT32,
			value: uintptr(cFWP_CONDITION_FLAG_IS_LOOPBACK),
		},
	}

	filter := wtFwpmFilter0{
		providerKey:         &baseObjects.provider,
		subLayerKey:         baseObjects.filters,
		weight:              filterWeight(weight),
		numFilterConditions: 1,
		filterCondition:     (*wtFwpmFilterCondition0)(unsafe.Pointer(&condition)),
		action: wtFwpmAction0{
			_type: cFWP_ACTION_PERMIT,
		},
	}

	//
	// #1 Permit outbound IPv4 on loopback.
	//
	{
		displayData, err := createWtFwpmDisplayData0("Permit outbound on loopback (IPv4)", "")
		if err != nil {
			return wrapErr(err)
		}

		filter.displayData = *displayData
		filter.layerKey = cFWPM_LAYER_ALE_AUTH_CONNECT_V4

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	//
	// #2 Permit inbound IPv4 on loopback.
	//
	{
		displayData, err := createWtFwpmDisplayData0("Permit inbound on loopback (IPv4)", "")
		if err != nil {
			return wrapErr(err)
		}

		filter.displayData = *displayData
		filter.layerKey = cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	//
	// #3 Permit outbound IPv6 on loopback.
	//
	{
		displayData, err := createWtFwpmDisplayData0("Permit outbound on loopback (IPv6)", "")
		if err != nil {
			return wrapErr(err)
		}

		filter.displayData = *displayData
		filter.layerKey = cFWPM_LAYER_ALE_AUTH_CONNECT_V6

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	//
	// #4 Permit inbound IPv6 on loopback.
	//
	{
		displayData, err := createWtFwpmDisplayData0("Permit inbound on loopback (IPv6)", "")
		if err != nil {
			return wrapErr(err)
		}

		filter.displayData = *displayData
		filter.layerKey = cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	return nil
}

func permitDHCPIPv4(backend wfpBackend, session uintptr, baseObjects *baseObjects, weight uint8) error {
	//
	// #1 Outbound DHCP request on IPv4.
	//
	{
		var conditions [4]wtFwpmFilterCondition0

		conditions[0].fieldKey = cFWPM_CONDITION_IP_PROTOCOL
		conditions[0].matchType = cFWP_MATCH_EQUAL
		conditions[0].conditionValue._type = cFWP_UINT8
		conditions[0].conditionValue.value = uintptr(cIPPROTO_UDP)

		conditions[1].fieldKey = cFWPM_CONDITION_IP_LOCAL_PORT
		conditions[1].matchType = cFWP_MATCH_EQUAL
		conditions[1].conditionValue._type = cFWP_UINT16
		conditions[1].conditionValue.value = uintptr(68)

		conditions[2].fieldKey = cFWPM_CONDITION_IP_REMOTE_PORT
		conditions[2].matchType = cFWP_MATCH_EQUAL
		conditions[2].conditionValue._type = cFWP_UINT16
		conditions[2].conditionValue.value = uintptr(67)

		conditions[3].fieldKey = cFWPM_CONDITION_IP_REMOTE_ADDRESS
		conditions[3].matchType = cFWP_MATCH_EQUAL
		conditions[3].conditionValue._type = cFWP_UINT32
		conditions[3].conditionValue.value = uintptr(0xffffffff)

		displayData, err := createWtFwpmDisplayData0("Permit outbound DHCP request (IPv4)", "")
		if err != nil {
			return wrapErr(err)
		}

		filter := wtFwpmFilter0{
			displayData:         *displayData,
			providerKey:         &baseObjects.provider,
			layerKey:            cFWPM_LAYER_ALE_AUTH_CONNECT_V4,
			subLayerKey:         baseObjects.filters,
			weight:              filterWeight(weight),
			numFilterConditions: uint32(len(conditions)),
			filterCondition:     (*wtFwpmFilterCondition0)(unsafe.Pointer(&conditions)),
			action: wtFwpmAction0{
				_type: cFWP_ACTION_PERMIT,
			},
		}

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	//
	// #2 Inbound DHCP response on IPv4.
	//
	{
		var conditions [3]wtFwpmFilterCondition0

		conditions[0].fieldKey = cFWPM_CONDITION_IP_PROTOCOL
		conditions[0].matchType = cFWP_MATCH_EQUAL
		conditions[0].conditionValue._type = cFWP_UINT8
		conditions[0].conditionValue.value = uintptr(cIPPROTO_UDP)

		conditions[1].fieldKey = cFWPM_CONDITION_IP_LOCAL_PORT
		conditions[1].matchType = cFWP_MATCH_EQUAL
		conditions[1].conditionValue._type = cFWP_UINT16
		conditions[1].conditionValue.value = uintptr(68)

		conditions[2].fieldKey = cFWPM_CONDITION_IP_REMOTE_PORT
		conditions[2].matchType = cFWP_MATCH_EQUAL
		conditions[2].conditionValue._type = cFWP_UINT16
		conditions[2].conditionValue.value = uintptr(67)

		displayData, err := createWtFwpmDisplayData0("Permit inbound DHCP response (IPv4)", "")
		if err != nil {
			return wrapErr(err)
		}

		filter := wtFwpmFilter0{
			displayData:         *displayData,
			providerKey:         &baseObjects.provider,
			layerKey:            cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4,
			subLayerKey:         baseObjects.filters,
			weight:              filterWeight(weight),
			numFilterConditions: uint32(len(conditions)),
			filterCondition:     (*wtFwpmFilterCondition0)(unsafe.Pointer(&conditions)),
			action: wtFwpmAction0{
				_type: cFWP_ACTION_PERMIT,
			},
		}

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	return nil
}

func permitDHCPIPv6(backend wfpBackend, session uintptr, baseObjects *baseObjects, weight uint8) error {
	//
	// #1 Outbound DHCP request on IPv6.
	//
	{
		var conditions [6]wtFwpmFilterCondition0

		conditions[0].fieldKey = cFWPM_CONDITION_IP_PROTOCOL
		conditions[0].matchType = cFWP_MATCH_EQUAL
		conditions[0].conditionValue._type = cFWP_UINT8
		conditions[0].conditionValue.value = uintptr(cIPPROTO_UDP)

		conditions[1].fieldKey = cFWPM_CONDITION_IP_REMOTE_ADDRESS
		conditions[1].matchType = cFWP_MATCH_EQUAL
		conditions[1].conditionValue._type = cFWP_BYTE_ARRAY16_TYPE
		conditions[1].conditionValue.value = uintptr(unsafe.Pointer(&linkLocalDHCPMulticast))

		// Repeat the condition type for logical OR.
		conditions[2].fieldKey = cFWPM_CONDITION_IP_REMOTE_ADDRESS
		conditions[2].matchType = cFWP_MATCH_EQUAL
		conditions[2].conditionValue._type = cFWP_BYTE_ARRAY16_TYPE
		conditions[2].conditionValue.value = uintptr(unsafe.Pointer(&siteLocalDHCPMulticast))

		conditions[3].fieldKey = cFWPM_CONDITION_IP_REMOTE_PORT
		conditions[3].matchType = cFWP_MATCH_EQUAL
		conditions[3].conditionValue._type = cFWP_UINT16
		conditions[3].conditionValue.value = uintptr(547)

		conditions[4].fieldKey = cFWPM_CONDITION_IP_LOCAL_ADDRESS
		conditions[4].matchType = cFWP_MATCH_EQUAL
		conditions[4].conditionValue._type = cFWP_V6_ADDR_MASK
		conditions[4].conditionValue.value = uintptr(unsafe.Pointer(&linkLocal))

		conditions[5].fieldKey = cFWPM_CONDITION_IP_LOCAL_PORT
		conditions[5].matchType = cFWP_MATCH_EQUAL
		conditions[5].conditionValue._type = cFWP_UINT16
		conditions[5].conditionValue.value = uintptr(546)

		displayData, err := createWtFwpmDisplayData0("Permit outbound DHCP request (IPv6)", "")
		if err != nil {
			return wrapErr(err)
		}

		filter := wtFwpmFilter0{
			displayData:         *displayData,
			providerKey:         &baseObjects.provider,
			layerKey:            cFWPM_LAYER_ALE_AUTH_CONNECT_V6,
			subLayerKey:         baseObjects.filters,
			weight:              filterWeight(weight),
			numFilterConditions: uint32(len(conditions)),
			filterCondition:     (*wtFwpmFilterCondition0)(unsafe.Pointer(&conditions)),
			action: wtFwpmAction0{
				_type: cFWP_ACTION_PERMIT,
			},
		}

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	//
	// #2 Inbound DHCP response on IPv6.
	//
	{
		var conditions [5]wtFwpmFilterCondition0

		conditions[0].fieldKey = cFWPM_CONDITION_IP_PROTOCOL
		conditions[0].matchType = cFWP_MATCH_EQUAL
		conditions[0].conditionValue._type = cFWP_UINT8
		conditions[0].conditionValue.value = uintptr(cIPPROTO_UDP)

		conditions[1].fieldKey = cFWPM_CONDITION_IP_REMOTE_ADDRESS
		conditions[1].matchType = cFWP_MATCH_EQUAL
		conditions[1].conditionValue._type = cFWP_V6_ADDR_MASK
		conditions[1].conditionValue.value = uintptr(unsafe.Pointer(&linkLocal))

		conditions[2].fieldKey = cFWPM_CONDITION_IP_REMOTE_PORT
		conditions[2].matchType = cFWP_MATCH_EQUAL
		conditions[2].conditionValue._type = cFWP_UINT16
		conditions[2].conditionValue.value = uintptr(547)

		conditions[3].fieldKey = cFWPM_CONDITION_IP_LOCAL_ADDRESS
		conditions[3].matchType = cFWP_MATCH_EQUAL
		conditions[3].conditionValue._type = cFWP_V6_ADDR_MASK
		conditions[3].conditionValue.value = uintptr(unsafe.Pointer(&linkLocal))

		conditions[4].fieldKey = cFWPM_CONDITION_IP_LOCAL_PORT
		conditions[4].matchType = cFWP_MATCH_EQUAL
		conditions[4].conditionValue._type = cFWP_UINT16
		conditions[4].conditionValue.value = uintptr(546)

		displayData, err := createWtFwpmDisplayData0("Permit inbound DHCP response (IPv6)", "")
		if err != nil {
			return wrapErr(err)
		}

		filter := wtFwpmFilter0{
			displayData:         *displayData,
			providerKey:         &baseObjects.provider,
			layerKey:            cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6,
			subLayerKey:         baseObjects.filters,
			weight:              filterWeight(weight),
			numFilterConditions: uint32(len(conditions)),
			filterCondition:     (*wtFwpmFilterCondition0)(unsafe.Pointer(&conditions)),
			action: wtFwpmAction0{
				_type: cFWP_ACTION_PERMIT,
			},
		}

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	return nil
}

func permitNdp(backend wfpBackend, session uintptr, baseObjects *baseObjects, weight uint8) error {
	/* TODO: actually handle the hop limit somehow! The rules should vaguely be:
	 *  - icmpv6 133: must be outgoing, dst must be FF02::2/128, hop limit must be 255
	 *  - icmpv6 134: must be incoming, src must be FE80::/10, hop limit must be 255
	 *  - icmpv6 135: either incoming or outgoing, hop limit must be 255
	 *  - icmpv6 136: either incoming or outgoing, hop limit must be 255
	 *  - icmpv6 137: must be incoming, src must be FE80::/10, hop limit must be 255
	 */

	type filterDefinition struct {
		displayData *wtFwpmDisplayData0
		conditions  []wtFwpmFilterCondition0
		layer       windows.GUID
	}

	var defs []filterDefinition

	//
	// Router Solicitation Message
	// ICMP type 133, code 0. Outgoing.
	//
	{
		conditions := make([]wtFwpmFilterCondition0, 4)

		conditions[0].fieldKey = cFWPM_CONDITION_IP_PROTOCOL
		conditions[0].matchType = cFWP_MATCH_EQUAL
		conditions[0].conditionValue._type = cFWP_UINT8
		conditions[0].conditionValue.value = uintptr(cIPPROTO_ICMPV6)

		conditions[1].fieldKey = cFWPM_CONDITION_ICMP_TYPE
		conditions[1].matchType = cFWP_MATCH_EQUAL
		conditions[1].conditionValue._type = cFWP_UINT16
		conditions[1].conditionValue.value = uintptr(133)

		conditions[2].fieldKey = cFWPM_CONDITION_ICMP_CODE
		conditions[2].matchType = cFWP_MATCH_EQUAL
		conditions[2].conditionValue._type = cFWP_UINT16
		conditions[2].conditionValue.value = uintptr(0)

		conditions[3].fieldKey = cFWPM_CONDITION_IP_REMOTE_ADDRESS
		conditions[3].matchType = cFWP_MATCH_EQUAL
		conditions[3].conditionValue._type = cFWP_BYTE_ARRAY16_TYPE
		conditions[3].conditionValue.value = uintptr(unsafe.Pointer(&linkLocalRouterMulticast))

		displayData, err := createWtFwpmDisplayData0("Permit NDP type 133", "")
		if err != nil {
			return wrapErr(err)
		}

		defs = append(defs, filterDefinition{
			displayData: displayData,
			conditions:  conditions,
			layer:       cFWPM_LAYER_ALE_AUTH_CONNECT_V6,
		})
	}

	//
	// Router Advertisement Message
	// ICMP type 134, code 0. Incoming.
	//
	{
		conditions := make([]wtFwpmFilterCondition0, 4)

		conditions[0].fieldKey = cFWPM_CONDITION_IP_PROTOCOL
		conditions[0].matchType = cFWP_MATCH_EQUAL
		conditions[0].conditionValue._type = cFWP_UINT8
		conditions[0].conditionValue.value = uintptr(cIPPROTO_ICMPV6)

		conditions[1].fieldKey = cFWPM_CONDITION_ICMP_TYPE
		conditions[1].matchType = cFWP_MATCH_EQUAL
		conditions[1].conditionValue._type = cFWP_UINT16
		conditions[1].conditionValue.value = uintptr(134)

		conditions[2].fieldKey = cFWPM_CONDITION_ICMP_CODE
		conditions[2].matchType = cFWP_MATCH_EQUAL
		conditions[2].conditionValue._type = cFWP_UINT16
		conditions[2].conditionValue.value = uintptr(0)

		conditions[3].fieldKey = cFWPM_CONDITION_IP_REMOTE_ADDRESS
		conditions[3].matchType = cFWP_MATCH_EQUAL
		conditions[3].conditionValue._type = cFWP_V6_ADDR_MASK
		conditions[3].conditionValue.value = uintptr(unsafe.Pointer(&linkLocal))

		displayData, err := createWtFwpmDisplayData0("Permit NDP type 134", "")
		if err != nil {
			return wrapErr(err)
		}

		defs = append(defs, filterDefinition{
			displayData: displayData,
			conditions:  conditions,
			layer:       cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6,
		})
	}

	//
	// Neighbor Solicitation Message
	// ICMP type 135, code 0. Bi-directional.
	//
	{
		conditions := make([]wtFwpmFilterCondition0, 3)

		conditions[0].fieldKey = cFWPM_CONDITION_IP_PROTOCOL
		conditions[0].matchType = cFWP_MATCH_EQUAL
		conditions[0].conditionValue._type = cFWP_UINT8
		conditions[0].conditionValue.value = uintptr(cIPPROTO_ICMPV6)

		conditions[1].fieldKey = cFWPM_CONDITION_ICMP_TYPE
		conditions[1].matchType = cFWP_MATCH_EQUAL
		conditions[1].conditionValue._type = cFWP_UINT16
		conditions[1].conditionValue.value = uintptr(135)

		conditions[2].fieldKey = cFWPM_CONDITION_ICMP_CODE
		conditions[2].matchType = cFWP_MATCH_EQUAL
		conditions[2].conditionValue._type = cFWP_UINT16
		conditions[2].conditionValue.value = uintptr(0)

		displayData, err := createWtFwpmDisplayData0("Permit NDP type 135", "")
		if err != nil {
			return wrapErr(err)
		}

		defs = append(defs, filterDefinition{
			displayData: displayData,
			conditions:  conditions,
			layer:       cFWPM_LAYER_ALE_AUTH_CONNECT_V6,
		})

		defs = append(defs, filterDefinition{
			displayData: displayData,
			conditions:  conditions,
			layer:       cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6,
		})
	}

	//
	// Neighbor Advertisement Message
	// ICMP type 136, code 0. Bi-directional.
	//
	{
		conditions := make([]wtFwpmFilterCondition0, 3)

		conditions[0].fieldKey = cFWPM_CONDITION_IP_PROTOCOL
		conditions[0].matchType = cFWP_MATCH_EQUAL
		conditions[0].conditionValue._type = cFWP_UINT8
		conditions[0].conditionValue.value = uintptr(cIPPROTO_ICMPV6)

		conditions[1].fieldKey = cFWPM_CONDITION_ICMP_TYPE
		conditions[1].matchType = cFWP_MATCH_EQUAL
		conditions[1].conditionValue._type = cFWP_UINT16
		conditions[1].conditionValue.value = uintptr(136)

		conditions[2].fieldKey = cFWPM_CONDITION_ICMP_CODE
		conditions[2].matchType = cFWP_MATCH_EQUAL
		conditions[2].conditionValue._type = cFWP_UINT16
		conditions[2].conditionValue.value = uintptr(0)

		displayData, err := createWtFwpmDisplayData0("Permit NDP type 136", "")
		if err != nil {
			return wrapErr(err)
		}

		defs = append(defs, filterDefinition{
			displayData: displayData,
			conditions:  conditions,
			layer:       cFWPM_LAYER_ALE_AUTH_CONNECT_V6,
		})

		defs = append(defs, filterDefinition{
			displayData: displayData,
			conditions:  conditions,
			layer:       cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6,
		})
	}

	//
	// Redirect Message
	// ICMP type 137, code 0. Incoming.
	//
	{
		conditions := make([]wtFwpmFilterCondition0, 4)

		conditions[0].fieldKey = cFWPM_CONDITION_IP_PROTOCOL
		conditions[0].matchType = cFWP_MATCH_EQUAL
		conditions[0].conditionValue._type = cFWP_UINT8
		conditions[0].conditionValue.value = uintptr(cIPPROTO_ICMPV6)

		conditions[1].fieldKey = cFWPM_CONDITION_ICMP_TYPE
		conditions[1].matchType = cFWP_MATCH_EQUAL
		conditions[1].conditionValue._type = cFWP_UINT16
		conditions[1].conditionValue.value = uintptr(137)

		conditions[2].fieldKey = cFWPM_CONDITION_ICMP_CODE
		conditions[2].matchType = cFWP_MATCH_EQUAL
		conditions[2].conditionValue._type = cFWP_UINT16
		conditions[2].conditionValue.value = uintptr(0)

		conditions[3].fieldKey = cFWPM_CONDITION_IP_REMOTE_ADDRESS
		conditions[3].matchType = cFWP_MATCH_EQUAL
		conditions[3].conditionValue._type = cFWP_V6_ADDR_MASK
		conditions[3].conditionValue.value = uintptr(unsafe.Pointer(&linkLocal))

		displayData, err := createWtFwpmDisplayData0("Permit NDP type 137", "")
		if err != nil {
			return wrapErr(err)
		}

		defs = append(defs, filterDefinition{
			displayData: displayData,
			conditions:  conditions,
			layer:       cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6,
		})
	}

	filter := wtFwpmFilter0{
		providerKey: &baseObjects.provider,
		subLayerKey: baseObjects.filters,
		weight:      filterWeight(weight),
		action: wtFwpmAction0{
			_type: cFWP_ACTION_PERMIT,
		},
	}

	for _, definition := range defs {
		filter.displayData = *definition.displayData
		filter.layerKey = definition.layer
		filter.numFilterConditions = uint32(len(definition.conditions))
		filter.filterCondition = (*wtFwpmFilterCondition0)(unsafe.Pointer(&definition.conditions[0]))

		err := backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	return nil
}

func permitHyperV(backend wfpBackend, session uintptr, baseObjects *baseObjects, weight uint8) error {
	condition := wtFwpmFilterCondition0{
		fieldKey:  cFWPM_CONDITION_L2_FLAGS,
		matchType: cFWP_MATCH_EQUAL,
		conditionValue: wtFwpConditionValue0{
			_type: cFWP_UINT32,
			value: uintptr(cFWP_CONDITION_L2_IS_VM2VM),
		},
	}

	filter := wtFwpmFilter0{
		providerKey:         &baseObjects.provider,
		subLayerKey:         baseObjects.filters,
		weight:              filterWeight(weight),
		numFilterConditions: 1,
		filterCondition:     (*wtFwpmFilterCondition0)(unsafe.Pointer(&condition)),
		action: wtFwpmAction0{
			_type: cFWP_ACTION_PERMIT,
		},
	}

	//
	// #1 Outbound.
	//
	{
		displayData, err := createWtFwpmDisplayData0("Permit Hyper-V => Hyper-V outbound", "")
		if err != nil {
			return wrapErr(err)
		}

		filter.displayData = *displayData
		filter.layerKey = cFWPM_LAYER_OUTBOUND_MAC_FRAME_NATIVE

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	//
	// #2 Inbound.
	//
	{
		displayData, err := createWtFwpmDisplayData0("Permit Hyper-V => Hyper-V inbound", "")
		if err != nil {
			return wrapErr(err)
		}

		filter.displayData = *displayData
		filter.layerKey = cFWPM_LAYER_INBOUND_MAC_FRAME_NATIVE

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	return nil
}

type layerFilterDef struct {
	name  string
	layer windows.GUID
}

func blockAllLayers(backend wfpBackend, session uintptr, baseObjects *baseObjects, weight uint8, layers []layerFilterDef) error {
	filter := wtFwpmFilter0{
		providerKey: &baseObjects.provider,
		subLayerKey: baseObjects.filters,
		weight:      filterWeight(weight),
		action: wtFwpmAction0{
			_type: cFWP_ACTION_BLOCK,
		},
	}

	for _, def := range layers {
		displayData, err := createWtFwpmDisplayData0(def.name, "")
		if err != nil {
			return wrapErr(err)
		}

		filter.displayData = *displayData
		filter.layerKey = def.layer

		if err := backend.addFilter(session, &filter); err != nil {
			return wrapErr(err)
		}
	}

	return nil
}

// Block all traffic except what is explicitly permitted by other rules.
func blockAll(backend wfpBackend, session uintptr, baseObjects *baseObjects, weight uint8) error {
	return blockAllLayers(backend, session, baseObjects, weight, []layerFilterDef{
		{"Block all outbound (IPv4)", cFWPM_LAYER_ALE_AUTH_CONNECT_V4},
		{"Block all inbound (IPv4)", cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4},
		{"Block all outbound (IPv6)", cFWPM_LAYER_ALE_AUTH_CONNECT_V6},
		{"Block all inbound (IPv6)", cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6},
		{"Block all forwarded (IPv4)", cFWPM_LAYER_IPFORWARD_V4},
		{"Block all forwarded (IPv6)", cFWPM_LAYER_IPFORWARD_V6},
	})
}

func blockAllIPv6(backend wfpBackend, session uintptr, baseObjects *baseObjects, weight uint8) error {
	return blockAllLayers(backend, session, baseObjects, weight, []layerFilterDef{
		{"Block all outbound (IPv6)", cFWPM_LAYER_ALE_AUTH_CONNECT_V6},
		{"Block all inbound (IPv6)", cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6},
	})
}

func remotePortConditions(ports ...uint16) []wtFwpmFilterCondition0 {
	conditions := make([]wtFwpmFilterCondition0, 0, len(ports)+2)
	for _, port := range ports {
		conditions = append(conditions, wtFwpmFilterCondition0{
			fieldKey:  cFWPM_CONDITION_IP_REMOTE_PORT,
			matchType: cFWP_MATCH_EQUAL,
			conditionValue: wtFwpConditionValue0{
				_type: cFWP_UINT16,
				value: uintptr(port),
			},
		})
	}
	conditions = append(conditions,
		wtFwpmFilterCondition0{
			fieldKey:  cFWPM_CONDITION_IP_PROTOCOL,
			matchType: cFWP_MATCH_EQUAL,
			conditionValue: wtFwpConditionValue0{
				_type: cFWP_UINT8,
				value: uintptr(cIPPROTO_UDP),
			},
		},
		// Repeat the condition type for logical OR.
		wtFwpmFilterCondition0{
			fieldKey:  cFWPM_CONDITION_IP_PROTOCOL,
			matchType: cFWP_MATCH_EQUAL,
			conditionValue: wtFwpConditionValue0{
				_type: cFWP_UINT8,
				value: uintptr(cIPPROTO_TCP),
			},
		},
	)
	return conditions
}

func blockRemotePortsOnALELayers(backend wfpBackend, session uintptr, baseObjects *baseObjects, weight uint8, label string, ports ...uint16) error {
	conditions := remotePortConditions(ports...)
	filter := wtFwpmFilter0{
		providerKey:         &baseObjects.provider,
		subLayerKey:         baseObjects.filters,
		weight:              filterWeight(weight),
		numFilterConditions: uint32(len(conditions)),
		filterCondition:     (*wtFwpmFilterCondition0)(unsafe.Pointer(&conditions[0])),
		action: wtFwpmAction0{
			_type: cFWP_ACTION_BLOCK,
		},
	}

	for _, def := range []layerFilterDef{
		{"Block " + label + " outbound (IPv4)", cFWPM_LAYER_ALE_AUTH_CONNECT_V4},
		{"Block " + label + " inbound (IPv4)", cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4},
		{"Block " + label + " outbound (IPv6)", cFWPM_LAYER_ALE_AUTH_CONNECT_V6},
		{"Block " + label + " inbound (IPv6)", cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6},
	} {
		displayData, err := createWtFwpmDisplayData0(def.name, "")
		if err != nil {
			return wrapErr(err)
		}
		filter.displayData = *displayData
		filter.layerKey = def.layer
		if err := backend.addFilter(session, &filter); err != nil {
			return wrapErr(err)
		}
	}

	runtime.KeepAlive(conditions)
	return nil
}

func blockNameResolution(backend wfpBackend, session uintptr, baseObjects *baseObjects, weight uint8) error {
	return blockRemotePortsOnALELayers(backend, session, baseObjects, weight, "DNS/DoT/LLMNR", 53, 853, 5355)
}

// Block all DNS traffic except towards specified DNS servers.
func blockDNS(backend wfpBackend, except []netip.Addr, session uintptr, baseObjects *baseObjects, weightAllow, weightDeny uint8) error {
	if weightDeny >= weightAllow {
		return errors.New("The allow weight must be greater than the deny weight")
	}

	if err := blockRemotePortsOnALELayers(backend, session, baseObjects, weightDeny, "DNS", 53); err != nil {
		return err
	}

	denyConditions := remotePortConditions(53)

	allowConditionsV4 := make([]wtFwpmFilterCondition0, 0, len(denyConditions)+len(except))
	allowConditionsV4 = append(allowConditionsV4, denyConditions...)
	for _, ip := range except {
		if !ip.Is4() {
			continue
		}
		allowConditionsV4 = append(allowConditionsV4, wtFwpmFilterCondition0{
			fieldKey:  cFWPM_CONDITION_IP_REMOTE_ADDRESS,
			matchType: cFWP_MATCH_EQUAL,
			conditionValue: wtFwpConditionValue0{
				_type: cFWP_UINT32,
				value: uintptr(binary.BigEndian.Uint32(ip.AsSlice())),
			},
		})
	}

	storedPointers := make([]*wtFwpByteArray16, 0, len(except))
	allowConditionsV6 := make([]wtFwpmFilterCondition0, 0, len(denyConditions)+len(except))
	allowConditionsV6 = append(allowConditionsV6, denyConditions...)
	for _, ip := range except {
		if !ip.Is6() {
			continue
		}
		address := wtFwpByteArray16{byteArray16: ip.As16()}
		allowConditionsV6 = append(allowConditionsV6, wtFwpmFilterCondition0{
			fieldKey:  cFWPM_CONDITION_IP_REMOTE_ADDRESS,
			matchType: cFWP_MATCH_EQUAL,
			conditionValue: wtFwpConditionValue0{
				_type: cFWP_BYTE_ARRAY16_TYPE,
				value: uintptr(unsafe.Pointer(&address)),
			},
		})
		storedPointers = append(storedPointers, &address)
	}

	filter := wtFwpmFilter0{
		providerKey:         &baseObjects.provider,
		subLayerKey:         baseObjects.filters,
		weight:              filterWeight(weightAllow),
		numFilterConditions: uint32(len(allowConditionsV4)),
		filterCondition:     (*wtFwpmFilterCondition0)(unsafe.Pointer(&allowConditionsV4[0])),
		action: wtFwpmAction0{
			_type: cFWP_ACTION_PERMIT,
		},
	}

	//
	// #5 Allow IPv4 outbound DNS.
	//
	if len(allowConditionsV4) > len(denyConditions) {
		displayData, err := createWtFwpmDisplayData0("Allow DNS outbound (IPv4)", "")
		if err != nil {
			return wrapErr(err)
		}

		filter.displayData = *displayData
		filter.layerKey = cFWPM_LAYER_ALE_AUTH_CONNECT_V4

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	//
	// #6 Allow IPv4 inbound DNS.
	//
	if len(allowConditionsV4) > len(denyConditions) {
		displayData, err := createWtFwpmDisplayData0("Allow DNS inbound (IPv4)", "")
		if err != nil {
			return wrapErr(err)
		}

		filter.displayData = *displayData
		filter.layerKey = cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	filter.filterCondition = (*wtFwpmFilterCondition0)(unsafe.Pointer(&allowConditionsV6[0]))
	filter.numFilterConditions = uint32(len(allowConditionsV6))

	//
	// #7 Allow IPv6 outbound DNS.
	//
	if len(allowConditionsV6) > len(denyConditions) {
		displayData, err := createWtFwpmDisplayData0("Allow DNS outbound (IPv6)", "")
		if err != nil {
			return wrapErr(err)
		}

		filter.displayData = *displayData
		filter.layerKey = cFWPM_LAYER_ALE_AUTH_CONNECT_V6

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	//
	// #8 Allow IPv6 inbound DNS.
	//
	if len(allowConditionsV6) > len(denyConditions) {
		displayData, err := createWtFwpmDisplayData0("Allow DNS inbound (IPv6)", "")
		if err != nil {
			return wrapErr(err)
		}

		filter.displayData = *displayData
		filter.layerKey = cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6

		err = backend.addFilter(session, &filter)
		if err != nil {
			return wrapErr(err)
		}
	}

	runtime.KeepAlive(allowConditionsV4)
	runtime.KeepAlive(allowConditionsV6)
	runtime.KeepAlive(storedPointers)

	return nil
}
