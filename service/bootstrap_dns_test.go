package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/netip"
	"testing"

	"github.com/xtls/xray-core/infra/conf/serial"
	"golang.org/x/net/dns/dnsmessage"
)

type fakeDNSConfig struct {
	aRecords    map[string][4]byte
	aaaaRecords map[string][16]byte
	truncateUDP bool
	failAll     bool
}

func startFakeDNSServer(t *testing.T, cfg fakeDNSConfig) (string, func()) {
	t.Helper()
	var udpConn net.PacketConn
	var tcpLn net.Listener
	var addr string
	var err error
	tryBind := func(candidate string) bool {
		var ln net.Listener
		ln, err = net.Listen("tcp4", candidate)
		if err != nil {
			return false
		}
		actual := ln.Addr().String()
		var pc net.PacketConn
		pc, err = net.ListenPacket("udp4", actual)
		if err != nil {
			_ = ln.Close()
			return false
		}
		tcpLn, udpConn, addr = ln, pc, actual
		return true
	}
	bound := false
	for attempt := 0; attempt < 20 && !bound; attempt++ {
		bound = tryBind("127.0.0.1:0")
	}
	for port := 31000; port < 32000 && !bound; port++ {
		bound = tryBind(fmt.Sprintf("127.0.0.1:%d", port))
	}
	if !bound {
		t.Fatalf("failed to bind UDP+TCP fake DNS server: %v", err)
	}

	buildReply := func(req []byte, forceTruncated bool) ([]byte, bool) {
		if cfg.failAll {
			return nil, false
		}
		var p dnsmessage.Parser
		hdr, err := p.Start(req)
		if err != nil {
			return nil, false
		}
		q, err := p.Question()
		if err != nil {
			return nil, false
		}
		b := dnsmessage.NewBuilder(nil, dnsmessage.Header{
			ID:                 hdr.ID,
			Response:           true,
			RecursionDesired:   hdr.RecursionDesired,
			RecursionAvailable: true,
			Truncated:          forceTruncated,
		})
		b.EnableCompression()
		_ = b.StartQuestions()
		_ = b.Question(q)
		if !forceTruncated {
			_ = b.StartAnswers()
			host := q.Name.String()
			switch q.Type {
			case dnsmessage.TypeA:
				if ip4, ok := cfg.aRecords[host]; ok {
					_ = b.AResource(dnsmessage.ResourceHeader{
						Name:  q.Name,
						Type:  dnsmessage.TypeA,
						Class: dnsmessage.ClassINET,
						TTL:   60,
					}, dnsmessage.AResource{A: ip4})
				}
			case dnsmessage.TypeAAAA:
				if ip6, ok := cfg.aaaaRecords[host]; ok {
					_ = b.AAAAResource(dnsmessage.ResourceHeader{
						Name:  q.Name,
						Type:  dnsmessage.TypeAAAA,
						Class: dnsmessage.ClassINET,
						TTL:   60,
					}, dnsmessage.AAAAResource{AAAA: ip6})
				}
			}
		}
		wire, err := b.Finish()
		return wire, err == nil
	}

	go func() {
		buf := make([]byte, 4096)
		for {
			n, peer, err := udpConn.ReadFrom(buf)
			if err != nil {
				return
			}
			if reply, ok := buildReply(buf[:n], cfg.truncateUDP); ok {
				_, _ = udpConn.WriteTo(reply, peer)
			}
		}
	}()

	go func() {
		for {
			conn, err := tcpLn.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				var lenBuf [2]byte
				if _, err := io.ReadFull(c, lenBuf[:]); err != nil {
					return
				}
				size := int(binary.BigEndian.Uint16(lenBuf[:]))
				req := make([]byte, size)
				if _, err := io.ReadFull(c, req); err != nil {
					return
				}
				if reply, ok := buildReply(req, false); ok {
					pkt := make([]byte, 2+len(reply))
					binary.BigEndian.PutUint16(pkt[:2], uint16(len(reply)))
					copy(pkt[2:], reply)
					_, _ = c.Write(pkt)
				}
			}(conn)
		}
	}()

	return addr, func() {
		_ = udpConn.Close()
		_ = tcpLn.Close()
	}
}

func TestResolveBootstrapHostDirectUDPAndTCPFallback(t *testing.T) {
	deadAddr, deadClose := startFakeDNSServer(t, fakeDNSConfig{failAll: true})
	defer deadClose()
	goodAddr, goodClose := startFakeDNSServer(t, fakeDNSConfig{
		truncateUDP: true,
		aRecords: map[string][4]byte{
			"vpn.example.test.": {203, 0, 113, 10},
		},
		aaaaRecords: map[string][16]byte{
			"v6only.example.test.": {0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9},
		},
	})
	defer goodClose()

	orig := bootstrapDNSServers
	bootstrapDNSServers = []string{deadAddr, goodAddr}
	defer func() { bootstrapDNSServers = orig }()

	// Direct IP literal skips network queries.
	ip, err := resolveBootstrapHost(context.Background(), "198.51.100.7", false, true)
	if err != nil || ip != netip.MustParseAddr("198.51.100.7") {
		t.Fatalf("IP literal failed: %v %v", ip, err)
	}

	// Falls back from dead server to truncated UDP -> TCP on second server.
	got, err := resolveBootstrapHost(context.Background(), "vpn.example.test", false, true)
	if err != nil || got != netip.MustParseAddr("203.0.113.10") {
		t.Fatalf("TCP fallback resolution failed: %v %v", got, err)
	}

	// IPv6-only host is refused when preferIPv6=false and resolved when preferIPv6=true.
	if _, err := resolveBootstrapHost(context.Background(), "v6only.example.test", false, true); err == nil {
		t.Fatal("returned IPv6 address when preferIPv6=false")
	}
	got6, err := resolveBootstrapHost(context.Background(), "v6only.example.test", true, true)
	if err != nil || got6 != netip.MustParseAddr("2001:db8::9") {
		t.Fatalf("IPv6 resolution failed: %v %v", got6, err)
	}
}

func TestBootstrapRequestClonesPreservesSNIAndPopulatesDNSHosts(t *testing.T) {
	srvAddr, stop := startFakeDNSServer(t, fakeDNSConfig{
		aRecords: map[string][4]byte{
			"edge.example.test.":  {203, 0, 113, 20},
			"group.example.test.": {203, 0, 113, 21},
			"dns.example.test.":   {203, 0, 113, 53},
		},
		aaaaRecords: map[string][16]byte{
			"wg6.example.test.": {0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 42},
		},
	})
	defer stop()

	orig := bootstrapDNSServers
	bootstrapDNSServers = []string{srvAddr}
	defer func() { bootstrapDNSServers = orig }()

	r := Request{
		Version: 1,
		Command: "connect",
		NodeID:  "node-1",
		Options: &Options{
			MTU:       1500,
			IPv6:      true,
			BypassLAN: true,
			SplitMode: "ExceptSelected",
			DNS:       "https://dns.example.test/dns-query",
		},
		Outbound: obj{
			"protocol": "vless",
			"settings": obj{
				"vnext": []any{obj{
					"address": "edge.example.test",
					"port":    float64(443),
					"users":   []any{obj{"id": "11111111-2222-3333-4444-555555555555", "encryption": "none"}},
				}},
			},
			"streamSettings": obj{
				"security": "tls",
				// Missing serverName intentionally to test preservation before IP substitution.
				"tlsSettings": obj{"alpn": []any{"h2"}},
			},
		},
		Group: &OutboundGroup{
			Strategy: "cost",
			Members: []map[string]any{
				{
					"protocol": "vless",
					"settings": obj{
						"vnext": []any{obj{
							"address": "group.example.test",
							"port":    float64(443),
							"users":   []any{obj{"id": "11111111-2222-3333-4444-555555555555", "encryption": "none"}},
						}},
					},
					"streamSettings": obj{
						"security":        "reality",
						"realitySettings": obj{"serverName": "explicit.sni.test", "publicKey": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
					},
				},
				{
					"protocol": "wireguard",
					"settings": obj{
						"secretKey": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
						"address":   []any{"10.0.0.2/32"},
						"peers": []any{obj{
							"publicKey":  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
							"endpoint":   "wg6.example.test:51820",
							"allowedIPs": []any{"0.0.0.0/0", "::/0"},
						}},
					},
				},
			},
		},
	}

	bootstrapped := bootstrapRequest(context.Background(), r, "", true)

	// Original Request must remain completely untouched with original hostnames.
	origAddr := r.Outbound["settings"].(obj)["vnext"].([]any)[0].(obj)["address"]
	if origAddr != "edge.example.test" {
		t.Fatalf("original request mutated: %v", origAddr)
	}
	if _, hasSNI := r.Outbound["streamSettings"].(obj)["tlsSettings"].(obj)["serverName"]; hasSNI {
		t.Fatal("original request tlsSettings mutated")
	}

	// Cloned outbound substituted IP and preserved serverName.
	vnext := bootstrapped.Outbound["settings"].(obj)["vnext"].([]any)[0].(obj)
	if vnext["address"] != "203.0.113.20" {
		t.Fatalf("outbound address not substituted: %v", vnext["address"])
	}
	if sni := bootstrapped.Outbound["streamSettings"].(obj)["tlsSettings"].(obj)["serverName"]; sni != "edge.example.test" {
		t.Fatalf("tlsSettings serverName not preserved: %v", sni)
	}

	// Explicit serverName on group member 0 was preserved (not overwritten).
	m0 := bootstrapped.Group.Members[0]
	if m0["settings"].(obj)["vnext"].([]any)[0].(obj)["address"] != "203.0.113.21" {
		t.Fatal("group member 0 address not substituted")
	}
	if sni := m0["streamSettings"].(obj)["realitySettings"].(obj)["serverName"]; sni != "explicit.sni.test" {
		t.Fatalf("explicit reality serverName overwritten: %v", sni)
	}

	// WireGuard endpoint IPv6 bracketed properly and localAddresses slice untouched.
	m1 := bootstrapped.Group.Members[1]["settings"].(obj)
	if ep := m1["peers"].([]any)[0].(obj)["endpoint"]; ep != "[2001:db8::2a]:51820" {
		t.Fatalf("wireguard endpoint got %v", ep)
	}

	// buildConfig populates dns.hosts and parses cleanly in Xray.
	doc := buildConfig(bootstrapped, 19090, "u", "p", false)
	dnsObj := doc["dns"].(obj)
	hosts, ok := dnsObj["hosts"].(map[string]string)
	if !ok || hosts["dns.example.test"] != "203.0.113.53" || hosts["edge.example.test"] != "203.0.113.20" {
		t.Fatalf("dns hosts missing entries: %+v", dnsObj["hosts"])
	}
	encoded, _ := json.Marshal(doc)
	if _, err := serial.LoadJSONConfig(bytes.NewReader(encoded)); err != nil {
		t.Fatalf("xray rejected bootstrapped config: %v", err)
	}
}
