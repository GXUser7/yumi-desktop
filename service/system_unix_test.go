//go:build unix

package main

import (
	"strings"
	"testing"
)

/*
The same data checks the Windows table gets, because a hand-edited table rots the same way on
every OS: a typo in a code hides a country behind the machine's own clock, a duplicate shadows
one of the two, and a misspelt zone id only fails on the machine that draws it.
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
	if len(seen) != 175 {
		t.Fatalf("%d countries, expected 175", len(seen))
	}
	pinned := map[string]string{
		"DE": "Europe/Berlin",
		"RU": "Europe/Moscow",
		"US": "America/New_York",
		"GB": "Europe/London",
		"CN": "Asia/Shanghai",
		"NL": "Europe/Amsterdam",
		"JP": "Asia/Tokyo",
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
The snapshot file is the whole crash story; the real apply and restore move the system clock and
are never run from a test. The file mechanics are what can be exercised honestly.
*/
func TestSysStateFileRoundTrip(t *testing.T) {
	t.Setenv("YUMI_STATE_DIR", t.TempDir())
	if st, err := readSysState(); err != nil || st != nil {
		t.Fatalf("empty machine answered %v, %v", st, err)
	}
	want := &sysState{Active: true, Country: "DE", Timezone: "Europe/Berlin",
		PrevTimezone: "Europe/Moscow", SourcesWereRunning: true, HadPolicy: false}
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
	if err := clearSysState(); err != nil {
		t.Fatal(err)
	}
	if err := restoreServerLocation(); err != nil {
		t.Fatalf("restore without a snapshot refused: %v", err)
	}
}
