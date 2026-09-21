# PR 4 — Home Assistant Core & Live Data

## Architecture

`DashboardBackend` is the stable card-facing `DashboardDataSource` and catalog facade. Switching DEMO / HOME_ASSISTANT replaces its delegates without reconstructing card providers. Fake IDs (`desk`, `room`) never resolve to HA devices. Clock and local functions remain independent.

`HaWebSocketClient` owns one OkHttp socket. Its callbacks, command timers, normalized state publication and catalog notifications are serialized onto the main looper. `HaEntityStore` owns the sole raw state map; `HaDashboardDataSource` projects immutable card models. Views do not parse protocol JSON. Discovery IDs and sensor grouping are cached separately; brightness/state updates do not reconstruct or republish the catalog or card Views.

Authentication follows `auth_required → auth → auth_ok`. A confirmed `state_changed` subscription precedes `get_states`. Events arriving during registry loading are buffered (bounded) and applied before Connected. Reconnection refreshes a complete snapshot; failed connections back off at 1, 2, 5, 10, 30 seconds, capped at 30. Authentication rejection stops retries until Save/Reconnect or credentials change. Request and initialization timeouts prevent stuck syncing; generation guards discard callbacks from replaced sockets. OkHttp ping/pong detects dead connections at a low frequency, without entity polling.

Disconnect retains real values with STALE availability, leaves unavailable entities unavailable, and cancels pending slider commands. Lights are disabled until a fresh connection is synchronized. No automatic fake fallback occurs. The Home indicator is hidden when connected; Control Center shows the detailed connection status.

`HaRestClient` only performs an asynchronous, user-triggered authenticated `/api/config` diagnostic. It owns no entity state and never polls.

## Settings and credentials

Control Center → Home Assistant settings provides backend, base URL, password-masked token, Test Connection, Save/Reconnect, Clear credentials, preferred weather, theme helper and opacity helper. Blank token input retains saved credentials. After first discovery, reopen settings to select discovered entities. All changes apply in the running process.

Non-secret settings use SharedPreferences. The token is encrypted with an app-owned Android Keystore AES key using AES/GCM and a fresh IV. Only IV/ciphertext are stored in `ha_credentials.xml`, excluded from cloud backup and device transfer. Reads do not require user authentication. Malformed ciphertext or invalidated keys require credential re-entry, never an app crash. Clear credentials also removes the key, allowing recovery. The token field does not participate in saved View state or autofill; the dialog is secure against screenshots. The token is never logged.

Base URL parsing accepts omitted scheme (HTTP), trailing slashes, API endpoint suffixes and ws/wss forms, including reverse-proxy path prefixes. Embedded credentials, query strings, fragments and non-HTTP protocols are rejected.

Android 9 cleartext policy is intentionally application-wide (`usesCleartextTraffic=true`) because arbitrary user-entered LAN hosts cannot be enumerated in Network Security Configuration. This is the practical tradeoff for local HTTP HA. All client requests derive from the explicitly configured endpoint; redirects are disabled to prevent sending credentials elsewhere. The settings surface warns that HTTP exposes the token in transit. HTTPS uses platform/OkHttp certificate and hostname validation; there is no trust-all override. Private/self-signed CA installations must configure Android trust appropriately.

## Discovery, normalization and commands

Discovery uses `config/entity_registry/list_for_display` and the full registry for explicit disabled status and device metadata, with state metadata for unregistered entities. Unknown registry fields are ignored. Registry updates trigger metadata refresh; catalog ordering is deterministic. Disabled entities are excluded. Device registry requests are unnecessary for the current unambiguous device-ID pairing.

- Lights use `ha:light.entity` IDs and current `supported_color_modes`, brightness 0–100%, and Kelvin attributes/ranges only. Brightness commands are clamped to 1–100% in both backends, and both brightness SeekBars have minimum 1. A reported backend value of zero is preserved; only the slider position/label is clamped. Demo brightness changes also turn an off light on, matching HA `light.turn_on`. Toggle chooses `light.turn_on` or `light.turn_off` from authoritative state. Service success does not optimistically change card values.
- Brightness and Kelvin are latest-wins per entity/property, flushing every 180 ms while dragging and delivering the trailing value while connected. Different lights are independent; disconnect cancels queued commands rather than replaying stale intent after reconnect.
- Sensors are recognized by `device_class`. Exactly one temperature and one humidity on the same HA device produce `ha:device:<id>`; ambiguous or unrelated entities remain partial `ha:sensor.entity` candidates. Units are preserved, including °F. Unknown/unavailable/non-numeric values are absent, not fabricated measurements.
- Weather uses the preferred entity (or deterministic first discovered entity). The current unit is preserved. Forecast uses WebSocket `weather.get_forecasts` with response data, preferring daily, then hourly/twice_daily when supported. It refreshes after synchronization/selection changes and hourly, without state polling.
- Optional theme helpers recognize light/dark case-insensitively and send the exact configured HA option. Opacity helpers map 0–100 to 0–1. Local setters defer to HA only for a connected, existing, enabled, available helper in the correct domain (and an exact case-insensitive matching theme option), and only when the WebSocket accepts the service request. Invalid bindings or rejected requests return false so AppearanceController applies the change locally, without deleting the binding. Valid accepted requests still wait for HA state updates. Opacity now checks immediate socket admission rather than claiming ownership of a deferred timer; brightness/Kelvin coalescing is unchanged. Unrelated entity events cannot replay old helper values over a local fallback. Offline appearance stays cached and local controls still work.

## Verification

Tests use synthetic tokens and MockWebServer, not real HA credentials. The focused API 28 instrumented run passed secure-token roundtrip/corruption and entering URL/token through the settings surface, saving HOME_ASSISTANT, and receiving discovered light state over an actual local WebSocket. Final full-suite results are recorded below.

Manual real-HA checklist: save URL/token, discover and add two distinct lights, observe external updates, tap each light, drag brightness/Kelvin, add sensor candidates, choose weather and helper bindings, disconnect/reconnect, verify duplicate cards backed by one entity. Existing Demo cards must be replaced with HA picker candidates.

## Limits

No real HA instance or X08E hardware was used for verification. MockWebServer success does not establish real integration/device compatibility. Last real values survive connection outages in the running process, not process death. Entity rename and sensor pairing-topology changes can require replacing old cards. Forecast presentation uses integer temperatures and the existing three visible cells. HA permissions/integration support still determine which controls and forecast services succeed. Settings use a low-frequency reopen-after-discovery flow, and no OAuth, launcher, boot, Root or Xiaomi-service changes are included. App version remains 1 / 1.0.

Protocol references: [WebSocket API](https://developers.home-assistant.io/docs/api/websocket/), [Light entity](https://developers.home-assistant.io/docs/core/entity/light/), [Weather integration](https://www.home-assistant.io/integrations/weather/), [Entity registry API](https://github.com/home-assistant/core/blob/dev/homeassistant/components/config/entity_registry.py).

## Final executed results (2026-09-20)

- `gradlew assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest`: BUILD SUCCESSFUL.
- JVM: 55 tests, 0 failures (13 new HA tests).
- Emulator: Android 9 / API 28, 1280×800; 12 instrumentation tests, 0 failures, including two new HA tests and all existing dashboard/gesture/persistence regressions.
- Lint: 0 errors, 11 warnings (newer dependency versions, version-catalog style, SharedPreferences KTX suggestions).
- Real HA: NOT performed. Physical X08E: NOT performed.
- Build log: `build/pr4-final-verification.log`; APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Visual check: Control Center inspected at 1280×800; screenshot in `docs/pr4-review/control-center-api28.png`. Credential dialog is FLAG_SECURE; its flow was verified with Espresso rather than a token-bearing screenshot.

## PR #4 interaction correction (2026-09-21)

The user reported real HA control working on a physical Android phone. This correction targets brightness commands that previously allowed off-at-zero and Appearance helpers that previously swallowed local input. No new real-HA checks were performed by Codex for this correction.

Focused regression coverage includes 1–100 brightness commands, an unmodified zero-valued authoritative snapshot, a coalesced drag ending at 1, explicit power cancelling older queued brightness, both actual SeekBar minimums, invalid/deleted/wrong-domain/unavailable helpers, missing theme options, non-finite opacity, saturated socket request admission and a stopped transport. Local fallback is checked through AppearanceController, and valid helpers still converge only from HA state events.

Correction verification: `gradlew assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest` passed; 63 JVM tests and 14 API 28 instrumentation tests, all successful. Lint: 0 errors, 11 existing dependency/style suggestions. Manual Demo checks at 1280×800 dragged both quick-control and inline sliders fully left: each displayed 1% while the light remained on; the explicit power button/card tap turned it off. Screenshots: `docs/pr4-review/quick-brightness-minimum-api28.png` and `docs/pr4-review/inline-brightness-minimum-api28.png`. Build log: `build/pr4-amend-full-verification.log`. Application version unchanged.
