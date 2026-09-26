# PR24 — DormPanel 0.1.0 Release Acceptance

Physical date: 2026-09-25. Device: X08E, Android 9 / API 28, 1280×800.

## 1. Release Gate 1 — Signing identity

Procedure: `adb shell pm path com.dormpanel.app` → pull installed `base.apk` →
`apksigner verify --print-certs`. Debug keystore read via `keytool` on
`~/.android/debug.keystore`. No private key or password is recorded here.

| Item | Result |
| --- | --- |
| Installed daily certificate SHA-256 | `32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7` (DN: C=US, O=Android, CN=Android Debug) |
| Machine debug keystore SHA-256 | `32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7` — **matches installed** |
| Debug APK certificate | same fingerprint as installed |
| Intended release keystore on machine before PR24 | none found (`*.jks` / `*.keystore` search) |
| Can debug key update the daily package? | **Yes** (proven by in-place `adb install -r` in section 6) |
| Can a different new key update the daily package? | **No** — Android would require uninstall |

Decision (user-approved, 2026-09-25): promote the existing debug keypair to the
DormPanel release identity. Copied to `~/.dormpanel/dormpanel-release.jks`
(alias `androiddebugkey`) with restricted ACL. This is now the long-term 0.1.0
identity: losing this keystore means future updates cannot keep app data.
It was **not** silently promoted — the product/security choice was asked first.

## 2. Release Gate 2 — Durable installer result

Problem: `ApkInstallResultReceiver` used an in-process static `callback`. If
DormPanel died during the Android confirmation UI, a later broadcast ran in a
fresh process with no callback and could lose the terminal outcome / HA ack.

Design (see `PendingInstallSession.kt`, `PreferencesPendingInstallStore.kt`,
`ApkInstallController.kt`):

- Persist one non-secret `PendingInstallRecord` in SharedPreferences
  `pending_install` **before** `session.commit`: session id, package name,
  staged path + SHA-256, candidate versionCode, source kind, HA transfer id,
  phase, message.
- Never persisted: HA device secret, signed download URL, WebDAV password, APK bytes.
- Receiver always writes the terminal phase from the installer broadcast before
  any in-process callback. Lost callback ⇒ store is the recovery source.
- `InstallRecoveryLogic.recover` is a pure state machine: only an installer
  status or installed-version proof can produce `Installed`. Session gone with
  no proof ⇒ `Unresolved` (no false HA `installed`; prefer rediscovery).
- `ApkInstallController.reconcileRecovered()` runs on ViewModel init;
  recovered HA outcomes are acked via `haRelay.resolve`.
- `close()` no longer deletes in-flight staged files / pending session.
- Backup rules exclude `pending_install.xml`.

Deterministic tests: `PendingInstallRecoveryTest` covers
prepare → persist → destroy callback → receiver/reconcile → recovered for both
success and failure; abort ⇒ `dismissed`; package-proof success; unresolved
when session gone without proof; live session stays pending; terminal stays
terminal. No instrumentation process kill is required (`InMemoryPendingInstallStore`).

## 3. Release Gate 3 — Build version identity

`HaScheduleRelayController` no longer sends literal `"1.0"`. Registration uses
`appVersion()` supplied from `PackageManager.getPackageInfo(...).versionName`
in `DashboardViewModel`. Capability strings (`schedule_relay_v1`,
`apk_install_v1`) remain the protocol negotiation and were not changed.
HA integration `manifest.json` remains `0.1.0`, matching the app release.

## 4. Release artifacts

Built by `tools/build-release.ps1` (refuses official build without signing
inputs; never prints passwords).

| File | Evidence |
| --- | --- |
| `artifacts/release/DormPanel-0.1.0.apk` | 15 590 048 bytes |
| `artifacts/release/DormPanel-0.1.0.apk.sha256` | `a4239d83b2db800ee4e91092fbc993711e464011100339f94eebec3bc3fbfbc3` — verified match |
| `artifacts/release/DormPanel-HA-0.1.0.zip` | only `custom_components/dormpanel/{__init__,config_flow,const,http,relay,ws}.py`, `manifest.json`, `strings.json`, `frontend/panel.js`, `translations/en.json` — no `__pycache__`, `.storage`, credentials, caches |

Release APK checks from `aapt2 dump badging` + `apksigner verify --print-certs`:

- signer SHA-256 = `32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7` (expected)
- `applicationId` = `com.dormpanel.app`
- `versionCode=2` `versionName=0.1.0`
- not debuggable
- permissions: INTERNET, MODIFY_AUDIO_SETTINGS, WRITE_SETTINGS, RECEIVE_BOOT_COMPLETED, REQUEST_INSTALL_PACKAGES (+ AndroidX-generated `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`)
- MAIN + LAUNCHER; **no CATEGORY_HOME**
- `x08eTest` is not part of this artifact

## 5. Automated verification

Executed on 2026-09-25 after the implementation:

| Command | Result |
| --- | --- |
| `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug` | BUILD SUCCESSFUL (includes `PendingInstallRecoveryTest`) |
| `.\tools\test-x08e.ps1` | BUILD SUCCESSFUL; full `com.dormpanel.app.testbed` suite; daily package left installed; instrumentation targets testbed; media volume/brightness/timeout restored (auto-brightness value drift noted by script) |
| `git diff --check` | exit 0 |
| `python -m unittest discover -s homeassistant\tests` | Ran 6 tests, OK (includes relay suite: registration, multi-target pending/claim/ack, APK kind terminals, limits) |
| `node --check homeassistant/custom_components/dormpanel/frontend/panel.js` | syntax OK |
| Focused `PendingInstallRecoveryTest` | covered inside `testDebugUnitTest` |

Release artifact verification is in section 4.

## 6. Physical upgrade and smoke

### In-place upgrade

1. Pre-upgrade non-secret snapshot (`run-as` while still debuggable): SHA-256 of
   dashboard/productivity/schedule DBs and prefs including `ha_credentials`,
   `ha_relay_identity`, `ha_settings`, `webdav_settings`, `webdav_credentials`,
   `startup_policy`, `device_controls`, `appearance` (`artifacts/release-audit/state-before.txt`).
2. `adb install -r artifacts\release\DormPanel-0.1.0.apk` → **Success** (no
   signature conflict; no uninstall).
3. Post-upgrade: `versionCode=2 versionName=0.1.0`; **`firstInstallTime`
   unchanged** (2026-09-24 22:33:21) proving in-place update not reinstall;
   `lastUpdateTime` advanced; `dataDir=/data/user/0/com.dormpanel.app`;
   installed cert still `32d68b5c…17f7`.
4. Byte-level prefs compare after upgrade was not possible (`run-as` denied on
   non-debuggable release). Preservation proven by UI state: dashboard layout,
   HA CONNECTED + live entities, WebDAV Configured, boot-start ON, timetable
   175 imported classes unchanged.

### Smoke (physical UI dump / screenshots)

| Check | Result |
| --- | --- |
| Dashboard / cards | clock 22:50→23:02 advancing; weather card; Yeelight light card |
| HA CONNECTED | yes (Control Center) |
| Real light control | Yeelight Off→On→Off via card; state reflected from HA |
| Home Control | live sensors (fridge/bedroom temp/humidity/battery) |
| Control Center | all sections present |
| System brightness / DormPanel brightness / Follow System | visible and consistent with pre-upgrade |
| Blackout + touch recovery | enter → screen black (brightest pixel 49); tap → UI restored (brightest 242) |
| Media volume | 5%; MUTE UNAVAILABLE ON X08E label shown (known limit) |
| Apps launcher | app list + Install APK |
| Calendar / Timetable | week view classes + Sources dialog (WebDAV binding intact) |
| Open MIUI Home | Xiaomi launcher foreground; return to DormPanel works |
| Responsive after external installer/settings/Home | yes |
| Top-edge vs Control Center | no conflict observed during smoke |

## 7. Live HA APK acceptance

**Not exercised against the live HA panel in this run.** The X08E is HA
CONNECTED (live entities and light control worked), but the HA web panel +
admin session was not available from this workstation (no reachable HA URL /
credentials on the PC side). Automated HA relay tests cover claim/ack/terminals
(`installed` / `install_failed` / `dismissed`) and the APK kind path.

**Remaining risk:** one real E2E HA → APK → install → `installed` (and a
cancel path) should be run on the live HA instance before calling 0.1.0 fully
accepted. This is an unverified release risk, not mock-as-proof.

## 8. WebDAV timetable regression

After PR23 moved account ownership, verified on the daily install:

| Check | Result |
| --- | --- |
| Global WebDAV account | Control Center shows **Configured** |
| Existing ICS binding | still present (175 classes, 2026-09-02 → 2027-02-23) |
| Folder / newest-ICS selection | binding is `folder · following newest ICS` |
| Sync now | **Already up to date.** (manual sync succeeded) |
| Hourly auto-sync soak | not observed in real time (binding Auto sync Off). Scheduler unit/android coverage remains the evidence — physical gap stated. |

## 9. Xiaomi coexistence and soak

| Check | Status |
| --- | --- |
| PR22 native alarm took foreground while DormPanel ran | already physically proved in PR22 |
| XiaoAI audible/visible response | **Not performed** in this PR24 session — unverified |
| Bluetooth Mesh real device command | **Not performed** — unverified (opening Xiaomi panel is not a Mesh command) |
| Runtime soak ≥ 4 h / overnight | **Not performed**. Short live session only (clock advanced 22:50→23:08+, UI responsive, HA connected). Missing soak is a remaining release risk; do not treat this line as soak evidence. |

## 10. Remaining release risks

1. Live HA APK E2E transfer (and cancel path) not run on the real HA panel.
2. XiaoAI and Bluetooth Mesh coexistence not re-verified in PR24.
3. Long-run soak (≥ 4 h / overnight PSS/CPU/thread observation) not done.
4. Hourly WebDAV auto-sync interval not observed live (binding auto-sync Off).
5. Release identity is the promoted former debug key: **back up
   `~/.dormpanel/dormpanel-release.jks`**; loss forces uninstall for future
   updates. Documented in README.
6. Post-upgrade file-level hash compare limited by non-debuggable `run-as`
   (UI/state evidence used instead).
