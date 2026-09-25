package com.dormpanel.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.MotionEvent
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.dormpanel.app.R
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.ThemeMode
import com.dormpanel.app.device.DeviceControlController
import com.dormpanel.app.device.DeviceControlState
import com.dormpanel.app.ha.DashboardBackend
import com.dormpanel.app.ha.HaStatus
import kotlin.math.roundToInt

@android.annotation.SuppressLint("ViewConstructor")
class ControlCenterView(context: Context, private val appearance: AppearanceController,
    private val device: DeviceControlController, private val onGestureClaimed: () -> Unit,
    private val backend: DashboardBackend? = null,
) : LinearLayout(context), PageInteraction {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()
    private var claimed = false
    private val brightnessLabel = TextView(context).apply { textSize = 21f }
    private val systemLabel = TextView(context).apply { textSize = 21f }
    private val systemBrightness = slider("System brightness").apply { min = 1 }
    private val automatic = CheckBox(context).apply { text = "Automatic system brightness"; textSize = 19f; minimumHeight = dp(48) }
    private val followSystem = CheckBox(context).apply { text = "Follow system"; textSize = 19f; minimumHeight = dp(48) }
    private val allowSystem = Button(context).apply { text = "Allow system brightness control"; minimumHeight = dp(48) }
    private val permissionStatus = TextView(context).apply { textSize = 16f; visibility = View.GONE }
    private val volumeLabel = TextView(context).apply { textSize = 23f }
    private val opacityLabel = TextView(context).apply { textSize = 22f }
    private val brightness = slider("DormPanel brightness")
        .apply { min = 1 }
    private val volume = slider("Media volume")
    private val opacity = slider(context.getString(R.string.card_opacity))
    private val keepAwake = CheckBox(context).apply { text = "Keep screen awake"; textSize = 20f; minimumHeight = dp(52) }
    private val mute = Button(context).apply { minimumHeight = dp(52); textSize = 18f }
    private val haStatus = TextView(context).apply { textSize = 16f }
    private val light = RadioButton(context).apply { id = generateViewId(); setText(R.string.theme_light); textSize = 20f; minimumHeight = dp(52) }
    private val dark = RadioButton(context).apply { id = generateViewId(); setText(R.string.theme_dark); textSize = 20f; minimumHeight = dp(52) }
    private val modes = RadioGroup(context).apply { orientation = HORIZONTAL; addView(light); addView(dark) }

    private fun slider(description: String) = ClaimingSeekBar(context) { claimed = true; onGestureClaimed() }.apply {
        max = 100
        contentDescription = description
        minimumHeight = dp(52)
    }
    private fun section(title: String) = TextView(context).apply { text = title; textSize = 27f; setPadding(0, dp(12), 0, dp(8)) }
    private val appearanceListener: (AppearanceState) -> Unit = {
        modes.check(if (it.themeMode == ThemeMode.LIGHT) light.id else dark.id)
        val percent = (it.cardSurfaceOpacity * 100).roundToInt()
        opacity.progress = percent
        opacityLabel.text = context.getString(R.string.card_opacity_value, percent)
    }
    private val deviceListener: (DeviceControlState) -> Unit = {
        brightness.progress = it.brightness
        brightnessLabel.text = "DormPanel override · ${it.brightness}%"
        brightness.isEnabled = !it.useSystemBrightness
        followSystem.isChecked = it.useSystemBrightness
        systemBrightness.progress = it.system.percent
        systemBrightness.isEnabled = it.system.canWrite && !it.system.automatic
        automatic.isChecked = it.system.automatic
        automatic.isEnabled = it.system.canWrite
        systemLabel.text = "System · ${if (it.system.automatic) "Automatic" else "Manual"} · ${it.system.percent}%${if (it.system.canWrite) "" else " · Read only"}"
        allowSystem.visibility = if (it.system.canWrite) View.GONE else View.VISIBLE
        if (it.system.canWrite) permissionStatus.visibility = View.GONE
        volume.progress = it.mediaPercent
        volume.min = if (it.muteAvailable) 0 else (100f / it.mediaMax).roundToInt()
        volumeLabel.text = "Media volume · ${it.mediaPercent}%"
        keepAwake.isChecked = it.keepAwake
        mute.isEnabled = it.muteAvailable
        mute.text = if (!it.muteAvailable) "Mute unavailable on X08E" else if (it.muted) "Unmute" else "Mute"
    }
    private val haListener: (HaStatus) -> Unit = { haStatus.text = context.getString(R.string.ha_status, it.state, it.detail) }

    init {
        orientation = VERTICAL
        setPadding(dp(40), dp(22), dp(40), dp(16))
        addView(TextView(context).apply { setText(R.string.control_center_title); textSize = 36f })
        val columns = LinearLayout(context).apply { orientation = HORIZONTAL }
        addView(columns, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val left = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, 0, dp(28), 0) }
        val right = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(28), 0, 0, 0) }
        columns.addView(left, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        columns.addView(right, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        left.addView(section("Display"))
        left.addView(systemLabel)
        left.addView(automatic)
        left.addView(systemBrightness)
        left.addView(allowSystem)
        left.addView(permissionStatus)
        left.addView(brightnessLabel)
        left.addView(followSystem)
        left.addView(brightness)
        left.addView(keepAwake)
        left.addView(Button(context).apply {
            text = "Blackout screen"
            minimumHeight = dp(52)
            setOnClickListener { onGestureClaimed(); device.enterBlackout() }
        })
        left.addView(section("Audio"))
        left.addView(volumeLabel)
        left.addView(volume)
        left.addView(mute)
        right.addView(section("Appearance"))
        right.addView(modes)
        right.addView(opacityLabel)
        right.addView(opacity)
        right.addView(section("Home Assistant"))
        right.addView(haStatus)
        if (backend != null) right.addView(Button(context).apply {
            setText(R.string.ha_settings)
            minimumHeight = dp(52)
            setOnClickListener { onGestureClaimed(); com.dormpanel.app.ha.HaSettingsDialog(context, backend).show() }
        })
        addView(TextView(context).apply { setText(R.string.return_home_up); textSize = 16f })
        brightness.onUserProgress { device.setBrightness(it.coerceAtLeast(1)) }
        systemBrightness.onUserProgress { device.setSystemBrightness(it.coerceAtLeast(1)) }
        automatic.setOnCheckedChangeListener { _, checked -> device.setSystemAutomatic(checked) }
        followSystem.setOnCheckedChangeListener { _, checked -> device.setFollowSystem(checked) }
        allowSystem.setOnClickListener {
            onGestureClaimed()
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${context.packageName}")))
            } catch (_: Exception) {
                permissionStatus.text = "System brightness access is unavailable on this device."
                permissionStatus.visibility = View.VISIBLE
            }
        }
        volume.onUserProgress { device.setMediaPercent(it); volume.progress = device.state.mediaPercent }
        keepAwake.setOnCheckedChangeListener { _, checked -> device.setKeepAwake(checked) }
        mute.setOnClickListener { device.toggleMute() }
        modes.setOnCheckedChangeListener { _, id -> appearance.setTheme(if (id == light.id) ThemeMode.LIGHT else ThemeMode.DARK) }
        opacity.onUserProgress { appearance.setCardOpacity(it / 100f) }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        device.refreshAudio()
        device.refreshSystemBrightness()
        device.addListener(deviceListener)
        appearance.addListener(appearanceListener)
        backend?.ha?.addStatusListener(haListener)
    }
    override fun onDetachedFromWindow() {
        backend?.ha?.removeStatusListener(haListener)
        appearance.removeListener(appearanceListener)
        device.removeListener(deviceListener)
        super.onDetachedFromWindow()
    }
    override fun shouldObservePageSwipe(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            claimed = listOf(systemBrightness, brightness, volume, opacity).any { bar ->
                val at = IntArray(2); bar.getLocationOnScreen(at)
                event.rawX >= at[0] && event.rawX < at[0] + bar.width && event.rawY >= at[1] && event.rawY < at[1] + bar.height
            }
        }
        val observe = !claimed
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) claimed = false
        return observe
    }
    override fun handleBack() = false
}
