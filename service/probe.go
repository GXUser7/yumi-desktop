package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/xtls/xray-core/app/dispatcher"
	"github.com/xtls/xray-core/app/proxyman"
	"github.com/xtls/xray-core/common"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/net/cnc"
	cserial "github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/core"
	xoutbound "github.com/xtls/xray-core/features/outbound"
	confserial "github.com/xtls/xray-core/infra/conf/serial"
	"github.com/xtls/xray-core/transport"
	"github.com/xtls/xray-core/transport/pipe"
)

const (
	defaultProbeURL     = "https://cp.cloudflare.com/generate_204"
	minProbeTimeout     = 500 * time.Millisecond
	defaultProbeTimeout = 5 * time.Second
	maxProbeTimeout     = 30 * time.Second
)

func validateProbeSpec(spec *ProbeSpec) error {
	if spec == nil {
		return errors.New("invalid_probe")
	}
	if len(spec.Targets) == 0 || len(spec.Targets) > 512 {
		return errors.New("invalid_probe")
	}
	rawURL := strings.TrimSpace(spec.ProbeUrl)
	if rawURL == "" || len(rawURL) > 2048 || strings.ContainsAny(rawURL, "\x00\r\n") {
		return errors.New("invalid_probe_url")
	}
	u, err := url.Parse(rawURL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" || u.User != nil {
		return errors.New("invalid_probe_url")
	}
	if len(spec.OutboundAdapter) > 256 || strings.ContainsAny(spec.OutboundAdapter, "\x00\r\n") || ownAdapter(spec.OutboundAdapter) {
		return errors.New("invalid_adapter")
	}
	if spec.ResolveDohUrl != "" {
		if err := validateDoHURL(spec.ResolveDohUrl); err != nil {
			return err
		}
	}
	if spec.TimeoutMillis < 0 || spec.TimeoutMillis > 60000 || spec.Concurrency < 0 || spec.Concurrency > 32 {
		return errors.New("invalid_probe")
	}
	for _, target := range spec.Targets {
		if target.ID == "" || len(target.ID) > 128 || strings.ContainsAny(target.ID, "\x00\r\n") {
			return errors.New("invalid_probe_target")
		}
		if err := validateOutbound(target.Outbound); err != nil {
			return err
		}
		if target.Fragment != nil {
			if err := validateFragmentSpec(*target.Fragment); err != nil {
				return err
			}
		}
	}
	return nil
}

func runProbeCLI() error {
	input, err := io.ReadAll(io.LimitReader(os.Stdin, maxFrame*4))
	if err != nil {
		return err
	}
	var spec ProbeSpec
	dec := json.NewDecoder(bytes.NewReader(input))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&spec); err != nil {
		return err
	}
	if err := validateProbeSpec(&spec); err != nil {
		return err
	}
	outboundIface := ""
	if resolved, err := outboundInterface(spec.OutboundAdapter); err == nil {
		outboundIface = resolved
	}
	budget := time.Duration(spec.TimeoutMillis) * time.Millisecond
	if budget <= 0 {
		budget = defaultProbeTimeout
	}
	totalBudget := budget*4 + 15*time.Second
	ctx, cancel := context.WithTimeout(context.Background(), totalBudget)
	defer cancel()
	results, err := runProbes(ctx, spec, outboundIface)
	if err != nil {
		return err
	}
	return json.NewEncoder(os.Stdout).Encode(results)
}

func attachOutboundInterface(out map[string]any, ifaceName string) {
	if out == nil || ifaceName == "" {
		return
	}
	protocol, _ := out["protocol"].(string)
	if protocol == "wireguard" {
		return
	}
	stream, _ := out["streamSettings"].(map[string]any)
	if stream == nil {
		stream = map[string]any{}
		out["streamSettings"] = stream
	}
	sock, _ := stream["sockopt"].(map[string]any)
	if sock == nil {
		sock = map[string]any{}
		stream["sockopt"] = sock
	}
	sock["interface"] = ifaceName
}

func probeResolveTargets(ctx context.Context, spec ProbeSpec, outboundIface string) map[string]netip.Addr {
	out := map[string]netip.Addr{}
	if !spec.ResolveServerAddress {
		return out
	}
	hosts := make([]string, 0, len(spec.Targets))
	seen := map[string]bool{}
	var collect func(node any)
	collect = func(node any) {
		switch v := node.(type) {
		case map[string]any:
			for k, val := range v {
				switch k {
				case "address":
					if s, ok := val.(string); ok {
						h := strings.TrimSuffix(strings.TrimSpace(s), ".")
						if h != "" && !seen[h] {
							if _, err := netip.ParseAddr(h); err != nil {
								seen[h] = true
								hosts = append(hosts, h)
							}
						}
					}
				case "endpoint":
					if s, ok := val.(string); ok {
						if h, _, err := net.SplitHostPort(s); err == nil {
							h = strings.TrimSuffix(strings.TrimSpace(h), ".")
							if h != "" && !seen[h] {
								if _, err := netip.ParseAddr(h); err != nil {
									seen[h] = true
									hosts = append(hosts, h)
								}
							}
						}
					}
				default:
					collect(val)
				}
			}
		case []any:
			for _, item := range v {
				collect(item)
			}
		}
	}
	for _, target := range spec.Targets {
		if settings, ok := target.Outbound["settings"]; ok {
			collect(settings)
		}
	}
	if len(hosts) == 0 {
		return out
	}
	dnsCtx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	var mu sync.Mutex
	var wg sync.WaitGroup
	slots := make(chan struct{}, 8)
	for _, h := range hosts {
		wg.Add(1)
		go func(host string) {
			defer wg.Done()
			select {
			case slots <- struct{}{}:
			case <-dnsCtx.Done():
				return
			}
			defer func() { <-slots }()
			if ip, err := resolveHostWithDoH(dnsCtx, host, false, true, outboundIface, spec.ResolveDohUrl); err == nil && ip.IsValid() {
				mu.Lock()
				out[host] = ip
				mu.Unlock()
			}
		}(h)
	}
	wg.Wait()
	return out
}

func substituteResolvedHosts(out map[string]any, resolved map[string]netip.Addr) {
	if out == nil || len(resolved) == 0 {
		return
	}
	var walk func(node any)
	walk = func(node any) {
		switch v := node.(type) {
		case map[string]any:
			for k, val := range v {
				switch k {
				case "address":
					if s, ok := val.(string); ok {
						h := strings.TrimSuffix(strings.TrimSpace(s), ".")
						if ip, found := resolved[h]; found {
							preserveServerName(out, s)
							v["address"] = ip.String()
						}
					}
				case "endpoint":
					if s, ok := val.(string); ok {
						if h, port, err := net.SplitHostPort(s); err == nil {
							clean := strings.TrimSuffix(strings.TrimSpace(h), ".")
							if ip, found := resolved[clean]; found {
								v["endpoint"] = net.JoinHostPort(ip.String(), port)
							}
						}
					}
				default:
					walk(val)
				}
			}
		case []any:
			for _, item := range v {
				walk(item)
			}
		}
	}
	if settings, ok := out["settings"]; ok {
		walk(settings)
	}
}

func runProbes(ctx context.Context, spec ProbeSpec, outboundIface string) ([]ProbeResultItem, error) {
	if err := validateProbeSpec(&spec); err != nil {
		return nil, err
	}
	probeURL := strings.TrimSpace(spec.ProbeUrl)
	if probeURL == "" {
		probeURL = defaultProbeURL
	}
	timeout := time.Duration(spec.TimeoutMillis) * time.Millisecond
	if timeout <= 0 {
		timeout = defaultProbeTimeout
	} else if timeout < minProbeTimeout {
		timeout = minProbeTimeout
	} else if timeout > maxProbeTimeout {
		timeout = maxProbeTimeout
	}
	concurrency := spec.Concurrency
	if concurrency <= 0 {
		concurrency = 8
	} else if concurrency > 16 {
		concurrency = 16
	}
	if concurrency > len(spec.Targets) {
		concurrency = len(spec.Targets)
	}

	resolvedHosts := probeResolveTargets(ctx, spec, outboundIface)

	preparedTargets := make([]obj, len(spec.Targets))
	fragMap := map[string]obj{}
	for i, target := range spec.Targets {
		ob := cloneJSONMap(target.Outbound)
		if ob == nil {
			ob = obj{}
		}
		ob["tag"] = "probe-t-" + target.ID
		substituteResolvedHosts(ob, resolvedHosts)
		if target.Fragment != nil {
			fragTag := fragmentTag(*target.Fragment)
			attachFragmentDialerProxy(ob, fragTag)
			if _, exists := fragMap[fragTag]; !exists {
				fragOb := buildFragmentOutbound(fragTag, *target.Fragment)
				if outboundIface != "" {
					attachOutboundInterface(fragOb, outboundIface)
				}
				fragMap[fragTag] = fragOb
			}
		} else if outboundIface != "" {
			attachOutboundInterface(ob, outboundIface)
		}
		preparedTargets[i] = ob
	}

	directOb := obj{"tag": "direct", "protocol": "freedom", "settings": obj{"domainStrategy": "AsIs"}}
	if outboundIface != "" {
		attachOutboundInterface(directOb, outboundIface)
	}
	supportDocList := make([]obj, 0, len(fragMap)+1)
	supportDocList = append(supportDocList, directOb)
	for _, fragOb := range fragMap {
		supportDocList = append(supportDocList, fragOb)
	}

	supportRaw, err := json.Marshal(obj{"outbounds": supportDocList})
	if err != nil {
		return nil, err
	}
	supportCfg, err := confserial.LoadJSONConfig(bytes.NewReader(supportRaw))
	if err != nil {
		return nil, err
	}

	hostCfg := &core.Config{
		App: []*cserial.TypedMessage{
			cserial.ToTypedMessage(&dispatcher.Config{}),
			cserial.ToTypedMessage(&proxyman.InboundConfig{}),
			cserial.ToTypedMessage(&proxyman.OutboundConfig{}),
		},
		Outbound: supportCfg.Outbound,
	}
	host, err := core.New(hostCfg)
	if err != nil {
		return nil, err
	}
	defer host.Close()
	if err := host.Start(); err != nil {
		return nil, err
	}

	targetsJSON, err := json.Marshal(obj{"outbounds": preparedTargets})
	if err != nil {
		return nil, err
	}
	targetsCfg, err := confserial.LoadJSONConfig(bytes.NewReader(targetsJSON))
	if err != nil {
		return nil, err
	}
	if err := rewriteHysteriaProbeOutbounds(targetsCfg); err != nil {
		return nil, err
	}

	results := make([]ProbeResultItem, len(spec.Targets))
	for i, target := range spec.Targets {
		results[i] = ProbeResultItem{ID: target.ID, Millis: -1}
	}

	type job struct {
		index int
		id    string
		cfg   *core.OutboundHandlerConfig
	}
	jobs := make(chan job, len(spec.Targets))
	for i, target := range spec.Targets {
		var obCfg *core.OutboundHandlerConfig
		if i < len(targetsCfg.Outbound) {
			obCfg = targetsCfg.Outbound[i]
		}
		jobs <- job{index: i, id: target.ID, cfg: obCfg}
	}
	close(jobs)

	var wg sync.WaitGroup
	for w := 0; w < concurrency; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := range jobs {
				if ctx.Err() != nil {
					return
				}
				if j.cfg == nil {
					continue
				}
				rawObj, err := core.CreateObject(host, j.cfg)
				if err != nil {
					continue
				}
				h, ok := rawObj.(xoutbound.Handler)
				if !ok {
					continue
				}
				if err := h.Start(); err != nil {
					_ = h.Close()
					continue
				}
				ms := measureProbeHandler(ctx, h, j.cfg.Tag, probeURL, timeout)
				_ = h.Close()
				results[j.index].Millis = ms
			}
		}()
	}
	wg.Wait()
	return results, nil
}

func measureProbeHandler(
	parent context.Context,
	handler xoutbound.Handler,
	tag string,
	probeURL string,
	timeout time.Duration,
) int {
	reqCtx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()

	var connsMu sync.Mutex
	var conns []net.Conn

	client := &http.Client{
		Transport: &http.Transport{
			Proxy: nil,
			DialContext: func(_ context.Context, network, addr string) (net.Conn, error) {
				dest, err := xnet.ParseDestination(network + ":" + addr)
				if err != nil {
					return nil, err
				}
				connCtx, connCancel := context.WithCancel(reqCtx)
				dispatchCtx := session.ContextWithContent(connCtx, &session.Content{SkipDNSResolve: true})
				dispatchCtx = session.ContextWithOutbounds(dispatchCtx, []*session.Outbound{{
					Target:         dest,
					OriginalTarget: dest,
					Tag:            tag,
				}})
				opts := pipe.OptionsFromContext(dispatchCtx)
				ur, uw := pipe.New(opts...)
				dr, dw := pipe.New(opts...)
				go handler.Dispatch(dispatchCtx, &transport.Link{Reader: ur, Writer: dw})
				raw := cnc.NewConnection(
					cnc.ConnectionInputMulti(uw),
					cnc.ConnectionOutputMulti(dr),
					cnc.ConnectionOnClose(closeFunc(func() error {
						connCancel()
						common.Interrupt(uw)
						common.Interrupt(dw)
						return nil
					})),
				)
				_ = raw.SetDeadline(time.Now().Add(timeout))
				connsMu.Lock()
				conns = append(conns, raw)
				connsMu.Unlock()
				return raw, nil
			},
			TLSHandshakeTimeout:   timeout,
			ResponseHeaderTimeout: timeout,
			DisableKeepAlives:     true,
		},
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		Timeout:       timeout,
	}
	defer func() {
		client.CloseIdleConnections()
		connsMu.Lock()
		for _, c := range conns {
			_ = c.Close()
		}
		connsMu.Unlock()
	}()

	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, probeURL, nil)
	if err != nil {
		return -1
	}
	req.Header.Set("User-Agent", "Yumi")
	req.Header.Set("Connection", "close")

	started := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return -1
	}
	if resp.Body != nil {
		_, _ = io.CopyN(io.Discard, resp.Body, 8192)
		_ = resp.Body.Close()
	}
	elapsed := int(time.Since(started).Milliseconds())
	if elapsed <= 0 {
		elapsed = 1
	}
	return elapsed
}
