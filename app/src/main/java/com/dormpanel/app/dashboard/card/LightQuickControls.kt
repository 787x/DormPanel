package com.dormpanel.app.dashboard.card

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.appearance.applyAppearanceTree
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
    private val status = TextView(context).apply { textSize = 16f }
    private val toggle = Button(context)
    private val brightnessLabel = TextView(context).apply { textSize = 18f }
    private val brightness = ClaimingSeekBar(context, interactions::claimGesture).apply { max = 100; contentDescription = "Light brightness" }
    private val temperatureLabel = TextView(context).apply { textSize = 18f }
    private val temperature = ClaimingSeekBar(context, interactions::claimGesture).apply { contentDescription = "Light color temperature" }
    private val dataListener: (DashboardData) -> Unit = { bind() }
    private val appearanceListener: (AppearanceState) -> Unit = {
        content.setBackgroundColor(PanelPalette.forMode(it.themeMode).background)
        applyAppearanceTree(content, it)
    }

    init {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        listOf(title, status, toggle, brightnessLabel, brightness, temperatureLabel, temperature).forEach { content.addView(it) }
        content.addView(Button(context).apply { text = "Done"; setOnClickListener { dismiss() } })
        setContentView(content)
        toggle.setOnClickListener { source.toggleLight(lightId) }
        brightness.onUserProgress { source.setBrightness(lightId, it) }
        temperature.onUserProgress { progress ->
            source.state.lights[lightId]?.capabilities?.colorTemperature?.let {
                source.setColorTemperature(lightId, it.first + progress)
            }
        }
        interactions.claimFromDown(brightness)
        interactions.claimFromDown(temperature)
        setOnDismissListener {
            source.removeListener(dataListener)
            appearance.removeListener(appearanceListener)
            onDismissed()
        }
    }

    override fun show() {
        super.show()
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        val width = minOf((520 * context.resources.displayMetrics.density).toInt(), context.resources.displayMetrics.widthPixels - 48)
        window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        source.addListener(dataListener)
        appearance.addListener(appearanceListener)
    }

    private fun bind() {
        val light = source.state.lights[lightId]
        title.text = light?.name ?: "Light unavailable"
        status.text = "Sample device · ${(light?.availability ?: Availability.UNAVAILABLE).label()}"
        val available = light?.availability == Availability.AVAILABLE
        toggle.text = if (light?.isOn == true) "Turn off" else "Turn on"
        toggle.isEnabled = available
        brightness.isEnabled = available
        temperature.isEnabled = available
        val hasBrightness = light?.capabilities?.brightness == true
        brightness.visibility = if (hasBrightness) View.VISIBLE else View.GONE
        brightnessLabel.visibility = brightness.visibility
        brightnessLabel.text = "Brightness · ${light?.brightness ?: 0}%"
        brightness.progress = light?.brightness ?: 0
        val range = light?.capabilities?.colorTemperature
        temperature.visibility = if (range != null) View.VISIBLE else View.GONE
        temperatureLabel.visibility = temperature.visibility
        if (range != null) {
            temperature.max = range.last - range.first
            temperature.progress = light.colorTemperature - range.first
            temperatureLabel.text = "Color temperature · ${light.colorTemperature} K"
        }
    }
}
