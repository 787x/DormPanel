# PR12 — X08E core cards and light controls

Based on merged PR11 (`2ca938d`). The logical grid remains 8×6. Version, permissions,
device density, Xiaomi services and ICS implementation are unchanged.

## Implementation

- Clock, weather and sensor values fit their current bounds from a fresh maximum on
  every measure. API 28 AutoSize retained the compact wrap-content height even after
  disabling/re-enabling it during physical testing; `FittingValueView` avoids that
  state entirely and retains the same card/TextView. Expanded 3×3 and 4×3 clocks cap
  at 104sp; one-row clocks retain their smaller hierarchy.
- Weather can use 72sp in a two-column card. Two-column lights expose brightness at
  two rows and color temperature at three rows. Sensor columns reserve a gap.
- `CardSizePolicy.legalSizes` feeds `addBestFit`: default first, then Manhattan shrink
  distance, descending area, descending width, with row-major placement. Neither
  dimension may exceed the default. Existing cards never move; persistence receives
  the selected size. Explicit policies never invent intermediate sizes.
- One-row Sensor titles ellipsize on one line. Values fit independently of the title.
  Tap opens a live, read-only landscape detail with actual units, partial measurements
  and availability. Edit mode and detach dismiss it and remove subscriptions.
- HA brightness and Kelvin are nullable observations. Raw entities remain untouched.
  `LightControlMemory` owns remembered values/control intent and supplies the same
  projection to dashboard cards, quick controls and Home Control. Fresh ingress
  supersedes memory, while absent/zero brightness does not erase a valid control value.
  A preferences store persists only integers, keyed by normalized endpoint + entity.
- Explicit OFF cancels queued commands and retains memory. ON sends one supported
  brightness/Kelvin payload. OFF Kelvin updates memory without a service request;
  brightness updates turn on with the pending Kelvin. One timer per light preserves
  the existing 180ms bounded coalescing window. Unsent fields are held separately so
  a previous request's incoming state cannot rewrite the next queued drag input.
  Fresh HA observations still replace displayed memory. Demo exposes the same interactions.
- Quick controls use at most 960dp, bounded by screen width minus 48dp margins.
  Numeric labels open whole-number entry, validating 1–100 or the actual Kelvin
  capability range. Inline validation avoids an X08E native error popup that covered
  the confirm button. Sliders retain 48dp touch targets and ACTION_DOWN ownership.

## Physical baseline

ADB serial `28605/A0UH93770`, observed 2026-09-23:

| Query | Observed result |
| --- | --- |
| `wm size` | `Physical size: 1280x800` |
| `wm density` | `Physical density: 160` |
| `settings get system font_scale` | `null` (no explicit setting) |
| `getprop ro.build.version.release` | `9` |
| `getprop ro.build.version.sdk` | `28` |

These values were read, not changed. An approximately 800dp smallest width is the
shorter landscape dimension, not evidence for changing the dashboard grid.

## Verification record

`assembleDebug`, `testDebugUnitTest` and `lintDebug` passed: 133 JVM tests, zero
failures/errors; lint has zero errors and 27 warnings.
The final X08E instrumentation run reports 46 tests, zero failures/errors through
direct AndroidJUnitRunner and the AGP built-in test platform. Two methods return without exercising their conditional
scenario (absent Calculator and the opt-in real-light probe). The other 44 run their
assertions. Real-light probes were run separately as recorded below.

The first direct full-suite run exposed one Clock screenshot assertion failure:
immediately after `waitForIdleSync`, X08E returned a frame with zero bright pixels.
The test now waits up to three seconds for the compositor to present the frame;
continuous blank rendering still fails. A focused direct rerun passed, followed by
the final direct full suite: `OK (46 tests)`, `INSTRUMENTATION_CODE: -1`, ADB exit 0.
The direct one-test smoke run also passed: `OK (1 test)`, code -1, ADB exit 0.

**AGP execution paths (9.4.1 / Gradle 9.6.0):** the default UTP path still exits 1
with "There were failing tests" even for one `ExampleInstrumentedTest`. Its XML
records one test, zero failures/errors; the test-engine exit-code artifact is `1`.
With `--no-configuration-cache --stacktrace --info`, logs show APK installation,
instrumentation and XML generation completed, followed by output/coverage collection
and the task failure. No assertion, instrumentation process, install or uninstall
error was reported. The exact internal UTP failure condition is unidentified; it
occurs after the test result is generated and before the task returns. The unusual
`28605/A0UH93770` serial is an observation, not a demonstrated cause.

The existing Android 28 X08E AVD was used for one A/B smoke check. With the same
APK, AGP/Gradle versions, default UTP path and `ExampleInstrumentedTest`, the
emulator produced one passing test and **Gradle exit 0**. This narrows the default
UTP failure to interaction with the physical X08E or its ADB environment, but does
not isolate a vendor behavior or prove the serial is the cause. An attempted Gradle
`--serial emulator-5554` selection failed before tests in AGP
`DeviceProviderInstrumentTestTask.getFilteredDevices` with an
`UnsupportedOperationException`; using a process-scoped `ANDROID_SERIAL` selected
the emulator successfully. The emulator was shut down after the comparison.

The actual alternate AGP built-in platform, selected with
`-Pandroid.experimental.androidTest.builtin_test_platform=true`, returned **Gradle
exit 0** for the one-test smoke run and for all 46 tests (XML: 46, zero failures/errors).
This command-line option is the physical X08E Gradle validation workaround; it is
not enabled globally. The default UTP task is still red. No failure suppression,
report rewrite or dependency downgrade was used. See `build/pr12/gate-*.log` for
the direct, UTP and built-in evidence.

The physical suite covers retained-view Clock resizing (4×3 → 2×1 → 3×3 → 4×3),
best-fit addition into a nearly full dashboard, long one-row sensor names with °F and
% RH units, partial/stale/unavailable details, edit/detach dismissal, numeric errors,
exact values and all supported core-card sizes. Protocol tests use MockWebServer;
they are not evidence of a real household light changing.

Existing device tests now avoid X08E system edge touch regions and disambiguate the
Calendar tab from the dashboard hint. The AOSP Calculator-specific test is skipped
when that package is absent. The opt-in real-light probe is skipped in normal runs.

**PR17 update:** the historical `connectedDebugAndroidTest` command below is unsafe
for routine physical X08E testing: it targets the daily-use package, and omitting
the retain-APK property can erase that package and its data. Use the isolated
testbed command in [PR17](PR17-x08e-physical-test-isolation.md) instead:

```powershell
.\tools\test-x08e.ps1
git diff --check
```

`RealLightVerificationTest` only operates a household light when both `realLightName`
and a recognized `realLightStep` are explicitly supplied. It checks incoming HA state,
logs no credentials, and captures the actual X08E quick-control dialog. Its service
results must still be distinguished from a person's visual observation of the light.

## Real HA and physical light, 2026-09-23

The selected Yeelight color-temperature light strip reports brightness support and
2700–6000 K. Each probe launched a new instrumentation process, exercising persisted
control memory between steps. The user observed the household light directly.

| Action | Incoming HA state / DormPanel control values | User observation |
| --- | --- | --- |
| Establish baseline | ON, 65%, 4000 K | Brighter than original 1% |
| Explicit OFF | OFF; raw brightness/Kelvin absent; controls retain 65% / 4000 K | Extinguished |
| Numeric Kelvin while OFF | OFF; controls 65% / 5100 K | Remained continuously off |
| Explicit ON | ON, 65%, 5100 K | Lit and visibly cooler than 4000 K |
| OFF, then numeric brightness 37 | ON, 37%, 5100 K | Relit, dimmer than 65% |
| Long brightness and Kelvin drags | After settling: ON, 76%, 4009 K | Both brightness and warmth changed |
| Restore pre-test state | ON, 1%, 3818 K | HA state verified; no separate visual confirmation requested |

The first consecutive-drag trial showed changing intermediate HA feedback. Inspection
also exposed queued input being read back from mutable memory; a focused protocol
regression now verifies that fresh feedback cannot rewrite unsent fields. On the
final build, both slider steps were checked after a three-second settling interval.
Numeric inputs produced exact HA values (37% and 5100 K); the slider endpoint is
position-derived. Visual warmth confirmation is not a calibrated Kelvin measurement.

Local evidence is retained under `build/pr12/`: `final-full-suite.xml`, physical screenshots and sanitized
real-light state logs, plus test reports. Credentials are not included. Real lights
with other integrations/capabilities, emulator behavior and long-duration soak
stability were not physically verified in this PR. AOSP Calculator is absent on this
X08E, so its external-launch case was not exercised.
