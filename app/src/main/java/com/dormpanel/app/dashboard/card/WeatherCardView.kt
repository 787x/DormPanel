package com.dormpanel.app.dashboard.card

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.graphics.Paint
import com.dormpanel.app.weather.WeatherSourceRef
import android.widget.TextView
import android.widget.LinearLayout
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.dashboard.model.PlacedCard
import com.dormpanel.app.data.Availability
import com.dormpanel.app.data.DashboardDataSource
import com.dormpanel.app.weather.WeatherBlock
import com.dormpanel.app.weather.WeatherRepository
import java.time.Instant

@SuppressLint("ViewConstructor")
class WeatherCardView(
    context: Context,
    appearance: AppearanceController,
    source: DashboardDataSource,
    repository: WeatherRepository? = null,
) : DashboardCardView(context, appearance) {
    private val feed = WeatherCardFeed(source, repository, ::refresh)
    private var configuration: WeatherCardConfiguration = WeatherCardConfiguration.Default
    private var requested = false
    private val heading = label(17f, true).singleLine()
    private val attribution = label(16f, true).singleLine()
    private val current = row()
    private val icon = WeatherIconView(context)
    private val temperature = fittingLabel(38f)
    private val condition = label(20f)
    private val status = label(15f, true).singleLine()
    private val summary = fittingLabel(19f, true)
    private val compactPrediction = fittingLabel(17f)
    private val forecast = row().apply { gravity = Gravity.TOP }
    private val forecastLabels = List(3) {
        Triple(label(16f, true).singleLine(), fittingLabel(21f), label(15f, true).singleLine())
    }

    init {
        setPadding(14.dp, 6.dp, 14.dp, 6.dp)
        addView(row().apply {
            addView(heading, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(attribution, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = 8.dp })
        })
        current.addView(icon, LayoutParams(36.dp, 36.dp).apply { marginEnd = 6.dp })
        current.addView(temperature, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        current.addView(condition, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 6.dp })
        addView(current)
        addView(status)
        addView(summary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp })
        addView(compactPrediction, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp })
        forecastLabels.forEach { (day, range, sky) ->
            forecast.addView(column().apply { addView(day); addView(range); addView(sky) },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6.dp })
        }
        addView(forecast, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 14.dp })
    }

    override fun onBound(previous: PlacedCard?) {
        val configurationChanged = previous?.configurationJson != card.configurationJson
        if (configurationChanged) configuration = WeatherCardConfiguration.decode(card.configurationJson)
        if (isAttachedToWindow && (configurationChanged || interactions.enabled || !requested)) bindFeed()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isBound) { bindFeed(); refresh() }
    }

    override fun onDetachedFromWindow() {
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
        updateLayout(presentation)
        val now = Instant.now()
        val legacy = feed.legacy
        val validLegacy = legacy?.takeIf { it.availability != Availability.UNAVAILABLE }
        val snapshot = feed[WeatherBlock.CURRENT]
        val conditions = snapshot.conditions()
        val prediction = feed.forecast
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
        temperature.text = conditions?.temperature.value("°C") ?: validLegacy?.temperature.value(validLegacy?.temperatureUnit.orEmpty()) ?: "—"
        condition.text = if (invalid) "请重新选择" else weather.description
        condition.textSize = if (presentation.summary) 24f else 18f
        icon.symbol = weather.symbol
        status.text = when {
            invalid -> "地点或来源已失效"
            legacy != null -> legacy.status()
            else -> weatherStatus(snapshot)
        }
        val unit = validLegacy?.temperatureUnit ?: "°"
        val metric = binding?.metric ?: WeatherCardMetric.FEELS_LIKE
        val availableMetrics = conditions ?: validLegacy?.conditions
        val extra = if (card.size.columnSpan >= 3 && card.size.rowSpan >= 2) 2 else 1
        val metrics = (listOf(metric) + WeatherCardMetric.entries).distinct()
            .mapNotNull { choice ->
                availableMetrics?.takeUnless { choice == WeatherCardMetric.WIND && it.windSpeed == null }?.metric(choice)
            }.take(extra)
            .ifEmpty { listOfNotNull(validLegacy?.humidity.value("%")?.let { "湿度 $it" }) }
        summary.text = (listOfNotNull(
            range(today?.minTemperature ?: validLegacy?.low, today?.maxTemperature ?: validLegacy?.high, unit),
        ) + metrics).joinToString("  ·  ")
        summary.show(presentation.summary && summary.text.isNotEmpty())
        val slots = if (prediction != null && prediction.query.block != WeatherBlock.DAILY && presentation.summary)
            maxOf(1, presentation.forecastColumns) else presentation.forecastColumns
        val lines = if (slots == 0) emptyList() else if (validLegacy != null)
            validLegacy.lines(now, slots) else prediction.forecastLines(now, slots, includeToday = prediction?.query?.block != WeatherBlock.DAILY)
        val compact = lines.firstOrNull()?.takeIf { presentation.forecastColumns == 0 }
        compactPrediction.show(compact != null)
        compactPrediction.text = compact?.let { listOfNotNull(it.label, it.temperature, it.sky.description).joinToString("  ") }.orEmpty()
        forecast.show(compact == null && lines.isNotEmpty())
        if (presentation.summary && !invalid && binding?.source is com.dormpanel.app.weather.WeatherSourceRef.HomeAssistant &&
            prediction == null && snapshot?.haForecastFeatures == 0) {
            status.text = "${status.text}  ·  不支持预报"
        } else if (presentation.summary && prediction != null && prediction.state != com.dormpanel.app.weather.WeatherState.FRESH &&
            (prediction.state != snapshot?.state || snapshot.data != null)) {
            status.text = "${status.text}  ·  预报${weatherStatus(prediction)}"
        }
        forecastLabels.forEachIndexed { index, (day, range, sky) ->
            val item = lines.getOrNull(index)
            forecast.getChildAt(index).show(item != null)
            if (item != null) { day.text = item.label; range.text = item.temperature ?: "—"; sky.text = item.sky.description }
        }
        describe(heading.text, temperature.text, condition.text, status.text, if (summary.visibility == VISIBLE) summary.text else "", compactPrediction.text)
    }

    override fun applyAppearance(state: AppearanceState) {
        super.applyAppearance(state)
        icon.tint(PanelPalette.forMode(state.themeMode).accent)
    }

    private fun updateLayout(presentation: WeatherPresentation) {
        val tall = card.size.columnSpan == 2 && card.size.rowSpan == 3
        val orientation = if (tall) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        if (current.orientation != orientation) {
            current.orientation = orientation
            current.gravity = if (tall) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            temperature.layoutParams = if (tall) LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                else LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            condition.layoutParams = if (tall) LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                else LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 6.dp }
        }
        val side = when { tall -> 72.dp; presentation.summary -> 52.dp; else -> 33.dp }
        if (icon.layoutParams.width != side) icon.layoutParams = (icon.layoutParams as LayoutParams).apply {
            width = side
            height = side
            marginEnd = if (tall) 0 else 6.dp
        }
        if (temperature.maximumTextSp != presentation.temperatureSp) temperature.maximumTextSp = presentation.temperatureSp
    }

    private fun TextView.singleLine() = apply { maxLines = 1 }
}
