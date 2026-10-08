# PR29 — DormPanel 0.1.1 final physical X08E evidence

Recorded 2026-10-07–08, Asia/Shanghai, against frozen runtime source
`e02ddfb3bb26b522ab2b3925d26bf5ca5951a827`. This record separates automated,
device-observed and human-observed evidence. It does not publish a release.

## Runtime acceptance candidate

The existing signed candidate was independently verified and used unchanged;
no release rebuild occurred during diagnosis or final acceptance.

| Field | Verified value |
| --- | --- |
| APK | `DormPanel-0.1.1.apk` |
| APK SHA-256 | `14e36f9b73051803513fef196b776802e91eae6407d7e3cea8fd71ed6700fd52` |
| HA integration ZIP | `DormPanel-HA-0.1.1.zip` |
| ZIP SHA-256 | `c5bfa82cd60d153c76cdc6b2317b791b2bb228f6a114e828823bcae176171ef7` |
| Package / versionCode / versionName | `com.dormpanel.app` / `3` / `0.1.1` |
| Debuggable | false |
| Signer certificate SHA-256 | `32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7` |
| Embedded source SHA | `e02ddfb3bb26b522ab2b3925d26bf5ca5951a827` |

Build-tools 37.0.0 verified the signature for minSdk 28 and exact APK metadata.
The APK sidecar matched. ZIP CRC, exact ten-file source set, source byte identity
and integration manifest 0.1.1 passed. These hashes identify the installed runtime
candidate, rather than the earlier PR28 APK.

## AUTO — automated and isolated physical evidence

- Android debug build/lint passed: zero lint errors, 100 warnings.
- Actually rerun JVM tests: 282 passed, zero failures/errors/skips.
- HA Python tests: 13 passed; frontend JS syntax and `git diff --check` passed.
- On the physical X08E/API28/1280×800, the focused
  `DeviceControlAndroidTest#activityRecreationAwakeAndBlackoutRestore` passed
  independently three times with `--info --stacktrace` and continuous logcat.
- The subsequent complete **unfiltered** `tools/test-x08e.ps1` run passed
  **98/98**, zero failures/errors/skips, Gradle exit 0 (7m 18s). Its case set matched
  the original 98 cases, including the X08E mute assertion and every phone-entry
  test. Instrumentation targeted `com.dormpanel.app.testbed`; daily package and
  media/mute/brightness-mode/screen-timeout restoration checks passed.
- Earlier empty UTP `status -1` results did not reproduce. Their root cause
  remains unknown; no harmless-infrastructure or product-regression attribution
  is claimed. Logged successful runs showed normal target-runner completion,
  followed by cleanup, with no observed crash/ANR/abnormal target termination or
  ADB transport loss.

The opt-in real-light test and granted-WRITE_SETTINGS test can return without
their prerequisites. Their JUnit entries do not establish those live paths;
dedicated live HA/Xiaomi observations below are separate evidence.

## LIVE — daily provenance, baseline and in-place upgrade

The physical upgrade was **current daily 0.1.0-labelled signed build →0.1.1**.
The installed baseline was debuggable and signed by the approved certificate,
but was not byte-identical to canonical published v0.1.0:

| Baseline APK | SHA-256 |
| --- | --- |
| Installed daily | `6884849fd660ef23f731c2994d29732f5396bb3461e0573d05aaf5501c81fa20` |
| Local canonical v0.1.0, matching published asset digest | `2380fe5bda3ca2949c08fca3964f0a2aec5943dc61829756f1e97db0f104a635` |

No reinstall or downgrade was used to normalize this baseline. This run does
not claim physical upgrading of the canonical published v0.1.0 APK.

Before update, the dashboard and full timetable were visible. Existing schedule
had one source, 13 series/131 occurrences. Read-only aggregate queries established
schedule schema **v2** and no persisted productivity owners, Todos or nonempty
Memos; timer was Ready 05:00. No private credential or row-content dump was made.
HA was CONNECTED and WebDAV Configured. Settings were dark, opacity 20%, automatic
system brightness, Follow system, override 45%, Keep awake and Start after boot
ON; media 10/20, unmuted, blackout inactive.

Only `adb install -r` was used. Installed base APK hash matched the exact
candidate; code 3 / name 0.1.1 and non-debuggable confirmed. First install time stayed
**2026-09-24 22:33:21**; last update advanced from **2026-09-27 10:19:15** to
**2026-10-07 20:14:06**. MainActivity started normally. Schedule loaded after the
actual v2→v3 path, with original source / 131 occurrences visible and no observed
migration/startup/storage error. Dashboard, empty productivity/default timer,
appearance/startup/device settings and saved HA/WebDAV configuration survived.
HA reconnected, original relay identity remained usable, and WebDAV authenticated
using its preserved credentials. Nonempty daily Todo/Memo retention was not
physically exercised because that baseline data was absent; isolated AUTO
persistence/migration tests are distinct evidence.

## LIVE — PR25/PR27 and cleanup

- Exact repository sanitized Hubei CSV reached device preview through real HA
  `schedule_csv`: **13 series/131 occurrences**, Asia/Shanghai, both phase dates.
  Local confirmation imported a separate disposable source; original source was
  never replaced.
- Term Schedule Profile editing saved a temporary Week 1 Monday change and
  showed edited status; Reset restored the initial built-in profile.
- No classes saved and Reset restored Normal. Use another weekday copied Tuesday
  2026-10-06 to Thursday 2026-10-08: periods 7–8 recomputed from **16:25–18:05** to
  **15:55–17:35**, demonstrating the date-effective phase cutover.
- This week title edit persisted after force-stop/restart and appeared on both
  the full page and dashboard card. All weeks title edit appeared in the next
  week; Custom time 10:00–11:35 persisted after a second force-stop/restart.
- Reset this week revealed the all-weeks value; Reset all-weeks restored imported
  inheritance. Source information/counts remained intact. This is functional
  LIVE recovery, supported separately by AUTO immutable-source tests, not a
  private byte-level database comparison.
- Temporary day adjustments, profile changes, class edits, imported test sources,
  WebDAV binding and HA transfer were cleaned up. Original131-class source,
  configuration and real device state remained. Final native UI navigation and
  source/state checks succeeded and returned to the dashboard.

## HUMAN LIVE / LIVE / AUTO — phone entry

The human separately confirmed completion of real phone-camera HA and WebDAV
QR scans and disposable submissions, instructed to check no browser Test/Save/
connect and never Android Save. Both Android dialogs showed Received from phone.
They were dismissed without Save; reopening restored the original URLs and
empty secret-entry fields. HA stayed usable; WebDAV connection test succeeded
with preserved credentials. Advanced started collapsed and Preferred weather
remained visible outside it. AUTO98-case suite independently covered field
mapping/no-save behavior. Exact human-entered disposable bytes were not separately
compared; no real secret was requested or displayed.

## LIVE — HA, WebDAV and Xiaomi coexistence

Ten exact HA ZIP files were deployed through the normal authenticated Filebrowser
workflow; visible manifest 0.1.1 and normally restarted HA verified. Existing
entry/registration survived. Non-secret registration fields reported
**app_version 0.1.1** and **schedule_relay_v1, schedule_csv_v1, apk_install_v1**.
Served frontend matched candidate bytes; temporary browser cache bypass was
restored. Real CSV transfer progressed **preview_ready→imported**. Daily native
HA light control was independently seen on the HA server, then restored to its
prior On / brightness 1% state; live sensor data also populated.

Android authenticated against existing WebDAV configuration, browsed a CSV the
human explicitly confirmed sanitized, imported it into a separate local source
and performed Sync now→Already up to date. The original remote file was not
modified. Optional profile-sensitive live resync was not exercised.

Open MIUI Home reached the original Xiaomi launcher. The human explicitly
confirmed normal visible XiaoAI response **and actually heard wake-word audio**,
and successful **Bluetooth Mesh control with prior state restored**. Native Mesh,
Mico/launcher/player/device and Bluetooth services were observed. Start after
boot was already ON: one normal reboot reached DormPanel through its existing
boot path, after native OEM startup ordering; boot log requested launch and
MainActivity appeared without an explicit replacement launch. Prior settings and
live/local state returned. Native alarm firing was not separately tested.

## LIVE observations / AUTO measurements — physical run

Authoritative observation window: **2026-10-08 09:03:16–13:13:57 Asia/Shanghai**,
**15040.93s (4h 10m 40s)**. Same daily PID29106 was present at every successful
observation. Final native `ps` reported process start 08:54:07 and elapsed 4h 20m 05s;
kernel start ticks matched the recovery observation, supporting continuity of
that process across the window. No app/device restart was performed for this run.

| Actual observation | Idle CPU | PSS (KiB) | Threads | Clock / HA / services |
| --- | --- | --- | --- | --- |
| 09:03:16, start | 0.0% | 53818 | 29 | 09:03; connected; present |
| 11:48:23, actual recovery (~+2h45m) | 0.0% | 64893 | 30 | 11:48; connected; present |
| 13:13:57, actual final (~+4h10m) | 0.0% | 64920 | 31 | 13:14; connected; present |

CPU used the second snapshot of a bounded 5s native-top observation; PSS used
dumpsys meminfo. Later PSS plateau differed by 27KiB; observed threads remained
29–31. No runaway CPU/memory/thread trend or daily crash marker was observed at
the recorded points. HA CONNECTED, WebDAV Configured, Xiaomi Mesh/Launcher/
Bluetooth services and baseline settings were present. UI accepted final page/
source/control navigation without observed ANR. Driver observation latency is
not a frame-rate benchmark.

Clock/date and timetable date agreed on 2026-10-08. The morning 09:55–11:35 row was
present initially and correctly absent by 11:48; next 15:55–17:35 and 19:00–20:40
remained, including the Oct8 afternoon phase timing. Temporary labels absent.

**Observation limits:** planned +1h/+2h metrics were unavailable during ADB
absence. Their values are **UNVERIFIED**, not reconstructed from the recovery
sample. The host collector did not retain its scheduled final point; final
checks above were taken at their actual later time after confirming the collector
was no longer running, without restarting the timer or replacing its JSON.
Thus this record establishes a >4h device/process run and healthy recorded
snapshots, not four successful scheduled checkpoints or continuous telemetry.
Unobserved transient CPU/reconnect behavior cannot be excluded from sparse data.

## Final state and scope

Final checks retained code 3 / name 0.1.1, unchanged install/update timestamps,
original 131-class source, no disposable labels, HA/WebDAV, dark / opacity 20%,
automatic brightness/Follow system/Keep awake/startup, override 45%, media 50% and
system timeout. Dashboard returned to foreground. Artifact hashes and frozen
production/test/build source unchanged; no data/credential loss or runtime
regression observed in the exercised checks. Optional resync, native alarm firing,
nonempty daily productivity and missed checkpoint metrics remain explicitly
unverified as above.

Only sanitized documentary evidence is committed. Local raw logs/captures and
artifact files remain outside Git. This PR changes documentation/status only:
no production/tests/dependencies/build logic/permissions/migrations/release
identity, tag, GitHub Release or published asset.
