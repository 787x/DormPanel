package com.dormpanel.app.dashboard.card

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import com.dormpanel.app.R
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.data.*

@SuppressLint("ViewConstructor")
class SensorCardView(context: Context, appearance: AppearanceController, source: DashboardDataSource) : SourceCardView<SensorState>(context, appearance, source) {
    private val name = label(DashboardTypography.SECONDARY, true)
    private val values = row()
    private val temperatureGroup = column()
    private val humidityGroup = column()
    private val temperature = fittingLabel(DashboardTypography.VALUE)
    private val humidity = fittingLabel(DashboardTypography.VALUE)
    private val temperatureLabel = label(DashboardTypography.MINOR, true).apply { setText(R.string.temperature) }
    private val humidityLabel = label(DashboardTypography.MINOR, true).apply { setText(R.string.humidity) }
    private val status = label(DashboardTypography.SECONDARY, true)
    private var dialog: SensorDetailDialog? = null
    init {
        addView(name)
        temperatureGroup.addView(temperature); temperatureGroup.addView(temperatureLabel)
        humidityGroup.addView(humidity); humidityGroup.addView(humidityLabel)
        values.addView(temperatureGroup); values.addView(humidityGroup)
        addView(values, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp })
    }
    override fun select(data: DashboardData) = data.sensors[entityId("sensorId", "room")]
    override fun primaryAction() {
        if (dialog == null) {
            dialog = SensorDetailDialog(context, appearance, source, entityId("sensorId", "room")) { dialog = null }
            dialog?.show()
        }
    }
    override fun onDetachedFromWindow() { dialog?.dismiss(); super.onDetachedFromWindow() }
    override fun render() {
        if (!interactions.enabled) dialog?.dismiss()
        val model = select(source.state)
        val availability = model?.availability ?: Availability.UNAVAILABLE
        val presentation = sensorPresentation(card.size)
        name.text = model?.name ?: context.getString(R.string.card_sensor)
        name.maxLines = if (card.size.rowSpan == 1) 1 else 2
        name.ellipsize = TextUtils.TruncateAt.END
        val unknown = context.getString(R.string.value_unknown)
        temperature.text = if (availability != Availability.UNAVAILABLE && model?.temperature != null) context.getString(R.string.degrees_decimal, model.temperature, model.temperatureUnit) else unknown
        humidity.text = if (availability != Availability.UNAVAILABLE && model?.humidity != null) context.getString(R.string.value_with_unit, model.humidity, model.humidityUnit) else unknown
        temperature.maximumTextSp = presentation.valueSp
        humidity.maximumTextSp = presentation.valueSp
        values.orientation = if (presentation.sideBySide) HORIZONTAL else VERTICAL
        // Narrow medium cards stack paired value/label rows; wide cards use two dominant columns.
        listOf(temperatureGroup, humidityGroup).forEach { group ->
            group.orientation = if (presentation.sideBySide) VERTICAL else HORIZONTAL
            group.layoutParams = if (presentation.sideBySide) LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) else LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        temperatureGroup.setPadding(0, 0, if (presentation.sideBySide) 12.dp else 0, 0)
        // These children were created in columns. Do not retain MATCH_PARENT widths after
        // switching to the narrow card's horizontal value/label rows.
        listOf(temperature, humidity, temperatureLabel, humidityLabel).forEach {
            it.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        if (presentation.sideBySide) listOf(temperature, humidity).forEach {
            it.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        temperatureLabel.setPadding(if (presentation.sideBySide) 0 else 12.dp, 0, 0, 0)
        humidityLabel.setPadding(if (presentation.sideBySide) 0 else 12.dp, 0, 0, 0)
        temperatureLabel.show(presentation.valueLabels); humidityLabel.show(presentation.valueLabels)
        status.text = availability.label(context)
        status.show(presentation.status || availability != Availability.AVAILABLE)
        // Compact unavailable/stale states stay visible without squeezing out the values.
        if (card.size.rowSpan == 1) {
            status.show(false)
            // Put availability first so a long ellipsized name cannot hide a stale warning.
            if (availability != Availability.AVAILABLE) name.text = context.getString(R.string.name_with_status, availability.label(context), name.text)
        }
        describe(name.text, temperature.text, humidity.text, availability.label(context))
    }
}
