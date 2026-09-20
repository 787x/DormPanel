package com.dormpanel.app.dashboard.card

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import com.dormpanel.app.R
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("ViewConstructor")
class WeatherCardView(context: Context, appearance: AppearanceController, source: DashboardDataSource) : SourceCardView<WeatherState>(context, appearance, source) {
    private val heading = label(DashboardTypography.MINOR, true)
    private val current = row()
    private val temperature = label(72f)
    private val condition = label(DashboardTypography.TITLE)
    private val summary = label(DashboardTypography.SECONDARY, true)
    private val forecast = row()
    // Fixed, reusable forecast cells; state updates do not reconstruct the card hierarchy.
    private val forecastLabels = List(3) {
        Triple(label(DashboardTypography.SECONDARY, true), label(DashboardTypography.TITLE), label(DashboardTypography.SECONDARY, true))
    }
    init {
        addView(heading)
        current.addView(temperature)
        current.addView(condition)
        addView(current)
        addView(summary)
        forecastLabels.forEach { (day, range, sky) ->
            forecast.addView(column().apply { addView(day); addView(range); addView(sky) }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(forecast, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 20.dp })
    }
    override fun select(data: DashboardData) = data.weather
    override fun render() {
        val model = source.state.weather
        val presentation = weatherPresentation(card.size)
        val available = model.availability != Availability.UNAVAILABLE
        heading.setText(R.string.weather_sample)
        heading.show(presentation.summary)
        current.orientation = if (presentation.horizontal) HORIZONTAL else VERTICAL
        current.gravity = Gravity.CENTER_VERTICAL
        temperature.textSize = presentation.temperatureSp
        temperature.text = if (available) context.getString(R.string.degrees_int, model.temperature) else context.getString(R.string.value_unknown)
        condition.text = if (model.availability == Availability.AVAILABLE) model.condition else model.availability.label(context)
        condition.textSize = if (card.size.rowSpan == 1) DashboardTypography.SECONDARY else DashboardTypography.TITLE
        condition.setPadding(if (presentation.horizontal) 16.dp else 0, 0, 0, 0)
        summary.text = context.getString(R.string.weather_summary, model.low, model.high, model.humidity)
        summary.show(presentation.summary && available)
        forecast.show(presentation.forecastColumns > 0 && available)
        forecastLabels.forEachIndexed { index, (day, range, sky) ->
            val item = model.forecast.getOrNull(index)
            forecast.getChildAt(index).show(index < presentation.forecastColumns && item != null)
            if (item != null) {
                day.text = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(item.timeEpochMillis))
                range.text = context.getString(R.string.temperature_range, item.low, item.high)
                sky.text = item.condition
            }
        }
        describe(context.getString(R.string.card_weather), temperature.text, condition.text, if (presentation.summary && available) summary.text else "")
    }
}
