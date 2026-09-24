# PR16 X08E runtime and recovery record

## Ownership review

`DashboardViewModel` retains the dashboard store, HA backend and its single
WebSocket, app discovery, productivity source, schedule source, WebDAV scheduler,
relay controller, and appearance controller across Activity recreation. Its
`onCleared()` closes the process-owned stores and workers. The dashboard store,
app discovery, productivity source, schedule source, and WebDAV controller guard
late callbacks after close. The relay controller now does the same, including
an idempotent `close()`.

`MainActivity.onDestroy()` detaches the relay presenter, closes schedule import
dialogs and workers, removes its appearance listener, and cancels page
animations. Card/page detach paths remove their source listeners and dialogs.
Clock, Timer, and Calendar/Timetable callbacks are tied to visible attached
views. Clock schedules only its next minute; Calendar/Timetable schedules its
next data or date boundary; Timer uses a display ticker only while visible.

WebDAV has one controller in the retained ViewModel. It cancels and replaces
its single due callback on binding/source changes, and schedules nothing when
there are no enabled bindings. It does not sync after the process exits. Existing
tests cover failed requests, conditional success, source deletion, persistence,
and process restart; the PR16 assertion checks controller identity and request
count across Activity recreation.

## Defects found and corrected

- A relay claim awaiting a WebSocket response could remain marked in flight
  after disconnection. The socket drops pending callbacks on disconnect, so
  the old relay state could block its queue after reconnection. The controller
  now requeues the in-flight ID on the next ready notification and ignores old
  connection callbacks.
- A terminal relay acknowledgement used to clear local deduplication before
  HA confirmed it. The controller now retains the ID after confirmed success;
  failed or unavailable acknowledgements release it for rediscovery through
  `list_pending`. A sent acknowledgement whose callback disappears with the
  socket is also released on reconnect. Local import success is never equated
  with HA terminal state.
- Clock instrumentation waited for the physical next minute. An injectable
  time/scheduling boundary now lets the test advance the retained Clock view
  from 12:34 to 12:35 without elapsed time. Production still uses device local
  time, one next-minute callback, and TIME/DATE/TIMEZONE receiver refresh.

## Verification limits and physical observations

The device reports Android API 28, 1280×800, density 160. The command-line
instrumentation workaround is scoped to the Gradle invocation:
`-Pandroid.experimental.androidTest.builtin_test_platform=true`.

The first full instrumentation run completed 52 tests with one test-harness
failure: the app UID cannot send Android's protected `TIME_SET` broadcast.
The test now invokes the registered Clock receiver's refresh path directly;
it does not change device time or permissions. Subsequent run results and
physical idle/recovery measurements are recorded below when available.

The second full run completed 53 tests with one device-interruption failure:
Xiaomi's native screensaver took the foreground during
`ScheduleAndroidTest.pageCrudSessionNavigationAndAppearance`; Espresso reported
`NoActivityResumedException` at line 102. The same test passed when run directly
on the awake X08E (`OK (1 test)`). No Schedule assertion failed.

**Device data incident:** the first two connected runs omitted the separate
`android.injected.androidTest.leaveApksInstalledAfterRun=true` flag documented
for physical X08E testing in PR12. Gradle removed DormPanel after the run,
including its app data. I reinstalled the debug APK. The local Android backup
transport returned `restoreFinished: -1000` and did not restore the previous
database or HA credentials. This is an actual loss of the pre-test device app
state; it must not be described as a passing runtime or coexistence check.
The relay installation identity was also recreated, so transfers addressed
only to the old identity may require reassignment in Home Assistant.
The final connected run included both the built-in platform and retained-APK
flags. `assembleDebug`, `testDebugUnitTest`, `lintDebug`, and all 54 X08E
instrumentation tests passed (`BUILD SUCCESSFUL`, 5m 50s). The app remained
installed. The user later re-entered the private HA settings on the device;
the original database and relay identity did not reappear.

The final command was:

```powershell
.\gradlew.bat --% assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest -Pandroid.experimental.androidTest.builtin_test_platform=true -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

## Physical short run after the passing suite

The reinstalled app initially ran in Demo mode, with no restored HA/WebDAV connection.
The same process (PID 24391) remained alive through five attempted cycles of
Home/Apps/Calendar/Home Control/Control Center gestures, a roughly three-minute
Clock observation (22:45 to 22:48 on screenshots), a quiet foreground interval,
and a visit to Xiaomi's launcher and back. A few ADB gestures landed on Apps,
so the automated suite is stronger evidence for exact navigation routes.

| Point | PSS | Views | Activities | Threads | CPU observation |
| --- | ---: | ---: | ---: | ---: | --- |
| Shortly after launch | 56,825 KB | 111 | 1 | 23 | `dumpsys cpuinfo`: 0.5% |
| After page gestures | 59,921 KB | 63 (Apps page) | 1 | 24 | 2.2% while active |
| Quiet on Home | 60,527 KB | 111 | 1 | 25 | absent from `dumpsys cpuinfo`; `top`: 0.0% |
| After Xiaomi launcher return | 55,306 KB | 112 | 1 | same PID | responsive |

These short observations do not show sustained memory or CPU growth, but are
not a multi-hour soak. Clock visibly advanced while Home stayed open. The
device remained API 28 at 1280×800 and density 160. No permanent telemetry or
system setting/service changes were made.

`com.xiaomi.micolauncher/.Launcher` resumed when explicitly launched, and
DormPanel resumed afterward. Pressing Home first presented a launcher resolver
because another launcher is installed; no default launcher choice was changed.
XiaoAI invocation, Bluetooth Mesh gateway behavior, and a native alarm were
not exercised. A multi-hour foreground run remains unverified. A 24/72-hour
observation remains a post-merge target.

## Real HA recovery after the user restored credentials

The device's HA mode was `HOME_ASSISTANT`, its encrypted token preference was
present, and Control Center displayed `CONNECTED`. Wi-Fi was initially enabled.
I disabled Wi-Fi briefly through ADB, observed Control Center show
`RECONNECTING`, and restored Wi-Fi in a `finally` block. Its original enabled
state was confirmed afterward. Control Center then displayed `CONNECTED`.
The Home Clock and live weather/light cards rendered again; Home Control
displayed `Live · Home Assistant` and live entities. The existing Yeelight card
reported ON, 1%, 2700 K before the check. A card tap changed incoming HA state
to OFF; another changed it back to ON, 1%, 2700 K. No optical observation of
the light itself was made. The HA settings dialog displayed `Timetable relay:
Relay ready` after reconnect, confirming registration. The controller sends
`list_pending` after registration; its duplicate/pending behavior was verified
with the instrumented fake channel, but the physical HA response to that
specific request was not directly inspected. The physical UI does not expose
WebSocket counts; the JVM protocol fixture asserts two total socket handshakes
across initial connection and one replacement connection.

The user subsequently restarted Home Assistant from its management side. The
status sampling while that happened read `CONNECTED` throughout and did not
capture the brief disconnect/reconnect transition, so this is not evidence of
the exact restart handshake. After the user confirmed completion, Control
Center read `CONNECTED`, the HA settings dialog read `Timetable relay: Relay
ready`, Home showed live weather and the Yeelight card, and Home Control read
`Live · Home Assistant`. A second OFF/ON command after the server restart
produced matching incoming HA states, restoring the light to ON, 1%, 2700 K.
No optical observation of the lamp was made. The device currently has no
WebDAV binding, so an hourly physical WebDAV run was not exercised.

After the HA restart checks, Home remained visibly live through another
Clock change (23:07 to 23:10). The same process had one Activity, 33 threads,
and PSS of 75,947 KB then 75,700 KB about 30 seconds later. Three consecutive
2-second `top` samples showed 0.0% CPU while idle. This is a short live-HA
baseline, not evidence of several-hour stability.

I then force-stopped only DormPanel and launched it again. PID changed from
24391 to 27546; Wi-Fi stayed enabled. Control Center returned to `CONNECTED`,
the HA settings dialog returned to `Timetable relay: Relay ready`, and Home
reconstructed its Clock, live weather, and Yeelight card with the same ON,
1%, 2700 K state. This verifies a real process restart with the newly entered
settings, separately from the instrumented persistence tests.

No 24-hour or 72-hour soak is claimed. A multi-hour foreground soak and any
unexercised Xiaomi service checks remain post-merge observation targets.

After the final 54-test build, DormPanel was still installed. The encrypted HA
token preference and HA mode remained present, Wi-Fi remained enabled, and the
physical Control Center again displayed `HA · CONNECTED`. This is a final
post-instrumentation connection check, not a second server-restart observation.
Home again rendered its Clock, weather, and Yeelight card.
