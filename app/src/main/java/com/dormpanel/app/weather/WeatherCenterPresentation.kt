package com.dormpanel.app.weather

import android.os.Bundle
import com.dormpanel.app.dashboard.card.WeatherCardConfiguration
import com.dormpanel.app.dashboard.card.localDate
import com.dormpanel.app.dashboard.card.value
import com.dormpanel.app.dashboard.card.today
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

internal data class WeatherCenterSelection(
    val placeId: String? = null,
    val source: WeatherSourceRef? = null,
    val legacy: Boolean = false,
    val invalidBinding: Boolean = false,
) {
    fun resolve(places: WeatherPlaceStore): WeatherCenterSelection {
        if (legacy || invalidBinding || source != null) return this
        val place = if (placeId == null) places.orderedPlaces.firstOrNull { !places.isTemporary(it.id) } else places.get(placeId)
        return if (place != null) WeatherCenterSelection(place.id, place.sources.first()) else this
    }

    fun selectedPlace(places: WeatherPlaceStore): WeatherPlace? =
        placeId?.let(places::get)?.takeIf { !invalidBinding && source in it.sources }

    fun isUnavailable(places: WeatherPlaceStore) = invalidBinding || (placeId != null && selectedPlace(places) == null)

    fun save(places: WeatherPlaceStore) = Bundle().apply {
        putBoolean("legacy", legacy)
        val temporary = placeId != null && places.isTemporary(placeId)
        putBoolean("invalid", invalidBinding || temporary)
        if (placeId != null && !temporary) {
            putString("place", placeId)
            putString("source", source?.let { WeatherSourceJson.encode(it).toString() })
        }
    }

    companion object {
        fun fromCard(config: WeatherCardConfiguration): WeatherCenterSelection = when (config) {
            WeatherCardConfiguration.Default -> WeatherCenterSelection(legacy = true)
            WeatherCardConfiguration.Invalid -> WeatherCenterSelection(invalidBinding = true)
            is WeatherCardConfiguration.Bound -> WeatherCenterSelection(config.placeId, config.source)
        }

        fun restore(state: Bundle?, places: WeatherPlaceStore): WeatherCenterSelection {
            val source = state?.getString("source")?.let {
                runCatching { WeatherSourceJson.decode(JSONObject(it)) }.getOrNull()
            }
            val place = state?.getString("place")
            val invalid = state?.getBoolean("invalid") == true || ((place != null || state?.containsKey("source") == true) &&
                (place.isNullOrBlank() || source == null))
            return WeatherCenterSelection(place, source, state?.getBoolean("legacy") == true, invalid).resolve(places)
        }
    }
}

internal val centerDate = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)
internal val centerTime = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

internal data class WeatherTrendPoint(
    val time: Instant,
    val value: Double?,
    val label: String,
    val detail: String,
    val dateBreak: Boolean = false,
)

internal enum class HourlyMetric(val title: String, val unit: String, val bars: Boolean = false) {
    TEMPERATURE("温度", "°C"), RAIN_CHANCE("降水概率", "%"), RAIN_AMOUNT("降水量", " mm", bars = true),
    WIND("风速", " m/s");

    fun reading(conditions: WeatherConditions?): Double? = when (this) {
        TEMPERATURE -> conditions?.temperature
        RAIN_CHANCE -> conditions?.precipitationProbability?.takeIf { it in 0.0..100.0 }
        RAIN_AMOUNT -> conditions?.precipitation?.takeIf { it >= 0 }
        WIND -> conditions?.windSpeed?.takeIf { it >= 0 }
    }?.takeIf(Double::isFinite)

    fun series(conditions: List<WeatherConditions?>): WeatherTrendSeries {
        val values = conditions.map(::reading)
        val available = values.filterNotNull()
        val scale = when {
            available.isEmpty() -> null
            this == RAIN_CHANCE -> WeatherScale(0.0, 100.0)
            this == RAIN_AMOUNT && available.max() < 1 -> WeatherScale(0.0, maxOf(.2, ceil(available.max() * 5) / 5))
            else -> weatherScale(available, includeZero = this != TEMPERATURE)
        }
        return WeatherTrendSeries(values, scale, unit, bars)
    }
}

internal data class WeatherTrendSeries(
    val values: List<Double?>, val scale: WeatherScale?, val unit: String, val bars: Boolean = false,
)

internal fun hourlyTrend(entries: List<WeatherForecast>, zone: ZoneId, now: Instant, hours: Int): List<WeatherTrendPoint> {
    val limit = now.plusSeconds(hours * 3600L)
    val periods = entries.mapNotNull { entry ->
        val time = entry.period.start ?: return@mapNotNull null
        val end = entry.period.end ?: time.plusSeconds(3600)
        if (end <= now || time >= limit) null else time to entry
    }.sortedBy { it.first }.distinctBy { it.first }.take(hours)
    val labels = periods.map { it.first.atZone(zone) }
    val counts = labels.groupingBy { it.toLocalDateTime() }.eachCount()
    return periods.mapIndexed { index, (time, entry) ->
        val local = labels[index]
        val dateBreak = index == 0 || local.toLocalDate() != labels[index - 1].toLocalDate()
        val clock = local.format(centerTime)
        val label = when {
            counts[local.toLocalDateTime()] != 1 -> "$clock ${local.offset}"
            dateBreak -> "${local.monthValue}/${local.dayOfMonth} $clock"
            else -> clock
        }
        val conditions = entry.conditions.let { c ->
            c.copy(temperature = HourlyMetric.TEMPERATURE.reading(c), windSpeed = HourlyMetric.WIND.reading(c),
                precipitation = HourlyMetric.RAIN_AMOUNT.reading(c), precipitationProbability = HourlyMetric.RAIN_CHANCE.reading(c))
        }
        WeatherTrendPoint(time, conditions.temperature, label,
            listOf(local.format(centerDate) + " " + clock + " " + local.offset,
                com.dormpanel.app.dashboard.card.sky(conditions.code, conditions.description).description,
                *conditionMetrics(conditions).toTypedArray()).joinToString("\n"), dateBreak)
    }
}

internal fun futureDays(entries: List<WeatherForecast>, zone: ZoneId, now: Instant): List<WeatherForecast> =
    entries.mapNotNull { entry -> entry.localDate(zone)?.let { it to entry } }
        .filter { (date, _) -> date >= now.atZone(zone).toLocalDate() }
        .sortedBy { it.first }.distinctBy { it.first }.take(10).map { it.second }

internal data class WeatherScale(val min: Double, val max: Double) {
    fun fraction(value: Double) = ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
}

internal fun weatherScale(values: List<Double>, includeZero: Boolean = false): WeatherScale? {
    val clean = values.filter(Double::isFinite)
    if (clean.isEmpty()) return null
    val low = if (includeZero) 0.0 else floor(clean.min())
    return WeatherScale(low, maxOf(low + 1, ceil(clean.max())))
}

internal fun conditionMetrics(c: WeatherConditions): List<String> {
    fun metric(title: String, value: Number?, unit: String) = value.value(unit)?.let { "$title  $it" }
    val uvLevel = c.uvIndex?.let {
        when {
            it < 3 -> "低"
            it < 6 -> "中等"
            it < 8 -> "高"
            it < 11 -> "很高"
            else -> "极高"
        }
    }
    return listOfNotNull(
        metric("温度", c.temperature, "°C"), metric("体感", c.feelsLike, "°C"),
        metric("最低", c.minTemperature, "°C"), metric("最高", c.maxTemperature, "°C"),
        metric("平均", c.meanTemperature, "°C"),
        metric("湿度", c.humidity, "%"), metric("露点", c.dewPoint, "°C"),
        listOfNotNull(c.windDirection ?: c.windDegrees.value("°"), c.windSpeed.value(" m/s"),
            c.windScale.value(" 级")).takeIf { it.isNotEmpty() }?.joinToString("  ", prefix = "风  "),
        metric("阵风", c.gust, " m/s"), metric("气压", c.pressure, " hPa"),
        metric("能见度", c.visibility, " m"), metric("云量", c.cloudCover, "%"),
        metric("紫外线", c.uvIndex, "")?.let { "$it · $uvLevel" },
        metric("降水量", c.precipitation, " mm"), metric("降水概率", c.precipitationProbability, "%"),
        metric("降水强度", c.precipitationIntensity, " mm/h"), c.precipitationType?.let { "降水类型  $it" },
    )
}

internal data class CenterAstronomy(
    val date: LocalDate,
    val daily: WeatherAstronomy?,
    val forecastSnapshot: WeatherSnapshot?,
    val supplement: WeatherAstronomy?,
    val supplementSnapshot: WeatherSnapshot?,
    val waitingForDaily: Boolean,
    val needsSupplement: Boolean,
    val missing: List<String>,
)

private val lunarEvents = setOf(AstronomicalEvent.MOONRISE, AstronomicalEvent.MOONSET, AstronomicalEvent.MOON_TRANSIT,
    AstronomicalEvent.MOON_UNDERFOOT)

internal fun centerAstronomy(
    block: WeatherBlock, daily: WeatherSnapshot?, supplement: WeatherSnapshot?, now: Instant, zone: ZoneId,
): CenterAstronomy {
    val date = now.atZone(zone).toLocalDate()
    val moon = block == WeatherBlock.MOON
    fun WeatherAstronomy.part(exclude: WeatherAstronomy? = null): WeatherAstronomy? {
        if (this.date != null && this.date != date) return null
        fun include(event: AstronomicalEvent) = (event in lunarEvents) == moon &&
            exclude?.events?.containsKey(event) != true && exclude?.localEvents?.containsKey(event) != true
        return copy(date = date, events = events.filterKeys(::include), localEvents = localEvents.filterKeys(::include),
            phase = phase.takeIf { moon && exclude?.phaseName() == null },
            hourlyPhases = hourlyPhases.takeIf { moon && exclude?.phaseName() == null }.orEmpty())
            .takeIf { it.events.isNotEmpty() || it.localEvents.isNotEmpty() || it.phaseName() != null }
    }
    fun WeatherSnapshot.isCurrent() = !stale && expiresAt?.let { now < it } != false
    val currentDaily = daily?.takeIf { it.isCurrent() }
    val forecast = currentDaily.today(now)?.astronomy?.part()
    fun WeatherAstronomy?.has(event: AstronomicalEvent) = this != null && (event in events || event in localEvents)
    val waiting = daily == null || (daily.state in setOf(WeatherState.UNLOADED, WeatherState.LOADING) &&
        currentDaily?.data == null && daily.network != WeatherNetworkStatus.OFFLINE)
    val events = if (moon) listOf(AstronomicalEvent.MOONRISE to "月升", AstronomicalEvent.MOONSET to "月落")
        else listOf(AstronomicalEvent.SUNRISE to "日出", AstronomicalEvent.SUNSET to "日落")
    fun missing(additional: WeatherAstronomy?) =
        events.filterNot { forecast.has(it.first) || additional.has(it.first) }.map { it.second } +
            listOfNotNull("月相".takeIf { moon && forecast?.phaseName() == null && additional?.phaseName() == null })
    val needs = missing(null).isNotEmpty()
    val currentSupplement = supplement?.takeIf {
        needs && it.query.block == block && (it.query.date == null || it.query.date == date) &&
            (daily == null || it.source == daily.source) && it.zone == zone
    }
    val usableSupplement = currentSupplement?.takeIf { it.isCurrent() }
    val additional = (usableSupplement?.data as? WeatherData.Astronomy)?.value?.part(forecast)
    return CenterAstronomy(date, forecast, daily, additional, currentSupplement, waiting, needs && !waiting, missing(additional))
}

internal fun WeatherAstronomy.phaseName(): String? =
    phase?.takeIf(String::isNotBlank) ?: hourlyPhases.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) }

internal fun centerRefreshText(result: WeatherRefreshResult, zone: ZoneId): String = when (result.status) {
    WeatherRefreshStatus.STARTED -> "已开始刷新"
    WeatherRefreshStatus.JOINED -> "已合并到正在进行的刷新"
    WeatherRefreshStatus.QUEUED -> "已排队"
    WeatherRefreshStatus.BLOCKED -> listOfNotNull(result.reason ?: "暂时无法刷新",
        result.retryAt?.atZone(zone)?.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))?.let { "最早 $it" }).joinToString(" · ")
}
