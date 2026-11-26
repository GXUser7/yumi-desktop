package main

import (
    "bytes"
    "crypto/rand"
    "crypto/sha256"
    "encoding/base64"
    "encoding/hex"
    "encoding/json"
    "errors"
    "net"
    "net/netip"
    "net/url"
    "os"
    "regexp"
    "slices"
    "strconv"
    "strings"
)

type FragmentSpec struct {
    Packets  string `json:"packets"`
    Length   string `json:"length"`
    Interval string `json:"interval"`
    MaxSplit string `json:"maxSplit,omitempty"`
}

type CustomRulesSpec struct {
    Proxy  []string `json:"proxy,omitempty"`
    Direct []string `json:"direct,omitempty"`
    Block  []string `json:"block,omitempty"`
}

type ProviderRuleSpec struct {
    Match  map[string]any `json:"match"`
    Target string         `json:"target"`
}

type ProviderDnsSpec struct {
    Servers       []any  `json:"servers,omitempty"`
    QueryStrategy string `json:"queryStrategy,omitempty"`
}

type ProviderProfileSpec struct {
    Rules []ProviderRuleSpec `json:"rules,omitempty"`
    DNS   *ProviderDnsSpec   `json:"dns,omitempty"`
}

type ProbeTargetSpec struct {
    ID       string         `json:"id"`
    Outbound map[string]any `json:"outbound"`
    Fragment *FragmentSpec  `json:"fragment,omitempty"`
}

type ProbeSpec struct {
    Targets              []ProbeTargetSpec `json:"targets"`
    ProbeUrl             string            `json:"probeUrl"`
    TimeoutMillis        int               `json:"timeoutMillis"`
    Concurrency          int               `json:"concurrency,omitempty"`
    OutboundAdapter      string            `json:"outboundAdapter,omitempty"`
    ResolveServerAddress bool              `json:"resolveServerAddress"`
    ResolveDohUrl        string            `json:"resolveDohUrl,omitempty"`
}

type ProbeResultItem struct {
    ID     string `json:"id"`
    Millis int    `json:"millis"`
}

type Options struct {
    MTU int `json:"mtu"`
    IPv6 bool `json:"ipv6"`
    BypassLAN bool `json:"bypassLan"`
    DNS string `json:"dns"`
    DirectDNS string `json:"directDns,omitempty"`
    HijackDNS *bool `json:"hijackDns,omitempty"`
    SplitMode string `json:"splitMode"`
    // Process names ("chrome.exe") or, from older settings, absolute paths. See applicationVariants.
    Applications []string `json:"applications"`
    Domains []string `json:"domains"`
    Networks []string `json:"networks"`
    // Block everything outside the tunnel from the moment of connecting until an explicit
    // disconnect, including after the worker dies. Enforced by the service; see firewall/.
    KillSwitch bool `json:"killSwitch"`
    // While connected, the service moves the system timezone onto the server's and silences the
    // system's location sources, restoring both on disconnect. Windows: system_windows.go; the
    // unix builds carry the same contract over timedatectl/systemsetup.
    MatchLocation bool `json:"matchLocation"`
    // Russian sites and addresses go around the tunnel, by the geo databases, whatever the
    // application rules say. Inert until the databases have been downloaded.
    BypassRussia bool `json:"bypassRussia"`
    BlockQuic bool `json:"blockQuic,omitempty"`
    BlockAds bool `json:"blockAds,omitempty"`
    CustomRules *CustomRulesSpec `json:"customRules,omitempty"`
    ProviderProfile *ProviderProfileSpec `json:"providerProfile,omitempty"`
    // The physical adapter the core sends proxy traffic out of. Empty means the adapter Windows
    // itself uses for its default route; anything else is a name from the service's own adapter
    // list, as Xray compares it against Go's interface name.
    OutboundAdapter string `json:"outboundAdapter"`
    ResolveServerAddress *bool `json:"resolveServerAddress,omitempty"`
    ResolveDohUrl string `json:"resolveDohUrl,omitempty"`
}

// One physical adapter the core could use to reach the internet, for the settings screen.
type Adapter struct {
    Name string `json:"name"`
    Address string `json:"address,omitempty"`
    Default bool `json:"default,omitempty"`
}
type Request struct {
    Version int `json:"version"`
    Command string `json:"command"`
    NodeID string `json:"nodeId"`
    Outbound map[string]any `json:"outbound"`
    Fragment *FragmentSpec `json:"fragment,omitempty"`
    Group *OutboundGroup `json:"group,omitempty"`
    Options *Options `json:"options"`
    Probe *ProbeSpec `json:"probe,omitempty"`
    // The active server's country, as the app reads it off the flag in its name. Top level
    // rather than inside Options so a hot switch to a server in another country keeps the
    // worker: only this field changes, and the timezone follows it live. See validCountry.
    ServerCountry string `json:"serverCountry"`
    dnsHosts      map[string]string
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
    // Travels with the worker's acknowledged server, including through a rejected hot switch.
    ServerCountry string `json:"serverCountry,omitempty"`
    GroupMember int `json:"groupMember,omitempty"`
    GroupMembers int `json:"groupMembers,omitempty"`
    GroupLatency int `json:"groupLatency,omitempty"`
    ConnectedAt int64 `json:"connectedAt"`
    Upload int64 `json:"upload"`
    Download int64 `json:"download"`
    ProbePort int `json:"probePort"`
    ProbeUser string `json:"probeUser"`
    ProbePassword string `json:"probePassword"`
    // Whether the kill switch is holding traffic right now. It outlives the tunnel on purpose: a
    // worker that died leaves this true and the state "failed", and only a disconnect clears it.
    KillSwitch bool `json:"killSwitch"`
    // The routing databases on disk, for the settings screen. See geo.go.
    GeoReady bool `json:"geoReady"`
    GeoUpdating bool `json:"geoUpdating"`
    GeoUpdatedAt int64 `json:"geoUpdatedAt"`
    GeoBytes int64 `json:"geoBytes"`
    GeoError string `json:"geoError,omitempty"`
    // The location mode as the machine sees it: the timezone the service has put the system
    // into, or the sentence about why it could not. Empty whenever the mode is off.
    SysTimezone string `json:"sysTimezone,omitempty"`
    SysNote string `json:"sysNote,omitempty"`
    // The worker's adapter, reported to the supervisor and never past it: the kill switch has to
    // let this interface through and the supervisor strips it before answering the app. Windows
    // reports the wintun LUID; unix reports the interface name the core created.
    TunLUID uint64 `json:"tunLuid,omitempty"`
    TunName string `json:"tunName,omitempty"`
    // Only answered for the "adapters" command; the once-a-second status poll does not carry them.
    Adapters []Adapter `json:"adapters,omitempty"`
    ProbeResults []ProbeResultItem `json:"probeResults,omitempty"`
    Logs string `json:"logs,omitempty"`
}

const maxFrame = 1024 * 1024
type obj = map[string]any

func decodeRequest(data []byte) (Request, error) {
    var r Request
    d := json.NewDecoder(bytes.NewReader(data)); d.DisallowUnknownFields()
    if len(data) > maxFrame { return r, errors.New("frame_too_large") }
    if err := d.Decode(&r); err != nil { return r, errors.New("invalid_request") }
    if r.Version != 1 { return r, errors.New("unsupported_version") }
    switch r.Command {
    case "status", "disconnect", "geo_update", "adapters", "logs":
        return r, nil
    case "probe":
        if err := validateProbeSpec(r.Probe); err != nil { return r, err }
        return r, nil
    case "connect":
    default:
        return r, errors.New("unknown_command")
    }
    if r.Options == nil || r.NodeID == "" || len(r.NodeID) > 128 { return r, errors.New("invalid_options") }
    if err := validateOptions(*r.Options); err != nil { return r, err }
    if err := validateOutbound(r.Outbound); err != nil { return r, err }
    if r.Fragment != nil {
        if err := validateFragmentSpec(*r.Fragment); err != nil { return r, err }
    }
    if r.Group != nil {
        if len(r.Group.Members) < 1 || len(r.Group.Members) > 64 ||
            (r.Group.Strategy != "cost" && r.Group.Strategy != "fastest") || r.Group.MaxDelayMillis < 0 || r.Group.MaxDelayMillis > 600000 { return r, errors.New("invalid_group") }
        for _, member := range r.Group.Members { if err := validateOutbound(member); err != nil { return r, err } }
        for k, frag := range r.Group.MemberFragments {
            if k == "" || len(k) > 160 { return r, errors.New("invalid_fragment") }
            if err := validateFragmentSpec(frag); err != nil { return r, err }
        }
    }
    if !validCountry(r.ServerCountry) { return r, errors.New("invalid_country") }
    return r, nil
}

// A server's country, as the app reads it off the flag in its name: two capital letters, or
// nothing at all — a server without a flag only skips the timezone half of the location mode.
func validCountry(code string) bool {
    if code == "" { return true }
    if len(code) != 2 { return false }
    for _, r := range code { if r < 'A' || r > 'Z' { return false } }
    return true
}

func validateOptions(o Options) error {
    if o.MTU < 1280 || o.MTU > 9000 || (o.SplitMode != "ExceptSelected" && o.SplitMode != "OnlySelected") { return errors.New("invalid_options") }
    if len(o.OutboundAdapter) > 256 || strings.ContainsAny(o.OutboundAdapter, "\x00\r\n") || ownAdapter(o.OutboundAdapter) {
        return errors.New("invalid_adapter")
    }
    if len(o.Applications)+len(o.Domains)+len(o.Networks) > 1024 { return errors.New("too_many_rules") }
    for _, p := range o.Applications {
        if !validApplication(p) { return errors.New("invalid_application") }
    }
    for _, d := range o.Domains {
        if !validHost(d) || strings.Contains(d, ":") { return errors.New("invalid_domain") }
    }
    for _, n := range o.Networks {
        if _, err := netip.ParsePrefix(n); err != nil { if _, err = netip.ParseAddr(n); err != nil { return errors.New("invalid_network") } }
    }
    if err := validateSingleDNS(o.DNS); err != nil { return err }
    if o.DirectDNS != "" {
        if err := validateSingleDNS(o.DirectDNS); err != nil { return err }
    }
    if o.ResolveDohUrl != "" {
        if err := validateDoHURL(o.ResolveDohUrl); err != nil { return err }
    }
    if err := validateCustomRules(o.CustomRules); err != nil { return err }
    if err := validateProviderProfile(o.ProviderProfile); err != nil { return err }
    return nil
}

func validateSingleDNS(raw string) error {
    dnsHost := raw
    if strings.Contains(raw, "://") {
        u, err := url.Parse(raw)
        if err != nil || u.User != nil || !validHost(u.Hostname()) || (u.Scheme != "https" && u.Scheme != "h2c" && u.Scheme != "tcp" && u.Scheme != "udp") {
            return errors.New("invalid_dns")
        }
        dnsHost = u.Hostname()
    } else if host, port, err := net.SplitHostPort(raw); err == nil {
        if !validHost(host) || !validPort(port) { return errors.New("invalid_dns") }
        dnsHost = host
    } else if !validHost(raw) {
        return errors.New("invalid_dns")
    }
    if isForbiddenDNSHost(dnsHost) { return errors.New("invalid_dns") }
    return nil
}

func validateDoHURL(raw string) error {
    trimmed := strings.TrimSpace(raw)
    if len(trimmed) > 2048 || strings.ContainsAny(trimmed, "\x00\r\n") {
        return errors.New("invalid_dns")
    }
    u, err := url.Parse(trimmed)
    if err != nil || u.Scheme != "https" || u.User != nil || !validHost(u.Hostname()) || isForbiddenDNSHost(u.Hostname()) {
        return errors.New("invalid_dns")
    }
    return nil
}

func validIntRange(raw string, minVal, maxVal int) bool {
    trimmed := strings.TrimSpace(raw)
    if trimmed == "" || len(trimmed) > 32 { return false }
    parts := strings.Split(trimmed, "-")
    if len(parts) == 1 {
        v, err := strconv.Atoi(parts[0])
        return err == nil && v >= minVal && v <= maxVal
    }
    if len(parts) == 2 {
        a, err1 := strconv.Atoi(parts[0])
        b, err2 := strconv.Atoi(parts[1])
        return err1 == nil && err2 == nil && a >= minVal && b <= maxVal && a <= b
    }
    return false
}

func validateFragmentSpec(f FragmentSpec) error {
    packets := strings.TrimSpace(f.Packets)
    if !strings.EqualFold(packets, "tlshello") && !validIntRange(packets, 1, 100) {
        return errors.New("invalid_fragment")
    }
    if !validIntRange(f.Length, 1, 2000) {
        return errors.New("invalid_fragment")
    }
    if !validIntRange(f.Interval, 1, 1000) {
        return errors.New("invalid_fragment")
    }
    if strings.TrimSpace(f.MaxSplit) != "" && !validIntRange(f.MaxSplit, 1, 100) {
        return errors.New("invalid_fragment")
    }
    return nil
}

func fragmentTag(f FragmentSpec) string {
    h := sha256.Sum256([]byte(f.Packets + "|" + f.Length + "|" + f.Interval + "|" + f.MaxSplit))
    return "frag-" + hex.EncodeToString(h[:6])
}

func buildFragmentOutbound(tag string, f FragmentSpec) obj {
    frag := obj{
        "packets":  f.Packets,
        "length":   f.Length,
        "interval": f.Interval,
    }
    if strings.TrimSpace(f.MaxSplit) != "" {
        frag["maxSplit"] = strings.TrimSpace(f.MaxSplit)
    }
    return obj{
        "tag":      tag,
        "protocol": "freedom",
        "settings": obj{
            "domainStrategy": "AsIs",
            "fragment":       frag,
        },
    }
}

func attachFragmentDialerProxy(outbound obj, fragTag string) {
    if outbound == nil || fragTag == "" {
        return
    }
    stream, _ := outbound["streamSettings"].(obj)
    if stream == nil {
        stream = obj{}
        outbound["streamSettings"] = stream
    }
    stream["sockopt"] = obj{"dialerProxy": fragTag}
}

func validateCustomRules(cr *CustomRulesSpec) error {
    if cr == nil {
        return nil
    }
    if len(cr.Proxy)+len(cr.Direct)+len(cr.Block) > 512 {
        return errors.New("too_many_rules")
    }
    checkBucket := func(list []string) error {
        for _, entry := range list {
            trimmed := strings.TrimSpace(entry)
            if trimmed == "" || len(trimmed) > 512 || strings.ContainsAny(entry, "\x00\r\n") {
                return errors.New("invalid_rule")
            }
            if strings.HasPrefix(strings.ToLower(trimmed), "ext:") {
                return errors.New("invalid_rule")
            }
        }
        return nil
    }
    if err := checkBucket(cr.Proxy); err != nil { return err }
    if err := checkBucket(cr.Direct); err != nil { return err }
    return checkBucket(cr.Block)
}

var allowedProviderMatchKeys = map[string]bool{
    "domain": true, "ip": true, "port": true, "sourcePort": true,
    "network": true, "source": true, "sourceIP": true, "protocol": true, "attrs": true,
}

func validateProviderProfile(pp *ProviderProfileSpec) error {
    if pp == nil {
        return nil
    }
    if len(pp.Rules) > 512 {
        return errors.New("too_many_rules")
    }
    for _, rule := range pp.Rules {
        if rule.Target != "proxy" && rule.Target != "direct" && rule.Target != "block" {
            return errors.New("invalid_provider_rule")
        }
        if len(rule.Match) == 0 || len(rule.Match) > 16 {
            return errors.New("invalid_provider_rule")
        }
        for key, val := range rule.Match {
            if !allowedProviderMatchKeys[key] {
                return errors.New("invalid_provider_rule")
            }
            if err := validateProviderMatchValue(val); err != nil {
                return err
            }
        }
    }
    if pp.DNS != nil {
        if len(pp.DNS.Servers) > 16 {
            return errors.New("invalid_dns")
        }
        if qs := pp.DNS.QueryStrategy; qs != "" && qs != "UseIP" && qs != "UseIPv4" && qs != "UseIPv6" && qs != "UseSystem" {
            return errors.New("invalid_dns")
        }
        for _, srv := range pp.DNS.Servers {
            switch v := srv.(type) {
            case string:
                if strings.TrimSpace(v) != "" && validateSingleDNS(v) != nil {
                    return errors.New("invalid_dns")
                }
            case map[string]any:
                addr, _ := v["address"].(string)
                if addr != "" && validateSingleDNS(addr) != nil {
                    return errors.New("invalid_dns")
                }
                for dk := range v {
                    switch dk {
                    case "address", "port", "domains", "expectIPs", "skipFallback", "clientIP":
                    default:
                        return errors.New("invalid_dns")
                    }
                }
            default:
                return errors.New("invalid_dns")
            }
        }
    }
    return nil
}

func validateProviderMatchValue(v any) error {
    checkStr := func(s string) error {
        if len(s) > 512 || strings.ContainsAny(s, "\x00\r\n") || strings.HasPrefix(strings.ToLower(strings.TrimSpace(s)), "ext:") {
            return errors.New("invalid_provider_rule")
        }
        return nil
    }
    switch x := v.(type) {
    case string:
        return checkStr(x)
    case []any:
        if len(x) > 512 {
            return errors.New("invalid_provider_rule")
        }
        for _, elem := range x {
            s, ok := elem.(string)
            if !ok {
                return errors.New("invalid_provider_rule")
            }
            if err := checkStr(s); err != nil {
                return err
            }
        }
        return nil
    default:
        return errors.New("invalid_provider_rule")
    }
}

func isForbiddenDNSHost(h string) bool {
    lower := strings.ToLower(strings.TrimSuffix(strings.Trim(strings.TrimSpace(h), "[]"), "."))
    if lower == "localhost" || strings.HasSuffix(lower, ".localhost") || lower == "fakedns" {
        return true
    }
    if ip, err := netip.ParseAddr(lower); err == nil {
        ip = ip.Unmap()
        return ip.IsLoopback() || ip.IsUnspecified()
    }
    return false
}

// Yumi's own adapter is never a way out. The prefix form covers the per-connection names older
// builds used, and the exact current name covers every platform — the name differs per OS, and
// macOS names the tunnel after the utun unit it was given. Both the route picker and request
// validation need this answer.
func ownAdapter(name string) bool {
    return name != "" && (strings.EqualFold(name, "Yumi") || strings.EqualFold(name, tunAdapterName()) || strings.HasPrefix(strings.ToLower(name), "yumi-"))
}

func validHost(h string) bool {
    if _, err := netip.ParseAddr(h); err == nil { return true }
    if len(h) == 0 || len(h) > 253 { return false }
    for _, label := range strings.Split(h, ".") {
        if len(label) == 0 || len(label) > 63 || !regexp.MustCompile(`^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$`).MatchString(label) { return false }
    }
    return true
}

func validPort(p string) bool {
    if p == "" { return false }
    for _, r := range p { if r < '0' || r > '9' { return false } }
    n, err := strconv.Atoi(p)
    return err == nil && n >= 1 && n <= 65535
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

var allowedFields = strings.Fields(`tag protocol settings streamSettings vnext address port users id flow encryption security alterId servers password method version secretKey peers endpoint allowedIPs publicKey preSharedKey reserved mtu user pass network tlsSettings realitySettings serverName alpn fingerprint pinnedPeerCertSha256 verifyPeerCertByName echConfigList mldsa65Verify shortId spiderX wsSettings grpcSettings httpupgradeSettings xhttpSettings kcpSettings tcpSettings rawSettings hysteriaSettings sockopt auth congestion up down finalmask udp quicParams brutalUp brutalDown type header seed tti uplinkCapacity downlinkCapacity congestion readBufferSize writeBufferSize path host headers serviceName multiMode authority idle_timeout health_check_timeout permit_without_stream mode extra downloadSettings xPaddingBytes noGRPCHeader noSSEHeader scMaxEachPostBytes scMinPostsIntervalMs scMaxBufferedPosts scStreamUpServerSecs xmux maxConcurrency maxConnections cMaxReuseTimes hMaxRequestTimes hMaxReusableSecs hKeepAlivePeriod udpHop ports interval obfs salamanderPassword udphop`) 

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
            if k == "endpoint" {
                ep, ok := item.(string)
                if !ok { return errors.New("invalid_host") }
                host, port, err := net.SplitHostPort(ep)
                if err != nil || !validHost(host) || !validPort(port) { return errors.New("invalid_host") }
            }
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

// An application rule is a process name — "chrome.exe", the way Clash and the core both think of
// one — or, from settings written before names were accepted, an absolute path to an .exe.
//
// Names rather than paths because the core compares paths byte for byte against what Windows
// reports for the running process, and a path picked out of a shortcut or typed by hand differs
// from that in case often enough to make the rule match nothing. A name also survives the update
// that moves an application into a versioned folder, which is how Discord and its relatives ship.
var applicationName = regexp.MustCompile(`^[^\\/:*?"<>|\x00-\x1f]{1,255}$`)
var applicationPath = regexp.MustCompile(`(?i)^[a-z]:[/\\].*\.exe$`)

func validApplication(p string) bool {
    if strings.ContainsAny(p, "\x00\r\n") { return false }
    if applicationPath.MatchString(p) { return true }
    trimmed := strings.TrimSpace(p)
    return trimmed == p && applicationName.MatchString(p) && strings.Trim(p, ".") != ""
}

// The spellings of an application the core is given.
//
// The core matches a name exactly, case included, against the executable's file name without
// ".exe" (common/net/find_process_windows.go), while Windows itself does not care: "Telegram.exe"
// launched from a shortcut and "telegram.exe" typed into the box are the same program to everyone
// but the router. A name picked from the list arrives spelt as it is on disk; one typed by hand
// arrives as typed, so it is offered in the three spellings people actually use. Paths pass
// through unchanged — they are compared whole, as before.
func applicationVariants(apps []string) []string {
    seen := map[string]bool{}
    out := []string{}
    add := func(v string) { if v != "" && !seen[v] { seen[v] = true; out = append(out, v) } }
    for _, app := range apps {
        if strings.ContainsAny(app, "/\\") { add(strings.ReplaceAll(app, "\\", "/")); continue }
        name := app
        if strings.HasSuffix(strings.ToLower(name), ".exe") { name = name[:len(name)-4] }
        add(name)
        add(strings.ToLower(name))
        if lower := strings.ToLower(name); lower != "" { add(strings.ToUpper(lower[:1]) + lower[1:]) }
    }
    return out
}

// The tunnel's own addresses. Here and only here, because two of the rules below are built from
// them and the adapter is given them, and a subnet spelt in two places drifts.
const (
    tunGatewayV4 = "172.31.255.1/30"
    tunDNSV4     = "172.31.255.2"
    tunSubnetV4  = "172.31.255.0/30"
    tunGatewayV6 = "fdfe:dcba:9876::1/126"
    tunSubnetV6  = "fdfe:dcba:9876::/126"
)

// The private ranges, spelt out rather than taken from geoip:private, so that reaching the router
// or the printer never depends on a database having been downloaded.
var privateRanges = []string{"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16", "fc00::/7", "fe80::/10", "::1/128"}

// Names that go through the proxy even when the address they resolve to is registered in Russia.
//
// The same list as the phone's (XrayConfigFactory.PROXIED_DESPITE_RU_ADDRESS), for the same reason:
// YouTube's video is served from Google Global Cache nodes inside Russian providers, geoip files
// those addresses under RU, and a rule sending them direct sends the player to a node that hangs.
var proxiedDespiteRussianAddress = []string{
    "domain:googlevideo.com", "domain:youtube.com", "domain:youtu.be", "domain:ytimg.com",
    "domain:ggpht.com", "domain:youtube-nocookie.com", "domain:youtubei.googleapis.com",
}

// How long a flow may stay silent, and how long a half-closed one lives, in seconds.
//
// The second is the one that bites. The core's default for both directions is one second
// (features/policy/policy.go), and it is not an idle timeout: it replaces the idle timeout the
// moment either direction finishes, so a connection whose upload is done has one second to receive
// something or be shot. The phone's journal showed push connections rebuilt every few minutes for
// exactly this; a desktop's long-polls and chat sockets have the same shape.
const (
    connIdleSeconds   = 1800
    halfClosedSeconds = 30
)

func buildConfig(r Request, port int, user, password string, geo bool, outbound ...string) obj {
    var cats geoCategories
    if geo {
        cats = loadGeoCategories(os.Getenv("XRAY_LOCATION_ASSET"))
    }
    outName := ""
    if len(outbound) > 0 {
        outName = outbound[0]
    }
    return buildConfigWithGeo(r, port, user, password, geo, geo, cats, outName)
}

func isCustomRuleAddr(entry string) bool {
    lower := strings.ToLower(strings.TrimSpace(entry))
    if strings.HasPrefix(lower, "geoip:") {
        return true
    }
    bare := strings.Trim(strings.Split(lower, "/")[0], "[]")
    _, err := netip.ParseAddr(bare)
    return err == nil
}

func extractHostFromResolver(raw string) string {
    raw = strings.TrimSpace(raw)
    if raw == "" {
        return ""
    }
    if strings.Contains(raw, "://") {
        if u, err := url.Parse(raw); err == nil {
            return u.Hostname()
        }
        return ""
    }
    if h, _, err := net.SplitHostPort(raw); err == nil {
        return h
    }
    return raw
}

func formatDnsServerEntry(raw string) any {
    raw = strings.TrimPrefix(strings.TrimSpace(raw), "udp://")
    if !strings.Contains(raw, "://") {
        if h, p, err := net.SplitHostPort(raw); err == nil && !strings.EqualFold(h, "localhost") {
            if portNum, err := strconv.Atoi(p); err == nil && portNum > 0 {
                return obj{"address": h, "port": portNum}
            }
        }
    }
    return raw
}

func filterListEntries(raw any, geo bool, cats geoCategories) ([]string, bool) {
    filterStr := func(s string) bool {
        lower := strings.ToLower(strings.TrimSpace(s))
        if strings.HasPrefix(lower, "ext:") {
            return false
        }
        if strings.HasPrefix(lower, "geosite:") || strings.HasPrefix(lower, "geoip:") {
            return geo && cats.knows(s)
        }
        return true
    }
    switch v := raw.(type) {
    case string:
        if filterStr(v) {
            return []string{v}, true
        }
        return nil, false
    case []any:
        out := make([]string, 0, len(v))
        for _, item := range v {
            s, ok := item.(string)
            if !ok {
                return nil, false
            }
            if filterStr(s) {
                out = append(out, s)
            }
        }
        return out, len(out) > 0
    default:
        return nil, false
    }
}

func carryProvider(pp *ProviderProfileSpec, geo bool, cats geoCategories) (rules []obj, dnsServers []any, queryStrategy string) {
    if pp == nil {
        return nil, nil, ""
    }
    for _, rule := range pp.Rules {
        conds := obj{"type": "field"}
        drop := false
        for k, val := range rule.Match {
            if k == "domain" || k == "ip" || k == "source" || k == "sourceIP" {
                kept, ok := filterListEntries(val, geo, cats)
                if !ok {
                    drop = true
                    break
                }
                conds[k] = kept
            } else {
                conds[k] = val
            }
        }
        if drop {
            continue
        }
        switch rule.Target {
        case "proxy":
            conds["outboundTag"] = "proxy-node"
        case "direct":
            conds["outboundTag"] = "direct"
        case "block":
            conds["outboundTag"] = "block"
        default:
            continue
        }
        rules = append(rules, conds)
    }
    if pp.DNS != nil {
        queryStrategy = pp.DNS.QueryStrategy
        for _, srv := range pp.DNS.Servers {
            switch s := srv.(type) {
            case string:
                if s != "" {
                    dnsServers = append(dnsServers, formatDnsServerEntry(s))
                }
            case map[string]any:
                narrowed := obj{}
                dropSrv := false
                for dk, dv := range s {
                    if dk == "domains" || dk == "expectIPs" {
                        kept, ok := filterListEntries(dv, geo, cats)
                        if !ok {
                            dropSrv = true
                            break
                        }
                        narrowed[dk] = kept
                    } else {
                        narrowed[dk] = dv
                    }
                }
                if !dropSrv {
                    dnsServers = append(dnsServers, narrowed)
                }
            }
        }
    }
    return rules, dnsServers, queryStrategy
}

func buildConfigWithGeo(r Request, port int, user, password string, bypassRU, geo bool, cats geoCategories, outbound string) obj {
    o := *r.Options
    outboundInterface := "auto"
    if outbound != "" { outboundInterface = outbound }
    proxy := cloneJSONMap(r.Outbound)
    if proxy == nil { proxy = obj{} }
    proxy["tag"] = "proxy-node"

    outbounds := []obj{proxy}
    if r.Fragment != nil {
        fragTag := fragmentTag(*r.Fragment)
        attachFragmentDialerProxy(proxy, fragTag)
        outbounds = append(outbounds, buildFragmentOutbound(fragTag, *r.Fragment))
    }

    providerRules, providerDNS, providerQS := carryProvider(o.ProviderProfile, geo, cats)
    hijackDNS := o.HijackDNS == nil || *o.HijackDNS

    var rules []obj
    if len(providerDNS) > 0 {
        rules = append(rules, obj{"type": "field", "inboundTag": []string{"probe-in"}, "outboundTag": "proxy-node"})
        seenBoot := map[string]bool{}
        var bootHosts []string
        for _, srv := range providerDNS {
            var addr string
            switch s := srv.(type) {
            case string:
                addr = s
            case obj:
                addr, _ = s["address"].(string)
            }
            h := extractHostFromResolver(addr)
            if h != "" {
                if _, err := netip.ParseAddr(h); err != nil && !seenBoot[h] {
                    seenBoot[h] = true
                    bootHosts = append(bootHosts, "full:"+h)
                }
            }
        }
        if len(bootHosts) > 0 {
            rules = append(rules, obj{"type": "field", "inboundTag": []string{"dns-in"}, "domain": bootHosts, "outboundTag": "direct"})
        }
        rules = append(rules, obj{"type": "field", "inboundTag": []string{"dns-in"}, "outboundTag": "proxy-node"})
    } else if o.DirectDNS != "" {
        rules = append(rules, obj{"type": "field", "inboundTag": []string{"probe-in"}, "outboundTag": "proxy-node"})
        if directHost := extractHostFromResolver(o.DirectDNS); directHost != "" {
            if _, err := netip.ParseAddr(directHost); err == nil {
                rules = append(rules, obj{"type": "field", "inboundTag": []string{"dns-in"}, "ip": []string{directHost}, "outboundTag": "direct"})
            } else {
                rules = append(rules, obj{"type": "field", "inboundTag": []string{"dns-in"}, "domain": []string{"full:" + directHost}, "outboundTag": "direct"})
            }
        }
        rules = append(rules, obj{"type": "field", "inboundTag": []string{"dns-in"}, "outboundTag": "proxy-node"})
    } else {
        rules = append(rules, obj{"type": "field", "inboundTag": []string{"dns-in", "probe-in"}, "outboundTag": "proxy-node"})
    }

    if hijackDNS {
        rules = append(rules, obj{"type": "field", "port": "53", "outboundTag": "dns-out"})
    } else {
        rules = append(rules, obj{"type": "field", "ip": []string{tunDNSV4 + "/32"}, "port": "53", "outboundTag": "dns-out"})
    }
    // Everything else addressed to the tunnel's own subnet is dropped before LAN/user rules.
    rules = append(rules, obj{"type": "field", "ip": []string{tunSubnetV4, tunSubnetV6}, "outboundTag": "block"})
    if !o.IPv6 { rules = append(rules, obj{"type": "field", "ip": []string{"::/0"}, "outboundTag": "block"}) }
    if o.BlockQuic { rules = append(rules, obj{"type": "field", "port": "443", "network": "udp", "outboundTag": "block"}) }
    if o.BypassLAN { rules = append(rules, obj{"type": "field", "ip": privateRanges, "outboundTag": "direct"}) }

    target, fallback := "direct", "proxy-node"
    if o.SplitMode == "OnlySelected" { target, fallback = fallback, target }
    if len(o.Domains) > 0 {
        domains := make([]string, 0, len(o.Domains))
        for _, d := range o.Domains { domains = append(domains, "domain:"+d) }
        rules = append(rules, obj{"type": "field", "domain": domains, "outboundTag": target})
    }
    if len(o.Networks) > 0 {
        rules = append(rules, obj{"type": "field", "ip": o.Networks, "outboundTag": target})
    }
    if o.CustomRules != nil {
        buckets := []struct {
            entries []string
            tag     string
        }{
            {o.CustomRules.Block, "block"},
            {o.CustomRules.Proxy, "proxy-node"},
            {o.CustomRules.Direct, "direct"},
        }
        for _, b := range buckets {
            var ips, doms []string
            for _, entry := range b.entries {
                trimmed := strings.TrimSpace(entry)
                lower := strings.ToLower(trimmed)
                if strings.HasPrefix(lower, "ext:") {
                    continue
                }
                if (strings.HasPrefix(lower, "geosite:") || strings.HasPrefix(lower, "geoip:")) && (!geo || !cats.knows(trimmed)) {
                    continue
                }
                if isCustomRuleAddr(trimmed) {
                    ips = append(ips, trimmed)
                } else {
                    doms = append(doms, trimmed)
                }
            }
            if len(doms) > 0 {
                rules = append(rules, obj{"type": "field", "domain": doms, "outboundTag": b.tag})
            }
            if len(ips) > 0 {
                rules = append(rules, obj{"type": "field", "ip": ips, "outboundTag": b.tag})
            }
        }
    }
    if len(providerRules) > 0 {
        rules = append(rules, providerRules...)
    }
    if geo && o.BlockAds && cats.knows("geosite:category-ads-all") {
        rules = append(rules, obj{"type": "field", "domain": []string{"geosite:category-ads-all"}, "outboundTag": "block"})
    }

    apps := applicationVariants(o.Applications)
    if o.SplitMode == "ExceptSelected" && !o.KillSwitch {
        for _, self := range applicationVariants([]string{"Yumi.exe", "yumi-service.exe"}) {
            if !slices.Contains(apps, self) { apps = append(apps, self) }
        }
    }
    appRule := func() { if len(apps) > 0 { rules = append(rules, obj{"type": "field", "process": apps, "outboundTag": target}) } }
    if o.SplitMode == "ExceptSelected" {
        appRule()
        if bypassRU { rules = append(rules, obj{"type": "field", "domain": proxiedDespiteRussianAddress, "outboundTag": "proxy-node"}) }
    } else if bypassRU && len(apps) > 0 {
        rules = append(rules, obj{"type": "field", "domain": proxiedDespiteRussianAddress, "process": apps, "outboundTag": "proxy-node"})
    }
    if bypassRU {
        rules = append(rules,
            obj{"type": "field", "domain": []string{"geosite:category-ru"}, "outboundTag": "direct"},
            obj{"type": "field", "ip": []string{"geoip:ru"}, "outboundTag": "direct"},
        )
    }
    if o.SplitMode == "OnlySelected" { appRule() }
    rules = append(rules, obj{"type": "field", "network": "tcp,udp", "outboundTag": fallback})

    dns := strings.TrimPrefix(o.DNS, "udp://")
    strategy := "UseIPv4"
    if o.IPv6 { strategy = "UseIP" }
    gateway := []string{tunGatewayV4}
    routes := []string{"0.0.0.0/1", "128.0.0.0/1"}
    if o.IPv6 {
        gateway = append(gateway, tunGatewayV6)
        routes = append(routes, "::/1", "8000::/1")
    }
    var dnsConfig obj
    if len(providerDNS) > 0 {
        servers := append([]any(nil), providerDNS...)
        if dns != "" {
            hasRemote := false
            for _, srv := range servers {
                var a string
                switch x := srv.(type) {
                case string:
                    a = x
                case obj:
                    a, _ = x["address"].(string)
                }
                if strings.EqualFold(a, dns) {
                    hasRemote = true
                    break
                }
            }
            if !hasRemote {
                servers = append(servers, formatDnsServerEntry(dns))
            }
        }
        pqs := strategy
        if o.IPv6 && providerQS != "" {
            pqs = providerQS
        }
        dnsConfig = obj{"servers": servers, "enableParallelQuery": true, "queryStrategy": pqs, "tag": "dns-in"}
    } else if o.DirectDNS != "" {
        servers := []any{formatDnsServerEntry(dns), formatDnsServerEntry(o.DirectDNS)}
        dnsConfig = obj{"servers": servers, "queryStrategy": strategy, "tag": "dns-in"}
    } else {
        dnsConfig = obj{"servers": []string{dns}, "queryStrategy": strategy, "tag": "dns-in"}
    }
    if len(r.dnsHosts) > 0 { dnsConfig["hosts"] = r.dnsHosts }
    outbounds = append(outbounds,
        obj{"tag": "direct", "protocol": "freedom", "settings": obj{"domainStrategy": "UseIP"}},
        obj{"tag": "block", "protocol": "blackhole"},
        obj{"tag": "dns-out", "protocol": "dns", "settings": obj{"nonIPQuery": "skip"}},
    )
    return obj{
        "log": obj{"loglevel": "none"},
        "stats": obj{},
        "policy": obj{
            "levels": obj{"0": obj{"connIdle": connIdleSeconds, "uplinkOnly": halfClosedSeconds, "downlinkOnly": halfClosedSeconds}},
            "system": obj{"statsOutboundUplink": true, "statsOutboundDownlink": true},
        },
        "dns": dnsConfig,
        "inbounds": []obj{
            {"tag": "tun-in", "protocol": "tun", "settings": obj{"name": tunAdapterName(), "desc": "Yumi VPN", "mtu": o.MTU,
                "gateway": gateway, "dns": []string{tunDNSV4},
                "autoSystemRoutingTable": routes, "autoOutboundsInterface": outboundInterface},
                "sniffing": obj{"enabled": true, "destOverride": []string{"http", "tls", "quic"}, "routeOnly": true}},
            {"tag": "probe-in", "protocol": "http", "listen": "127.0.0.1", "port": port, "settings": obj{"accounts": []obj{{"user": user, "pass": password}}}},
        },
        "outbounds": outbounds,
        "routing": obj{"domainStrategy": "IPIfNonMatch", "rules": rules},
    }
}

func freePort() (int,error) { l,err:=net.Listen("tcp4","127.0.0.1:0"); if err!=nil{return 0,err}; defer l.Close();return l.Addr().(*net.TCPAddr).Port,nil }
