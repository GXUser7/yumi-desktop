//go:build linux

package main

import "os/exec"

func applySystemDNS() error {
	m := systemDNSManager()
	if _, err := exec.LookPath("resolvectl"); err == nil {
		if _, err := m.run("resolvectl", "status"); err == nil {
			return m.applyResolved(tunAdapterName())
		}
	}
	return m.applyFile()
}
