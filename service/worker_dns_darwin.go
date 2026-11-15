//go:build darwin

package main

func applySystemDNS() error { return systemDNSManager().applyMac() }
