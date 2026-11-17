//go:build linux

package firewall

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func nftFixture(t *testing.T) (string, string) {
	t.Helper()
	dir := t.TempDir()
	rules := filepath.Join(dir, "rules")
	commands := filepath.Join(dir, "commands")
	t.Setenv("YUMI_NFT_RULES", rules)
	t.Setenv("YUMI_NFT_COMMANDS", commands)
	script := `#!/bin/sh
if [ "$1 $2" = "list tables" ]; then
 printf '%s\n' 'table inet yumi_shield_1' 'table inet yumi_shield_2_3' 'table inet yumi_shield_foreign' 'table inet another_firewall'
else
 printf '%s\n' "$*" >> "$YUMI_NFT_COMMANDS"
 if [ "$1" = "-f" ]; then cat > "$YUMI_NFT_RULES"; fi
fi
`
	nftBin := filepath.Join(dir, "nft")
	if err := os.WriteFile(nftBin, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	origFind := findNFTPath
	origEnv := nftEnv
	findNFTPath = func() (string, error) { return nftBin, nil }
	nftEnv = os.Environ
	t.Cleanup(func() {
		findNFTPath = origFind
		nftEnv = origEnv
	})
	t.Setenv("PATH", dir+string(os.PathListSeparator)+os.Getenv("PATH"))
	return rules, commands
}

func TestNFTNeverPermitsEstablishedDirectOutputAndCleansOnlyOurTables(t *testing.T) {
	rulesPath, commandsPath := nftFixture(t)
	shield, err := Enable(TunRef{Name: "Yumi"}, false)
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(rulesPath)
	if err != nil {
		t.Fatal(err)
	}
	rules := string(data)
	output := strings.Split(rules, "chain input")[0]
	if strings.Contains(output, "established") || !strings.Contains(output, `oifname "Yumi" accept`) {
		t.Fatal("allowed an old direct socket around the tunnel")
	}
	if strings.Contains(output, "192.168.0.0/16") || !strings.Contains(output, "udp sport 546 udp dport 547") {
		t.Fatal("incorrect LAN or DHCPv6 exceptions")
	}
	shield.Close()
	shield.Close()
	if err := CleanupLeftovers(); err != nil {
		t.Fatal(err)
	}
	commands, _ := os.ReadFile(commandsPath)
	if strings.Contains(string(commands), "foreign") || strings.Contains(string(commands), "another_firewall") {
		t.Fatal("deleted someone else's firewall")
	}
	if !strings.Contains(string(commands), "delete table inet yumi_shield_1") || !strings.Contains(string(commands), "delete table inet yumi_shield_2_3") {
		t.Fatal("left an abandoned table behind")
	}
}

// The CI-only check uses a fresh network namespace. No rules are installed in the runner's
// namespace, and this test is skipped in an ordinary developer/user session.
func TestNFTRulesParseInAnIsolatedNetworkNamespace(t *testing.T) {
	if os.Getenv("YUMI_NETWORK_TESTS") != "1" {
		t.Skip("requires an isolated privileged CI namespace")
	}
	actual, err := exec.LookPath("nft")
	if err != nil {
		t.Fatal(err)
	}
	rulesPath, _ := nftFixture(t)
	for _, lan := range []bool{false, true} {
		shield, err := Enable(TunRef{Name: "Yumi"}, lan)
		if err != nil {
			t.Fatal(err)
		}
		if out, err := exec.Command("unshare", "--net", actual, "--check", "-f", rulesPath).CombinedOutput(); err != nil {
			t.Fatalf("nft syntax: %s %v", out, err)
		}
		shield.Close()
	}
}

func TestNFTRulesBlockDNSDoTLLMNRBeforeLANAndEnforceForwardChain(t *testing.T) {
	rules, err := buildRuleset("yumi_shield_10_1", TunRef{Name: "tun0"}, true, 0)
	if err != nil {
		t.Fatal(err)
	}
	parts := strings.Split(rules, "chain input")
	if len(parts) != 2 {
		t.Fatalf("missing input chain: %s", rules)
	}
	output := parts[0]
	inAndFwd := strings.Split(parts[1], "chain forward")
	if len(inAndFwd) != 2 {
		t.Fatalf("missing forward chain: %s", rules)
	}
	forward := inAndFwd[1]

	for _, required := range []string{`oifname "lo" accept`, `oifname "tun0" accept`, "meta skuid 0 accept"} {
		if idxReq, idxDrop := strings.Index(output, required), strings.Index(output, "udp dport { 53, 853, 5355 } drop"); idxReq < 0 || idxDrop < 0 || idxReq >= idxDrop {
			t.Fatalf("output must permit %q before port 53/853/5355 drop:\n%s", required, output)
		}
	}
	for _, drop := range []string{"udp dport { 53, 853, 5355 } drop", "tcp dport { 53, 853, 5355 } drop"} {
		idxDrop := strings.Index(output, drop)
		idxLAN := strings.Index(output, lanPermitV4)
		if idxDrop < 0 || idxLAN < 0 || idxDrop >= idxLAN {
			t.Fatalf("output must drop %q before LAN permit:\n%s", drop, output)
		}
	}

	if !strings.Contains(forward, "type filter hook forward priority 0; policy drop;") || !strings.Contains(forward, "ct state invalid drop") {
		t.Fatalf("forward chain missing drop policy or invalid drop:\n%s", forward)
	}
	for _, tunRule := range []string{`oifname "tun0" accept`, `iifname "tun0" ct state established,related accept`} {
		idxTun := strings.Index(forward, tunRule)
		idxDrop := strings.Index(forward, "udp dport { 53, 853, 5355 } drop")
		if idxTun < 0 || idxDrop < 0 || idxTun >= idxDrop {
			t.Fatalf("forward must permit %q before port 53/853/5355 drop:\n%s", tunRule, forward)
		}
	}
	if idxTCP, idxLAN := strings.Index(forward, "tcp dport { 53, 853, 5355 } drop"), strings.Index(forward, lanPermitV4); idxTCP < 0 || idxLAN < 0 || idxTCP >= idxLAN {
		t.Fatalf("forward must drop 53/853/5355 before LAN permit:\n%s", forward)
	}

	crashRules, err := buildRuleset("yumi_shield_10_2", TunRef{}, true, 0)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(crashRules, `oifname ""`) || strings.Contains(crashRules, `iifname ""`) {
		t.Fatalf("empty tunnel ref emitted empty interface match:\n%s", crashRules)
	}
	noLAN, err := buildRuleset("yumi_shield_10_3", TunRef{Name: "tun0"}, false, 0)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(noLAN, "192.168.0.0/16") || strings.Contains(noLAN, "fc00::/7") {
		t.Fatalf("permitLAN=false leaked LAN permits:\n%s", noLAN)
	}
	if _, err := buildRuleset("yumi_shield_10_4", TunRef{Name: "tun0\naccept"}, false, 0); err == nil {
		t.Fatal("accepted injected interface name")
	}
}

func TestNFTDeleteTableRetriesOnEBUSYAndUsesSanitizedDefaultEnv(t *testing.T) {
	defaultEnv := strings.Join(nftEnv(), "\n")
	if !strings.Contains(defaultEnv, "PATH=/usr/sbin:/usr/bin:/sbin:/bin") {
		t.Fatalf("default nft env missing sanitized PATH: %q", defaultEnv)
	}

	dir := t.TempDir()
	attemptsFile := filepath.Join(dir, "attempts")
	nftBin := filepath.Join(dir, "nft")
	script := fmt.Sprintf(`#!/bin/sh
n=0
if [ -f %q ]; then n=$(cat %q); fi
n=$((n + 1))
printf '%%s' "$n" > %q
if [ "$1" = "delete" ] && [ "$n" -le 2 ]; then
  echo "Error: Could not process rule: Device or resource busy" >&2
  exit 1
fi
exit 0
`, attemptsFile, attemptsFile, attemptsFile)
	if err := os.WriteFile(nftBin, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}

	origDelay := nftRetryDelay
	origEnv := nftEnv
	nftRetryDelay = time.Millisecond
	nftEnv = os.Environ
	t.Cleanup(func() {
		nftRetryDelay = origDelay
		nftEnv = origEnv
	})

	shield := &nftShield{nftPath: nftBin, table: "yumi_shield_99_1"}
	shield.Close()
	data, err := os.ReadFile(attemptsFile)
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(string(data)) != "3" {
		t.Fatalf("expected 3 attempts (2 EBUSY retries + 1 success), got %q", string(data))
	}

	if err := os.WriteFile(attemptsFile, []byte("0"), 0o644); err != nil {
		t.Fatal(err)
	}
	alwaysBusy := `#!/bin/sh
n=$(cat "$YUMI_ATTEMPTS")
n=$((n + 1))
printf '%s' "$n" > "$YUMI_ATTEMPTS"
echo "Device or resource busy" >&2
exit 1
`
	if err := os.WriteFile(nftBin, []byte(alwaysBusy), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("YUMI_ATTEMPTS", attemptsFile)
	if err := deleteNFTTable(nftBin, "yumi_shield_99_2"); err == nil {
		t.Fatal("expected error after exhausting retries")
	}
	data, _ = os.ReadFile(attemptsFile)
	if strings.TrimSpace(string(data)) != "4" {
		t.Fatalf("expected 4 total attempts (1 initial + 3 retries), got %q", string(data))
	}
}
