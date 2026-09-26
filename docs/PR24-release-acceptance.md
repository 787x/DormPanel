# DormPanel 0.1.0 — Final Release Acceptance

**Status:** physical/live acceptance completed 2026-09-26 on daily X08E.
**Device:** Xiaomi Redmi XiaoAI Touchscreen Speaker Pro 8 (X08E), Android 9 / API 28, 1280×800.

**Acceptance candidate commit:** `b91f7c9d9b6df00e9ebb5b1b1ce9e85561044fe3`
(`Sync control center Boolean helpers with Home Assistant (#24)`)

Evidence classes used below:

- **[LIVE]** — executed on the physical X08E and/or live Home Assistant in this acceptance session.
- **[AUTO]** — automated unit / Android instrumentation / HA Python tests (not a substitute for LIVE).
- **[UNVERIFIED]** — not performed or not fully proven; must not be read as pass.

---

## 1. Signing identity

Procedure: pull installed `base.apk` → `apksigner verify --print-certs`.
Release keystore at `~/.dormpanel/dormpanel-release.jks` (alias `androiddebugkey`).
No private key material or passwords are recorded in this document.

| Item | Result | Class |
| --- | --- | --- |
| Installed daily certificate SHA-256 | `32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7` (DN: C=US, O=Android, CN=Android Debug) | LIVE |
| Release APK certificate SHA-256 | same `32d68b5c…17f7` | LIVE |
| Approved identity | `32d68b5c…17f7` — **match** | LIVE |
| In-place `adb install -r` with this key | **Success**, no uninstall, `firstInstallTime` preserved | LIVE |
| Different new key would update daily package | **No** — would require uninstall (Android signing rule) | AUTO/prior |

Decision (user-approved earlier): the former debug keypair is the long-term 0.1.0
release identity (`~/.dormpanel/dormpanel-release.jks`). Losing it forces
uninstall for future updates.

---

## 2. Installer recovery (implementation — corrected)

Stale wording that “success = versionCode changed” is **wrong** and is not what
the code does. Current design (`PendingInstallSession.kt`,
`PreferencesPendingInstallStore.kt`, `ApkInstallController.kt`,
`InstallRecoveryLogic`):

1. **Success is never inferred from versionCode alone.** `Installed` requires
   installer `STATUS_SUCCESS` **or** conservative package proof: pre-install
   baseline versionCode + signing SHA-256 + `lastUpdateTime` change consistent
   with the candidate (see `PackageProof`). VersionCode equality alone is
   insufficient (reinstall of the same version).
2. **Pre-install baseline / signing / lastUpdateTime** are captured and stored
   in the non-secret `PendingInstallRecord` **before** `PackageInstaller`
   commit. Missing fingerprint ⇒ cannot claim installed.
3. **Stale PackageInstaller session results are rejected.** A session id that
   no longer matches the pending record, or a status for an aborted/replaced
   session, does not fabricate `Installed`.
4. **Persistence failure prevents `session.commit`.** If the pending record
   cannot be written, commit is not performed (no orphan installer session
   without recovery metadata).
5. **Recovered HA APK terminals** use the recovered-terminal relay path:
   `HaScheduleRelay.resolveRecovered` / ack **after** `register()`, not via a
   live active-APK preview callback. `Unresolved` is never reported as
   `installed`.

`[AUTO]` `PendingInstallRecoveryTest` covers prepare → persist → destroy
callback → receiver/reconcile for success, abort⇒`dismissed`, package-proof
success, unresolved when session gone without proof, terminal stays terminal.

Backup rules exclude `pending_install.xml`. Secrets (HA device secret, signed
URLs, WebDAV password, APK bytes) are never persisted there.

---

## 3. Build / relay version identity

`[LIVE]` HA relay registration for the daily X08E after this build:

| Field | Value |
| --- | --- |
| installation_id | `ffddd277-c4f0-4dfc-9b6f-a82814e4f883` |
| display_name | DormPanel X08E |
| app_version | **0.1.0** (from `PackageManager.versionName`, not literal `"1.0"`) |
| capabilities | **schedule_relay_v1**, **apk_install_v1** |

HA integration `manifest.json` remains `0.1.0`. A stale older registration
(`71a66d6b…`, app_version `1.0`, schedule-only) may remain in HA storage and is
not the live device registration.

---

## 4. Release artifacts (acceptance candidate `b91f7c9`)

Built by `.\tools\build-release.ps1` from exact `main` = `b91f7c9d9b6df00e9ebb5b1b1ce9e85561044fe3`.

| Item | Value |
| --- | --- |
| APK | `artifacts/release/DormPanel-0.1.0.apk` |
| APK size | 15 611 556 bytes |
| APK SHA-256 | `d7b0ed09807f4cc505ef4a28aa01e6f539784faa02711526b23fbd497d59d7de` |
| Signer SHA-256 | `32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7` |
| HA ZIP | `artifacts/release/DormPanel-HA-0.1.0.zip` |
| HA ZIP SHA-256 | `b21f01bd665a894e8be11684e767718d73ff6831219914b3c0e30310100ba9a1` |

`[LIVE]` APK checks (`aapt2 dump badging`, `apksigner verify --print-certs`):

- `applicationId` = `com.dormpanel.app`
- `versionCode=2` `versionName=0.1.0`
- **not** debuggable
- permissions only: INTERNET, MODIFY_AUDIO_SETTINGS, WRITE_SETTINGS,
  RECEIVE_BOOT_COMPLETED, REQUEST_INSTALL_PACKAGES,
  DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
- MAIN + LAUNCHER; **no CATEGORY_HOME**
- no testbed package/content in artifact
- `git diff --check` exit 0; tree clean at candidate

`[AUTO]` on same commit: `.\tools\build-release.ps1` (includes
`testDebugUnitTest`), `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug`
BUILD SUCCESSFUL.

HA ZIP contents: `custom_components/dormpanel/{__init__,config_flow,const,http,relay,ws}.py`,
`manifest.json`, `strings.json`, `frontend/panel.js`, `translations/en.json` only.

---

## 5. In-place upgrade on the daily install **[LIVE]**

| Check | Result |
| --- | --- |
| `adb install -r artifacts/release/DormPanel-0.1.0.apk` | **Success** |
| Uninstall required? | **No** |
| `firstInstallTime` | **2026-09-24 22:33:21 unchanged** |
| `lastUpdateTime` | 2026-09-26 14:29:08 (advanced) |
| `dataDir` | `/data/user/0/com.dormpanel.app` |
| UI after update | dashboard layout, weather, Yeelight card, HA CONNECTED, WebDAV Configured, timetable 175 classes |

Byte-level `run-as` compare is not possible on the non-debuggable release;
preservation shown by live UI/state (same as prior PR24 note).

---

## 6. Automated / mock coverage **[AUTO]** (not physical proof)

| Command | Result |
| --- | --- |
| `.\tools\build-release.ps1` | BUILD SUCCESSFUL (unit tests + assembleRelease + artifact checks) |
| `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug` | BUILD SUCCESSFUL |
| `git diff --check` | exit 0 |
| `PendingInstallRecoveryTest` | included in `testDebugUnitTest` |
| `HaProtocolTest` (helpers, dependency order, no-echo snapshot) | unit |
| HA Python `python -m unittest discover -s homeassistant\tests` | prior PR24: 6 tests OK (relay registration, terminals, limits) |

Mock/fake HA tests are **not** counted as live helper or APK E2E proof.

---

## 7. PR24 four Control Center helpers **[LIVE]**

Temporary real HA `input_boolean` helpers (HA 2026.9.3) + one `input_number`
for the dependency scenario, bound via DormPanel HA settings UI, then bidirectional tests on X08E.

Helpers used:

- `input_boolean.dormpanel_auto_brightness`
- `input_boolean.dormpanel_follow_system`
- `input_boolean.dormpanel_keep_awake`
- `input_boolean.dormpanel_start_after_boot`
- `input_number.dormpanel_system_brightness` (dependency scenario only)

### Keep screen awake **[LIVE]**

| Direction | Result |
| --- | --- |
| HA ON/OFF → CC checkbox | matches |
| CC ON/OFF → HA helper | matches |
| `FLAG_KEEP_SCREEN_ON` | present when ON, absent when OFF |
| `screen_off_timeout` | **2147483647 unchanged** |

### Start after boot **[LIVE]**

| Direction | Result |
| --- | --- |
| HA ON/OFF → CC `Start DormPanel after boot · ON/OFF` | matches |
| CC toggle → HA helper | matches |
| Remotely launching DormPanel while not running | **not observed** (helper change only updates preference) |

Persistence/synchronization verified. Reboot `BootReceiver` path not re-run
(PR22 already covered it; no evidence that path changed).

### Follow System **[LIVE]**

| Direction | Result |
| --- | --- |
| HA ON/OFF → Follow system checkbox | matches |
| CC toggle → HA helper | matches |
| No command loop | states stable after settle |

### Automatic system brightness — dependency scenario **[LIVE]**

1. Device put in Automatic (`screen_brightness_mode=1`), HA helper ON → CC checked, “System · Automatic”.
2. From HA: Automatic **OFF** + System brightness **40%** (`input_number/set_value`).
   - Android `screen_brightness_mode` → **0 (Manual)** (via `settings get`)
   - `screen_brightness` → **101/255 ≈ 39.6% (40%)**
   - UI “System · Manual · 40%”
   - recheck 3s later: helpers + Android stable — **no echo loop**
3. From HA: Automatic **ON** → mode=1, checkbox ON.
4. CC Automatic OFF/ON → helper + Android mode track.

`WRITE_SETTINGS` appop = **allow**. Apply actually changed Android settings.
The product does not claim success when the write fails; that failure path was
not forced here (permission was granted).

---

## 8. Live HA APK relay E2E **[LIVE]**

Final HA integration deployed to the live instance before this run
(older live `http.py` rejected `kind=apk`; after deploy + restart, upload worked).

Disposable APK: `com.dormpanel.acceptance.disposable` 1.0 (1),
SHA-256 `df8abcfb47db444331fa873dcac8aaf4290b3d6d5a8af107c09f7c555c5d0154`,
signed with approved cert. **Not** DormPanel itself.

### Registration **[LIVE]**

app_version `0.1.0`, capabilities `schedule_relay_v1` + `apk_install_v1` (see §3).

### Success path **[LIVE]** (transfer `8d60ea14…`)

1. HA upload → `pending`
2. DormPanel receives + validates → `preview_ready` (APK preview shows name, package, version, size, source, SHA-256, cert SHA-256)
3. Local DormPanel **Install**
4. Android PackageInstaller confirmation — HA still **`preview_ready` before** system confirm
5. System **安装** → package installed (`firstInstallTime` 2026-09-26 15:15:23)
6. HA → **`installed` only after** Android installation completed
7. UI “Installed successfully. Android installed the APK.”

**The HA transfer did not reach `installed` before Android install completed.**

### Failure terminal **[LIVE]** (transfer `92ebdf1f…`, APK without DEX)

Android `INSTALL_FAILED_INVALID_APK` → HA **`install_failed`** (never `installed`).
Shows terminals are real; no false success.

### Cancel/dismiss path **[LIVE]** (transfer `21f853a1…`)

- Second disposable transfer → preview (showed “Installed: 1.0 (1) · Same version · Signing certificate: matches”)
- **Dismiss**
- HA terminal **`dismissed`**, never `installed`
- `file_available=false` after terminal; **no repeated download/preview** after terminal

### Cleanup **[LIVE]**

`adb uninstall com.dormpanel.acceptance.disposable` → Success.
No staged transfer files left on device. DormPanel daily package untouched.

---

## 9. Xiaomi coexistence **[LIVE]**

### Bluetooth Mesh / Xiaomi BLE light (Yeelight 智能色温灯带2.0)

Normal Xiaomi path: MIUI Home smart-screen device card power control.

| Step | Observation |
| --- | --- |
| Before | Xiaomi UI 已开启 (green power); HA `light.yeelink_cn_2170915002_str1_s_2_light` = on |
| Tap power | Xiaomi UI **已关** + grayscale strip + outline power; HA = **off** |
| Restore | HA = **on** |

Real device/state change on the Xiaomi control surface (not only opening UI / seeing a gateway process).

### XiaoAI

Invoked via Xiaomi launcher assistant path (`DevTest` → “刘德华是谁”) while DormPanel ran.

| Observation | Detail |
| --- | --- |
| Activity | `com.xiaomi.micolauncher/.voiceassistant.base.wakeup.view.BaikeActivityX08` |
| Visible response | Baike title **刘德华** (XiaoAI knowledge card) |
| TTS button | “说句话呗” invoked; **audible audio not recorded** by this workstation |

**[LIVE]** visible XiaoAI response — not process-existence-only.
Wake-word **audible** response **[UNVERIFIED]** (no microphone capture in this session).

Xiaomi services (micolauncher, miot, voip, mqtt) stayed running throughout soak.

---

## 10. WebDAV hourly sync + ≥4 h soak **[LIVE]**

Original WebDAV **Auto sync: Off** (Phase 1). Temporarily enabled for acceptance; restored to **Off** afterwards.

Soak window: **2026-09-26 15:25:42 → 19:27:56 +08:00 (4 h 02 m)**, normal daily
DormPanel UI, display on, not simulated.

### Hourly WebDAV (INTERVAL = 1 h)

| Last synced | Status |
| --- | --- |
| 2026-09-26 **15:25** | Up to date (first after enable; prior 2026-09-25 23:19) |
| 2026-09-26 **17:25** | Up to date |
| 2026-09-26 **19:25** | Up to date |

Timetable remained **175 classes** (2026-09-02 → 2027-02-23), binding
`folder · following newest ICS · /课表ics/`, file `课表.ics`.

### Runtime (periodic samples)

| Time | Idle CPU | PSS | Threads |
| --- | --- | --- | --- |
| 15:25 | — | 48 MB | — |
| 15:28 | **0.0%** | 46 MB | — |
| 15:59 | **0.0%** | 56 MB | 28 |
| 17:56 | **0.0%** | 57 MB | 29 |
| 18:23 | **0.0%** | 58 MB | 29 |
| 19:27 | **0.0%** | 58 MB | 30 |

- Local clock advanced (dashboard LOCAL TIME).
- HA stayed connected / weather entities refreshed; **no reconnect/error loop** observed in samples.
- Thread count **26–31**, not continuously increasing.
- PSS **46–58 MB**, no obvious runaway growth.
- Idle CPU **near zero** (`top` 0.0% when idle).
- UI responsive on samples.
- No unexpected Xiaomi-service disruption.
- Host PC rebooted mid-soak; device soak continued (ADB only).

---

## 11. Daily state restore **[LIVE]**

| Item | Status |
| --- | --- |
| WebDAV Auto sync | **Off** (original) |
| Automatic system brightness | ON; Android mode Automatic |
| System brightness | 15 (~6%) |
| Follow system | ON |
| Keep screen awake | ON (`FLAG_KEEP_SCREEN_ON` set) |
| Start after boot | ON |
| Media volume | 1/20 (5%) |
| `screen_off_timeout` | 2147483647 unchanged |
| `firstInstallTime` | **2026-09-24 22:33:21** unchanged |
| HA | CONNECTED; relay identity valid |
| WebDAV | Configured; timetable 175 classes |
| Display | ON |
| `com.dormpanel.app.testbed` | **absent** |
| disposable packages / staged transfer files | **absent** |
| temporary LAN receiver | none |

PR24 HA helper **bindings** remain (product feature); helper **values** restored to ON.

---

## 12. Remaining risks / unverified

1. **[UNVERIFIED]** XiaoAI wake-word **audible** response (visible Baike UI was proven; audio not captured).
2. **[UNVERIFIED]** File-level prefs hash compare after upgrade (`run-as` denied on release).
3. Reboot `BootReceiver` path not re-executed in this session (PR22 + persistence-only for start-after-boot).
4. Release identity is the promoted former debug key — **back up `~/.dormpanel/dormpanel-release.jks`**.
5. HA acceptance helpers remain on the live HA instance (named `DormPanel *`); harmless but not cleaned from HA.
6. If this document is committed after runtime acceptance, rebuild release artifacts from the **new** final `main` SHA and treat those hashes as release provenance (documentation-only commit does not require a new ≥4 h soak).

---

## 13. Artifact provenance note

Runtime acceptance used commit **`b91f7c9d9b6df00e9ebb5b1b1ce9e85561044fe3`**
and APK SHA-256 `d7b0ed09807f4cc505ef4a28aa01e6f539784faa02711526b23fbd497d59d7de`.

If `docs/PR24-release-acceptance.md` is merged after that, re-run
`.\tools\build-release.ps1` from the final `main` commit and publish **those**
APK/HA ZIP hashes as the release artifacts (see task provenance rule).
