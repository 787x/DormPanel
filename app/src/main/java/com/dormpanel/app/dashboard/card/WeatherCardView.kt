package com.dormpanel.app.dashboard.card

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.data.DashboardDataSource
import com.dormpanel.app.weather.WeatherRepository
import java.time.Instant
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
internal class WeatherCardView(
    context: Context,
    appearance: AppearanceController,
    source: DashboardDataSource,
    repository: WeatherRepository? = null,
    clock: () -> Instant = Instant::now,
    private val compactForecast: Boolean = false,
) : BaseWeatherCardView(context, appearance, source, repository, clock) {
    private val heading = text(19f).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minimumWidthTextSp = 17f
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val attribution = text(15f, secondary = true)
    private val header = row()
    private val current = row()
    private val reading = row()
    private val icon = WeatherIconView(context)
    private val temperature = text(64f)
    private val condition = text(30f)
    private val status = text(15f, secondary = true).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
    private val summary = row()
    private val todayRange = text(30f)
    private val metrics = row()
    private val metricLabels = List(3) { text(25f, secondary = true) }
    private val metricPaint = Paint()
    private val forecast = row()
    private val forecastCells = List(3) { ForecastCell() }
    private var measuredPolicy: LayoutKey? = null
    private var showSecondaryDates = false

    init {
        header.addView(heading, weighted(horizontal = true, 1f))
        header.addView(attribution, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply { marginStart = 8.dp })
        current.addView(icon, LayoutParams(42.dp, LayoutParams.MATCH_PARENT).apply { marginEnd = 8.dp })
        current.addView(reading, weighted(horizontal = true, 1f))
        reading.addView(temperature)
        reading.addView(condition)
        summary.addView(todayRange)
        summary.addView(metrics)
        metricLabels.forEach { metrics.addView(it, weighted(horizontal = true, 1f).apply { marginStart = 6.dp }) }
        forecastCells.forEach { forecast.addView(it.root, weighted(horizontal = true, 1f)) }
        addView(header)
        addView(current)
        addView(summary)
        addView(forecast)
        addView(status)
    }
    override val forecastColumns get() = weatherPresentation(card.size, compactForecast).forecastColumns

    override fun renderReadings(readings: WeatherCardReadings, metric: (WeatherCardMetric) -> String?) {
        heading.text = readings.title
        bindAttribution(attribution, readings)
        temperature.text = readings.temperature
        condition.text = readings.condition
        icon.symbol = readings.symbol
        status.text = readings.status
        todayRange.setReading(readings.range)

        val preferred = readings.preferredMetric ?: WeatherCardMetric.FEELS_LIKE
        val details = (listOf(preferred, WeatherCardMetric.HUMIDITY, WeatherCardMetric.FEELS_LIKE) + WeatherCardMetric.entries)
            .distinct()
            .mapNotNull(metric)
            .take(if (preferred == WeatherCardMetric.WIND) 3 else 2)
        metricLabels.forEachIndexed { index, view ->
            val value = details.getOrNull(index).orEmpty()
            if (view.text != value) {
                view.text = value
                requestLayout()
            }
        }

        val lines = readings.forecast
        forecast.show(lines.isNotEmpty())
        forecastCells.forEachIndexed { index, cell -> cell.bind(lines.getOrNull(index)) }
        describe(heading.text, temperature.text, condition.text, todayRange.text,
            metricLabels.joinToString(" ") { it.text }, status.text, forecastDescription(lines))
    }

    override fun applyAppearance(state: AppearanceState) {
        super.applyAppearance(state)
        val accent = PanelPalette.forMode(state.themeMode).accent
        icon.tint(accent)
        forecastCells.forEach { it.icon.tint(accent) }
    }

    private data class LayoutKey(
        val width: Int, val height: Int, val fontScale: Float, val density: Float,
        val columns: Int, val rows: Int, val hasRange: Boolean, val metrics: Int, val forecasts: Int,
    )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (isBound) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val metricCount = visibleMetricCount(width)
            metricLabels.forEachIndexed { index, label -> label.show(index < metricCount) }
            metrics.show(metricCount > 0)
            summary.show(todayRange.visibility == VISIBLE || metricCount > 0)
            val key = LayoutKey(width, MeasureSpec.getSize(heightMeasureSpec),
                resources.configuration.fontScale, resources.displayMetrics.density, card.size.columnSpan, card.size.rowSpan,
                todayRange.visibility == VISIBLE, metricCount,
                forecastCells.count { it.root.visibility == VISIBLE })
            if (measuredPolicy != key) {
                measuredPolicy = key
                applyLayout(key)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun visibleMetricCount(width: Int): Int {
        val count = metricLabels.count { it.text.isNotEmpty() }
        if (count == 0) return 0
        val hasRange = todayRange.visibility == VISIBLE
        if (card.size.rowSpan == 1) return if (card.size.columnSpan >= 3 && !hasRange) 1 else 0

        val rangeShares = if (hasRange && !weatherPresentation(card.size).stackedCurrent) 1 else 0
        val available = width - 28.dp
        val minimumPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
        fun fits(label: TextView, slot: Int): Boolean {
            metricPaint.set(label.paint)
            metricPaint.textSize = minimumPx
            val margin = if (label === todayRange) 0 else 6.dp
            return metricPaint.measureText(label.text.toString()) + margin + 3 <= slot
        }
        for (visible in count downTo 2) {
            val slot = available / (visible + rangeShares)
            if ((rangeShares == 0 || fits(todayRange, slot)) &&
                (0 until visible).all { fits(metricLabels[it], slot) }) return visible
        }
        return 1
    }

    private fun applyLayout(key: LayoutKey) {
        val width = key.width
        val height = key.height
        val tall = card.size.rowSpan == 3
        val compact = card.size.rowSpan == 1
        val stacked = weatherPresentation(card.size).stackedCurrent
        val narrowForecast = compactForecast && key.forecasts == 3
        val inset = if (compact) 6.dp else 10.dp
        setPadding(14.dp, inset, 14.dp, inset)
        val available = (height - inset * 2).coerceAtLeast(0)
        // Reserve header and status first. Give the remaining height only to visible sections.
        val headingHeight = minOf(available / 5, if (compact) 23.dp else 28.dp)
        val statusHeight = minOf(available / 6, if (compact) 19.dp else 22.dp)
        header.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, headingHeight)
        status.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, statusHeight)
        current.layoutParams = weighted(horizontal = false, if (stacked) 2.7f else if (compact) 2.2f else 2.4f)
        summary.layoutParams = weighted(horizontal = false, if (stacked && key.metrics > 0 && key.hasRange) 1.8f else 1f)
        forecast.layoutParams = weighted(horizontal = false, if (tall) 4.2f else 1.05f)
        reading.orientation = if (stacked) VERTICAL else HORIZONTAL
        temperature.layoutParams = weighted(!stacked, if (stacked) 1.55f else 1.15f)
        condition.layoutParams = weighted(!stacked, 1f).apply { if (!stacked) marginStart = 8.dp }
        temperature.maximumTextSp = when { compact -> 39f; tall -> 76f; else -> 67f }
        condition.maximumTextSp = when { compact -> 23f; tall -> 40f; else -> 34f }
        icon.layoutParams = LayoutParams(minOf(width / 4, when { compact -> 34.dp; tall -> 86.dp; else -> 62.dp }),
            LayoutParams.MATCH_PARENT).apply { marginEnd = 8.dp }

        summary.orientation = if (stacked) VERTICAL else HORIZONTAL
        todayRange.layoutParams = weighted(!stacked, 1f)
        metrics.layoutParams = weighted(!stacked, if (stacked) 1f else key.metrics.coerceAtLeast(1).toFloat())
        todayRange.maximumTextSp = when { compact -> 22f; tall -> 36f; else -> 30f }
        metricLabels.forEach { it.maximumTextSp = if (tall) 28f else 24f }

        val count = key.forecasts.coerceAtLeast(1)
        val cellWidth = (width - paddingLeft - paddingRight) / count
        showSecondaryDates = tall && !narrowForecast && available - headingHeight - statusHeight >=
            (215 * key.density * key.fontScale).roundToInt()
        forecastCells.forEachIndexed { index, cell ->
            if (cell.root.visibility == VISIBLE) cell.measurePolicy(tall, cellWidth, index != 0, narrowForecast)
        }
    }

    private inner class ForecastCell {
        val root = row()
        private val day = text(24f)
        private val date = text(16f, secondary = true)
        val icon = WeatherIconView(context)
        private val range = text(27f)
        private val sky = text(21f)
        init { listOf(day, date, icon, range, sky).forEach { root.addView(it) } }

        fun bind(line: WeatherLine?) {
            root.show(line != null)
            day.text = line?.label.orEmpty()
            date.text = line?.secondaryDate.orEmpty()
            date.show(showSecondaryDates && date.text.isNotEmpty())
            icon.symbol = line?.sky?.symbol ?: WeatherSymbol.UNKNOWN
            range.setReading(line?.temperature)
            sky.text = line?.sky?.description.orEmpty()
        }

        fun measurePolicy(tall: Boolean, width: Int, separated: Boolean, narrow: Boolean) {
            root.orientation = if (tall) VERTICAL else HORIZONTAL
            root.gravity = Gravity.CENTER
            if (narrow) root.setPadding(2.dp, 3.dp, 2.dp, 0)
            else root.setPadding(if (separated) 5.dp else 0, 3.dp, 0, 0)
            date.show(showSecondaryDates && date.text.isNotEmpty())
            day.maximumTextSp = if (narrow) 20f else 24f
            range.maximumTextSp = if (narrow) 22f else 27f
            sky.maximumTextSp = if (narrow) 18f else 21f
            listOf(day, range, sky).forEach { it.verifyFittedWidth = narrow }
            day.layoutParams = weighted(!tall, if (tall) 1.18f else 1.1f)
            date.layoutParams = weighted(horizontal = false, .65f)
            icon.layoutParams = if (tall) LayoutParams(minOf(width / 2, if (narrow) 40.dp else 56.dp), 0, 1.6f)
                else LayoutParams(minOf(width / 8, 30.dp), LayoutParams.MATCH_PARENT)
            range.layoutParams = weighted(!tall, if (tall) 1.12f else 1.35f)
            sky.layoutParams = weighted(!tall, 1f)
        }
    }

    private fun text(sp: Float, secondary: Boolean = false) = fittingLabel(sp, secondary).apply { gravity = Gravity.CENTER }
    private fun TextView.setReading(value: String?) { text = value.orEmpty(); show(value != null) }
    private fun weighted(horizontal: Boolean, weight: Float) = if (horizontal) LayoutParams(0, LayoutParams.MATCH_PARENT, weight)
        else LayoutParams(LayoutParams.MATCH_PARENT, 0, weight)
}
