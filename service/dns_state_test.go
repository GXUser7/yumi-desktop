package main

import (
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"
	"testing"
)

func dnsFixture(t *testing.T) dnsManager {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "resolv.conf")
	if err := os.WriteFile(path, []byte("nameserver 192.0.2.53\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	return dnsManager{directory: dir, resolvPath: path, interfaceExists: func(string) bool { return true }}
}

func TestMacDNSBackupHandlesDHCPAndSurvivesWorkerDeath(t *testing.T) {
	m := dnsFixture(t)
	var set []string
	m.run = func(name string, args ...string) (string, error) {
		switch args[0] {
		case "-listallnetworkservices":
			return "An asterisk denotes disabled services\nWi-Fi\nEthernet\n*Disabled", nil
		case "-getdnsservers":
			if args[1] == "Wi-Fi" {
				return "192.0.2.1\n2001:db8::53", nil
			}
			return "There aren't any DNS Servers set on Ethernet.", nil
		case "-setdnsservers":
			if _, err := os.Stat(m.path()); err != nil {
				t.Fatal("changed DNS before persisting its original state")
			}
			set = append(set, strings.Join(args[1:], " "))
			return "", nil
		}
		t.Fatalf("unexpected command %s %v", name, args)
		return "", nil
	}
	if err := m.applyMac(); err != nil {
		t.Fatal(err)
	}
	replacement := m // New process: only the durable file, no in-memory backup.
	if err := replacement.restore(); err != nil {
		t.Fatal(err)
	}
	want := []string{"Wi-Fi " + tunDNSV4, "Ethernet " + tunDNSV4, "Wi-Fi 192.0.2.1 2001:db8::53", "Ethernet Empty"}
	if !reflect.DeepEqual(set, want) {
		t.Fatalf("got %v want %v", set, want)
	}
	if err := replacement.restore(); err != nil {
		t.Fatal(err)
	}
	if len(set) != 4 {
		t.Fatal("replayed a completed restoration")
	}
}

func TestMacDNSPartialApplyRollsBackAndFailedRestoreStaysRetryable(t *testing.T) {
	m := dnsFixture(t)
	failRestore := true
	var restored []string
	m.run = func(_ string, args ...string) (string, error) {
		switch args[0] {
		case "-listallnetworkservices":
			return "Header\nWi-Fi\nEthernet", nil
		case "-getdnsservers":
			return "192.0.2.1", nil
		case "-setdnsservers":
			if args[2] == tunDNSV4 {
				if args[1] == "Ethernet" {
					return "", errors.New("apply refused")
				}
				return "", nil
			}
			if args[1] == "Wi-Fi" && failRestore {
				return "", errors.New("temporary restore refusal")
			}
			restored = append(restored, args[1])
			return "", nil
		}
		return "", errors.New("unexpected command")
	}
	if err := m.applyMac(); err == nil {
		t.Fatal("partial application succeeded")
	}
	st, err := m.load()
	if err != nil || st == nil || len(st.Services) != 1 || st.Services[0].Name != "Wi-Fi" {
		t.Fatalf("lost retry record: %+v %v", st, err)
	}
	failRestore = false
	if err := m.restore(); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(restored, []string{"Ethernet", "Wi-Fi"}) {
		t.Fatalf("replayed successful restores: %v", restored)
	}
}

func TestUnrecognizedMacDNSResponseDoesNotChangeAnyService(t *testing.T) {
	m := dnsFixture(t)
	m.run = func(_ string, args ...string) (string, error) {
		if args[0] == "-listallnetworkservices" {
			return "Header\nWi-Fi", nil
		}
		if args[0] == "-getdnsservers" {
			return "networksetup: permission denied", nil
		}
		t.Fatal("changed a service without knowing its DNS")
		return "", nil
	}
	if err := m.applyMac(); err == nil {
		t.Fatal("accepted an error sentence as DNS addresses")
	}
}

func TestResolvedUsesExclusiveRootRoutingDomainAndRevertsAllSettings(t *testing.T) {
	m := dnsFixture(t)
	var commands []string
	m.run = func(_ string, args ...string) (string, error) {
		commands = append(commands, strings.Join(args, " "))
		return "", nil
	}
	if err := m.applyResolved("Yumi"); err != nil {
		t.Fatal(err)
	}
	if err := m.restore(); err != nil {
		t.Fatal(err)
	}
	want := []string{"dns Yumi " + tunDNSV4, "domain Yumi ~.", "default-route Yumi true", "revert Yumi"}
	if !reflect.DeepEqual(commands, want) {
		t.Fatalf("got %v want %v", commands, want)
	}
}

func TestResolverFileAndLegacyBackupRecoverAfterCrash(t *testing.T) {
	m := dnsFixture(t)
	original, _ := os.ReadFile(m.resolvPath)
	if err := m.applyFile(); err != nil {
		t.Fatal(err)
	}
	if err := m.restore(); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(m.resolvPath)
	if !reflect.DeepEqual(got, original) {
		t.Fatal("lost original resolver file")
	}
	if err := os.WriteFile(filepath.Join(m.directory, "resolv.conf.yumi"), original, 0o600); err != nil {
		t.Fatal(err)
	}
	_ = os.WriteFile(m.resolvPath, []byte("broken"), 0o600)
	if err := m.restore(); err != nil {
		t.Fatal(err)
	}
	got, _ = os.ReadFile(m.resolvPath)
	if !reflect.DeepEqual(got, original) {
		t.Fatal("legacy backup was not restored")
	}
}

func TestDNSCannotApplyWithoutADurableBackup(t *testing.T) {
	m := dnsFixture(t)
	original, _ := os.ReadFile(m.resolvPath)
	blocker := filepath.Join(m.directory, "not-a-directory")
	_ = os.WriteFile(blocker, []byte("fixture"), 0o600)
	m.directory = blocker
	if err := m.applyFile(); err == nil {
		t.Fatal("applied without a recovery record")
	}
	got, _ := os.ReadFile(m.resolvPath)
	if !reflect.DeepEqual(got, original) {
		t.Fatal("changed DNS before backup")
	}
}

func TestParseMacDNSServersAcceptsScopedIPv6(t *testing.T) {
	servers, err := parseMacDNSServers("192.0.2.1\nfe80::1%en0\n2001:db8::53")
	if err != nil {
		t.Fatalf("rejected valid scoped IPv6 DNS: %v", err)
	}
	want := []string{"192.0.2.1", "fe80::1%en0", "2001:db8::53"}
	if !reflect.DeepEqual(servers, want) {
		t.Fatalf("got %v want %v", servers, want)
	}
}

func TestApplyFileAndRestorePreserveActiveAndDanglingSymlinks(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "systemd-stub.conf")
	if err := os.WriteFile(target, []byte("nameserver 127.0.0.53\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	resolv := filepath.Join(dir, "resolv.conf")
	if err := os.Symlink(target, resolv); err != nil {
		if runtime.GOOS == "windows" {
			t.Skipf("symlink creation unprivileged on Windows: %v", err)
		}
		t.Fatal(err)
	}
	m := dnsManager{directory: dir, resolvPath: resolv, interfaceExists: func(string) bool { return true }}

	// Active symlink: applyFile replaces the link itself without touching the target,
	// and restore recreates the exact symlink target.
	if err := m.applyFile(); err != nil {
		t.Fatalf("applyFile on active symlink failed: %v", err)
	}
	if targetData, _ := os.ReadFile(target); string(targetData) != "nameserver 127.0.0.53\n" {
		t.Fatalf("symlink target was modified: %q", targetData)
	}
	if info, err := os.Lstat(resolv); err != nil || info.Mode()&os.ModeSymlink != 0 {
		t.Fatalf("expected regular resolv.conf during VPN, got %v %v", info, err)
	}
	if err := m.restore(); err != nil {
		t.Fatalf("restore active symlink failed: %v", err)
	}
	if gotTarget, err := os.Readlink(resolv); err != nil || gotTarget != target {
		t.Fatalf("symlink not restored: %q %v", gotTarget, err)
	}

	// Dangling symlink: applyFile succeeds and restore restores the exact dangling link.
	_ = os.Remove(target)
	if err := m.applyFile(); err != nil {
		t.Fatalf("applyFile on dangling symlink failed: %v", err)
	}
	if err := m.restore(); err != nil {
		t.Fatalf("restore dangling symlink failed: %v", err)
	}
	if gotTarget, err := os.Readlink(resolv); err != nil || gotTarget != target {
		t.Fatalf("dangling symlink not restored: %q %v", gotTarget, err)
	}
}
