//go:build !race

package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
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

// A real client/server exchange for every supported VLESS stream transport, using requests
// emitted by the JVM factory. No internet, TUN, installed service, routes or real credentials.
// Skipped under -race because xray-core's splithttp (xhttp) transport has upstream data races in
// WaitReadCloser.Set/Read and uploadWriter.Write; build-unix.sh and build.ps1 run this in full.
func TestEveryStreamTransportCarriesTraffic(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	certificate := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "example.com"},
		DNSNames: []string{"example.com"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, certificate, certificate, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	certLines := strings.Split(strings.TrimSpace(string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))), "\n")
	keyLines := strings.Split(strings.TrimSpace(string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER}))), "\n")
	requests := make(chan chan struct{}, 16)
	echo := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		done := <-requests
		w.Header().Set("Content-Length", strconv.Itoa(len("transport-ok")))
		_, _ = io.WriteString(w, "transport-ok")
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
		// Keep the upstream socket open until the client reads the body: Xray's HTTP inbound
		// sets Connection: close, and an immediate server-side WebSocket CloseFrame (1000)
		// makes vless/outbound call Interrupt(link.Writer), discarding unread pipe bytes (503).
		select {
		case <-done:
		case <-r.Context().Done():
		}
	}))
	defer echo.Close()
	start := func(t *testing.T, document obj) *core.Instance {
		t.Helper()
		data, _ := json.Marshal(document)
		config, err := serial.LoadJSONConfig(bytes.NewReader(data))
		if err != nil {
			t.Fatal(err)
		}
		instance, err := core.New(config)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = instance.Close() })
		if err = instance.Start(); err != nil {
			t.Fatal(err)
		}
		return instance
	}
	twoPorts := func(t *testing.T) (int, int) {
		t.Helper()
		l1, err := net.Listen("tcp4", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		l2, err := net.Listen("tcp4", "127.0.0.1:0")
		if err != nil {
			_ = l1.Close()
			t.Fatal(err)
		}
		p1, p2 := l1.Addr().(*net.TCPAddr).Port, l2.Addr().(*net.TCPAddr).Port
		_ = l1.Close()
		_ = l2.Close()
		return p1, p2
	}
	for _, name := range []string{"tcp", "ws", "grpc", "httpupgrade", "xhttp", "xhttp-packet-up", "xhttp-stream-up", "xhttp-stream-one"} {
		t.Run(name, func(t *testing.T) {
			done := make(chan struct{})
			defer close(done)
			requests <- done
			data, err := os.ReadFile(filepath.Join("../logic/build/service-requests", "vless-"+name+"-tls.json"))
			if err != nil {
				t.Fatal("Run :logic:test to generate transport fixtures")
			}
			r, err := decodeRequest(data)
			if err != nil {
				t.Fatal(err)
			}
			serverPort, proxyPort := twoPorts(t)
			stream := r.Outbound["streamSettings"].(map[string]any)
			stream["tlsSettings"].(map[string]any)["certificates"] = []obj{{"usage": "verify", "certificate": certLines}}
			stream["tlsSettings"].(map[string]any)["disableSystemRoot"] = true
			serverData, _ := json.Marshal(stream)
			var serverStream obj
			_ = json.Unmarshal(serverData, &serverStream)
			alpn := []string{"h2", "http/1.1"}
			if name == "ws" || name == "httpupgrade" {
				alpn = []string{"http/1.1"}
			}
			serverStream["tlsSettings"] = obj{"alpn": alpn, "certificates": []obj{{"certificate": certLines, "key": keyLines}}}
			vnext := r.Outbound["settings"].(map[string]any)["vnext"].([]any)[0].(map[string]any)
			user := vnext["users"].([]any)[0].(map[string]any)
			vnext["address"], vnext["port"] = "127.0.0.1", serverPort
			start(t, obj{"log": obj{"loglevel": "none"}, "inbounds": []obj{{"listen": "127.0.0.1", "port": serverPort,
				"protocol": "vless", "settings": obj{"clients": []obj{{"id": user["id"]}}, "decryption": "none"}, "streamSettings": serverStream}},
				"outbounds": []obj{{"protocol": "freedom", "settings": obj{"finalRules": []obj{{"action": "allow", "ip": []string{"127.0.0.1/32"}}}}}}})
			document := buildConfig(r, proxyPort, "test", "test", false)
			document["inbounds"] = document["inbounds"].([]obj)[1:]
			start(t, document)
			proxy, _ := url.Parse("http://test:test@127.0.0.1:" + strconv.Itoa(proxyPort))
			transport := &http.Transport{Proxy: http.ProxyURL(proxy), DisableKeepAlives: true}
			defer transport.CloseIdleConnections()
			client := &http.Client{Transport: transport, Timeout: 8 * time.Second}
			response, err := client.Get(echo.URL)
			if err != nil {
				t.Fatal(err)
			}
			defer response.Body.Close()
			body, err := io.ReadAll(response.Body)
			if err != nil || string(body) != "transport-ok" {
				t.Fatalf("transport response: status=%d body=%q error=%v", response.StatusCode, body, err)
			}
		})
	}
}
