# PR 7 — Apps Launcher & App Shortcuts

The feature branch was fast-forwarded to merged PR 6 (`27bb558`) before implementation. Dashboard database, compatibility repair, quarantine and initialized-empty behavior are unchanged.

## Shared installed-app source

`DashboardViewModel` owns one `AndroidInstalledApps` source. `InstalledAppSource` exposes normalized component identities, labels, package names, availability, favorite operations, subscriptions and exact-component launching. The platform-independent `AppState` handles deduplication, sorting and membership checks with an injected launcher and favorite store. Apps page, app catalog and shortcut cards consume this same source; none enumerate PackageManager in their views.

Discovery queries MAIN/LAUNCHER activities off the main thread, preserving multiple components in a package and excluding DormPanel's own component. The manifest declares only the matching `<queries><intent>` visibility entry. There are no new permissions or HOME/default-launcher declarations.

An application-context receiver observes package added, removed, replaced and changed events. Refresh requests are debounced for 180 ms; events during enumeration request at most one follow-up scan. Activity resume also refreshes changes missed while away. There is no package polling. ViewModel cleanup unregisters the receiver, releases listeners and shuts down workers and icon cache.

Launching uses `Intent.makeMainActivity` for the exact selected component with NEW_TASK. Missing membership and launcher exceptions fail safely with a brief toast and a refresh request. No alternative component in the same package is selected. DormPanel never forces itself back to the foreground.

## Apps page and icons

The Apps page is a RecyclerView grid, visually reviewed at 1280×800 / 160 dpi. It has seven columns, approximately 178×156 dp cells, 56 dp icons and two-line 21 sp labels. The 100 dp toolbar has a prominent title, an options hint and a 144×56 dp Home button. The column count adapts to available width.

Apps opts out of global page-swipe observation for every touch stream, including header, grid and app cells. The grid owns scrolling. The Home button calls a factory-supplied callback; MainActivity owns the existing router transition back Home. Android Back also returns Home. Home → Apps upward navigation and inverse gestures on other pages remain unchanged. Light/Dark Appearance updates live, with a true-black dark background.

Icons load asynchronously for bound cells/cards. A shared 32-entry LRU holds drawable constant states, with a bounded 128-request worker queue. Weak view references, per-bind identity tokens and cache generations reject recycled or outdated deliveries. Recycling cancels queued target work; missing icons use the platform fallback. No icon/bitmap data is persisted. Package refresh invalidates icon state. Card surface opacity remains owned by Dashboard and does not fade icons.

Favorites are a SharedPreferences set of component strings. Pinned entries sort first, followed by case-insensitive label order and component-string tiebreaker. Removed favorites remain stored but create no broken-app section. Reinstalling the same component restores its favorite membership.

## Catalog and cards

Both long-press menus call `InstalledAppSource.openAppSettings(component)`. AppState requires an exact current component match and supplies its package to the injected settings action. Android checks package existence and uses application context with `ACTION_APPLICATION_DETAILS_SETTINGS`, `package:<packageName>` and NEW_TASK. Missing components, disappearing packages and launch exceptions (including ActivityNotFoundException and SecurityException) return false, request normal refresh and show the dedicated settings-failure toast. Multi-launcher components may intentionally share a package details page; absent components never fall back to siblings. No settings action is available through dashboard edit gestures and no permissions are added.

`AppCardCatalog` projects current apps into an Apps category. `CombinedCardCatalog` merges it with the existing Dashboard/HA catalog, deduplicating candidate IDs and owning only listeners requested by consumers. Package updates replace candidates rather than append duplicates. Neither app discovery nor app candidates belong to DashboardBackend.

The stable provider key is `app`. Configuration is `{"component":"package/fully.qualified.Activity"}`; labels and icons are resolved at runtime. Supported sizes are 1×1, 2×1 and 2×2, with progressively larger icons and more label space; the largest size also shows package identity. All three sizes were visually reviewed using real emulator app icons.

Normal taps launch; normal long press offers Open and App settings. Apps-grid long press offers Pin/Unpin and App settings. Existing Dashboard edit chrome owns taps, drag and resize while editing. Missing apps remain valid placed cards with unavailable presentation and their unchanged component configuration, automatically becoming usable when the exact component reappears. No changes were made to PR 6 recovery rules or database schema.

## Verification performed

Final combined command on 2026-09-21:

```text
gradlew assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
git diff --check
```

- BUILD SUCCESSFUL; log: `build/pr7-amend-verification.log`.
- The real Calculator scenario was additionally extended to exercise Pin and Unpin using isolated favorites, then rerun successfully with `connectedDebugAndroidTest` filtered to `AppsAndroidTest#realDiscoveryIconsAndExactExternalLaunchReturn`, plus lint. Log: `build/pr7-amend-real-settings.log`; the full 24-test XML is preserved at `build/pr7-amend-full-results.xml`.
- JVM: 90 tests, zero failures/errors/skips. New coverage includes favorites/sorting, exact identity, multiple activities, deduplication, own-component exclusion, safe missing/racing launch, reappearance, configuration round-trip, live composed catalog subscriptions, no catalog-triggered polling, and unavailable-card compatibility repair.
- API 28 instrumentation: 24 tests, zero failures/errors/skips. All 18 existing PR 1–6 tests remain, with Apps return assertions updated to use the explicit Home button.
- Six Apps instrumentation tests use `IsolatedDashboardRule`; none open or own production `dashboard.db`. They cover 120 injected app entries and virtualized scrolling, grid gesture ownership, Home-button/Back return and non-navigating header swipes, pin/unpin ordering, Activity recreation/new ViewModel restoration, isolated preference round-trip, Light/Dark updates, picker candidates, distinct app cards, actual move/resize gestures without launch, card persistence, coalesced refresh, missing/reappearing components and no idle scans.
- Real emulator discovery displayed 14 external launcher activities with icons. Tapping Calculator from Apps and its dashboard card launched `com.android.calculator2/.Calculator`; Android Back returned to the retained DormPanel session. Three distinct installed apps supplied the card-size visual review.
- The amended real-app test also opens App settings from both Apps and a shortcut card. API 28 displayed Android Settings' **App info** page for **Calculator**, with `package:com.android.calculator2` in its launch intent; Back returned to the originating Apps/dashboard surface. The shortcut Open menu action still launched Calculator. No management controls on the system page were used.
- Amended toolbar Light/Dark and both Settings screenshots were reviewed under `build/pr7-amend-review/`. The explicit Home button remains visible in both themes. X08E firmware/system-gesture interception was not tested on physical hardware.
- Reviewed screenshots: `build/pr7-review/pr7-real-apps.png`, `pr7-apps-light.png`, `pr7-apps-dark.png`, and `pr7-real-shortcuts.png`.
- Lint: zero errors, 18 advisory warnings (dependency/style notices, including RecyclerView full-data notifications on discrete source/appearance changes).
- No `.idea` changes included in the PR; pre-existing local IDE changes were preserved. `versionCode=1` and `versionName=1.0` unchanged. No dependency additions and no forbidden permissions.

Actual package installation/removal was not exercised: removal/reappearance and update coalescing use injected discovery, without changing globally installed emulator packages. The package receiver wiring is implemented but is not presented as a measured package-install experiment. No physical X08E performance, idle CPU, OEM launch behavior or long-running stability measurements are claimed; those remain device verification work.
