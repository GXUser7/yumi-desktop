//go:build linux

package firewall

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
)

/*
The Linux kill switch: an nftables table with three hooked chains — output, input, and forward —
all dropping by default, permitting exactly what the Windows WFP version permits — this daemon's
own account, loopback, the tunnel's interface, DHCP, neighbour discovery, and optionally the local
network (excluding DNS/DoT/LLMNR on ports 53, 853, and 5355).

Two honest differences from the Windows half. The permit is per-account rather than per-executable:
nftables matches owners, and the daemon is root, so every root process rides free alongside it —
user-level traffic, which is what the shield exists to hold, is still confined to the tunnel.
And there is no dynamic session: an nftables table outlives the process that made it, so a daemon
killed mid-shield leaves the block standing until the daemon is restarted, which the systemd unit
does immediately and [CleanupLeftovers] unwinds first thing.
*/

const (
	lanPermitV4 = "ip daddr { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16 } accept"
	lanPermitV6 = "ip6 daddr { fc00::/7, fe80::/10 } accept"

	nftCommandTimeout   = 15 * time.Second
	nftDeleteMaxRetries = 3
)

var (
	shieldCounter uint64
	ifaceNameRe   = regexp.MustCompile(`^[A-Za-z0-9_.:-]{1,32}$`)
	shieldTableRe = regexp.MustCompile(`^yumi_shield_[0-9]+(?:_[0-9]+)?$`)

	findNFTPath   = defaultFindNFTPath
	nftEnv        = func() []string { return []string{"PATH=/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL=C"} }
	nftRetryDelay = 50 * time.Millisecond
)

type nftShield struct {
	nftPath string
	table   string
}

func defaultFindNFTPath() (string, error) {
	for _, candidate := range []string{"/usr/sbin/nft", "/sbin/nft", "/usr/bin/nft", "/bin/nft"} {
		if info, err := os.Stat(candidate); err == nil && !info.IsDir() && info.Mode()&0o111 != 0 {
			return candidate, nil
		}
	}
	return exec.LookPath("nft")
}

func execNFT(nftPath, stdin string, args ...string) ([]byte, string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), nftCommandTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, nftPath, args...)
	cmd.Env = nftEnv()
	if stdin != "" {
		cmd.Stdin = strings.NewReader(stdin)
	}
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	return stdout.Bytes(), stderr.String(), err
}

func isTransientNFTError(err error, stderr string) bool {
	if errors.Is(err, syscall.EBUSY) {
		return true
	}
	lower := strings.ToLower(stderr + " " + err.Error())
	return strings.Contains(lower, "ebusy") || strings.Contains(lower, "device or resource busy") || strings.Contains(lower, "resource busy")
}

func deleteNFTTable(nftPath, table string) error {
	for attempt := 0; ; attempt++ {
		_, stderr, err := execNFT(nftPath, "", "delete", "table", "inet", table)
		if err == nil {
			return nil
		}
		if !isTransientNFTError(err, stderr) || attempt >= nftDeleteMaxRetries {
			if msg := strings.TrimSpace(stderr); msg != "" {
				return fmt.Errorf("nft delete table inet %s: %s: %w", table, msg, err)
			}
			return fmt.Errorf("nft delete table inet %s: %w", table, err)
		}
		time.Sleep(nftRetryDelay)
	}
}

func buildRuleset(table string, ref TunRef, permitLAN bool, uid int) (string, error) {
	if ref.Name != "" && !ifaceNameRe.MatchString(ref.Name) {
		return "", errors.New("invalid tunnel interface")
	}
	var builder strings.Builder
	builder.WriteString(fmt.Sprintf(`table inet %s {
chain output { type filter hook output priority 0; policy drop;
	oifname "lo" accept
`, table))
	if ref.Name != "" {
		builder.WriteString(fmt.Sprintf("	oifname %q accept\n", ref.Name))
	}
	builder.WriteString(fmt.Sprintf(`	meta skuid %d accept
	udp sport 68 udp dport 67 accept
	udp sport 546 udp dport 547 accept
	icmpv6 type { nd-router-solicit, nd-router-advert, nd-neighbor-solicit, nd-neighbor-advert } accept
	udp dport { 53, 853, 5355 } drop
	tcp dport { 53, 853, 5355 } drop
`, uid))
	if permitLAN {
		builder.WriteString("	" + lanPermitV4 + "\n	" + lanPermitV6 + "\n")
	}
	builder.WriteString(`}
chain input { type filter hook input priority 0; policy drop;
	iifname "lo" accept
	ct state established,related accept
	icmpv6 type { nd-router-solicit, nd-router-advert, nd-neighbor-solicit, nd-neighbor-advert } accept
	udp sport 67 udp dport 68 accept
	udp sport 547 udp dport 546 accept
`)
	if permitLAN {
		builder.WriteString("	" + strings.Replace(lanPermitV4, "daddr", "saddr", 1) + "\n	" + strings.Replace(lanPermitV6, "daddr", "saddr", 1) + "\n")
	}
	builder.WriteString(`}
chain forward { type filter hook forward priority 0; policy drop;
	ct state invalid drop
`)
	if ref.Name != "" {
		builder.WriteString(fmt.Sprintf("	oifname %q accept\n", ref.Name))
		builder.WriteString(fmt.Sprintf("	iifname %q ct state established,related accept\n", ref.Name))
	}
	builder.WriteString("	udp dport { 53, 853, 5355 } drop\n")
	builder.WriteString("	tcp dport { 53, 853, 5355 } drop\n")
	if permitLAN {
		builder.WriteString("	" + lanPermitV4 + "\n	" + lanPermitV6 + "\n")
	}
	builder.WriteString("}\n}\n")
	return builder.String(), nil
}

// Enable installs a kill switch under its own numbered table, so several can be alive at once —
// the supervisor puts the new one in before it takes the old one out, and a shared name would let
// the teardown of the first delete the second's rules.
func Enable(ref TunRef, permitLAN bool) (Firewall, error) {
	nftPath, err := findNFTPath()
	if err != nil {
		return nil, errors.New("nft not found: " + err.Error())
	}
	table := fmt.Sprintf("yumi_shield_%d_%d", os.Getpid(), atomic.AddUint64(&shieldCounter, 1))
	ruleset, err := buildRuleset(table, ref, permitLAN, os.Getuid())
	if err != nil {
		return nil, err
	}
	stdout, stderr, err := execNFT(nftPath, ruleset, "-f", "-")
	if err != nil {
		msg := strings.TrimSpace(stderr)
		if msg == "" {
			msg = strings.TrimSpace(string(stdout))
		}
		if msg == "" {
			msg = err.Error()
		}
		return nil, fmt.Errorf("nft: %s", msg)
	}
	return &nftShield{nftPath: nftPath, table: table}, nil
}

// Close takes this shield's table out. Safe to call more than once.
func (f *nftShield) Close() {
	if f == nil || f.table == "" {
		return
	}
	nftPath := f.nftPath
	if nftPath == "" {
		var err error
		nftPath, err = findNFTPath()
		if err != nil {
			log.Printf("firewall: nft not found closing table %s: %v", f.table, err)
			f.table = ""
			return
		}
	}
	if err := deleteNFTTable(nftPath, f.table); err != nil {
		log.Printf("firewall: %v", err)
	}
	f.table = ""
}

/*
CleanupLeftovers removes every yumi_shield table a previous life of this daemon may have left
standing. Run once at daemon start, before anything else: at that moment no shield of ours is
supposed to exist, and a block nobody is holding is a machine with no network.
*/
func CleanupLeftovers() error {
	nftPath, err := findNFTPath()
	if err != nil {
		return nil
	}
	out, stderr, err := execNFT(nftPath, "", "list", "tables")
	if err != nil {
		if msg := strings.TrimSpace(stderr); msg != "" {
			return fmt.Errorf("nft list tables: %s: %w", msg, err)
		}
		return err
	}
	for _, line := range strings.Split(string(out), "\n") {
		fields := strings.Fields(strings.TrimSpace(line))
		if len(fields) == 3 && fields[0] == "table" && fields[1] == "inet" && shieldTableRe.MatchString(fields[2]) {
			if err := deleteNFTTable(nftPath, fields[2]); err != nil {
				return err
			}
		}
	}
	return nil
}
