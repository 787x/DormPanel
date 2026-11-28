package com.dormpanel.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.dormpanel.app.R
import com.dormpanel.app.airplay.AirPlayController
import com.dormpanel.app.airplay.AirPlayState
import com.dormpanel.app.airplay.label
import com.dormpanel.app.airplay.showAirPlaySettings
import com.dormpanel.app.appearance.AppearanceAware
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.appearance.ThemeMode
import com.dormpanel.app.appearance.applyAppearanceTree
import com.dormpanel.app.device.DeviceControlController
import com.dormpanel.app.device.DeviceControlState
import com.dormpanel.app.ha.DashboardBackend
import com.dormpanel.app.ha.HaConnectionState
import com.dormpanel.app.ha.HaStatus
import com.dormpanel.app.schedule.WebDavAccount
import com.dormpanel.app.schedule.WebDavCredentials
import com.dormpanel.app.schedule.WebDavSettings
import com.dormpanel.app.schedule.WebDavSyncController
import com.dormpanel.app.schedule.saveImportAccount
import com.dormpanel.app.startup.StartupPolicy
import com.dormpanel.app.startup.SystemHomeNavigator
import kotlin.math.roundToInt

@android.annotation.SuppressLint("ViewConstructor")
class ControlCenterView(
    context: Context,
    private val appearance: AppearanceController,
    private val device: DeviceControlController,
    private val onGestureClaimed: () -> Unit,
    private val backend: DashboardBackend? = null,
    private val startupPolicy: StartupPolicy,
    private val webDavSettings: WebDavSettings? = null,
    private val webDavSync: WebDavSyncController? = null,
    private val showPortal: ((() -> Unit) -> Unit)? = null,
    private val closePortal: () -> Unit = {},
    private val onReturnHome: () -> Unit,
) : LinearLayout(context), PageInteraction, AppearanceAware {
    private val openPortal: (() -> Unit)? = showPortal?.let { show -> { show(::updateWebDavStatus) } }
    private var openWeatherCenter: (() -> Unit)? = null

    internal fun setWeatherCenterAction(open: () -> Unit) { openWeatherCenter = open }

    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()
    private val secondaryLabels = mutableListOf<TextView>()
    private val dividers = mutableListOf<View>()
    private val switches = mutableListOf<SwitchCompat>()
    private val returnSwipeArea = secondaryLabel(context.getString(R.string.return_home_up)).apply {
        gravity = Gravity.CENTER
        minHeight = dp(44)
    }
    private val contentScroll = ScrollView(context).apply { id = R.id.control_center_scroll }
    private var returnSwipe = false

    private val brightnessLabel = label(size = 18f)
    private val systemLabel = label(size = 18f)
    private val systemBrightness = slider(context.getString(R.string.control_center_system_brightness)).apply { min = 1 }
    private val automatic = toggle(context.getString(R.string.control_center_auto)).apply { contentDescription = context.getString(R.string.control_center_auto_description) }
    private val followSystem = toggle(context.getString(R.string.control_center_follow))
    private val allowSystem = action(context.getString(R.string.control_center_grant), context.getString(R.string.control_center_grant_description), ::requestBrightnessAccess)
    private val permissionStatus = secondaryLabel().apply { visibility = GONE }
    private val volumeLabel = label(size = 18f)
    private val muteUnavailable = secondaryLabel(context.getString(R.string.control_center_mute_unavailable))
    private val opacityLabel = label(size = 18f)
    private val brightness = slider(context.getString(R.string.control_center_brightness)).apply { min = 1 }
    private val volume = slider(context.getString(R.string.control_center_volume))
    private val airPlay = AirPlayController.get(context)
    private val airPlayStatus = secondaryLabel().apply { maxLines = 3 }
    private val airPlayToggle = action(context.getString(R.string.airplay_enable)) {
        val saved = airPlay.settings
        if (saved.enabled) {
            airPlay.configure(saved.name, false, saved.autoStart)
        } else {
            val enable = { airPlay.configure(saved.name, true, saved.autoStart); Unit }
            val activity = context as? com.dormpanel.app.MainActivity
            if (activity != null) activity.withAirPlayNetworkPermission(enable) else enable()
        }
    }
    private val airPlayListener: (AirPlayState) -> Unit = { state ->
        airPlayStatus.text = listOfNotNull(state.name, state.phase.label(context), state.detail).joinToString(" · ")
        airPlayToggle.setText(if (airPlay.settings.enabled) R.string.airplay_disable else R.string.airplay_enable)
    }
    private val opacity = slider(context.getString(R.string.card_opacity))
    private val sliders = listOf(systemBrightness, brightness, volume, opacity)
    private val keepAwake = toggle(context.getString(R.string.control_center_awake))
    private val mute = action(context.getString(R.string.control_center_mute)) { device.toggleMute() }
    private val haStatus = secondaryLabel()
    private val webDavStatus = secondaryLabel()
    private val startAfterBoot = toggle(context.getString(R.string.start_after_boot)).apply { id = R.id.start_after_boot }
    private val homeStatus = secondaryLabel().apply { visibility = GONE }
    private val bootPreferenceListener = CompoundButton.OnCheckedChangeListener { _, enabled ->
        if (!startupPolicy.setStartAfterBoot(enabled)) {
            showBootPreference()
            homeStatus.setText(R.string.start_after_boot_save_failed)
            homeStatus.visibility = View.VISIBLE
        } else {
            homeStatus.visibility = View.GONE
        }
    }
    private fun showBootPreference() {
        startAfterBoot.setOnCheckedChangeListener(null)
        startAfterBoot.isChecked = startupPolicy.startAfterBoot
        startAfterBoot.setOnCheckedChangeListener(bootPreferenceListener)
    }
    private val bootStateListener: (Boolean) -> Unit = { showBootPreference() }
    private val light = themeOption(R.string.theme_light)
    private val dark = themeOption(R.string.theme_dark)
    private val modes = RadioGroup(context).apply {
        orientation = HORIZONTAL
        addView(light)
        addView(dark, RadioGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
    }

    private fun slider(description: String) = ClaimingSeekBar(context, onGestureClaimed).apply {
        max = 100
        contentDescription = description
    }

    private fun column() = LinearLayout(context).apply { orientation = VERTICAL }

    private fun label(value: String = "", size: Float, heading: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        if (heading) {
            setTypeface(typeface, Typeface.BOLD)
            isAccessibilityHeading = true
        }
    }

    private fun secondaryLabel(value: String = "") = label(value, 14f).also(secondaryLabels::add)

    private fun toggle(value: String) = SwitchCompat(context).apply {
        text = value
        textSize = 16f
        minHeight = dp(48)
        switchPadding = dp(8)
    }.also(switches::add)

    private fun themeOption(title: Int) = RadioButton(context).apply {
        id = generateViewId()
        setText(title)
        textSize = 16f
        minHeight = dp(48)
    }

    private fun action(value: String, description: String? = null, click: () -> Unit) = Button(context).apply {
        text = value
        contentDescription = description
        isAllCaps = false
        textSize = 18f
        minimumHeight = dp(56)
        minHeight = dp(56)
        minimumWidth = dp(144)
        minWidth = dp(144)
        setPadding(dp(16), dp(6), dp(16), dp(6))
        setOnClickListener {
            onGestureClaimed()
            click()
        }
    }

    private fun row(leading: View, vararg trailing: View) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(32)
        addView(leading, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        for (view in trailing) {
            addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
        }
    }

    private fun LinearLayout.heading(title: String, vararg actions: View) {
        if (childCount > 0) {
            val divider = View(context).also(dividers::add)
            addView(divider, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
                bottomMargin = dp(4)
            })
        }
        addView(row(label(title, 21f, heading = true), *actions))
    }

    override fun applyAppearance(state: AppearanceState) {
        val palette = PanelPalette.forMode(state.themeMode)
        for (index in 0 until childCount) applyAppearanceTree(getChildAt(index), state)
        secondaryLabels.forEach { it.setTextColor(palette.secondary) }
        dividers.forEach { it.setBackgroundColor((palette.secondary and 0x00ffffff) or 0x40000000) }
        val toggleColors = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(palette.secondary, palette.accent, palette.secondary),
        )
        val toggleText = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(palette.secondary, palette.text),
        )
        val sliderColors = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(palette.secondary, palette.accent),
        )
        for (slider in sliders) {
            slider.progressTintList = sliderColors
            slider.thumbTintList = sliderColors
        }
        switches.forEach {
            it.thumbTintList = toggleColors
            it.trackTintList = toggleColors.withAlpha(100)
            it.setTextColor(toggleText)
        }
    }

    private val appearanceListener: (AppearanceState) -> Unit = {
        modes.check(if (it.themeMode == ThemeMode.LIGHT) light.id else dark.id)
        val percent = (it.cardSurfaceOpacity * 100).roundToInt()
        opacity.progress = percent
        opacityLabel.text = context.getString(R.string.card_opacity_value, percent)
    }
    private val deviceListener: (DeviceControlState) -> Unit = {
        brightness.progress = it.brightness
        brightnessLabel.text = context.getString(R.string.control_center_brightness_value, it.brightness)
        brightness.isEnabled = !it.useSystemBrightness
        followSystem.isChecked = it.useSystemBrightness
        systemBrightness.progress = it.system.percent
        systemBrightness.isEnabled = it.system.canWrite && !it.system.automatic
        automatic.isChecked = it.system.automatic
        automatic.isEnabled = it.system.canWrite
        val systemValue = context.getString(R.string.control_center_system_brightness_value, it.system.percent)
        systemLabel.text = if (it.system.canWrite) systemValue else {
            "$systemValue · ${context.getString(R.string.control_center_read_only)}"
        }
        allowSystem.visibility = if (it.system.canWrite) View.GONE else View.VISIBLE
        if (it.system.canWrite) permissionStatus.visibility = View.GONE
        volume.min = if (it.muteAvailable) 0 else (100f / it.mediaMax).roundToInt()
        volume.progress = it.mediaPercent
        volumeLabel.text = context.getString(R.string.control_center_volume_value, it.mediaPercent)
        keepAwake.isChecked = it.keepAwake
        mute.visibility = if (it.muteAvailable) VISIBLE else GONE
        muteUnavailable.visibility = if (it.muteAvailable) GONE else VISIBLE
        mute.setText(if (it.muted) R.string.control_center_unmute else R.string.control_center_mute)
    }
    private val haListener: (HaStatus) -> Unit = { status ->
        val stateLabel = when (status.state) {
            HaConnectionState.NOT_CONFIGURED -> R.string.control_center_not_configured
            HaConnectionState.CONNECTING -> R.string.control_center_connecting
            HaConnectionState.SYNCING -> R.string.control_center_syncing
            HaConnectionState.CONNECTED -> R.string.control_center_connected
            HaConnectionState.RECONNECTING -> R.string.control_center_reconnecting
            HaConnectionState.AUTH_ERROR -> R.string.control_center_auth_error
            HaConnectionState.ERROR -> R.string.control_center_connection_error
        }
        val state = context.getString(stateLabel)
        haStatus.text = if (status.detail.isBlank()) state else "$state · ${status.detail}"
    }

    init {
        orientation = VERTICAL
        setPadding(dp(24), dp(10), dp(24), dp(24))
        val title = label(context.getString(R.string.control_center_title), 28f, heading = true)
        val home = action(context.getString(R.string.control_center_home), context.getString(R.string.control_center_home_description), onReturnHome)
        val headerActions = listOfNotNull(home, openPortal?.let { action("扫码配置", click = it) })
        addView(row(title, *headerActions.toTypedArray()), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        val columns = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }
        contentScroll.addView(columns, android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(contentScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val left = column()
        val right = column()
        columns.addView(left, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(24) })
        columns.addView(right, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        left.heading(context.getString(R.string.control_center_display))
        left.addView(row(systemLabel, automatic))
        left.addView(systemBrightness)
        left.addView(allowSystem, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        left.addView(permissionStatus)
        left.addView(row(brightnessLabel, followSystem))
        left.addView(brightness)
        val awakeToggle = LinearLayout(context).apply {
            addView(keepAwake, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        left.addView(row(awakeToggle, action(context.getString(R.string.control_center_blackout)) { device.enterBlackout() }))
        left.heading(context.getString(R.string.control_center_audio))
        left.addView(row(volumeLabel, mute, muteUnavailable))
        left.addView(volume)
        left.addView(row(airPlayStatus, airPlayToggle,
            action(context.getString(R.string.airplay_settings), null) {
                showAirPlaySettings(context, appearance, airPlay)
            }.apply {
                minimumWidth = dp(96)
                minWidth = dp(96)
            }))

        right.heading(context.getString(R.string.control_center_appearance))
        right.addView(row(label(context.getString(R.string.control_center_theme), 18f), modes))
        right.addView(opacityLabel)
        right.addView(opacity)
        if (backend != null) {
            right.heading("天气",
                action("天气中心") { openWeatherCenter?.invoke() },
                action("设置", "天气服务与地点") {
                    com.dormpanel.app.weather.WeatherSettingsDialog(context, backend.weatherServices, openPortal = openPortal).show()
                },
            )
            right.heading("Home Assistant", action("设置", context.getString(R.string.ha_settings)) {
                com.dormpanel.app.ha.HaSettingsDialog(context, backend, openPortal).show()
            })
            right.addView(haStatus)
        }
        if (webDavSettings != null && webDavSync != null) {
            right.heading("WebDAV", action("设置", "WebDAV 连接设置", ::showWebDavSettings))
            right.addView(webDavStatus)
            updateWebDavStatus()
        }
        right.heading(context.getString(R.string.system_section))
        right.addView(startAfterBoot, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        right.addView(action(context.getString(R.string.open_miui_home)) {
            if (!SystemHomeNavigator(context).openHome()) {
                homeStatus.setText(R.string.open_miui_home_failed)
                homeStatus.visibility = VISIBLE
            } else {
                homeStatus.visibility = GONE
            }
        }.apply { id = R.id.open_miui_home }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        right.addView(homeStatus)
        addView(returnSwipeArea)
        brightness.onUserProgress { device.setBrightness(it.coerceAtLeast(1)) }
        systemBrightness.onUserProgress { device.setSystemBrightness(it.coerceAtLeast(1)) }
        fun onAutomaticChanged(checked: Boolean) {
            if (!device.setSystemAutomatic(checked)) {
                automatic.setOnCheckedChangeListener(null)
                automatic.isChecked = device.state.system.automatic
                automatic.setOnCheckedChangeListener { _, next -> onAutomaticChanged(next) }
            }
        }
        automatic.setOnCheckedChangeListener { _, checked -> onAutomaticChanged(checked) }
        followSystem.setOnCheckedChangeListener { _, checked -> device.setFollowSystem(checked) }
        volume.onUserProgress { device.setMediaPercent(it); volume.progress = device.state.mediaPercent }
        keepAwake.setOnCheckedChangeListener { _, checked -> device.setKeepAwake(checked) }
        modes.setOnCheckedChangeListener { _, id -> appearance.setTheme(if (id == light.id) ThemeMode.LIGHT else ThemeMode.DARK) }
        opacity.onUserProgress { appearance.setCardOpacity(it / 100f) }
        showBootPreference()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        showBootPreference()
        device.refreshAudio()
        device.refreshSystemBrightness()
        device.addListener(deviceListener)
        airPlay.addListener(airPlayListener)
        appearance.addListener(appearanceListener)
        startupPolicy.addListener(bootStateListener)
        backend?.ha?.addStatusListener(haListener)
    }
    override fun onDetachedFromWindow() {
        returnSwipe = false
        closePortal()
        backend?.ha?.removeStatusListener(haListener)
        startupPolicy.removeListener(bootStateListener)
        appearance.removeListener(appearanceListener)
        device.removeListener(deviceListener)
        airPlay.removeListener(airPlayListener)
        super.onDetachedFromWindow()
    }
    private fun contentOverflows(): Boolean =
        contentScroll.canScrollVertically(1) || contentScroll.canScrollVertically(-1)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val hint = context.getString(if (contentOverflows()) R.string.return_home_up_here else R.string.return_home_up)
        if (returnSwipeArea.text != hint) returnSwipeArea.text = hint
    }

    override fun shouldObservePageSwipe(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val bounds = Rect()
            fun hits(view: View): Boolean = view.getGlobalVisibleRect(bounds) &&
                bounds.contains(event.rawX.toInt(), event.rawY.toInt())
            // A scrollable viewport owns the stream even when it starts at either scroll boundary.
            returnSwipe = if (contentOverflows()) hits(returnSwipeArea) else sliders.none(::hits)
        }
        val observe = returnSwipe
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            returnSwipe = false
        }
        return observe
    }

    private fun requestBrightnessAccess() {
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")))
        } catch (_: Exception) {
            permissionStatus.text = context.getString(R.string.control_center_grant_failed)
            permissionStatus.visibility = VISIBLE
        }
    }

    private fun updateWebDavStatus() {
        val account = webDavSettings?.account()
        val label = context.getString(if (account == null) R.string.control_center_not_configured else R.string.control_center_configured)
        val host = account?.let { Uri.parse(it.baseUrl).host }
        webDavStatus.text = if (host.isNullOrBlank()) label else "$label · $host"
    }

    private fun showWebDavSettings() {
        val settings = webDavSettings ?: return
        val sync = webDavSync ?: return
        val (oldUrl, oldUser) = settings.publicAccount()
        val fields = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        fun field(hintText: String, value: String, secret: Boolean = false) = EditText(context).apply {
            hint = hintText; contentDescription = hintText; setSingleLine(); setText(value)
            if (secret) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            fields.addView(this)
        }
        val url = field("WebDAV 服务地址", oldUrl)
        val user = field("用户名", oldUser)
        val password = field("密码（仅同一访问身份可留空复用）", "", true)
        val status = TextView(context).apply { textSize = 16f; text = "建议使用 HTTPS，已保存密码不会回显。" }
        lateinit var dialog: AlertDialog
        openPortal?.let { open ->
            fields.addView(Button(context).apply { text = "扫码配置"; setOnClickListener { dialog.dismiss(); open() } })
        }
        fields.addView(status)
        fun candidate(saved: WebDavAccount? = settings.account()): WebDavAccount? {
            val entered = url.text.toString().trim()
            val username = user.text.toString()
            return runCatching { WebDavCredentials.candidate(saved,entered,username,password.text.toString(),
                WebDavCredentials.inheritedCertificate(saved,entered,username)) }
                .onFailure { status.text = it.message }.getOrNull()
        }
        val test = Button(context).apply { text = "测试连接"; setOnClickListener {
            val account = candidate() ?: return@setOnClickListener
            status.text = "正在测试…"
            sync.test(account) { result -> status.text = result.fold({ "连接成功。" }, { it.message ?: "连接失败。" }) }
        } }
        fields.addView(test)
        dialog = AlertDialog.Builder(context).setTitle("全局 WebDAV 导入账号").setView(fields)
            .setNegativeButton("关闭", null).setNeutralButton("清除凭据", null)
            .setPositiveButton("保存", null).create()
        dialog.setOnDismissListener { password.text.clear() }
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val old = settings.account()
            val account = candidate(old) ?: return@setOnClickListener
            saveImportAccount(context, settings, sync, old, account) { error ->
                if (error == null) { updateWebDavStatus(); dialog.dismiss() }
                else status.text = error
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            AlertDialog.Builder(context).setTitle("清除 WebDAV 凭据？")
                .setMessage("将移除已保存的账号凭据，已导入的课程内容保留。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ ->
                    settings.clearAccount(); updateWebDavStatus(); dialog.dismiss()
                }.show()
        }
    }
}
