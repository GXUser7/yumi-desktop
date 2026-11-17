package main

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"net/url"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

var (
	bootstrapDNSServers = []string{"1.1.1.1:53", "8.8.8.8:53"}
	dnsTransactionID    atomic.Uint32
	errDNSTruncated     = errors.New("dns response truncated")
)

func nextDNSID() uint16 {
	return uint16(dnsTransactionID.Add(1))
}

func resolveBootstrapHost(ctx context.Context, host string, preferIPv6, bypassDnscache bool, outboundIfaces ...string) (netip.Addr, error) {
	host = strings.TrimSuffix(strings.TrimSpace(host), ".")
	if host == "" {
		return netip.Addr{}, errors.New("empty host")
	}
	if addr, err := netip.ParseAddr(host); err == nil {
		return addr.Unmap(), nil
	}
	outboundIface := ""
	if len(outboundIfaces) > 0 {
		outboundIface = outboundIfaces[0]
	}
	if !bypassDnscache {
		lookupCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
		network := "ip4"
		if preferIPv6 {
			network = "ip"
		}
		addrs, err := net.DefaultResolver.LookupNetIP(lookupCtx, network, host)
		cancel()
		if err == nil {
			if picked, ok := pickResolvedAddr(addrs, preferIPv6); ok {
				return picked, nil
			}
		}
	}
	return resolveDirectRFC1035(ctx, host, preferIPv6, outboundIface)
}

func pickResolvedAddr(addrs []netip.Addr, preferIPv6 bool) (netip.Addr, bool) {
	var fallback netip.Addr
	for _, addr := range addrs {
		addr = addr.Unmap()
		if !addr.IsValid() || addr.IsUnspecified() {
			continue
		}
		if addr.Is4() {
			return addr, true
		}
		if preferIPv6 && !fallback.IsValid() {
			fallback = addr
		}
	}
	return fallback, fallback.IsValid()
}

func resolveDirectRFC1035(ctx context.Context, host string, preferIPv6 bool, outboundIface string) (netip.Addr, error) {
	lookupCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()

	qtypes := []dnsmessage.Type{dnsmessage.TypeA}
	if preferIPv6 {
		qtypes = append(qtypes, dnsmessage.TypeAAAA)
	}

	var lastErr error
	for _, qtype := range qtypes {
		for i, server := range bootstrapDNSServers {
			if lookupCtx.Err() != nil {
				return netip.Addr{}, lookupCtx.Err()
			}
			remaining := len(bootstrapDNSServers) - i
			budget := 1500 * time.Millisecond
			if deadline, ok := lookupCtx.Deadline(); ok {
				rem := time.Until(deadline)
				if rem <= 0 {
					return netip.Addr{}, context.DeadlineExceeded
				}
				if perServer := rem / time.Duration(remaining); perServer < budget && remaining > 1 {
					budget = perServer
				} else if rem < budget {
					budget = rem
				}
			}
			attemptCtx, attemptCancel := context.WithTimeout(lookupCtx, budget)
			addr, err := queryDNSServer(attemptCtx, server, host, qtype, outboundIface)
			attemptCancel()
			if err == nil && addr.IsValid() {
				return addr, nil
			}
			if err != nil {
				lastErr = err
			}
		}
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("no DNS record for %s", host)
	}
	return netip.Addr{}, lastErr
}

func buildDNSQuery(host string, qtype dnsmessage.Type) (uint16, []byte, error) {
	fqdn := strings.TrimSuffix(host, ".") + "."
	name, err := dnsmessage.NewName(fqdn)
	if err != nil {
		return 0, nil, err
	}
	id := nextDNSID()
	b := dnsmessage.NewBuilder(make([]byte, 0, 128), dnsmessage.Header{
		ID:               id,
		RecursionDesired: true,
	})
	b.EnableCompression()
	if err := b.StartQuestions(); err != nil {
		return 0, nil, err
	}
	if err := b.Question(dnsmessage.Question{
		Name:  name,
		Type:  qtype,
		Class: dnsmessage.ClassINET,
	}); err != nil {
		return 0, nil, err
	}
	wire, err := b.Finish()
	if err != nil {
		return 0, nil, err
	}
	return id, wire, nil
}

func parseDNSAnswer(resp []byte, expectedID uint16, qtype dnsmessage.Type) (netip.Addr, error) {
	var p dnsmessage.Parser
	header, err := p.Start(resp)
	if err != nil {
		return netip.Addr{}, err
	}
	if header.ID != expectedID || !header.Response {
		return netip.Addr{}, errors.New("unexpected dns header")
	}
	if header.Truncated {
		return netip.Addr{}, errDNSTruncated
	}
	if header.RCode != dnsmessage.RCodeSuccess {
		return netip.Addr{}, fmt.Errorf("dns rcode %v", header.RCode)
	}
	if err := p.SkipAllQuestions(); err != nil {
		return netip.Addr{}, err
	}
	for {
		ah, err := p.AnswerHeader()
		if errors.Is(err, dnsmessage.ErrSectionDone) {
			break
		}
		if err != nil {
			return netip.Addr{}, err
		}
		switch ah.Type {
		case dnsmessage.TypeA:
			rec, err := p.AResource()
			if err != nil {
				return netip.Addr{}, err
			}
			if qtype == dnsmessage.TypeA {
				addr := netip.AddrFrom4(rec.A)
				if addr.IsValid() && !addr.IsUnspecified() {
					return addr, nil
				}
			}
		case dnsmessage.TypeAAAA:
			rec, err := p.AAAAResource()
			if err != nil {
				return netip.Addr{}, err
			}
			if qtype == dnsmessage.TypeAAAA {
				addr := netip.AddrFrom16(rec.AAAA).Unmap()
				if addr.IsValid() && !addr.IsUnspecified() {
					return addr, nil
				}
			}
		default:
			if err := p.SkipAnswer(); err != nil {
				return netip.Addr{}, err
			}
		}
	}
	return netip.Addr{}, fmt.Errorf("no matching answer for type %v", qtype)
}

func bootstrapDialer(outboundIface, server string) net.Dialer {
	d := net.Dialer{Timeout: 1500 * time.Millisecond}
	if outboundIface == "" {
		return d
	}
	if host, _, err := net.SplitHostPort(server); err == nil {
		if ip, err := netip.ParseAddr(host); err == nil && ip.IsLoopback() {
			return d
		}
	}
	iface, err := net.InterfaceByName(outboundIface)
	if err != nil {
		return d
	}
	d.Control = func(network, address string, c syscall.RawConn) error {
		var bindErr error
		if err := c.Control(func(fd uintptr) {
			bindErr = bindBootstrapSocket(fd, network, iface)
		}); err != nil {
			return err
		}
		return bindErr
	}
	return d
}

func queryDNSServer(ctx context.Context, server, host string, qtype dnsmessage.Type, outboundIface string) (netip.Addr, error) {
	id, wire, err := buildDNSQuery(host, qtype)
	if err != nil {
		return netip.Addr{}, err
	}
	udpCtx := ctx
	var udpCancel context.CancelFunc
	if deadline, ok := ctx.Deadline(); ok {
		if rem := time.Until(deadline); rem > 100*time.Millisecond {
			udpCtx, udpCancel = context.WithTimeout(ctx, rem*7/10)
		}
	}
	addr, err := queryDNSUDP(udpCtx, server, id, wire, qtype, outboundIface)
	if udpCancel != nil {
		udpCancel()
	}
	if err == nil {
		return addr, nil
	}
	if ctx.Err() != nil {
		return netip.Addr{}, err
	}
	if tcpAddr, tcpErr := queryDNSTCP(ctx, server, id, wire, qtype, outboundIface); tcpErr == nil {
		return tcpAddr, nil
	}
	return netip.Addr{}, err
}

func queryDNSUDP(ctx context.Context, server string, id uint16, wire []byte, qtype dnsmessage.Type, outboundIface string) (netip.Addr, error) {
	dialer := bootstrapDialer(outboundIface, server)
	conn, err := dialer.DialContext(ctx, "udp4", server)
	if err != nil {
		return netip.Addr{}, err
	}
	defer conn.Close()
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	if _, err := conn.Write(wire); err != nil {
		return netip.Addr{}, err
	}
	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	if err != nil {
		return netip.Addr{}, err
	}
	return parseDNSAnswer(buf[:n], id, qtype)
}

func queryDNSTCP(ctx context.Context, server string, id uint16, wire []byte, qtype dnsmessage.Type, outboundIface string) (netip.Addr, error) {
	dialer := bootstrapDialer(outboundIface, server)
	conn, err := dialer.DialContext(ctx, "tcp4", server)
	if err != nil {
		return netip.Addr{}, err
	}
	defer conn.Close()
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	packet := make([]byte, 2+len(wire))
	binary.BigEndian.PutUint16(packet[:2], uint16(len(wire)))
	copy(packet[2:], wire)
	if _, err := conn.Write(packet); err != nil {
		return netip.Addr{}, err
	}
	var length [2]byte
	if _, err := io.ReadFull(conn, length[:]); err != nil {
		return netip.Addr{}, err
	}
	size := int(binary.BigEndian.Uint16(length[:]))
	if size == 0 {
		return netip.Addr{}, errors.New("empty tcp dns response")
	}
	resp := make([]byte, size)
	if _, err := io.ReadFull(conn, resp); err != nil {
		return netip.Addr{}, err
	}
	return parseDNSAnswer(resp, id, qtype)
}

func cloneRequestForConnect(r Request) Request {
	cloned := r
	if r.Options != nil {
		o := *r.Options
		o.Applications = append([]string(nil), r.Options.Applications...)
		o.Domains = append([]string(nil), r.Options.Domains...)
		o.Networks = append([]string(nil), r.Options.Networks...)
		cloned.Options = &o
	}
	cloned.Outbound = cloneJSONMap(r.Outbound)
	if r.Group != nil {
		group := *r.Group
		group.Members = make([]map[string]any, len(r.Group.Members))
		for i, member := range r.Group.Members {
			group.Members[i] = cloneJSONMap(member)
		}
		cloned.Group = &group
	}
	return cloned
}

func cloneJSONMap(m map[string]any) map[string]any {
	if m == nil {
		return nil
	}
	out := make(map[string]any, len(m))
	for k, v := range m {
		out[k] = cloneJSONValue(v)
	}
	return out
}

func cloneJSONValue(v any) any {
	switch x := v.(type) {
	case map[string]any:
		return cloneJSONMap(x)
	case []any:
		cp := make([]any, len(x))
		for i, item := range x {
			cp[i] = cloneJSONValue(item)
		}
		return cp
	default:
		return v
	}
}

func preserveServerName(out map[string]any, originalHost string) {
	if out == nil || originalHost == "" {
		return
	}
	stream, ok := out["streamSettings"].(map[string]any)
	if !ok || stream == nil {
		return
	}
	security, _ := stream["security"].(string)
	if security == "tls" || stream["tlsSettings"] != nil {
		tlsSettings, _ := stream["tlsSettings"].(map[string]any)
		if tlsSettings == nil {
			tlsSettings = map[string]any{}
			stream["tlsSettings"] = tlsSettings
		}
		if sni, _ := tlsSettings["serverName"].(string); strings.TrimSpace(sni) == "" {
			tlsSettings["serverName"] = originalHost
		}
	}
	if security == "reality" || stream["realitySettings"] != nil {
		realitySettings, _ := stream["realitySettings"].(map[string]any)
		if realitySettings == nil {
			realitySettings = map[string]any{}
			stream["realitySettings"] = realitySettings
		}
		if sni, _ := realitySettings["serverName"].(string); strings.TrimSpace(sni) == "" {
			realitySettings["serverName"] = originalHost
		}
	}
}

func bootstrapRequest(ctx context.Context, r Request, outboundIface string, bypassDnscache bool) Request {
	cloned := cloneRequestForConnect(r)
	dnsHosts := map[string]string{}
	resolved := map[string]netip.Addr{}
	failed := map[string]bool{}
	preferIPv6 := cloned.Options != nil && cloned.Options.IPv6

	resolve := func(host string) (netip.Addr, bool) {
		host = strings.TrimSuffix(strings.TrimSpace(host), ".")
		if host == "" {
			return netip.Addr{}, false
		}
		if _, err := netip.ParseAddr(host); err == nil {
			return netip.Addr{}, false
		}
		if addr, ok := resolved[host]; ok {
			return addr, true
		}
		if failed[host] {
			return netip.Addr{}, false
		}
		addr, err := resolveBootstrapHost(ctx, host, preferIPv6, bypassDnscache, outboundIface)
		if err != nil {
			failed[host] = true
			return netip.Addr{}, false
		}
		resolved[host] = addr
		dnsHosts[host] = addr.String()
		return addr, true
	}

	if cloned.Options != nil && cloned.Options.DNS != "" {
		dnsHost := cloned.Options.DNS
		if strings.Contains(dnsHost, "://") {
			if u, err := url.Parse(dnsHost); err == nil {
				dnsHost = u.Hostname()
			}
		}
		resolve(dnsHost)
	}

	var walkSettings func(out map[string]any, node any)
	walkSettings = func(out map[string]any, node any) {
		switch v := node.(type) {
		case map[string]any:
			for k, val := range v {
				switch k {
				case "address":
					if addr, ok := val.(string); ok {
						if ip, replaced := resolve(addr); replaced {
							preserveServerName(out, addr)
							v["address"] = ip.String()
						}
					}
				case "endpoint":
					if ep, ok := val.(string); ok {
						if host, port, err := net.SplitHostPort(ep); err == nil {
							if ip, replaced := resolve(host); replaced {
								v["endpoint"] = net.JoinHostPort(ip.String(), port)
							}
						}
					}
				default:
					walkSettings(out, val)
				}
			}
		case []any:
			for _, item := range v {
				walkSettings(out, item)
			}
		}
	}

	rewriteOutbound := func(out map[string]any) {
		if out == nil {
			return
		}
		if settings, ok := out["settings"]; ok {
			walkSettings(out, settings)
		}
	}

	rewriteOutbound(cloned.Outbound)
	if cloned.Group != nil {
		for _, member := range cloned.Group.Members {
			rewriteOutbound(member)
		}
	}
	if len(dnsHosts) > 0 {
		cloned.dnsHosts = dnsHosts
	}
	return cloned
}
