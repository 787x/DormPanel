package com.dormpanel.app.dashboard.card

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.data.DashboardDataSource
import com.dormpanel.app.weather.WeatherRepository
import java.time.Instant

@SuppressLint("ViewConstructor")
internal class ClassicWeatherCardView(
    context: Context,
    appearance: AppearanceController,
    source: DashboardDataSource,
    repository: WeatherRepository? = null,
    clock: () -> Instant = Instant::now,
) : BaseWeatherCardView(context, appearance, source, repository, clock, classic = true) {
    private val heading = classicLabel(DashboardTypography.MINOR, true).apply {
        minimumWidthTextSp = 14f
        ellipsize = TextUtils.TruncateAt.END
    }
    private val current = row()
    private val temperature = classicLabel(72f)
    private val condition = classicLabel(DashboardTypography.TITLE)
    private val summary = classicLabel(DashboardTypography.SECONDARY, true)
    private val forecast = row()
    private val cells = List(3) { ForecastCell() }
    private val metadata = row()
    private val status = classicLabel(15f, true)
    private val attribution = classicLabel(15f, true)

    init {
        current.addView(temperature, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        current.addView(condition)
        cells.forEach { forecast.addView(it.root, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)) }
        metadata.addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        metadata.addView(attribution, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = 8.dp })
        addView(heading)
        addView(current)
        addView(summary)
        addView(forecast, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 20.dp })
        addView(metadata)
    }

    override val forecastColumns get() = classicWeatherPresentation(card.size).forecastColumns

    override fun renderReadings(readings: WeatherCardReadings, metric: (WeatherCardMetric) -> String?) {
        val presentation = classicWeatherPresentation(card.size)
        heading.text = readings.displayName ?: "Weather"
        heading.show(presentation.summary)
        val orientation = if (presentation.horizontal) HORIZONTAL else VERTICAL
        if (current.orientation != orientation) current.orientation = orientation
        current.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        condition.setPaddingRelative(if (presentation.horizontal) 16.dp else 0, 0, 0, 0)
        temperature.text = readings.temperature
        condition.text = readings.condition
        summary.text = listOfNotNull(
            readings.range,
            metric(WeatherCardMetric.HUMIDITY),
            readings.preferredMetric?.takeUnless { it == WeatherCardMetric.HUMIDITY }?.let(metric),
        ).joinToString("  ·  ")
        summary.show(presentation.summary && summary.text.isNotEmpty())
        forecast.show(readings.forecast.isNotEmpty())
        cells.forEachIndexed { index, cell -> cell.bind(readings.forecast.getOrNull(index)) }
        bindAttribution(attribution, readings)
        status.text = readings.status.takeUnless { it == "最新" }.orEmpty()
        metadata.show(status.text.isNotEmpty() || attribution.visibility == VISIBLE)
        describe(heading.text, readings.title.takeUnless { it == heading.text }.orEmpty(), temperature.text, condition.text,
            if (summary.visibility == VISIBLE) summary.text else "",
            status.text, forecastDescription(readings.forecast))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (isBound) {
            val presentation = classicWeatherPresentation(card.size)
            val conditionSp = if (presentation.summary) DashboardTypography.TITLE else DashboardTypography.SECONDARY
            val currentSp = if (presentation.horizontal) presentation.temperatureSp else presentation.temperatureSp + conditionSp
            val visibleSp = currentSp +
                DashboardTypography.MINOR.takeIf { heading.visibility == VISIBLE }.orZero() +
                DashboardTypography.SECONDARY.takeIf { summary.visibility == VISIBLE }.orZero() +
                (2 * DashboardTypography.SECONDARY + DashboardTypography.TITLE).takeIf { forecast.visibility == VISIBLE }.orZero() +
                15f.takeIf { metadata.visibility == VISIBLE }.orZero()
            val width = MeasureSpec.getSize(widthMeasureSpec) - paddingStart - paddingEnd
            val available = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom -
                if (forecast.visibility == VISIBLE) 20.dp else 0
            // Preserve historical sizes when space permits, with room for the font's line metrics.
            val desired = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, visibleSp * 1.25f, resources.displayMetrics)
            val scale = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) 1f
                else (available / desired).coerceIn(0.1f, 1f)
            val temperatureWidth = if (presentation.horizontal) (width * .58f).toInt().coerceAtLeast(1) else Int.MAX_VALUE
            if (temperature.maxWidth != temperatureWidth) temperature.maxWidth = temperatureWidth
            temperature.fit(presentation.temperatureSp * scale)
            condition.fit(conditionSp * scale)
            heading.fit(DashboardTypography.MINOR * scale)
            summary.fit(DashboardTypography.SECONDARY * scale)
            status.fit(15f * scale)
            attribution.fit(15f * scale)
            cells.forEach { it.fit(scale) }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun Float?.orZero() = this ?: 0f

    private fun classicLabel(sp: Float, secondary: Boolean = false) =
        fittingLabel(sp, secondary).apply { verifyFittedWidth = true }

    private fun FittingValueView.fit(sp: Float) {
        if (maximumTextSp != sp) maximumTextSp = sp
    }

    private inner class ForecastCell {
        val root = column()
        private val day = classicLabel(DashboardTypography.SECONDARY, true)
        private val range = classicLabel(DashboardTypography.TITLE)
        private val sky = classicLabel(DashboardTypography.SECONDARY, true)

        init {
            root.addView(day)
            root.addView(range)
            root.addView(sky)
        }

        fun bind(line: WeatherLine?) {
            root.show(line != null)
            day.text = line?.label.orEmpty()
            range.text = line?.temperature ?: "—"
            sky.text = line?.sky?.description.orEmpty()
        }

        fun fit(scale: Float) {
            day.fit(DashboardTypography.SECONDARY * scale)
            range.fit(DashboardTypography.TITLE * scale)
            sky.fit(DashboardTypography.SECONDARY * scale)
        }
    }
}
