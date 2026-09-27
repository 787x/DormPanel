package com.dormpanel.app.ha

import com.dormpanel.app.R
import com.dormpanel.app.lan.PhoneEntryUi
import com.dormpanel.app.lan.SettingsEntryForm
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import okhttp3.Call

/** Low-frequency configuration surface; protocol/state ownership remains in the backend. */
class HaSettingsDialog(private val context: Context, private val backend: DashboardBackend) {
    fun show(): AlertDialog {
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        val advanced = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fun label(text: String, target: LinearLayout = content) {
            target.addView(TextView(context).apply { this.text = text; textSize = 16f })
        }
        fun input(title: String, value: String, target: LinearLayout = content): EditText {
            label(title, target)
            return EditText(context).apply { setText(value); contentDescription = title; isSingleLine = true; target.addView(this) }
        }
        fun choices(title: String, values: List<String>, selected: String, target: LinearLayout = content): Spinner {
            label(title, target)
            return Spinner(context, Spinner.MODE_DROPDOWN).apply {
                contentDescription = title
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values)
                setSelection(values.indexOf(selected).coerceAtLeast(0)); target.addView(this)
            }
        }
        val settings = backend.settings
        val initialSetup = settings.mode == BackendMode.DEMO && settings.baseUrl.isBlank() && !backend.hasToken()
        val status = TextView(context).apply { textSize = 16f; content.addView(this) }
        val url = input("Home Assistant base URL", settings.baseUrl).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val token = input("Access token (blank keeps saved token)", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val diagnostic = TextView(context).apply { textSize = 16f }
        val phoneEntry = PhoneEntryUi(context, SettingsEntryForm.HA,
            { values ->
                url.setText(values.getValue("url")); token.setText(values.getValue("token"))
                diagnostic.text = "Received from phone — review, test, then Save / Reconnect."
            }, { diagnostic.text = it })
        content.addView(Button(context).apply {
            text = "Fill from phone"
            setOnClickListener { phoneEntry.start(mapOf("url" to url.text.toString())) }
        })
        label("HTTP sends your token without encryption. Prefer HTTPS on untrusted networks.")
        if (initialSetup) label("Saving a URL and token enables Home Assistant. Backend mode can be changed in Advanced settings.")
        fun entities(domain: String, saved: String) = (listOf("") + backend.ha.store.discovered(domain).map { it.id } + listOf(saved)).distinct()
        val weather = choices("Preferred weather (blank = first discovered)", entities("weather", settings.weatherEntity), settings.weatherEntity)
        content.addView(Button(context).apply {
            text = "Advanced settings ▸"; contentDescription = "Advanced settings"
            setOnClickListener {
                val expanded = advanced.visibility != View.VISIBLE
                advanced.visibility = if (expanded) View.VISIBLE else View.GONE
                text = if (expanded) "Advanced settings ▾" else "Advanced settings ▸"
            }
        })
        content.addView(advanced)
        val mode = choices("Backend", BackendMode.entries.map { it.name }, settings.mode.name, advanced)
        var modeExplicitlyChosen = false
        mode.setOnTouchListener { _, _ -> modeExplicitlyChosen = true; false }
        val relayName = input("DormPanel relay screen name", backend.relayIdentity.displayName, advanced)
        label("Timetable relay: ${backend.relayStatus}", advanced)
        val theme = choices("Theme helper (optional input_select: light / dark)", entities("input_select", settings.themeEntity), settings.themeEntity, advanced)
        val opacity = choices("Opacity helper (optional input_number: 0–100)", entities("input_number", settings.opacityEntity), settings.opacityEntity, advanced)
        val brightness = choices("DormPanel brightness helper (optional input_number: 1–100)", entities("input_number", settings.displayBrightnessEntity), settings.displayBrightnessEntity, advanced)
        val systemBrightness = choices("System brightness helper (manual only, input_number: 1–100)", entities("input_number", settings.systemBrightnessEntity), settings.systemBrightnessEntity, advanced)
        val volume = choices("Media volume helper (optional input_number: 0–100)", entities("input_number", settings.mediaVolumeEntity), settings.mediaVolumeEntity, advanced)
        val blackout = choices("DormPanel Blackout helper (optional input_boolean)", entities("input_boolean", settings.blackoutEntity), settings.blackoutEntity, advanced)
        val systemAutomatic = choices("Automatic system brightness helper (optional input_boolean)", entities("input_boolean", settings.systemAutomaticEntity), settings.systemAutomaticEntity, advanced)
        val followSystem = choices("Follow system helper (optional input_boolean)", entities("input_boolean", settings.followSystemEntity), settings.followSystemEntity, advanced)
        val keepAwake = choices("Keep screen awake helper (optional input_boolean)", entities("input_boolean", settings.keepAwakeEntity), settings.keepAwakeEntity, advanced)
        val startAfterBoot = choices("Start after boot helper (optional input_boolean)", entities("input_boolean", settings.startAfterBootEntity), settings.startAfterBootEntity, advanced)
        label("System helper updates are sent only for local manual changes; automatic ambient changes are not mirrored.", advanced)
        content.addView(diagnostic)
        var call: Call? = null
        var dismissed = false
        val test = Button(context).apply { text = context.getString(R.string.ha_test); content.addView(this) }
        content.addView(Button(context).apply {
            text = context.getString(R.string.ha_clear)
            setOnClickListener { token.text.clear(); backend.clearCredentials(); diagnostic.text = context.getString(R.string.ha_cleared) }
        })
        val dialog = AlertDialog.Builder(context).setTitle("Home Assistant").setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("Close", null).setPositiveButton("Save / Reconnect", null).create()
        val listener: (HaStatus) -> Unit = { status.text = context.getString(R.string.ha_status, it.state, it.detail) }
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.window?.setLayout((context.resources.displayMetrics.widthPixels * 0.8).toInt(), (context.resources.displayMetrics.heightPixels * 0.9).toInt())
            (context as? android.app.Activity)?.window?.attributes?.screenBrightness?.let { current ->
                dialog.window?.let { window ->
                    val attributes = window.attributes; attributes.screenBrightness = current; window.attributes = attributes
                }
            }
            backend.ha.addStatusListener(listener)
            test.setOnClickListener {
                try {
                    val endpoint = HaEndpoint.parse(url.text.toString())
                    val access = backend.testToken(token.text.toString())
                    require(access.isNotBlank()) { "Enter an access token." }
                    call?.cancel(); diagnostic.text = context.getString(R.string.ha_testing)
                    call = backend.rest.test(endpoint, access) { if (!dismissed) diagnostic.text = it }
                } catch (_: Exception) { diagnostic.text = context.getString(R.string.ha_invalid) }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val selectedMode = BackendMode.valueOf(mode.selectedItem.toString())
                    val effectiveMode = if (initialSetup && !modeExplicitlyChosen && selectedMode == BackendMode.DEMO &&
                        url.text.isNotBlank() && token.text.isNotBlank()) BackendMode.HOME_ASSISTANT else selectedMode
                    backend.save(HaConnectionSettings(effectiveMode, url.text.toString(),
                        weather.selectedItem.toString(), theme.selectedItem.toString(), opacity.selectedItem.toString(),
                        brightness.selectedItem.toString(), volume.selectedItem.toString(),
                        systemBrightness.selectedItem.toString(), blackout.selectedItem.toString(),
                        systemAutomatic.selectedItem.toString(), followSystem.selectedItem.toString(),
                        keepAwake.selectedItem.toString(), startAfterBoot.selectedItem.toString()), token.text.toString())
                    backend.relayIdentity.setDisplayName(relayName.text.toString())
                    token.text.clear(); diagnostic.text = context.getString(R.string.ha_saved)
                } catch (_: Exception) { diagnostic.text = context.getString(R.string.ha_save_failed) }
            }
        }
        dialog.setOnDismissListener {
            dismissed = true; phoneEntry.close(); call?.cancel(); backend.ha.removeStatusListener(listener); token.text.clear()
        }
        dialog.show()
        return dialog
    }
}
