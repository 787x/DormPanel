# PR22: X08E startup and system coexistence

## Implementation

`StartupPolicy` owns a Boolean in the app's `startup_policy` preferences. A missing
key is OFF; Control Center writes changes synchronously and rereads persisted
state when recreated. The setting is only an opt-in for normal boot. It does not
restart a killed process or follow the user back from Home.

The production manifest requests `RECEIVE_BOOT_COMPLETED` and registers a
non-exported `BOOT_COMPLETED` receiver. It checks the action and preference, then
launches the existing `MainActivity` explicitly with `NEW_TASK`, `CLEAR_TOP`, and
`SINGLE_TOP`. It does no HA, database, or device-control work. The Activity and
ViewModel retain their normal startup ownership. The `x08eTest` manifest disables
this receiver, so its isolated package cannot compete at boot. There is no boot
service, delayed retry, or foreground watchdog.

Control Center's **Open MIUI Home** button resolves an `ACTION_MAIN` plus
`CATEGORY_HOME` intent and launches it only when available. Resolution or launch
failure leaves DormPanel running with a short error message. No manifest
component or visibility query declares `CATEGORY_HOME`; DormPanel remains an
ordinary `MAIN/LAUNCHER` app.

## Verification

The checks below were performed on the physical X08E, Android 9, 1280×800,
2026-09-25 local time. Only the isolated `com.dormpanel.app.testbed` package was
used for connected instrumentation. It was removed by the test helper before
either reboot. The daily `com.dormpanel.app` APK was updated with `adb install
-r` and app data was retained.

| Check | Result and evidence |
| --- | --- |
| Build, unit tests, lint | `assembleDebug testDebugUnitTest lintDebug` passed. |
| Isolated X08E instrumentation | `tools/test-x08e.ps1` passed the full connected suite: 67 tests, zero failures. Its instrumentation APK targeted `com.dormpanel.app.testbed`; the daily package remained installed. The first run found a new test bug: `getReceiverInfo` omitted the disabled testbed receiver without `MATCH_DISABLED_COMPONENTS`. That test was fixed before the clean full rerun. |
| `git diff --check` | Passed. |
| Daily app data after `adb install -r` | SHA-256 values for HA settings and credentials, relay identity, dashboard, productivity and schedule databases, WebDAV settings, and device-control preferences matched before and after update and after both reboots. Hashes were compared without displaying credentials or local content. |
| Enabled real reboot and `BOOT_COMPLETED` | Startup preference was explicitly ON and persisted before reboot. Android's broadcast record listed the DormPanel receiver for `BOOT_COMPLETED` and later showed it delivered. `DormPanelBoot` logged one `Boot launch requested` at 16:58:25. The ordered boot broadcast was still pending when `sys.boot_completed` became 1 at 16:57:29; delivery was about 56 seconds later. |
| Foreground Activity after boot | `dumpsys activity` showed `com.dormpanel.app/.MainActivity` resumed after receiver delivery and still foreground 15 seconds later. Xiaomi fallback Home, Xiaomi Launcher, and an installed Dangbei launcher had appeared earlier during boot. No delayed retry was necessary. |
| Local dashboard and HA recovery | Local clock card rendered at 16:59. Control Center showed `HA · CONNECTED` after boot and after returning from Xiaomi Home. The existing light card displayed current state. No boot-specific HA work was added. |
| Generic HOME resolution and launch | `cmd package query-activities` found Xiaomi `com.xiaomi.micolauncher.Launcher` and an installed Dangbei launcher among HOME handlers. `resolve-activity` reported Android's ResolverActivity, but tapping **Open MIUI Home** brought `com.xiaomi.micolauncher/.Launcher` to the foreground directly. This still worked after the final APK removed the manifest HOME visibility query. Production code contains no Xiaomi package name. |
| Return to DormPanel | Android recents displayed DormPanel; selecting its task resumed `MainActivity`. Control Center still showed HA connected. Xiaomi Home remained foreground until the user selected DormPanel. |
| Disabled real reboot | Startup preference was saved OFF through Control Center before a second real reboot. Android's broadcast record showed the receiver delivered, no `DormPanelBoot` launch line appeared, and the installed Dangbei launcher remained foreground. |
| XiaoAI response | Not confirmed. ADB assist and voice-assist key events produced no observable response. A direct physical response check was requested but was not available during this run. |
| Bluetooth Mesh action | Not verified. Xiaomi Home displayed devices and opened its Yeelight control and temperature-sensor panels, but this did not establish a Bluetooth Mesh command. The Yeelight state remained On at 1% afterward. |
| Temporary Xiaomi alarm | A one-time 17:12 alarm was created in Xiaomi's native alarm page while it initially had no alarms. DormPanel was foreground before it fired. At 17:12, `com.xiaomi.micolauncher/.skills.alarm.view.AlarmBoomActivity` took foreground with a full-screen alarm; it was dismissed. The alarm page again showed no alarms afterward, so no temporary entry remained. Audible sound could not be assessed remotely. |
| Final device state | Final APK installed with `adb install -r`; boot preference OFF. Daily app and data remain installed. System brightness mode 1, brightness value 2, timeout 2147483647 ms, and media volume 1/20 matched the pre-update snapshot. The testbed package is absent after the completed suite. The temporary alarm was removed automatically after firing. Xiaomi's lock-screen Activity returned to foreground after idle, matching the pre-test foreground state. |

The data and system-setting observations above are physical device evidence.
Android's generic boot and HOME behavior alone would not have established the
X08E outcome. This run found no need for an OEM, root, or recurring startup
mechanism. Xiaomi's ordered boot queue made delivery later than
`sys.boot_completed` on this device.

## Top-edge system gesture follow-up

DormPanel reserves the top **32dp** of the screen for gestures whose initial
`ACTION_DOWN` is in that zone. On the X08E at 160dpi, that is 32px. The global
page-swipe detector is canceled for the whole stream; each event still follows
normal Android View dispatch. A stream starting below the zone remains eligible
for the existing page and slider arbitration even if it later crosses the top.
Blackout's existing touch path takes priority. No immersive-mode or system UI
configuration changed.

On the physical X08E, direct finger verification confirmed that a downward
swipe from the extreme top edge opened Xiaomi's native pull-down while
DormPanel stayed on Home underneath. This observation was reported by the
person at the device; ADB-injected extreme-edge swipes exposed transient system
bars but did not reproduce the full Xiaomi pull-down. ADB boundary probes on
the daily app showed starts at 24px and 31px stayed on Home, while starts at
32px, 33px, 40px, 80px, and 100px entered DormPanel Control Center. The
reserved zone therefore remained 32dp.

Focused unit tests cover stream ownership, movement outside the zone,
UP/CANCEL reset, and the exact boundary. Connected tests cover edge-start
downward and horizontal streams, normal navigation after UP/CANCEL, and
below-edge navigation. The pre-existing slider and Blackout tests remain part
of the full isolated X08E suite. Final verification passed:
`assembleDebug testDebugUnitTest lintDebug`, `tools/test-x08e.ps1` (69 connected
tests, zero failures), and `git diff --check`.
