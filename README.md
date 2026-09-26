# DormPanel

DormPanel is a lightweight always-on dashboard and smart-home control panel for
the **Xiaomi Redmi XiaoAI Touchscreen Speaker Pro 8 (X08E)**.

It is designed for a fixed landscape 1280×800 bedside/desk screen on Android 9,
not as a general tablet launcher.

## Supported hardware

| Item | Value |
| --- | --- |
| Primary device | Xiaomi Redmi XiaoAI Touchscreen Speaker Pro 8 (X08E) |
| OS | Android 9 / API 28 |
| Display | 1280×800 landscape |
| RAM | ~2 GB |

Other Android 9+ landscape devices may work, but only the X08E is validated.

## Install / update

### APK installation

1. Copy `DormPanel-0.1.0.apk` to the device (USB, `adb push`, or any file transfer).
2. Open the APK on the device and confirm installation.
3. If Android asks, grant **Install unknown apps** for the app you are installing from.

### Updating an existing install (important)

Always update in place:

```bash
adb install -r DormPanel-0.1.0.apk
```

or install the new APK over the existing one from the system installer.

| Action | App data |
| --- | --- |
| APK update (`adb install -r` / Android update) | **Kept** — dashboard, HA credentials, relay identity, WebDAV account, schedules, productivity data, settings |
| Uninstall + reinstall | **Not guaranteed** — Android Keystore-backed secrets and app-local data are lost |

Uninstall/reinstall is **not** a supported upgrade path. A previous test incident
confirmed that migration after uninstall is not possible for Keystore-backed state.

### Signing

Release APKs are signed with the project release identity. The public certificate
SHA-256 is printed by `tools/build-release.ps1` at build time and should match the
certificate of any previously installed DormPanel package. If Android reports a
signature conflict, **stop** — do not uninstall the existing app to force the update.

## Home Assistant

DormPanel talks to Home Assistant over WebSocket for live state and control.
REST is only used as a fallback.

### Configure

1. Open **Control Center → Home Assistant**.
2. Enter the HA URL (for example `http://homeassistant.local:8123`) and a long-lived access token.
3. Save. Status should become **CONNECTED**.

### Custom integration (`custom_components/dormpanel`)

Optional, required for timetable and APK relay features:

1. Unpack `DormPanel-HA-0.1.0.zip` into the HA `config` directory so you have
   `config/custom_components/dormpanel/`.
2. Restart Home Assistant.
3. **Settings → Devices & Services → Add Integration → DormPanel**.
4. Create the integration entry (admin user required for relay registration).

Setup also exposes helper entities used by device controls (names are configurable
in DormPanel settings):

- **Brightness** — DormPanel display brightness
- **Volume** — media volume
- **Blackout** — screen blackout switch

### Timetable relay

From the HA DormPanel panel, send an ICS timetable to a registered X08E over the
existing WebSocket connection. The device previews the file and imports it only
after local confirmation.

### APK relay

From the HA DormPanel panel, send an APK to a registered X08E. The device
validates the file, then requires local Install confirmation **and** the Android
system installer confirmation. HA reaches `installed` only after the package is
actually installed.

## WebDAV

One WebDAV account is configured **globally** in Control Center
(URL, username, password). The password is stored encrypted with Android Keystore.

Timetable page owns the sync bindings:

- select a specific ICS file, or
- select a folder and always use the newest ICS in it;
- hourly automatic sync;
- **Sync now** for manual sync.

The Apps page owns one-shot APK browsing/install from the same WebDAV account.

## Device controls

| Control | Behavior |
| --- | --- |
| System brightness | Writes Android `screen_brightness`. Requires **WRITE_SETTINGS** special access and Manual brightness mode. |
| DormPanel brightness | App-local overlay brightness; does not change the system value. |
| Follow System | DormPanel brightness tracks the system brightness. |
| Keep screen awake | Holds `screen_off_timeout` while enabled. |
| Blackout | Covers the screen with a black surface and blocks touch. |

**Blackout is not a true LCD/backlight power-off.** The panel stays powered;
only the UI is covered. Recover by tapping the blackout surface (or the
configured gesture) to restore the UI.

### WRITE_SETTINGS

System brightness writes need the **Modify system settings** special access.
Grant it once from Control Center when prompted. Without it, system-brightness
HA commands cannot be applied.

## Startup and system coexistence

- **Boot start is opt-in.** Enable it in Control Center if you want DormPanel
  after reboot. It uses the standard `BOOT_COMPLETED` broadcast — no root, no
  persistent service.
- On X08E, `BOOT_COMPLETED` may arrive noticeably after `sys.boot_completed`.
  Startup is therefore later than on typical Android devices.
- **Open MIUI Home** (Control Center) returns to the Xiaomi launcher.
- DormPanel is **not** a Home launcher. It never replaces MIUI Home, XiaoAI,
  Bluetooth Mesh gateway, or the system alarm app.

## APK install sources

From the Apps page you can install an APK from:

| Source | Notes |
| --- | --- |
| Local (SAF) | Pick a `.apk` from device storage |
| LAN temporary upload | Short-lived private URL; upload from a phone/PC on the same network |
| WebDAV | Browse the global WebDAV account |
| Home Assistant | Relay through the HA integration |

Common rules:

- **256 MiB** size limit;
- local DormPanel confirmation **and** Android system confirmation;
- **no silent or root installation**;
- single APK only — **no** split APK / XAPK / APKS.

A staged APK is validated (package metadata, signing certificate) before the
Install button is enabled. An update with a different signing certificate is
blocked so the existing app and its data stay untouched.

If DormPanel is killed while the Android installer is open, the session is
reconciled on the next start from persisted non-secret metadata. A lost callback
is never reported as a successful install without platform proof.

## Dashboard

The home screen is an editable card grid (Mijia-style):

- add / delete / drag cards;
- resize cards across preset sizes;
- cards: Clock, Weather, HA lights / sensors / scenes, Todo, Memo, Timer,
  Calendar, Timetable, Apps, and more.

Swipe edges open the secondary pages (Apps, Control Center, Schedule, Home
Control). Exact page targets are configurable.

## Backup and sensitive state

The following are **excluded from Android backup and device transfer**:

- HA access token (`ha_credentials`)
- HA relay installation identity/secret (`ha_relay_identity`)
- WebDAV encrypted password (`webdav_credentials`)
- pending installer session metadata (`pending_install`)

Staged APK cache and LAN capability tokens live in cache/ephemeral storage and
are not backed up.

Ordinary non-secret user state (layout, schedules, todos, non-secret preferences)
remains backup-eligible.

## Known limitations

- X08E public Android **media mute is unavailable** (no reliable `MUTE` stream API
  on this build). Volume control works.
- **True display power-off is not implemented.** Blackout only covers the UI.
- System-brightness HA writes require **WRITE_SETTINGS** and Manual brightness mode.
- Boot startup may occur noticeably after `sys.boot_completed` because X08E
  delivers `BOOT_COMPLETED` late.
- **Split APK bundles (XAPK/APKS) are unsupported.** Single-APK only.
- Only physically verified coexistence claims are documented as proven. XiaoAI
  and Bluetooth Mesh end-to-end checks are listed in the release notes with their
  actual verification status.

## Build

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
.\tools\test-x08e.ps1          # physical X08E suite (device required)
.\tools\build-release.ps1      # signed release APK + HA archive
```

Release signing is configured through environment variables or Gradle properties
(`DORMPANEL_RELEASE_KEYSTORE`, `DORMPANEL_RELEASE_KEY_ALIAS`,
`DORMPANEL_RELEASE_STORE_PASSWORD`, `DORMPANEL_RELEASE_KEY_PASSWORD`). Secret
values are never committed. `tools/build-release.ps1` refuses an official release
build when signing inputs are missing.

## Repository layout

| Path | Content |
| --- | --- |
| `app/` | Android application (Kotlin) |
| `homeassistant/custom_components/dormpanel/` | HA custom integration |
| `homeassistant/tests/` | HA integration tests |
| `tools/` | Windows helper scripts |
| `docs/` | Design and PR documentation |
