package com.dormpanel.app.ha

import android.os.SystemClock
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.ThemeMode
import com.dormpanel.app.device.DeviceControlController
import com.dormpanel.app.device.DeviceControlState
import com.dormpanel.app.startup.StartupPolicy
import org.json.JSONObject
import kotlin.math.roundToInt

/** Main scheduler only. The local controllers own all setting values. */
internal class NativeDeviceControls(
    private val channel: HaRelayChannel,
    private val scheduler: HaScheduler,
    private val credentials: () -> Pair<String, String>,
    private val device: DeviceControlController,
    private val appearance: AppearanceController,
    private val startup: StartupPolicy,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val onSessionAvailable: (Boolean) -> Unit = {},
) {
    companion object {
        const val VERSION = 1
        const val CAPABILITY = "device_control_v1"
        private val identifier = Regex("[0-9a-f]{32}")
    }

    private var unsubscribe: (() -> Unit)? = null
    private var sessionRetry: (() -> Unit)? = null
    private var sessionFailures = 0
    private var protocolSupported = false
    private var registered = false
    private var scheduledReport: (() -> Unit)? = null
    private var reportInFlight: Long? = null
    private var dirty = false
    private var reportFailures = 0
    private var reportSequence = 0L
    private var confirmedSequence = 0L
    private var confirmedSnapshot: String? = null
    private var session: String? = null
    private var admitting: String? = null
    private var generation = 0
    private val executed = linkedMapOf<String, String?>()
    private val deviceListener: (DeviceControlState) -> Unit = { stateChanged() }
    private val appearanceListener: (AppearanceState) -> Unit = { stateChanged() }
    private val startupListener: (Boolean) -> Unit = { stateChanged() }
    private val disconnectListener: () -> Unit = { disconnect() }

    init {
        device.addListener(deviceListener)
        appearance.addListener(appearanceListener)
        startup.addListener(startupListener)
        channel.addDisconnectListener(disconnectListener)
    }

    fun protocolSupported() {
        protocolSupported = true
        connectIfReady()
    }

    fun registered() {
        registered = true
        connectIfReady()
    }

    private fun connectIfReady() {
        if (!protocolSupported || !registered || !channel.ready || unsubscribe != null || sessionRetry != null) return
        val epoch = generation
        val (installation, secret) = credentials()
        val request = JSONObject().put("type", "dormpanel/control_subscribe")
            .put("version", VERSION).put("installation_id", installation).put("device_secret", secret)
        val cancel = channel.subscribe(request, onResult = { reply ->
            if (epoch != generation || session != null) return@subscribe
            val result = reply.optJSONObject("result")
            val token = result?.opt("session")
            if (!reply.optBoolean("success") || result?.opt("version") != VERSION ||
                token !is String || !identifier.matches(token)) {
                recoverSession()
                return@subscribe
            }
            device.refreshSystemBrightness()
            device.refreshAudio()
            session = token
            sessionFailures = 0
            onSessionAvailable(true)
            report(installation)
        }, onEvent = { command ->
            if (epoch == generation && session != null) execute(installation, command)
        })
        if (epoch != generation) cancel?.invoke()
        else if (cancel == null) recoverSession()
        else unsubscribe = cancel
    }

    fun disconnect() {
        protocolSupported = false
        registered = false
        sessionRetry?.invoke()
        sessionRetry = null
        sessionFailures = 0
        clearSession()
    }

    private fun recoverSession() {
        clearSession()
        if (!channel.ready) return
        onSessionAvailable(false)
        val epoch = generation
        sessionRetry = scheduler.after(reconnectDelay(sessionFailures)) {
            if (epoch != generation) return@after
            sessionRetry = null
            connectIfReady()
        }
        sessionFailures = (sessionFailures + 1).coerceAtMost(4)
    }

    private fun clearSession() {
        generation++
        session = null
        admitting = null
        unsubscribe?.invoke()
        unsubscribe = null
        scheduledReport?.invoke()
        scheduledReport = null
        reportInFlight = null
        dirty = false
        reportFailures = 0
        reportSequence = 0
        confirmedSequence = 0
        confirmedSnapshot = null
        executed.clear()
    }

    fun close() {
        disconnect()
        channel.removeDisconnectListener(disconnectListener)
        device.removeListener(deviceListener)
        appearance.removeListener(appearanceListener)
        startup.removeListener(startupListener)
    }

    private fun stateChanged() {
        if (session == null) return
        dirty = true
        scheduleReport()
    }

    private fun scheduleReport() {
        if (!dirty || reportInFlight != null || scheduledReport != null) return
        val delay = if (reportFailures == 0) 180L else reconnectDelay(reportFailures - 1)
        val epoch = generation
        scheduledReport = scheduler.after(delay) {
            if (epoch != generation) return@after
            scheduledReport = null
            if (dirty) report(credentials().first)
        }
    }

    private fun report(installation: String, commandId: String? = null, error: String? = null) {
        val token = session ?: return
        if (!channel.ready) return
        val current = device.state
        val values = JSONObject()
            .put("display_brightness", current.brightness)
            .put("system_brightness", current.system.percent)
            .put("media_volume", current.mediaPercent)
            .put("blackout", current.blackout)
            .put("system_automatic", current.system.automatic)
            .put("follow_system", current.useSystemBrightness)
            .put("keep_awake", current.keepAwake)
            .put("start_after_boot", startup.startAfterBoot)
            .put("theme", appearance.state.themeMode.name.lowercase())
            .put("card_opacity", (appearance.state.cardSurfaceOpacity * 100).roundToInt())
        val payload = JSONObject().put("type", "dormpanel/control_report")
            .put("installation_id", installation).put("session", token)
            .put("values", values).put("system_write", current.system.canWrite)
            .put("mute_available", current.muteAvailable)
        val snapshot = payload.toString()
        dirty = false
        if (commandId == null && reportSequence == confirmedSequence && snapshot == confirmedSnapshot) return
        val sequence = ++reportSequence
        if (commandId != null) {
            payload.put("command_id", commandId)
            if (error != null) payload.put("error", error)
        } else {
            reportInFlight = sequence
        }
        val epoch = generation
        var completed = false
        val finish: (JSONObject) -> Unit = finish@{ result ->
            if (epoch != generation || completed) return@finish
            completed = true
            if (reportInFlight == sequence) reportInFlight = null
            when {
                result.optBoolean("success") -> {
                    if (sequence > confirmedSequence) {
                        confirmedSequence = sequence
                        confirmedSnapshot = snapshot
                    }
                    if (sequence == reportSequence) reportFailures = 0
                }
                sequence > confirmedSequence && result.optJSONObject("error")?.optString("code") == "offline" -> {
                    recoverSession()
                    return@finish
                }
                sequence == reportSequence -> {
                    // A failed receipt leaves its actual snapshot pending, never the command.
                    dirty = true
                    reportFailures = (reportFailures + 1).coerceAtMost(5)
                    scheduledReport?.invoke()
                    scheduledReport = null
                }
            }
            scheduleReport()
        }
        if (!channel.request(payload, finish)) finish(JSONObject().put("success", false))
    }

    private fun execute(installation: String, command: JSONObject) {
        val token = session ?: return
        if (command.optInt("version") != VERSION || command.optString("installation_id") != installation ||
            command.optString("session") != token) return
        val id = command.optString("command_id")
        if (!identifier.matches(id)) return
        if (id in executed) {
            report(installation, id, executed[id])
            return
        }
        if (admitting == id) return
        val request = JSONObject().put("type", "dormpanel/control_admit").put("installation_id", installation)
            .put("session", token).put("command_id", id)
        val epoch = generation
        val sentAt = now()
        admitting = id
        if (!channel.request(request) { result ->
            if (epoch != generation || admitting != id) return@request
            admitting = null
            if (result.optJSONObject("error")?.optString("code") == "offline") {
                recoverSession()
                return@request
            }
            if (!result.optBoolean("success")) return@request
            val remaining = result.optJSONObject("result")?.optLong("remaining_ms") ?: 0
            // Bound the permit from local send time, including the full round trip.
            val failure = if (now() - sentAt >= remaining) "timeout" else
                runCatching { apply(command.optString("field"), command.opt("value")) }.getOrDefault("execution_failed")
            executed[id] = failure
            if (executed.size > 64) executed.remove(executed.keys.first())
            report(installation, id, failure)
        }) admitting = null
    }

    private fun apply(field: String, value: Any?): String? {
        val succeeded = when (field) {
            "display_brightness" -> {
                if (value !is Int || value !in 1..100) return "invalid_value"
                if (device.state.useSystemBrightness) return "following"
                device.setBrightness(value, remote = true)
                device.state.brightness == value
            }
            "system_brightness" -> {
                if (value !is Int || value !in 1..100) return "invalid_value"
                device.refreshSystemBrightness()
                if (!device.state.system.canWrite) return "permission"
                if (device.state.system.automatic) return "automatic"
                device.setSystemBrightness(value, remote = true)
            }
            "media_volume" -> {
                if (value !is Int || value !in 0..100) return "invalid_value"
                if (value == 0 && !device.state.muteAvailable) return "unsupported"
                device.setMediaPercent(value, remote = true)
            }
            "blackout" -> {
                if (value !is Boolean) return "invalid_value"
                if (value) device.enterBlackout(remote = true) else device.exitBlackout(remote = true)
                device.state.blackout == value
            }
            "system_automatic" -> {
                if (value !is Boolean) return "invalid_value"
                device.refreshSystemBrightness()
                if (!device.state.system.canWrite) return "permission"
                device.setSystemAutomatic(value, remote = true)
            }
            "follow_system" -> {
                if (value !is Boolean) return "invalid_value"
                device.setFollowSystem(value, remote = true)
                device.state.useSystemBrightness == value
            }
            "keep_awake" -> {
                if (value !is Boolean) return "invalid_value"
                device.setKeepAwake(value, remote = true)
                device.state.keepAwake == value
            }
            "start_after_boot" -> {
                if (value !is Boolean) return "invalid_value"
                startup.setStartAfterBoot(value, remote = true)
            }
            "theme" -> {
                val mode = when (value) {
                    "light" -> ThemeMode.LIGHT
                    "dark" -> ThemeMode.DARK
                    else -> return "invalid_value"
                }
                appearance.update(appearance.state.copy(themeMode = mode))
                appearance.state.themeMode == mode
            }
            "card_opacity" -> {
                if (value !is Int || value !in 0..100) return "invalid_value"
                val opacity = value / 100f
                appearance.update(appearance.state.copy(cardSurfaceOpacity = opacity))
                appearance.state.cardSurfaceOpacity == opacity
            }
            else -> return "unsupported"
        }
        return if (succeeded) null else "execution_failed"
    }
}
