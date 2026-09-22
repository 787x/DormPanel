# PR 11 — ICS timetable import

## Boundaries and storage

`ScheduleArtifact` accepts bounded bytes, a filename/type hint, provenance kind and an optional opaque locator. It has no transport or networking behavior. Credentials must never enter locator metadata. The SAF adapter reads `ContentResolver` streams once, without filesystem paths, broad storage access or persistent URI grants. Generic MIME providers are accepted. Reads, parsing, recurrence expansion and SHA-256 run on its worker; callbacks return on main and are discarded after Activity teardown.

`IcsScheduleImporter.parse` produces an immutable preview. `IcsScheduleParser` isolates biweekly 0.6.8 and its recurrence engine. No library objects enter source/UI models. `commit` uses the asynchronous schedule store; users explicitly confirm replacements after preview. A source has a UUID independent of VEVENT UIDs. Exact hash replacements are no-ops. Changed imports delete and insert only the selected source's occurrences and update its metadata in one Room transaction. A failed parse does not reach persistence; a failed insert rolls back the deletion. Deletion cascades only to that source's imported rows.

Schedule database v2 adds `import_sources` and `imported_timetable_occurrences`. Source metadata includes filename, name, provenance, optional locator/calendar name, SHA-256, timestamp, timezones, count and range. Occurrences preserve source/series/UID identity, original start, actual start/end milliseconds, source timezone, title, location, description and optional period label. Indexes cover source, start and end/start. Explicit `MIGRATION_1_2` only creates these tables/indexes; v1 `events` and `timetable` remain untouched. There is no destructive fallback or application version change.

## Parser choice and supported subset

[biweekly](https://github.com/mangstadt/biweekly) supports Android and Java 6+, works on this project's API 28/Java 11 bytecode target, and includes the Google iCalendar recurrence engine. Text parsing uses its existing vinnie tokenizer. Jackson JSON codecs are excluded; no JSON/XML importer or remote timezone loader is used. The biweekly JAR is 639,121 bytes; it adds only vinnie as a runtime dependency. This is smaller and simpler than adopting iCal4j's wider runtime dependencies here. Dependency objects remain replaceable behind the parser interface.

Supported: UTF-8 iCalendar 2.0, VEVENT UID/SUMMARY/DTSTART/DTEND, UTC and resolvable IANA TZIDs, fixed embedded VTIMEZONE, text escaping/folding, LOCATION/DESCRIPTION, finite DAILY/WEEKLY RRULE with COUNT or UTC UNTIL, INTERVAL, plain BYDAY and WKST, date-time EXDATE/RDATE. DTSTART is always part of the recurrence set unless excluded; RDATE does not erase it. UNTIL is inclusive and interpreted as UTC. Each VEVENT stays independent even when summaries match. Repeated UID with a different DTSTART stays independent; ambiguous duplicate masters are rejected. DTEND defines exact duration. DESCRIPTION period text is only optional presentation metadata.

The whole file is rejected with a readable error for malformed properties or unsupported recurrence semantics. Currently unsupported: RECURRENCE-ID overrides, EXRULE, RDATE PERIOD, all-day/floating dates, DURATION-only events, cancelled/scheduling-message exports, subdaily/monthly/yearly RRULEs, ordinal BYDAY and other BY* filters, and recurring embedded VTIMEZONE definitions. For DST zones use IANA TZIDs without embedded recurring definitions; fixed VTIMEZONE (including the supplied WakeUp export) is supported. Timezone recurrence is refused by the streaming admission pass before timezone objects can evaluate it. Nonexistent calendar dates and unresolved zones are refused instead of normalized/guessed.

VALARM is detected and preview explicitly states reminders are ignored. No notifications, AlarmManager work, attachments, URL opening, TZURL retrieval, polling or automatic synchronization are implemented. WebDAV, LAN upload/QR, HA relay and their credentials remain deferred; they can later supply the same artifact.

## Resource policy

- At most 1 MiB input (both provider size and streaming byte count checked), 256 VEVENTs, 8,192 properties and 16,384 characters per property. Component nesting is restricted to the supported calendar/event/alarm/timezone structure.
- At most 10,000 resulting occurrences and 20,000 candidate operations per artifact. No unbounded recurrence; COUNT/UNTIL required for every RRULE. INTERVAL is 1–366.
- A finite rule with an estimated expansion span beyond 3,660 days is rejected, not truncated. Supported filters are restricted so even a recurrence iterator with no matches cannot search indefinitely. Very sparse COUNT rules may be conservatively rejected by the preflight span estimate.
- At most 16 stored sources and 50,000 total stored imported occurrences. This bounds the single source-owned snapshot. Cards share a sorted range index with binary search and do not load/scan the complete history per render. There are no periodic database queries. Existing one-shot boundary scheduling remains.
- Expanded/stored occurrence text is also capped at 4 million characters across sources, checked during expansion and again within the commit transaction. This prevents a small recurring event with a huge description from amplifying into hundreds of megabytes of stored/loaded text.

## Presentation

Timetable retains a displayed week in `ScheduleSession`, with previous/next/This week navigation. Manual entries project into every week; imports use actual dates. Source instants never change with the device timezone: presentation converts those instants into the current device zone, while preview/details disclose the source/display zones. Imported blocks are subtly labelled and open read-only source-aware details. Source management shows name/file/import time/count/range and confirms source-scoped deletion.

Timetable cards combine manual and imported current/next classes, including a distant next imported class when the larger card has no more classes today. Imported items open details, never the weekly editor. Calendar cards continue to use only Calendar events.

## Regression fixture

`app/src/test/resources/schedule/wakeup.ics` is copied from the supplied `课表.ics`, with no semantic edits. It is also packaged only in the instrumentation test APK via the test assets source set. Expected results: 7 independent series, 25 occurrences each, 175 total; Asia/Shanghai; 2026-09-02 13:30 through 2027-02-23 17:30 in that zone. Repeated math/management titles remain separate. Chinese titles/locations, DESCRIPTION newlines, period labels and ignored DISPLAY reminders are asserted. UTC UNTIL excludes the following week's class rather than interpreting its UTC boundary as local wall time.

Unit tests also cover interval/count/exclusions/additions, UTC and DST timezone dates, folded/escaped text, duplicate identities, rejection policy and resource limits, week/card projection and overnight current classes. Instrumentation covers v1 upgrade/schema validation, initial/no-op/replacement imports, rollback after DELETE followed by an injected failing INSERT, isolated deletion, close/callback behavior and disk reopen. All instrumentation databases are uniquely named test databases or in-memory overrides, never the production schedule database.

## Executed verification

- `gradlew assembleDebug`: passed. Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (about 21.3 MiB overall; biweekly plus vinnie JARs total about 674 KiB, with Jackson excluded).
- `gradlew testDebugUnitTest`: passed, 123 tests, including the real fixture and deterministic parser/projection cases.
- `gradlew lintDebug`: passed, 0 errors and 24 warnings (resource/localization/dependency/style warnings; report retained under `app/build/reports`).
- `gradlew connectedDebugAndroidTest`: full existing/new persistence suite passed, 40 tests, API 28 X08E AVD at 1280×800/160 dpi. The additional `ScheduleImportAndroidTest` then passed both through Gradle and direct instrumentation. Its databases use the existing in-memory isolation rule.
- After the final expanded-text budget hardening, debug/test APK assembly, all 123 unit tests and lint passed again; direct instrumentation reran all 4 import persistence/UI tests successfully, including rollback and text-budget refusal. No full-suite rerun was needed for that isolated importer/store refinement.
- The import UI test exercised preview before writes, source metadata, light/dark appearance, week navigation, imported read-only detail, manual/calendar coexistence and source deletion. Captured screenshots were visually inspected; representative images are under `docs/pr11-review/`.
- Actual SAF verification on the emulator: selected a byte-identical WakeUp copy in Downloads; preview showed 7/175 and the ignored-reminder warning; confirmed import; created a manual class; force-stopped/relaunched; confirmed both persisted; selected the same file/source again and observed **Timetable unchanged**, still 175 classes.
- A separate modified fixture changed the two Friday series to `COUNT=2;INTERVAL=2`, producing 129 classes. Actual preview and explicit **Replace source** confirmation were exercised; the original source retained its name with 129 classes. Source management deletion left the second import and manual class intact. Week browsing showed B310 math on September 18 but not September 11. These were emulator checks, not X08E hardware claims.
- `git diff --check`: passed. No `.idea`, manifest/permission or application-version changes. Instrumentation never opens/owns the production `schedule.db`; manual emulator exercises used explicitly named PR11 test sources through the normal app UI.

The original and repository fixture working-copy SHA-256 match: `6c2acd2acb021e95532d681451488116121da942b5efad89838fb076f3f85cae`. Screenshots use the emulator's GMT display zone, clearly disclosed in preview; the source remains Asia/Shanghai and the asserted instants are identical across device timezones.
