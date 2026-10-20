# PR33 — 0.1.2-rc.1 release preparation

Prepared from merged PR32 / main `a89b4b49a9b32ab981987334620482664e9eb74b`.
GitHub PR32 reports MERGED; remote main was independently fetched and matched.
No PR32 merge operation was performed. This PR changes release metadata,
phone release signing, build/verification tools and documentation only. It adds
no UI, protocol, algorithm, database migration, permission or background work.

## Release identity and tooling

| App | applicationId | versionCode | versionName | minSdk / targetSdk |
| --- | --- | --- | --- | --- |
| X08E | `com.dormpanel.app` | 4 | `0.1.2-rc.1` | 28 / 37 |
| Phone | `com.dormpanel.timetable` | 2 | `0.1.2-rc.1` | 28 / 37 |

Both apps use the existing approved certificate SHA-256:
`32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7`.
Phone signing reads the same machine-local Gradle properties / environment as
X08E. Neither keystores nor passwords are committed. The certificate's historical
Android Debug subject/alias does not define the APK build type: the approved
keypair is retained, and release APKs must be non-debuggable. No default debug
identity is accepted merely because signing succeeds.

`tools/build-release.ps1` requires signing inputs before building, runs three
modules' JVM tests unless `-SkipTests` is explicit, removes only checked local
release APK output directories, then builds both exact Release targets. It does
not search for a newest APK. Each module APK and each copied final APK is checked
using actual build-tools. SDK selection uses a stable numeric build-tools
version, or explicit `-BuildToolsDirectory`. Paths containing spaces are passed
as separate native arguments. SDK 37's `minSdkVersion` and older `sdkVersion`
badging labels are both supported.

The shared validator requires the exact package/code/name, min/target SDK,
non-debuggable status and exactly the approved certificate; `apksigner verify`
must succeed for minSdk 28. Sidecars contain the actual final bytes' SHA-256 and
exact filename. Staging verification finishes before files are copied into
`artifacts/release/0.1.2-rc.1/`; a final independent directory check follows.
Failed attempts must never be treated as new candidates; a previous successful
candidate may remain, so check exit status and the recorded final hashes.

HA version is independently pinned to its real **0.1.1**. The generated archive
is `DormPanel-HA-0.1.1.zip`, under `custom_components/dormpanel/`; manifest is
unchanged. No Android/HA version-equality requirement remains. Existing source
filtering excludes caches, transfer/storage trees and secret-like filenames.

PowerShell reproduction (supply signing inputs locally, never in Git):

```powershell
# Process environment: DORMPANEL_RELEASE_KEYSTORE, DORMPANEL_RELEASE_KEY_ALIAS,
# DORMPANEL_RELEASE_STORE_PASSWORD, DORMPANEL_RELEASE_KEY_PASSWORD.
pwsh -NoProfile -File tools/build-release.ps1
pwsh -NoProfile -File tools/verify-release.ps1
pwsh -NoProfile -File tools/test-release-validation.ps1
pwsh -NoProfile -File tools/test-release-artifacts.ps1 -PreviousX08eApk artifacts/release/DormPanel-0.1.1.apk
```

`-PreviousX08eApk` is optional historical metadata rejection coverage; it is not
a device upgrade test. Direct unsigned Gradle outputs are not official candidates;
the official entry point refuses missing signing inputs and verifies signatures.
Subsequent RC/final versions must use X08E code >4 and Phone code >2 and preserve
each app's package/signature for in-place upgrades.

## Automated checks actually executed

```powershell
.\gradlew.bat :app:testDebugUnitTest :schedule-core:testDebugUnitTest :phone-app:testDebugUnitTest :app:lintRelease :schedule-core:lintRelease :phone-app:lintRelease --rerun-tasks
.\gradlew.bat :app:assembleX08eTest :app:assembleX08eTestAndroidTest :phone-app:assembleDebug :phone-app:assembleDebugAndroidTest :schedule-core:assembleDebugAndroidTest '-Pdormpanel.testBuildType=x08eTest'
python -m unittest discover -s homeassistant/tests -v
node --check homeassistant/custom_components/dormpanel/frontend/panel.js
git diff --check
```

- JVM: app **198**, schedule-core **110**, zero failures/errors/skips. Phone has
  no independent JVM cases (`NO-SOURCE`); shared logic is covered in core.
- Release Lint: zero errors; existing warnings app **95**, core **22**, phone **11**.
  No baseline or suppressions added.
- HA Python **13 passed**; frontend syntax passed.
- Guard tests: **37 passed**, using mocked Android output to exercise both apps'
  package/code/name, SDK, debuggable, native failure, unsigned, wrong/multiple/
  absent signer, missing/empty APK/tool and sidecar tampering paths. These tests
  are parser/control-flow evidence, not cryptographic proof.
- Native artifact tests: **8 passed** using real SDK tools: missing APK, the other app APK,
  old approved-signed 0.1.1 APK, wrong hash/sidecar filename, repacked unsigned
  APK, and a valid APK signed by a generated disposable wrong identity must fail.
  An isolated fake repository with stale valid APKs and a successful no-op Gradle
  also must fail, proving an old output cannot satisfy a missing new build.
- `build-release.ps1 -SkipTests` without signing passwords exited **1** before
  Gradle. No passwords were invented; the user supplied the local key conditions.

One preliminary combined command used `testDebugUnitTest` together with
`dormpanel.testBuildType=x08eTest`; this AGP configuration has no app debug test
task in that graph and rejected task selection. The commands above separate JVM
and instrumentation builds and passed. No product/test assertions were changed.

## Final local candidates

The final build ran `tools/build-release.ps1` with locally supplied signing
environment against clean source commit
`1f505117429895e40a38109c1f0cf525da53729a`. Both APKs embed that revision in
`META-INF/version-control-info.textproto`. Later commits only record verification
evidence; application, signing and build-tool sources are identical. Rebuilding
after another commit can change APK bytes due to embedded version-control data;
the hashes below identify the selected files, not an earlier build.

All files are in ignored `artifacts/release/0.1.2-rc.1/`:

| File | Bytes | SHA-256 |
| --- | --- | --- |
| `DormPanel-X08E-0.1.2-rc.1.apk` | 16,035,756 | `b6a8e49834771ec5e5b9ffc5435c7def458b4c71432914c01a4ecf313687218a` |
| `DormPanel-Phone-0.1.2-rc.1.apk` | 9,736,703 | `3165af3fa16e32b9633f4c47143e83f1dc7f4fb6d00e145ff1fe9e20fb11a80e` |
| `DormPanel-HA-0.1.1.zip` | 11,756 | `c5bfa82cd60d153c76cdc6b2317b791b2bb228f6a114e828823bcae176171ef7` |

Both APK sidecars were freshly generated from these final copied bytes and
independently compared with Python `hashlib` and exact filenames, in addition
to the PowerShell verifier. Android build-tools **37.0.0** `apksigner` verifies
APK Signature Scheme v2 and exactly one approved certificate for both apps.
`aapt2 dump badging` confirms the table above and non-debuggable release status.
`verify-release.ps1` and native rejection tests were rerun against these selected
files. No APK/sidecar/ZIP or signing material is in Git.

HA ZIP CRC, all **10** archive entries' byte identity with repository sources,
correct `custom_components/dormpanel/` paths and manifest **0.1.1** were checked
independently with Python `zipfile`. The ZIP is byte-identical to the historical
PR29 archive; its hash was still recalculated from this run's final ZIP.

## Emulator evidence

The API 28 emulator `emulator-5554` has physical size **1280×800**, density
**160 dpi**. `emulator-5556` is the API 37 Pixel phone emulator, **1280×2856**,
**480 dpi**. These are emulators, not physical X08E/phone evidence.

Executed runners after installing the freshly built test APKs:

```powershell
adb -s emulator-5554 shell am instrument -w -r com.dormpanel.schedule.core.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell am instrument -w -r com.dormpanel.schedule.core.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -r -e notClass com.dormpanel.app.DeviceControlAndroidTest#mediaUsesPhysicalMusicStreamAndRestoresIt com.dormpanel.app.testbed.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell am instrument -w -r com.dormpanel.timetable.test/androidx.test.runner.AndroidJUnitRunner
```

- Core **26/26 passed on each API**, including Room migration/persistence,
  PR31 phone checkpoint and X08E publisher-state upgrade, interrupted initial
  upload, uncertain receipts, historical recovery and multi-round two-store sync.
- X08E isolated testbed **87/87 passed** (282.408 s), including timetable UI,
  CSV/ICS import, WebDAV, HA relay, Dashboard, controls and productivity. The
  exact physical-X08E-only media mute assertion is excluded, as in PR28's
  applicable emulator run; it remains required on hardware. No product assertion
  was changed. Opt-in live-light and granted WRITE_SETTINGS paths can return
  without prerequisites; these entries do not prove live hardware behavior.
- Phone debug runner **10/10 passed on API 37**. The **final signed release APK**
  was then installed with `adb install -r`; the same **10/10 passed again**
  (81.211 s), including binding, editing/import/source/profile management,
  offline reopening and both conflict choices.
- Against the final API 37 phone release APK, `PhoneBidirectionalRestartTest`
  seed → `am force-stop com.dormpanel.timetable` → verify used one UUID:
  **1/1 in each phase passed**, covering baseline, offline modifications,
  deletion, catalog, pending receipt/capture and remaining dirty state after
  receipt recovery. This is real emulator process interruption, not physical
  device evidence.
- Final signed X08E RC installed over the emulator's approved-signed,
  debuggable 0.1.1-labelled development installation (code 3); `am start -W`
  succeeded and installed metadata shows code **4**, RC name and no DEBUGGABLE
  flag. The 1280×800 dashboard screenshot was inspected. This is an emulator
  smoke test, **not** an upgrade from the canonical signed 0.1.1 on real X08E.
- API 37 release phone empty/offline screen screenshot was inspected: controls,
  dates and status are legible on a black background. No real account was used.

Cross-process runner arguments were `-e class
com.dormpanel.phone.PhoneBidirectionalRestartTest -e pr32Run <UUID> -e
pr32Phase seed|verify`. Full suite counts require `OK (N tests)` and no failure
statuses in logs; ADB command exit status alone is insufficient.

Local logs/screenshots and `final-candidates.json` are retained under ignored
`artifacts/pr33/`, including `release-final.log`, `verify-final.log`,
`native-negative-final.log`, `release-guards.log`, `android-checks.log`,
`ha-tests.log`, `frontend-check.log`, `core-api28.log`, `core-api37.log`,
`x08e-api28.log`, `phone-api37.log`, `phone-release-api37.log` and restart logs.

## Physical acceptance still required

No real X08E or phone acceptance is claimed for this RC. Required checks include:

- Signed X08E **0.1.1 → code 4 RC** in-place installation without uninstall/data
  clear; existing nonempty schedules, Dashboard, Todo/Memo/timer, preferences,
  Keystore accounts and relay identity survive actual migrations.
- Phone independent installation and same-signer future update path; OEM file
  picker, lifecycle, reboot and network-constrained job behavior.
- Real WebDAV strong ETag/conditional writes, offline edits on both devices,
  three-way merge/delete/reset/profile changes, conflicts, uncertain receipts
  and PR31-state recovery across process interruption.
- Live HA 0.1.1 control/relay and original ICS/CSV WebDAV imports; Xiaomi Home,
  audible XiaoAI, alarm and Bluetooth Mesh coexistence, boot and long soak with
  near-zero idle CPU. Mock/emulator results cannot establish those behaviors.

See [RC release notes](RELEASE-0.1.2-rc.1.md) for installation and limitations.
Historical 0.1.1 notes/PR28/PR29 evidence remain unchanged. **No tag, GitHub
Release or Release asset is created; the PR is not merged.**
