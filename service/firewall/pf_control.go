package firewall

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
)

const yumiPFAnchor = "com.apple/yumi"

type firewallCommand func(string, ...string) (string, error)
type pfSpec struct {
	ref TunRef
	lan bool
}
type pfRecovery struct {
	Token  string `json:"token"`
	BootID string `json:"bootId"`
}
type pfController struct {
	sync.Mutex
	run     firewallCommand
	path    string
	next    uint64
	active  map[uint64]pfSpec
	prevLAN bool
	token   string
	bootID  string
}
type pfShield struct {
	controller *pfController
	id         uint64
}

func pfRules(ref TunRef, lan bool) (string, error) {
	if ref.Name != "" && !regexp.MustCompile(`^[A-Za-z0-9_.:-]{1,32}$`).MatchString(ref.Name) {
		return "", errors.New("invalid tunnel interface")
	}
	var b strings.Builder
	b.WriteString("pass quick on lo0 all\n")
	if ref.Name != "" {
		fmt.Fprintf(&b, "pass quick on %s all\n", ref.Name)
	}
	b.WriteString("pass out quick user root\n")
	b.WriteString("pass out quick inet proto udp from any port 68 to any port 67\n")
	b.WriteString("pass in quick inet proto udp from any port 67 to any port 68\n")
	b.WriteString("pass out quick inet6 proto udp from any port 546 to any port 547\n")
	b.WriteString("pass in quick inet6 proto udp from any port 547 to any port 546\n")
	b.WriteString("pass quick inet6 proto ipv6-icmp icmp6-type { routersol, routeradv, neighbrsol, neighbradv }\n")
	b.WriteString("block drop out quick proto { tcp, udp } from any to any port { 53, 853, 5355 }\n")
	if lan {
		b.WriteString("pass out quick inet to { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16 }\n")
		b.WriteString("pass in quick inet from { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16 }\n")
		b.WriteString("pass out quick inet6 to { fc00::/7, fe80::/10 }\npass in quick inet6 from { fc00::/7, fe80::/10 }\n")
	}
	b.WriteString("block drop quick all\n")
	return b.String(), nil
}

func (c *pfController) save() error {
	if err := os.MkdirAll(filepath.Dir(c.path), 0o755); err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(c.path), ".pf-recovery-*")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	err = json.NewEncoder(f).Encode(pfRecovery{Token: c.token, BootID: c.bootID})
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(f.Name(), c.path)
}

func (c *pfController) enable(ref TunRef, lan bool) (Firewall, error) {
	rules, err := pfRules(ref, lan)
	if err != nil {
		return nil, err
	}
	c.Lock()
	defer c.Unlock()
	first := len(c.active) == 0
	prevLAN := !first && c.prevLAN
	if first {
		root, err := c.run("", "-sr")
		if err != nil {
			return nil, err
		}
		if !regexp.MustCompile(`(?m)^anchor "com\.apple/\*"(?:\s|$)`).MatchString(root) {
			return nil, errors.New("PF ruleset has no com.apple/* anchor; refusing to replace system firewall rules")
		}
		// Persist before activation, and persist the enable token before loading blocking rules.
		if err := c.save(); err != nil {
			return nil, err
		}
		out, err := c.run("", "-E")
		if err != nil {
			return nil, err
		}
		token := regexp.MustCompile(`Token\s*:\s*([0-9]+)`).FindStringSubmatch(out)
		if len(token) != 2 {
			return nil, errors.New("pfctl did not return an enable token")
		}
		c.token = token[1]
		if err := c.save(); err != nil {
			_, _ = c.run("", "-X", c.token)
			c.token = ""
			return nil, err
		}
	}
	if _, err = c.run(rules, "-a", yumiPFAnchor, "-f", "-"); err != nil {
		if first {
			_ = c.cleanupLocked()
		}
		return nil, err
	}
	if first || (prevLAN && !lan) {
		// PF consults existing states before rules. Close pre-VPN direct states once, after the
		// shield is in place, and flush established LAN states when BypassLAN is turned off;
		// ordinary replacements preserve the current tunnel's established flows.
		if _, err = c.run("", "-F", "states"); err != nil {
			if first {
				_ = c.cleanupLocked()
			} else if prev, ok := c.latestActiveLocked(); ok {
				if prevRules, rerr := pfRules(prev.ref, prev.lan); rerr == nil {
					_, _ = c.run(prevRules, "-a", yumiPFAnchor, "-f", "-")
				}
			}
			return nil, err
		}
	}
	if c.active == nil {
		c.active = map[uint64]pfSpec{}
	}
	c.next++
	c.active[c.next] = pfSpec{ref: ref, lan: lan}
	c.prevLAN = lan
	return &pfShield{controller: c, id: c.next}, nil
}

func (c *pfController) latestActiveLocked() (pfSpec, bool) {
	if len(c.active) == 0 {
		return pfSpec{}, false
	}
	var maxID uint64
	for id := range c.active {
		if id > maxID {
			maxID = id
		}
	}
	return c.active[maxID], true
}

func (s *pfShield) Close() {
	if s == nil || s.controller == nil {
		return
	}
	c := s.controller
	c.Lock()
	defer c.Unlock()
	if _, exists := c.active[s.id]; !exists {
		return
	}
	ids := make([]uint64, 0, len(c.active))
	for id := range c.active {
		ids = append(ids, id)
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })
	if ids[len(ids)-1] != s.id {
		delete(c.active, s.id)
		return
	}
	if len(ids) > 1 {
		previous := c.active[ids[len(ids)-2]]
		rules, _ := pfRules(previous.ref, previous.lan)
		if _, err := c.run(rules, "-a", yumiPFAnchor, "-f", "-"); err != nil {
			return
		}
		delete(c.active, s.id)
		c.prevLAN = previous.lan
		return
	}
	if c.cleanupLocked() == nil {
		delete(c.active, s.id)
	}
}

func (c *pfController) cleanupLocked() error {
	if _, err := c.run("", "-a", yumiPFAnchor, "-F", "rules"); err != nil {
		return err
	}
	if c.token != "" {
		if _, err := c.run("", "-X", c.token); err != nil {
			return err
		}
		c.token = ""
	}
	c.prevLAN = false
	if err := os.Remove(c.path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

func (c *pfController) recover() error {
	c.Lock()
	defer c.Unlock()
	data, err := os.ReadFile(c.path)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err == nil {
		var st pfRecovery
		if err := json.Unmarshal(data, &st); err != nil {
			return err
		}
		if st.Token != "" && !regexp.MustCompile(`^[0-9]+$`).MatchString(st.Token) {
			return errors.New("invalid saved PF token")
		}
		if st.BootID == c.bootID {
			c.token = st.Token
		}
	}
	// Empty the legacy 1.0.x anchor without rewriting the root ruleset or disabling another
	// application's PF session. A leftover empty anchor reference is harmless.
	if _, err := c.run("", "-a", "com.yumi.shield", "-F", "rules"); err != nil {
		return err
	}
	return c.cleanupLocked()
}
