package com.dormpanel.app.device

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

data class DeviceControlState(
    val brightness: Int = 50,
    val useSystemBrightness: Boolean = false,
    val keepAwake: Boolean = true,
    val blackout: Boolean = false,
    val mediaVolume: Int = 0,
    val mediaMax: Int = 1,
    val muted: Boolean = false,
    val muteAvailable: Boolean = true,
) {
    val mediaPercent get() = if (muted) 0 else (mediaVolume * 100f / mediaMax.coerceAtLeast(1)).roundToInt()
}

interface DeviceControlStore {
    fun brightness(): Int?
    fun keepAwake(): Boolean
    fun lastAudibleVolume(): Int
    fun saveBrightness(value: Int)
    fun saveKeepAwake(value: Boolean)
    fun saveLastAudibleVolume(value: Int)
}

interface MediaVolumePort {
    val max: Int
    val muteAvailable: Boolean
    fun current(): Int
    fun set(value: Int)
    fun muted(): Boolean
    fun mute(value: Boolean)
}

/** Owns intent and observed media state. A Window is deliberately never retained here. */
class DeviceControlController(private val store: DeviceControlStore, private val audio: MediaVolumePort) {
    private val listeners = linkedSetOf<(DeviceControlState) -> Unit>()
    var brightnessCommand: ((Int) -> Unit)? = null
    var volumeCommand: ((Int) -> Unit)? = null
    private var lastAudible = store.lastAudibleVolume().coerceIn(1, audio.max.coerceAtLeast(1))
    var state = DeviceControlState(store.brightness()?.coerceIn(1, 100) ?: 50, store.brightness() == null, store.keepAwake(),
        mediaVolume = audio.current().coerceIn(0, audio.max.coerceAtLeast(1)), mediaMax = audio.max.coerceAtLeast(1), muted = audio.muted(),
        muteAvailable = audio.muteAvailable)
        private set

    fun addListener(listener: (DeviceControlState) -> Unit) { listeners += listener; listener(state) }
    fun removeListener(listener: (DeviceControlState) -> Unit) { listeners -= listener }
    private fun update(next: DeviceControlState) {
        if (next == state) return
        state = next
        listeners.toList().forEach { it(next) }
    }
    fun refreshAudio() {
        val maximum = audio.max.coerceAtLeast(1)
        val current = audio.current().coerceIn(0, maximum)
        val muted = audio.muted() || current == 0
        if (current > 0 && !muted) remember(current)
        update(state.copy(mediaVolume = current, mediaMax = maximum, muted = muted))
    }
    fun setBrightness(percent: Int, remote: Boolean = false) {
        if (percent !in 1..100 || (percent == state.brightness && !state.useSystemBrightness)) return
        store.saveBrightness(percent)
        update(state.copy(brightness = percent, useSystemBrightness = false))
        if (!remote) brightnessCommand?.invoke(percent)
    }
    fun setKeepAwake(enabled: Boolean) {
        if (enabled == state.keepAwake) return
        store.saveKeepAwake(enabled)
        update(state.copy(keepAwake = enabled))
    }
    fun enterBlackout() = update(state.copy(blackout = true))
    fun exitBlackout() = update(state.copy(blackout = false))
    fun setMediaPercent(percent: Int, remote: Boolean = false) {
        if (percent !in 0..100 || (percent == 0 && !audio.muteAvailable)) return
        val target = if (percent == 0) 0 else (percent * audio.max.coerceAtLeast(1) / 100f).roundToInt().coerceAtLeast(1)
        if ((target == 0 && audio.muted()) || (target > 0 && target == audio.current() && !audio.muted())) {
            refreshAudio()
            return
        }
        if (target == 0) {
            if (!audio.muted()) audio.mute(true)
        } else {
            if (target != audio.current()) audio.set(target)
            if (audio.muted()) audio.mute(false)
        }
        refreshAudio()
        if (!remote) volumeCommand?.invoke(state.mediaPercent)
    }
    fun toggleMute() {
        if (!audio.muteAvailable) return
        refreshAudio()
        if (!state.muted) {
            remember(state.mediaVolume)
            audio.mute(true)
            refreshAudio()
            volumeCommand?.invoke(0)
        } else {
            val target = lastAudible.coerceIn(1, audio.max.coerceAtLeast(1))
            audio.set(target)
            audio.mute(false)
            refreshAudio()
            volumeCommand?.invoke(state.mediaPercent)
        }
    }
    private fun remember(value: Int) {
        if (lastAudible == value) return
        lastAudible = value
        store.saveLastAudibleVolume(value)
    }
}

class PreferencesDeviceControlStore(context: Context) : DeviceControlStore {
    private val prefs = context.getSharedPreferences("device_controls", Context.MODE_PRIVATE)
    override fun brightness(): Int? = if (prefs.contains("brightness")) prefs.getInt("brightness", 50) else null
    override fun keepAwake() = prefs.getBoolean("keep_awake", true)
    override fun lastAudibleVolume() = prefs.getInt("last_audible_volume", 6)
    override fun saveBrightness(value: Int) { prefs.edit().putInt("brightness", value).apply() }
    override fun saveKeepAwake(value: Boolean) { prefs.edit().putBoolean("keep_awake", value).apply() }
    override fun saveLastAudibleVolume(value: Int) { prefs.edit().putInt("last_audible_volume", value).apply() }
}

class AndroidMediaVolumePort(context: Context) : MediaVolumePort {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    // Physical X08E / Android 9 probe: ADJUST_MUTE, setStreamMute, adjustVolume,
    // and index 0 all leave STREAM_MUSIC unmuted (minimum index 1).
    override val muteAvailable = android.os.Build.MODEL != "X08E"
    override val max get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    override fun current() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    override fun set(value: Int) = audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
    override fun muted() = audio.isStreamMute(AudioManager.STREAM_MUSIC)
    override fun mute(value: Boolean) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (value) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
    }
}
