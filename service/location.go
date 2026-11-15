package main

import "errors"

// The same recovery transaction on every OS. Only the platform's observations and commands differ.
type locationOps struct {
	currentTimezone func() (string, error)
	setTimezone     func(string) error
	captureSources  func(*sysState) error
	silenceSources  func(*sysState) error
	restoreSources  func(*sysState) error
}

func applyServerLocation(country string) (string, error) {
	return applyLocation(country, systemLocationOps())
}
func restoreServerLocation() error { return restoreLocation(systemLocationOps()) }

func applyLocation(country string, ops locationOps) (string, error) {
	st, err := readSysState()
	if err != nil {
		return "", err
	}
	if st != nil && !st.Active {
		if err := restoreLocation(ops); err != nil {
			return "", err
		}
		st = nil
	}
	if st == nil {
		tz, err := ops.currentTimezone()
		if err != nil {
			return "", err
		}
		st = &sysState{SnapshotVersion: 2, Active: true, PrevTimezone: tz}
		if err := ops.captureSources(st); err != nil {
			return "", err
		}
		if err := writeSysState(st); err != nil {
			return "", err
		}
	}
	target := countryTimezone(country)
	wanted := target
	if wanted == "" {
		wanted = st.PrevTimezone
	}
	if wanted != st.Timezone {
		// Persist the intent first: dying after tzutil/systemsetup but before this write used
		// to leave an empty Timezone field, so recovery skipped the changed clock entirely.
		st.Timezone, st.Country = wanted, country
		if err := writeSysState(st); err != nil {
			return "", err
		}
		if err := ops.setTimezone(wanted); err != nil {
			return "", err
		}
	}
	if err := ops.silenceSources(st); err != nil {
		return target, err
	}
	return target, nil
}

func restoreLocation(ops locationOps) error {
	st, err := readSysState()
	if err != nil || st == nil {
		return err
	}
	var clockErr error
	if st.Timezone != "" && st.PrevTimezone != st.Timezone {
		clockErr = ops.setTimezone(st.PrevTimezone)
	}
	if err := errors.Join(clockErr, ops.restoreSources(st)); err != nil {
		return err
	}
	return clearSysState()
}

func restoreAbandonedLocation() {
	if err := restoreServerLocation(); err != nil {
		journalLine("location mode restore after start: " + detailOf(err))
	}
}
