package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/geodata"
	"github.com/xtls/xray-core/core"
	xoutbound "github.com/xtls/xray-core/features/outbound"
	"github.com/xtls/xray-core/infra/conf/serial"
	"google.golang.org/protobuf/proto"
)

func TestFragmentValidationAndInjection(t *testing.T) {
	for _, bad := range []FragmentSpec{
		{Packets: "invalid", Length: "10-20", Interval: "2-5"},
		{Packets: "tlshello", Length: "30-10", Interval: "2-5"},
		{Packets: "tlshello", Length: "10-20", Interval: "0-5"},
		{Packets: "1-3", Length: "10-20", Interval: "2-5", MaxSplit: "0"},
	} {
		if err := validateFragmentSpec(bad); err == nil {
			t.Fatalf("expected error for bad fragment spec %+v", bad)
		}
	}

	req := fixture()
	req.Outbound["streamSettings"] = obj{"network": "tcp", "security": "tls", "tlsSettings": obj{"serverName": "edge.example.com"}}
	req.Fragment = &FragmentSpec{Packets: "tlshello", Length: "20-50", Interval: "2-5", MaxSplit: "8"}
	raw, _ := json.Marshal(req)
	decoded, err := decodeRequest(raw)
	if err != nil {
		t.Fatalf("decode valid fragment request: %v", err)
	}

	// Client-supplied sockopt must still be refused across privilege boundary.
	clientSockopt := cloneJSONMap(req.Outbound)
	clientSockopt["streamSettings"].(obj)["sockopt"] = obj{"dialerProxy": "frag"}
	reqBad := fixture()
	reqBad.Outbound = clientSockopt
	rawBad, _ := json.Marshal(reqBad)
	if _, err := decodeRequest(rawBad); err == nil {
		t.Fatal("decodeRequest accepted client sockopt")
	}

	cats := geoCategories{}
	doc := buildConfigWithGeo(decoded, 19090, "u", "p", false, false, cats, "")
	parse(t, doc)

	outbounds := doc["outbounds"].([]obj)
	expectedTag := fragmentTag(*req.Fragment)
	foundProxyDialer := false
	foundFragOutbound := false
	for _, ob := range outbounds {
		if ob["tag"] == "proxy-node" {
			if ss, ok := ob["streamSettings"].(obj); ok {
				if so, ok := ss["sockopt"].(obj); ok && so["dialerProxy"] == expectedTag {
					foundProxyDialer = true
				}
			}
		}
		if ob["tag"] == expectedTag && ob["protocol"] == "freedom" {
			if settings, ok := ob["settings"].(obj); ok && settings["fragment"] != nil {
				foundFragOutbound = true
			}
		}
	}
	if !foundProxyDialer || !foundFragOutbound {
		t.Fatalf("fragment outbound or dialerProxy not injected (dialer=%v frag=%v)", foundProxyDialer, foundFragOutbound)
	}
}

func TestLiveFragmentHotSwap(t *testing.T) {
	member1 := obj{
		"tag":      "proxy-m1",
		"protocol": "socks",
		"settings": obj{"servers": []obj{{"address": "127.0.0.1", "port": 1080}}},
	}
	member2 := obj{
		"tag":            "proxy-m2",
		"protocol":       "vless",
		"settings":       obj{"vnext": []obj{{"address": "198.51.100.2", "port": 443, "users": []obj{{"id": "11111111-2222-3333-4444-555555555555", "encryption": "none"}}}}},
		"streamSettings": obj{"network": "tcp", "security": "tls", "tlsSettings": obj{"serverName": "cdn.example.com"}},
	}
	data, _ := json.Marshal(obj{
		"log":       obj{"loglevel": "none"},
		"outbounds": []obj{cloneJSONMap(member1)},
	})
	cfg, err := serial.LoadJSONConfig(bytes.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	cfg.Outbound[0].Tag = "proxy-node"
	instance, err := core.New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	firstHandler := instance.GetFeature(xoutbound.ManagerType()).(xoutbound.Manager).GetHandler("proxy-node")
	spec := &OutboundGroup{
		Members:  []map[string]any{member1, member2},
		Strategy: "cost",
		MemberFragments: map[string]FragmentSpec{
			"proxy-m2": {Packets: "1-3", Length: "10-20", Interval: "2-5"},
		},
	}
	grouped, err := prepareGroupOutbound(instance, spec, firstHandler)
	if err != nil {
		t.Fatalf("prepareGroupOutbound failed with fragment: %v", err)
	}
	_ = grouped.Close()
	fragTag := fragmentTag(spec.MemberFragments["proxy-m2"])
	if h := instance.GetFeature(xoutbound.ManagerType()).(xoutbound.Manager).GetHandler(fragTag); h == nil {
		t.Fatalf("expected live fragment outbound %s to be registered", fragTag)
	}
}

func TestDoHValidationAndPreserveServerName(t *testing.T) {
	if err := validateDoHURL("https://1.1.1.1/dns-query"); err != nil {
		t.Fatalf("valid DoH URL rejected: %v", err)
	}
	for _, bad := range []string{"", "http://1.1.1.1/dns-query", "https://localhost/dns-query", "https://127.0.0.1/dns-query", "https://[::1]/dns-query", "https://fakedns/dns-query"} {
		if err := validateDoHURL(bad); err == nil {
			t.Fatalf("bad DoH URL accepted: %q", bad)
		}
	}

	wsOut := obj{"streamSettings": obj{"network": "ws", "security": "tls"}}
	preserveServerName(wsOut, "origin.example.com")
	wsSS := wsOut["streamSettings"].(obj)
	if wsSS["tlsSettings"].(obj)["serverName"] != "origin.example.com" {
		t.Fatal("WS TLS SNI not preserved")
	}
	if wsSS["wsSettings"].(obj)["headers"].(obj)["Host"] != "origin.example.com" {
		t.Fatal("WS Host header not preserved")
	}

	grpcOut := obj{"streamSettings": obj{"network": "grpc", "security": "reality", "grpcSettings": obj{"serviceName": "svc"}}}
	preserveServerName(grpcOut, "grpc.example.com")
	gSS := grpcOut["streamSettings"].(obj)
	if gSS["realitySettings"].(obj)["serverName"] != "grpc.example.com" {
		t.Fatal("gRPC REALITY SNI not preserved")
	}
}

func TestCustomAndProviderRoutingWithQuicAdsAndHijackDNS(t *testing.T) {
	geoDir := t.TempDir()
	sites, err := proto.Marshal(&geodata.GeoSiteList{Entry: []*geodata.GeoSite{
		{Code: "CATEGORY-RU", Domain: []*geodata.Domain{{Type: geodata.Domain_Domain, Value: "ru"}}},
		{Code: "CATEGORY-ADS-ALL", Domain: []*geodata.Domain{{Type: geodata.Domain_Domain, Value: "ads.example"}}},
	}})
	if err != nil {
		t.Fatal(err)
	}
	ips, err := proto.Marshal(&geodata.GeoIPList{Entry: []*geodata.GeoIP{{Code: "RU", Cidr: []*geodata.CIDR{{Ip: []byte{5, 8, 0, 0}, Prefix: 16}}}}})
	if err != nil {
		t.Fatal(err)
	}
	if os.WriteFile(filepath.Join(geoDir, "geosite.dat"), sites, 0o644) != nil || os.WriteFile(filepath.Join(geoDir, "geoip.dat"), ips, 0o644) != nil {
		t.Fatal("write")
	}
	t.Setenv("XRAY_LOCATION_ASSET", geoDir)
	cats := loadGeoCategories(geoDir)

	req := fixture()
	hijackFalse := false
	req.Options.DirectDNS = "9.9.9.9"
	req.Options.HijackDNS = &hijackFalse
	req.Options.BlockQuic = true
	req.Options.BlockAds = true
	req.Options.CustomRules = &CustomRulesSpec{
		Proxy:  []string{"domain:proxy.example"},
		Direct: []string{"192.0.2.0/24"},
		Block:  []string{"domain:blocked.example"},
	}
	req.Options.ProviderProfile = &ProviderProfileSpec{
		Rules: []ProviderRuleSpec{
			{Match: map[string]any{"domain": []any{"geosite:category-ru", "geosite:unknown-category"}}, Target: "direct"},
			{Match: map[string]any{"domain": []any{"geosite:unknown-only"}}, Target: "block"},
		},
		DNS: &ProviderDnsSpec{
			Servers:       []any{"8.8.8.8", "https://1.1.1.1/dns-query"},
			QueryStrategy: "UseIPv4",
		},
	}

	doc := buildConfigWithGeo(req, 19090, "u", "p", true, true, cats, "")
	parse(t, doc)

	encodedBytes, _ := json.Marshal(doc)
	encoded := string(encodedBytes)
	if strings.Contains(encoded, "geosite:unknown-category") || strings.Contains(encoded, "geosite:unknown-only") {
		t.Fatal("unknown geosite category leaked into xray config")
	}
	if !strings.Contains(encoded, "domain:proxy.example") || !strings.Contains(encoded, "domain:blocked.example") {
		t.Fatal("custom rules missing from config")
	}
	if !strings.Contains(encoded, "geosite:category-ads-all") {
		t.Fatal("blockAds rule missing from config when category exists")
	}
	if !strings.Contains(encoded, `"enableParallelQuery":true`) {
		t.Fatal("provider DNS missing enableParallelQuery")
	}
	inbounds := doc["inbounds"].([]obj)
	for _, inb := range inbounds {
		if inb["tag"] == "dns-in" {
			t.Fatal("dns-in port 53 hijack should be omitted when HijackDNS=false")
		}
	}

	noAdsDir := fakeGeo(t)
	t.Setenv("XRAY_LOCATION_ASSET", noAdsDir)
	noAdsDoc := buildConfigWithGeo(req, 19090, "u", "p", true, true, loadGeoCategories(noAdsDir), "")
	parse(t, noAdsDoc)
	noAdsBytes, _ := json.Marshal(noAdsDoc)
	if strings.Contains(string(noAdsBytes), "geosite:category-ads-all") {
		t.Fatal("blockAds rule emitted when category-ads-all is missing from geosite.dat")
	}
}

func TestProbeValidationAndRunProbes(t *testing.T) {
	if err := validateProbeSpec(nil); err == nil {
		t.Fatal("nil ProbeSpec accepted")
	}
	if err := validateProbeSpec(&ProbeSpec{Targets: []ProbeTargetSpec{{ID: "t1", Outbound: obj{"protocol": "vless", "streamSettings": obj{"sockopt": obj{"mark": 1}}}}}, ProbeUrl: "https://cp.cloudflare.com/generate_204"}); err == nil {
		t.Fatal("ProbeSpec with client sockopt accepted")
	}

	endpoint := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer endpoint.Close()

	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "CONNECT" {
			w.WriteHeader(405)
			return
		}
		remote, err := net.DialTimeout("tcp", r.Host, time.Second)
		if err != nil {
			w.WriteHeader(502)
			return
		}
		client, buf, err := w.(http.Hijacker).Hijack()
		if err != nil {
			remote.Close()
			return
		}
		defer client.Close()
		defer remote.Close()
		_, _ = buf.WriteString("HTTP/1.1 200 Connection established\r\n\r\n")
		_ = buf.Flush()
		go func() { _, _ = io.Copy(remote, buf); _ = remote.Close() }()
		_, _ = io.Copy(client, remote)
	}))
	defer proxy.Close()

	_, portStr, _ := net.SplitHostPort(proxy.Listener.Addr().String())
	port, _ := strconv.Atoi(portStr)
	spec := ProbeSpec{
		Targets: []ProbeTargetSpec{
			{
				ID: "ok-node",
				Outbound: obj{
					"protocol": "http",
					"settings": obj{"servers": []any{obj{"address": "127.0.0.1", "port": float64(port)}}},
				},
			},
		},
		ProbeUrl:             endpoint.URL,
		TimeoutMillis:        3000,
		Concurrency:          2,
		ResolveServerAddress: false,
	}

	results, err := runProbes(context.Background(), spec, "")
	if err != nil {
		t.Fatalf("runProbes failed: %v", err)
	}
	if len(results) != 1 || results[0].ID != "ok-node" || results[0].Millis <= 0 {
		t.Fatalf("unexpected probe results: %+v", results)
	}
}

func TestReadTailFromFileAndSupervisorLogsIPC(t *testing.T) {
	f, err := os.CreateTemp(t.TempDir(), "journal-*.log")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	_, _ = f.WriteString("line-one-dropped\nline-two-kept\nline-three-kept\n")
	tail := readTailFromFile(f, int64(len("ed\nline-two-kept\nline-three-kept\n")))
	if tail != "line-two-kept\nline-three-kept\n" {
		t.Fatalf("unexpected truncated tail: %q", tail)
	}

	s := &supervisor{status: Status{Version: 1, State: "disconnected"}}
	reply := s.handle(Request{Version: 1, Command: "logs"})
	if reply.Error != "" || reply.State != "disconnected" {
		t.Fatalf("unexpected logs IPC reply: %+v", reply)
	}
}
