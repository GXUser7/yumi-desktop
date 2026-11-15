package main

import (
	"errors"
	"runtime"
	"strings"
	"testing"
)

func locationFixture(t *testing.T) {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Setenv("ProgramData", t.TempDir())
	} else {
		t.Setenv("YUMI_STATE_DIR", t.TempDir())
	}
}

func TestLocationPersistsIntentBeforeChangingTheClockAndRecoversPartialApply(t *testing.T) {
	locationFixture(t)
	clock := "original-zone"
	failApply := true
	restored := false
	ops := locationOps{
		currentTimezone: func() (string, error) { return clock, nil },
		captureSources: func(st *sysState) error {
			st.DisableLocation = policyValue{Present: true, Type: 4, Value: 0}
			return nil
		},
		setTimezone: func(zone string) error {
			st, err := readSysState()
			if err != nil || st == nil || st.Timezone == "" {
				t.Fatal("clock changed before durable intent")
			}
			clock = zone
			if failApply {
				return errors.New("process interrupted after changing timezone")
			}
			return nil
		},
		silenceSources: func(*sysState) error { return nil },
		restoreSources: func(st *sysState) error {
			restored = st.DisableLocation.Present && st.DisableLocation.Value == 0
			return nil
		},
	}
	if _, err := applyLocation("DE", ops); err == nil {
		t.Fatal("expected interrupted application")
	}
	if clock != countryTimezone("DE") {
		t.Fatal("fixture did not reach the crash window")
	}
	failApply = false
	if err := restoreLocation(ops); err != nil {
		t.Fatal(err)
	}
	if clock != "original-zone" || !restored {
		t.Fatal("partial application did not restore its exact baseline")
	}
}

func TestLocationWithoutACountryReturnsToTheOriginalTimezone(t *testing.T) {
	locationFixture(t)
	clock := "original-zone"
	ops := locationOps{currentTimezone: func() (string, error) { return clock, nil }, setTimezone: func(v string) error { clock = v; return nil },
		captureSources: func(*sysState) error { return nil }, silenceSources: func(*sysState) error { return nil }, restoreSources: func(*sysState) error { return nil }}
	if _, err := applyLocation("DE", ops); err != nil {
		t.Fatal(err)
	}
	if _, err := applyLocation("", ops); err != nil {
		t.Fatal(err)
	}
	if clock != "original-zone" {
		t.Fatal("kept the last server's timezone for an unflagged server")
	}
}

func TestUnixLocationRestorePreservesStoppedAndMaskedServices(t *testing.T) {
	for _, mac := range []bool{false, true} {
		var commands []string
		run := func(name string, args ...string) (string, error) {
			commands = append(commands, name+" "+strings.Join(args, " "))
			return "", nil
		}
		st := &sysState{SnapshotVersion: 2, SourcesWereRunning: false, SourcesDisabled: true, SourcesMask: "masked-runtime"}
		if err := restoreUnixLocationSources(st, mac, run); err != nil {
			t.Fatal(err)
		}
		for _, cmd := range commands {
			if strings.Contains(cmd, "start") || strings.Contains(cmd, "enable") || strings.Contains(cmd, "unmask") {
				t.Fatalf("enabled a source that was already disabled: %v", commands)
			}
		}
	}
}

func TestLocationRestoreFailureRetainsTheSnapshotForRetry(t *testing.T) {
	locationFixture(t)
	if err := writeSysState(&sysState{Active: true, PrevTimezone: "original", Timezone: "changed"}); err != nil {
		t.Fatal(err)
	}
	ops := locationOps{setTimezone: func(string) error { return errors.New("try later") }, restoreSources: func(*sysState) error { return nil }}
	if err := restoreLocation(ops); err == nil {
		t.Fatal("claimed a failed restoration succeeded")
	}
	if st, _ := readSysState(); st == nil {
		t.Fatal("deleted the only recovery record")
	}
	ops.setTimezone = func(string) error { return nil }
	if err := restoreLocation(ops); err != nil {
		t.Fatal(err)
	}
	if st, _ := readSysState(); st != nil {
		t.Fatal("kept a completed recovery record")
	}
}
