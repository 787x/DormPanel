package com.dormpanel.app.dashboard.card

import com.dormpanel.app.weather.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WeatherCardPresentationTest {
    private val source = WeatherSourceRef.QWeatherCity("101010100")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val instant = Instant.parse("2026-11-15T17:00:00Z")

    @Test fun configurationRoundTripsAllSourceTypesAndRejectsInvalidPayloads() {
        listOf(source, WeatherSourceRef.QWeather(WeatherCoordinates("3.1E+1".toBigDecimal(), "121.400".toBigDecimal()), "123"),
            WeatherSourceRef.HomeAssistant("weather.home", "server")).forEach {
            val config = WeatherCardConfiguration.Bound("place", it, "阳台", WeatherCardMetric.HUMIDITY)
            assertEquals(config, WeatherCardConfiguration.decode(config.encode()))
            assertEquals(WeatherCardConfiguration.Invalid, WeatherCardConfiguration.decode(config.encode() + "{}"))
        }
        assertEquals(WeatherCardConfiguration.Default, WeatherCardConfiguration.decode(" { } "))
        listOf("", "null", "[]", "{", """{"version":"1"}""", """{"version":2}""",
            """{"version":1,"placeId":"p","source":{"kind":"ha","entity":"light.bad","server":"h"}}""",
            """{"version":1,"placeId":"p","source":{"kind":"qw","lat":"91","lon":"1"}}""",
            """{"version":1,"placeId":"p","source":{"kind":"qw-city","location":"101010100","secret":"ignored"}}""",
            """{"version":1,"placeId":"p","source":{"kind":"qw-city","location":"101010100"},"metric":"AQI"}""")
            .forEach { assertEquals(it, WeatherCardConfiguration.Invalid, WeatherCardConfiguration.decode(it)) }
    }

    @Test fun crossingPlacesResetsAliasButSamePlaceSourceChangeKeepsIt() {
        val original = WeatherCardConfiguration.Bound("wuhan", source, "武汉宿舍", WeatherCardMetric.WIND)
        val ha = WeatherSourceRef.HomeAssistant("weather.home", "server")
        assertEquals(original.copy(source = ha), original.select("wuhan", ha))
        assertEquals(original.copy(placeId = "beijing", displayName = null), original.select("beijing", source))
    }

    @Test fun datesAndTemperatureSemanticsFollowBlockAndPlaceZone() {
        val hour = WeatherForecast(WeatherPeriod(instant), WeatherConditions(code = "150", temperature = 6.0))
        val hourly = snapshot(WeatherBlock.HOURLY, hour)
        assertNull(hourly.today(instant))
        assertEquals(WeatherLine("01:00", WeatherSky(WeatherSymbol.MOON, "晴夜"), "6°", null),
            hourly.forecastLines(instant, 1).single())
        val yesterday = hour.copy(period = WeatherPeriod(date = LocalDate.parse("2026-11-15")))
        val today = WeatherForecast(WeatherPeriod(date = LocalDate.parse("2026-11-16")),
            WeatherConditions(maxTemperature = 13.0), day = hour.copy(conditions = WeatherConditions(code = "100")))
        val daily = snapshot(WeatherBlock.DAILY, yesterday, today)
        assertEquals(today, daily.today(instant))
        assertEquals("11月16日", daily.forecastLines(instant, 3).single().label)
        assertEquals("高 13°", daily.forecastLines(instant, 3).single().temperature)
        val periods = snapshot(WeatherBlock.TWICE_DAILY,
            hour.copy(period = hour.period.copy(isDaytime = false), conditions = WeatherConditions(code = "sunny", temperature = 8.0)),
            hour.copy(period = hour.period.copy(isDaytime = true)))
        assertEquals(listOf("11月16日 夜间", "11月16日 白天"), periods.forecastLines(instant, 3).map { it.label })
        assertEquals(WeatherSymbol.MOON, periods.forecastLines(instant, 3).first().sky.symbol)
        assertEquals("8°", periods.forecastLines(instant, 3).first().temperature)
        assertNull(WeatherConditions().metric(WeatherCardMetric.HUMIDITY))
        assertEquals("湿度 0%", WeatherConditions(humidity = 0.0).metric(WeatherCardMetric.HUMIDITY))
    }

    @Test fun haForecastTimesRespectExplicitEndsAndDaylightSaving() {
        val newYork = ZoneId.of("America/New_York")
        fun time(value: String) = Instant.parse(value)
        fun item(start: String, temperature: Double, end: String? = null, day: Boolean? = null) =
            WeatherForecast(WeatherPeriod(start = time(start), end = end?.let(::time), isDaytime = day),
                WeatherConditions(temperature = temperature))

        val daily = item("2026-11-01T04:00:00Z", 16.0)
        assertEquals(time("2026-11-02T05:00:00Z"), daily.period.haForecastEnd(WeatherBlock.DAILY, newYork))
        val night = item("2026-10-31T22:00:00Z", 7.0, day = false)
        val springNight = item("2026-03-07T23:00:00Z", 8.0, day = false)
        assertEquals(time("2026-11-01T11:00:00Z"), night.period.haForecastEnd(WeatherBlock.TWICE_DAILY, newYork))
        assertEquals(time("2026-03-08T10:00:00Z"), springNight.period.haForecastEnd(WeatherBlock.TWICE_DAILY, newYork))

        val ha = WeatherSourceRef.HomeAssistant("weather.home", "server")
        val split = WeatherSnapshot(ha, newYork, WeatherQuery(WeatherBlock.TWICE_DAILY), WeatherState.FRESH,
            WeatherData.Forecast(listOf(night, night.copy(period = night.period.copy(isDaytime = null)))))
        assertEquals("10月31日 夜间", split.forecastLines(time("2026-11-01T07:00:00Z"), 3).single().label)
        assertTrue(split.forecastLines(time("2026-11-01T11:00:00Z"), 3).isEmpty())

        val earlyHandover = HaWeatherParser.parse(org.json.JSONObject("""{"forecast":[
            {"datetime":"2026-10-31T22:00:00Z","is_daytime":false},
            {"datetime":"2026-11-01T10:00:00Z","is_daytime":true}]
        }"""), newYork, WeatherBlock.TWICE_DAILY).data
        val handover = split.copy(data = earlyHandover)
        assertEquals(listOf("11月1日 白天"), handover.forecastLines(time("2026-11-01T10:00:00Z"), 3).map { it.label })

        val firstHour = item("2026-11-01T05:00:00Z", 6.0)
        val secondHour = item("2026-11-01T06:00:00Z", 7.0, "2026-11-01T06:30:00Z")
        val ambiguous = firstHour.copy(period = WeatherPeriod(localTime = java.time.LocalDateTime.parse("2026-11-01T01:30:00")))
        val invalidEnd = firstHour.copy(period = firstHour.period.copy(end = time("2026-11-01T04:00:00Z")))
        val hourly = split.copy(query = WeatherQuery(WeatherBlock.HOURLY),
            data = WeatherData.Forecast(listOf(firstHour, secondHour, ambiguous, invalidEnd)))
        assertEquals("7°", hourly.forecastLines(time("2026-11-01T06:00:00Z"), 10).single().temperature)
        assertTrue(hourly.forecastLines(time("2026-11-01T06:30:00Z"), 10).isEmpty())
    }

    @Test fun knownAndUnknownConditionsAndConnectionStatesAreDistinct() {
        assertEquals(WeatherSymbol.HAZE, sky("502").symbol)
        assertEquals(WeatherSymbol.FOG, sky("500").symbol)
        assertEquals(WeatherSymbol.STORM, sky("lightning-rainy").symbol)
        assertEquals(WeatherSymbol.UNKNOWN, sky("other").symbol)
        val model = snapshot(WeatherBlock.CURRENT)
        assertEquals("离线 · 已过期", weatherStatus(model.copy(state = WeatherState.STALE, stale = true,
            network = WeatherNetworkStatus.OFFLINE)))
        assertEquals("请求受限", weatherStatus(model.copy(state = WeatherState.ERROR, failureCode = 429)))
        assertEquals("认证或权限失败", weatherStatus(model.copy(state = WeatherState.UNAUTHORIZED)))
    }

    private fun snapshot(block: WeatherBlock, vararg forecasts: WeatherForecast) =
        WeatherSnapshot(source, zone, WeatherQuery(block), WeatherState.FRESH, WeatherData.Forecast(forecasts.toList()))
}
