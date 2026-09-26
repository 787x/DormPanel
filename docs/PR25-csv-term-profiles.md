# PR 25 — Configurable Term Profiles + Hubei University CSV Timetable Import

## Scope

Adds timetable import from the **Hubei University personal timetable CSV** export only.
This is not a general CSV timetable importer and does **not** support XLS/XLSX or
arbitrary CSV layouts. Format detection uses a structural signature (heading,
`学年学期:…`, Monday–Sunday header, period rows, `…周[…节]` course cells), never the
filename alone.

Existing ICS import behavior, fingerprints, and already-imported data remain compatible.

## Sanitized regression fixture

`app/src/test/resources/schedule/hubei_2026-2027-1.csv` is a sanitized copy of a real
Hubei University personal timetable export. Personal name/class/major header fields were
replaced with obvious test values. Structure, quoting, UTF-8 BOM, multiline course cells,
course titles, week/period semantics, and the military-training note are preserved.

Oracle for this fixture with the built-in 2026-2027-1 profile:

- 13 course series
- 131 concrete class occurrences
- timezone Asia/Shanghai
- note `军事训练 4-16周` produces a warning and **no** occurrence
- earliest class: 大数据导论（公选）, Week 1 Thursday periods 9–10 → 2026-09-03 19:00–20:40
  (must **not** end at 21:35 just because the CSV row covers periods 9–11)
- a `[09-10-11节]` class ends at 21:35
- mid-week seasonal cutover is per occurrence date:
  - 管理科学与工程导论 Thursday 7–8 on 2026-10-01 uses summer times 16:25–18:05
  - the same series on 2026-10-08 uses autumn times 15:55–17:35
- last occurrence is Friday 2026-12-18 (Week 16); classes are never generated past the
  CSV's declared weeks

Student personal header data is never persisted, logged, or sent in HA metadata.

## CSV structure and parsing

A lightweight RFC 4180-style reader handles quoted multiline fields; the file is never
parsed with `line.split(",")`. Decoding order:

1. UTF-8 BOM (stripped)
2. strict UTF-8
3. GB18030 fallback only if UTF-8 is genuinely invalid (tested)

Malformed input is rejected with a clear error, never silently replaced.

Structure parsing produces series of `title / termKey / weekday / weeks / periodNumbers /
location / description` and is kept separate from absolute-time resolution.

Course cells use the range week form actually present in the export (`4-16周`) and period
forms `[01-02节]`, `[09-10节]`, `[09-10-11节]`. The period specification inside the cell is
authoritative for first/last period; the broader row time range never extends a 09–10
class through period 11. The first normal line after the week/period line is the location;
later nonblank lines are retained as description metadata only.

The notes row `军事训练 4-16周` has no weekday/period and becomes a preview warning only.

## Term Schedule Profiles

A lightweight persisted profile maps week/period structure onto dates and times:

- `termKey`
- `timezone`
- `week1Monday`
- date-effective `phases`, each with `periodNumber → start/end`

Validation rejects blank/invalid term keys, unknown timezones, a Week 1 Monday that is not
a Monday, no phase effective at term start, duplicate phase effective dates, and invalid
period bounds. Phases may start mid-week.

**Built-in editable preset — 2026-2027-1**

| | |
|---|---|
| termKey | `2026-2027-1` |
| timezone | `Asia/Shanghai` |
| week1Monday | `2026-08-31` |

Phase 1 (summer) effective `2026-08-31`:

| Period | Time |
|---|---|
| 1 | 08:00–08:45 |
| 2 | 08:55–09:40 |
| 3 | 09:55–10:40 |
| 4 | 10:50–11:35 |
| 5 | 14:30–15:15 |
| 6 | 15:25–16:10 |
| 7 | 16:25–17:10 |
| 8 | 17:20–18:05 |
| 9 | 19:00–19:45 |
| 10 | 19:55–20:40 |
| 11 | 20:50–21:35 |

Phase 2 (autumn) effective `2026-10-08`:

| Period | Time |
|---|---|
| 1 | 08:00–08:45 |
| 2 | 08:55–09:40 |
| 3 | 09:55–10:40 |
| 4 | 10:50–11:35 |
| 5 | 14:00–14:45 |
| 6 | 14:55–15:40 |
| 7 | 15:55–16:40 |
| 8 | 16:50–17:35 |
| 9 | 19:00–19:45 |
| 10 | 19:55–20:40 |
| 11 | 20:50–21:35 |

These dates/times are a **preset only**. Future terms do not inherit them as facts.

Profiles are non-secret local configuration (SharedPreferences), survive restarts/upgrades,
and are user-editable. The 2026-2027-1 override can be reset to the built-in values from
**Sources → Term schedule profiles**.

For a concrete occurrence:

```
actualDate = week1Monday + (week - 1) weeks + weekday offset
phase      = latest phase with effectiveFrom <= actualDate
start/end  = that phase's first/last requested periods
```

Phase selection is per occurrence date, never per whole semester.

### Unknown/future terms

If a supported Hubei CSV names a term with no profile, DormPanel does **not** guess Week 1
or period times. The user is prompted to configure the term (Week 1 Monday, timezone,
phases, period times) before an import preview is generated.

## Import fingerprint

The existing `ImportPreview.sha256` / source comparison remains the storage mechanism.

- **ICS**: raw-file SHA-256 (unchanged behavior).
- **CSV**: an **effective import fingerprint** that deterministically covers
  - raw CSV bytes
  - parser ID/version (`hubei-personal-csv-v1`)
  - term key, timezone, Week 1 Monday
  - all effective phase dates and period start/end times

Canonical ordering is independent of locale/map iteration (phases sorted by
`effectiveFrom`, periods sorted by `periodNumber`).

Therefore: same CSV + same profile → same fingerprint (no-op replace); same CSV + changed
Week 1 or phase/period time → different fingerprint (atomic replace/re-time).

## Transports

CSV reaches the same `ImportPreview → explicit confirmation → ScheduleSource → atomic Room
import` pipeline as ICS. There is no separate CSV database and no Room schema change.

| Transport | CSV support |
|---|---|
| Local document picker | Accepts `.ics` and `.csv`; generic MIME fallback kept |
| Temporary LAN upload | Accepts `.ics` and `.csv` within the existing 1 MiB limit |
| WebDAV exact file | Exact `.ics` or `.csv` |
| WebDAV folder | `FOLDER_LATEST_ICS` unchanged; new `FOLDER_LATEST_CSV` |
| Home Assistant relay | New `schedule_csv` kind + `schedule_csv_v1` capability |

### WebDAV CSV profile-aware sync

A WebDAV CSV binding stores non-secret metadata: format, detected term, and the profile
fingerprint used for the last successful import. If remote bytes/ETag are unchanged but
the local term profile changed, the next sync forces a fresh download and re-import.
Missing/invalid profile during auto-sync keeps the last good timetable, records a clear
status, and never partially replaces the source.

Profile changes become effective for a WebDAV CSV source after a successful manual or
automatic sync even when the remote file itself did not change.

### Home Assistant

- `schedule_csv` is a distinct transfer kind; `schedule_ics` is not reinterpreted.
- Client capability `schedule_csv_v1` is advertised only when CSV support is present.
- CSV transfers are rejected/not offered to screens that do not advertise `schedule_csv_v1`.
- Same 1 MiB timetable transfer limit and the same terminal outcomes
  (`preview_ready`, `imported`, `dismissed`, `rejected_invalid`).
- A valid CSV that needs a missing term profile is handed to the profile configuration
  flow; cancel results in `dismissed`, never `imported`.
- No second WebSocket. APK relay and ICS relay behavior are unchanged.

## Profile edits vs already imported classes

Editing a profile does **not** retroactively mutate stored occurrence rows (DormPanel does
not retain local source-file bytes):

- local / LAN / HA imports require **re-importing the CSV** to regenerate occurrences
- WebDAV CSV sources can regenerate on their **next successful sync**

This is stated in the profile UI.

## Out of scope / not supported

- XLS/XLSX
- arbitrary CSV timetable formats
- guessing unknown scheduling semantics
- retaining student name/class/major from the CSV header

## Verification (this PR)

### Tests actually executed

- `gradlew assembleDebug testDebugUnitTest lintDebug` — **passed** (250 unit tests, 0 failures; lint clean)
- `tools/test-x08e.ps1` — **passed** on physical X08E (76 instrumentation tests, 0 failures; daily `com.dormpanel.app` left installed)
- Home Assistant `python -m unittest discover -s tests` — **passed** (13 tests)
- `node --check custom_components/dormpanel/frontend/panel.js` — **passed**
- `git diff --check` — **passed**

### Physical X08E checks observed

- Full testbed instrumentation suite green on X08E (API 28, 1280×800), including existing schedule import/WebDAV/HA relay tests after CSV/profile additions.
- Daily released `com.dormpanel.app` installation was not uninstalled or overwritten.

### Live HA/WebDAV checks

- Not performed against a real Home Assistant / WebDAV server in this environment.
- Automated HA relay tests (13) and WebDAV client/sync unit + instrumentation tests cover the CSV paths.

### Unverified external paths

- Real WebDAV CSV import/sync against a live server.
- Real HA `schedule_csv` relay to a live HA instance.
- Manual on-device CSV import through the SAF picker UI (covered indirectly by instrumentation; full manual walkthrough not repeated here).
