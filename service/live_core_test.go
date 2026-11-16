package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"strconv"
	"testing"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/stats"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
)

func echoPort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	go func() {
		for {
			c, e := listener.Accept()
			if e != nil {
				return
			}
			go func() { defer c.Close(); _, _ = io.Copy(c, c) }()
		}
	}()
	return listener.Addr().(*net.TCPAddr).Port
}

func TestLiveCoreKeepsDirectSocketsAndCumulativeProxyCounters(t *testing.T) {
	proxyPort, bypassPort := echoPort(t), echoPort(t)
	document := obj{
		"log": obj{"loglevel": "none"}, "stats": obj{},
		"policy":    obj{"system": obj{"statsOutboundUplink": true, "statsOutboundDownlink": true}},
		"outbounds": []obj{{"tag": "proxy-node", "protocol": "freedom"}, {"tag": "direct", "protocol": "freedom"}},
		"routing":   obj{"rules": []obj{{"type": "field", "port": strconv.Itoa(bypassPort), "outboundTag": "direct"}}},
	}
	encoded, _ := json.Marshal(document)
	config, err := serial.LoadJSONConfig(bytes.NewReader(encoded))
	if err != nil {
		t.Fatal(err)
	}
	instance, err := core.New(config)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	live, err := installLiveOutbound(instance)
	if err != nil {
		t.Fatal(err)
	}
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	dial := func(port int) net.Conn {
		t.Helper()
		c, e := core.Dial(context.Background(), instance, xnet.TCPDestination(xnet.LocalHostIP, xnet.Port(port)))
		if e != nil {
			t.Fatal(e)
		}
		t.Cleanup(func() { _ = c.Close() })
		return c
	}
	exchange := func(c net.Conn, message string) {
		t.Helper()
		_ = c.SetDeadline(time.Now().Add(3 * time.Second))
		if _, e := io.WriteString(c, message); e != nil {
			t.Fatal(e)
		}
		data := make([]byte, len(message))
		if _, e := io.ReadFull(c, data); e != nil {
			t.Fatal(e)
		}
		if string(data) != message {
			t.Fatal("wrong echo")
		}
	}
	next := func(tag string) {
		t.Helper()
		data := []byte(`{"outbounds":[{"tag":"` + tag + `","protocol":"freedom"}]}`)
		conf, e := serial.LoadJSONConfig(bytes.NewReader(data))
		if e != nil {
			t.Fatal(e)
		}
		handler, e := prepareLiveOutbound(instance, conf)
		if e != nil {
			t.Fatal(e)
		}
		if e = live.replace(handler, tag == "direct-forward"); e != nil {
			t.Fatal(e)
		}
	}
	manager := instance.GetFeature(stats.ManagerType()).(stats.Manager)
	count := func() int64 { return manager.GetCounter("outbound>>>proxy-node>>>traffic>>>uplink").Value() }
	proxy, bypass := dial(proxyPort), dial(bypassPort)
	exchange(proxy, "first")
	first := count()
	if first != 5 {
		t.Fatalf("first flow counted %d bytes, want5", first)
	}
	next("proxy-node")
	_ = proxy.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, err := proxy.Read(make([]byte, 1)); err == nil {
		t.Fatal("old VPN socket survived a server change")
	} else if timeout, ok := err.(net.Error); ok && timeout.Timeout() {
		t.Fatal("old VPN socket was left open instead of being interrupted")
	}
	newest := dial(proxyPort)
	exchange(newest, "new-flow")
	exchange(bypass, "game-direct")
	if got := count(); got != first+8 {
		t.Fatalf("new flow counted %d, want%d", got, first+8)
	}
	next("direct-forward")
	exchange(bypass, "game-still-connected")
	off := dial(proxyPort)
	exchange(off, "vpn-off-direct")
	before := count()
	next("proxy-node")
	exchange(off, "retained-direct")
	exchange(bypass, "game-after-reconnect")
	if count() != before {
		t.Fatal("direct traffic counted as VPN traffic")
	}
	reconnected := dial(proxyPort)
	exchange(reconnected, "vpn")
	if count() != before+3 {
		t.Fatal("proxy counter reset or double-counted after replacement")
	}
}

func TestLiveSwitchStatusKeepsConnectedEpochAndTotals(t *testing.T) {
	previous := Status{Version: 1, State: "connected", NodeID: "old", ConnectedAt: 123, Upload: 456, Download: 789, ProbePort: 19090, ProbeUser: "user", ProbePassword: "pass", TunLUID: 9}
	pending := switchingStatus(previous, "new")
	if pending.State != "connected" || pending.ConnectedAt != previous.ConnectedAt || pending.Upload != 456 || pending.Download != 789 {
		t.Fatal("hot switch reset the connected session", pending)
	}
	connected := liveConnectedStatus(previous, pending, 999)
	if connected.State != "connected" || connected.NodeID != "new" || connected.ConnectedAt != 123 || connected.Upload != 456 || connected.Download != 789 || connected.TunLUID != 9 {
		t.Fatal("worker acknowledgement reset the session", connected)
	}
	off := Status{Version: 1, State: "disconnected", TunLUID: 9}
	if switchingStatus(off, "new").State != "connecting" || liveConnectedStatus(off, pending, 999).ConnectedAt != 999 {
		t.Fatal("reconnect from VPN-off must start a new session")
	}
}

func TestLiveSwitchCountryBelongsToAcknowledgedServer(t *testing.T) {
	previous := Status{Version: 1, State: "connected", NodeID: "old", ServerCountry: "BE", ConnectedAt: 123}
	requested := switchingStatus(previous, "new")
	requested.ServerCountry = "DE"
	// Preparing a candidate must not move the old connection's country. A rejected candidate
	// keeps the previous status; only the worker's successful replacement publishes this one.
	if previous.ServerCountry != "BE" {
		t.Fatal("preparing a switch changed the active country")
	}
	acknowledged := liveConnectedStatus(previous, requested, 999)
	if acknowledged.NodeID != "new" || acknowledged.ServerCountry != "DE" || acknowledged.ConnectedAt != 123 {
		t.Fatalf("acknowledgement lost the active country or session: %+v", acknowledged)
	}
}

func TestLiveCoreKeepsDirectUDPSocketAndSourcePort(t *testing.T) {
	listener, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	sources := make(chan string, 8)
	go func() {
		data := make([]byte, 1024)
		for {
			n, source, e := listener.ReadFrom(data)
			if e != nil {
				return
			}
			sources <- source.String()
			_, _ = listener.WriteTo(data[:n], source)
		}
	}()
	port := listener.LocalAddr().(*net.UDPAddr).Port
	document := obj{
		"log":       obj{"loglevel": "none"},
		"outbounds": []obj{{"tag": "proxy-node", "protocol": "freedom"}, {"tag": "direct", "protocol": "freedom"}},
		"routing":   obj{"rules": []obj{{"type": "field", "port": strconv.Itoa(port), "outboundTag": "direct"}}},
	}
	encoded, _ := json.Marshal(document)
	config, err := serial.LoadJSONConfig(bytes.NewReader(encoded))
	if err != nil {
		t.Fatal(err)
	}
	instance, err := core.New(config)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	live, err := installLiveOutbound(instance)
	if err != nil {
		t.Fatal(err)
	}
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	conn, err := core.Dial(context.Background(), instance, xnet.UDPDestination(xnet.LocalHostIP, xnet.Port(port)))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	exchange := func() string {
		t.Helper()
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
		if _, e := conn.Write([]byte("game")); e != nil {
			t.Fatal(e)
		}
		data := make([]byte, 4)
		if _, e := io.ReadFull(conn, data); e != nil {
			t.Fatal(e)
		}
		if string(data) != "game" {
			t.Fatal("wrong datagram")
		}
		return await(t, sources)
	}
	source := exchange()
	for _, tag := range []string{"proxy-node", "direct-forward", "proxy-node"} {
		encoded := []byte(`{"outbounds":[{"tag":"` + tag + `","protocol":"freedom"}]}`)
		config, e := serial.LoadJSONConfig(bytes.NewReader(encoded))
		if e != nil {
			t.Fatal(e)
		}
		handler, e := prepareLiveOutbound(instance, config)
		if e != nil {
			t.Fatal(e)
		}
		if e = live.replace(handler, tag == "direct-forward"); e != nil {
			t.Fatal(e)
		}
		if got := exchange(); got != source {
			t.Fatalf("direct UDP mapping changed from%s to%s", source, got)
		}
	}
}
