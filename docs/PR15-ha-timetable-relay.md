# Home Assistant timetable relay

Copy `homeassistant/custom_components/dormpanel/` to
`/config/custom_components/dormpanel/` on Home Assistant OS. Restart Home Assistant,
then open **Settings → Devices & services → Add integration → DormPanel**. Open the
new **DormPanel** sidebar page. No add-on, HACS package, `configuration.yaml`
entry, or `/config/www` directory is needed.
On the tested HAOS File Browser add-on, the same HA configuration directory
appears as `/homeassistant`, so the visible destination was
`/homeassistant/custom_components/dormpanel/` (Home Assistant Core 2026.9.3).

Configure DormPanel's normal Home Assistant URL and token on each screen. The
first registration requires an administrator's Home Assistant token. In the
Android Home Assistant settings, set a readable relay screen name and choose
**Save / Reconnect**. The screen should then appear under **Registered screens**.
Relay registration failure is shown in the Android Home Assistant settings;
ordinary HA cards and controls continue to work.

In the HA panel, choose one `.ics` file (up to 1 MiB), select one or more
registered screens, choose 1 hour, 24 hours, or 7 days, and select **Send**.
The screens download the file using a 60-second signed HA path, parse it with
the existing Android ICS importer, and show the normal local preview. Each
screen must confirm its own import. Canceling the preview marks that target
**dismissed**. A successful local database commit marks it **imported**.
Pending transfers are rediscovered when a screen reconnects. The panel shows
per-target state and lets an administrator delete a transfer or obsolete
screen.

HA stores metadata in its private `.storage` facility and temporary binary
files in `/config/.storage/dormpanel_transfers/`. Files are removed at expiry
or when all targets reach a terminal state. Do not copy this directory to
`/config/www`.

WebSocket commands: `dormpanel/register`, `dormpanel/list_pending`,
`dormpanel/claim_transfer`, `dormpanel/ack_transfer`, and administrator-only
state/removal commands. The push event is `dormpanel_transfer_available` and
contains only routing metadata. Timetable bytes travel via authenticated
browser upload and short-lived signed HTTP download.

Local verification: `python -m unittest discover -s homeassistant/tests -v`,
`python -m compileall -q homeassistant/custom_components/dormpanel`, and the
normal Android Gradle verification. A full Home Assistant integration test
requires a Home Assistant development environment or an installed HAOS
instance; the pure store tests do not simulate HA's HTTP/WebSocket stack.

## Verification on 2026-09-24

The integration loaded on HAOS Core 2026.9.3 and its custom panel rendered in
both desktop and 390 px wide browser viewports. A physical X08E registered and
appeared by name. Uploading the WakeUp ICS to that screen over the HA panel
showed the normal Android preview with 7 series and 175 classes. Canceling it
changed the HA target state to `dismissed`. With DormPanel force-stopped, a
second upload remained `pending`; relaunch recovered it through `list_pending`
and opened the preview. Local import then showed `wakeup · 175 classes` and HA
recorded `imported`. HAOS File Browser showed the private transfer directory
empty after both transfers reached terminal states.

The pure relay tests cover per-target isolation, cancellation, expiry blocking
claim/download and removing the temporary file, and startup cleanup. A full
X08E instrumentation run stopped at an existing clock minute-boundary test with
runner status -1 after 12 passing tests; the new relay identity test passed in
a targeted run. An actual HA mobile app, a second physical screen, and waiting
for a live one-hour HAOS expiry were not verified.
