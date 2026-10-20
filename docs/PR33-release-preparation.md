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
- Native artifact tests use real SDK tools: missing APK, the other app APK,
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

## Candidate and emulator inventory

The final committed-source candidate hashes and completed emulator results are
recorded after the frozen-source build. Local logs are under ignored
`artifacts/pr33/`; APKs/sidecars/ZIP remain under ignored `artifacts/release/`.

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
