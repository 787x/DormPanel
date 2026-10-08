# DormPanel 0.1.1 release candidate

Changes since 0.1.0 only, from merged PR25–PR27. The feature set is frozen.
Android package `com.dormpanel.app` uses versionCode **3**, versionName **0.1.1**;
the Home Assistant custom integration is **0.1.1**. Signed-candidate physical
X08E evidence is now recorded in [PR29](PR29-release-acceptance.md), including
its observation limits. This does not publish a release.

## Hubei University CSV and Term Schedule Profiles

- Import the Hubei University personal timetable CSV export alongside existing
  ICS imports. Detection checks the file structure. Arbitrary CSV formats and
  XLS/XLSX remain unsupported; personal student header fields are not retained.
- Editable, persisted **Term Schedule Profiles** define Week 1 Monday, timezone,
  and date-effective period times. The built-in `2026-2027-1` preset can be edited
  or reset. Unknown terms require a profile; dates and times are never guessed.
  Seasonal phase selection uses each occurrence's actual date.
- CSV uses the existing preview, explicit confirmation, and atomic import path
  through the local document picker, temporary LAN upload, WebDAV exact file or
  newest-CSV folder, and HA relay. Timetable transfer limits remain 1 MiB.
  HA uses `schedule_csv` and requires `schedule_csv_v1`; existing ICS/APK relay
  semantics remain intact.
- A CSV import fingerprint includes the profile. Editing a profile requires
  re-import for local/LAN/HA base classes, or the next successful WebDAV sync.
  WebDAV detects profile changes even when remote bytes/ETag are unchanged;
  a missing/invalid profile keeps the last good timetable.

See [PR25](PR25-csv-term-profiles.md) for the supported format and preset.

## Settings and phone-assisted credentials

- HA **Advanced settings** starts collapsed, containing backend mode, relay
  identity/status, and helper bindings. **Preferred weather** stays directly
  visible. Expanding/collapsing does not save; hidden selections are preserved.
  Saving a first HA URL/token enables the HA backend; subsequent saves preserve
  existing backend choices.
- HA and WebDAV **Fill from phone** show a QR code/selectable URL for a ten-minute,
  one-shot form on the same trusted LAN. Submission fills the current device
  fields; Test and Save remain explicit device actions. Closing the dialog
  discards unsaved entries and stops the server.
- The temporary form uses plain HTTP, so use a trusted local network. Secrets
  travel in the POST body; saved credentials are never sent to the browser.
  Existing Android Keystore storage and aliases are unchanged.

See [PR26](PR26-settings-phone-entry.md).

## Persistent timetable adjustments and class editing

- Tap a timetable day heading to persist **No classes**, **Use another weekday**,
  or reset to **Normal**. A makeup day copies the weekday template from the same
  Monday–Sunday academic week, without following that source day's adjustment.
- Class details offer **This week** and **All weeks for this timetable item**.
  This-week edits target an effective class in the selected academic week and
  allow movement within that week. All-weeks edits target a stable local entry
  ID or imported `sourceId + seriesId`, not a class title. Multi-weekday imported
  ICS series retain their weekday pattern.
- Effective order is source/local class → all-weeks edit → target day adjustment
  → this-week edit. Imported CSV/ICS base rows remain immutable. Re-import/source
  replacement preserves matching edits; unmatched edits remain dormant. Deleting
  a source or local recurring class removes its associated class edits. Calendar
  events remain separate.
- **Linked periods** use a known profile's timezone and the phase effective on
  the actual occurrence date. Moving/copying across a phase boundary recomputes
  time. Profile changes refresh linked local edits immediately; base imported
  times still require re-import/sync. **Custom time** stays custom until explicitly
  switched back. Without a resolvable profile, custom clock editing is available;
  an existing linked edit becomes dormant if its profile is removed.
- Unchanged fields inherit the latest source/lower-precedence value. Clearing
  location, teacher, or user note stores an explicit empty value. Teacher and
  user note are local metadata where the source supplies none; opaque CSV
  description remains source information. Reset this-week/all-weeks edits reveals
  the lower-precedence values. Non-time edits preserve overnight ICS instants.

See [PR27](PR27-timetable-overrides.md).

## Upgrade and artifacts

Update over the existing package:

```bash
adb install -r DormPanel-0.1.1.apk
```

A normal APK update preserves existing app data, including dashboard, credentials,
relay identity, WebDAV settings, schedules, productivity data, and preferences.
The existing Room schedule migration **v2 → v3** retains calendar, manual class,
source, and imported-occurrence rows; upgrades from v1 chain through v2. Legacy
CSV sources may retain a null term key and recover it only from a CSV filename
and the exact parser-generated Hubei term calendar name, not arbitrary display names.
No new migration is added by this release-preparation PR.

Uninstall/reinstall remains **unsupported for migration of Keystore-backed state**.
Uninstall loses app-local data and Keystore secrets. If Android reports a signing
conflict, stop and keep the existing installation.

The approved signer certificate SHA-256 remains:

```text
32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7
```

| Candidate filename | Content |
| --- | --- |
| `DormPanel-0.1.1.apk` | Signed application, versionCode 3 |
| `DormPanel-0.1.1.apk.sha256` | APK SHA-256 and filename |
| `DormPanel-HA-0.1.1.zip` | Integration under `custom_components/dormpanel/` only |

`tools/build-release.ps1` checks the signer, package, version, non-debuggable APK,
HA manifest version, and required artifacts. Signing inputs and local candidate
artifacts remain outside Git. Release-preparation checks are recorded in
[PR28 verification](PR28-release-preparation.md); the installed runtime candidate
hashes and physical evidence are in [PR29](PR29-release-acceptance.md).

## Established checks and physical acceptance evidence

The behaviors above are implemented in merged PR25–PR27 and covered by repository
tests. PR25 records its earlier physical X08E testbed run; historical final 0.1.0
acceptance evidence remains in [PR24](PR24-release-acceptance.md) unchanged. Those
results do not establish acceptance of the signed 0.1.1 candidate.

PR28 automated and API 28 / 1280×800 emulator results are reported separately in
the verification record. Emulator results cannot establish Xiaomi service
coexistence, OEM media/brightness capabilities, or hardware long-running stability.

The signed 0.1.1 candidate has now been installed and exercised on the physical
X08E. [PR29](PR29-release-acceptance.md) records the unfiltered 98-case suite,
in-place upgrade/state preservation, phone entry, live HA CSV/WebDAV paths,
Xiaomi coexistence/boot and a >4h physical observation window. The daily baseline
was an approved signed 0.1.0-labelled build, not byte-identical to published
v0.1.0. Scheduled soak checkpoint gaps and optional unverified paths are disclosed
in that record; sparse snapshots are not continuous telemetry. No 0.1.1 tag,
GitHub Release or published asset has been created.
