//go:build unix

package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"net"
	"os"
	"time"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/stats"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"

	// Two handlers the distro bundle does not register, and the reason the tunnel could never
	// start. See the Windows worker for the full post-mortem: the config loads without complaint,
	// core.New succeeds, and only Start() fails.
	_ "github.com/xtls/xray-core/proxy/hysteria"
	_ "github.com/xtls/xray-core/proxy/tun"
)

/*
The unix worker. Everything around the core is the same program as on Windows — the config frame
on stdin, the status frames on stdout, the live outbound for hot switches, the direct-forward
fake for VPN-off. What differs is the adapter: the core creates its own device (a named tun on
Linux, a numbered utun on macOS) and manages its addresses and routes itself, so there is nothing
here to open, flush or report by LUID — only the interface to find, so the kill switch can name
it, and the system's resolver to point into the tunnel, which Windows does through the adapter's
own DNS setting and unix does not have.
*/
func worker() {
	input := bufio.NewReaderSize(os.Stdin, maxFrame+1)
	line, err := input.ReadBytes('\n')
	if err != nil || len(line) > maxFrame {
		return
	}
	var initial struct {
		Config json.RawMessage `json:"config"`
		Status Status          `json:"status"`
	}
	if json.Unmarshal(line, &initial) != nil {
		return
	}
	encoder := json.NewEncoder(os.Stdout)
	fail := func(code string, cause error) {
		initial.Status.State = "failed"
		initial.Status.Error = code
		initial.Status.Detail = detailOf(cause)
		_ = encoder.Encode(initial.Status)
	}
	config, err := serial.LoadJSONConfig(bytes.NewReader(initial.Config))
	if err != nil {
		fail("invalid_configuration", err)
		return
	}
	instance, err := core.New(config)
	if err != nil {
		fail("core_initialization_failed", err)
		return
	}
	live, err := installLiveOutbound(instance)
	if err != nil {
		_ = instance.Close()
		fail("core_initialization_failed", err)
		return
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		fail("tunnel_start_failed", err)
		return
	}
	initial.Status.State = "connected"
	initial.Status.ConnectedAt = time.Now().UnixMilli()
	// The device the core just made, for the supervisor's kill switch to let through. It exists
	// the moment Start() returns on Linux; on macOS the utun appears with the socket still open,
	// so a short wait costs nothing and covers an answer that has not landed yet.
	if tun, err := findTunInterface(); err == nil {
		initial.Status.TunName = tun
		// The system resolver, into the tunnel: the core's tun stack answers 172.31.255.2 on
		// every platform, but only Windows hears about it through the adapter itself. Best
		// effort — a resolver we cannot point keeps the machine's own, which resolves around
		// the tunnel rather than through it, and everything else still works.
		if err := applySystemDNS(); err != nil {
			journalLine("system dns not captured: " + detailOf(err))
		} else {
			defer revertSystemDNS()
		}
	} else {
		fail("tunnel_start_failed", errors.New("tunnel interface did not appear"))
		return
	}
	manager, _ := instance.GetFeature(stats.ManagerType()).(stats.Manager)
	type control struct {
		Config     json.RawMessage `json:"config"`
		Status     Status          `json:"status"`
		Disconnect bool            `json:"disconnect"`
	}
	updates := make(chan control)
	go func() {
		defer close(updates)
		scanner := bufio.NewScanner(input)
		scanner.Buffer(make([]byte, 4096), maxFrame)
		for scanner.Scan() {
			var next control
			if json.Unmarshal(scanner.Bytes(), &next) == nil {
				updates <- next
			}
		}
	}()
	var baseUp, baseDown int64
	counter := func(name string) int64 {
		if manager != nil {
			if c := manager.GetCounter("outbound>>>proxy-node>>>traffic>>>" + name); c != nil {
				return c.Value()
			}
		}
		return 0
	}
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		if initial.Status.State == "connected" {
			initial.Status.Upload = counter("uplink") - baseUp
			initial.Status.Download = counter("downlink") - baseDown
		}
		if encoder.Encode(initial.Status) != nil {
			return
		}
		select {
		case next, ok := <-updates:
			if !ok {
				return
			}
			document := next.Config
			if next.Disconnect {
				// Keep the forwarding stack and its direct flows intact while VPN is off.
				// DNS still uses the same resolver, now reached directly through this handler.
				document = json.RawMessage(`{"outbounds":[{"tag":"direct-forward","protocol":"freedom","settings":{"domainStrategy":"UseIP"}}]}`)
			}
			parsed, err := serial.LoadJSONConfig(bytes.NewReader(document))
			if err == nil {
				handler, e := prepareLiveOutbound(instance, parsed)
				err = e
				if err == nil {
					err = live.replace(handler, next.Disconnect)
				}
			}
			if err != nil {
				initial.Status.Error = "hot_swap_failed"
				initial.Status.Detail = detailOf(err)
				continue
			}
			if next.Disconnect {
				initial.Status = Status{Version: 1, State: "disconnected", ProbePort: initial.Status.ProbePort, ProbeUser: initial.Status.ProbeUser, ProbePassword: initial.Status.ProbePassword, TunName: initial.Status.TunName}
			} else {
				wasConnected := initial.Status.State == "connected"
				initial.Status = liveConnectedStatus(initial.Status, next.Status, time.Now().UnixMilli())
				if !wasConnected {
					baseUp = counter("uplink")
					baseDown = counter("downlink")
				}
			}
		case <-ticker.C:
		}
	}
}

// The interface the core's tun inbound was configured to create; see tunAdapterName for why the
// name is spelled differently per platform.
func findTunInterface() (string, error) {
	want := tunAdapterName()
	for attempt := 0; attempt < 20; attempt++ {
		if iface, err := net.InterfaceByName(want); err == nil && iface.Flags&net.FlagUp != 0 {
			return iface.Name, nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	return "", errors.New("tunnel interface did not appear")
}
