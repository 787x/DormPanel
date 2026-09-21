# PR 6 ¡ª Persistence & Runtime Hardening

Dashboard UI instrumentation injects an in-memory Room database through `DashboardStores`. Core dashboard, Home Control and HA Activity tests all install the same JUnit rule before launching an Activity. They never open, clear, copy or restore production `dashboard.db`. Each Activity-owned store receives an executor owned by the rule. After ActivityScenario destroys the ViewModel, teardown asserts shutdown and awaits executor termination off the main thread before closing its database; database-value equality is not a drain signal.

`RoomDashboardStore.close()` is idempotent and non-blocking: admission closes synchronously, already accepted operations drain in order. `DashboardStateHolder` is confined to the UI scheduler, ignores late load/save/repair completions after close, clears listeners and prevents further persistence. Writes are submitted before listener notification so a synchronous listener edit or close cannot reorder a repair after a newer edit or submit it after shutdown.

## Compatibility repair and recovery

Only an uninitialized store receives the default seed. An initialized empty layout stays empty, and a fully valid layout with no newly recoverable records is not rewritten on launch.

Repair consumes active records in their persisted order (Room reads row, column, then ID), followed by quarantined records in recovery order. It reserves the first occurrence of each ID, preserves provider/configuration/identity, retains supported sizes that fit the grid, and otherwise uses the provider's `CardSizePolicy.snap`. It tries the original position, then the first free row-major position without moving any accepted card. The result must pass the ordinary layout validator. Invalid spans/identities, unknown providers, impossible sizes, duplicate IDs and exhausted space retain their complete raw fields and a reason in quarantine. Opaque provider configuration is retained without reinterpretation.

Room schema 2 explicitly migrates schema 1 by adding `dashboard_quarantine`; existing tables and records are untouched. Its independent generated key preserves multiple raw records sharing a card ID. Repair replaces active and quarantine snapshots in one transaction. Ordinary edits replace only active cards. If repair persistence fails, subsequent edits retry the atomic repair snapshot, preventing a later ordinary save from deleting raw records before they reach quarantine.

Every later launch retries quarantine after the active layout. A returning provider or newly available space can restore a card, retaining its identity and configuration. Only successful recovery removes its quarantine record in the same transaction as adding it to active storage. No recovery management UI is introduced.

## Home Assistant projection work

`DashboardBackend` delegates Home subscription ownership to `DemandHomeSource`: zero consumers means no upstream Home listener; the first attaches once and the last detaches. Backend activation detaches before reconfiguration and attaches once to the selected source when consumers exist. Direct reads query that source independently of listener deduplication.

HA keeps its single raw entity store and existing scheduler. Direct Home reads project current raw entities and current light state, including during reconnect initialization. Event publication reuses the already computed Dashboard light map. No Home snapshot is built for notification without Home consumers. While observed, supported Home domains trigger Home projection; weather/Appearance/other domains still mutate the raw store, check topology and update their own subsystems without unnecessary Home projection. Forecast updates likewise do not project Home.

Dashboard publication computes one normalized snapshot and passes it to catalog rebuilding and listeners. Registry completions reuse that snapshot; initialization publishes on synchronization rather than again while nested completion callbacks unwind. Notification deduplication uses a separate last-emitted Home value, never a recomputing getter. The injectable projector supports invocation-count assertions, not timing thresholds.

## Verification

Verified on 2026-09-21:

- `gradlew assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`: BUILD SUCCESSFUL, twice. Final source verification is in `build/pr6-repeat-verification.log`; the preceding full run is in `build/pr6-final-verification.log`.
- Final JVM suite: 84 tests, zero failures/errors/skips. All existing PR 1¨C5 tests remain.
- Instrumentation: 18 tests, zero failures/errors/skips in both completed full runs. Emulator reports API 28 and physical size 1280¡Á800. Includes explicit schema 1¡ú2 migration, quarantine survival across ordinary saves, malformed raw loading, queue draining and the existing UI regressions.
- Lint: zero errors, 15 warnings (existing dependency/tooling suggestions). `git diff --check` passed.
- Projection-count tests assert zero notification Home projections without observers, one Dashboard normalization for topology/catalog publication and each registry/service refresh, relevant observed notifications, irrelevant Appearance-domain exclusion, fresh unsubscribed/reconnect reads and raw-light reads before initialization publishes Dashboard state.
- The APK-retention property preserves the installed app for the read-only before/after database check. After normal app initialization and force-stop, SHA-256 of `dashboard.db`, `dashboard.db-shm` and `dashboard.db-wal` was identical before and after each completed instrumentation run. Tests do not read or write these files. Verified hashes:

```text
dashboard.db      48e55f9c3257aafe496e62d78dfeb626807bb3d04bfb2577de4f7af850d2d70c
dashboard.db-shm  fd4c9fda9cd3f9ae7c962b0ddf37232294d55580e1aa165aa06129b8549389eb
dashboard.db-wal  e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

An earlier verification attempt was interrupted by the local emulator/process environment; its damaged APK metadata was rebuilt before the two successful runs. No timing-based performance claim is derived from these runs.

No physical X08E profiling or measured frame-rate/CPU improvement is claimed. Apps Launcher, new card/device domains, recovery UI, app version changes, background HA processing and device/system integration remain outside this PR.
