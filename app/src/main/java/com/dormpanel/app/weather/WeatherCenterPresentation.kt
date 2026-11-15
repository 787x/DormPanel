package com.dormpanel.app.weather

import android.os.Bundle
import com.dormpanel.app.dashboard.card.localDate
import com.dormpanel.app.dashboard.card.value
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

internal data class WeatherCenterSelection(
    val placeId: String? = null,
    val source: WeatherSourceRef? = null,
    val legacy: Boolean = false,
) {
    fun resolve(places: WeatherPlaceStore): WeatherCenterSelection {
        if (legacy) return WeatherCenterSelection(legacy = true)
        val place = placeId?.let(places::get) ?: places.orderedPlaces.firstOrNull()
            ?: return WeatherCenterSelection()
        return WeatherCenterSelection(place.id, source?.takeIf { it in place.sources } ?: place.sources.first())
    }

    fun save(places: WeatherPlaceStore) = Bundle().apply {
        putBoolean("legacy", legacy)
        if (placeId != null && !places.isTemporary(placeId)) {
            putString("place", placeId)
            putString("source", source?.let { WeatherSourceJson.encode(it).toString() })
        }
    }

    companion object {
        fun restore(state: Bundle?, places: WeatherPlaceStore): WeatherCenterSelection {
            val source = state?.getString("source")?.let {
                runCatching { WeatherSourceJson.decode(JSONObject(it)) }.getOrNull()
            }
            return WeatherCenterSelection(state?.getString("place"), source, state?.getBoolean("legacy") == true).resolve(places)
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
        val conditions = entry.conditions
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

internal fun centerRefreshText(result: WeatherRefreshResult, zone: ZoneId): String = when (result.status) {
    WeatherRefreshStatus.STARTED -> "已开始刷新"
    WeatherRefreshStatus.JOINED -> "已合并到正在进行的刷新"
    WeatherRefreshStatus.QUEUED -> "已排队"
    WeatherRefreshStatus.BLOCKED -> listOfNotNull(result.reason ?: "暂时无法刷新",
        result.retryAt?.atZone(zone)?.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))?.let { "最早 $it" }).joinToString(" · ")
}
