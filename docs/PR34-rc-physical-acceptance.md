# PR34 — 0.1.2-rc.1 physical acceptance

## Decision

**RC NOT ACCEPTED — release blocked.** The real WebDAV endpoint accepted and
performed writes that must have returned HTTP 412. Independent device checks
below do not override this failed safety gate. No tag or GitHub Release is
authorized by this record; PR34 must remain unmerged during acceptance.

This is an acceptance record, not a product fix. Testing started on
**2026-10-21, Asia/Shanghai**, from merged PR33/main
`a531fa525c265aea6c6234c5061e53de15126501`. GitHub independently reported PR33
merged at 2026-10-20 23:28:11 +08:00; no merge was performed here.

## Frozen candidate identity — PASS (native host tools / physical installation)

`tools/verify-release.ps1` was rerun before installation using Android build-tools
37.0.0. Native apksigner verification, exactly one approved signer, aapt2 package,
version, min/target SDK, non-debuggable checks, and exact final-byte/filename
sidecars passed. Expected frozen hashes were also checked before each install.

| Candidate | Bytes | SHA-256 | Package / code | SDK |
| --- | ---: | --- | --- | --- |
| X08E | 16,035,756 | `ba7135315d251e0f2425e1eeeb8e11748410e18c74c9c0cb29c506fe99a0a20f` | `com.dormpanel.app` / 4 | 28 / 37 |
| Phone | 9,736,703 | `16a6f581fd87d9f7a88ca58b031309554a27f5dc00487dc32482d87a45ade79c` | `com.dormpanel.timetable` / 2 | 28 / 37 |

Both names are `0.1.2-rc.1`; certificate SHA-256 is
`32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7`.
Both embedded `META-INF/version-control-info.textproto` entries independently
read **`e3423d212004e22b2be2bac4ab8fcc8a9e6b7d92`**. This is deliberately different
from the acceptance branch HEAD. Installed base APKs were pulled from **both real
devices** and their full hashes matched the respective frozen candidates.
No release candidate was rebuilt or replaced; isolated test APKs are separate
evidence and never substitute for a candidate installation.

## Real environment and baseline

| Device | Observed conditions |
| --- | --- |
| X08E | `mico_x08e`, Android 9 / API 28, physical 1280×800 landscape, authorized USB ADB |
| Phone | Xiaomi `MIX_2S_Emerald_Edition`, `polaris`, Android 13 / API 33, physical 1080×2160, 440 dpi, portrait, authorized USB ADB |
| WebDAV | User-supplied HTTPS endpoint/account; default TLS certificate verification; independent `.NET HttpClient` requests from the development machine |

No emulator result is treated as physical evidence. Secrets, full private
courses, UI dumps, screenshots and APKs remain outside Git. Device-side existing
`su 0`/sqlite3 was used **read-only** for aggregate and digest inspection on X08E;
no database or preference file was written through root. This diagnostic access
is not an application requirement. The phone's unrelated apps/data were untouched.

### X08E actual in-place upgrade

The pulled pre-upgrade APK was `com.dormpanel.app`, code **3**, name **0.1.1**,
non-debuggable and approved-signed. Its SHA-256 was
`5f3a14ea040f659ad573dc761dc585f4de5a7b3617c673b1e1c295ef071d3cda`.
It differs from PR29's canonical candidate
`14e36f9b73051803513fef196b776802e91eae6407d7e3cea8fd71ed6700fd52`;
this run must not be described as upgrading byte-identical canonical 0.1.1.
First install was 2026-09-24 22:33:21; previous update was
2026-10-17 17:18:55 (device local time). After update, first-install time remained
unchanged and last-update time became **2026-10-21 08:42:47**.

The baseline read was taken at **08:42:27 +08:00**. Only normal
`adb -s <X08E> install -r <frozen-X08E-APK>` was used. No uninstall, clear-data,
downgrade, database replacement or backup restoration occurred. MainActivity's
normal launch returned `Status: ok`. The actual schema changed **v3 → v4**;
SQLite `quick_check` returned `ok` for schedule, productivity and Dashboard.

| Actually existing baseline | Immediate post-upgrade result |
| --- | --- |
| 1 imported source, 13 distinct source/series pairs, 131 occurrences | Counts retained; ordered complete-row SHA-256 digests of sources and occurrences unchanged |
| Local entries 0, events 0, date adjustments 0, class overrides 0 | Empty counts/digests retained; no nonempty migration claim |
| Productivity: 1 owner, 0 Todos, 0 nonempty Memos; Timer 300000/300000 ms, READY | Complete owner/Todo content digests unchanged; no nonempty Todo/Memo migration claim |
| Dashboard cards and revision | Ordered complete-row digests unchanged |
| Appearance/device controls/startup/term profiles | Preference-file digests unchanged |
| HA credentials/settings, relay identity, WebDAV credentials/settings | Encrypted credential and preference-file digests unchanged; digests alone do not prove successful decryption or remote authentication |

The pre-upgrade Dashboard and representative imported class detail were observed:
the 2026-10-21 Wednesday afternoon class retained its 14:00–15:40 timing, period
label and source information. Private content is not reproduced here. Existing
live temperature/humidity values and light Off / brightness 1% were visible before
upgrade; they are not by themselves proof of post-upgrade live control.
System baseline was automatic brightness (mode 1), timeout 2147483647 ms and
media 9/20. Automatic brightness values may vary without an application write.

After the complete isolated physical suite, the daily source/occurrence,
Dashboard, productivity and preference digests were compared again. They remained
unchanged apart from the expected automatic screen-brightness value. Daily
force-stop → normal reopen also succeeded. On a normal reboot requested at
**08:58:12**, native Xiaomi/third-party launcher startup occurred first; at
**08:59:43.275** `DormPanelBoot` recorded `Boot launch requested`, followed by the
daily MainActivity in foreground. No explicit app launch was used to create that
boot result. Startup policy remained ON. Reboot readback retained all original
schedule/productivity/Dashboard content digests and encrypted credentials; light
memory changed with live device state, so it is not claimed byte-identical after
live controls. Automatic brightness also varied; mode, timeout and volume remained.

The post-reboot Control Center showed **HA CONNECTED**, saved WebDAV Configured,
override 45%, Follow system/Keep awake and boot ON. Its original saved WebDAV
account's **Test Connection → Connection successful** worked without re-entering
or saving credentials, establishing app-side decryption/authentication. This
read-only account check is separate from the failed conditional-write preflight.
The Dashboard showed live sensor values. A real HA light was observed at
**On / 1% / 2700 K** immediately before this operation; native card control changed
it Off, then restored **On / 1% / 2700 K** with state readback. Earlier pre-upgrade
Off / 3216 K is not substituted for the actual pre-operation state. No independent
HA-server trace or emitted-light measurement was collected in this run. Relay
identity bytes survived; a complete relay submission was not repeated.

HOME reached `com.xiaomi.micolauncher/.Launcher`. Once the formal candidate was
back on Dashboard after the full suite, the human explicitly reported **all three
requested checks passed**: audible XiaoAI reply, native Mesh control with prior
device state restored, and temporary native alarm firing followed by cancellation/
deletion. These are **HUMAN physical observations**, not automated assertions.

### Phone actual installation and local data

No `com.dormpanel.timetable` installation was present initially. The frozen signed
Phone APK installed successfully and its declared `TimetableActivity` launched
normally. An initial attempt to launch nonexistent `PhoneActivity` returned
`Error type 3`; using the manifest's real activity succeeded without any APK change.

Native portrait UI showed week/day navigation, add-course, date-adjustment and
management controls. A disposable **PR34-Math** local course was added through
the normal UI, visible as 09:00–10:00, then survived actual
`am force-stop com.dormpanel.timetable` followed by normal activity launch.
This establishes local course persistence on this phone; it does not establish
pending-cloud receipt, account, reboot or OEM scheduler behavior.

Subsequent **normal native UI** checks on the signed candidate:

- The actual Android system document picker opened Downloads and read repository
  fixtures `wakeup.ics` and sanitized `hubei_2026-2027-1.csv`, copied under PR34
  names. Preview showed **7 series/175 occurrences** for ICS and **13/131** for CSV,
  with Asia/Shanghai and the two CSV schedule phases. Both confirmed imports
  appeared in source management. This did not use an intercepted picker Intent.
- Term management displayed the built-in 2026-2027-1 profile and phase timings;
  selecting it displayed teaching week 8. Custom term mutation was not exercised.
- Edited **This week** for PR34-Math to PR34-Math-W8; week 8 showed the local edit,
  next week 9 retained PR34-Math. Reset this week restored inheritance.
- No classes date adjustment hid that day's classes; **RESET DAY TO NORMAL**
  restored them. The restored state was explicitly checked rather than inferred
  from tapping Save.
- System night mode was temporarily enabled. After a normal app restart, a
  screenshot was visually inspected: black background with legible controls.
  System night mode was restored to its observed original `no` state.
- The phone underwent a normal reboot; its locally added course, selected term,
  and both imported schedules were present afterward. Reopening required no
  application reinstall or data restoration. Wi-Fi was returned to its initial
  enabled state after a system-panel interaction; phone UI taps during that panel
  are not attributed to app behavior.
- Deleted the disposable recurring class and, through normal confirmed source
  deletion, both disposable imported sources. A final force-stop/reopen showed
  no courses. No pre-existing phone timetable existed; no unrelated data was
  deleted. The two transferred fixture files were removed separately.

The approved-signed existing test APK was additionally installed alongside the
unchanged candidate. `PhoneBidirectionalRestartTest` ran **seed 1/1**, real
`am force-stop`, then **verify 1/1**, with one recorded UUID. This is physical
process/persistence evidence against **isolated synthetic Room state**, including
offline edits, deletion, catalog and a simulated uncertain receipt. It is not a
real WebDAV commit/receipt recovery or OEM JobScheduler test.

Phone encrypted real-account configuration/persistence and credential-error
handling remain **UNVERIFIED**. No unsafe-server shared binding was configured.

## Real WebDAV safety gate — FAIL

Only a newly created, recognizable subdirectory below the user-authorized
course folder was written:
`pr34-rc1-20261021-084449-eac13df2/`. It contains only the disposable
`pr34-conditional-probe.txt`, with synthetic PR34 text. No daily ICS/CSV file,
production shared head or historical snapshot was modified or deleted.

The server failure is independently observable without uploading any courses:

| Time (+08:00) / request | Actual result | Required result / interpretation |
| --- | --- | --- |
| 08:44:48–49, parent `PROPFIND Depth: 0`, child MKCOL | 207, then 201 | Isolated collection available |
| PUT new probe, `If-None-Match: *` | 201 | Conditional creation succeeds |
| GET v1 | 200; strong ETag `"18e064ceade23c422a"`; SHA-256 `b11132239eafccf2c9e732ff541bdf871a973ccc39967221ee58177f9725e119` | Strong-looking ETag alone is insufficient |
| PUT **same** probe, `If-None-Match: *`, different text | **201**, new ETag `"18e064ceb822c23118"` | **FAIL: must reject with 412** |
| 08:46:30–31 GET after rejection failure | SHA-256 `3f417401fcc56337a173cf3f1616a356ac335381d99204297cad46d7e546892a` | Equals exact second synthetic body; overwrite independently confirmed |
| PUT same disposable probe, `If-Match: "pr34-intentionally-stale"` | **201**; next GET bytes changed to SHA-256 `9e9783cee63d3b60cd820a12acbebcb9f08a2d59f52bd3d011cd60b54cb4495d` | **FAIL: stale write must reject with 412** |
| PUT with currently returned ETag | 201; GET changed ETag `"18e064e67297c1eb26"` and content hash | Matching update works, but does not repair failed rejection semantics |

The initial probe stopped at the first unsafe response. Subsequent diagnosis
was restricted to that same synthetic file, confirming actual bytes, stale-match
behavior and correct-match updates. **No unconditional PUT fallback was used.**
The diagnostic exit code 0 means diagnosis completed, **not** acceptance passed.
Logs explicitly report `RC_SYNC_GATE FAIL`.

Reproduction: in a new disposable collection, conditionally create a synthetic
file; GET its bytes/ETag; PUT different bytes at the same name with
`If-None-Match: *`; GET again. Repeat with an intentionally incorrect `If-Match`.
This endpoint returned 201 and changed bytes in both rejection cases. Impact:
concurrent writers can overwrite data or immutable snapshots if these conditions
are ignored. The observed endpoint behavior does not establish which origin or
reverse-proxy component is responsible, nor demonstrate an APK algorithm defect.

**Required remediation:** configure/fix the WebDAV origin/proxy to enforce both
conditional headers, or select a server that does. Repeat this preflight before
any two-device snapshot binding. If an application defect is subsequently found,
fix it separately, generate a new candidate and repeat affected acceptance with
the new hashes. Do not weaken the client or reuse these results for new APK bytes.

## Execution ledger and remaining gates

The independent observation was ended early at the user's request. Unfinished
rows are not passes. The WebDAV failure still prevents `RC ACCEPTED`.

| Gate | Current result / evidence class |
| --- | --- |
| Frozen candidates and installed bytes | PASS — native tools and physical APK readback |
| Existing X08E upgrade/data retention | PASS for actual existing data, v3→v4, force-stop and normal reboot; canonical 0.1.1 bytes/nonempty Todo/Memo were not the baseline |
| X08E full hardware regression | **88/88 PASS**, unfiltered isolated physical testbed; signed daily live/UI checks separately recorded; original real ICS/CSV source resync and full relay transaction not repeated |
| Phone basic functions | Normal UI install/add/week edit/reset/delete/system picker/ICS/CSV/source/term inspection/dark/force-stop/reboot observed; custom term edit, broader scrolling and encrypted real credentials not fully verified; UI instrumentation timeout unresolved |
| Real WebDAV conditional writes | **FAIL** — both rejection guards ignored, actual overwrite verified |
| Real two-device binding/edit/offline merge/conflict choices/delete/reset/profile | **BLOCKED** by unsafe real server; no application head/snapshot published or downloaded |
| Remote snapshot/head readback hashes | **NOT RUN** after failed preflight; arbitrary probe readback is not a snapshot test |
| Restart/network failure/foreground/background JobScheduler | Actual local force-stop/reboot and isolated two-process phone receipt persistence observed; real sync, foreground remote check and actual scheduler execution **UNVERIFIED** |
| ≥4-hour physical X08E run | **NOT COMPLETED — ended at user request**, actual window 09:06:44–10:11:59 +08:00 (about 1 h 5 min 16 s); no four-hour pass |
| Human XiaoAI/Mesh/native alarm | **PASS (human report)** after candidate restoration; not repeated at the early-stop endpoint |

Real-server “commit succeeded but receipt lost” is **UNVERIFIED**. Existing
MockWebServer/emulator receipt evidence remains attributed to
[PR33](PR33-release-preparation.md), not reclassified as real-service execution.
Android force-stop is not expected to execute background jobs. No permanent
foreground service or frequent polling is proposed to compensate for scheduling.

### Test-run provenance and interrupted attempts

`tools/test-x08e.ps1` requires exactly one attached device; this host has two.
A local untracked copy selected the known X08E for hardware validation and
restoration and supplied a Gradle device-serial property. Gradle nevertheless
started the testbed on both physical devices. That accidentally overlapped with
the phone candidate runner. Both testbed runs and the phone run were explicitly
force-stopped; the interrupted 30-test Gradle summary and process-crash results
are **not passes**, nor are their interrupted cases attributed to a product defect.
The daily X08E package remained installed and script restoration checks ran.

A direct-runner retry initially failed because Gradle's cleanup had removed the
testbed instrumentation. Existing isolated testbed APK and test APK were then
installed explicitly on X08E, and an **unfiltered**, explicitly serial-selected
native `am instrument` run was started with the original hardware/state checks
and restoration logic. No media, hardware or product assertion was excluded.
Phone's isolated rerun also stalled before progressing through its conflict UI;
it was interrupted. A local-function group with a 90-second per-test bound
reported `TestTimedOutException` in `Instrumentation.startActivitySync`, called
by `ActivityScenario.launch` in `PhoneTimetableTest.open`, before exercising its
first case's feature assertions. It too was stopped. These remain unsuccessful
automation attempts with unresolved cause; a normal shell activity launch works.
No timeout increase, test-assertion change or app change was made to claim a pass.

The final direct **X08E** full run returned **OK (88 tests), 401.216 seconds**, no
failure statuses, and wrapper exit 0. Hardware identity, testbed target manifest,
daily package retention, media/mute, brightness mode and timeout restoration
checks passed. Actual physical media assertion was included. The optional live
HA light and granted-WRITE_SETTINGS cases can return without their prerequisites;
their passing entries alone are not proof of those live paths. This is physical
testbed evidence, not 88 tests instrumenting the non-debuggable daily candidate.

## Normal-app observation — stopped early, four-hour gate not met

A corrected host collector started at **2026-10-21 09:06:44 +08:00**, with nine
planned discrete observations at 30-minute intervals. At the user's explicit
request to end the test, its exact host process/command identity was verified
and the collector was stopped at **10:11:53.926**. One final read-only sample
ran **10:11:54.111–10:11:59.796**. Actual observation span from the first sample
start to final sample completion is **3915.619 seconds (1 h 5 min 15.619 s)**,
not four hours. The future 10:36–13:06 checkpoints were not collected and are not
reconstructed. This is an early termination, not a four-hour stability pass.

Samples checked daily PID/boot ID/proc start ticks, bounded native top (second of
two snapshots), dumpsys PSS, threads, crash buffer, foreground, brightness mode/
timeout/media state and native process presence. The collector performed no
server polling or UI operations between samples. This is discrete observation, not
continuous monitoring or continuous crash/network capture.

| Actual sample (+08:00) | PID | CPU (second native top) | PSS KiB | Threads | Observation |
| --- | ---: | ---: | ---: | ---: | --- |
| 09:06:44–09:06:49 | 2674 | 0.0% | 64973 | 31 | Dashboard foreground; mode 1; media 9/20; Xiaomi/Mesh/Bluetooth processes present; no daily mention in crash buffer |
| 09:36:44–09:36:49 | 2674 | 0.0% | 65840 | 30 | Same boot/process start identity, foreground and sampled settings/services |
| 10:06:44–10:06:49 | 2674 | 0.0% | 65388 | 30 | Same boot/process start identity, foreground and sampled settings/services |
| 10:11:54–10:11:59, final after stop request | 2674 | 0.0% | 65218 | 30 | Same boot/process start identity, foreground and sampled settings/services |

All four samples had the same boot ID and process start ticks **7647**, with
PID2674. Recorded PSS ranged **64973–65840 KiB**, threads **30–31**, and each
second native-top snapshot showed **0.0%** daily-app CPU. Dashboard remained
foreground at the sample points. The sampled crash buffer did not mention the
daily package; this is not proof that every possible transient crash/ANR or HA
reconnection was continuously monitored. Xiaomi launcher/Mesh/Bluetooth processes
were present, which alone does not establish successful voice/device control at
the endpoint. Those human checks were not repeated after the stop request.

Final read-only SQLite/ordered-row and preference-digest checks matched the
post-reboot baseline, including the original **1 source / 13 series / 131
occurrences**, Dashboard/productivity, encrypted credentials, relay identity and
settings. Only automatic screen brightness varied (13 → 18); brightness mode 1,
timeout 2147483647 ms and media 9/20 remained. No app or device restart, test-data
write, candidate rebuild or household device operation was performed to end the
observation. Sanitized sample evidence and final digest reads are committed as
[soak samples](evidence/pr34/soak-stopped-samples.json) and
[final data read](evidence/pr34/x08e-data-at-user-stop.txt).

An earlier **09:05:03** host-helper attempt failed with PowerShell call-depth
overflow before obtaining a device sample. The helper function was corrected
to call `adb.exe` rather than recursively calling its own case-insensitive name.
That failed attempt is retained, outside the declared soak window. A successful
09:06:01 diagnostic sample (PSS 66123 KiB, 32 threads) also precedes the scheduled
window and is not an invented scheduled checkpoint.

The app heartbeat **PR34 四小时实机验收收尾** (id `pr34`) was set to **PAUSED** in
response to the user's stop request, and the host collector process was verified
absent afterward. No automatic resumption is scheduled. The earlier plan to
observe until 13:06:44 was cancelled; it is not an executed observation window.
WebDAV request frequency and continuous HA reconnect history are not measured
by process/service samples and remain unverified without suitable evidence.

Raw local evidence is retained under ignored `artifacts/pr34/`, including
`verify-before.log`, `baseline-before.txt`, `baseline-after.txt`, installed APKs,
package metadata, `webdav-probe.log`, `webdav-diagnostic-final.log`, interrupted
runner logs and subsequent runner logs. Public evidence must exclude secrets and
private UI/course dumps. Historical stable 0.1.1 release records remain unchanged.

Committed, sanitized machine output is in [evidence/pr34](evidence/pr34/):
[candidate verification](evidence/pr34/candidate-verification.txt),
[installed identities](evidence/pr34/installed-identities.txt),
[before](evidence/pr34/x08e-data-before.txt)/[after](evidence/pr34/x08e-data-after.txt)
aggregate/digest reads,
[full physical testbed runner](evidence/pr34/x08e-testbed-instrumentation.txt),
[WebDAV preflight](evidence/pr34/webdav-preflight.txt) and
[follow-up diagnosis](evidence/pr34/webdav-diagnostic.txt),
[phone timeout excerpt](evidence/pr34/phone-ui-timeout.txt) (first failure only,
not a completed five-test run), and phone isolated restart
[seed](evidence/pr34/phone-isolated-restart-seed.txt)/
[verify](evidence/pr34/phone-isolated-restart-verify.txt).
Paths and digests of encrypted preference files are included; plaintext credentials
and private row contents are not. The temporary host credential container used for
the server probe was deleted after diagnosis. The synthetic remote probe remains
in its identifiable test directory as evidence; it is not an application snapshot.

Publication gate remains closed: the unmet four-hour observation, failed real WebDAV,
unexecuted real dual-device merge/conflicts/delete and missing real scheduler/
account checks prevent acceptance. Creation of this documentary PR conveys no
permission to merge, tag, upload assets or publish a Release. The existing frozen
APK pair passed verification again after the completed checks; no stable 0.1.1
historical record or HA version was changed.
