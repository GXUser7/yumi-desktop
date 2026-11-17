//go:build linux

package main

func applySystemDNS() error {
	m := systemDNSManager()
	if _, err := m.run("resolvectl", "status"); err == nil {
		return m.applyResolved(tunAdapterName())
	}
	return m.applyFile()
}
