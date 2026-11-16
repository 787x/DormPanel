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
internal data class WeatherLine(val label: String, val sky: WeatherSky, val temperature: String?, val rain: String?,
    val secondaryDate: String? = null)

internal fun sky(code: String?, description: String? = null, daytime: Boolean? = null, classic: Boolean = false): WeatherSky {
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
    val text = if (classic) classicCondition(code, description, daytime)
        else description?.takeIf { it.isNotBlank() } ?: value.description
    return value.copy(description = text)
}

// Exact descriptions are independent of the broad glyph groups used by the modern card.
// See QWeather's Weather Conditions and HA's Weather condition mapping.
private data class ClassicCondition(val english: String, val chinese: String)

private val qWeatherConditions = mapOf(
    "100" to ClassicCondition("Sunny", "晴"),
    "101" to ClassicCondition("Cloudy", "多云"),
    "102" to ClassicCondition("Few clouds", "少云"),
    "103" to ClassicCondition("Partly cloudy", "晴间多云"),
    "104" to ClassicCondition("Overcast", "阴"),
    "150" to ClassicCondition("Clear night", "晴"),
    "151" to ClassicCondition("Cloudy at night", "多云"),
    "152" to ClassicCondition("Few clouds at night", "少云"),
    "153" to ClassicCondition("Partly cloudy at night", "晴间多云"),
    "154" to ClassicCondition("Overcast at night", "阴"),
    "300" to ClassicCondition("Rain showers", "阵雨"),
    "301" to ClassicCondition("Heavy rain showers", "强阵雨"),
    "302" to ClassicCondition("Thundershowers", "雷阵雨"),
    "303" to ClassicCondition("Heavy thundershowers", "强雷阵雨"),
    "304" to ClassicCondition("Thundershowers with hail", "雷阵雨伴有冰雹"),
    "305" to ClassicCondition("Light rain", "小雨"),
    "306" to ClassicCondition("Moderate rain", "中雨"),
    "307" to ClassicCondition("Heavy rain", "大雨"),
    "308" to ClassicCondition("Extreme rain", "极端降雨"),
    "309" to ClassicCondition("Drizzle", "毛毛雨/细雨"),
    "310" to ClassicCondition("Rainstorm", "暴雨"),
    "311" to ClassicCondition("Heavy rainstorm", "大暴雨"),
    "312" to ClassicCondition("Severe rainstorm", "特大暴雨"),
    "313" to ClassicCondition("Freezing rain", "冻雨"),
    "314" to ClassicCondition("Light to moderate rain", "小到中雨"),
    "315" to ClassicCondition("Moderate to heavy rain", "中到大雨"),
    "316" to ClassicCondition("Heavy rain to rainstorm", "大到暴雨"),
    "317" to ClassicCondition("Rainstorm to heavy rainstorm", "暴雨到大暴雨"),
    "318" to ClassicCondition("Heavy to severe rainstorm", "大暴雨到特大暴雨"),
    "350" to ClassicCondition("Rain showers at night", "阵雨"),
    "351" to ClassicCondition("Heavy rain showers at night", "强阵雨"),
    "399" to ClassicCondition("Rain", "雨"),
    "400" to ClassicCondition("Light snow", "小雪"),
    "401" to ClassicCondition("Moderate snow", "中雪"),
    "402" to ClassicCondition("Heavy snow", "大雪"),
    "403" to ClassicCondition("Snowstorm", "暴雪"),
    "404" to ClassicCondition("Sleet", "雨夹雪"),
    "405" to ClassicCondition("Rain and snow", "雨雪天气"),
    "406" to ClassicCondition("Rain and snow showers", "阵雨夹雪"),
    "407" to ClassicCondition("Snow flurries", "阵雪"),
    "408" to ClassicCondition("Light to moderate snow", "小到中雪"),
    "409" to ClassicCondition("Moderate to heavy snow", "中到大雪"),
    "410" to ClassicCondition("Heavy snow to snowstorm", "大到暴雪"),
    "456" to ClassicCondition("Rain and snow showers at night", "阵雨夹雪"),
    "457" to ClassicCondition("Snow flurries at night", "阵雪"),
    "499" to ClassicCondition("Snow", "雪"),
    "500" to ClassicCondition("Mist", "薄雾"),
    "501" to ClassicCondition("Fog", "雾"),
    "502" to ClassicCondition("Haze", "霾"),
    "503" to ClassicCondition("Blowing sand", "扬沙"),
    "504" to ClassicCondition("Blowing dust", "浮尘"),
    "507" to ClassicCondition("Duststorm", "沙尘暴"),
    "508" to ClassicCondition("Severe duststorm", "强沙尘暴"),
    "509" to ClassicCondition("Dense fog", "浓雾"),
    "510" to ClassicCondition("Very dense fog", "强浓雾"),
    "511" to ClassicCondition("Moderate haze", "中度霾"),
    "512" to ClassicCondition("Heavy haze", "重度霾"),
    "513" to ClassicCondition("Severe haze", "严重霾"),
    "514" to ClassicCondition("Heavy fog", "大雾"),
    "515" to ClassicCondition("Extremely dense fog", "特强浓雾"),
    "900" to ClassicCondition("Hot", "热"),
    "901" to ClassicCondition("Cold", "冷"),
)

private val classicStateConditions = mapOf(
    "clear-night" to "Clear night",
    "cloudy" to "Cloudy",
    "fog" to "Fog",
    "hail" to "Hail",
    "lightning" to "Thunderstorm",
    "lightning-rainy" to "Thunderstorm with rain",
    "partlycloudy" to "Partly cloudy",
    "pouring" to "Heavy rain",
    "rainy" to "Rain",
    "snowy" to "Snow",
    "snowy-rainy" to "Rain and snow",
    "sunny" to "Sunny",
    "windy" to "Windy",
    "windy-variant" to "Windy and cloudy",
    "exceptional" to "Exceptional weather",
    // Historical Demo values also arrive in the condition field.
    "clear" to "Clear",
    "light rain" to "Light rain",
    "partly cloudy" to "Partly cloudy",
)

private val classicChineseConditions = buildMap {
    // Day and night codes can share the same Chinese text. Text alone never implies night.
    qWeatherConditions.values.forEach { putIfAbsent(it.chinese, it.english) }
    put("晴夜", "Clear night")
    put("毛毛雨", "Drizzle")
    put("细雨", "Drizzle")
    put("冰雹", "Hail")
    put("雷阵雨伴冰雹", "Thundershowers with hail")
    put("有风", "Windy")
}

private val unknownConditions = setOf("unknown", "unavailable", "n/a", "未知", "天气未知", "未知天气", "—", "-")
private fun String?.validConditionText() = this?.takeIf {
    it.any(Char::isLetter) && it.trim().lowercase(Locale.ROOT) !in unknownConditions
}

private fun classicCondition(code: String?, description: String?, daytime: Boolean?): String {
    val key = code?.trim()?.lowercase(Locale.ROOT)
    val coded = qWeatherConditions[key]
    val text = description.validConditionText()
    if (daytime == false && (key == "100" || key == "sunny" || key == "clear") &&
        (text == null || text.trim() == "晴")) return "Clear night"
    if (text != null) {
        val translation = classicChineseConditions[text.trim()]
        if (translation == null) return text
        // An exact match can carry additional information in the code, e.g. 晴 with code 150.
        if (text.trim() != coded?.chinese) return translation
    }
    return coded?.english ?: classicStateConditions[key] ?: "Unknown"
}

internal fun legacyConditionText(condition: String?): String? {
    val key = condition?.trim()?.lowercase(Locale.ROOT)
    return condition.validConditionText()?.takeUnless { key in classicStateConditions }
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

internal fun WeatherSnapshot?.forecastLines(now: Instant, limit: Int, includeToday: Boolean = true,
    forCard: Boolean = false, classic: Boolean = false): List<WeatherLine> {
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
                if (forCard && query.block == WeatherBlock.DAILY) cardDayLabel(date, today)
                else dateLabel(date) + if (query.block == WeatherBlock.TWICE_DAILY) part else ""
            }
            else -> return@mapNotNull null
        }
        val conditions = if (query.block == WeatherBlock.DAILY && item.conditions.code == null) {
            item.day?.conditions ?: item.night?.conditions ?: item.conditions
        } else item.conditions
        val daytime = item.period.isDaytime ?: if (conditions === item.night?.conditions) false else null
        val temperature = if (forCard) {
            if (query.block == WeatherBlock.DAILY) item.conditions.cardRange() else conditions.cardTemperature()
        } else if (query.block == WeatherBlock.DAILY) range(item.conditions.minTemperature, item.conditions.maxTemperature, "°")
        else conditions.temperature.value("°")
        val textConditions = if (classic && query.block == WeatherBlock.DAILY &&
            item.conditions.description.validConditionText() != null) item.conditions else conditions
        WeatherLine(label, sky(textConditions.code, textConditions.description, daytime, classic), temperature,
            conditions.precipitationProbability.value("%")?.let { "降水 $it" },
            date?.takeIf { forCard && query.block == WeatherBlock.DAILY }?.let(::dateLabel))
    }.take(limit).toList()
}

private val hourFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
internal val weatherUpdateFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
internal fun dateLabel(date: LocalDate) = "${date.monthValue}月${date.dayOfMonth}日"
internal fun cardDayLabel(date: LocalDate, today: LocalDate) = when (date) {
    today -> "今天"
    today.plusDays(1) -> "明天"
    else -> "周" + "一二三四五六日"[date.dayOfWeek.value - 1]
}

internal fun nextWeatherMidnight(now: Instant, zone: ZoneId): Instant =
    now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
internal fun Number?.value(unit: String): String? {
    val number = this?.toDouble()?.takeIf(Double::isFinite) ?: return null
    val formatted = if (number % 1 == 0.0) String.format(Locale.ROOT, "%.0f", number) else String.format(Locale.ROOT, "%.1f", number)
    return formatted + unit
}

internal fun range(low: Number?, high: Number?, unit: String): String? =
    listOfNotNull(low.value(unit)?.let { "低 $it" }, high.value(unit)?.let { "高 $it" })
        .joinToString(" / ").ifEmpty { null }

internal fun cardRange(low: Number?, high: Number?, unit: String): String? {
    val minimum = low.value("")?.removeSuffix(".0")
    val maximum = high.value("")?.removeSuffix(".0")
    return when {
        minimum != null && maximum != null -> "$minimum–$maximum$unit"
        maximum != null -> "最高 $maximum$unit"
        minimum != null -> "最低 $minimum$unit"
        else -> null
    }
}

private fun Double?.inDisplayUnit(unit: String): Double? = this?.let {
    when (unit) {
        "°F" -> it * 1.8 + 32
        "K" -> it + 273.15
        else -> it
    }
}

internal fun WeatherConditions.cardTemperature() =
    (sourceTemperatureUnit ?: "°C").let { unit ->
        temperature.inDisplayUnit(unit).value("")?.removeSuffix(".0")?.plus(unit)
    }

internal fun WeatherConditions.cardRange() = (sourceTemperatureUnit ?: "°C").let {
    cardRange(minTemperature.inDisplayUnit(it), maxTemperature.inDisplayUnit(it), it)
}

internal fun WeatherConditions.metric(metric: WeatherCardMetric, temperatureUnit: String = "°C") = when (metric) {
    WeatherCardMetric.FEELS_LIKE -> feelsLike.inDisplayUnit(temperatureUnit).value(temperatureUnit)?.let { "体感 $it" }
    WeatherCardMetric.HUMIDITY -> humidity.value("%")?.let { "湿度 $it" }
    WeatherCardMetric.WIND -> listOfNotNull(windDirection, windDegrees.value("°").takeIf { windDirection == null }, windSpeed.value(" m/s"))
        .joinToString(" ").takeIf(String::isNotEmpty)?.let { "风 $it" }
}

internal fun LegacyWeather.lines(now: Instant, limit: Int, forCard: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(), includeToday: Boolean = true, classic: Boolean = false): List<WeatherLine> {
    val today = now.atZone(zone).toLocalDate()
    return forecast.asSequence().mapNotNull {
        val start = Instant.ofEpochMilli(it.timeEpochMillis)
        val local = start.atZone(zone)
        val date = local.toLocalDate()
        if (!includeToday && forecastType == "daily" && date == today) return@mapNotNull null
        val label = when (forecastType) {
            "hourly" -> if (start.plusSeconds(3600) <= now) return@mapNotNull null else local.format(hourFormat)
            "twice_daily" -> {
                if (forCard && WeatherPeriod(start = start, isDaytime = it.isDaytime)
                        .haForecastEnd(WeatherBlock.TWICE_DAILY, zone)?.isAfter(now) != true) return@mapNotNull null
                dateLabel(date) + when (it.isDaytime) { true -> " 白天"; false -> " 夜间"; null -> "" }
            }
            else -> {
                if (forCard && date < today) return@mapNotNull null
                if (forCard) cardDayLabel(date, today) else dateLabel(date)
            }
        }
        if (!forCard && entityId != null && date < today) return@mapNotNull null
        WeatherLine(label, sky(it.condition, if (classic) legacyConditionText(it.condition) else null, it.isDaytime, classic),
            when {
                forecastType != "daily" -> it.forecastTemperature.value(temperatureUnit)
                forCard -> cardRange(it.low, it.high, temperatureUnit)
                else -> range(it.low, it.high, temperatureUnit)
            }, it.rainProbability.value("%")?.let { probability -> "降水 $probability" },
            date.takeIf { forCard && forecastType == "daily" }?.let(::dateLabel))
    }.take(limit).toList()
}
