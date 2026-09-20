# PR 3 — Core Cards & Appearance

The production registry contains `clock`, `weather`, `sensor`, and `light`. The first-launch
layout uses a clock and weather across the top and a climate sensor plus three lights below.
The three lights demonstrate full controls, brightness only, and on/off only. No network
dependencies, permissions, system controls, or application version changes were introduced.

## State ownership and PR 4 integration

`DashboardViewModel` owns one `DashboardDataSource` and one `AppearanceController`.
`FakeDashboardDataSource` supplies deterministic immutable snapshots; card views and the
light dialog subscribe while attached/shown and remove their listeners when detached/dismissed.
Light commands update the source, which publishes the resulting state to every subscriber.
Views do not optimistically mutate device state. Fake device changes intentionally reset on
process restart; dashboard layout and appearance persist.

PR 4 can replace the source in the ViewModel. Preserve these contracts:

- Deliver current state on subscription, then updates on the Android main thread.
- Keep protocol, connection state, command acknowledgement, and reconnection outside views.
- Preserve provider type keys and opaque configuration: sensor cards use `sensorId`, light
  cards use `lightId`. Resolve these IDs in the source; they need not be HA entity names.
- Publish availability (`AVAILABLE`, `UNAVAILABLE`, `STALE`) and capability information.
  Brightness is 0–100 percent; color temperature is Kelvin within an optional advertised range.
- Route optional remote appearance changes through `AppearanceController.update`, retaining
  its bounds checking, persistence, and notifications. No HA helper IDs are required.

Malformed or missing device configurations render unavailable state. Unknown IDs do not send
effective fake commands. The fake source includes stale and unavailable sensor examples (`desk`
and `balcony`); no HA freshness policy is implied.

## Presentation and interaction

Clock, weather, and sensor accept 2×1 through 4×3. Light accepts 2×1, 2×2, 3×2, and 3×3.
One-row cards show compact essential values. Taller cards add date, humidity, or light settings;
cards at least 3×3 add forecast, local year/timezone, sensor status, or light interaction hints.
Size decisions live in providers and pure `cardDensity`, never in the grid.

The clock uses device time/date/locale and its preferred 12/24-hour format, without seconds.
It schedules the next minute boundary, listens for time/date/timezone corrections, and removes
both callbacks and broadcasts when hidden or detached. No sample source polls or animates.

Light tap toggles; long press claims the current stream and opens a dialog. Dialog controls
follow advertised capabilities and availability. Sliders own their stream from `ACTION_DOWN`
and reserve 48dp touch targets. The dialog also has its own Android window. Edit mode removes
provider actions, while the existing grid handles drag, resize, add, and delete.

## Appearance and development repair

Appearance is independent of Room card configuration. `PreferencesAppearanceStore` persists
theme and card fill opacity in the `appearance` SharedPreferences file. The controller sends
live notifications; existing views change color without Activity recreation or layout rebuilds.

Dark page/window backgrounds are #000000. Dark card fill is neutral #202020 at full opacity;
at zero opacity it disappears into the pure-black background. Light mode uses a neutral
page and white surfaces. Only card fill alpha changes, so text stays opaque. Card borders
appear only in edit mode. Defaults are Dark and 100% opacity.

The existing pre-release layout validation/repair path replaces layouts containing removed
`mock.*` providers with the production seed and persists the repaired snapshot. Valid
production layouts, stable IDs/configurations, and initialized empty dashboards are retained.
The existing broader invalid-layout repair behavior is unchanged.

## Verification

Run from the repository root:

```text
gradlew.bat assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest
```

UI instrumentation expects the seeded dashboard on a test emulator. It exercises light tap,
long press, capability-specific controls, slider gestures, disabled actions/page swipes in edit
mode, all four navigation routes, live appearance, real preferences, retained view identities,
compact content, actual drag resizing of all four providers, and a real minute boundary.
Unit tests cover appearance restore/notification/bounds, fake commands/capabilities, size
policies/presentation, minute scheduling, and legacy-layout repair/preservation alongside the
existing engine tests.

Executed on 2026-09-20: debug assembly succeeded, 36 unit tests passed, and all 9 connected
instrumentation tests passed. Lint completed with zero errors and 35 warnings (unextracted
English UI strings, programmatic-view constructors, and KTX style suggestions).

Manual checks confirmed readable light/dark dashboards and the light dialog, 48dp slider and
theme-selector targets, immediate theme/opacity updates, 0% and 100% fill, and light toggling
with transparent cards. Setting Light + 46% in Control Center survived force-stop/relaunch,
with both preferences and the rendered dashboard checked. Screenshot samples confirmed
black page and transparent-card pixels; 100% dark fill remained neutral #202020.

The verification environment was the X08E AVD: API 28, 1280×800, 160 dpi, 2048 MB RAM.
This does not establish behavior on physical Xiaomi hardware or long-duration memory/CPU
soak reliability.
