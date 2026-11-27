package com.dormpanel.app.dashboard.card

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.dashboard.model.PlacedCard
import com.dormpanel.app.data.Availability
import com.dormpanel.app.data.DashboardDataSource
import com.dormpanel.app.weather.WeatherBlock
import com.dormpanel.app.weather.WeatherRepository
import com.dormpanel.app.weather.WeatherSourceRef
import com.dormpanel.app.weather.WeatherState
import java.time.Instant
import java.time.ZoneId

internal data class WeatherCardReadings(
    val title: String,
    val credit: String,
    val qweather: Boolean,
    val temperature: String,
    val condition: String,
    val symbol: WeatherSymbol,
    val range: String?,
    val airQuality: String?,
    val status: String,
    val preferredMetric: WeatherCardMetric?,
    val forecast: List<WeatherLine>,
)

@SuppressLint("ViewConstructor")
internal abstract class BaseWeatherCardView(
    context: Context,
    appearance: AppearanceController,
    source: DashboardDataSource,
    private val repository: WeatherRepository?,
    private val clock: () -> Instant,
    private val classic: Boolean = false,
) : DashboardCardView(context, appearance) {
    protected abstract val forecastColumns: Int
    protected open val showsAirQuality get() = false
    protected abstract fun renderReadings(readings: WeatherCardReadings, metric: (WeatherCardMetric) -> String?)

    private val feed = WeatherCardFeed(source, repository, ::refresh)
    private var configuration: WeatherCardConfiguration = WeatherCardConfiguration.Default
    private var lastClassicPlaceName: String? = null
    private val needsForecast get() = card.size.rowSpan >= 2
    private data class Demand(val forecast: Boolean, val airQuality: Boolean)

    private val demand get() = Demand(needsForecast, showsAirQuality &&
        (configuration as? WeatherCardConfiguration.Bound)?.source?.let { it !is WeatherSourceRef.HomeAssistant } == true)
    private var requestedDemand: Demand? = null
    private var pendingDemand: Demand? = null
    private val updatePreviewDemand = Runnable {
        pendingDemand = null
        bindFeed()
        refresh()
    }
    private val midnight = Runnable { refresh() }
    private val clockChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refresh() }
    }
    override fun onBound(previous: PlacedCard?) {
        val configurationChanged = previous?.configurationJson != card.configurationJson
        if (configurationChanged) {
            val next = WeatherCardConfiguration.decode(card.configurationJson)
            if ((configuration as? WeatherCardConfiguration.Bound)?.placeId != (next as? WeatherCardConfiguration.Bound)?.placeId) {
                lastClassicPlaceName = null
            }
            configuration = next
        }
        if (!isAttachedToWindow) return
        val demand = demand
        if (!interactions.isResizePreview || configurationChanged || requestedDemand == null) {
            cancelPreviewDemand()
            if (configurationChanged || requestedDemand != demand) bindFeed()
        } else if (demand != pendingDemand) {
            // Update layout immediately. Wait for stable data demand during a drag;
            // confirmation and rollback reconcile it immediately through the regular bind.
            cancelPreviewDemand()
            if (demand != requestedDemand) {
                pendingDemand = demand
                postDelayed(updatePreviewDemand, RESIZE_PREVIEW_DELAY_MS)
            }
        }
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
        cancelPreviewDemand()
        feed.close()
        requestedDemand = null
        super.onDetachedFromWindow()
    }

    private fun bindFeed() {
        val demand = demand
        requestedDemand = demand
        val blocks = if (demand.airQuality) setOf(WeatherBlock.CURRENT, WeatherBlock.AIR_CURRENT)
            else setOf(WeatherBlock.CURRENT)
        feed.bind(configuration, blocks, compactForecast = demand.forecast)
    }

    private fun cancelPreviewDemand() {
        if (pendingDemand == null) return
        removeCallbacks(updatePreviewDemand)
        pendingDemand = null
    }

    override fun primaryAction() = open(WeatherCardNavigation.Destination.WEATHER_CENTER)
    override fun secondaryAction() = open(WeatherCardNavigation.Destination.QUICK_DETAIL)

    private fun open(destination: WeatherCardNavigation.Destination) {
        val binding = configuration as? WeatherCardConfiguration.Bound
        interactions.openWeather(WeatherCardNavigation(card.id, binding?.placeId, binding?.source, destination))
    }

    final override fun render() {
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
        val description = conditions?.description ?: if (classic) legacyConditionText(validLegacy?.condition) else null
        val weather = sky(conditions?.code ?: validLegacy?.condition, description, classic = classic)
        val title = if (classic) {
            binding?.savedPlace(repository?.places)?.name?.let { lastClassicPlaceName = it }
            binding?.displayName ?: lastClassicPlaceName ?: "Weather"
        } else binding?.displayName ?: feed.place?.name ?: legacy?.name ?: "天气"
        val qweather = binding != null && binding.source !is WeatherSourceRef.HomeAssistant
        val credit = when {
            qweather -> "和风天气"
            binding != null -> "HA"
            else -> ""
        }
        val temperature = conditions?.cardTemperature()
            ?: validLegacy?.temperature.value(validLegacy?.temperatureUnit.orEmpty()) ?: "—"
        val condition = if (invalid) "请重新选择" else weather.description
        var status = when {
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
        val range = when {
            today != null -> today.cardRange()
            legacyToday != null -> cardRange(legacyToday.low, legacyToday.high, unit)
            else -> cardRange(providedRange?.low, providedRange?.high, unit) ?: conditions?.cardRange()
        }

        fun metric(choice: WeatherCardMetric): String? = when (choice) {
            WeatherCardMetric.HUMIDITY -> conditions?.metric(choice)
                ?: validLegacy?.humidity.value("%")?.let { "湿度 $it" }
            WeatherCardMetric.WIND -> conditions?.takeIf { it.windSpeed != null }?.metric(choice)
            else -> conditions?.metric(choice, unit)
        }

        val slots = forecastColumns
        val lines = when {
            slots == 0 -> emptyList()
            validLegacy != null -> validLegacy.lines(now, slots, forCard = true, zone = zone, includeToday = false, classic = classic)
            else -> prediction.forecastLines(now, slots, includeToday = prediction?.query?.block != WeatherBlock.DAILY,
                forCard = true, classic = classic)
        }
        if (needsForecast && !invalid && binding?.source is WeatherSourceRef.HomeAssistant &&
            prediction == null && snapshot?.haForecastFeatures == 0) {
            status = "${status}  ·  不支持预报"
        } else if (needsForecast && prediction != null && prediction.state != WeatherState.FRESH &&
            (prediction.state != snapshot?.state || snapshot.data != null)) {
            status = "${status}  ·  预报${weatherStatus(prediction)}"
        } else if (slots > 0 && prediction?.state == WeatherState.FRESH && lines.isEmpty()) {
            status = "${status}  ·  暂无后续预报"
        }
        val airQuality = if (showsAirQuality) {
            feed[WeatherBlock.AIR_CURRENT].cardAirQuality()
                ?: validLegacy?.takeIf { it.availability == Availability.AVAILABLE }?.airQuality?.let {
                    listOfNotNull("AQI ${it.index}", it.category).joinToString(" ")
                }
        } else null
        renderReadings(WeatherCardReadings(title, credit, qweather, temperature, condition,
            weather.symbol, range, airQuality,
            status, binding?.metric, lines), ::metric)

        removeCallbacks(midnight)
        if (isAttachedToWindow && (prediction.entries().isNotEmpty() || !validLegacy?.forecast.isNullOrEmpty())) {
            postDelayed(midnight, (nextWeatherMidnight(now, zone).toEpochMilli() - now.toEpochMilli()).coerceAtLeast(1))
        }
    }

    protected fun bindAttribution(attribution: TextView, readings: WeatherCardReadings) {
        attribution.text = readings.credit
        val qweather = readings.qweather
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
    }

    protected fun forecastDescription(lines: List<WeatherLine>) =
        lines.joinToString("；") { listOfNotNull(it.label, it.temperature, it.sky.description).joinToString(" ") }

    private companion object {
        const val RESIZE_PREVIEW_DELAY_MS = 300L
    }
}
