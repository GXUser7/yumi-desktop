//go:build darwin

package main

import (
	"fmt"
	"os/exec"
	"strings"
)

/*
The system resolver, into the tunnel.

macOS keeps DNS per network service, so every service that exists is pointed at the tunnel's
resolver for as long as the worker lives, and each one's previous answer — "Empty" is a legal
state — is remembered for the way out. Best effort throughout and the caller logs the refusal:
a resolver that stays with the machine resolves around the tunnel, and nothing breaks.
*/
type dnsServiceBackup struct {
	service string
	empty   bool
	servers []string
}

var dnsBackups []dnsServiceBackup

func applySystemDNS() error {
	out, err := exec.Command("networksetup", "-listallnetworkservices").Output()
	if err != nil {
		return err
	}
	var services []string
	for i, line := range strings.Split(string(out), "\n") {
		// The first line is a header; an asterisk marks a disabled service, which takes no part.
		if i == 0 || line == "" || strings.HasPrefix(line, "*") {
			continue
		}
		services = append(services, strings.TrimSpace(line))
	}
	for _, service := range services {
		current, err := exec.Command("networksetup", "-getdnsservers", service).Output()
		backup := dnsServiceBackup{service: service, empty: true}
		if err == nil {
			for _, server := range strings.Fields(string(current)) {
				backup.servers = append(backup.servers, server)
			}
			if len(backup.servers) > 0 {
				backup.empty = false
			}
		}
		dnsBackups = append(dnsBackups, backup)
		if out, err := exec.Command("networksetup", "-setdnsservers", service, tunDNSV4).CombinedOutput(); err != nil {
			return fmt.Errorf("%s: %s", service, strings.TrimSpace(string(out)))
		}
	}
	return nil
}

func revertSystemDNS() {
	for _, backup := range dnsBackups {
		args := []string{"-setdnsservers", backup.service}
		if backup.empty || len(backup.servers) == 0 {
			args = append(args, "Empty")
		} else {
			args = append(args, backup.servers...)
		}
		_ = exec.Command("networksetup", args...).Run()
	}
	dnsBackups = nil
}
