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

The signed 0.1.1 candidate was physically exercised on X08E on 2026-10-07–08
(Asia/Shanghai). Separately, the unfiltered X08E instrumentation testbed suite
passed **98/98**. The signed, non-debuggable APK was verified in an in-place
upgrade, with live HA CSV/WebDAV, phone entry, Xiaomi coexistence/boot, and a
**>4h** physical observation window. See [PR29 physical acceptance]
(docs/PR29-release-acceptance.md) for artifact identities, evidence types and
observation limits, and [0.1.1 release notes](docs/RELEASE-0.1.1.md) for changes
since 0.1.0. PR29 recorded acceptance evidence only; it did not publish a release.

最新 Android：[v0.1.10](https://github.com/787x/DormPanel/releases/tag/v0.1.10)，包含 **X08E 0.1.10 / code 13、Phone 0.1.9 / code 11**。新增可选课表布局、配色和横划导航，Phone 增加课程管理与两个今日课程桌面小部件。详见[本轮发布说明](docs/RELEASE-0.1.10.md)。本轮仅发布 Android APK；HA 0.1.2、FPK 0.8.1 保持独立版本。应用原位升级不会自动执行 v2→v3 迁移，同步协议格式未变化。

Sync v3 已发布：[Android v0.1.9（历史 Stable）](https://github.com/787x/DormPanel/releases/tag/v0.1.9) 包含 **X08E 0.1.9 / code 12、Phone 0.1.8 / code 10、HA Integration 0.1.2**；[fnOS Schedule Manager 0.8.1](https://github.com/787x/DormPanel/releases/tag/fpk-v0.8.1) 延续 Pre-release 标记。
复用 PR58 最终已验收产物；[RC ACCEPTED 历史验收](docs/pr58-sync-v3-rc.md)与[正式发布说明](docs/RELEASE-sync-v3.md)分别记录。v2→v3 必须显式迁移，不会自动迁移已有日用库；保留旧 v2 RAW、工作区和各端安装 Writer，普通 WebDAV 不自动清理远端历史。
支持现有本地数据原位升级及 Sync v2/v3 共享；v3 采用课程三方合并、Epoch、离线恢复和安全端点切换，仍受每轮/分代预算约束；v2 累计 256 提交限制未解除。已有 v1 共享绑定不提供兼容性保证。
[RC2/PR36 验收记录](docs/PR36-rc2-physical-acceptance.md)、RC1/PR34 历史结论和 0.1.1 产物保留。

Sync v3 的 fnOS 可选安装包见 [0.8.1 FPK](https://github.com/787x/DormPanel/releases/download/fpk-v0.8.1/dormpanel-schedule-manager-0.8.1.fpk)及其 [SHA-256 清单](https://github.com/787x/DormPanel/releases/download/fpk-v0.8.1/SHA256SUMS.txt)。HA [0.1.2 ZIP](https://github.com/787x/DormPanel/releases/download/v0.1.9/DormPanel-HA-0.1.2.zip)保持原版本和能力；已部署同版本无需仅为本次 Sync v3 重复部署。

## Install / update

### APK installation

1. 下载 [X08E 0.1.10 APK](https://github.com/787x/DormPanel/releases/download/v0.1.10/DormPanel-X08E-0.1.10.apk) 或 [Phone 0.1.9 APK](https://github.com/787x/DormPanel/releases/download/v0.1.10/DormPanel-Phone-0.1.9.apk)，按同页 [SHA-256 清单](https://github.com/787x/DormPanel/releases/download/v0.1.10/SHA256SUMS.txt)核对后传到设备。
2. Open the APK on the device and confirm installation.
3. If Android asks, grant **Install unknown apps** for the app you are installing from.

### Updating an existing install (important)

Always update in place:

```bash
adb install -r DormPanel-X08E-0.1.10.apk
# 手机请使用 DormPanel-Phone-0.1.9.apk
```

or install the new APK over the existing one from the system installer.

| Action | App data |
| --- | --- |
| APK update (`adb install -r` / Android update) | **Kept** — dashboard, HA credentials, relay identity, WebDAV account, schedules, productivity data, settings |
| Uninstall + reinstall | **Not guaranteed** — Android Keystore-backed secrets and app-local data are lost |

PR29 physically verified an `adb install -r` upgrade from an approved-signer
**daily 0.1.0-labelled build**, not a byte-identical copy of the canonical
published v0.1.0 APK. The v2→v3 schedule migration retained the original source
and 131 occurrences; exercised dashboard state, preferences, HA/WebDAV
configuration, and relay identity survived. Nonempty daily Todo/Memo retention
was not physically exercised because the baseline had no such data; isolated
persistence/migration tests provide separate evidence.

Uninstall/reinstall is **not** a supported upgrade path. A previous test incident
confirmed that migration after uninstall is not possible for Keystore-backed state.

### Signing

Release APKs are signed with the project release identity. The public certificate
SHA-256 is printed by `tools/build-release.ps1` at build time and should match the
certificate of any previously installed DormPanel package. If Android reports a
signature conflict, **stop** — do not uninstall the existing app to force the update.

## Home Assistant

DormPanel talks to Home Assistant over WebSocket for live entity state and
control. REST is used only for the user-triggered connection diagnostic and
other necessary supplemental calls — not as an entity-state fallback.

### Configure

1. Open **Control Center → Home Assistant**.
2. Enter the HA URL (for example `http://homeassistant.local:8123`) and a long-lived access token.
3. Save. Status should become **CONNECTED**.

HA and WebDAV settings also offer **Fill from phone** on a trusted LAN. Phone
submission fills the open fields; Test and Save remain explicit device actions.
The temporary form uses plain HTTP. HA **Advanced settings** starts collapsed;
**Preferred weather** remains directly visible.

### Custom integration (`custom_components/dormpanel`)

Optional, required for timetable and APK relay features:

1. Unpack `DormPanel-HA-0.1.2.zip` into the HA `config` directory so you have
   `config/custom_components/dormpanel/`.
2. Restart Home Assistant.

   XLS 升级顺序：先将 HA 集成更新到 0.1.2 并重启 HA（当前集成不支持卸载重载），再升级 X08E 到已发布的 0.1.9 / code 12 并重新连接 HA，重新注册 `schedule_xls_v1`。面板刷新后使用 XLS 区域上传湖北大学 BIFF8 `.xls`（最多 1 MiB）；仅支持该能力的设备可选。设备预览并确认后才保存，未知学期先配置日期和作息；旧设备仍可接收原有 ICS/CSV。详见 [PR52 说明](docs/pr52-ha-xls.md)。

3. **Settings → Devices & Services → Add Integration → DormPanel**.
4. Create the integration entry (admin user required for relay registration).

DormPanel does not create these helper entities. It can bind/configure existing
Home Assistant helpers for synchronization (names are chosen in DormPanel
settings):

- **Brightness** — DormPanel display brightness
- **Volume** — media volume
- **Blackout** — screen blackout switch
- **System brightness** — Android system brightness (manual writes)
- **Theme / Opacity** — appearance helpers (`input_select` / `input_number`)
- **Automatic system brightness** — optional `input_boolean`
- **Follow System** — optional `input_boolean`
- **Keep screen awake** — optional `input_boolean`
- **Start after boot** — optional `input_boolean`

The four `input_boolean` bindings mirror the existing Control Center toggles
bidirectionally when configured. Local controls keep working if HA is
disconnected or a helper is missing, disabled, or unavailable.

Notes on the optional Boolean helpers:

- **Automatic system brightness** and **System brightness** require the normal
  Android **WRITE_SETTINGS** conditions (see below). Manual System brightness
  additionally requires Automatic mode OFF.
- **Start after boot** updates the persisted next-boot preference only. It does
  not remotely launch DormPanel while the app is not running.

### Timetable relay

From the HA DormPanel panel, send an ICS or supported Hubei University CSV
timetable to a registered X08E over the existing WebSocket connection. The device
previews the file and imports it only after local confirmation. CSV relay
requires the device's `schedule_csv_v1` capability.

PR29 live-verified a sanitized Hubei CSV over HA `schedule_csv`:
`preview_ready` → `imported` after local confirmation. Native HA light control
was independently observed on the HA server, and live sensor state populated.
A separate live HA APK-relay run was not documented as exercised by PR29.

### APK relay

From the HA DormPanel panel, send an APK to a registered X08E. The device
validates the file, then requires local Install confirmation **and** the Android
system installer confirmation. HA reaches `installed` only after the package is
actually installed.

## WebDAV

One WebDAV account is configured **globally** in Control Center
(URL, username, password). The password is stored encrypted with Android Keystore.

Timetable page owns the sync bindings:

- select a specific ICS or supported CSV file, or
- select a folder and use the newest file of the selected format (ICS or CSV);
- hourly automatic sync;
- **Sync now** for manual sync.

The Apps page owns one-shot APK browsing/install from the same WebDAV account.

PR29 verified authentication with preserved WebDAV credentials, browsing and
importing a human-confirmed sanitized CSV into a separate local source, and
`Sync now` → `Already up to date`. The original remote file was untouched.
Optional profile-sensitive live resync was not exercised.

## Device controls

| Control | Behavior |
| --- | --- |
| System brightness | Writes Android `screen_brightness`. Requires **WRITE_SETTINGS** special access and Manual brightness mode. |
| DormPanel brightness | App-local overlay brightness; does not change the system value. |
| Follow System | DormPanel brightness tracks the system brightness. |
| Keep screen awake | Uses `FLAG_KEEP_SCREEN_ON` while enabled. It does not modify `screen_off_timeout`. |
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
- DormPanel is not a Home launcher and does not replace MIUI Home, XiaoAI,
  the Bluetooth Mesh gateway, or the native alarm app. In signed **0.1.1**
  physical X08E acceptance, **Open MIUI Home** reached the Xiaomi launcher;
  a human confirmed visible XiaoAI response **and heard wake-word audio**,
  plus successful Bluetooth Mesh control with the prior state restored.
  Native Xiaomi/Mesh/Bluetooth services were observed.
- With **Start after boot** already enabled, one normal reboot reached
  DormPanel through the existing boot path after OEM startup ordering.
  **Native alarm firing was not separately tested.** See
  [PR29 acceptance](docs/PR29-release-acceptance.md) for evidence and limits;
  prior 0.1.0 acceptance remains documented in
  [PR24](docs/PR24-release-acceptance.md).

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
Control). Directional page targets are fixed defaults and are not yet user
configurable.

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
- **0.1.1 physical soak has sparse observations.** The X08E device/process
  observation window lasted **4h 10m 40s**, with successful samples at
  09:03:16, 11:48:23, and 13:13:57 (2026-10-08, Asia/Shanghai). Planned
  +1h/+2h metrics are **UNVERIFIED** due to ADB absence; the collector did not
  retain its scheduled final point. Later final checks do not establish four
  successful scheduled checkpoints or continuous telemetry. Unobserved transient
  CPU/reconnection behavior cannot be excluded.
- **0.1.1 upgrade scope:** PR29 used an approved-signer daily 0.1.0-labelled
  baseline, not the canonical published v0.1.0 APK; nonempty daily Todo/Memo
  retention was not physically exercised.
- **Optional live paths not verified by PR29:** native alarm firing,
  profile-sensitive WebDAV live resync, and the opt-in real-light and
  granted-`WRITE_SETTINGS` tests without their prerequisites. JUnit case
  results alone do not establish these optional live paths.
- **Other evidence limits:** exact disposable phone-form bytes were not
  independently compared; timetable-edit restoration was verified
  functionally rather than by byte-level database comparison; earlier
  empty UTP `status -1` results did not reproduce but have unknown root cause;
  UI observation latency was not a frame-rate benchmark. See
  [PR29 acceptance](docs/PR29-release-acceptance.md).

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
