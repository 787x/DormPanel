package com.dormpanel.app.dashboard.card

import com.dormpanel.app.R
import android.app.Dialog
import android.app.AlertDialog
import android.widget.EditText
import android.text.InputType
import com.dormpanel.app.data.parseLightControlNumber
import androidx.core.widget.doAfterTextChanged
import com.dormpanel.app.ui.hidePanelSystemBars
import android.content.Context
import androidx.core.graphics.drawable.toDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.appearance.applyAppearanceTree
import com.dormpanel.app.appearance.matchActivityBrightness
import com.dormpanel.app.data.Availability
import com.dormpanel.app.data.DashboardData
import com.dormpanel.app.data.DashboardDataSource
import com.dormpanel.app.ui.ClaimingSeekBar
import com.dormpanel.app.ui.onUserProgress

/** A separate dialog window owns its touches; sliders additionally claim at ACTION_DOWN. */
class LightQuickControls(
    context: Context,
    private val source: DashboardDataSource,
    private val appearance: AppearanceController,
    private val lightId: String,
    interactions: CardInteractionScope,
    onDismissed: () -> Unit,
) : Dialog(context) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
    }
    private val title = TextView(context).apply { textSize = 24f }
    private val status = TextView(context).apply { textSize = DashboardTypography.SECONDARY }
    private val toggle = Button(context)
    private val brightnessLabel = TextView(context).apply { textSize = DashboardTypography.SECONDARY }
    private val brightness = ClaimingSeekBar(context, interactions::claimGesture).apply { min = 1; max = 100; contentDescription = context.getString(R.string.light_brightness) }
    private val temperatureLabel = TextView(context).apply { textSize = DashboardTypography.SECONDARY }
    private val temperature = ClaimingSeekBar(context, interactions::claimGesture).apply { contentDescription = context.getString(R.string.light_temperature) }
    private var numericDialog: AlertDialog? = null
    private val dataListener: (DashboardData) -> Unit = { bind() }
    private val appearanceListener: (AppearanceState) -> Unit = {
        content.setBackgroundColor(PanelPalette.forMode(it.themeMode).background)
        applyAppearanceTree(content, it)
    }

    init {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        listOf(title, status, toggle, brightnessLabel, brightness, temperatureLabel, temperature).forEach { content.addView(it) }
        content.addView(Button(context).apply { setText(R.string.dashboard_done); setOnClickListener { dismiss() } })
        setContentView(content)
        toggle.setOnClickListener { source.state.lights[lightId]?.let { source.setLightPower(lightId, !it.isOn) } }
        val touchHeight = (48 * context.resources.displayMetrics.density).toInt()
        listOf(brightnessLabel, temperatureLabel, brightness, temperature).forEach { it.minimumHeight = touchHeight }
        brightnessLabel.setOnClickListener {
            val light = source.state.lights[lightId] ?: return@setOnClickListener
            numeric(R.string.light_brightness, light.controlBrightness, 1..100) { source.setBrightness(lightId, it) }
        }
        temperatureLabel.setOnClickListener {
            val light = source.state.lights[lightId] ?: return@setOnClickListener
            light.capabilities.colorTemperature?.let { range ->
                numeric(R.string.light_temperature, light.controlTemperature, range) { source.setColorTemperature(lightId, it) }
            }
        }
        brightness.onUserProgress { source.setBrightness(lightId, it) }
        temperature.onUserProgress { progress ->
            source.state.lights[lightId]?.capabilities?.colorTemperature?.let {
                source.setColorTemperature(lightId, it.first + progress)
            }
        }
        interactions.claimFromDown(brightness)
        interactions.claimFromDown(temperature)
        setOnDismissListener {
            numericDialog?.dismiss(); numericDialog = null
            source.removeListener(dataListener)
            appearance.removeListener(appearanceListener)
            onDismissed()
        }
    }

    override fun show() {
        super.show()
        window?.hidePanelSystemBars()
        window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
        val width = minOf((960 * context.resources.displayMetrics.density).toInt(), context.resources.displayMetrics.widthPixels - (48 * context.resources.displayMetrics.density).toInt())
        window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        matchActivityBrightness()
        source.addListener(dataListener)
        appearance.addListener(appearanceListener)
    }

    private fun numeric(title: Int, current: Int?, range: IntRange, submit: (Int) -> Unit) {
        if (numericDialog != null) return
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText(current?.toString().orEmpty())
            hint = "${range.first}–${range.last}"
            selectAll()
        }
        // An EditText error popup overlaps the dialog buttons on X08E. Keep validation
        // inline, with reserved layout space, and clear it when the user edits again.
        val validation = TextView(context).apply { textSize = 18f; visibility = View.GONE }
        val fields = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(input); addView(validation)
        }
        input.doAfterTextChanged { validation.visibility = View.GONE }
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(fields)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        numericDialog = dialog
        dialog.setOnDismissListener { numericDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = parseLightControlNumber(input.text.toString(), range)
                if (value == null) {
                    validation.text = context.getString(R.string.light_number_error, range.first, range.last)
                    validation.visibility = View.VISIBLE
                }
                else { submit(value); dialog.dismiss() }
            }
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        dialog.matchActivityBrightness()
        input.requestFocus()
    }

    private fun bind() {
        val light = source.state.lights[lightId]
        title.text = light?.name ?: context.getString(R.string.light_unavailable)
        status.text = if (light?.isOn == false && light.capabilities.colorTemperature != null)
            context.getString(R.string.light_pending_temperature, light.availability.label(context))
        else (light?.availability ?: Availability.UNAVAILABLE).label(context)
        val available = light?.availability == Availability.AVAILABLE
        toggle.text = context.getString(if (light?.isOn == true) R.string.light_turn_off else R.string.light_turn_on)
        toggle.isEnabled = available
        brightness.isEnabled = available
        temperature.isEnabled = available
        brightnessLabel.isEnabled = available
        temperatureLabel.isEnabled = available
        val hasBrightness = light?.capabilities?.brightness == true
        brightness.visibility = if (hasBrightness) View.VISIBLE else View.GONE
        brightnessLabel.visibility = brightness.visibility
        // Only the control presentation is clamped; the backend state remains authoritative.
        val controlBrightness = light?.controlBrightness
        brightnessLabel.text = controlBrightness?.let { context.getString(R.string.brightness_value, it) } ?: context.getString(R.string.brightness_unknown)
        brightness.progress = controlBrightness ?: 1
        val range = light?.capabilities?.colorTemperature
        temperature.visibility = if (range != null) View.VISIBLE else View.GONE
        temperatureLabel.visibility = temperature.visibility
        if (range != null) {
            temperature.max = range.last - range.first
            temperature.progress = (light.controlTemperature ?: range.first) - range.first
            temperatureLabel.text = light.controlTemperature?.let { context.getString(R.string.color_temperature_value, it) } ?: context.getString(R.string.temperature_unknown)
        }
    }
}
