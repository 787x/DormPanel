package com.dormpanel.app.dashboard.card

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.dashboard.model.PlacedCard
import com.dormpanel.app.data.Availability
import com.dormpanel.app.data.DashboardDataSource
import com.dormpanel.app.weather.WeatherBlock
import com.dormpanel.app.weather.WeatherRepository
import com.dormpanel.app.weather.WeatherSourceRef
import com.dormpanel.app.weather.WeatherState
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class WeatherCardView internal constructor(
    context: Context,
    appearance: AppearanceController,
    source: DashboardDataSource,
    repository: WeatherRepository? = null,
    private val clock: () -> Instant = Instant::now,
) : DashboardCardView(context, appearance) {
    private val feed = WeatherCardFeed(source, repository, ::refresh)
    private var configuration: WeatherCardConfiguration = WeatherCardConfiguration.Default
    private var requested = false
    private val midnight = Runnable { refresh() }
    private val clockChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refresh() }
    }
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
    override fun onBound(previous: PlacedCard?) {
        val configurationChanged = previous?.configurationJson != card.configurationJson
        if (configurationChanged) configuration = WeatherCardConfiguration.decode(card.configurationJson)
        if (isAttachedToWindow && (configurationChanged || interactions.enabled || !requested)) bindFeed()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ContextCompat.registerReceiver(context, clockChanges, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (isBound) { bindFeed(); refresh() }
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(clockChanges)
        removeCallbacks(midnight)
        feed.close()
        requested = false
        super.onDetachedFromWindow()
    }

    private fun bindFeed() {
        requested = true
        feed.bind(configuration, setOf(WeatherBlock.CURRENT), compactForecast = card.size.rowSpan >= 2)
    }

    override fun primaryAction() = open(WeatherCardNavigation.Destination.WEATHER_CENTER)
    override fun secondaryAction() = open(WeatherCardNavigation.Destination.QUICK_DETAIL)

    private fun open(destination: WeatherCardNavigation.Destination) {
        val binding = configuration as? WeatherCardConfiguration.Bound
        interactions.openWeather(WeatherCardNavigation(card.id, binding?.placeId, binding?.source, destination))
    }

    override fun render() {
        val presentation = weatherPresentation(card.size)
        val now = clock()
        val legacy = feed.legacy
        val validLegacy = legacy?.takeIf { it.availability != Availability.UNAVAILABLE }
        val snapshot = feed[WeatherBlock.CURRENT]
        val conditions = snapshot.conditions() ?: validLegacy?.conditions
        val prediction = feed.forecast
        val zone = prediction?.zone ?: snapshot?.zone ?: feed.place?.zone ?: ZoneId.systemDefault()
        val today = prediction.today(now)?.conditions
        val binding = configuration as? WeatherCardConfiguration.Bound
        val invalid = configuration == WeatherCardConfiguration.Invalid || (binding != null && feed.invalid)
        val weather = sky(conditions?.code ?: validLegacy?.condition, conditions?.description)
        heading.text = binding?.displayName ?: feed.place?.name ?: legacy?.name ?: "天气"
        val qweather = binding != null && binding.source !is WeatherSourceRef.HomeAssistant
        attribution.text = when {
            qweather -> "和风天气"
            binding != null -> "HA"
            else -> ""
        }
        attribution.show(attribution.text.isNotEmpty())
        val link = qweather && interactions.enabled
        attribution.setOnClickListener(if (link) OnClickListener { attribution.openWeatherLink(QWEATHER_WEBSITE) } else null)
        attribution.setOnLongClickListener(if (link) OnLongClickListener { performLongClick() } else null)
        attribution.isLongClickable = link
        attribution.isClickable = link
        attribution.isFocusable = link
        attribution.paintFlags = if (qweather) attribution.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            else attribution.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        attribution.contentDescription = if (qweather) "和风天气官网" else "Home Assistant"
        temperature.text = conditions?.cardTemperature()
            ?: validLegacy?.temperature.value(validLegacy?.temperatureUnit.orEmpty()) ?: "—"
        condition.text = if (invalid) "请重新选择" else weather.description
        icon.symbol = weather.symbol
        status.text = when {
            invalid -> "地点或来源已失效"
            legacy != null -> legacy.status()
            else -> weatherStatus(snapshot)
        }
        val unit = validLegacy?.temperatureUnit ?: conditions?.sourceTemperatureUnit ?: "°C"
        val localDate = now.atZone(zone).toLocalDate()
        val legacyToday = validLegacy?.takeIf { it.forecastType == "daily" }?.forecast?.firstOrNull {
            Instant.ofEpochMilli(it.timeEpochMillis).atZone(zone).toLocalDate() == localDate
        }
        val providedRange = validLegacy?.takeIf { it.entityId == null || it.forecast.isEmpty() }
        todayRange.setReading(when {
            today != null -> today.cardRange()
            legacyToday != null -> cardRange(legacyToday.low, legacyToday.high, unit)
            else -> cardRange(providedRange?.low, providedRange?.high, unit) ?: conditions?.cardRange()
        })

        val preferred = binding?.metric ?: WeatherCardMetric.FEELS_LIKE
        val details = (listOf(preferred, WeatherCardMetric.HUMIDITY, WeatherCardMetric.FEELS_LIKE) + WeatherCardMetric.entries)
            .distinct()
            .mapNotNull { choice ->
                when (choice) {
                    WeatherCardMetric.HUMIDITY -> conditions?.metric(choice)
                        ?: validLegacy?.humidity.value("%")?.let { "湿度 $it" }
                    WeatherCardMetric.WIND -> conditions?.takeIf { it.windSpeed != null }?.metric(choice)
                    else -> conditions?.metric(choice, unit)
                }
            }.take(if (preferred == WeatherCardMetric.WIND) 3 else 2)
        metricLabels.forEachIndexed { index, view ->
            val value = details.getOrNull(index).orEmpty()
            if (view.text != value) {
                view.text = value
                requestLayout()
            }
        }

        val slots = presentation.forecastColumns
        val lines = when {
            slots == 0 -> emptyList()
            validLegacy != null -> validLegacy.lines(now, slots, forCard = true, zone = zone, includeToday = false)
            else -> prediction.forecastLines(now, slots, includeToday = prediction?.query?.block != WeatherBlock.DAILY, forCard = true)
        }
        forecast.show(lines.isNotEmpty())
        if (presentation.summary && !invalid && binding?.source is WeatherSourceRef.HomeAssistant &&
            prediction == null && snapshot?.haForecastFeatures == 0) {
            status.text = "${status.text}  ·  不支持预报"
        } else if (presentation.summary && prediction != null && prediction.state != WeatherState.FRESH &&
            (prediction.state != snapshot?.state || snapshot.data != null)) {
            status.text = "${status.text}  ·  预报${weatherStatus(prediction)}"
        } else if (presentation.summary && prediction?.state == WeatherState.FRESH && lines.isEmpty()) {
            status.text = "${status.text}  ·  暂无后续预报"
        }
        forecastCells.forEachIndexed { index, cell -> cell.bind(lines.getOrNull(index)) }
        describe(heading.text, temperature.text, condition.text, todayRange.text,
            metricLabels.joinToString(" ") { it.text }, status.text,
            lines.joinToString("；") { listOfNotNull(it.label, it.temperature, it.sky.description).joinToString(" ") })

        removeCallbacks(midnight)
        if (isAttachedToWindow && (prediction.entries().isNotEmpty() || !validLegacy?.forecast.isNullOrEmpty())) {
            postDelayed(midnight, (nextWeatherMidnight(now, zone).toEpochMilli() - now.toEpochMilli()).coerceAtLeast(1))
        }
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
        showSecondaryDates = tall && available - headingHeight - statusHeight >=
            (215 * key.density * key.fontScale).roundToInt()
        forecastCells.forEachIndexed { index, cell ->
            if (cell.root.visibility == VISIBLE) cell.measurePolicy(tall, cellWidth, index != 0)
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

        fun measurePolicy(tall: Boolean, width: Int, separated: Boolean) {
            root.orientation = if (tall) VERTICAL else HORIZONTAL
            root.gravity = Gravity.CENTER
            root.setPadding(if (separated) 5.dp else 0, 3.dp, 0, 0)
            date.show(showSecondaryDates && date.text.isNotEmpty())
            day.layoutParams = weighted(!tall, if (tall) 1.18f else 1.1f)
            date.layoutParams = weighted(horizontal = false, .65f)
            icon.layoutParams = if (tall) LayoutParams(minOf(width / 2, 56.dp), 0, 1.6f)
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
