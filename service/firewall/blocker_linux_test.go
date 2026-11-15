//go:build linux

package firewall

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
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
	if err := os.WriteFile(filepath.Join(dir, "nft"), []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
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
