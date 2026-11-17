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
	second, err := c.enable(TunRef{Name: "utun70"}, true)
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
	const blockNameRule = "block drop out quick proto { tcp, udp } from any to any port { 53, 853, 5355 }"
	idxBlockName := strings.Index(rules, blockNameRule)
	if idxBlockName < 0 {
		t.Fatalf("missing DNS/DoT/LLMNR block rule in:\n%s", rules)
	}
	for _, before := range []string{"pass quick on lo0 all", "pass quick on utun69 all", "pass out quick user root"} {
		if idx := strings.Index(rules, before); idx < 0 || idx >= idxBlockName {
			t.Fatalf("rule %q must precede %q:\n%s", before, blockNameRule, rules)
		}
	}
	if idxLAN := strings.Index(rules, "pass out quick inet to"); idxLAN < 0 || idxBlockName >= idxLAN {
		t.Fatalf("DNS/DoT/LLMNR block rule must precede LAN pass rules:\n%s", rules)
	}
	without, _ := pfRules(TunRef{Name: "utun69"}, false)
	if strings.Contains(without, "192.168.0.0/16") {
		t.Fatal("LAN bypass ignored its setting")
	}
	if !strings.Contains(without, blockNameRule) {
		t.Fatal("DNS/DoT/LLMNR block rule missing when LAN bypass is off")
	}
	if _, err := pfRules(TunRef{Name: "utun69\npass all"}, true); err == nil {
		t.Fatal("accepted an injected interface")
	}
}

func TestPFDisablingLANBypassFlushesEstablishedStates(t *testing.T) {
	c, f := pfFixture(t)
	s1, err := c.enable(TunRef{Name: "utun69"}, false)
	if err != nil {
		t.Fatal(err)
	}
	s2, err := c.enable(TunRef{Name: "utun69"}, true) // false -> true: no flush
	if err != nil {
		t.Fatal(err)
	}
	s1.Close()
	if got := strings.Count(strings.Join(f.commands, "\n"), "-F states"); got != 1 {
		t.Fatalf("expected 1 state flush after false->true, got %d (%v)", got, f.commands)
	}
	s3, err := c.enable(TunRef{Name: "utun69"}, false) // true -> false: must flush
	if err != nil {
		t.Fatal(err)
	}
	s2.Close()
	if got := strings.Count(strings.Join(f.commands, "\n"), "-F states"); got != 2 {
		t.Fatalf("expected 2 state flushes after true->false, got %d (%v)", got, f.commands)
	}
	s4, err := c.enable(TunRef{Name: "utun70"}, false) // false -> false: no flush
	if err != nil {
		t.Fatal(err)
	}
	s3.Close()
	if got := strings.Count(strings.Join(f.commands, "\n"), "-F states"); got != 2 {
		t.Fatalf("expected 2 state flushes after false->false, got %d (%v)", got, f.commands)
	}
	s4.Close()
}
