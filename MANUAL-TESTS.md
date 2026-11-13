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
- [ ] Settings → Behavior → Connect VPN on launch is off on an existing installation unless enabled.
      Enable it with a selected server, exit and reopen: one connection to that server. With the
      service starting late it waits; after a manual disconnect it stays disconnected. Disable the
      setting and relaunch: no connection. An empty server list never connects.

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

## Split tunnelling

- [ ] **Choose** lists what is running first, then what is installed, with icons. Typing filters it;
      typing a name that is not there offers "Add “name.exe”", and Enter adds it.
- [ ] A program picked as `Telegram.exe` and one typed as `telegram` both match the running
      Telegram.
- [ ] **Everything except selected**: add a browser, connect, and that browser goes direct
      while everything else goes through.
- [ ] **Only selected**: the opposite, and the rest of the machine keeps working normally.
- [ ] Add a domain. Subdomains follow it. A site on the same IP but a different name does not.
- [ ] Add a subnet in CIDR form. An invalid one is refused with the field marked, not saved.
- [ ] Save rules while connected: the tunnel reconnects itself once and comes back up.

## Russian sites and the databases

- [ ] On a fresh install the databases download by themselves within a minute of the app opening;
      the routing page shows their size and age.
- [ ] `%ProgramData%\Yumi Desktop\geo` is writable by administrators and SYSTEM only.
- [ ] **Only selected** with a browser chosen: a Russian site (ya.ru) opens with a Russian address on
      2ip.ru, a foreign one with the server's.
- [ ] YouTube plays through the tunnel with "Russian sites directly" on.
- [ ] A Russian domain typed into the domain list goes through the VPN despite the databases.
- [ ] Unplug the network and press the update button: the old databases stay, the page says why.

## Kill switch

- [ ] On, connect: browsing works. `Get-NetFirewallRule` shows nothing new — the filters are WFP's,
      see `netsh wfp show filters`.
- [ ] Kill `yumi-service.exe --worker` in Task Manager: the browser stops loading, the connect screen
      says the kill switch is holding traffic, and "Lift the block" restores the internet.
- [ ] With "local network directly" on, the router's page and the phone transfer still work while
      the block is up.
- [ ] Stop the service from `services.msc` while blocked: the internet comes back at once.
- [ ] Off, connect, kill the worker: the internet stays up.

## Speed test

- [ ] Idle, the dial rests at zero with its scale readable.
- [ ] Run it with the tunnel down: the subtitle says the measurement is of the PC's own link.
- [ ] Run it through the tunnel: the subtitle names the server, and the figure is plausibly lower.
- [ ] Stop it mid-run. It stops within a second and does not leave the dial spinning.

## Appearance

- [ ] Each of the fifteen palettes repaints the whole window — background, cards, rail — not just the
      accent.
- [ ] Light and dark, and system-follows-Windows.
- [ ] The title bar follows the theme, in both directions, without restarting the app.
- [ ] AMOLED collapses the surfaces onto black and keeps the palette's hue.
- [ ] Drag the window across the screen: the shapes behind the glass lag, swing back and settle.
      With "Live backdrop" off they stay still and the app uses no CPU at rest.
- [ ] Hovering a dock button names it; the page you are on is the accent square.
- [ ] The tunnel indicator is a rotating cookie while connecting/disconnecting, a checked shield
      when connected and a crossed shield when disconnected. A failure or unavailable service
      shows a warning triangle; hover reveals the reason and core details. A failed tunnel whose
      kill switch is holding traffic also explains the block. Clicking opens Connection.
- [ ] Drag the window to its minimum size: everything stays usable.
- [ ] Server names show their country flags, and the ⚡ and ⭐ providers decorate names with are
      coloured — all of them, consistently.
- [ ] Switch to English. Every screen changes, including units and relative times.
- [ ] With 600 or more servers, scroll to the end and back, search for the last server and select it.
      Only nearby cards should be composed; names, flags, latency and selection remain correct.
- [ ] Minimize and restore, then hide in the tray and reopen: continuous animation sleeps and resumes
      without a jump. With the live backdrop off, pointer motion leaves the scene still.

## Shared grid, resizing and ping

- [ ] Switch through all six pages: the separate logo, navigation pill and status circle stay in
      place. In a wide window the logo aligns with the top of the header island, navigation starts
      at the map/content row and the status circle ends at the bottom of the working area.
- [ ] At 680×600, the left half above the full-width map contains the heading and server picker;
      the right half is the connection button. Both end controls match in size and stay clear of
      navigation. Resize in both directions: no centred page drift, empty bands or doubled map.
- [ ] Server search uses the same white glass in the light theme and matches the ping button's
      height. Long names wrap to two lines; protocol/source and latency remain readable.
- [ ] Start a large ping sweep, then press Restart before it finishes. New probes start, old sockets
      close and old unfinished batches do not overwrite the new readings. Repeat while refreshing
      a subscription: ping stays available and subscription progress stays correct.
- [ ] Connect to a chosen server, including with Connect on launch: its latency updates without
      waiting for a manual sweep. Switching the connected server probes the new one; ordinary
      status polls do not keep probing it. Disconnect during a slow probe: commands remain usable.
- [ ] Check the moving pixel map at 100%, 125%, 150% and 200% Windows scaling. After zoom settles,
      grid gaps keep the same thickness rather than crawling. The idle map still fills its figure
      from top to bottom. Test both themes and resize while it moves.
- [ ] Run the UI benchmark on the target Windows GPU and a 120 Hz display; compare active-scenario
      FPS and p95/p99. A static screen, tray or software-rendered cloud run is not a 120 FPS result.

## Wave resume and extended palettes

- [ ] Use the waves visualizer while connected, hide to tray for at least ten minutes, then reopen:
      the wave continues without a burst or an accelerated replay. Repeat after minimizing and
      after putting the display to sleep. Check the first visible frames, not just the settled wave.
- [ ] After a long session, start/stop a large transfer: amplitude/speed change smoothly; phase
      must not jump as if all previous time had been recalculated at the new traffic speed.
- [ ] All 15 named swatches fit the compact settings page by wrapping. Choose Cobalt, Forest,
      Lime, Amber, Orchid, Graphite and Coffee; reopen the app and confirm the selection persists.
- [ ] Check the new palettes in light, dark and AMOLED modes, including text, buttons, charts and
      glass panels. Graphite containers stay neutral in AMOLED rather than turning blue.

## Application updates

- [ ] Settings shows the running version, Check for updates and Update automatically on an installed
      copy. A development copy explains the mode and links to the release when a newer one exists.
- [ ] Newer published Windows release: background download, changing progress, cancellation and retry.
      A draft, older version or release without installer/checksum is never installed.
- [ ] A failed network request leaves a downloaded update intact. A bad/truncated installer is rejected.
- [ ] Restart and update: declining UAC leaves the installation and profiles unchanged. Yumi
      disconnects and exits when the installer is launched; the installer
      waits for the old process, replaces the application/service, and launches the new version with
      the same subscriptions/settings. With automatic updates on, a ready update also installs on exit.
- [ ] With automatic updates off there is no background download or installation on exit; manual
      Check, Download and Restart and update still work.

## What must never happen

- [ ] The tunnel comes up on launch with Connect VPN on launch disabled, or unexpectedly on wake or
      a subscription refresh.
- [ ] A server is selected by anything other than the user.
- [ ] The application makes a network request naming a server to anything but that server.
