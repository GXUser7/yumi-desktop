//go:build unix

package main

import (
	"errors"
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

func legacySysStatePath() string { return "" }

func currentTimezone() (string, error) {
	if isDarwin() {
		out, err := runSystemCommand("systemsetup", "-gettimezone")
		if err != nil {
			return "", err
		}
		value := strings.TrimSpace(strings.TrimPrefix(out, "Time Zone:"))
		if value == "" {
			return "", errors.New("systemsetup: empty timezone")
		}
		return value, nil
	}
	out, err := runSystemCommand("timedatectl", "show", "-p", "Timezone", "--value")
	if err == nil && out == "" {
		err = errors.New("timedatectl: empty timezone")
	}
	return out, err
}

func setTimezone(id string) error {
	var err error
	if isDarwin() {
		_, err = runSystemCommand("systemsetup", "-settimezone", id)
	} else {
		_, err = runSystemCommand("timedatectl", "set-timezone", id)
	}
	return err
}

func systemLocationOps() locationOps {
	return locationOps{
		currentTimezone: currentTimezone, setTimezone: setTimezone,
		captureSources: func(st *sysState) error { return captureUnixLocationSources(st, isDarwin(), runSystemCommand) },
		silenceSources: func(st *sysState) error { return silenceUnixLocationSources(st, isDarwin(), runSystemCommand) },
		restoreSources: func(st *sysState) error { return restoreUnixLocationSources(st, isDarwin(), runSystemCommand) },
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
