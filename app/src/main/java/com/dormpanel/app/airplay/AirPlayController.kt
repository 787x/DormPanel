package com.dormpanel.app.airplay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat

internal const val DEFAULT_RECEIVER_NAME = "DormPanel X08E"

internal enum class AirPlayPhase {
    OFF, STARTING, WAITING, CONNECTED, PLAYING, PAUSED, OFFLINE, FAILED
}

internal data class AirPlaySettings(val name: String, val enabled: Boolean, val autoStart: Boolean)
internal data class AirPlayTrack(val title: String = "", val artist: String = "", val album: String = "")
internal data class AirPlayProgress(val positionMs: Long, val durationMs: Long)
internal enum class AirPlayCommand(val path: String) {
    PLAY("play"), PAUSE("pause"), PREVIOUS("previtem"), NEXT("nextitem")
}

internal data class AirPlayState(
    val name: String,
    val phase: AirPlayPhase = AirPlayPhase.OFF,
    val detail: String? = null,
    val track: AirPlayTrack = AirPlayTrack(),
    val artwork: Bitmap? = null,
    val progress: AirPlayProgress? = null,
    val commands: Set<AirPlayCommand> = emptySet(),
)

/** Main-thread state for the Activity and the foreground service in the same process. */
internal class AirPlayController private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("airplay", Context.MODE_PRIVATE)
    private val listeners = linkedSetOf<(AirPlayState) -> Unit>()
    private var serviceOwner: Any? = null

    val settings: AirPlaySettings
        get() = AirPlaySettings(prefs.getString("name", DEFAULT_RECEIVER_NAME) ?: DEFAULT_RECEIVER_NAME,
            prefs.getBoolean("enabled", false), prefs.getBoolean("boot", false))
    var state = AirPlayState(settings.name)
        private set

    fun addListener(listener: (AirPlayState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (AirPlayState) -> Unit) {
        listeners -= listener
    }

    fun configure(name: String, enabled: Boolean, autoStart: Boolean): Boolean {
        val normalized = validName(name) ?: return false
        val previous = settings
        if (!prefs.edit().putString("name", normalized).putBoolean("boot", autoStart)
                .putBoolean("enabled", enabled).commit()) return false
        if (enabled) {
            if (!previous.enabled || previous.name != normalized || state.phase == AirPlayPhase.FAILED ||
                state.phase == AirPlayPhase.OFF) {
                update(AirPlayState(normalized, AirPlayPhase.STARTING))
                try {
                    ContextCompat.startForegroundService(context, Intent(context, AirPlayService::class.java))
                } catch (_: RuntimeException) {
                    update(AirPlayState(normalized, AirPlayPhase.FAILED, "系统未允许启动后台接收服务"))
                }
            }
        } else {
            if (serviceOwner != null || state.phase == AirPlayPhase.STARTING) {
                val stop = Intent(context, AirPlayService::class.java).setAction(AirPlayService.STOP)
                try {
                    ContextCompat.startForegroundService(context, stop)
                } catch (_: RuntimeException) {
                    context.stopService(stop)
                }
            } else update(AirPlayState(normalized))
        }
        return true
    }

    fun restoreInForeground() {
        if (settings.enabled && state.phase == AirPlayPhase.OFF) {
            configure(settings.name, true, settings.autoStart)
        }
    }

    fun boot() {
        val saved = settings
        if (saved.autoStart) configure(saved.name, true, true)
    }

    fun disableFromNotification() {
        val saved = settings
        configure(saved.name, false, saved.autoStart)
    }

    fun command(command: AirPlayCommand) {
        if (command !in state.commands) return
        context.startService(Intent(context, AirPlayService::class.java).putExtra("command", command.name))
    }

    fun onServiceStart(owner: Any) { serviceOwner = owner }

    fun onServiceState(owner: Any, value: AirPlayState) {
        if (serviceOwner == owner && settings.enabled && value.name == settings.name) update(value)
    }

    fun onServiceStop(owner: Any) {
        if (serviceOwner != owner) return
        serviceOwner = null
        update(AirPlayState(settings.name))
    }

    private fun update(value: AirPlayState) {
        if (state == value) return
        state = value
        listeners.toList().forEach { it(value) }
    }

    companion object {
        private var instance: AirPlayController? = null
        fun get(context: Context): AirPlayController =
            instance ?: AirPlayController(context.applicationContext).also { instance = it }

        fun validName(name: String): String? = name.trim().takeIf {
            it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 48 &&
                it.none { char -> char.isISOControl() } &&
                // JNI's Modified UTF-8 encodes supplementary characters as surrogate pairs.
                it.none(Char::isSurrogate)
        }
    }
}
