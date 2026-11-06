/* SPDX-License-Identifier: MIT
 *
 * Yumi Desktop addition to the WireGuard firewall rules.
 */

package firewall

import (
	"runtime"
	"unsafe"
)

/*
The local network, let through the kill switch when the tunnel lets it bypass.

"Bypass local network" routes these ranges around the tunnel, and Windows sends them out of the
physical adapter on its own on-link routes — so a kill switch that blocked everything but the tunnel
would quietly undo the setting: no printer, no NAS, and no phone handing a subscription over by QR,
because that transfer is an inbound connection from the LAN to the window.

Multicast and the limited broadcast are included because that is how a LAN is discovered at all:
mDNS, SSDP, NetBIOS. None of them leave the link.
*/
var (
	lanV4 = []wtFwpV4AddrAndMask{
		{0x0A000000, 0xFF000000}, // 10.0.0.0/8
		{0xAC100000, 0xFFF00000}, // 172.16.0.0/12
		{0xC0A80000, 0xFFFF0000}, // 192.168.0.0/16
		{0xA9FE0000, 0xFFFF0000}, // 169.254.0.0/16
		{0xE0000000, 0xF0000000}, // 224.0.0.0/4
		{0xFFFFFFFF, 0xFFFFFFFF}, // 255.255.255.255/32
	}
	lanV6 = []wtFwpV6AddrAndMask{
		{[16]uint8{0xfc}, 7},        // fc00::/7
		{[16]uint8{0xfe, 0x80}, 10}, // fe80::/10
		{[16]uint8{0xff}, 8},        // ff00::/8
	}
)

func permitLocalNetwork(session uintptr, baseObjects *baseObjects, weight uint8) error {
	// Several conditions on the same field are an OR, which is what lets one filter per layer
	// carry every range.
	conditionsV4 := make([]wtFwpmFilterCondition0, len(lanV4))
	for i := range lanV4 {
		conditionsV4[i] = wtFwpmFilterCondition0{
			fieldKey:  cFWPM_CONDITION_IP_REMOTE_ADDRESS,
			matchType: cFWP_MATCH_EQUAL,
			conditionValue: wtFwpConditionValue0{
				_type: cFWP_V4_ADDR_MASK,
				value: uintptr(unsafe.Pointer(&lanV4[i])),
			},
		}
	}
	conditionsV6 := make([]wtFwpmFilterCondition0, len(lanV6))
	for i := range lanV6 {
		conditionsV6[i] = wtFwpmFilterCondition0{
			fieldKey:  cFWPM_CONDITION_IP_REMOTE_ADDRESS,
			matchType: cFWP_MATCH_EQUAL,
			conditionValue: wtFwpConditionValue0{
				_type: cFWP_V6_ADDR_MASK,
				value: uintptr(unsafe.Pointer(&lanV6[i])),
			},
		}
	}

	filterID := uint64(0)
	for _, def := range []struct {
		name       string
		v6         bool
		conditions []wtFwpmFilterCondition0
		inbound    bool
	}{
		{"Permit outbound to the local network (IPv4)", false, conditionsV4, false},
		{"Permit inbound from the local network (IPv4)", false, conditionsV4, true},
		{"Permit outbound to the local network (IPv6)", true, conditionsV6, false},
		{"Permit inbound from the local network (IPv6)", true, conditionsV6, true},
	} {
		displayData, err := createWtFwpmDisplayData0(def.name, "")
		if err != nil {
			return wrapErr(err)
		}
		filter := wtFwpmFilter0{
			displayData:         *displayData,
			providerKey:         &baseObjects.provider,
			subLayerKey:         baseObjects.filters,
			weight:              filterWeight(weight),
			numFilterConditions: uint32(len(def.conditions)),
			filterCondition:     (*wtFwpmFilterCondition0)(unsafe.Pointer(&def.conditions[0])),
			action: wtFwpmAction0{
				_type: cFWP_ACTION_PERMIT,
			},
		}
		switch {
		case !def.v6 && !def.inbound:
			filter.layerKey = cFWPM_LAYER_ALE_AUTH_CONNECT_V4
		case !def.v6 && def.inbound:
			filter.layerKey = cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4
		case def.v6 && !def.inbound:
			filter.layerKey = cFWPM_LAYER_ALE_AUTH_CONNECT_V6
		default:
			filter.layerKey = cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6
		}
		err = fwpmFilterAdd0(session, &filter, 0, &filterID)
		if err != nil {
			return wrapErr(err)
		}
	}

	runtime.KeepAlive(conditionsV4)
	runtime.KeepAlive(conditionsV6)
	return nil
}
