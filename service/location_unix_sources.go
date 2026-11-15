package main

import (
	"errors"
	"fmt"
	"strings"
)

func captureUnixLocationSources(st *sysState, mac bool, run commandRunner) error {
	if mac {
		disabled, err := run("launchctl", "print-disabled", "system")
		if err != nil {
			return err
		}
		for _, line := range strings.Split(disabled, "\n") {
			if strings.Contains(line, `"com.apple.locationd"`) {
				st.SourcesDisabled = strings.Contains(line, "=> true")
			}
		}
		job, jobErr := run("launchctl", "print", "system/com.apple.locationd")
		st.SourcesLoaded = jobErr == nil
		st.SourcesWereRunning = st.SourcesLoaded && strings.Contains(job, "state = running")
		return nil
	}
	text, err := run("systemctl", "show", "geoclue.service", "--property=LoadState,ActiveState,UnitFileState")
	if err != nil {
		return err
	}
	values := map[string]string{}
	for _, line := range strings.Split(text, "\n") {
		key, value, ok := strings.Cut(line, "=")
		if ok {
			values[key] = strings.TrimSpace(value)
		}
	}
	if values["LoadState"] == "" {
		return errors.New("systemctl did not report GeoClue state")
	}
	st.SourcesWereRunning = values["ActiveState"] == "active" || values["ActiveState"] == "activating"
	st.SourcesMask = values["UnitFileState"]
	if values["LoadState"] == "not-found" {
		st.SourcesMask = "not-found"
	}
	return nil
}

func silenceUnixLocationSources(st *sysState, mac bool, run commandRunner) error {
	if mac {
		if _, err := run("launchctl", "disable", "system/com.apple.locationd"); err != nil {
			return err
		}
		if st.SourcesLoaded || (st.SnapshotVersion < 2 && st.SourcesWereRunning) {
			_, err := run("launchctl", "bootout", "system/com.apple.locationd")
			return err
		}
		return nil
	}
	if st.SourcesMask == "not-found" {
		return nil
	}
	if st.SourcesMask != "masked" && st.SourcesMask != "masked-runtime" {
		if _, err := run("systemctl", "mask", "--runtime", "geoclue.service"); err != nil {
			return err
		}
	}
	if st.SourcesWereRunning {
		_, err := run("systemctl", "stop", "geoclue.service")
		return err
	}
	return nil
}

func restoreUnixLocationSources(st *sysState, mac bool, run commandRunner) error {
	if mac {
		action := "enable"
		if st.SourcesDisabled || (st.SnapshotVersion < 2 && !st.SourcesWereRunning) {
			action = "disable"
		}
		if _, err := run("launchctl", action, "system/com.apple.locationd"); err != nil {
			return err
		}
		if !st.SourcesLoaded && !st.SourcesWereRunning {
			return nil
		}
		if _, err := run("launchctl", "print", "system/com.apple.locationd"); err != nil {
			if _, err = run("launchctl", "bootstrap", "system", "/System/Library/LaunchDaemons/com.apple.locationd.plist"); err != nil {
				return err
			}
		}
		if st.SourcesWereRunning {
			_, err := run("launchctl", "kickstart", "system/com.apple.locationd")
			return err
		}
		return nil
	}
	if st.SourcesMask == "not-found" {
		return nil
	}
	if st.SnapshotVersion >= 2 {
		if st.SourcesMask != "masked" && st.SourcesMask != "masked-runtime" {
			if _, err := run("systemctl", "unmask", "--runtime", "geoclue.service"); err != nil {
				return err
			}
		}
	} else if st.SourcesWereRunning {
		// 1.0.x wrote a persistent mask but did not record whether one existed before.
		if _, err := run("systemctl", "unmask", "geoclue.service"); err != nil {
			return err
		}
	}
	if st.SourcesWereRunning {
		if _, err := run("systemctl", "start", "geoclue.service"); err != nil {
			return fmt.Errorf("restore GeoClue: %w", err)
		}
	}
	return nil
}
