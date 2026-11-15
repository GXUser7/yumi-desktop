//go:build linux

package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

/*
The system resolver, into the tunnel.

systemd-resolved, which runs the resolver on the distributions people actually boot, is told that
the tunnel interface answers for names nothing else claimed — `resolvectl default-route` is the
resolved way of saying "use this for everything else", which is exactly how the Windows build
behaves with the adapter's DNS setting. Where there is no resolved, the classic file takes over:
a copy of /etc/resolv.conf is kept beside the journal, the file itself is reduced to the tunnel's
resolver, and the copy goes back on the way out. Both are best effort and the caller logs the
refusal; a resolver that stays with the machine resolves around the tunnel, and nothing breaks.
*/
func applySystemDNS() error {
	tun := tunAdapterName()
	if _, err := exec.LookPath("resolvectl"); err == nil {
		if out, err := exec.Command("resolvectl", "dns", tun, tunDNSV4).CombinedOutput(); err != nil {
			return fmt.Errorf("resolvectl dns: %s", strings.TrimSpace(string(out)))
		}
		if out, err := exec.Command("resolvectl", "default-route", tun, "true").CombinedOutput(); err != nil {
			return fmt.Errorf("resolvectl default-route: %s", strings.TrimSpace(string(out)))
		}
		return nil
	}
	path := "/etc/resolv.conf"
	if backup := dnsBackupPath(); backup != "" {
		if _, err := os.Stat(backup); err != nil {
			data, err := os.ReadFile(path)
			if err != nil {
				return err
			}
			if err := os.WriteFile(backup, data, 0o644); err != nil {
				return err
			}
		}
	}
	return os.WriteFile(path, []byte("# managed by Yumi Desktop while the tunnel is up\nnameserver "+tunDNSV4+"\n"), 0o644)
}

func revertSystemDNS() {
	tun := tunAdapterName()
	if _, err := exec.LookPath("resolvectl"); err == nil {
		_ = exec.Command("resolvectl", "default-route", tun, "false").Run()
		return
	}
	if backup := dnsBackupPath(); backup != "" {
		if data, err := os.ReadFile(backup); err == nil {
			_ = os.WriteFile("/etc/resolv.conf", data, 0o644)
			_ = os.Remove(backup)
		}
	}
}

func dnsBackupPath() string {
	dir := systemStateDirectory()
	if dir == "" {
		return ""
	}
	_ = os.MkdirAll(dir, 0o755)
	return filepath.Join(dir, "resolv.conf.yumi")
}
