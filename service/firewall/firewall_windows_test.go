//go:build windows

package firewall

import (
	"net/netip"
	"testing"
	"unsafe"

	"golang.org/x/sys/windows"
)

type recordedCondition struct {
	fieldKey windows.GUID
	condType wtFwpDataType
	value    uintptr
}

type recordedFilter struct {
	name       string
	layer      windows.GUID
	weight     uint8
	action     wtFwpActionType
	conditions []recordedCondition
}

type wfpRecorder struct {
	filters []recordedFilter
	closed  int
	dummy   [4]byte
}

func (r *wfpRecorder) backend() wfpBackend {
	return wfpBackend{
		openSession: func() (uintptr, error) { return 0x1234, nil },
		closeSession: func(uintptr) error {
			r.closed++
			return nil
		},
		runTx: func(session uintptr, op wfpObjectInstaller) error {
			return op(session)
		},
		registerBase: func(uintptr) (*baseObjects, error) {
			return &baseObjects{}, nil
		},
		addFilter: func(_ uintptr, f *wtFwpmFilter0) error {
			var conds []recordedCondition
			if f.numFilterConditions > 0 && f.filterCondition != nil {
				raw := unsafe.Slice(f.filterCondition, f.numFilterConditions)
				for _, c := range raw {
					conds = append(conds, recordedCondition{
						fieldKey: c.fieldKey,
						condType: c.conditionValue._type,
						value:    c.conditionValue.value,
					})
				}
			}
			r.filters = append(r.filters, recordedFilter{
				name:       windows.UTF16PtrToString(f.displayData.name),
				layer:      f.layerKey,
				weight:     uint8(f.weight.value),
				action:     f.action._type,
				conditions: conds,
			})
			return nil
		},
		currentAppID: func() (*wtFwpByteBlob, func(), error) {
			return &wtFwpByteBlob{size: uint32(len(r.dummy)), data: &r.dummy[0]}, nil, nil
		},
	}
}

func findFilters(filters []recordedFilter, layer windows.GUID, action wtFwpActionType) []recordedFilter {
	var out []recordedFilter
	for _, f := range filters {
		if f.layer == layer && f.action == action {
			out = append(out, f)
		}
	}
	return out
}

func hasPortAndProtocols(f recordedFilter, ports ...uint16) bool {
	seenPort := map[uint16]bool{}
	seenUDP, seenTCP := false, false
	for _, c := range f.conditions {
		switch c.fieldKey {
		case cFWPM_CONDITION_IP_REMOTE_PORT:
			seenPort[uint16(c.value)] = true
		case cFWPM_CONDITION_IP_PROTOCOL:
			if wtIPProto(c.value) == cIPPROTO_UDP {
				seenUDP = true
			}
			if wtIPProto(c.value) == cIPPROTO_TCP {
				seenTCP = true
			}
		}
	}
	if !seenUDP || !seenTCP {
		return false
	}
	for _, p := range ports {
		if !seenPort[p] {
			return false
		}
	}
	return true
}

func TestProtectTunnelSignatureAndIPv6Behavior(t *testing.T) {
	// Compile-time check for the frozen ProtectTunnel and ProtectDNS signatures.
	var _ func([]netip.Addr, bool, bool) (Firewall, error) = ProtectTunnel
	var _ func([]netip.Addr) (Firewall, error) = ProtectDNS

	dns := []netip.Addr{netip.MustParseAddr("172.19.0.2")}

	t.Run("blockIPv6=false matches ProtectDNS baseline", func(t *testing.T) {
		rec := &wfpRecorder{}
		fw, err := protectTunnelWithBackend(rec.backend(), dns, false, false)
		if err != nil {
			t.Fatal(err)
		}
		defer fw.Close()

		for _, layer := range []windows.GUID{cFWPM_LAYER_ALE_AUTH_CONNECT_V6, cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6} {
			for _, f := range findFilters(rec.filters, layer, cFWP_ACTION_BLOCK) {
				if len(f.conditions) == 0 {
					t.Fatalf("unexpected unconditional IPv6 block filter when blockIPv6=false: %+v", f)
				}
			}
		}
	})

	t.Run("blockIPv6=true bypassLAN=false blocks all non-loopback IPv6 below loopback and service", func(t *testing.T) {
		rec := &wfpRecorder{}
		fw, err := protectTunnelWithBackend(rec.backend(), dns, true, false)
		if err != nil {
			t.Fatal(err)
		}
		defer fw.Close()

		var serviceWeight, loopbackWeight, dnsBlockWeight uint8
		for _, f := range findFilters(rec.filters, cFWPM_LAYER_ALE_AUTH_CONNECT_V6, cFWP_ACTION_PERMIT) {
			if len(f.conditions) == 2 && f.conditions[0].fieldKey == cFWPM_CONDITION_ALE_APP_ID {
				serviceWeight = f.weight
			}
			if len(f.conditions) == 1 && f.conditions[0].fieldKey == cFWPM_CONDITION_FLAGS {
				loopbackWeight = f.weight
			}
			if len(f.conditions) > 0 && f.conditions[0].fieldKey == cFWPM_CONDITION_IP_REMOTE_ADDRESS {
				t.Fatalf("unexpected IPv6 LAN permit when bypassLAN=false: %+v", f)
			}
		}
		for _, f := range findFilters(rec.filters, cFWPM_LAYER_ALE_AUTH_CONNECT_V6, cFWP_ACTION_BLOCK) {
			if hasPortAndProtocols(f, 53) {
				dnsBlockWeight = f.weight
			}
		}
		if serviceWeight != 15 || loopbackWeight != 13 {
			t.Fatalf("unexpected weights service=%d loopback=%d", serviceWeight, loopbackWeight)
		}

		for _, layer := range []windows.GUID{cFWPM_LAYER_ALE_AUTH_CONNECT_V6, cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6} {
			var found bool
			for _, f := range findFilters(rec.filters, layer, cFWP_ACTION_BLOCK) {
				if len(f.conditions) == 0 {
					found = true
					if f.weight >= loopbackWeight || f.weight >= serviceWeight || f.weight >= dnsBlockWeight {
						t.Fatalf("IPv6 block weight %d must be below loopback(%d), service(%d), and dnsBlock(%d)", f.weight, loopbackWeight, serviceWeight, dnsBlockWeight)
					}
				}
			}
			if !found {
				t.Fatalf("missing unconditional IPv6 block on layer %v", layer)
			}
		}
	})

	t.Run("blockIPv6=true bypassLAN=true permits IPv6 LAN and NDP above IPv6 block and below DNS block", func(t *testing.T) {
		rec := &wfpRecorder{}
		fw, err := protectTunnelWithBackend(rec.backend(), dns, true, true)
		if err != nil {
			t.Fatal(err)
		}
		defer fw.Close()

		var lanWeight, ndpWeight, dnsBlockWeight, v6BlockWeight uint8
		for _, f := range findFilters(rec.filters, cFWPM_LAYER_ALE_AUTH_CONNECT_V6, cFWP_ACTION_PERMIT) {
			if len(f.conditions) == len(lanV6) && f.conditions[0].fieldKey == cFWPM_CONDITION_IP_REMOTE_ADDRESS {
				lanWeight = f.weight
			}
			if len(f.conditions) >= 3 && f.conditions[0].fieldKey == cFWPM_CONDITION_IP_PROTOCOL {
				ndpWeight = f.weight
			}
		}
		for _, f := range findFilters(rec.filters, cFWPM_LAYER_ALE_AUTH_CONNECT_V6, cFWP_ACTION_BLOCK) {
			if hasPortAndProtocols(f, 53) {
				dnsBlockWeight = f.weight
			}
			if len(f.conditions) == 0 {
				v6BlockWeight = f.weight
			}
		}
		if lanWeight == 0 || ndpWeight == 0 || v6BlockWeight == 0 {
			t.Fatalf("missing expected weights lan=%d ndp=%d v6Block=%d", lanWeight, ndpWeight, v6BlockWeight)
		}
		if !(v6BlockWeight < lanWeight && lanWeight < dnsBlockWeight && v6BlockWeight < ndpWeight && ndpWeight < dnsBlockWeight) {
			t.Fatalf("invalid weight ordering: v6Block=%d lan=%d ndp=%d dnsBlock=%d", v6BlockWeight, lanWeight, ndpWeight, dnsBlockWeight)
		}
	})
}

func TestEnableBlocksDNSDoTLLMNRAndEnforcesIPForwardLayers(t *testing.T) {
	t.Run("permitLAN=true blocks 53/853/5355 above LAN permit and below TUN/loopback/service; LUID=0 blocks IPFORWARD", func(t *testing.T) {
		rec := &wfpRecorder{}
		fw, err := enableWithBackend(rec.backend(), TunRef{}, true)
		if err != nil {
			t.Fatal(err)
		}
		fw.Close()
		if rec.closed != 1 {
			t.Fatalf("Close did not close session once: %d", rec.closed)
		}

		for _, layer := range []windows.GUID{
			cFWPM_LAYER_ALE_AUTH_CONNECT_V4,
			cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4,
			cFWPM_LAYER_ALE_AUTH_CONNECT_V6,
			cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6,
		} {
			var nameBlockWeight, lanWeight, loopbackWeight, serviceWeight uint8
			for _, f := range findFilters(rec.filters, layer, cFWP_ACTION_BLOCK) {
				if hasPortAndProtocols(f, 53, 853, 5355) {
					nameBlockWeight = f.weight
				}
			}
			for _, f := range findFilters(rec.filters, layer, cFWP_ACTION_PERMIT) {
				switch {
				case len(f.conditions) > 0 && f.conditions[0].fieldKey == cFWPM_CONDITION_IP_REMOTE_ADDRESS:
					lanWeight = f.weight
				case len(f.conditions) == 1 && f.conditions[0].fieldKey == cFWPM_CONDITION_FLAGS:
					loopbackWeight = f.weight
				case len(f.conditions) == 2 && f.conditions[0].fieldKey == cFWPM_CONDITION_ALE_APP_ID:
					serviceWeight = f.weight
				}
			}
			if nameBlockWeight == 0 || lanWeight == 0 {
				t.Fatalf("layer %v missing nameBlock(%d) or lanPermit(%d)", layer, nameBlockWeight, lanWeight)
			}
			if !(lanWeight < nameBlockWeight && nameBlockWeight < loopbackWeight && loopbackWeight < serviceWeight) {
				t.Fatalf("layer %v invalid weights: lan=%d nameBlock=%d loopback=%d service=%d", layer, lanWeight, nameBlockWeight, loopbackWeight, serviceWeight)
			}
		}

		for _, fwdLayer := range []windows.GUID{cFWPM_LAYER_IPFORWARD_V4, cFWPM_LAYER_IPFORWARD_V6} {
			blocks := findFilters(rec.filters, fwdLayer, cFWP_ACTION_BLOCK)
			if len(blocks) != 1 || blocks[0].weight != 0 || len(blocks[0].conditions) != 0 {
				t.Fatalf("layer %v expected single weight-0 block, got %+v", fwdLayer, blocks)
			}
			permits := findFilters(rec.filters, fwdLayer, cFWP_ACTION_PERMIT)
			if len(permits) != 0 {
				t.Fatalf("layer %v expected no permits when LUID=0, got %+v", fwdLayer, permits)
			}
		}
	})

	t.Run("non-zero TUN LUID permits TUN above 53/853/5355 block and permits IPFORWARD for TUN LUID", func(t *testing.T) {
		const tunLUID = uint64(0x0006000001000000)
		rec := &wfpRecorder{}
		fw, err := enableWithBackend(rec.backend(), TunRef{LUID: tunLUID, Name: "Yumi"}, true)
		if err != nil {
			t.Fatal(err)
		}
		defer fw.Close()

		var nameBlockWeight, tunWeight uint8
		for _, f := range findFilters(rec.filters, cFWPM_LAYER_ALE_AUTH_CONNECT_V4, cFWP_ACTION_BLOCK) {
			if hasPortAndProtocols(f, 53, 853, 5355) {
				nameBlockWeight = f.weight
			}
		}
		for _, f := range findFilters(rec.filters, cFWPM_LAYER_ALE_AUTH_CONNECT_V4, cFWP_ACTION_PERMIT) {
			if len(f.conditions) == 1 && f.conditions[0].fieldKey == cFWPM_CONDITION_IP_LOCAL_INTERFACE && f.conditions[0].condType == cFWP_UINT64 && f.conditions[0].value != 0 {
				tunWeight = f.weight
			}
		}
		if !(nameBlockWeight < tunWeight) {
			t.Fatalf("TUN permit weight %d must exceed DNS/DoT/LLMNR block weight %d", tunWeight, nameBlockWeight)
		}

		for _, fwdLayer := range []windows.GUID{cFWPM_LAYER_IPFORWARD_V4, cFWPM_LAYER_IPFORWARD_V6} {
			permits := findFilters(rec.filters, fwdLayer, cFWP_ACTION_PERMIT)
			var seenForward, seenLocal bool
			for _, p := range permits {
				if p.weight != tunWeight || len(p.conditions) != 1 || p.conditions[0].condType != cFWP_UINT64 || p.conditions[0].value == 0 {
					t.Fatalf("unexpected IPFORWARD permit %+v", p)
				}
				if p.conditions[0].fieldKey == cFWPM_CONDITION_IP_FORWARD_INTERFACE {
					seenForward = true
				}
				if p.conditions[0].fieldKey == cFWPM_CONDITION_IP_LOCAL_INTERFACE {
					seenLocal = true
				}
			}
			if !seenForward || !seenLocal {
				t.Fatalf("layer %v missing TUN IPFORWARD permits (forward=%v local=%v)", fwdLayer, seenForward, seenLocal)
			}
		}
	})
}
