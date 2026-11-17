//go:build windows

package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

func TestLegacyLocationSnapshotMigratesAndClearsBothPathsAfterRecovery(t *testing.T) {
	t.Setenv("ProgramData", t.TempDir())
	legacy := legacySysStatePath()
	if err := os.MkdirAll(filepath.Dir(legacy), 0o755); err != nil {
		t.Fatal(err)
	}
	want := sysState{Active: true, PrevTimezone: "original", Timezone: "changed", HadPolicy: true}
	data, _ := json.Marshal(want)
	if err := os.WriteFile(legacy, data, 0o600); err != nil {
		t.Fatal(err)
	}
	got, err := readSysState()
	if err != nil || got == nil || *got != want {
		t.Fatal("lost the legacy recovery baseline")
	}
	if _, err := os.Stat(sysStatePath()); err != nil {
		t.Fatal("did not create the protected-path snapshot")
	}
	if err := clearSysState(); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{legacy, sysStatePath()} {
		if _, err := os.Stat(path); !os.IsNotExist(err) {
			t.Fatal("left a stale recovery record")
		}
	}
}

/*
The table is data edited by hand, which is exactly the kind of thing that rots quietly: a typo in
a code hides a country behind the machine's own clock, a duplicate shadows one of the two, and a
misspelt zone id only fails on the machine that draws it. So every entry is checked here, and a
few well-known countries are pinned by name.
*/
func TestCountryTimezonesParse(t *testing.T) {
	seen := map[string]string{}
	for _, line := range strings.Split(countryTimezones, "\n") {
		code, tz, found := strings.Cut(line, " ")
		if !found || tz == "" {
			t.Fatalf("malformed entry %q", line)
		}
		if !validCountry(code) || code == "" {
			t.Fatalf("bad code %q", code)
		}
		if prev, dup := seen[code]; dup {
			t.Fatalf("%s appears twice: %q and %q", code, prev, tz)
		}
		if strings.ContainsAny(tz, "\n\r\t") {
			t.Fatalf("control character in %q", tz)
		}
		seen[code] = tz
	}
	// 175 is the count of the WorldMap table this mirrors; drift in either is a question.
	if len(seen) != 175 {
		t.Fatalf("%d countries, expected 175", len(seen))
	}
	pinned := map[string]string{
		"DE": "W. Europe Standard Time",
		"RU": "Russian Standard Time",
		"US": "Eastern Standard Time",
		"GB": "GMT Standard Time",
		"CN": "China Standard Time",
		"NL": "W. Europe Standard Time",
		"JP": "Tokyo Standard Time",
	}
	for code, want := range pinned {
		if got := countryTimezone(code); got != want {
			t.Fatalf("%s: %q, expected %q", code, got, want)
		}
	}
	if got := countryTimezone("ZZ"); got != "" {
		t.Fatalf("unknown country answered %q", got)
	}
	if got := countryTimezone("de"); got != "" {
		t.Fatalf("lowercase code answered %q", got)
	}
}

/*
The snapshot file is the whole crash story: written before the first change, read back by a
service that no longer remembers why, cleared only when the machine is whole again. The real
apply and restore move the system clock and are never run from a test; the file mechanics are
what can be exercised honestly.
*/
func TestSysStateFileRoundTrip(t *testing.T) {
	t.Setenv("ProgramData", t.TempDir())
	if st, err := readSysState(); err != nil || st != nil {
		t.Fatalf("empty machine answered %v, %v", st, err)
	}
	want := &sysState{Active: true, Country: "DE", Timezone: "W. Europe Standard Time",
		PrevTimezone: "Russian Standard Time", SourcesWereRunning: true, HadPolicy: false}
	if err := writeSysState(want); err != nil {
		t.Fatal(err)
	}
	got, err := readSysState()
	if err != nil || got == nil || *got != *want {
		t.Fatalf("round trip: %v, %v", got, err)
	}
	if err := clearSysState(); err != nil {
		t.Fatal(err)
	}
	if st, err := readSysState(); err != nil || st != nil {
		t.Fatalf("cleared machine answered %v, %v", st, err)
	}
	// A second clear is a no-op, not an error: every teardown path calls this.
	if err := clearSysState(); err != nil {
		t.Fatal(err)
	}
}

func TestSysStateNeedsProgramData(t *testing.T) {
	t.Setenv("ProgramData", "")
	if err := writeSysState(&sysState{Active: true}); err == nil {
		t.Fatal("wrote a snapshot with nowhere to put it")
	}
	if st, err := readSysState(); st != nil || err != nil {
		t.Fatalf("read answered %v, %v", st, err)
	}
	if err := clearSysState(); err != nil {
		t.Fatalf("clear refused: %v", err)
	}
	if err := restoreServerLocation(); err != nil {
		t.Fatalf("restore without a snapshot refused: %v", err)
	}
}

// A connected frame can queue behind disconnect on the command lock. Its delayed callback must
// not move the machine again after disconnect has already restored it. No writable snapshot
// directory is provided, so the pre-fix path cannot change the test machine either.
func TestLateLocationFrameCannotReactivateAfterDisconnect(t *testing.T) {
	t.Setenv("ProgramData", "")
	for _, state := range []string{"disconnected", "disconnecting", "connecting", "failed"} {
		s := &supervisor{options: &Options{MatchLocation: true}, status: Status{State: state, ServerCountry: "DE"}}
		s.applyLocationMode()
		if s.sysActive || s.sysCountry != "" || s.sysNote != "" {
			t.Fatalf("late callback applied location to %s: active=%v country=%q note=%q", state, s.sysActive, s.sysCountry, s.sysNote)
		}
	}
}

func TestLocationPolicyRoundTripPreservesBothValuesTypesAndAbsence(t *testing.T) {
	path := fmt.Sprintf(`Software\YumiDesktopTest\Location-%d`, time.Now().UnixNano())
	key, _, err := registry.CreateKey(registry.CURRENT_USER, path, registry.ALL_ACCESS)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { key.Close(); registry.DeleteKey(registry.CURRENT_USER, path) }()
	if err := key.SetDWordValue("DisableLocation", 0); err != nil {
		t.Fatal(err)
	}
	if err := key.SetQWordValue("DisableWindowsLocationProvider", 0); err != nil {
		t.Fatal(err)
	}
	st := &sysState{SnapshotVersion: 2}
	if err := capturePolicyValues(key, st); err != nil {
		t.Fatal(err)
	}
	_ = key.SetDWordValue("DisableLocation", 1)
	_ = key.SetDWordValue("DisableWindowsLocationProvider", 1)
	if err := restorePolicyValues(key, st); err != nil {
		t.Fatal(err)
	}
	for name, want := range map[string]uint32{"DisableLocation": registry.DWORD, "DisableWindowsLocationProvider": registry.QWORD} {
		value, kind, err := key.GetIntegerValue(name)
		if err != nil || value != 0 || kind != want {
			t.Fatalf("lost original %s: value%d type%d %v", name, value, kind, err)
		}
	}
	if err := restorePolicyValues(key, &sysState{SnapshotVersion: 2}); err != nil {
		t.Fatal(err)
	}
	if _, _, err := key.GetIntegerValue("DisableLocation"); err != registry.ErrNotExist {
		t.Fatal("restored an absent value as zero instead of removing it")
	}
	_ = key.SetStringValue("DisableLocation", "administrator policy")
	if err := capturePolicyValues(key, &sysState{}); err == nil {
		t.Fatal("accepted an unknown policy type that cannot be restored")
	}
}

func TestLegacySysStateRefusesNonRegularAndJunctionTarget(t *testing.T) {
	t.Setenv("ProgramData", t.TempDir())
	legacy := legacySysStatePath()
	// A directory at legacySysStatePath is not a regular file and must be refused.
	if err := os.MkdirAll(legacy, 0o755); err != nil {
		t.Fatal(err)
	}
	if _, err := readSysState(); err == nil {
		t.Fatal("accepted a directory at legacy sysstate path")
	}
	_ = os.Remove(legacy)

	// A junction at legacySysStatePath (or symlink if allowed) must also be refused.
	target := t.TempDir()
	cmd := exec.Command("cmd.exe", "/c", "mklink", "/J", legacy, target)
	if err := cmd.Run(); err != nil {
		t.Fatalf("mklink /J failed: %v", err)
	}
	if _, err := readSysState(); err == nil {
		t.Fatal("accepted a reparse point at legacy sysstate path")
	}
}

func TestSecureDirectoryAndWorkerJournalRefuseReparsePointsAndRotate(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("ProgramData", tmp)
	target := t.TempDir()
	root := programDataRoot()
	if err := exec.Command("cmd.exe", "/c", "mklink", "/J", root, target).Run(); err != nil {
		t.Fatalf("mklink /J failed: %v", err)
	}
	if err := secureDirectory(root); err == nil {
		t.Fatal("secureDirectory accepted a junction/reparse point")
	}
	if f := workerJournal(); f != nil {
		_ = f.Close()
		t.Fatal("workerJournal opened a junction directory")
	}

	// Now test normal journal write + >1 MiB rotation.
	t.Setenv("ProgramData", t.TempDir())
	f := workerJournal()
	if f == nil {
		t.Fatal("workerJournal returned nil on clean temp dir")
	}
	_ = f.Close()

	logPath := filepath.Join(programDataRoot(), "worker.log")
	oversized := bytes.Repeat([]byte("A"), (1<<20)+128)
	if err := os.WriteFile(logPath, oversized, 0o644); err != nil {
		t.Fatal(err)
	}
	rotated := workerJournal()
	if rotated == nil {
		t.Fatal("workerJournal returned nil during rotation")
	}
	_ = rotated.Close()

	bakInfo, err := os.Stat(logPath + ".1")
	if err != nil || bakInfo.Size() != int64(len(oversized)) {
		t.Fatalf("expected rotated worker.log.1 with %d bytes, got %v %v", len(oversized), bakInfo, err)
	}
	curInfo, err := os.Stat(logPath)
	if err != nil || curInfo.Size() >= 1<<20 {
		t.Fatalf("expected fresh rotated worker.log, got %v %v", curInfo, err)
	}
}

func TestTzutilPathResolvesSystemDirectory(t *testing.T) {
	sysDir, err := windows.GetSystemDirectory()
	if err != nil {
		t.Fatal(err)
	}
	got, err := tzutilPath()
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(sysDir, "tzutil.exe")
	if !strings.EqualFold(got, want) {
		t.Fatalf("got %q want %q", got, want)
	}
}
