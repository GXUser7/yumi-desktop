package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
)

// Uses only a loopback HTTP inbound, never a TUN, the service or the saved selection. Each
// request gets its own core and HTTP connection, so an old browser connection cannot affect it.
func TestLiveTransportExitCountries(t *testing.T) {
	dir := os.Getenv("YUMI_VERIFY_TRANSPORT_OUTPUT")
	if dir == "" {
		t.Skip("requires explicitly exported local transport requests")
	}
	type result struct {
		File      string `json:"file"`
		Name      string `json:"name"`
		Transport string `json:"transport"`
		Country   string `json:"country"`
		Exit      string `json:"exitCountry,omitempty"`
		IP        string `json:"ip,omitempty"`
		Error     string `json:"error,omitempty"`
		OK        bool   `json:"ok"`
	}
	data, err := os.ReadFile(filepath.Join(dir, "manifest.json"))
	if err != nil {
		t.Fatal("cannot read local manifest")
	}
	var results []result
	if json.Unmarshal(data, &results) != nil || len(results) == 0 {
		t.Fatal("invalid local manifest")
	}
	for i := range results {
		entry := &results[i]
		t.Run(entry.Name+"/"+entry.Transport, func(t *testing.T) {
			data, err := os.ReadFile(filepath.Join(dir, filepath.Base(entry.File)))
			if err != nil {
				t.Fatal("cannot read local request")
			}
			r, err := decodeRequest(data)
			if err != nil {
				t.Fatal("local request did not validate")
			}
			port, err := freePort()
			if err != nil {
				t.Fatal(err)
			}
			document := buildConfig(r, port, "test", "test", false)
			document["inbounds"] = document["inbounds"].([]obj)[1:]
			// Match the real worker's physical egress, without installing any routes. Otherwise
			// an already active VPN would nest these test connections inside its own tunnel.
			adapter, err := outboundInterface(r.Options.OutboundAdapter)
			if err != nil {
				t.Fatal("cannot identify physical egress")
			}
			stream, _ := r.Outbound["streamSettings"].(map[string]any)
			if stream == nil {
				stream = obj{}
				r.Outbound["streamSettings"] = stream
			}
			sockopt, _ := stream["sockopt"].(map[string]any)
			if sockopt == nil {
				sockopt = obj{}
				stream["sockopt"] = sockopt
			}
			sockopt["interface"] = adapter
			// buildConfig copies the outbound map shallowly; a newly added stream must also
			// be attached to that copy.
			document["outbounds"].([]obj)[0]["streamSettings"] = stream
			encoded, _ := json.Marshal(document)
			config, err := serial.LoadJSONConfig(bytes.NewReader(encoded))
			if err != nil {
				entry.Error = err.Error()
				t.Fatal("core rejected local transport configuration")
			}
			instance, err := core.New(config)
			if err != nil {
				t.Fatal("core could not create transport")
			}
			defer instance.Close()
			if err = instance.Start(); err != nil {
				t.Fatal("core could not start transport")
			}
			proxy, _ := url.Parse("http://test:test@127.0.0.1:" + strconv.Itoa(port))
			transport := &http.Transport{Proxy: http.ProxyURL(proxy), DisableKeepAlives: true}
			defer transport.CloseIdleConnections()
			client := &http.Client{Transport: transport, Timeout: 25 * time.Second}
			ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
			defer cancel()
			request, _ := http.NewRequestWithContext(ctx, "GET", "https://1.1.1.1/cdn-cgi/trace", nil)
			response, err := client.Do(request)
			if err != nil {
				entry.Error = err.Error()
				t.Error("fresh tunneled request failed; private connection details omitted")
				return
			}
			defer response.Body.Close()
			body, err := io.ReadAll(io.LimitReader(response.Body, 8192))
			if err != nil || response.StatusCode != 200 {
				t.Error("trace endpoint did not return a complete response")
				return
			}
			for _, line := range strings.Split(string(body), "\n") {
				key, value, _ := strings.Cut(strings.TrimSpace(line), "=")
				if key == "loc" {
					entry.Exit = value
				}
				if key == "ip" {
					entry.IP = value
				}
			}
			entry.OK = entry.Exit != "" && entry.IP != ""
			t.Logf("declared=%s actual=%s transport=%s", entry.Country, entry.Exit, entry.Transport)
		})
	}
	encoded, _ := json.MarshalIndent(results, "", "  ")
	if err = os.WriteFile(filepath.Join(dir, "results.json"), encoded, 0o600); err != nil {
		t.Fatal(err)
	}
}
