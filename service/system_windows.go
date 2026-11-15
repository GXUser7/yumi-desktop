//go:build windows

package main

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"golang.org/x/sys/windows/registry"
	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
)

/*
The "match the server's location" mode.

Two system-wide facts give a machine away no matter what the tunnel does to its IP address: the
clock's timezone, and the Windows Location Service, which triangulates the real room by the Wi-Fi
access points around it and answers even when every packet rides the tunnel. While the mode is on
this service moves the first onto the selected server's country and silences the second; on
disconnect, on a worker that died, and on the service's next start after a crash, everything goes
back exactly the way it was found.

The machine is only ever changed after a snapshot of its current state has landed on disk, which
is what makes the recovery honest: the file under ProgramData names the timezone to put back, and
whether the location service was running and the policy keys were there before we touched them.
The policy keys are the enforcement — DisableLocation refuses every client — and stopping lfsvc is
belt and braces on top.

The timezone half is skipped rather than guessed: a server without a flag in its name, or with one
the table below does not know, keeps the machine's clock and only silences location.
*/

const lfsvcName = "lfsvc"
const locationPolicyKey = `SOFTWARE\Policies\Microsoft\Windows\LocationAndSensors`

func sysStatePath() string {
	root := os.Getenv("ProgramData")
	if root == "" {
		return ""
	}
	return filepath.Join(root, "Yumi Desktop", "sysstate.json")
}

func currentWindowsTimezone() (string, error) {
	out, err := exec.Command("tzutil", "/g").Output()
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(out)), nil
}

func setWindowsTimezone(id string) error {
	out, err := exec.Command("tzutil", "/s", id).CombinedOutput()
	if err != nil {
		return fmt.Errorf("tzutil: %s", strings.TrimSpace(string(out)))
	}
	return nil
}

func locationPolicyPresent() bool {
	key, err := registry.OpenKey(registry.LOCAL_MACHINE, locationPolicyKey, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer key.Close()
	_, _, err = key.GetIntegerValue("DisableLocation")
	return err == nil
}

func setLocationPolicy() error {
	key, _, err := registry.CreateKey(registry.LOCAL_MACHINE, locationPolicyKey, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer key.Close()
	if err := key.SetDWordValue("DisableLocation", 1); err != nil {
		return err
	}
	return key.SetDWordValue("DisableWindowsLocationProvider", 1)
}

// A policy the machine already had is the owner's, not ours: it is left standing on restore
// rather than deleted by something that never wrote it.
func clearLocationPolicy(had bool) error {
	if had {
		return nil
	}
	key, err := registry.OpenKey(registry.LOCAL_MACHINE, locationPolicyKey, registry.SET_VALUE)
	if err != nil {
		if errors.Is(err, registry.ErrNotExist) {
			return nil
		}
		return err
	}
	defer key.Close()
	for _, name := range []string{"DisableLocation", "DisableWindowsLocationProvider"} {
		if err := key.DeleteValue(name); err != nil && !errors.Is(err, registry.ErrNotExist) {
			return err
		}
	}
	return nil
}

func openLfsvc() (*mgr.Service, func(), error) {
	m, err := mgr.Connect()
	if err != nil {
		return nil, nil, err
	}
	s, err := m.OpenService(lfsvcName)
	if err != nil {
		m.Disconnect()
		return nil, nil, err
	}
	return s, func() { s.Close(); m.Disconnect() }, nil
}

func lfsvcRunning() bool {
	s, closeFn, err := openLfsvc()
	if err != nil {
		return false
	}
	defer closeFn()
	state, err := s.Query()
	return err == nil && state.State == svc.Running
}

func lfsvcStop() error {
	s, closeFn, err := openLfsvc()
	if err != nil {
		return err
	}
	defer closeFn()
	state, err := s.Query()
	if err != nil || state.State != svc.Running {
		return nil
	}
	halt(s)
	return nil
}

func lfsvcStart() error {
	s, closeFn, err := openLfsvc()
	if err != nil {
		return err
	}
	defer closeFn()
	state, err := s.Query()
	if err != nil || state.State == svc.Running {
		return nil
	}
	return s.Start()
}

/*
Applies the mode to the machine, taking the one snapshot of how things were the first time and
moving only the timezone on every call after that. Returns the timezone the system is now in,
empty when the clock was left alone. Stopping lfsvc is best effort — a service that will not stop
is not worth failing the mode over, the policy already refuses every client.
*/
func applyServerLocation(country string) (string, error) {
	st, err := readSysState()
	if err != nil {
		return "", err
	}
	if st != nil && !st.Active {
		// A restore that died halfway left its snapshot behind. Start over rather than trust a
		// machine description that may predate changes made since.
		_ = clearSysState()
		st = nil
	}
	if st == nil {
		tz, err := currentWindowsTimezone()
		if err != nil {
			return "", err
		}
		st = &sysState{Active: true, PrevTimezone: tz, SourcesWereRunning: lfsvcRunning(), HadPolicy: locationPolicyPresent()}
		if err := writeSysState(st); err != nil {
			return "", err
		}
	}
	if id := countryTimezone(country); id != "" && id != st.Timezone {
		if err := setWindowsTimezone(id); err != nil {
			return "", err
		}
		st.Timezone, st.Country = id, country
		if err := writeSysState(st); err != nil {
			return "", err
		}
	}
	if err := setLocationPolicy(); err != nil {
		return st.Timezone, err
	}
	_ = lfsvcStop()
	return st.Timezone, nil
}

/*
Puts the machine back the way the snapshot found it. Every step is best effort and the snapshot
survives until all of them have succeeded, so a restore that failed is retried at the service's
next start instead of being lost with the file.
*/
func restoreServerLocation() error {
	st, err := readSysState()
	if err != nil || st == nil {
		return err
	}
	var first error
	note := func(err error) {
		if err != nil && first == nil {
			first = err
		}
	}
	if st.Timezone != "" && st.PrevTimezone != st.Timezone {
		note(setWindowsTimezone(st.PrevTimezone))
	}
	note(clearLocationPolicy(st.HadPolicy))
	if st.SourcesWereRunning {
		note(lfsvcStart())
	}
	if first != nil {
		return first
	}
	return clearSysState()
}

// The service's start after a crash that left the mode applied. Best effort by design: failing
// to start the tunnel because yesterday's timezone refused to go back would be worse.
func restoreAbandonedLocation() {
	if err := restoreServerLocation(); err != nil {
		journalLine("location mode restore after start: " + detailOf(err))
	}
}

/*
One timezone per country: the zone of the capital or the most populous region, which is what a
site's timezone heuristic means when it asks where the machine is. Kept as one string rather than
a map because the table is data, edited by hand, and 175 map entries built at class-load time
would buy nothing on a path taken once per connection.

Windows names its zones its own way; the spellings are the tzres ones tzutil accepts.
*/
const countryTimezones = `AE Gulf Standard Time
AF Afghanistan Standard Time
AL Central Europe Standard Time
AM Caucasus Standard Time
AO W. Central Africa Standard Time
AQ New Zealand Standard Time
AR Argentina Standard Time
AT W. Europe Standard Time
AU AUS Eastern Standard Time
AZ Azerbaijan Standard Time
BA Central European Standard Time
BD Bangladesh Standard Time
BE Romance Standard Time
BF Greenwich Standard Time
BG FLE Standard Time
BI South Africa Standard Time
BJ W. Central Africa Standard Time
BN Singapore Standard Time
BO SA Western Standard Time
BR E. South America Standard Time
BS Eastern Standard Time
BT Bangladesh Standard Time
BW South Africa Standard Time
BY Belarus Standard Time
BZ Central America Standard Time
CA Eastern Standard Time
CD W. Central Africa Standard Time
CF W. Central Africa Standard Time
CG W. Central Africa Standard Time
CH W. Europe Standard Time
CI Greenwich Standard Time
CL Pacific SA Standard Time
CM W. Central Africa Standard Time
CN China Standard Time
CO SA Pacific Standard Time
CR Central America Standard Time
CU Eastern Standard Time
CY GTB Standard Time
CZ Central Europe Standard Time
DE W. Europe Standard Time
DJ E. Africa Standard Time
DK Romance Standard Time
DO SA Western Standard Time
DZ W. Central Africa Standard Time
EC SA Pacific Standard Time
EE FLE Standard Time
EG Egypt Standard Time
EH Greenwich Standard Time
ER E. Africa Standard Time
ES Romance Standard Time
ET E. Africa Standard Time
FI FLE Standard Time
FJ Fiji Standard Time
FK Atlantic Standard Time
FR Romance Standard Time
GA W. Central Africa Standard Time
GB GMT Standard Time
GE Georgian Standard Time
GH Greenwich Standard Time
GL Greenland Standard Time
GM Greenwich Standard Time
GN Greenwich Standard Time
GQ W. Central Africa Standard Time
GR GTB Standard Time
GT Central America Standard Time
GW Greenwich Standard Time
GY SA Western Standard Time
HN Central America Standard Time
HR Central European Standard Time
HT Haiti Standard Time
HU Central Europe Standard Time
ID SE Asia Standard Time
IE GMT Standard Time
IL Israel Standard Time
IN India Standard Time
IQ Arabic Standard Time
IR Iran Standard Time
IS Greenwich Standard Time
IT W. Europe Standard Time
JM SA Pacific Standard Time
JO Jordan Standard Time
JP Tokyo Standard Time
KE E. Africa Standard Time
KG Central Asia Standard Time
KH SE Asia Standard Time
KP Korea Standard Time
KR Korea Standard Time
KW Arab Standard Time
KZ Qyzylorda Standard Time
LA SE Asia Standard Time
LB Middle East Standard Time
LK Sri Lanka Standard Time
LR Greenwich Standard Time
LS South Africa Standard Time
LT FLE Standard Time
LU W. Europe Standard Time
LV FLE Standard Time
LY E. Europe Standard Time
MA Morocco Standard Time
MD FLE Standard Time
ME Central European Standard Time
MG E. Africa Standard Time
MK Central European Standard Time
ML Greenwich Standard Time
MM Myanmar Standard Time
MN Ulaanbaatar Standard Time
MR Greenwich Standard Time
MW South Africa Standard Time
MX Central Standard Time (Mexico)
MY Singapore Standard Time
MZ South Africa Standard Time
NA Namibia Standard Time
NC Central Pacific Standard Time
NE W. Central Africa Standard Time
NG W. Central Africa Standard Time
NI Central America Standard Time
NL W. Europe Standard Time
NO W. Europe Standard Time
NP Nepal Standard Time
NZ New Zealand Standard Time
OM Arabian Standard Time
PA Central America Standard Time
PE SA Pacific Standard Time
PG West Pacific Standard Time
PH Singapore Standard Time
PK Pakistan Standard Time
PL Central European Standard Time
PR SA Western Standard Time
PS West Bank Standard Time
PT GMT Standard Time
PY Paraguay Standard Time
QA Arab Standard Time
RO GTB Standard Time
RS Central European Standard Time
RU Russian Standard Time
RW South Africa Standard Time
SA Arab Standard Time
SB Central Pacific Standard Time
SD E. Africa Standard Time
SE W. Europe Standard Time
SI Central European Standard Time
SK Central Europe Standard Time
SL Greenwich Standard Time
SN Greenwich Standard Time
SO E. Africa Standard Time
SR Suriname Standard Time
SS E. Africa Standard Time
SV Central America Standard Time
SY Syria Standard Time
SZ South Africa Standard Time
TD W. Central Africa Standard Time
TF West Asia Standard Time
TG Greenwich Standard Time
TH SE Asia Standard Time
TJ West Asia Standard Time
TL Tokyo Standard Time
TM Turkmenistan Standard Time
TN W. Central Africa Standard Time
TR Turkey Standard Time
TT SA Western Standard Time
TW Taipei Standard Time
TZ E. Africa Standard Time
UA FLE Standard Time
UG E. Africa Standard Time
US Eastern Standard Time
UY Montevideo Standard Time
UZ Uzbekistan Standard Time
VE Venezuela Standard Time
VN SE Asia Standard Time
VU Central Pacific Standard Time
XK Central European Standard Time
YE Arab Standard Time
ZA South Africa Standard Time
ZM South Africa Standard Time
ZW South Africa Standard Time`

// The Windows timezone of a country code, or "" when the table does not know it — a refusal that
// leaves the clock alone rather than a guess that moves it somewhere wrong.
func countryTimezone(code string) string {
	if !validCountry(code) || code == "" {
		return ""
	}
	for _, line := range strings.Split(countryTimezones, "\n") {
		id, tz, found := strings.Cut(line, " ")
		if found && id == code {
			return tz
		}
	}
	return ""
}
