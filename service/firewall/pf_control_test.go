package firewall

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type fakePF struct {
	commands   []string
	rules      string
	root       string
	rejectLoad bool
}

func (f *fakePF) run(input string, args ...string) (string, error) {
	command := strings.Join(args, " ")
	f.commands = append(f.commands, command)
	switch command {
	case "-sr":
		return f.root, nil
	case "-E":
		return "pf enabled\nToken : 123456", nil
	case "-a com.apple/yumi -f -":
		if f.rejectLoad {
			return "", errors.New("invalid rules")
		}
		f.rules = input
	case "-a com.apple/yumi -F rules":
		f.rules = ""
	}
	return "", nil
}
func pfFixture(t *testing.T) (*pfController, *fakePF) {
	t.Helper()
	fake := &fakePF{root: `anchor "com.apple/*" all`}
	return &pfController{run: fake.run, path: filepath.Join(t.TempDir(), "pf-state.json")}, fake
}

func TestPFReplacementAndRepeatedCloseCannotRemoveTheActiveShield(t *testing.T) {
	c, f := pfFixture(t)
	first, err := c.enable(TunRef{Name: "utun69"}, true)
	if err != nil {
		t.Fatal(err)
	}
	second, err := c.enable(TunRef{Name: "utun70"}, false)
	if err != nil {
		t.Fatal(err)
	}
	before := len(f.commands)
	first.Close()
	first.Close()
	if len(f.commands) != before || !strings.Contains(f.rules, "on utun70") {
		t.Fatal("closing the old shield changed the replacement")
	}
	second.Close()
	second.Close()
	if f.rules != "" {
		t.Fatal("last close left a shield")
	}
	joined := strings.Join(f.commands, "\n")
	if strings.Count(joined, "-F states") != 1 || strings.Count(joined, "-E\n") != 1 || strings.Count(joined, "-X 123456") != 1 {
		t.Fatalf("wrong ownership of states or PF enable token: %s", joined)
	}
	if _, err := os.Stat(c.path); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("kept a completed recovery token")
	}
}

func TestPFCrashRecoveryUsesOnlyOurAnchorsAndOwnEnableToken(t *testing.T) {
	c, f := pfFixture(t)
	if _, err := c.enable(TunRef{Name: "utun69"}, true); err != nil {
		t.Fatal(err)
	}
	replacement := &pfController{run: f.run, path: c.path}
	if err := replacement.recover(); err != nil {
		t.Fatal(err)
	}
	if f.rules != "" {
		t.Fatal("crash recovery left the blocking anchor")
	}
	for _, cmd := range f.commands {
		if cmd == "-d" || cmd == "-f -" || cmd == "-F all" {
			t.Fatalf("touched someone else's PF rules or session: %s", cmd)
		}
	}
}

func TestPFFailedReplacementKeepsThePreviousRules(t *testing.T) {
	c, f := pfFixture(t)
	first, err := c.enable(TunRef{Name: "utun69"}, true)
	if err != nil {
		t.Fatal(err)
	}
	old := f.rules
	f.rejectLoad = true
	if _, err := c.enable(TunRef{Name: "utun70"}, false); err == nil {
		t.Fatal("accepted failed replacement")
	}
	if f.rules != old || len(c.active) != 1 {
		t.Fatal("failed replacement removed the old shield")
	}
	f.rejectLoad = false
	first.Close()
}

func TestPFRefusesAnUnhookedRulesetInsteadOfReplacingSystemRules(t *testing.T) {
	c, f := pfFixture(t)
	f.root = "pass all"
	if _, err := c.enable(TunRef{}, true); err == nil {
		t.Fatal("claimed protection with no root anchor")
	}
	if len(f.commands) != 1 {
		t.Fatalf("modified an unsupported ruleset: %v", f.commands)
	}
}

func TestPFRecoveryDoesNotReleaseAnEnableTokenFromAPreviousBoot(t *testing.T) {
	c, f := pfFixture(t)
	c.bootID = "previous-boot"
	if _, err := c.enable(TunRef{Name: "utun69"}, true); err != nil {
		t.Fatal(err)
	}
	f.commands = nil
	replacement := &pfController{run: f.run, path: c.path, bootID: "current-boot"}
	if err := replacement.recover(); err != nil {
		t.Fatal(err)
	}
	for _, command := range f.commands {
		if strings.HasPrefix(command, "-X ") {
			t.Fatal("released an unrelated PF token after reboot")
		}
	}
}

func TestPFRulesPermitTunnelLoopbackAndLANAheadOfTheFinalBlock(t *testing.T) {
	rules, err := pfRules(TunRef{Name: "utun69"}, true)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasSuffix(rules, "block drop quick all\n") {
		t.Fatal("default drop must follow all exceptions")
	}
	for _, value := range []string{"on lo0", "on utun69", "user root", "port 546", "port 547", "neighbrsol", "pass in quick inet from"} {
		if !strings.Contains(rules, value) {
			t.Fatalf("missing exception %s", value)
		}
	}
	without, _ := pfRules(TunRef{Name: "utun69"}, false)
	if strings.Contains(without, "192.168.0.0/16") {
		t.Fatal("LAN bypass ignored its setting")
	}
	if _, err := pfRules(TunRef{Name: "utun69\npass all"}, true); err == nil {
		t.Fatal("accepted an injected interface")
	}
}
