//go:build unix

package main

import "net"

func systemDNSManager() dnsManager {
	return dnsManager{directory: systemStateDirectory(), resolvPath: "/etc/resolv.conf", run: runSystemCommand,
		interfaceExists: func(name string) bool { _, err := net.InterfaceByName(name); return err == nil }}
}
func restoreWorkerNetwork() {
	if err := systemDNSManager().restore(); err != nil {
		journalLine("system DNS restore: " + detailOf(err))
	}
}
