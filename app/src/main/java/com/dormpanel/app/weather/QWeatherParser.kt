package com.dormpanel.app.weather

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

internal fun JSONObject.text(key: String) = opt(key)?.let { it as? String ?: (it as? Number)?.toString() }
    ?.takeIf { it.isNotBlank() }
internal fun JSONObject.number(key: String) = text(key)?.toDoubleOrNull()?.takeIf(Double::isFinite)
internal fun JSONObject.objects(key: String) = optJSONArray(key)?.objects().orEmpty()
internal fun JSONArray.objects() = (0 until length()).mapNotNull(::optJSONObject)
internal fun JSONObject.strings(key: String) = optJSONArray(key)?.let { array ->
    (0 until array.length()).mapNotNull { array.opt(it) as? String }
}.orEmpty()
internal fun JSONObject.time(key: String): Instant? = text(key)?.let {
    runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
}
internal fun JSONObject.date(key: String): LocalDate? = text(key)?.let {
    runCatching { LocalDate.parse(it) }.getOrNull()
}

internal data class ParsedWeather(
    val data: WeatherData?,
    val attribution: WeatherAttribution = WeatherAttribution(),
    val dataAt: Instant? = null,
    val tag: String? = null,
)

internal object QWeatherParser {
    fun parse(json: JSONObject, zone: ZoneId, query: WeatherQuery): ParsedWeather {
        val metadata = json.optJSONObject("metadata")
        val refer = json.optJSONObject("refer")
        val attribution = WeatherAttribution(
            metadata?.strings("attributions").orEmpty(),
            (listOf("和风天气 https://www.qweather.com/") + refer?.strings("sources").orEmpty()).distinct(),
            refer?.strings("license").orEmpty(), json.text("fxLink"),
        )
        val data = when (query.block) {
            WeatherBlock.CURRENT -> json.takeIf {
                it.has("condition") || it.has("validTime") || it.has("temperature") || it.has("humidity")
            }?.let { WeatherData.Current(conditions(it)) }
            WeatherBlock.HOURLY, WeatherBlock.DAILY -> {
                val key = if (query.block == WeatherBlock.HOURLY) "hours" else "days"
                json.objects(key).takeIf { it.isNotEmpty() }?.let { WeatherData.Forecast(it.map { row -> forecast(row, zone) }) }
            }
            WeatherBlock.ALERTS -> when {
                metadata?.optBoolean("zeroResult") == true || json.text("code") == "204" -> WeatherData.Alerts(emptyList())
                json.optJSONArray("alerts") != null -> WeatherData.Alerts(json.objects("alerts").map(::alert))
                else -> null
            }
            WeatherBlock.AIR_CURRENT -> listOf(air(json, zone))
                .takeIf { it.any { row -> row.indexes.isNotEmpty() || row.pollutants.isNotEmpty() || row.stations.isNotEmpty() } }
                ?.let(WeatherData::Air)
            WeatherBlock.AIR_HOURLY, WeatherBlock.AIR_DAILY -> {
                val key = if (query.block == WeatherBlock.AIR_HOURLY) "hours" else "days"
                json.objects(key).takeIf { it.isNotEmpty() }?.let { WeatherData.Air(it.map { row -> air(row, zone) }) }
            }
            WeatherBlock.MINUTELY -> json.objects("minutely").map {
                MinutePrecipitation(it.time("fxTime"), it.number("precip"), it.text("type"))
            }.takeIf { it.isNotEmpty() || json.text("summary") != null }?.let { WeatherData.Minutely(json.text("summary"), it) }
            WeatherBlock.INDICES -> json.objects("daily").map {
                LifestyleIndex(it.date("date"), it.text("type"), it.text("name"), it.text("level"), it.text("category"), it.text("text"))
            }.takeIf { it.isNotEmpty() }?.let(WeatherData::Indices)
            WeatherBlock.SUN, WeatherBlock.MOON -> json.takeIf { row ->
                events.any { (_, key) -> row.has(key) } || row.has("moonPhase")
            }?.let { WeatherData.Astronomy(astronomy(it, query.date)) }
            WeatherBlock.HISTORY -> history(json)
            WeatherBlock.TWICE_DAILY -> null
        }
        return ParsedWeather(data, attribution, json.time("validTime") ?: json.time("updateTime")
            ?: (data as? WeatherData.Alerts)?.entries?.mapNotNull { it.issuedAt }?.maxOrNull(), metadata?.text("tag"))
    }

    private fun measure(json: JSONObject?, key: String, unit: String) =
        json?.optJSONObject(key)?.takeIf { it.text("unit") == unit }?.number("value")

    private fun fraction(json: JSONObject?, key: String) = json?.number(key)?.takeIf { it in 0.0..1.0 }?.times(100)
    private fun conditions(row: JSONObject): WeatherConditions {
        val condition = row.optJSONObject("condition")
        val wind = row.optJSONObject("wind")
        val direction = wind?.optJSONObject("direction")
        val precipitation = row.optJSONObject("precipitation")
        return WeatherConditions(
            code = condition?.text("code"), description = condition?.text("text"),
            temperature = measure(row, "temperature", "°C"), feelsLike = measure(row, "feelsLike", "°C"),
            maxTemperature = measure(row, "temperatureMax", "°C"), minTemperature = measure(row, "temperatureMin", "°C"),
            meanTemperature = measure(row, "temperatureAvg", "°C"), humidity = fraction(row, "humidity"),
            dewPoint = measure(row, "dewPoint", "°C"), windDirection = direction?.text("compass"),
            windDegrees = direction?.number("degree"), windSpeed = measure(wind, "speed", "m/s"),
            windScale = wind?.number("scale"),
            gust = measure(row, "windGust", "m/s") ?: measure(row, "windGustMax", "m/s"),
            pressure = measure(row, "pressure", "hPa"), visibility = measure(row, "visibility", "m"),
            cloudCover = fraction(row, "cloudCover"), uvIndex = row.number("uvIndex") ?: row.number("uvIndexMax"),
            precipitation = measure(precipitation, "amount", "mm"),
            precipitationProbability = fraction(precipitation, "probability"),
            precipitationIntensity = measure(precipitation, "intensity", "mm/h"),
            precipitationType = precipitation?.text("type"),
        )
    }

    private fun period(row: JSONObject, zone: ZoneId): WeatherPeriod {
        val dailyStart = row.time("forecastStartTime")
        return WeatherPeriod(dailyStart ?: row.time("forecastTime"), row.time("forecastEndTime"),
            date = dailyStart?.atZone(zone)?.toLocalDate())
    }

    private fun forecast(row: JSONObject, zone: ZoneId, daytime: Boolean? = null): WeatherForecast {
        val period = period(row, zone).copy(isDaytime = daytime)
        return WeatherForecast(period, conditions(row),
            row.optJSONObject("daytime")?.let { forecast(it, zone, true) },
            row.optJSONObject("nighttime")?.let { forecast(it, zone, false) },
            row.optJSONObject("astro")?.let { astronomy(it, period.date) })
    }

    private val events = AstronomicalEvent.entries.zip(listOf("sunrise", "sunset", "astronomicalDawn",
        "nauticalDawn", "civilDawn", "astronomicalDusk", "nauticalDusk", "civilDusk", "solarNoon", "solarMidnight",
        "moonrise", "moonset", "moonTransit", "moonUnderfoot"))

    private fun astronomy(row: JSONObject, date: LocalDate?) = WeatherAstronomy(date,
        events.mapNotNull { (event, key) -> row.time(key)?.let { event to it } }.toMap(),
        row.text("moonPhase"), row.objects("moonPhase").map {
            MoonPhase(it.time("fxTime"), it.number("value"), it.text("name"), it.text("icon"), it.number("illumination"))
        }, events.mapNotNull { (event, key) ->
            if (date == null) null else row.text(key)?.let {
                runCatching { event to date.atTime(LocalTime.parse(it)) }.getOrNull()
            }
        }.toMap())

    private fun JSONObject.color() = optJSONObject("color")?.let {
        WeatherColor(it.text("red")?.toIntOrNull(), it.text("green")?.toIntOrNull(), it.text("blue")?.toIntOrNull(),
            it.number("alpha"), it.text("code"))
    }

    private fun pollutant(row: JSONObject): AirPollutant {
        val concentration = row.optJSONObject("concentration")
        return AirPollutant(row.text("code"), row.text("name"), row.text("fullName"), concentration?.number("value"),
            concentration?.text("unit"), row.objects("subIndexes").map {
                AirSubIndex(it.text("code"), it.number("aqi"), it.text("aqiDisplay"))
            })
    }

    private fun air(row: JSONObject, zone: ZoneId) = AirQuality(period(row, zone), row.objects("indexes").map {
        val health = it.optJSONObject("health")
        val advice = health?.optJSONObject("advice")
        AirIndex(it.text("code"), it.text("name"), it.number("aqi"), it.text("aqiDisplay"), it.text("level"),
            it.text("category"), it.color(), it.optJSONObject("primaryPollutant")?.let(::pollutant),
            health?.let { h -> AirHealth(h.text("effect"), advice?.text("generalPopulation"), advice?.text("sensitivePopulation")) })
    }, row.objects("pollutants").map(::pollutant), row.objects("stations").map { AirStation(it.text("id"), it.text("name")) })

    private fun alert(row: JSONObject): WeatherAlert {
        val type = row.optJSONObject("messageType")
        val event = row.optJSONObject("eventType")
        return WeatherAlert(row.text("id"), row.text("senderName"), row.time("issuedTime"), row.time("effectiveTime"),
            row.time("onsetTime"), row.time("expireTime"), type?.text("code"), type?.strings("supersedes").orEmpty(),
            event?.text("code"), event?.text("name"), row.text("severity"), row.text("urgency"), row.text("certainty"),
            row.text("icon"), row.color(), row.text("headline"), row.text("description"), row.text("criteria"),
            row.strings("responseTypes"), row.text("instruction"), row.text("latestChange"))
    }

    private fun history(json: JSONObject): WeatherData.History? {
        val daily = json.optJSONObject("weatherDaily")?.let {
            val date = it.date("date")
            WeatherForecast(WeatherPeriod(date = date), legacyConditions(it), astronomy = astronomy(it, date))
        }
        val hourly = json.objects("weatherHourly").map {
            val local = it.text("time")?.let { value -> runCatching { LocalDateTime.parse(value.replace(' ', 'T')) }.getOrNull() }
            WeatherForecast(WeatherPeriod(localTime = local, date = local?.toLocalDate()), legacyConditions(it))
        }
        return if (daily == null && hourly.isEmpty()) null else WeatherData.History(daily, hourly)
    }

    private fun legacyConditions(row: JSONObject) = WeatherConditions(
        code = row.text("icon"), description = row.text("text"), temperature = row.number("temp"),
        maxTemperature = row.number("tempMax"), minTemperature = row.number("tempMin"),
        humidity = row.number("humidity")?.takeIf { it in 0.0..100.0 }, windDirection = row.text("windDir"),
        windDegrees = row.number("wind360"), windSpeed = row.number("windSpeed")?.div(3.6),
        windScale = row.number("windScale"), pressure = row.number("pressure"), precipitation = row.number("precip"),
    )
}
