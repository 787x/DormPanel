# Local Productivity Core

## Architecture and compatibility

`DashboardViewModel` owns one `ProductivitySource`, independent of the HA/dashboard data source and installed-app discovery. Providers and Views use that boundary only. Its commands and owner-scoped listeners run on the main thread; a serial Room worker loads/writes structured content in a separate `productivity.db` (schema version 1, no destructive migration).

`PlacedCard.id` owns a list of ordered Todo records, one plain-text Memo and one countdown state. The catalog has three stable generic candidates/provider keys: `todo`, `memo`, `timer`. Adding a candidate allocates the ID through the existing dashboard state holder. Moving, resizing, quarantining or deleting layout entries does not delete content. No orphan cleanup is performed. The layout database still stores only layout/configuration.

Accepted writes drain before the productivity database closes. Store admission/close share a lock, and both the store and source ignore late completions after close. Failed initial loading disables commands rather than overwriting unavailable data. Storage failures are shown on the card.

## Interaction

All three cards support 2×1, 2×2 (default), and 3×2. Compact cards summarize; normal and larger cards expose progressively more text. Todo previews prioritize incomplete items in insertion order; completed items remain accessible in the editor. Tasks can be added, toggled, edited and deleted. Blank task text is rejected; surrounding whitespace is normalized.

Memo is plain Android multiline input: Save commits the draft; Cancel discards it; Clear text clears the draft and still requires Save. Line breaks survive persistence. No per-keystroke writes occur.

Timer supports 1-, 5-, and 25-minute presets, custom 1–86400 seconds, Start/Pause/Resume/Reset, and Finished. Large cards expose inline controls; every size opens the control dialog. Timer controls claim their touch stream. All business actions check the dashboard interaction scope, and inline buttons are disabled during layout editing. Editors use a landscape-sized dialog, readable text, at least 48dp action targets and the current appearance palette.

## Time and background behavior

The injectable clock supplies wall time and monotonic elapsed time. Running timers use an in-process elapsed-time deadline. Start/resume persists the wall deadline; pause persists the remaining duration. A reopened source derives a bounded remaining duration from the saved wall deadline. Wall-clock changes while the process is absent can therefore affect recovery; ordinary wall-clock changes during the process do not move the countdown.

A display-only one-shot ticker runs only for attached, visible, running Timer cards. It cancels on detach/window hiding, and stops at expiry. Idle, paused and finished timers do not schedule work. Reading a countdown never writes to Room, including at expiry. There is no foreground service, notification, alarm, sound, vibration or wake lock. Reopening computes the elapsed/finished state; this feature is not a system alarm.

## Test isolation

`ProductivityStores.overrideFactory` is installed before any instrumented Activity launch. The existing `IsolatedDashboardRule` now owns both in-memory databases, drains their workers and closes them after Activities/ViewModels are destroyed. Disk round-trip tests use UUID-named test databases. They never clear or restore production `dashboard.db` or `productivity.db`.

`ProductivityRestartAndroidTest` also supports two independent instrumentation runs around an actual `adb shell am force-stop com.dormpanel.app`, using the same `productivityRun` UUID and `productivityPhase=seed` / `verify`. Both its layout and content databases are test-only. The verify phase removes only those two files. Ordinary suite execution performs both phases without an external process kill.

## Verification

- `gradlew assembleDebug`: passed.
- `gradlew testDebugUnitTest`: 97 tests passed.
- `gradlew lintDebug`: passed, 0 errors (18 existing dependency/style warnings).
- `gradlew connectedDebugAndroidTest`: 30 tests passed on the X08E API 28 emulator, 1280×800 at 160 dpi; includes all existing tests.
- Focused UI checks exercise independent Todo/Memo/Timer cards, add/complete/uncomplete/edit/delete, multiline save/cancel/clear, Activity and source recreation, timer pause/resume/reset/expiry, background return, Apps navigation, picker categories, layout repair and edit-mode action isolation.
- Room tests verify disk round-trip, main-thread completions, draining queued writes and discarding callbacks after close. Unit tests verify monotonic time, restart deadlines, independent owners, deterministic ordering, owner-only notifications and no idle/per-second persistence work.
- Actual two-run force-stop verification passed: two independent Todo lists, two multiline Memos, a running timer and an expired timer were restored after `adb shell am force-stop`. Both phases used the same UUID-named isolated test databases.
- Light/Dark Home and editor screenshots, compact/wide Timer layouts, and readable dialog actions were visually inspected at 1280×800. Local screenshots/logs are in `build/productivity-review/` (excluded from Git).
- `git diff --check` passed. Both the base-to-HEAD diff and working-tree file list were checked for unintended `.idea` state. Version and manifest permissions remain unchanged.

Physical X08E testing is still required for long-running memory/CPU behavior, touch/IME usability at bedside distance, and coexistence with Xiaomi's original XiaoAI, alarms and Bluetooth Mesh services. No device-specific behavior is asserted by the emulator checks.
