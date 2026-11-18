package com.dormpanel.app.ha

import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.AppearanceStore
import com.dormpanel.app.appearance.ThemeMode
import com.dormpanel.app.device.DeviceControlController
import com.dormpanel.app.device.DeviceControlStore
import com.dormpanel.app.device.MediaVolumePort
import com.dormpanel.app.device.SystemBrightnessPort
import com.dormpanel.app.device.SystemBrightnessState
import com.dormpanel.app.startup.StartupPolicy
import com.dormpanel.app.startup.StartupStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeDeviceControlsTest {
    private class Channel : HaRelayChannel {
        override val origin = "https://fixture.local"
        override val ready = true
        val reports = mutableListOf<JSONObject>()
        val callbacks = ArrayDeque<(JSONObject) -> Unit>()
        var onEvent: ((JSONObject) -> Unit)? = null
        var onResult: ((JSONObject) -> Unit)? = null
        var rejectNextReport = false
        var remainingMs = 5000
        override fun request(message: JSONObject, callback: (JSONObject) -> Unit): Boolean {
            if (message.getString("type") == "dormpanel/control_admit") {
                callback(JSONObject().put("success", true).put("result", JSONObject().put("remaining_ms", remainingMs)))
                return true
            }
            if (rejectNextReport) {
                rejectNextReport = false
                return false
            }
            reports += message
            callbacks.addLast(callback)
            return true
        }
        override fun subscribe(message: JSONObject, onResult: (JSONObject) -> Unit, onEvent: (JSONObject) -> Unit): () -> Unit {
            assertEquals("dormpanel/control_subscribe", message.getString("type"))
            assertEquals("screen", message.getString("installation_id"))
            assertEquals("secret", message.getString("device_secret"))
            this.onResult = onResult
            this.onEvent = onEvent
            return { this.onEvent = null; this.onResult = null }
        }
        override fun addReadyListener(listener: () -> Unit) = Unit
        override fun removeReadyListener(listener: () -> Unit) = Unit
        override fun addEventListener(listener: (JSONObject) -> Unit) = Unit
        override fun removeEventListener(listener: (JSONObject) -> Unit) = Unit
        fun confirm() {
            while (callbacks.isNotEmpty()) callbacks.removeFirst()(JSONObject().put("success", true))
        }
    }

    private class Fixture {
        val channel = Channel()
        val scheduler = ManualScheduler()
        val appearance = AppearanceController(object : AppearanceStore {
            override fun read() = AppearanceState()
            override fun write(state: AppearanceState) = Unit
        })
        val startup = StartupPolicy(object : StartupStore {
            private var enabled = false
            override fun startAfterBoot() = enabled
            override fun setStartAfterBoot(enabled: Boolean): Boolean {
                this.enabled = enabled
                return true
            }
        })
        var volume = 3
        var system = SystemBrightnessState(canWrite = true, automatic = true, raw = 50)
        var systemWrites = 0
        val device = DeviceControlController(object : DeviceControlStore {
            override fun brightness() = 50
            override fun keepAwake() = false
            override fun lastAudibleVolume() = 3
            override fun saveBrightness(value: Int) = Unit
            override fun saveKeepAwake(value: Boolean) = Unit
            override fun saveLastAudibleVolume(value: Int) = Unit
        }, object : MediaVolumePort {
            override val max = 15
            override val muteAvailable = false
            override fun current() = volume
            override fun set(value: Int) { volume = value }
            override fun muted() = false
            override fun mute(value: Boolean) { error("mute must not be used") }
        }, object : SystemBrightnessPort {
            override fun read() = system
            override fun setBrightness(raw: Int): Boolean {
                systemWrites++
                system = system.copy(raw = raw)
                return true
            }
            override fun setAutomatic(enabled: Boolean): Boolean {
                system = system.copy(automatic = enabled)
                return true
            }
        })
        val adapter = NativeDeviceControls(channel, scheduler, { "screen" to "secret" }, device, appearance, startup) { 1000 }
        var token = "a".repeat(32)
        private var seq = 0
        fun connect() {
            adapter.connect()
            channel.onResult!!.invoke(JSONObject().put("success", true)
                .put("result", JSONObject().put("version", 1).put("session", token)))
        }
        fun command(field: String, value: Any, installation: String = "screen"): JSONObject {
            val command = JSONObject().put("version", 1).put("installation_id", installation)
                .put("session", token).put("command_id", (++seq).toString(16).padStart(32, '0'))
                .put("field", field).put("value", value)
            channel.onEvent!!.invoke(command)
            return command
        }
        fun values() = channel.reports.last().getJSONObject("values")
        fun error() = channel.reports.last().optString("error")
    }

    @Test fun reportsActualStateAndExecutesEverySettingWithoutHelperEcho() {
        val f = Fixture()
        var helperWrites = 0
        f.device.brightnessCommand = { helperWrites++ }
        f.device.volumeCommand = { helperWrites++ }
        f.appearance.themeCommand = { helperWrites++; true }
        f.startup.startAfterBootCommand = { helperWrites++ }
        f.connect()
        assertEquals(10, f.values().length())
        assertFalse(f.channel.reports.last().has("device_secret"))
        f.channel.confirm()
        for ((field, value) in listOf(
            "display_brightness" to 48, "system_automatic" to false, "system_brightness" to 77,
            "media_volume" to 48, "blackout" to true, "follow_system" to true, "keep_awake" to true,
            "start_after_boot" to true, "theme" to "light", "card_opacity" to 17,
        )) {
            val command = f.command(field, value)
            assertEquals(command.getString("command_id"), f.channel.reports.last().getString("command_id"))
            assertEquals("", f.error())
            assertEquals(if (field == "media_volume") 47 else value, f.values().get(field))
            f.channel.confirm()
        }
        assertEquals(0, helperWrites)
        f.device.exitBlackout()
        f.appearance.update(AppearanceState(ThemeMode.DARK, .29f))
        val before = f.channel.reports.size
        f.scheduler.advance(180)
        assertEquals(before + 1, f.channel.reports.size)
        assertFalse(f.values().getBoolean("blackout"))
        assertEquals("dark", f.values().getString("theme"))
        assertEquals(29, f.values().getInt("card_opacity"))
    }

    @Test fun rejectsStaleInvalidAndUnavailableCommandsAndResyncsActualState() {
        val f = Fixture()
        f.connect()
        f.channel.confirm()
        val oldReceiver = f.channel.onEvent!!
        f.command("system_brightness", 42)
        assertEquals("automatic", f.error())
        f.system = f.system.copy(canWrite = false)
        f.command("system_automatic", false)
        assertEquals("permission", f.error())
        assertEquals(0, f.systemWrites)
        f.command("media_volume", 0)
        assertEquals("unsupported", f.error())
        f.command("follow_system", true)
        f.command("display_brightness", 80)
        assertEquals("following", f.error())
        f.command("keep_awake", 1)
        assertEquals("invalid_value", f.error())
        f.channel.remainingMs = 0
        f.command("theme", "light")
        assertEquals("timeout", f.error())
        f.channel.remainingMs = 5000
        val count = f.channel.reports.size
        f.command("theme", "light", installation = "other")
        assertEquals(count, f.channel.reports.size)
        val duplicated = f.command("blackout", true)
        f.device.exitBlackout()
        oldReceiver(duplicated)
        assertFalse(f.values().getBoolean("blackout"))
        f.adapter.disconnect()
        oldReceiver(duplicated)
        assertNull(f.channel.onEvent)
        f.appearance.update(AppearanceState(ThemeMode.LIGHT, .8f))
        f.token = "b".repeat(32)
        f.connect()
        assertEquals("light", f.values().getString("theme"))
        assertTrue(f.values().getBoolean("follow_system"))
        f.adapter.close()
    }

    @Test fun retriesLatestSnapshotWithoutReplayingCommand() {
        val f = Fixture()
        f.channel.rejectNextReport = true
        f.connect()
        assertTrue(f.channel.reports.isEmpty())
        f.scheduler.advance(180)
        assertEquals(1, f.channel.reports.size)
        f.channel.confirm()
        f.channel.rejectNextReport = true
        f.command("blackout", true)
        f.device.exitBlackout()
        f.device.setKeepAwake(true)
        f.scheduler.advance(180)
        assertEquals(2, f.channel.reports.size)
        assertFalse(f.channel.reports.last().has("command_id"))
        assertFalse(f.values().getBoolean("blackout"))
        assertTrue(f.values().getBoolean("keep_awake"))
    }
}
