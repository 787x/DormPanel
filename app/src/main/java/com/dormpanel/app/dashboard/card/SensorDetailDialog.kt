package com.dormpanel.app.dashboard.card

import android.app.Dialog
import android.content.Context
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.dormpanel.app.R
import com.dormpanel.app.appearance.*
import com.dormpanel.app.data.*
import com.dormpanel.app.ui.hidePanelSystemBars

/** Read-only live detail; its owner dismisses it on detach or entry into edit mode. */
class SensorDetailDialog(context: Context, private val appearance: AppearanceController,
    private val source: DashboardDataSource, private val sensorId: String, onDismissed: () -> Unit,
) : Dialog(context) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
    }
    private val title = TextView(context).apply { textSize = 28f }
    private val status = TextView(context).apply { textSize = 22f }
    private val values = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val listener: (DashboardData) -> Unit = { bind() }
    private val theme: (AppearanceState) -> Unit = {
        content.setBackgroundColor(PanelPalette.forMode(it.themeMode).background)
        applyAppearanceTree(content, it)
    }
    init {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        content.addView(title); content.addView(status); content.addView(values)
        content.addView(Button(context).apply { setText(R.string.dashboard_done); setOnClickListener { dismiss() } })
        setContentView(content)
        setOnDismissListener { source.removeListener(listener); appearance.removeListener(theme); onDismissed() }
    }
    override fun show() {
        super.show()
        window?.hidePanelSystemBars()
        val metrics = context.resources.displayMetrics
        window?.setLayout(minOf((960 * metrics.density).toInt(), metrics.widthPixels - (48 * metrics.density).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
        matchActivityBrightness()
        source.addListener(listener); appearance.addListener(theme)
    }
    private fun bind() {
        val sensor = source.state.sensors[sensorId]
        title.text = sensor?.name ?: context.getString(R.string.card_sensor)
        val availability = sensor?.availability ?: Availability.UNAVAILABLE
        status.text = availability.label(context)
        values.removeAllViews()
        fun measurement(value: String, label: Int) {
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            column.addView(TextView(context).apply {
                text = value; textSize = 64f; maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(24, 64, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (120 * context.resources.displayMetrics.density).toInt()))
            column.addView(TextView(context).apply { setText(label); textSize = 22f })
            values.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (availability != Availability.UNAVAILABLE) {
            sensor?.temperature?.let { measurement(context.getString(R.string.degrees_decimal, it, sensor.temperatureUnit), R.string.temperature) }
            sensor?.humidity?.let { measurement(context.getString(R.string.value_with_unit, it, sensor.humidityUnit), R.string.humidity) }
        }
        theme(appearance.state)
    }
}
