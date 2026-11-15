//go:build darwin

package firewall

import (
	"errors"
	"fmt"
	"os/exec"
	"strings"
	"sync"
)

/*
The macOS kill switch: a pf anchor holding the block-everything rules, with one line in the main
ruleset referencing the anchor so the rules are actually evaluated.

The reference-counting is not decoration. The supervisor keeps a new shield alive before retiring
the old one, so for an instant two exist; a naive "restore the main ruleset on close" would let
the first one's teardown pull the anchor line out from under the second. The first Enable adds the
line, the last Close takes it back, and the original main ruleset — captured once, before we
touched anything — goes home with it.

Like its Linux cousin this is per-account where Windows is per-executable: pf matches owners, the
daemon is root, so root processes ride free alongside it. And a crash leaves the anchor and its
reference line standing, which [CleanupLeftovers] unwinds at the next daemon start.
*/

const anchorName = "com.yumi.shield"
const lanPermitV4 = "to { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16 }"
const lanPermitV6 = "inet6 to { fc00::/7, fe80::/10 }"

var shieldState = struct {
	sync.Mutex
	active int
	enabledPf bool
	originalRules string
}{}

type pfShield struct{}

func pfctl(args ...string) (string, error) {
	cmd := exec.Command("pfctl", args...)
	out, err := cmd.CombinedOutput()
	return string(out), err
}

func Enable(ref TunRef, permitLAN bool) (Firewall, error) {
	if _, err := exec.LookPath("pfctl"); err != nil {
		return nil, errors.New("pfctl not found: " + err.Error())
	}
	shieldState.Lock()
	defer shieldState.Unlock()
	if shieldState.active == 0 {
		status, _ := pfctl("-si")
		if strings.Contains(status, "Status: Disabled") {
			if _, err := pfctl("-e"); err != nil {
				return nil, fmt.Errorf("pf enable: %w", err)
			}
			shieldState.enabledPf = true
		}
		rules, _ := pfctl("-sr")
		shieldState.originalRules = rules
		if !strings.Contains(rules, anchorName) {
			cmd := exec.Command("pfctl", "-f", "-")
			cmd.Stdin = strings.NewReader(rules + "\nanchor \"" + anchorName + "\"\n")
			if out, err := cmd.CombinedOutput(); err != nil {
				return nil, fmt.Errorf("pf anchor reference: %s", strings.TrimSpace(string(out)))
			}
		}
	}
	shieldState.active++

	var builder strings.Builder
	builder.WriteString("block drop out quick all\n")
	if ref.Name != "" {
		builder.WriteString(fmt.Sprintf("pass out quick on %s all\n", ref.Name))
	}
	builder.WriteString("pass out quick proto udp to any port 67\n")
	builder.WriteString("pass out quick user root\n")
	builder.WriteString("pass out quick inet6 proto ipv6-icmp all\n")
	builder.WriteString("pass in quick on lo0 all\n")
	builder.WriteString("block drop in quick all\n")
	builder.WriteString("pass in quick proto udp from any port 67 to any port 68\n")
	builder.WriteString("pass in quick inet6 proto ipv6-icmp all\n")
	if permitLAN {
		builder.WriteString("pass out quick " + lanPermitV4 + "\n")
		builder.WriteString("pass out quick " + lanPermitV6 + "\n")
	}
	cmd := exec.Command("pfctl", "-a", anchorName, "-f", "-")
	cmd.Stdin = strings.NewReader(builder.String())
	if out, err := cmd.CombinedOutput(); err != nil {
		shieldState.active--
		return nil, fmt.Errorf("pf anchor: %s", strings.TrimSpace(string(out)))
	}
	return &pfShield{}, nil
}

// Close empties this shield's anchor and, when it was the last one, restores the main ruleset —
// and pf itself, if we were the ones who turned it on. Safe to call more than once.
func (f *pfShield) Close() {
	shieldState.Lock()
	defer shieldState.Unlock()
	if shieldState.active == 0 {
		return
	}
	shieldState.active--
	_, _ = pfctl("-a", anchorName, "-F", "all")
	if shieldState.active == 0 {
		cmd := exec.Command("pfctl", "-f", "-")
		cmd.Stdin = strings.NewReader(shieldState.originalRules)
		_, _ = cmd.CombinedOutput()
		if shieldState.enabledPf {
			_, _ = pfctl("-d")
			shieldState.enabledPf = false
		}
		shieldState.originalRules = ""
	}
}

/*
CleanupLeftovers undoes what a dead daemon left behind: the anchor's rules and the reference line
in the main ruleset. Run once at daemon start, before anything else — at that moment no shield of
ours is supposed to exist, and a block nobody is holding is a machine with no network.
*/
func CleanupLeftovers() {
	_, _ = pfctl("-a", anchorName, "-F", "all")
	rules, err := pfctl("-sr")
	if err != nil || !strings.Contains(rules, anchorName) {
		return
	}
	var kept []string
	for _, line := range strings.Split(rules, "\n") {
		if !strings.Contains(line, anchorName) {
			kept = append(kept, line)
		}
	}
	cmd := exec.Command("pfctl", "-f", "-")
	cmd.Stdin = strings.NewReader(strings.Join(kept, "\n") + "\n")
	_, _ = cmd.CombinedOutput()
}
