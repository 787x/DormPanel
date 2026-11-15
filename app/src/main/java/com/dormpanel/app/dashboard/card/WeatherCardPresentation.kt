package com.dormpanel.app.dashboard.card

import com.dormpanel.app.data.Availability
import com.dormpanel.app.data.WeatherState as LegacyWeather
import com.dormpanel.app.weather.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class WeatherSymbol { SUN, MOON, PARTLY, PARTLY_NIGHT, CLOUD, RAIN, SNOW, STORM, FOG, HAZE, WIND, UNKNOWN, THERMOMETER, DROPLET, GAUGE }
internal data class WeatherSky(val symbol: WeatherSymbol, val description: String)
internal data class WeatherLine(val label: String, val sky: WeatherSky, val temperature: String?, val rain: String?)

internal fun sky(code: String?, description: String? = null, daytime: Boolean? = null): WeatherSky {
    val night = daytime == false
    val partly = if (night) WeatherSymbol.PARTLY_NIGHT else WeatherSymbol.PARTLY
    val value = when (code?.lowercase(Locale.ROOT)) {
        "100", "sunny", "clear" -> WeatherSky(if (night) WeatherSymbol.MOON else WeatherSymbol.SUN, if (night) "晴夜" else "晴")
        "150", "clear-night" -> WeatherSky(WeatherSymbol.MOON, "晴夜")
        "101", "102", "103", "partlycloudy", "partly cloudy" -> WeatherSky(partly, "多云")
        "151", "152", "153" -> WeatherSky(WeatherSymbol.PARTLY_NIGHT, "多云")
        "104", "cloudy" -> WeatherSky(WeatherSymbol.CLOUD, "阴")
        "300", "301", "305", "306", "307", "308", "309", "310", "311", "312", "314", "315", "316",
        "317", "318", "350", "351", "399", "rainy", "pouring", "light rain" -> WeatherSky(WeatherSymbol.RAIN, "雨")
        "302", "303", "304", "lightning", "lightning-rainy" -> WeatherSky(WeatherSymbol.STORM, "雷暴")
        "313", "hail" -> WeatherSky(WeatherSymbol.SNOW, if (code == "313") "冻雨" else "冰雹")
        "404", "405", "406", "456", "457", "snowy-rainy" -> WeatherSky(WeatherSymbol.SNOW, "雨夹雪")
        "400", "401", "402", "403", "407", "408", "409", "410", "419", "450", "499", "snowy" -> WeatherSky(WeatherSymbol.SNOW, "雪")
        "500", "501", "509", "510", "514", "515", "fog" -> WeatherSky(WeatherSymbol.FOG, "雾")
        "502", "511", "512", "513" -> WeatherSky(WeatherSymbol.HAZE, "霾")
        "503", "504", "507", "508" -> WeatherSky(WeatherSymbol.HAZE, "沙尘")
        "windy", "windy-variant" -> WeatherSky(WeatherSymbol.WIND, "有风")
        else -> WeatherSky(WeatherSymbol.UNKNOWN, if (code.isNullOrBlank()) "天气未知" else "未知天气")
    }
    return value.copy(description = description?.takeIf { it.isNotBlank() } ?: value.description)
}

internal fun weatherSourceName(source: WeatherSourceRef) = when (source) {
    is WeatherSourceRef.HomeAssistant -> "Home Assistant · ${source.entityId}"
    is WeatherSourceRef.QWeather, is WeatherSourceRef.QWeatherCity -> "和风天气"
}

internal fun weatherStatus(snapshot: WeatherSnapshot?): String {
    if (snapshot == null) return "正在加载"
    val state = when {
        snapshot.failureCode == 429 -> "请求受限"
        else -> when (snapshot.state) {
            WeatherState.UNLOADED, WeatherState.LOADING -> if (snapshot.data == null) "正在加载" else "正在更新"
            WeatherState.FRESH -> "最新"
            WeatherState.STALE -> "已过期"
            WeatherState.EMPTY -> "无数据"
            WeatherState.UNSUPPORTED -> "不支持"
            WeatherState.UNCONFIGURED -> "未配置"
            WeatherState.UNAUTHORIZED -> "认证或权限失败"
            WeatherState.BUDGET -> "请求受限 · 达到预算"
            WeatherState.ERROR -> "暂不可用"
            WeatherState.INVALID_REQUEST -> "请求无效"
        }
    }
    val offline = snapshot.network == WeatherNetworkStatus.OFFLINE
    val status = if (offline && snapshot.state in setOf(WeatherState.ERROR, WeatherState.LOADING, WeatherState.UNLOADED)) "离线" else state
    return listOfNotNull(
        if (offline && status != "离线") "离线" else null,
        if (snapshot.stale && state != "已过期") "已过期" else null,
        status,
    ).joinToString(" · ")
}

internal fun LegacyWeather.status() = when (availability) {
    Availability.AVAILABLE -> "最新"
    Availability.STALE -> "离线 · 已过期"
    Availability.UNAVAILABLE -> "无数据"
}

internal fun WeatherSnapshot?.conditions() = (this?.data as? WeatherData.Current)?.conditions
internal fun WeatherSnapshot?.entries() = (this?.data as? WeatherData.Forecast)?.entries.orEmpty()
internal fun WeatherForecast.localDate(zone: ZoneId) = period.date ?: period.localTime?.toLocalDate()
    ?: period.start?.atZone(zone)?.toLocalDate()

internal fun WeatherSnapshot?.today(now: Instant): WeatherForecast? {
    val snapshot = this?.takeIf { it.query.block == WeatherBlock.DAILY } ?: return null
    val date = now.atZone(snapshot.zone).toLocalDate()
    return snapshot.entries().firstOrNull { it.localDate(snapshot.zone) == date }
}

internal fun WeatherSnapshot?.forecastLines(now: Instant, limit: Int, includeToday: Boolean = true): List<WeatherLine> {
    val snapshot = this ?: return emptyList()
    val today = now.atZone(zone).toLocalDate()
    val homeAssistant = source is WeatherSourceRef.HomeAssistant
    return entries().asSequence().mapNotNull { item ->
        if (homeAssistant && item.period.haForecastEnd(query.block, zone)?.isAfter(now) != true) return@mapNotNull null
        val date = item.localDate(zone)
        val local = item.period.start?.atZone(zone)?.toLocalDateTime() ?: item.period.localTime
        val label = when (query.block) {
            WeatherBlock.HOURLY -> {
                if (item.period.end?.let { it <= now } == true ||
                    (item.period.end == null && item.period.start?.plusSeconds(3600)?.let { it <= now } == true)) return@mapNotNull null
                local?.format(hourFormat) ?: return@mapNotNull null
            }
            WeatherBlock.DAILY, WeatherBlock.TWICE_DAILY -> {
                if (date == null || (!homeAssistant && date < today) || (!includeToday && date == today)) return@mapNotNull null
                val part = when (item.period.isDaytime) { true -> " 白天"; false -> " 夜间"; null -> "" }
                dateLabel(date) + if (query.block == WeatherBlock.TWICE_DAILY) part else ""
            }
            else -> return@mapNotNull null
        }
        val conditions = if (query.block == WeatherBlock.DAILY && item.conditions.code == null) {
            item.day?.conditions ?: item.night?.conditions ?: item.conditions
        } else item.conditions
        val daytime = item.period.isDaytime ?: if (conditions === item.night?.conditions) false else null
        WeatherLine(label, sky(conditions.code, conditions.description, daytime),
            if (query.block != WeatherBlock.DAILY) conditions.temperature.value("°")
            else range(item.conditions.minTemperature, item.conditions.maxTemperature, "°"),
            conditions.precipitationProbability.value("%")?.let { "降水 $it" })
    }.take(limit).toList()
}

private val hourFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
internal val weatherUpdateFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
internal fun dateLabel(date: LocalDate) = "${date.monthValue}月${date.dayOfMonth}日"
internal fun Number?.value(unit: String): String? {
    val number = this?.toDouble()?.takeIf(Double::isFinite) ?: return null
    val formatted = if (number % 1 == 0.0) String.format(Locale.ROOT, "%.0f", number) else String.format(Locale.ROOT, "%.1f", number)
    return formatted + unit
}

internal fun range(low: Number?, high: Number?, unit: String): String? =
    listOfNotNull(low.value(unit)?.let { "低 $it" }, high.value(unit)?.let { "高 $it" })
        .joinToString(" / ").ifEmpty { null }

internal fun WeatherConditions.metric(metric: WeatherCardMetric) = when (metric) {
    WeatherCardMetric.FEELS_LIKE -> feelsLike.value("°C")?.let { "体感 $it" }
    WeatherCardMetric.HUMIDITY -> humidity.value("%")?.let { "湿度 $it" }
    WeatherCardMetric.WIND -> listOfNotNull(windDirection, windDegrees.value("°").takeIf { windDirection == null }, windSpeed.value(" m/s"))
        .joinToString(" ").takeIf(String::isNotEmpty)?.let { "风 $it" }
}

internal fun LegacyWeather.lines(now: Instant, limit: Int): List<WeatherLine> {
    val zone = ZoneId.systemDefault()
    val today = now.atZone(zone).toLocalDate()
    return forecast.asSequence().mapNotNull {
        val start = Instant.ofEpochMilli(it.timeEpochMillis)
        val local = start.atZone(zone)
        val label = when (forecastType) {
            "hourly" -> if (start.plusSeconds(3600) <= now) return@mapNotNull null else local.format(hourFormat)
            "twice_daily" -> dateLabel(local.toLocalDate()) + when (it.isDaytime) { true -> " 白天"; false -> " 夜间"; null -> "" }
            else -> dateLabel(local.toLocalDate())
        }
        if (entityId != null && local.toLocalDate() < today) return@mapNotNull null
        WeatherLine(label, sky(it.condition, daytime = it.isDaytime),
            if (forecastType != "daily") it.forecastTemperature.value(temperatureUnit) else range(it.low, it.high, temperatureUnit),
            it.rainProbability.value("%")?.let { probability -> "降水 $probability" })
    }.take(limit).toList()
}
