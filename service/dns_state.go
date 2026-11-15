package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
)

type dnsServiceState struct {
	Name    string   `json:"name"`
	Servers []string `json:"servers"`
}
type dnsState struct {
	Mode      string            `json:"mode"`
	Interface string            `json:"interface,omitempty"`
	Original  []byte            `json:"original,omitempty"`
	Services  []dnsServiceState `json:"services,omitempty"`
}
type dnsManager struct {
	directory       string
	resolvPath      string
	run             commandRunner
	interfaceExists func(string) bool
}

func (m dnsManager) path() string            { return filepath.Join(m.directory, "dns-state.json") }
func (m dnsManager) save(st *dnsState) error { return writeStateFile(m.path(), st) }
func (m dnsManager) load() (*dnsState, error) {
	data, err := os.ReadFile(m.path())
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var st dnsState
	if err := json.Unmarshal(data, &st); err != nil {
		return nil, err
	}
	return &st, nil
}
func (m dnsManager) applyMac() (err error) {
	if err = m.restore(); err != nil {
		return err
	}
	text, err := m.run("networksetup", "-listallnetworkservices")
	if err != nil {
		return err
	}
	st := &dnsState{Mode: "macos"}
	for index, line := range strings.Split(text, "\n") {
		name := strings.TrimSpace(line)
		if index == 0 || name == "" || strings.HasPrefix(name, "*") {
			continue
		}
		answer, e := m.run("networksetup", "-getdnsservers", name)
		if e != nil {
			return e
		}
		servers, e := parseMacDNSServers(answer)
		if e != nil {
			return fmt.Errorf("DNS for %s: %w", name, e)
		}
		st.Services = append(st.Services, dnsServiceState{Name: name, Servers: servers})
	}
	if len(st.Services) == 0 {
		return errors.New("no active network services")
	}
	if err = m.save(st); err != nil {
		return err
	}
	defer func() {
		if err != nil {
			err = errors.Join(err, m.restore())
		}
	}()
	for _, service := range st.Services {
		if _, err = m.run("networksetup", "-setdnsservers", service.Name, tunDNSV4); err != nil {
			return err
		}
	}
	return nil
}
func parseMacDNSServers(text string) ([]string, error) {
	text = strings.TrimSpace(text)
	if text == "" || strings.HasPrefix(text, "There aren't any DNS Servers set on ") {
		return nil, nil
	}
	servers := strings.Fields(text)
	for _, server := range servers {
		if net.ParseIP(server) == nil {
			return nil, fmt.Errorf("unexpected DNS response %q", text)
		}
	}
	return servers, nil
}
func (m dnsManager) applyResolved(tun string) (err error) {
	if err = m.restore(); err != nil {
		return err
	}
	if err = m.save(&dnsState{Mode: "resolved", Interface: tun}); err != nil {
		return err
	}
	defer func() {
		if err != nil {
			err = errors.Join(err, m.restore())
		}
	}()
	for _, args := range [][]string{{"dns", tun, tunDNSV4}, {"domain", tun, "~."}, {"default-route", tun, "true"}} {
		if _, err = m.run("resolvectl", args...); err != nil {
			return err
		}
	}
	return nil
}
func (m dnsManager) applyFile() (err error) {
	if err = m.restore(); err != nil {
		return err
	}
	data, err := os.ReadFile(m.resolvPath)
	if err != nil {
		return err
	}
	if err = m.save(&dnsState{Mode: "file", Original: data}); err != nil {
		return err
	}
	if err = os.WriteFile(m.resolvPath, []byte("# managed by Yumi Desktop\nnameserver "+tunDNSV4+"\n"), 0o644); err != nil {
		return errors.Join(err, m.restore())
	}
	return nil
}

// Recovery is read from disk, including in a replacement supervisor after SIGKILL. Retain every
// failed restoration for another attempt; do not replay successful ones over later user changes.
func (m dnsManager) restore() error {
	st, err := m.load()
	if err != nil {
		return err
	}
	if st == nil {
		return m.restoreLegacyFile()
	}
	switch st.Mode {
	case "macos":
		var failed []dnsServiceState
		var result error
		for _, service := range st.Services {
			args := []string{"-setdnsservers", service.Name}
			if len(service.Servers) == 0 {
				args = append(args, "Empty")
			} else {
				args = append(args, service.Servers...)
			}
			if _, e := m.run("networksetup", args...); e != nil {
				failed = append(failed, service)
				result = errors.Join(result, e)
			}
		}
		if result != nil {
			st.Services = failed
			return errors.Join(result, m.save(st))
		}
	case "resolved":
		if _, err := m.run("resolvectl", "revert", st.Interface); err != nil {
			if m.interfaceExists == nil || m.interfaceExists(st.Interface) {
				return err
			}
		}
	case "file":
		if err := os.WriteFile(m.resolvPath, st.Original, 0o644); err != nil {
			return err
		}
	default:
		return fmt.Errorf("unknown saved DNS mode %q", st.Mode)
	}
	return os.Remove(m.path())
}
func (m dnsManager) restoreLegacyFile() error {
	backup := filepath.Join(m.directory, "resolv.conf.yumi")
	data, err := os.ReadFile(backup)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	if err := os.WriteFile(m.resolvPath, data, 0o644); err != nil {
		return err
	}
	return os.Remove(backup)
}
