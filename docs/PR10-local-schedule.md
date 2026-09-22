# PR 10 — Local Calendar & Timetable

## Data boundary

`DashboardViewModel` owns `ScheduleSource`, `ScheduleSession`, and their lifetime. Views and providers only use source commands/projections. `ScheduleStores` creates a dedicated Room `schedule.db`; dashboard persistence remains layout-only and productivity storage is unchanged. Schedule identities are UUIDs, shared across every Calendar/Timetable card, with no card-owner key.

`CalendarEvent` holds ID, title, start/end epoch milliseconds, and optional note. One-time timed events may span dates and overlap. Local-date agendas include every intersecting event, with an exclusive end. Editing preserves identity. Editors use the current device timezone, locale and time format; a nonexistent DST local time is rejected. Unchanged timestamps preserve their original offset during repeated DST hours. If the device timezone changes while an editor is open, Save asks the user to reopen the editor rather than interpreting its old draft under a new timezone.

`TimetableEntry` holds ID, title, ISO weekday (1–7), local start/end minutes, and optional location. It is an ordinary weekly civil-time schedule. Entries end later on the same day and may overlap. Occurrences are projected into the current device timezone; event ordering is start/title/ID, class ordering is day/start/title/ID.

Invalid database rows are hidden with a visible warning, not repaired or deleted. Per-record writes leave unrelated rows intact. Storage failures are exposed; optimistic edits with a storage error may not survive restart. Schema version 1 is the migration boundary: all future schema changes must add explicit preserving Room migrations. No destructive migration fallback is used.

Room work is serialized off the main thread. Callbacks return on main. Admission and close share a lock; accepted writes drain, new work is ignored after close, and late callbacks cannot revive a closed source. Activity destruction does not wait for database work.

## UI and navigation

Home left swipe opens a native Views page with Calendar and Timetable modes. Calendar uses a six-row, locale-ordered month grid beside the selected-day agenda. Outline marks today; muted fill marks selection. Previous/next month changes the displayed month without silently moving the agenda selection. Choosing a grid date updates both; Today selects the current local date. Explicit selections remain stable at midnight until Today or another date is chosen.

Timetable uses seven day columns with independently scrollable, chronologically ordered entries and a subtle today label. Native date/time pickers and landscape-width editors save only on Save, preserve drafts on validation failure, and discard them on Cancel. Existing entries offer Delete. The ViewModel retains mode/date/month across page reconstruction and Activity recreation within the session.

The page opts out of global swipe observation. Header Home uses MainActivity's existing return-home callback; Android Back returns Home. Apps and other page gestures retain their existing behavior.

Provider keys `calendar` and `timetable` are persistent compatibility surfaces. Both support 2×1, 2×2 and 3×2. Calendar shows today's date and up to 1/2/4 ongoing or upcoming events, including the next future date when today is empty. Timetable distinguishes current, next today and no more today; larger cards can show a future day. Notes stay off Home. Tapping edits the next item, or creates one if empty. Dashboard edit mode disables all business actions and closes editors. `CombinedCardCatalog` now lives in `dashboard.catalog`, combining core/HA, Apps, Productivity and Schedule candidates.

## Refresh and isolation

The injectable `ScheduleClock` supplies instant, timezone and locale. Visible surfaces listen for TIME_CHANGED, TIMEZONE_CHANGED, DATE_CHANGED and LOCALE_CHANGED. A single one-shot callback is scheduled at the next event/class start/end or local midnight. Detach/invisibility cancels callbacks and broadcast/source subscriptions. Activity resume refreshes presentation, including 12/24-hour preference changes. No polling, wake locks, alarms, notifications or time-triggered database writes are introduced.

`IsolatedDashboardRule` now injects an in-memory schedule database, alongside dashboard/productivity. Existing restart tests also inject isolated schedule storage. Schedule persistence tests own UUID-named files or in-memory databases; they never clear, restore or open production data. `ScheduleRestartAndroidTest` supports separate instrumentation runs using `schedulePhase=seed` then `schedulePhase=verify`, with the same UUID `scheduleRun`, allowing a force-stop between phases.

## Scope

Everything works locally without HA, network or launcher apps. Calendar Provider/permissions, cloud calendars, sync/import/export, reminders, recurring or all-day events, semester/odd-even/holiday rules and timetable drag editing are intentionally deferred. X08E hardware still needs long-running memory/idle CPU, native picker/touch behavior and Xiaomi service coexistence checks; emulator behavior does not establish device-side guarantees.

## Verification

Verified on the API 28 X08E AVD at 1280×800, density 160. The JVM suite has 109 passing tests, including 12 schedule tests covering CRUD, stable identity/tie ordering, invalid rows, late completion, event overlap/date projection, leap February, locale week starts, month/year transitions, timezone changes, DST midnight and weekly civil-time projection, and compact/large card selection.

`assembleDebug`, `testDebugUnitTest` and `lintDebug` passed. Native UI tests exercise Home left swipe, Home button, Back, inverse-swipe opt-out, mode/date/month retention, month navigation, Today, date selection, event/class Save/Edit/Delete/Cancel, title/time validation, native date/time pickers, seven-day layout, and light/dark appearance. Card tests verify provider keys, all three sizes, shared state, edit-mode suppression, and a real event-end wakeup without mutating stored data. Room tests verify file round-trip, callbacks on main, selected deletes, queued-write draining and post-close rejection.

The full `connectedDebugAndroidTest` regression run passed all 36 tests (4m 32s), including prior Dashboard, HA, Apps and Productivity coverage. After adding the editor timezone-change guard, all four Schedule UI tests passed again using the class-filtered connected task (1m 21s), including the new test proving the draft performs zero writes after a timezone change. Build, JVM tests and lint were rerun successfully on the final code. `git diff --check` passed.

A separate seed → `am force-stop` → verify protocol passed using run UUID `ffa4341c-f7d6-4da2-8b77-ae9f03d3936e`; both instrumentation invocations passed and verified schedule and dashboard restoration. Only UUID-named test databases were used.

Fourteen emulator screenshots were captured under local ignored `build/pr10-visuals/files/`: Calendar/Timetable light and dark, selected date versus today, both editors, native pickers, 2×1/2×2/3×2 cards, edit mode and light cards. Visual inspection prompted separating time and title on compact cards to preserve the title's usable width.

Application version and Manifest are unchanged; no Calendar Provider permissions or machine-local `.idea` changes were introduced, and existing IDE ignore rules remain effective.
