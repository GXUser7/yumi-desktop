package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
)

// No credentials or remote addresses are committed. The opt-in JVM check writes this private
// request to .local; this test verifies its real protocol handshakes without a TUN or privileges.
func TestLiveProviderGroupWhenExplicitlyRequested(t *testing.T) {
	path := os.Getenv("YUMI_VERIFY_REQUEST")
	if path == "" {
		t.Skip("requires an explicitly supplied local request")
	}
	groupData, err := os.ReadFile(filepath.Join(filepath.Dir(path), "groups.json"))
	if err != nil {
		t.Fatal("cannot read the private group verification requests")
	}
	var requests []json.RawMessage
	if err := json.Unmarshal(groupData, &requests); err != nil {
		t.Fatal("invalid private group request list")
	}
	for index, data := range requests {
		t.Run(fmt.Sprintf("configuration-%d", index+1), func(t *testing.T) {
			request, err := decodeRequest(data)
			if err != nil || request.Group == nil {
				t.Fatal("group request validation failed")
			}
			outbound := obj{}
			for key, value := range request.Outbound {
				outbound[key] = value
			}
			outbound["tag"] = "proxy-node"
			document, _ := json.Marshal(obj{"log": obj{"loglevel": "none"}, "outbounds": []obj{outbound}})
			config, err := serial.LoadJSONConfig(bytes.NewReader(document))
			if err != nil {
				t.Fatal("core rejected a provider group")
			}
			instance, err := core.New(config)
			if err != nil {
				t.Fatal("group core initialization failed")
			}
			defer instance.Close()
			if _, err := installLiveOutbound(instance, request.Group); err != nil {
				t.Fatal("could not construct every provider group member")
			}
		})
	}
	t.Logf("validated all %d provider group configurations", len(requests))
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal("cannot read the private verification request")
	}
	request, err := decodeRequest(data)
	if err != nil || request.Group == nil {
		t.Fatal("group request validation failed")
	}
	first := obj{}
	for key, value := range request.Outbound {
		first[key] = value
	}
	first["tag"] = "proxy-node"
	document, _ := json.Marshal(obj{"log": obj{"loglevel": "none"}, "outbounds": []obj{first}})
	config, err := serial.LoadJSONConfig(bytes.NewReader(document))
	if err != nil {
		t.Fatal("provider group configuration was not accepted by the pinned core")
	}
	instance, err := core.New(config)
	if err != nil {
		t.Fatal("provider group core initialization failed")
	}
	defer instance.Close()
	live, err := installLiveOutbound(instance, request.Group)
	if err != nil {
		t.Fatal("provider group members could not be prepared")
	}
	if err := instance.Start(); err != nil {
		t.Fatal("provider group could not start")
	}
	status := live.groupStatus(Status{})
	if status.GroupLatency <= 0 || status.GroupMember <= 0 {
		t.Fatal("no member relayed the verified HTTPS request on this network")
	}
	t.Logf("verified HTTPS through member %d/%d, %d ms", status.GroupMember, status.GroupMembers, status.GroupLatency)
}

func TestGroupSelectionMatchesMobileCostAndFastest(t *testing.T) {
	spec := OutboundGroup{Members: make([]map[string]any, 3), Strategy: "cost", MaxDelayMillis: 200}
	for _, test := range []struct {
		delays map[int]int
		want   int
	}{
		{map[int]int{1: 90, 2: 20}, 1}, {map[int]int{0: 300, 1: 220, 2: 250}, 1},
		{map[int]int{0: 150, 1: 20}, 0}, {map[int]int{0: 0}, -1},
	} {
		if got := pickGroupMember(spec, test.delays); got != test.want {
			t.Fatalf("%v: got%d want%d", test.delays, got, test.want)
		}
	}
	spec.Strategy = "fastest"
	if got := pickGroupMember(spec, map[int]int{0: 150, 1: 20}); got != 1 {
		t.Fatal("fastest strategy ignored the measured delay")
	}
}

func TestGroupUsesWorkingCredentialsInsteadOfItsListeningFirstMember(t *testing.T) {
	endpoint := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer endpoint.Close()
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Proxy-Authorization") != "Basic "+base64.StdEncoding.EncodeToString([]byte("fixture:accepted")) {
			w.WriteHeader(407)
			return
		}
		if r.Method != "CONNECT" {
			w.WriteHeader(405)
			return
		}
		remote, err := net.DialTimeout("tcp", r.Host, time.Second)
		if err != nil {
			w.WriteHeader(502)
			return
		}
		client, buffer, err := w.(http.Hijacker).Hijack()
		if err != nil {
			remote.Close()
			return
		}
		defer client.Close()
		defer remote.Close()
		_, _ = buffer.WriteString("HTTP/1.1 200 Connection established\r\n\r\n")
		_ = buffer.Flush()
		go func() { _, _ = io.Copy(remote, buffer); _ = remote.Close() }()
		_, _ = io.Copy(client, remote)
	}))
	defer proxy.Close()
	_, portText, _ := net.SplitHostPort(proxy.Listener.Addr().String())
	port, _ := strconv.Atoi(portText)
	member := func(pass string) obj {
		return obj{"tag": "proxy-node", "protocol": "http", "settings": obj{"servers": []obj{{"address": "127.0.0.1", "port": port, "users": []obj{{"user": "fixture", "pass": pass}}}}}}
	}
	first, second := member("refused"), member("accepted")
	data, _ := json.Marshal(obj{"log": obj{"loglevel": "none"}, "stats": obj{}, "outbounds": []obj{first}})
	config, err := serial.LoadJSONConfig(bytes.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	instance, err := core.New(config)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	live, err := installLiveOutbound(instance, &OutboundGroup{Members: []map[string]any{first, second}, Strategy: "cost", MaxDelayMillis: 2000})
	if err != nil {
		t.Fatal(err)
	}
	group := live.current.handler.(*groupOutbound)
	group.probeURL = endpoint.URL
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	status := live.groupStatus(Status{})
	if status.GroupMember != 2 || status.GroupMembers != 2 || status.GroupLatency <= 0 {
		t.Fatalf("did not select the working member: %+v", status)
	}
	targetPort := echoPort(t)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	conn, err := core.Dial(ctx, instance, xnet.TCPDestination(xnet.LocalHostIP, xnet.Port(targetPort)))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()
	if _, err := io.WriteString(conn, "group-payload"); err != nil {
		t.Fatal(err)
	}
	result := make([]byte, len("group-payload"))
	if _, err := io.ReadFull(conn, result); err != nil || string(result) != "group-payload" {
		t.Fatalf("group did not relay traffic: %q %v", result, err)
	}
}
