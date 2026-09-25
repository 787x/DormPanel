# PR18 X08E device-control verification

Observed on physical X08E (Android 9/API 28, 1280×800) through the isolated `com.dormpanel.app.testbed` package and ADB on 25 September 2026. Findings below are device observations, not generic Android guarantees. The daily package was not instrumented or reinstalled.

| Capability | Mechanism / privilege | Physical result | Shipped | Reason |
| --- | --- | --- | --- | --- |
| DormPanel brightness | Activity `WindowManager.LayoutParams.screenBrightness`; ordinary app | 100%, 50%, 10%, 1% yielded display-service brightness 255, 127, 25, 2. App UI and HA settings dialog remained dim at 1%. MIUI Home changed the window override to `-1`. | Yes | App scoped and reversible; a fresh install inherits system brightness until the first adjustment. |
| Global brightness | `Settings.System.SCREEN_BRIGHTNESS`; `WRITE_SETTINGS` special access | System value was 22 then 21 while automatic mode was enabled; this PR made no system writes. Testbed `Settings.System.canWrite()` is checked in instrumentation and is false. | No | Window control satisfies the local need without special access. |
| Automatic brightness | `Settings.System.SCREEN_BRIGHTNESS_MODE`; read only | Mode was `1`; display service reported `mAppliedAutoBrightness=true`. Window override still took effect. | No | Leave Xiaomi automatic brightness intact outside DormPanel. |
| Keep awake | Activity `FLAG_KEEP_SCREEN_ON`; ordinary app | Instrumentation verified flag enabled by default and removed when disabled; no timeout value was changed. Explicit power key still slept the display. | Yes | Preserves old behavior by default. |
| Blackout | Black `View` over pages plus 1% window brightness; ordinary app | Capture was all black; display-service brightness remained 2, so backlight was still on. Touch removed the overlay and restored the previous 32% override (81). | Yes | Safe local recovery, no page reconstruction. |
| Media volume | `AudioManager.STREAM_MUSIC` / `MODIFY_AUDIO_SETTINGS` normal permission | Stream range 1–20, initial index 6. Slider at 50% set index 10; external index 8 refreshed as 40% on Activity resume. | Yes | Standard media stream; no alarm/ring/call writes. |
| True media mute | Public `ADJUST_MUTE`, legacy `setStreamMute`, `adjustVolume`, index 0 | All were attempted in isolated testbed and left music stream unmuted; shell rejected index 0 because minimum is 1. | No | The disabled button says “Mute unavailable on X08E” rather than claiming silence. No privileged workaround. |
| True screen off | ADB shell `input keyevent KEYCODE_POWER`; shell access | Display power and actual state became `OFF`; same key event restored `ON`. Testbed PID survived and Activity remained resumed. | No | ADB/shell is not an ordinary in-app control; local recovery and HA/XiaoAI behavior need a dedicated decision. |
| Device policy sleep | `DevicePolicyManager.lockNow`; Device Admin/Owner | Researched API requirement; not enrolled or exercised. | No | Changes device administration and is outside PR18. |
| Root screen control | `su 0 id`; root shell | ADB shell can become root. `run-as com.dormpanel.app.testbed su 0 id` failed with `Permission denied` from the app UID. No sysfs or Xiaomi files changed. | No | App root is unavailable through this probe; privilege and coexistence risk. |

The shell power-key probe kept the Xiaomi launcher process alive. HA WebSocket continuity, XiaoAI invocation, and Bluetooth Mesh actions were **not** verified during sleep. An alive process alone does not prove those services remained functional. The screen-off route stays deferred.

No test tone was played; stream selection is based on `AudioManager` state and service logs. Xiaomi voice-assistant volume behavior was not exercised. The native alarm stream stayed at 10 when music changed from 6 to 8; the probes never set the alarm stream.

`MODIFY_AUDIO_SETTINGS` is the sole new permission, for public `AudioManager` media-volume operations; the testbed package reported it granted. No `WRITE_SETTINGS`, wake lock, Device Admin, root execution, system timeout write, or system file modification was added.

The HA `input_number` bindings are optional and isolated from ordinary controls. Brightness accepts 1–100 and media volume accepts 0–100; invalid, unavailable, unrelated, and disconnected updates do not change the device. The X08E cannot honor a requested media value of 0 as silence, so that local command is ignored. A real HA instance was not used for physical helper testing; mock WebSocket tests cover synchronization and coalescing.

Physical test safety: media index was restored to 6 after volume probes; alarm remained at 10. Automatic system brightness stayed in mode 1; its setting varied from 22 to 18 with ambient automation and was not written by the manual probes. The isolated suite snapshots/restores music index, mute state, system brightness and mode, and checks system timeout. Final display power was `ON`, window brightness override `-1`, and system timeout `2147483647`; the testbed package and temporary probe images were removed.
