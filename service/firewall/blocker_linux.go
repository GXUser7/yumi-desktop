//go:build linux

package firewall

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"sync/atomic"
)

/*
The Linux kill switch: an nftables table with two hooked chains, output and input, both dropping
by default, permitting exactly what the Windows WFP version permits — this daemon's own account,
loopback, the tunnel's interface, DHCP, neighbour discovery, and optionally the local network.

Two honest differences from the Windows half. The permit is per-account rather than per-executable:
nftables matches owners, and the daemon is root, so every root process rides free alongside it —
user-level traffic, which is what the shield exists to hold, is still confined to the tunnel.
And there is no dynamic session: an nftables table outlives the process that made it, so a daemon
killed mid-shield leaves the block standing until the daemon is restarted, which the systemd unit
does immediately and [CleanupLeftovers] unwinds first thing.
*/

const lanPermitV4 = "ip daddr { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16 } accept"
const lanPermitV6 = "ip6 daddr { fc00::/7, fe80::/10 } accept"

var shieldCounter uint64

type nftShield struct {
	table string
}

// Enable installs a kill switch under its own numbered table, so several can be alive at once —
// the supervisor puts the new one in before it takes the old one out, and a shared name would let
// the teardown of the first delete the second's rules.
func Enable(ref TunRef, permitLAN bool) (Firewall, error) {
	if _, err := exec.LookPath("nft"); err != nil {
		return nil, errors.New("nft not found: " + err.Error())
	}
	table := fmt.Sprintf("yumi_shield_%d_%d", os.Getpid(), atomic.AddUint64(&shieldCounter, 1))
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
`, os.Getuid()))
	if permitLAN {
		builder.WriteString("	" + lanPermitV4 + "\n	" + lanPermitV6 + "\n")
	}
	builder.WriteString(fmt.Sprintf(`}
chain input { type filter hook input priority 0; policy drop;
	iifname "lo" accept
	ct state established,related accept
	icmpv6 type { nd-router-solicit, nd-router-advert, nd-neighbor-solicit, nd-neighbor-advert } accept
	udp sport 67 udp dport 68 accept
	udp sport 547 udp dport 546 accept
`))
	if permitLAN {
		builder.WriteString("	" + strings.Replace(lanPermitV4, "daddr", "saddr", 1) + "\n	" + strings.Replace(lanPermitV6, "daddr", "saddr", 1) + "\n")
	}
	builder.WriteString("}\n}\n")
	cmd := exec.Command("nft", "-f", "-")
	cmd.Stdin = strings.NewReader(builder.String())
	if out, err := cmd.CombinedOutput(); err != nil {
		return nil, fmt.Errorf("nft: %s", strings.TrimSpace(string(out)))
	}
	return &nftShield{table: table}, nil
}

// Close takes this shield's table out. Safe to call more than once.
func (f *nftShield) Close() {
	if f == nil || f.table == "" {
		return
	}
	_ = exec.Command("nft", "delete", "table", "inet", f.table).Run()
	f.table = ""
}

/*
CleanupLeftovers removes every yumi_shield table a previous life of this daemon may have left
standing. Run once at daemon start, before anything else: at that moment no shield of ours is
supposed to exist, and a block nobody is holding is a machine with no network.
*/
func CleanupLeftovers() error {
	if _, err := exec.LookPath("nft"); err != nil {
		return nil
	}
	out, err := exec.Command("nft", "list", "tables").Output()
	if err != nil {
		return err
	}
	for _, line := range strings.Split(string(out), "\n") {
		fields := strings.Fields(strings.TrimSpace(line))
		if len(fields) == 3 && fields[0] == "table" && fields[1] == "inet" && regexp.MustCompile(`^yumi_shield_[0-9]+(?:_[0-9]+)?$`).MatchString(fields[2]) {
			if err := exec.Command("nft", "delete", "table", "inet", fields[2]).Run(); err != nil {
				return err
			}
		}
	}
	return nil
}
