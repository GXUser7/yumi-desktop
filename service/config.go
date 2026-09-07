package main

import (
    "bytes"
    "crypto/rand"
    "encoding/base64"
    "encoding/json"
    "errors"
    "net"
    "net/netip"
    "net/url"
    "regexp"
    "strings"
)

type Options struct {
    MTU int `json:"mtu"`
    IPv6 bool `json:"ipv6"`
    BypassLAN bool `json:"bypassLan"`
    DNS string `json:"dns"`
    SplitMode string `json:"splitMode"`
    Applications []string `json:"applications"`
    Domains []string `json:"domains"`
    Networks []string `json:"networks"`
}
type Request struct {
    Version int `json:"version"`
    Command string `json:"command"`
    NodeID string `json:"nodeId"`
    Outbound map[string]any `json:"outbound"`
    Options *Options `json:"options"`
}
type Status struct {
    Version int `json:"version"`
    State string `json:"state"`
    Error string `json:"error,omitempty"`
    // What the core actually said. The code above is a category the UI can translate; this is the
    // sentence that names the port, the adapter or the field, and without it a tunnel that will
    // not start is unattributable by anyone — the user, the journal, or whoever is reading this.
    Detail string `json:"detail,omitempty"`
    NodeID string `json:"nodeId,omitempty"`
    ConnectedAt int64 `json:"connectedAt"`
    Upload int64 `json:"upload"`
    Download int64 `json:"download"`
    ProbePort int `json:"probePort"`
    ProbeUser string `json:"probeUser"`
    ProbePassword string `json:"probePassword"`
}

const maxFrame = 1024 * 1024
type obj = map[string]any

func decodeRequest(data []byte) (Request, error) {
    var r Request
    d := json.NewDecoder(bytes.NewReader(data)); d.DisallowUnknownFields()
    if len(data) > maxFrame { return r, errors.New("frame_too_large") }
    if err := d.Decode(&r); err != nil { return r, errors.New("invalid_request") }
    if r.Version != 1 { return r, errors.New("unsupported_version") }
    switch r.Command { case "status", "disconnect": return r, nil; case "connect": default: return r, errors.New("unknown_command") }
    if r.Options == nil || r.NodeID == "" || len(r.NodeID) > 128 { return r, errors.New("invalid_options") }
    if err := validateOptions(*r.Options); err != nil { return r, err }
    if err := validateOutbound(r.Outbound); err != nil { return r, err }
    return r, nil
}

func validateOptions(o Options) error {
    if o.MTU < 1280 || o.MTU > 9000 || (o.SplitMode != "ExceptSelected" && o.SplitMode != "OnlySelected") { return errors.New("invalid_options") }
    if len(o.Applications)+len(o.Domains)+len(o.Networks) > 1024 { return errors.New("too_many_rules") }
    for _, p := range o.Applications {
        if !regexp.MustCompile(`(?i)^[a-z]:[/\\].*\.exe$`).MatchString(p) || strings.ContainsAny(p, "\x00\r\n") { return errors.New("invalid_application") }
    }
    for _, d := range o.Domains {
        if !validHost(d) || strings.Contains(d, ":") { return errors.New("invalid_domain") }
    }
    for _, n := range o.Networks {
        if _, err := netip.ParsePrefix(n); err != nil { if _, err = netip.ParseAddr(n); err != nil { return errors.New("invalid_network") } }
    }
    if strings.Contains(o.DNS, "://") {
        u, err := url.Parse(o.DNS)
        if err != nil || u.User != nil || !validHost(u.Hostname()) || (u.Scheme != "https" && u.Scheme != "tcp" && u.Scheme != "udp") { return errors.New("invalid_dns") }
    } else if !validHost(o.DNS) { return errors.New("invalid_dns") }
    return nil
}

func validHost(h string) bool {
    if _, err := netip.ParseAddr(h); err == nil { return true }
    if len(h) == 0 || len(h) > 253 { return false }
    for _, label := range strings.Split(h, ".") {
        if len(label) == 0 || len(label) > 63 || !regexp.MustCompile(`^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$`).MatchString(label) { return false }
    }
    return true
}

// Only fields emitted by the desktop's node converter may cross the privilege boundary.
// Inbounds, files, certificates, socket options, APIs and log destinations are service-owned.
//
// The allowlist has to keep up with what the JVM actually emits. Hysteria2's obfuscator and
// congestion controller live under streamSettings.finalmask in this core — `congestion & up &
// down & udphop move to finalmask/quicParams`, as transport_internet.go puts it — and until
// those five names were listed here every salamander or bandwidth-shaped node was refused
// with forbidden_field and reported to the user as unsupported by the core. Nothing added is
// a path, a certificate or a listener; they are transport knobs whose values this same walk
// keeps validating.
func validateOutbound(out obj) error {
    protocol, _ := out["protocol"].(string)
    if !strings.Contains("|vless|vmess|trojan|shadowsocks|hysteria|wireguard|http|socks|", "|"+protocol+"|") || protocol == "" { return errors.New("unsupported_protocol") }
    for k := range out { if k != "tag" && k != "protocol" && k != "settings" && k != "streamSettings" { return errors.New("forbidden_field") } }
    return validateTree(out, 0)
}

var allowedFields = strings.Fields(`tag protocol settings streamSettings vnext address port users id flow encryption security alterId servers password method version secretKey peers endpoint allowedIPs publicKey preSharedKey reserved mtu user pass network tlsSettings realitySettings serverName alpn fingerprint pinnedPeerCertSha256 verifyPeerCertByName shortId spiderX wsSettings grpcSettings httpupgradeSettings xhttpSettings kcpSettings tcpSettings rawSettings hysteriaSettings sockopt auth congestion up down finalmask udp quicParams brutalUp brutalDown type header seed tti uplinkCapacity downlinkCapacity congestion readBufferSize writeBufferSize path host headers serviceName multiMode idle_timeout health_check_timeout permit_without_stream mode udpHop ports interval obfs salamanderPassword udphop`) 

func validateTree(value any, depth int) error {
    if depth > 12 { return errors.New("invalid_outbound") }
    switch v := value.(type) {
    case map[string]any:
        for k, item := range v {
            allowed := false
            for _, field := range allowedFields { if field == k { allowed = true; break } }
            if !allowed || k == "sockopt" { return errors.New("forbidden_field") }
            if k == "headers" {
                headers, ok := item.(map[string]any); if !ok || len(headers) > 32 { return errors.New("invalid_headers") }
                for name, raw := range headers { val, ok := raw.(string); if !ok || len(name) > 128 || len(val) > 4096 || strings.ContainsAny(name+val,"\x00\r\n") { return errors.New("invalid_headers") } }
                continue
            }
            if k == "address" { if host, ok := item.(string); ok && !validHost(host) { return errors.New("invalid_host") } }
            if err := validateTree(item,depth+1); err != nil { return err }
        }
    case []any:
        if len(v) > 1024 { return errors.New("invalid_outbound") }
        for _, item := range v { if err := validateTree(item,depth+1); err != nil { return err } }
    case string:
        if len(v) > 16384 || strings.ContainsRune(v,0) { return errors.New("invalid_outbound") }
    case nil, bool, float64:
    default: return errors.New("invalid_outbound")
    }
    return nil
}

// The reason, trimmed to something that can cross the pipe and sit on a label. Written by a
// library, so: control characters folded to spaces, runs of whitespace collapsed, and a hard cap.
func detailOf(err error) string {
    if err == nil { return "" }
    cleaned := strings.Map(func(r rune) rune { if r < 0x20 { return ' ' }; return r }, err.Error())
    cleaned = strings.Join(strings.Fields(cleaned), " ")
    if len(cleaned) > 400 { cleaned = cleaned[:400] }
    return cleaned
}

func randomSecret() string { b := make([]byte,24); if _,err := rand.Read(b); err != nil { panic(err) }; return base64.RawURLEncoding.EncodeToString(b) }

func buildConfig(r Request, port int, user, password string) obj {
    o := *r.Options
    proxy := obj{}; for k,v := range r.Outbound { proxy[k] = v }; proxy["tag"] = "proxy-node"
    rules := []obj{
        {"type":"field", "inboundTag":[]string{"dns-in","probe-in"}, "outboundTag":"proxy-node"},
        {"type":"field", "port":"53", "outboundTag":"dns-out"},
    }
    if !o.IPv6 { rules = append(rules, obj{"type":"field","ip":[]string{"::/0"},"outboundTag":"block"}) }
    if o.BypassLAN { rules = append(rules, obj{"type":"field","ip":[]string{"10.0.0.0/8","172.16.0.0/12","192.168.0.0/16","127.0.0.0/8","169.254.0.0/16","fc00::/7","fe80::/10","::1/128"},"outboundTag":"direct"}) }
    target, fallback := "direct", "proxy-node"
    if o.SplitMode == "OnlySelected" { target, fallback = fallback, target }
    // Separate rules implement OR across applications, domains and networks.
    if len(o.Applications)>0 { rules=append(rules,obj{"type":"field","process":o.Applications,"outboundTag":target}) }
    if len(o.Domains)>0 { domains:=[]string{}; for _,d := range o.Domains { domains=append(domains,"domain:"+d) }; rules=append(rules,obj{"type":"field","domain":domains,"outboundTag":target}) }
    if len(o.Networks)>0 { rules=append(rules,obj{"type":"field","ip":o.Networks,"outboundTag":target}) }
    rules=append(rules,obj{"type":"field","network":"tcp,udp","outboundTag":fallback})
    dns:=strings.TrimPrefix(o.DNS,"udp://")
    strategy:="UseIPv4"; if o.IPv6 { strategy="UseIP" }
    // The adapter is only given the families the user asked for, and this is not tidiness.
    //
    // The core sets the routes before it assigns the addresses, in one call for both families,
    // and Windows answers a route it cannot place with ERROR_NOT_FOUND — "Element not found" —
    // which fails the whole batch. So on a machine with IPv6 switched off, asking for `::/1`
    // alongside the v4 halves did not merely skip the v6 route: it took the tunnel down with it,
    // and the only thing the user ever saw was that it would not start.
    //
    // Asking for a v6 address and v6 routes while the app's own setting says IPv6 is off was
    // wrong on its own terms too. Now the switch means what it says.
    gateway:=[]string{"172.31.255.1/30"}
    routes:=[]string{"0.0.0.0/1","128.0.0.0/1"}
    if o.IPv6 {
        gateway=append(gateway,"fdfe:dcba:9876::1/126")
        routes=append(routes,"::/1","8000::/1")
    }
    return obj{
        "log":obj{"loglevel":"none"},
        "stats":obj{}, "policy":obj{"system":obj{"statsOutboundUplink":true,"statsOutboundDownlink":true}},
        "dns":obj{"servers":[]string{dns},"queryStrategy":strategy,"tag":"dns-in"},
        "inbounds":[]obj{
            {"tag":"tun-in","protocol":"tun","settings":obj{"name":"Yumi","desc":"Yumi VPN","mtu":o.MTU,
                "gateway":gateway, "dns":[]string{"172.31.255.2"},
                "autoSystemRoutingTable":routes,"autoOutboundsInterface":"auto"},
                "sniffing":obj{"enabled":true,"destOverride":[]string{"http","tls","quic"},"routeOnly":true}},
            {"tag":"probe-in","protocol":"http","listen":"127.0.0.1","port":port,"settings":obj{"accounts":[]obj{{"user":user,"pass":password}}}},
        },
        "outbounds":[]obj{proxy,{"tag":"direct","protocol":"freedom","settings":obj{"domainStrategy":"UseIP"}},{"tag":"block","protocol":"blackhole"},{"tag":"dns-out","protocol":"dns"}},
        "routing":obj{"domainStrategy":"IPIfNonMatch","rules":rules},
    }
}

func freePort() (int,error) { l,err:=net.Listen("tcp4","127.0.0.1:0"); if err!=nil{return 0,err}; defer l.Close();return l.Addr().(*net.TCPAddr).Port,nil }
