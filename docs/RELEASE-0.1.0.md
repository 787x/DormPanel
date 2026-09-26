# DormPanel 0.1.0 Release Notes

First daily-use release for the Xiaomi Redmi XiaoAI Touchscreen Speaker Pro 8
(X08E), Android 9, 1280×800.

## Highlights

### Editable Dashboard
- Card grid with add, delete, drag-to-reorder, and resize across preset sizes
- Clock / date, weather, HA lights, sensors, scenes
- Local Todo, Memo, and Timer cards
- Layout and card configuration persist across restarts

### Home Assistant
- WebSocket live state and control (REST fallback)
- Lights, sensors, scenes, and Home Control page
- Display brightness, media volume, system brightness, and Blackout helpers
- Timetable relay from the HA panel to the device
- APK relay from the HA panel to the device
- Relay registration reports the real installed DormPanel version

### Calendar and Timetable
- Full calendar / timetable pages
- ICS import (SAF)
- WebDAV transfer with one global account
  - specific ICS file, or folder / newest-ICS selection
  - hourly automatic sync and manual **Sync now**
- LAN temporary upload and QR receive
- HA timetable relay

### Apps
- Installed-app launcher with favorites
- One-shot APK install from local SAF, LAN upload, WebDAV, or Home Assistant
- 256 MiB limit, local confirmation + Android system confirmation
- Signature-mismatch guard protects the existing install
- Durable installer-result recovery across process death (no false “installed”)

### Device controls
- System brightness vs DormPanel brightness
- Follow System mode
- Keep screen awake
- Blackout (UI cover — not true backlight power-off)
- Media volume

### System coexistence
- Opt-in boot start via standard `BOOT_COMPLETED`
- Open MIUI Home / system Home action
- DormPanel is not a Home launcher and coexists with XiaoAI, Bluetooth Mesh
  gateway, and system alarms

## Upgrade notes

Install this release **over** the existing package:

```bash
adb install -r DormPanel-0.1.0.apk
```

App data, HA credentials, relay identity, WebDAV configuration, schedules, and
productivity data are preserved by a normal APK update.

Uninstall/reinstall is **not** a supported upgrade path. Keystore-backed secrets
cannot be migrated after uninstall.

If Android reports a signing incompatibility, stop and keep the current install.

## Artifacts

| File | Purpose |
| --- | --- |
| `DormPanel-0.1.0.apk` | Application release APK (`com.dormpanel.app`, versionCode 2) |
| `DormPanel-0.1.0.apk.sha256` | SHA-256 sidecar |
| `DormPanel-HA-0.1.0.zip` | HA custom integration (`custom_components/dormpanel/...` only) |

## Known limitations

- X08E public Android media mute is unavailable
- True display power-off is not implemented (Blackout is a UI cover)
- System-brightness HA writes require WRITE_SETTINGS and Manual mode
- Boot startup can occur noticeably after `sys.boot_completed` on X08E
- Split APK bundles (XAPK/APKS) are unsupported
- See README for the full list

## Verification status

See `docs/PR24-release-acceptance.md` for the executed automated suite, physical
upgrade result, live HA APK transfer result, WebDAV timetable regression, XiaoAI /
Bluetooth Mesh checks actually performed, and soak duration actually performed.
