//go:build windows

package main

import "testing"

func TestChooseOutboundRoute(t *testing.T) {
	tests := []struct {
		name   string
		routes []outboundRoute
		want   string
	}{
		{"localized wifi beats Radmin", []outboundRoute{{"Radmin VPN", 5, 9256, 1}, {"Беспроводная сеть", 17, 0, 50}}, "Беспроводная сеть"},
		{"interface metric is included", []outboundRoute{{"Ethernet", 10, 5, 100}, {"Wi-Fi", 17, 20, 10}}, "Wi-Fi"},
		{"ignore own tunnel", []outboundRoute{{"Yumi", 58, 0, 0}, {"Yumi-abcd1234", 59, 0, 0}, {"Беспроводная сеть", 17, 0, 50}}, "Беспроводная сеть"},
		{"deterministic tie", []outboundRoute{{"Wi-Fi", 17, 0, 50}, {"Ethernet", 10, 0, 50}}, "Ethernet"},
		{"metric does not overflow", []outboundRoute{{"Bad", 5, ^uint32(0), 10}, {"Wi-Fi", 17, 0, 50}}, "Wi-Fi"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got, err := chooseOutboundRoute(test.routes)
			if err != nil || got != test.want {
				t.Fatalf("got %q, %v; want %q", got, err, test.want)
			}
		})
	}
	if _, err := chooseOutboundRoute(nil); err == nil {
		t.Fatal("no default route must fail before starting TUN")
	}
}

func TestConfigUsesResolvedOutboundInterface(t *testing.T) {
	document := buildConfig(fixture(), 19090, "user", "password", false, "Беспроводная сеть")
	settings := document["inbounds"].([]obj)[0]["settings"].(obj)
	if settings["autoOutboundsInterface"] != "Беспроводная сеть" {
		t.Fatal("resolved interface was replaced by auto")
	}
	parse(t, document)
}
