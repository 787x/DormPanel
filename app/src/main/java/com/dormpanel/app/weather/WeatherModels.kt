package com.dormpanel.app.weather

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class WeatherCoordinates(val latitude: BigDecimal, val longitude: BigDecimal) {
    init {
        require(latitude.abs() <= BigDecimal(90) && longitude.abs() <= BigDecimal(180))
        require(latitude.scale() <= 20 && longitude.scale() <= 20)
    }

    private fun normalized(value: BigDecimal) =
        value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    internal val path get() = "${normalized(latitude)}/${normalized(longitude)}"
    internal val query get() = "${normalized(longitude)},${normalized(latitude)}"
}

sealed interface WeatherSourceRef {
    data class QWeather(val coordinates: WeatherCoordinates, val locationId: String? = null) : WeatherSourceRef {
        init { require(locationId == null || locationId.matches(Regex("[A-Za-z0-9]{1,40}"))) }
    }
    /** A Location ID distributed by the public QWeather LocationList. */
    data class QWeatherCity(val locationId: String) : WeatherSourceRef {
        init { require(locationId.matches(Regex("[0-9]{9}"))) }
    }
    data class HomeAssistant(val entityId: String, val serverId: String) : WeatherSourceRef {
        init { require(entityId.matches(Regex("weather\\.[a-z0-9_]+")) && serverId.isNotBlank()) }
    }
}

data class WeatherPlace(
    val id: String,
    val name: String,
    val zone: ZoneId,
    val sources: List<WeatherSourceRef>,
) {
    init { require(id.isNotBlank() && name.isNotBlank() && sources.isNotEmpty()) }
}

enum class WeatherBlock(val minutes: Long, val core: Boolean = false) {
    CURRENT(15, true), HOURLY(45, true), DAILY(120, true), TWICE_DAILY(120),
    ALERTS(10), AIR_CURRENT(45), AIR_HOURLY(60), AIR_DAILY(480), MINUTELY(5),
    INDICES(360), SUN(1440), MOON(1440), HISTORY(1440)
}

data class WeatherQuery(
    val block: WeatherBlock,
    val hours: Int = 24,
    val days: Int = 7,
    val date: LocalDate? = null,
    val language: String = "zh",
    val indexTypes: Set<Int> = setOf(1, 2, 3),
    val indexDays: Int = 1,
) {
    init {
        require(hours in 1..240 && days in 1..10 && indexDays in listOf(1, 3))
        require(language.matches(Regex("[a-zA-Z0-9-]{1,20}")))
        require(indexTypes.isNotEmpty() && indexTypes.all { it in 0..99 })
    }
}

enum class WeatherPersistence { NONE, SESSION_ONLY, SAVING, SAVED, FAILED }

enum class WeatherNetworkStatus { UNKNOWN, ONLINE, OFFLINE, NOT_APPLICABLE }

enum class WeatherCacheClearResult { CLEARED, SHARED_RETAINED, FAILED }

enum class WeatherState {
    UNLOADED, LOADING, FRESH, STALE, EMPTY, UNSUPPORTED, UNCONFIGURED, UNAUTHORIZED, INVALID_REQUEST, BUDGET, ERROR
}

data class WeatherCapabilities(val blocks: Map<WeatherBlock, WeatherState>)

data class WeatherAttribution(
    val statements: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val licenses: List<String> = emptyList(),
    val link: String? = null,
)

data class WeatherSnapshot(
    val source: WeatherSourceRef,
    val zone: ZoneId,
    val query: WeatherQuery,
    val state: WeatherState,
    val data: WeatherData? = null,
    val fetchedAt: Instant? = null,
    val dataAt: Instant? = null,
    val expiresAt: Instant? = null,
    val stale: Boolean = false,
    val attribution: WeatherAttribution = WeatherAttribution(),
    val error: String? = null,
    val upstreamTag: String? = null,
    val refreshing: Boolean = false,
    val persistence: WeatherPersistence = WeatherPersistence.NONE,
    val unchanged: Boolean = false,
    val network: WeatherNetworkStatus = WeatherNetworkStatus.UNKNOWN,
    val failureCode: Int? = null,
)

data class WeatherPeriod(
    val start: Instant? = null,
    val end: Instant? = null,
    val localTime: LocalDateTime? = null,
    val date: LocalDate? = null,
    val isDaytime: Boolean? = null,
)

/** °C, m/s, hPa, m, mm, mm/h and percentages on a 0–100 scale. */
data class WeatherConditions(
    val code: String? = null,
    val description: String? = null,
    val temperature: Double? = null,
    val feelsLike: Double? = null,
    val maxTemperature: Double? = null,
    val minTemperature: Double? = null,
    val meanTemperature: Double? = null,
    val humidity: Double? = null,
    val dewPoint: Double? = null,
    val windDirection: String? = null,
    val windDegrees: Double? = null,
    val windSpeed: Double? = null,
    val windScale: Double? = null,
    val gust: Double? = null,
    val pressure: Double? = null,
    val visibility: Double? = null,
    val cloudCover: Double? = null,
    val uvIndex: Double? = null,
    val precipitation: Double? = null,
    val precipitationProbability: Double? = null,
    val precipitationIntensity: Double? = null,
    val precipitationType: String? = null,
    val ozone: Double? = null,
)

data class WeatherForecast(
    val period: WeatherPeriod,
    val conditions: WeatherConditions,
    val day: WeatherForecast? = null,
    val night: WeatherForecast? = null,
    val astronomy: WeatherAstronomy? = null,
)

enum class AstronomicalEvent {
    SUNRISE, SUNSET, ASTRONOMICAL_DAWN, NAUTICAL_DAWN, CIVIL_DAWN,
    ASTRONOMICAL_DUSK, NAUTICAL_DUSK, CIVIL_DUSK, SOLAR_NOON, SOLAR_MIDNIGHT,
    MOONRISE, MOONSET, MOON_TRANSIT, MOON_UNDERFOOT
}

data class WeatherAstronomy(
    val date: LocalDate?,
    val events: Map<AstronomicalEvent, Instant>,
    val phase: String?,
    val hourlyPhases: List<MoonPhase>,
    val localEvents: Map<AstronomicalEvent, LocalDateTime> = emptyMap(),
)
data class MoonPhase(val time: Instant?, val value: Double?, val name: String?, val code: String?, val illumination: Double?)
data class WeatherColor(val red: Int?, val green: Int?, val blue: Int?, val alpha: Double?, val code: String?)
data class AirPollutant(val code: String?, val name: String?, val fullName: String?, val concentration: Double?,
    val unit: String?, val subIndexes: List<AirSubIndex>)
data class AirSubIndex(val code: String?, val aqi: Double?, val display: String?)
data class AirIndex(val code: String?, val name: String?, val aqi: Double?, val display: String?, val level: String?,
    val category: String?, val color: WeatherColor?, val primaryPollutant: AirPollutant?, val health: AirHealth?)
data class AirHealth(val effect: String?, val general: String?, val sensitive: String?)
data class AirQuality(val period: WeatherPeriod, val indexes: List<AirIndex>, val pollutants: List<AirPollutant>,
    val stations: List<AirStation>)
data class AirStation(val id: String?, val name: String?)
data class WeatherAlert(
    val id: String?, val sender: String?, val issuedAt: Instant?, val effectiveAt: Instant?, val onsetAt: Instant?,
    val expiresAt: Instant?, val messageType: String?, val supersedes: List<String>, val eventCode: String?,
    val eventName: String?, val severity: String?, val urgency: String?, val certainty: String?, val icon: String?,
    val color: WeatherColor?, val headline: String?, val description: String?, val criteria: String?,
    val responseTypes: List<String>, val instruction: String?, val latestChange: String?,
)
data class MinutePrecipitation(val time: Instant?, val amountMm: Double?, val type: String?)
data class LifestyleIndex(val date: LocalDate?, val type: String?, val name: String?, val level: String?,
    val category: String?, val description: String?)

sealed interface WeatherData {
    data class Current(val conditions: WeatherConditions) : WeatherData
    data class Forecast(val entries: List<WeatherForecast>) : WeatherData
    data class Alerts(val entries: List<WeatherAlert>, val upcoming: List<WeatherAlert> = emptyList(),
        val changes: List<WeatherAlert> = emptyList()) : WeatherData
    data class Air(val entries: List<AirQuality>) : WeatherData
    data class Minutely(val summary: String?, val entries: List<MinutePrecipitation>) : WeatherData
    data class Indices(val entries: List<LifestyleIndex>) : WeatherData
    data class Astronomy(val value: WeatherAstronomy) : WeatherData
    data class History(val daily: WeatherForecast?, val hourly: List<WeatherForecast>) : WeatherData
}
