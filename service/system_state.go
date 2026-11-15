package main

import (
	"encoding/json"
	"errors"
	"io/fs"
	"os"
)

/*
What the machine looked like before the location mode touched it. Written before the first change
and deleted only after a full restore, so whatever kills this process leaves a trail back. The
same file, the same shape, on every OS; only where it lives is the platform's business.
*/
type sysState struct {
	Active       bool   `json:"active"`
	Country      string `json:"country,omitempty"`
	Timezone     string `json:"timezone,omitempty"`
	PrevTimezone string `json:"prevTimezone"`
	// Whether the location sources were already silent before we arrived, so a restore that says
	// "leave the policy alone" means the owner's own policy and not ours.
	SourcesWereRunning bool `json:"sourcesWereRunning"`
	HadPolicy          bool `json:"hadPolicy"`
}

func readSysState() (*sysState, error) {
	path := sysStatePath()
	if path == "" {
		return nil, nil
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var st sysState
	if err := json.Unmarshal(data, &st); err != nil {
		return nil, err
	}
	return &st, nil
}

func writeSysState(st *sysState) error {
	path := sysStatePath()
	if path == "" {
		return errors.New("sysstate_unavailable")
	}
	if err := os.MkdirAll(dirOf(path), 0o755); err != nil {
		return err
	}
	data, err := json.Marshal(st)
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func clearSysState() error {
	path := sysStatePath()
	if path == "" {
		return nil
	}
	err := os.Remove(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}

func dirOf(path string) string {
	for i := len(path) - 1; i >= 0; i-- {
		if path[i] == '/' || path[i] == '\\' {
			return path[:i]
		}
	}
	return "."
}
