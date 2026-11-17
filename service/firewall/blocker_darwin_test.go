//go:build darwin

package firewall

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestPFRulesAcceptedBySystemParser(t *testing.T) {
	if os.Getenv("YUMI_PF_SYNTAX_CHECK") != "1" {
		t.Skip("requires a privileged CI syntax check")
	}
	for _, lan := range []bool{false, true} {
		rules, err := pfRules(TunRef{Name: "lo0"}, lan)
		if err != nil {
			t.Fatal(err)
		}
		path := filepath.Join(t.TempDir(), "yumi.pf")
		if err := os.WriteFile(path, []byte(rules), 0o600); err != nil {
			t.Fatal(err)
		}
		if out, err := exec.Command("/sbin/pfctl", "-n", "-a", yumiPFAnchor, "-f", path).CombinedOutput(); err != nil {
			t.Fatalf("PF syntax: %s %v", out, err)
		}
	}
}

func TestGetPlatformPFIsSingleton(t *testing.T) {
	first := getPlatformPF()
	second := getPlatformPF()
	if first == nil || first != second {
		t.Fatalf("getPlatformPF did not return a non-nil singleton: %p vs %p", first, second)
	}
}
