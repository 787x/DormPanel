# PR17 X08E physical test isolation

The normal `debug` application ID remains `com.dormpanel.app`. Instrumentation
uses the debuggable `x08eTest` build type, copied from `debug`, with application ID
`com.dormpanel.app.testbed` and visible label **DormPanel Testbed**. The test APK
targets `com.dormpanel.app.testbed`. Both applications can coexist; each package
has its own Android app data, preferences, databases, and UID-scoped keystore.
The testbed has the same ordinary app launcher entry and is not a Home launcher.
Production namespace, application ID, version, and permissions are unchanged.

## Safe physical command

From the repository root in PowerShell:

```powershell
.\tools\test-x08e.ps1
```

The script requires exactly one authorized ADB device, checks X08E, API 28 and
1280×800, confirms the daily package is installed, runs the complete
`connectedX08eTestAndroidTest` suite with the command-line-only AGP built-in
test-platform flag, and checks that the daily package remains installed. The
script selects `x08eTest` as AGP's instrumentation build type for that invocation;
ordinary Gradle invocations keep `debug` and `testDebugUnitTest`. It exits
with Gradle's status when the post-run package check passes. It does not set
`android.injected.androidTest.leaveApksInstalledAfterRun`; Gradle may remove the
testbed after the run. Never use `connectedDebugAndroidTest` against the daily-use
package for routine physical X08E testing.

Run ordinary checks as before:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
git diff --check
```

The existing androidTest sources and isolated Dashboard, Productivity, Schedule,
fake HA, and test WebDAV fixtures are retained. Normal tests stay on Demo/fakes;
real-HA probes still require explicit opt-in arguments. The testbed starts with
its own empty HA and relay settings and cannot read the daily package's identity.

## Incident and verification

In PR16, omission of
`android.injected.androidTest.leaveApksInstalledAfterRun=true` allowed Gradle
cleanup to remove the daily-use app. Its application-local data, HA settings,
and relay identity were lost; Android backup did not restore them. The distinct
testbed application ID removes dependence on remembering that flag to preserve
the daily installation.

Physical verification should record the daily package before and after the full
suite, then open the normal DormPanel app and confirm its existing HA setting
still exists without editing it. The focused instrumentation package assertion
also verifies that the runner's target context is the testbed package.

## PR17 verification on X08E

- `assembleDebug testDebugUnitTest lintDebug`: passed.
- `tools/test-x08e.ps1`: passed the full physical suite, 54 tests, zero failures,
  errors, or skips (`BUILD SUCCESSFUL`, 5m 44s). The built APKs declared IDs
  `com.dormpanel.app` and `com.dormpanel.app.testbed`, and the instrumentation
  manifest targeted `com.dormpanel.app.testbed`.
- Both app IDs were installed concurrently during the suite. Gradle removed the
  testbed and its test APK after the run; the daily package remained installed.
- Before and after the suite, checksums of the daily package's HA settings,
  credentials, and relay identity files matched. Normal DormPanel reopened as
  the resumed Activity, and the same files still existed with matching checksums.
  Their contents were neither read nor changed for this check.
- `git diff --check`: passed.

AGP 9.4.1 creates androidTest tasks for only the selected `testBuildType`. Setting
it globally to `x08eTest` hides the existing `testDebugUnitTest` task. The
repository therefore defaults to `debug`; the helper selects `x08eTest` for
its invocation with `-Pdormpanel.testBuildType=x08eTest`.
