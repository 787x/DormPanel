package com.dormpanel.app.dashboard.card

import android.annotation.SuppressLint
import android.content.Context
import com.dormpanel.app.R
import com.dormpanel.app.appearance.*
import com.dormpanel.app.data.*
import com.dormpanel.app.ui.ClaimingSeekBar
import com.dormpanel.app.ui.onUserProgress

@SuppressLint("ViewConstructor")
class LightCardView(context: Context, appearance: AppearanceController, source: DashboardDataSource) : SourceCardView<LightState>(context, appearance, source) {
    private val name = label(DashboardTypography.SECONDARY, true)
    private val header = row()
    private val power = label(DashboardTypography.VALUE)
    private val summary = label(DashboardTypography.VALUE)
    private val brightnessLabel = label(DashboardTypography.SECONDARY)
    private val temperatureLabel = label(DashboardTypography.SECONDARY)
    private val brightness = ClaimingSeekBar(context) { interactions.claimGesture() }.apply {
        min = 1; max = 100; contentDescription = context.getString(R.string.inline_brightness)
    }
    private val temperature = ClaimingSeekBar(context) { interactions.claimGesture() }.apply {
        contentDescription = context.getString(R.string.inline_temperature)
    }
    private var dialog: LightQuickControls? = null
    init {
        addView(name)
        header.addView(power); header.addView(summary)
        addView(header)
        addView(brightnessLabel); addView(brightness)
        addView(temperatureLabel); addView(temperature)
        brightness.onUserProgress { source.setBrightness(lightId(), it) }
        temperature.onUserProgress { progress ->
            select(source.state)?.capabilities?.colorTemperature?.let { source.setColorTemperature(lightId(), it.first + progress) }
        }
    }
    private fun lightId() = entityId("lightId", "desk")
    override fun select(data: DashboardData) = data.lights[lightId()]
    override fun primaryAction() { select(source.state)?.let { source.setLightPower(lightId(), !it.isOn) } }
    override fun secondaryAction() {
        if (dialog == null) {
            dialog = LightQuickControls(context, source, appearance, lightId(), interactions) { dialog = null }
            dialog?.show()
        }
    }
    override fun onDetachedFromWindow() { dialog?.dismiss(); dialog = null; super.onDetachedFromWindow() }
    override fun render() {
        val light = select(source.state)
        val capabilities = light?.capabilities ?: LightCapabilities()
        val presentation = lightPresentation(card.size, capabilities)
        val availability = light?.availability ?: Availability.UNAVAILABLE
        name.text = light?.name ?: context.getString(R.string.card_light)
        power.text = if (availability != Availability.AVAILABLE) availability.label(context) else context.getString(if (light?.isOn == true) R.string.light_on else R.string.light_off)
        power.textSize = if (card.size.rowSpan == 1) 28f else DashboardTypography.VALUE
        header.orientation = if (presentation.horizontalHeader) HORIZONTAL else VERTICAL
        summary.setPadding(if (presentation.horizontalHeader) 20.dp else 0, 0, 0, 0)
        summary.textSize = if (presentation.inlineBrightness) DashboardTypography.SECONDARY else DashboardTypography.VALUE
        summary.text = if (capabilities.brightness) light?.controlBrightness?.let { context.getString(R.string.percent_value, it) } ?: context.getString(R.string.value_unknown) else ""
        summary.show(presentation.prominentSummary && capabilities.brightness && !presentation.inlineBrightness)
        // Only the control presentation is clamped; the backend state remains authoritative.
        val controlBrightness = light?.controlBrightness
        brightnessLabel.text = controlBrightness?.let { context.getString(R.string.brightness_value, it) } ?: context.getString(R.string.brightness_unknown)
        brightness.progress = controlBrightness ?: 1
        brightnessLabel.show(presentation.inlineBrightness); brightness.show(presentation.inlineBrightness)
        val range = capabilities.colorTemperature
        if (range != null) {
            temperature.max = range.last - range.first
            temperature.progress = (light?.controlTemperature ?: range.first) - range.first
            temperatureLabel.text = light?.controlTemperature?.let { context.getString(R.string.color_temperature_value, it) } ?: context.getString(R.string.temperature_unknown)
        }
        temperatureLabel.show(presentation.inlineTemperature); temperature.show(presentation.inlineTemperature)
        listOf(brightness, temperature).forEach {
            it.isEnabled = interactions.enabled && availability == Availability.AVAILABLE
            interactions.claimFromDown(it)
        }
        if (!interactions.enabled) { dialog?.dismiss(); dialog = null }
        describe(name.text, power.text, if (presentation.prominentSummary) summary.text else "")
    }
    override fun applyAppearance(state: AppearanceState) {
        super.applyAppearance(state)
        applyAppearanceTree(brightness, state); applyAppearanceTree(temperature, state)
    }
}
