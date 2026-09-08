# Manual tests

The checks a machine cannot make for itself. The automated suites cover parsing, the Xray config,
latency and speed arithmetic, storage and the pairing protocol; what is left is everything that
needs a real network, a real server and a pair of eyes.

Work down the list on a clean install. Where a step says what should *not* happen, that is the
interesting half of it.

## Install and first run

- [ ] The installer elevates once and finishes without a second prompt.
- [ ] `services.msc` shows **Yumi VPN tunnel** running, startup type Automatic.
- [ ] The SID in the service's command line is the signed-in user's, not the administrator's who
      answered the elevation prompt. Compare `whoami /user` against
      `(Get-ItemProperty HKLM:\SYSTEM\CurrentControlSet\Services\YumiTunnel).ImagePath`. Worth
      testing by installing with a *separate* administrator account: that is the case this is for.
- [ ] With the service deliberately registered to another SID
      (`yumi-service.exe --install --owner <other>`), the connect screen says the service belongs
      to another account — not that it is missing — and its button re-registers it after one
      elevation prompt.
- [ ] The application opens with no servers and says so, offering the phone transfer.
- [ ] The connect button is inert while there is no server. It must not be pressable.
- [ ] Close the window with the ✕. It goes to the tray and keeps running; the tray icon reopens it.
- [ ] Exit from the tray menu. The window and the process both go.

## Subscriptions

- [ ] Paste a subscription link with **+**. Servers appear, and the card shows quota and expiry if
      the provider publishes them.
- [ ] Refresh it. The server list is replaced, and the server you had selected stays selected.
- [ ] A provider that answers with rubbish must leave the old subscription intact and say the
      refresh failed — never empty the list.
- [ ] Delete a subscription while its server is the connected one: the tunnel comes down first.

## Transfer from the phone

Both devices on the same network. The network profile may be Private or Public — the rule covers
both, and testing on a Public one is worth doing because that is what a laptop gets by default.

- [ ] **С телефона / From phone** shows a QR. Scanning it in the Android app offers the
      subscription list.
- [ ] The chosen subscription lands on the desktop and refreshes itself.
- [ ] The QR expires after five minutes and a second scan of a used code is refused.
- [ ] With the firewall rule removed, the transfer fails and says so rather than hanging.

## The tunnel

- [ ] Connect. The headline turns accented, the timer runs, the figure fills, and the rates under
      it move.
- [ ] `ipconfig` shows the Wintun adapter; a browser reports the server's country.
- [ ] Check for leaks: the address on <https://browserleaks.com/ip> matches the exit country, and
      the DNS shown there is the resolver from settings — not your provider's.
- [ ] Disconnect. The adapter goes, and normal traffic resumes without a reconnect of anything else.
- [ ] Connect, then kill `yumi-service.exe` from Task Manager. The application must notice and say
      the service is unavailable, not sit on a stale "connected".
- [ ] Switch servers while connected: the tunnel moves without an intermediate disconnected state
      visible to the browser.
- [ ] Reboot with the tunnel connected. Nothing should be tunnelling after the reboot until you
      press connect.

## Beside another VPN client

The case this is for: a second TUN client on the same machine, which does not have to be running
to break things. Clash Verge, Hiddify and sing-box all leave their adapter behind when their
tunnel is switched off, and it keeps the resolver and a default route.

- [ ] With another client's TUN adapter present, connect. Within a few seconds the headline still
      reads connected, and under it, in the failure colour, a line saying the tunnel is up but the
      machine is not using it. Nothing is disconnected on the user's behalf.
- [ ] Which of the three lines appears is the diagnosis, and they must not be interchangeable: a
      fake-ip client answering DNS says so; a machine routing around the adapter says that; a
      server that has stopped answering says that.
- [ ] Close the other client, remove its adapter, `ipconfig /flushdns`, reconnect: the line goes.
- [ ] The check runs once per connection. Watch the connect screen for a minute with the tunnel
      healthy — it must not repeat, and the traffic counters must not show it doing so.

## The journal

- [ ] Settings → log level, then connect. `%ProgramData%\Yumi Desktop\worker.log` records at the
      chosen level: **Error** leaves a failing connection with one line, **Info** narrates it.
- [ ] Connect to a server that is deliberately unreachable. The reason is in the journal in the
      core's own words, and no longer only in the app.
- [ ] The file is truncated, not grown without bound, once it passes a megabyte.

## Split tunnelling

- [ ] **Everything except selected**: add a browser's `.exe`, connect, and that browser goes direct
      while everything else goes through.
- [ ] **Only selected**: the opposite, and the rest of the machine keeps working normally.
- [ ] Add a domain. Subdomains follow it. A site on the same IP but a different name does not.
- [ ] Add a subnet in CIDR form. An invalid one is refused with the field marked, not saved.
- [ ] Save rules while connected: the tunnel reconnects itself once and comes back up.

## Speed test

- [ ] Idle, the dial rests at zero with its scale readable.
- [ ] Run it with the tunnel down: the subtitle says the measurement is of the PC's own link.
- [ ] Run it through the tunnel: the subtitle names the server, and the figure is plausibly lower.
- [ ] Stop it mid-run. It stops within a second and does not leave the dial spinning.

## Appearance

- [ ] Each of the seven palettes repaints the whole window — background, cards, rail — not just the
      accent.
- [ ] Light and dark, and system-follows-Windows.
- [ ] The title bar follows the theme, in both directions, without restarting the app.
- [ ] AMOLED collapses the surfaces onto black and keeps the palette's hue.
- [ ] Drag the window narrow: the rail's labels leave and the icons centre, smoothly, and the
      whole app stays usable at the minimum size.
- [ ] Server names show their country flags, and the ⚡ and ⭐ providers decorate names with are
      coloured — all of them, consistently.
- [ ] Switch to English. Every screen changes, including units and relative times.

## What must never happen

- [ ] The tunnel comes up without being asked — on launch, on wake, on a subscription refresh.
- [ ] A server is selected by anything other than the user.
- [ ] The application makes a network request naming a server to anything but that server.
