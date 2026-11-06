# Third-party components

Yumi Desktop is distributed under the GNU General Public License v3.0 or later; the full text is
in `LICENSE`. What follows is everything else that ships inside the installer, and under what terms.

The installer places the machine-readable copies beside the application, in `licenses/`. That
folder is assembled at build time by `tools/collect-licenses.ps1`, which copies this file, the
hand-written notices in `licenses/`, and the `LICENSE`/`NOTICE` file of every Go module the tunnel
service links — the last of those is generated rather than listed here, because the set follows
whatever `service/go.mod` resolves to and a list kept by hand would be wrong by the next update.
`licenses/Go-dependencies.txt` names each module and the exact version that was built.

## The tunnel

**Xray-core** — <https://github.com/XTLS/Xray-core> — Mozilla Public License 2.0.
The proxy engine. Linked into `yumi-service.exe`, which is the only component that speaks to a
server; the application itself never opens a proxy connection. Source for the exact revision is
recorded in `licenses/Go-dependencies.txt`.

**Wintun** — <https://www.wintun.net> — Prebuilt Binaries License (see `licenses/Wintun.txt`).
The virtual network adapter the tunnel runs over. Shipped as the unmodified signed
`wintun-0.14.1` amd64 binary, downloaded and checksum-verified by `tools/bootstrap.ps1`.

**WireGuard for Windows** — <https://git.zx2c4.com/wireguard-windows> — MIT.
The kill switch's filtering-platform code is adapted from its `tunnel/firewall` package and lives
in `service/firewall/`, with the original copyright notices kept in each file.

**Go standard library and the modules in `service/go.mod`** — mostly BSD-3-Clause and MIT.
Each module's own notice is copied into `licenses/` at build time.

## Routing databases

**v2fly geoip** and **domain-list-community** — <https://github.com/v2fly> — CC-BY-SA 4.0 and
MIT. Not shipped: the service downloads them on request, into `%ProgramData%\Yumi Desktop\geo`,
and they decide which sites go around the tunnel when "Russian sites directly" is on.

## The application

**Compose Multiplatform** and **Kotlin** — Apache License 2.0 — JetBrains.
**AndroidX Compose** (`compose.material3`, `androidx.graphics:graphics-shapes`) — Apache License 2.0 — The Android Open Source Project.
**kotlinx.coroutines**, **kotlinx.serialization** — Apache License 2.0 — JetBrains.
**ZXing** — Apache License 2.0 — used to lay out the pairing QR code offline; nothing is scanned.
**JNA** — Apache License 2.0 or LGPL 2.1 (dual) — used for the Windows registry, the service
control manager and the title-bar colour.

## Typefaces

**Unbounded** — SIL Open Font License 1.1 — see `licenses/Unbounded-OFL.txt`.
Page titles and the tunnel's headline — the same face Yumi's sister app YouCloud sets its titles in.

**Onest** — SIL Open Font License 1.1 — see `licenses/Onest-OFL.txt`.
Everything else, figures included: its tabular numerals keep the timer and the rates from
twitching as digits change.

Both are bundled as static instances cut down to Latin and Cyrillic rather than as the variable
files: pinning the weight axis drops the delta tables, and Skia then has nothing left to interpret.

**Twemoji** — CC-BY 4.0 (graphics), Apache License 2.0 (build tooling) — see
`licenses/Twemoji-CC-BY-4.0.txt`. Bundled because Windows ships no glyph for a country flag: a
flag emoji is two Regional Indicators, and Segoe UI Emoji draws the letters instead. Used only for
the emoji in server names.

## Map data

**Natural Earth** — public domain — <https://www.naturalearthdata.com>.
The land mask the connect screen's planet is drawn from, and the country label points it centres
on, are derived from `ne_110m_admin_0_countries` and reduced to a 2 KB bitmask. Nothing is asked of
the network to find out where a server is: the country comes from the flag in the server's own
name, and the coordinates come from a table compiled into the app.
