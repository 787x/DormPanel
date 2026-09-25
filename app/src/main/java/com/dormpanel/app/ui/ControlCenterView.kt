package com.dormpanel.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.MotionEvent
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.EditText
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.schedule.WebDavSettings
import com.dormpanel.app.schedule.WebDavSyncController
import com.dormpanel.app.schedule.WebDavClient
import com.dormpanel.app.schedule.WebDavAccount
import com.dormpanel.app.R
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.ThemeMode
import com.dormpanel.app.device.DeviceControlController
import com.dormpanel.app.device.DeviceControlState
import com.dormpanel.app.ha.DashboardBackend
import com.dormpanel.app.ha.HaStatus
import com.dormpanel.app.startup.StartupPolicy
import com.dormpanel.app.startup.SystemHomeNavigator
import kotlin.math.roundToInt

@android.annotation.SuppressLint("ViewConstructor")
class ControlCenterView(context: Context, private val appearance: AppearanceController,
    private val device: DeviceControlController, private val onGestureClaimed: () -> Unit,
    private val backend: DashboardBackend? = null,
    private val startupPolicy: StartupPolicy,
    private val webDavSettings: WebDavSettings? = null,
    private val webDavSync: WebDavSyncController? = null,
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
    private val webDavStatus = TextView(context).apply { textSize = 18f }
    private val startAfterBoot = CheckBox(context).apply {
        id = R.id.start_after_boot
        setText(R.string.start_after_boot)
        textSize = 20f
        minimumHeight = dp(52)
    }
    private val homeStatus = TextView(context).apply { textSize = 16f; visibility = View.GONE }
    private val bootPreferenceListener = CompoundButton.OnCheckedChangeListener { _, enabled ->
        if (!startupPolicy.setStartAfterBoot(enabled)) {
            showBootPreference()
            homeStatus.setText(R.string.start_after_boot_save_failed)
            homeStatus.visibility = View.VISIBLE
        } else {
            updateBootLabel(enabled)
            homeStatus.visibility = View.GONE
        }
    }
    private fun updateBootLabel(enabled: Boolean) {
        startAfterBoot.text = context.getString(R.string.start_after_boot_state,
            context.getString(if (enabled) R.string.start_after_boot_on else R.string.start_after_boot_off))
    }
    private fun showBootPreference() {
        startAfterBoot.setOnCheckedChangeListener(null)
        val enabled = startupPolicy.startAfterBoot
        startAfterBoot.isChecked = enabled
        updateBootLabel(enabled)
        startAfterBoot.setOnCheckedChangeListener(bootPreferenceListener)
    }
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
        if (webDavSettings != null) {
            right.addView(section("WebDAV"))
            right.addView(webDavStatus)
            right.addView(Button(context).apply { text = "WebDAV settings"; minimumHeight = dp(48)
                setOnClickListener { onGestureClaimed(); showWebDavSettings() } })
            updateWebDavStatus()
        }
        right.addView(section(context.getString(R.string.system_section)))
        right.addView(startAfterBoot)
        right.addView(Button(context).apply {
            id = R.id.open_miui_home
            setText(R.string.open_miui_home)
            minimumHeight = dp(52)
            setOnClickListener {
                onGestureClaimed()
                if (!SystemHomeNavigator(context).openHome()) {
                    homeStatus.setText(R.string.open_miui_home_failed)
                    homeStatus.visibility = View.VISIBLE
                } else {
                    homeStatus.visibility = View.GONE
                }
            }
        })
        right.addView(homeStatus)
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
        showBootPreference()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        showBootPreference()
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

    private fun updateWebDavStatus() {
        webDavStatus.text = if (webDavSettings?.account() == null) "Not configured" else "Configured"
    }

    private fun showWebDavSettings() {
        val settings = webDavSettings ?: return
        val sync = webDavSync ?: return
        val (oldUrl, oldUser) = settings.publicAccount()
        val fields = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        fun field(hintText: String, value: String, secret: Boolean = false) = EditText(context).apply {
            hint = hintText; setSingleLine(); setText(value)
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            fields.addView(this)
        }
        val url = field("WebDAV server URL", oldUrl)
        val user = field("Username", oldUser)
        val password = field("Password (blank keeps saved password)", "", true)
        val status = TextView(context).apply { textSize = 16f; text = "Use HTTPS when possible. Saved passwords are never displayed." }
        fields.addView(status)
        fun candidate(): WebDavAccount? {
            val entered = url.text.toString().trim()
            val username = user.text.toString()
            val saved = settings.account()
            val secret = password.text.toString().ifBlank {
                if (saved?.baseUrl == entered && saved.username == username) saved.password else ""
            }
            if (secret.isBlank()) { status.text = "Enter the account password."; return null }
            return runCatching { WebDavClient().url(entered); WebDavAccount(entered, username, secret) }
                .onFailure { status.text = it.message }.getOrNull()
        }
        val test = Button(context).apply { text = "Test connection"; setOnClickListener {
            val account = candidate() ?: return@setOnClickListener
            status.text = "Testing…"
            sync.test(account) { result -> status.text = result.fold({ "Connection successful." }, { it.message ?: "Connection failed." }) }
        } }
        fields.addView(test)
        val dialog = AlertDialog.Builder(context).setTitle("Shared WebDAV account").setView(fields)
            .setNegativeButton("Close", null).setNeutralButton("Clear credentials", null)
            .setPositiveButton("Save", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val account = candidate() ?: return@setOnClickListener
            val old = settings.account()
            fun save() { settings.saveAccount(account); updateWebDavStatus(); dialog.dismiss() }
            val client = WebDavClient()
            val previous = old?.let { client.url(it.baseUrl) }
            val next = client.url(account.baseUrl)
            val originChanged = previous != null && (previous.scheme != next.scheme || previous.host != next.host || previous.port != next.port)
            val bindings = settings.bindings()
            if (originChanged && bindings.isNotEmpty()) {
                AlertDialog.Builder(context).setTitle("WebDAV server changed")
                    .setMessage("${bindings.size} timetable bindings point to the previous server. Remove those bindings? Imported timetable content stays on this screen; choose a new remote ICS source later.")
                    .setNegativeButton("Keep current account", null)
                    .setPositiveButton("Remove bindings and save") { _, _ -> sync.clearBindingsForAccountChange(); save() }.show()
            } else save()
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            AlertDialog.Builder(context).setTitle("Clear WebDAV credentials?")
                .setMessage("Saved account credentials will be removed. Imported timetables stay intact.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                    settings.clearAccount(); updateWebDavStatus(); dialog.dismiss()
                }.show()
        }
    }
}
