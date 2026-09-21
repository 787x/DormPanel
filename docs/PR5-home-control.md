# PR 5 — Home Control & HA Device UX

Home Control now provides an area sidebar and a virtualized device/entity list for the 1280×800 landscape panel. All and Unassigned are explicit choices. HA names are displayed as supplied; area buttons do not uppercase backend names. Selection belongs to the ViewModel-owned backend session and falls back to All when the selected area disappears.

## State and controls

`HomeControlSource` publishes typed `HomeControlState` snapshots of areas, devices and supported entities, separately from `DashboardData`. `HaEntityStore` remains the only raw HA state owner. The existing WebSocket projects both boundaries. Demo uses the same contract and projects its existing dashboard lights and sensors; only the new Demo switch has new local state. Demo scenes turn lights off, and its script turns on the desk light.

Area resolution is explicit entity area, then device area, then Unassigned. A reference to a missing area resolves to Unassigned. Device names prefer name_by_user, name, then stable ID. Missing devices become entity-only groups. Ordering uses names followed by stable IDs, with fixed domain ordering within a group. Disabled entities and diagnostic/config categories are excluded deterministically. Unsupported domains never receive a toggle.

- Lights use the existing normalized light state, deterministic power command and `LightQuickControls`; brightness/Kelvin coalescing and the 1–100 brightness command range are unchanged.
- Switches send switch.turn_on or switch.turn_off from authoritative state.
- Sensors show string/numeric state and the supplied unit; unknown/unavailable and stale values are distinct.
- Scenes send scene.turn_on with one tap.
- Scripts send script.turn_on. The existing connection fetches get_services metadata; scripts with required fields or unverified service metadata are disabled. No field values are invented.

Commands do not optimistically change HA state. Action feedback reports send admission, not confirmed execution. Disconnect retains process-local values, marks the Home Control projection stale and disables commands. The HA settings button remains available for configuration. No fallback to Demo occurs in HA mode.

## Synchronization and lifecycle

Initialization requests entity list_for_display and full entity list, device list, area list and script service descriptions on the existing connection. Entity/device/area registry events refresh only their corresponding metadata. Concurrent refresh events are coalesced with a trailing refresh so changes during an in-flight request are not lost. Script service registration/removal refreshes field metadata. Ordinary state_changed updates do not fetch registries or service descriptions. Reconnect resynchronizes the snapshot and metadata.

RecyclerView/ListAdapter/DiffUtil uses stable item identities, disables item animations and only rebinds changed rows. Domain presenters are separate from area/device navigation. No raw JSON is retained in Views, new polling is absent, and appearance uses the existing controller. Dark background is black; entity surfaces follow cardSurfaceOpacity.

Controls claim page gestures at touch-down. Vertical movement in the lists claims scrolling; non-interactive horizontal swipes and header gestures remain available to the shell. Light sliders use the shared claiming SeekBars inside their own dialog window. Back returns through the existing shell.

## Verification

Final results (2026-09-21):

- `gradlew assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest`: BUILD SUCCESSFUL (`build/pr5-verification.log`).
- After adding two more topology/500-entity JVM cases, `gradlew testDebugUnitTest lintDebug`: BUILD SUCCESSFUL (`build/pr5-final-unit-lint.log`). Latest JVM result: 70 tests, zero failures/errors.
- Full emulator suite: 15 tests, zero failures/errors. Device reports API 28, 1280×800 and 2,046,704 kB RAM.
- Home Control instrumentation also passed a direct runner invocation while collecting screenshots. It exercises right entry, area/device selection, All/Unassigned, real list scrolling, light power and shared brightness/Kelvin controls, switch power, sensor values, scene/script activation, slider isolation, left header return, Back, retained selection, live themes and surface alpha.
- Lint: zero errors, 15 warnings (dependency/version-catalog, constructor tooling and KTX suggestions). VersionCode/versionName unchanged.
- Visual review: [Study](pr5-review/study-api28.png), [Light with 30% surfaces](pr5-review/light-api28.png), [Dark with 30% surfaces](pr5-review/dark-api28.png). APK: `app/build/outputs/apk/debug/app-debug.apk`.

The new tests cover normalization, registry parsing and topology, deterministic commands, offline preservation, Demo synchronization, selection reconciliation, 500-entity identity/order, and API 28 touch/appearance behavior. Existing PR 1–4 tests remain present. The existing Demo UI fixture now saves/restores HA mode, and the clock detach test waits for the outgoing page transition to finish before asserting detachment.

Real Home Assistant and physical X08E verification have not been performed in this task. Areas/devices, two real lights, external updates, switch/sensor/scene/script availability, registry edits and outage/reconnect therefore remain real-environment checks; MockWebServer tests do not establish those results. No claim is made that a missing domain is absent from the user's HA.

Deferred: climate, cover, fan, lock, media_player, vacuum, alarm_control_panel, RGB picking, histories, dashboard Scene/Script cards and arbitrary service/automation editing. App version remains 1 / 1.0. No launcher, boot, Root or Xiaomi-service changes.

Protocol reference: [Home Assistant WebSocket API](https://developers.home-assistant.io/docs/api/websocket/).
