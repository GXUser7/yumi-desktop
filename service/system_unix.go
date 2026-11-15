//go:build unix

package main

import (
	"errors"
	"fmt"
	"os/exec"
	"strings"
)

/*
The unix half of the "match the server's location" mode: the same contract the Windows build
keeps, over the tools the platform owns. The clock moves through timedatectl on Linux and
systemsetup on macOS; the location sources go quiet by stopping and masking GeoClue on Linux and
by unloading locationd's system-domain service on macOS. Everything is best effort with a
snapshot first and a restore that is retried at the next start — the mode failing is a note in
the journal, never a failed connection.
*/

func sysStatePath() string {
	root := systemStateDirectory()
	if root == "" {
		return ""
	}
	return root + "/sysstate.json"
}

func currentTimezone() (string, error) {
	if isDarwin() {
		out, err := exec.Command("systemsetup", "-gettimezone").Output()
		if err != nil {
			return "", err
		}
		value := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(string(out)), "Time Zone:"))
		if value == "" {
			return "", errors.New("systemsetup: empty timezone")
		}
		return value, nil
	}
	out, err := exec.Command("timedatectl", "show", "-p", "Timezone", "--value").Output()
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(out)), nil
}

func setTimezone(id string) error {
	var out []byte
	var err error
	if isDarwin() {
		out, err = exec.Command("systemsetup", "-settimezone", id).CombinedOutput()
	} else {
		out, err = exec.Command("timedatectl", "set-timezone", id).CombinedOutput()
	}
	if err != nil {
		return fmt.Errorf("set-timezone: %s", strings.TrimSpace(string(out)))
	}
	return nil
}

// Whether the machine's location answers are being produced at all right now, and the two halves
// of putting that state back the way it was.
func locationSourcesRunning() bool {
	if isDarwin() {
		return exec.Command("launchctl", "print", "system/com.apple.locationd").Run() == nil
	}
	return exec.Command("systemctl", "is-active", "--quiet", "geoclue").Run() == nil
}

func silenceLocationSources() error {
	if isDarwin() {
		// Not running is the state we wanted anyway; only the disable is load-bearing, because
		// it keeps launchd from answering the next request for a location.
		_ = exec.Command("launchctl", "bootout", "system/com.apple.locationd").Run()
		return exec.Command("launchctl", "disable", "system/com.apple.locationd").Run()
	}
	// Mask rather than merely stop: GeoClue is socket-activated, and a stopped service is back
	// the moment some application asks it where it is.
	if out, err := exec.Command("systemctl", "mask", "geoclue").CombinedOutput(); err != nil {
		return fmt.Errorf("mask geoclue: %s", strings.TrimSpace(string(out)))
	}
	_ = exec.Command("systemctl", "stop", "geoclue").Run()
	return nil
}

func restoreLocationSources() error {
	if isDarwin() {
		if err := exec.Command("launchctl", "enable", "system/com.apple.locationd").Run(); err != nil {
			return err
		}
		return exec.Command("launchctl", "kickstart", "-k", "system/com.apple.locationd").Run()
	}
	if out, err := exec.Command("systemctl", "unmask", "geoclue").CombinedOutput(); err != nil {
		return fmt.Errorf("unmask geoclue: %s", strings.TrimSpace(string(out)))
	}
	return exec.Command("systemctl", "start", "geoclue").Run()
}

/*
Applies the mode to the machine, taking the one snapshot of how things were the first time and
moving only the timezone on every call after that. Returns the timezone the system is now in,
empty when the clock was left alone. Silencing the location sources is best effort — the caller
logs the refusal; a machine that keeps answering where it is still tunnels its traffic.
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
		tz, err := currentTimezone()
		if err != nil {
			return "", err
		}
		st = &sysState{Active: true, PrevTimezone: tz, SourcesWereRunning: locationSourcesRunning(), HadPolicy: false}
		// The snapshot lands on disk before the first change, so whatever kills this process
		// leaves a trail back to how things were.
		if err := writeSysState(st); err != nil {
			return "", err
		}
	}
	if id := countryTimezone(country); id != "" && id != st.Timezone {
		if err := setTimezone(id); err != nil {
			return "", err
		}
		st.Timezone, st.Country = id, country
		if err := writeSysState(st); err != nil {
			return "", err
		}
	}
	if err := silenceLocationSources(); err != nil {
		return st.Timezone, err
	}
	return st.Timezone, nil
}

/*
Puts the machine back the way the snapshot found it. Every step is best effort and the snapshot
survives until all of them have succeeded, so a restore that failed is retried at the daemon's
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
		note(setTimezone(st.PrevTimezone))
	}
	// Unconditional: an unmask and an enable are no-ops when we never touched anything, and the
	// snapshot's word about who silenced the sources first is not worth a machine left mute.
	note(restoreLocationSources())
	if first != nil {
		return first
	}
	return clearSysState()
}

// The daemon's start after a crash that left the mode applied. Best effort by design: failing to
// start the tunnel because yesterday's timezone refused to go back would be worse.
func restoreAbandonedLocation() {
	if err := restoreServerLocation(); err != nil {
		journalLine("location mode restore after start: " + detailOf(err))
	}
}

/*
One zone per country: the zone of the capital or the most populous region, the same choice the
Windows table makes. Kept as one string rather than a map because the table is data, edited by
hand, and read once per connection.
*/
const countryTimezones = `AE Asia/Dubai
AF Asia/Kabul
AL Europe/Tirane
AM Asia/Yerevan
AO Africa/Luanda
AQ Antarctica/McMurdo
AR America/Argentina/Buenos_Aires
AT Europe/Vienna
AU Australia/Sydney
AZ Asia/Baku
BA Europe/Sarajevo
BD Asia/Dhaka
BE Europe/Brussels
BF Africa/Ouagadougou
BG Europe/Sofia
BI Africa/Bujumbura
BJ Africa/Porto-Novo
BN Asia/Brunei
BO America/La_Paz
BR America/Sao_Paulo
BS America/Nassau
BT Asia/Thimphu
BW Africa/Gaborone
BY Europe/Minsk
BZ America/Belize
CA America/Toronto
CD Africa/Kinshasa
CF Africa/Bangui
CG Africa/Brazzaville
CH Europe/Zurich
CI Africa/Abidjan
CL America/Santiago
CM Africa/Douala
CN Asia/Shanghai
CO America/Bogota
CR America/Costa_Rica
CU America/Havana
CY Asia/Nicosia
CZ Europe/Prague
DE Europe/Berlin
DJ Africa/Djibouti
DK Europe/Copenhagen
DO America/Santo_Domingo
DZ Africa/Algiers
EC America/Guayaquil
EE Europe/Tallinn
EG Africa/Cairo
EH Africa/El_Aaiun
ER Africa/Asmara
ES Europe/Madrid
ET Africa/Addis_Ababa
FI Europe/Helsinki
FJ Pacific/Fiji
FK Atlantic/Stanley
FR Europe/Paris
GA Africa/Libreville
GB Europe/London
GE Asia/Tbilisi
GH Africa/Accra
GL America/Nuuk
GM Africa/Banjul
GN Africa/Conakry
GQ Africa/Malabo
GR Europe/Athens
GT America/Guatemala
GW Africa/Bissau
GY America/Guyana
HN America/Tegucigalpa
HR Europe/Zagreb
HT America/Port-au-Prince
HU Europe/Budapest
ID Asia/Jakarta
IE Europe/Dublin
IL Asia/Jerusalem
IN Asia/Kolkata
IQ Asia/Baghdad
IR Asia/Tehran
IS Atlantic/Reykjavik
IT Europe/Rome
JM America/Jamaica
JO Asia/Amman
JP Asia/Tokyo
KE Africa/Nairobi
KG Asia/Bishkek
KH Asia/Phnom_Penh
KP Asia/Pyongyang
KR Asia/Seoul
KW Asia/Kuwait
KZ Asia/Almaty
LA Asia/Vientiane
LB Asia/Beirut
LK Asia/Colombo
LR Africa/Monrovia
LS Africa/Maseru
LT Europe/Vilnius
LU Europe/Luxembourg
LV Europe/Riga
LY Africa/Tripoli
MA Africa/Casablanca
MD Europe/Chisinau
ME Europe/Podgorica
MG Indian/Antananarivo
MK Europe/Skopje
ML Africa/Bamako
MM Asia/Yangon
MN Asia/Ulaanbaatar
MR Africa/Nouakchott
MW Africa/Blantyre
MX America/Mexico_City
MY Asia/Kuala_Lumpur
MZ Africa/Maputo
NA Africa/Windhoek
NC Pacific/Noumea
NE Africa/Niamey
NG Africa/Lagos
NI America/Managua
NL Europe/Amsterdam
NO Europe/Oslo
NP Asia/Kathmandu
NZ Pacific/Auckland
OM Asia/Muscat
PA America/Panama
PE America/Lima
PG Pacific/Port_Moresby
PH Asia/Manila
PK Asia/Karachi
PL Europe/Warsaw
PR America/Puerto_Rico
PS Asia/Gaza
PT Europe/Lisbon
PY America/Asuncion
QA Asia/Qatar
RO Europe/Bucharest
RS Europe/Belgrade
RU Europe/Moscow
RW Africa/Kigali
SA Asia/Riyadh
SB Pacific/Guadalcanal
SD Africa/Khartoum
SE Europe/Stockholm
SI Europe/Ljubljana
SK Europe/Bratislava
SL Africa/Freetown
SN Africa/Dakar
SO Africa/Mogadishu
SR America/Paramaribo
SS Africa/Juba
SV America/El_Salvador
SY Asia/Damascus
SZ Africa/Mbabane
TD Africa/Ndjamena
TF Indian/Kerguelen
TG Africa/Lome
TH Asia/Bangkok
TJ Asia/Dushanbe
TL Asia/Dili
TM Asia/Ashgabat
TN Africa/Tunis
TR Europe/Istanbul
TT America/Port_of_Spain
TW Asia/Taipei
TZ Africa/Dar_es_Salaam
UA Europe/Kyiv
UG Africa/Kampala
US America/New_York
UY America/Montevideo
UZ Asia/Tashkent
VE America/Caracas
VN Asia/Ho_Chi_Minh
VU Pacific/Efate
XK Europe/Belgrade
YE Asia/Aden
ZA Africa/Johannesburg
ZM Africa/Lusaka
ZW Africa/Harare`

// The zone of a country code, or "" when the table does not know it — a refusal that leaves the
// clock alone rather than a guess that moves it somewhere wrong.
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
