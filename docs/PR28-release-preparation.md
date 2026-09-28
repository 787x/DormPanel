# PR28 — 0.1.1 release preparation

Prepared on 2026-09-29 (Asia/Shanghai) from current `main`, PR27 merge commit
`d93eac5b3c2aa2b29337753bf8596ed6b5569689`. This PR updates release metadata,
tooling, README, and [0.1.1 release notes](RELEASE-0.1.1.md). The feature set is
frozen. No product behavior, dependency, permission, service, polling, or database
migration is added. The only test change aligns an overnight ICS fixture's editor
clock with its explicit Asia/Shanghai projection zone.

## Candidate artifacts actually built and verified

`tools/build-release.ps1` completed with the existing approved keystore, after
signing inputs were supplied through process environment variables. No signing
material or password is committed. Artifacts remain in ignored `artifacts/release/`;
they have not been uploaded or published.

The final candidate was rebuilt from clean source commit
`0abc0a4b90a21f2c4d92c1f79da9df73206c981d`. The subsequent verification-document
commit only records these results; application, HA integration, test, and release
tooling sources are identical. AGP's `extractReleaseVersionControlInfo` records
that source revision; rebuilding after a commit can change the APK hash even when
application code is unchanged. The hashes below identify the final local files,
which were independently reverified after that committed-source build.

| Metadata | Verified result |
| --- | --- |
| APK | `DormPanel-0.1.1.apk` |
| Package | `com.dormpanel.app` |
| versionCode / versionName | `3` / `0.1.1` |
| minSdk / targetSdk | `28` / `37` (unchanged) |
| Debuggable | **false** (`aapt2 dump badging` has no `application-debuggable`) |
| Signer certificate SHA-256 | `32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7` |
| APK SHA-256 | `5eb359f1ea39667ddff1b68650d8ead7f66d64dcf046982356941d2c42bd385b` |
| APK size | 15,888,920 bytes |
| APK sidecar | `DormPanel-0.1.1.apk.sha256` (hash and filename independently checked) |
| HA archive | `DormPanel-HA-0.1.1.zip` |
| HA integration version | `0.1.1` |
| HA ZIP SHA-256 | `3474e6d423cb5b7f112e7b0c192af0f161e5765d69a661159c70a33812b7652c` |
| HA ZIP size | 11,756 bytes |

Android build-tools **37.0.0** independently verified the copied official APK:

```powershell
$buildTools = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\37.0.0'
& "$buildTools\apksigner.bat" verify --verbose --print-certs --min-sdk-version 28 artifacts/release/DormPanel-0.1.1.apk
& "$buildTools\aapt2.exe" dump badging artifacts/release/DormPanel-0.1.1.apk
Get-FileHash artifacts/release/DormPanel-0.1.1.apk, artifacts/release/DormPanel-HA-0.1.1.zip -Algorithm SHA256
```

`apksigner` reports **Verifies**, APK Signature Scheme **v2**, and exactly **one**
signer with the approved certificate. The ZIP CRC check passed; all **10** archive
entries are byte-identical to the selected repository source files, use the
`custom_components/dormpanel/` prefix, and exclude caches, transfer data, and
credential/secret/token files. Its manifest version is 0.1.1. These hashes identify
this local candidate; the ZIP file set is deterministic but ZIP timestamps are not
normalized, so a later build's archive hash can differ.

## Release tooling guards

Release constants coherently identify 0.1.1 / versionCode 3. APK metadata checks
read the package line. Missing `aapt2` now fails instead of skipping verification;
HA manifest version and nonempty expected artifacts are also checked. Certificate
parsing accepts both traditional `Signer #1` and build-tools 37's `V2 Signer`
labels, requires exactly one certificate digest, and preserves the approved
fingerprint. Existing packaging exclusions and secret handling remain intact.

Executed local guard tests using mock Gradle/APK/SDK inputs, separate from the
real artifact verification above:

- Missing signing passwords: actual script exited **1** before any build.
- Valid mock metadata: expected 0.1.1 filenames, sidecar, and filtered ZIP passed.
- Wrong signer, multiple signers, wrong package, wrong versionCode, wrong
  versionName, debuggable APK, missing `aapt2`, no APK, androidTest-only APK,
  wrong HA version, and missing HA manifest: each exited **1**.
- Mock ZIP excluded `__pycache__`, `.storage`, transfer directories, `.pyc`,
  unsupported extensions, and credential/secret/token filenames.
- PowerShell parsing and `git diff --check` passed.

The local harness/logs are retained under ignored `build/pr28/`. Mock checks do
not establish cryptographic signing; the real `apksigner` run above does.

## Automated and emulator verification

| Executed check | Result |
| --- | --- |
| `gradlew.bat assembleDebug testDebugUnitTest lintDebug` | Passed; lint zero errors, 100 existing warnings |
| `gradlew.bat testDebugUnitTest --rerun-tasks` | 282 tests actually executed, zero failures/errors/skips |
| `python -m unittest discover -s homeassistant/tests -v` | 13 tests passed |
| `node --check homeassistant/custom_components/dormpanel/frontend/panel.js` | Passed |
| `git diff --check` | Passed |
| Full isolated emulator instrumentation, before fixture correction | 98 tests, 2 failures, 0 ignored |
| Applicable isolated emulator instrumentation after fixture correction | **97 tests passed**, zero failures/errors/ignored, Gradle exit 0 (10m 23s) |

The connected device was **emulator-5554**, Android 9 / **API 28**, physical
display size **1280×800**, density **160 dpi**, model
`Android SDK built for x86_64`. Instrumentation targets
`com.dormpanel.app.testbed`, not the daily package. The signed candidate was not
installed by these tests.

Commands:

```powershell
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat connectedX08eTestAndroidTest '-Pdormpanel.testBuildType=x08eTest' '-Pandroid.experimental.androidTest.builtin_test_platform=true'
.\gradlew.bat connectedX08eTestAndroidTest '-Pdormpanel.testBuildType=x08eTest' '-Pandroid.experimental.androidTest.builtin_test_platform=true' '-Pandroid.testInstrumentationRunnerArguments.notClass=com.dormpanel.app.DeviceControlAndroidTest#mediaUsesPhysicalMusicStreamAndRestoresIt'
```

Initial failures and their treatment:

1. `DeviceControlAndroidTest#mediaUsesPhysicalMusicStreamAndRestoresIt` asserts
   `muteAvailable == false`, as required by physical X08E behavior. The generic
   emulator supports media mute; the production capability is intentionally
   `Build.MODEL != "X08E"`. This is a device-capability-only failure. The assertion
   and product code remain unchanged; only this exact method is excluded from
   the applicable emulator command. It remains required on physical X08E.
2. `ScheduleImportAndroidTest#overnightIcsDetailAllowsNonTimeEditWithoutChangingInstants`
   used a Shanghai overnight occurrence/projection with a device-default UTC
   editor clock. The mismatched test setup yielded different baseline clock
   ranges and rejected the save. The test now supplies a clock in the fixture's
   projection zone, matching the real UI's common clock. All instant, room,
   teacher, user-note, and immutable-source assertions remain unchanged. No
   product change is made or failure excluded for this case.

The initial and final XML test lists were compared: the only removed method is
the X08E mute test named above. The overnight case passes in the final report,
and the generated instrumentation manifest targets `com.dormpanel.app.testbed`.

The suite covers Room **v2 → v3** preservation and the **v1 → v2 → v3** chain,
CSV/profile import and sync, class/day edits, HA/WebDAV phone-entry UI, and existing
dashboard/control/import regressions. The opt-in `RealLightVerificationTest`
returns without real-device arguments; no live household-device operation is
claimed. The granted `WRITE_SETTINGS` brightness test similarly returns without
special access; this run does not establish granted OEM brightness behavior.

Local logs/reports: `build/pr28/android-checks.log`, `unit-tests-rerun.log`,
`android-checks-final.log`, `ha-tests.log`, `emulator-full.log`,
`emulator-full-results.xml`, `emulator-applicable.log`,
`emulator-applicable-results.xml`, `release-build.log`,
`release-build-committed.log`, `release-metadata.json`,
`release-gates.log`, `apksigner-verify.log`, and `aapt2-badging.log`.

## Pending X08E final acceptance

The daily X08E is unavailable. No candidate install, in-place signed upgrade,
live HA/WebDAV acceptance, Xiaomi coexistence check, physical UI walkthrough, or
hardware soak was performed for 0.1.1. These remain final acceptance work;
emulator/mocked checks are not a substitute. A normal APK update preserves app
data; uninstall/reinstall remains unsupported for Keystore-backed migration.

Historical 0.1.0 notes/evidence, the immutable `v0.1.0` tag, and published assets
are unchanged. **No tag, GitHub Release, or Release asset was created.** PR28 is
release preparation only.
