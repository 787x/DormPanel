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

    @Test fun classicWeatherUsesEnglishConditionsWithSharedForecastDates() {
        val date = instant.atZone(zone).toLocalDate().plusDays(1)
        val forecast = snapshot(WeatherBlock.DAILY,
            WeatherForecast(WeatherPeriod(date = date), WeatherConditions(code = "104", description = "阴")))
        val modern = forecast.forecastLines(instant, 1, forCard = true).single()
        val classic = forecast.forecastLines(instant, 1, forCard = true, classic = true).single()
        assertEquals("明天", modern.label)
        assertEquals("阴", modern.sky.description)
        assertEquals(modern.label, classic.label)
        assertEquals("Overcast", classic.sky.description)
        assertEquals("Sunny", sky("100", "晴", classic = true).description)
        assertEquals("Partly cloudy", sky("partlycloudy", classic = true).description)
        assertEquals("Light rain", sky("Light rain", classic = true).description)
        assertEquals("Clear", sky("Clear", classic = true).description)
        assertEquals("Heavy rain turning to sleet", sky("rainy", "Heavy rain turning to sleet", classic = true).description)
    }

    @Test fun classicConditionsPreserveIntensityNightAndCompoundWeather() {
        mapOf("101" to "Cloudy", "102" to "Few clouds", "103" to "Partly cloudy",
            "150" to "Clear night", "305" to "Light rain", "306" to "Moderate rain",
            "307" to "Heavy rain", "304" to "Thundershowers with hail", "313" to "Freezing rain",
            "402" to "Heavy snow", "404" to "Sleet", "406" to "Rain and snow showers",
            "500" to "Mist", "501" to "Fog").forEach { (code, expected) ->
            assertEquals(code, expected, sky(code, classic = true).description)
        }
        mapOf("clear-night" to "Clear night", "cloudy" to "Cloudy", "fog" to "Fog", "hail" to "Hail",
            "lightning" to "Thunderstorm", "lightning-rainy" to "Thunderstorm with rain", "partlycloudy" to "Partly cloudy",
            "pouring" to "Heavy rain", "rainy" to "Rain", "snowy" to "Snow", "snowy-rainy" to "Rain and snow",
            "sunny" to "Sunny", "windy" to "Windy", "windy-variant" to "Windy and cloudy",
            "exceptional" to "Exceptional weather").forEach { (code, expected) ->
            assertEquals(code, expected, sky(code, classic = true).description)
        }
        assertEquals("Clear night", sky("150", "晴", classic = true).description)
        assertEquals("Clear night", sky("sunny", daytime = false, classic = true).description)
        assertEquals("Clear night", sky("sunny", "晴", daytime = false, classic = true).description)
        assertEquals("雷暴", sky("lightning-rainy").description)
        assertEquals("雨", sky("307").description)
    }

    @Test fun classicConditionsPreferMeaningfulSourceTextToConflictingCodes() {
        assertEquals("Heavy rain turning to sleet", sky("305", "Heavy rain turning to sleet", classic = true).description)
        assertEquals("Heavy rain", sky("305", "大雨", classic = true).description)
        assertEquals("Sleet", sky(null, "雨夹雪", classic = true).description)
        assertEquals("Thundershowers with hail", sky("unknown", "雷阵雨伴冰雹", classic = true).description)
        assertEquals("局部阵雨", sky("unknown", "局部阵雨", classic = true).description)
        assertEquals("夜间强雷雨伴大风", sky("302", "夜间强雷雨伴大风", classic = true).description)
        assertEquals("雨转冰粒", sky(null, "雨转冰粒", classic = true).description)
        assertEquals("Light rain", sky("305", "未知", classic = true).description)
        assertEquals("Unknown", sky("unknown", " ", classic = true).description)
    }

    @Test fun classicConditionsOnlyUseCodeDetailsCompatibleWithDescription() {
        data class Case(val code: String?, val text: String?, val expected: String, val daytime: Boolean? = null)
        listOf(
            Case("304", "雷阵雨", "Thundershowers with hail"),
            Case("304", "雷阵雨伴有冰雹", "Thundershowers with hail"),
            Case("307", "雨", "Heavy rain"),
            Case("307", "Rain", "Heavy rain"),
            Case("307", "大雨", "Heavy rain"),
            Case("100", "Clear", "Sunny"),
            Case("402", "雪", "Heavy snow"),
            Case("150", "晴", "Clear night"),
            Case("clear-night", "晴", "Clear night"),
            Case("sunny", null, "Clear night", daytime = false),
            Case("100", "晴", "Clear night", daytime = false),
            Case(null, "晴", "Sunny"),
            Case(null, "多云", "Cloudy"),
            Case("153", "Partly cloudy", "Partly cloudy at night"),
            Case("351", "阵雨", "Heavy rain showers at night"),
            Case("456", "雨雪天气", "Rain and snow showers at night"),
            Case("lightning-rainy", "雷暴", "Thunderstorm with rain"),
            Case("windy-variant", "Windy", "Windy and cloudy"),
            Case("305", "小雨", "Light rain"),
            Case("305", "大雨", "Heavy rain"),
            Case("307", "小雨", "Light rain"),
            Case("314", "小雨", "Light rain"),
            Case("snowy", "Rain", "Rain"),
            Case("rainy", "Heavy rain turning to sleet", "Heavy rain turning to sleet"),
            Case("302", "夜间强雷雨伴大风", "夜间强雷雨伴大风"),
            Case("unknown", "局部阵雨", "局部阵雨"),
            Case(null, "雨夹雪", "Sleet"),
            Case(null, " ", "Unknown"),
        ).forEach { case ->
            assertEquals(case.toString(), case.expected, sky(case.code, case.text, case.daytime, classic = true).description)
        }
    }

    @Test fun classicCurrentAndForecastsUseCompatibleDetailsForBothProviders() {
        val start = instant.plusSeconds(3600)
        val period = WeatherPeriod(start = start, date = start.atZone(zone).toLocalDate(), isDaytime = false)
        val ha = WeatherSourceRef.HomeAssistant("weather.home", "server")
        for ((provider, conditions, expected) in listOf(
            Triple(source, WeatherConditions(code = "304", description = "雷阵雨"), "Thundershowers with hail"),
            Triple(ha, WeatherConditions(code = "clear-night", description = "晴"), "Clear night"),
        )) {
            val current = sky(conditions.code, conditions.description, period.isDaytime, classic = true)
            assertEquals(expected, current.description)
            for (block in listOf(WeatherBlock.DAILY, WeatherBlock.HOURLY, WeatherBlock.TWICE_DAILY)) {
                val forecast = snapshot(block, WeatherForecast(period, conditions)).copy(source = provider)
                val classic = forecast.forecastLines(instant, 1, forCard = true, classic = true).single()
                val modern = forecast.forecastLines(instant, 1, forCard = true).single()
                assertEquals(block.name, current, classic.sky)
                assertEquals(conditions.description, modern.sky.description)
                assertEquals(modern.label, classic.label)
            }
        }
    }

    @Test fun classicForecastsUseSameDescriptionRulesAcrossBlocksAndLegacyData() {
        val date = instant.atZone(zone).toLocalDate().plusDays(1)
        val forecast = WeatherForecast(WeatherPeriod(start = instant.plusSeconds(3600), date = date),
            WeatherConditions(description = "夜间强雷雨伴大风"),
            day = WeatherForecast(WeatherPeriod(date = date), WeatherConditions(code = "302")))
        val currentText = sky(null, forecast.conditions.description, classic = true).description
        listOf(WeatherBlock.DAILY, WeatherBlock.HOURLY, WeatherBlock.TWICE_DAILY).forEach { block ->
            val lines = snapshot(block, forecast).forecastLines(instant, 1, forCard = true, classic = true)
            assertEquals(block.name, currentText, lines.single().sky.description)
        }
        assertEquals("雷暴", snapshot(WeatherBlock.DAILY, forecast).forecastLines(instant, 1, forCard = true).single().sky.description)
        val legacy = com.dormpanel.app.data.FakeDashboardDataSource().state.weather.copy(
            forecast = listOf("局部阵雨", "Clear", "Light rain", "clear-night").mapIndexed { index, condition ->
                com.dormpanel.app.data.WeatherForecast(date.plusDays(index.toLong()).atStartOfDay(zone).toInstant().toEpochMilli(),
                    condition, 17, 24)
            },
        )
        assertEquals(listOf("局部阵雨", "Clear", "Light rain", "Clear night"),
            legacy.lines(instant, 4, forCard = true, zone = zone, classic = true).map { it.sky.description })
    }

    @Test fun cardRangesKeepUnitsMissingSidesAndDecimals() {
        assertEquals("18–27°C", cardRange(18, 27, "°C"))
        assertEquals("-123.5–105.2°F", cardRange(-123.5, 105.2, "°F"))
        assertEquals("最高 27°C", cardRange(null, 27, "°C"))
        assertEquals("最低 -18°C", cardRange(-18, Double.NaN, "°C"))
        assertNull(cardRange(null, null, "°C"))
        assertEquals("低 18°C / 高 27°C", range(18, 27, "°C"))

        val data = HaWeatherParser.parse(org.json.JSONObject("""{
          "attrs":{"temperature_unit":"°F"},
          "forecast":[{"datetime":"2026-11-17T00:00:00+08:00","temperature":74.5,"templow":34,
          "condition":"partlycloudy","is_daytime":false}]
        }"""), zone, WeatherBlock.DAILY).data
        val ha = snapshot(WeatherBlock.DAILY).copy(source = WeatherSourceRef.HomeAssistant("weather.home", "server"), data = data)
        val line = ha.forecastLines(instant, 1, forCard = true).single()
        assertEquals("明天", line.label)
        assertEquals("34–74.5°F", line.temperature)
        assertEquals(WeatherSymbol.PARTLY_NIGHT, line.sky.symbol)
    }

    @Test fun cardDatesFollowThePlacesMidnightAndDoNotDependOnForecastIndex() {
        val newYork = ZoneId.of("America/New_York")
        val beforeMidnight = Instant.parse("2026-11-01T03:59:59Z") // Saturday in New York; Sunday at the device in China.
        val midnight = nextWeatherMidnight(beforeMidnight, newYork)
        assertEquals(Instant.parse("2026-11-01T04:00:00Z"), midnight)
        assertEquals(Instant.parse("2026-11-02T05:00:00Z"), nextWeatherMidnight(midnight, newYork))
        fun day(date: String) = WeatherForecast(WeatherPeriod(date = LocalDate.parse(date)),
            WeatherConditions(code = "unmapped", minTemperature = 18.0, maxTemperature = 27.0))
        val qweather = snapshot(WeatherBlock.DAILY, day("2026-11-01"), day("2026-11-03")).copy(zone = newYork)
        val before = qweather.forecastLines(beforeMidnight, 3, forCard = true)
        assertEquals(listOf("明天", "周二"), before.map { it.label })
        assertEquals("11月1日", before.first().secondaryDate)
        assertEquals("18–27°C", before.first().temperature)
        assertEquals(WeatherSymbol.UNKNOWN, before.first().sky.symbol)
        assertEquals(listOf("今天", "周二"), qweather.forecastLines(midnight, 3, forCard = true).map { it.label })
        val single = qweather.forecastLines(midnight, 3, includeToday = false, forCard = true).single()
        assertEquals("周二", single.label)
        assertEquals("明天", qweather.forecastLines(nextWeatherMidnight(midnight, newYork), 3, forCard = true).single().label)
    }

    @Test fun cardHourlyAndSplitForecastsKeepTheirPeriodAndNightIcon() {
        val current = WeatherForecast(WeatherPeriod(start = instant, isDaytime = false),
            WeatherConditions(code = "100", temperature = 8.5))
        val hourly = snapshot(WeatherBlock.HOURLY, current).forecastLines(instant, 1, forCard = true).single()
        assertEquals("01:00", hourly.label)
        assertEquals("8.5°C", hourly.temperature)
        assertNull(hourly.secondaryDate)
        val split = snapshot(WeatherBlock.TWICE_DAILY, current).forecastLines(instant, 1, forCard = true).single()
        assertEquals("11月16日 夜间", split.label)
        assertEquals(WeatherSymbol.MOON, split.sky.symbol)
        assertNull(split.secondaryDate)
    }

    @Test fun demoAndLegacyHaRetainRealForecastDatesAndOriginalUnits() {
        val date = LocalDate.of(2026, 11, 18)
        val forecast = com.dormpanel.app.data.WeatherForecast(date.atStartOfDay(zone).toInstant().toEpochMilli(), "snowy", -2, 6)
        val demo = com.dormpanel.app.data.WeatherState("clear", 3, 50, null, null, listOf(forecast))
        assertEquals("周三", demo.lines(instant, 1, forCard = true, zone = zone).single().label)
        val ha = demo.copy(entityId = "weather.home", temperatureUnit = "°F")
        assertEquals("-2–6°F", ha.lines(instant, 1, forCard = true, zone = zone).single().temperature)
        assertTrue(demo.lines(date.plusDays(1).atStartOfDay(zone).toInstant(), 1, forCard = true, zone = zone).isEmpty())
        val night = forecast.copy(timeEpochMillis = date.atTime(18, 0).atZone(zone).toInstant().toEpochMilli(),
            condition = "partlycloudy", isDaytime = false, forecastTemperature = 31)
        val legacyNight = ha.copy(forecastType = "twice_daily", forecast = listOf(night))
        val nextDay = date.plusDays(1).atStartOfDay(zone).toInstant()
        val nightLine = legacyNight.lines(nextDay, 1, forCard = true, zone = zone, includeToday = false).single()
        assertEquals(WeatherSymbol.PARTLY_NIGHT, nightLine.sky.symbol)
        assertEquals("11月18日 夜间", nightLine.label)
        assertTrue(legacyNight.lines(nextDay.plusSeconds(12 * 3600), 1, forCard = true, zone = zone, includeToday = false).isEmpty())

        val hourly = legacyNight.copy(forecastType = "hourly", forecast = listOf(night.copy(timeEpochMillis = nextDay.toEpochMilli())))
        assertEquals("00:00", hourly.lines(nextDay, 1, forCard = true, zone = zone, includeToday = false).single().label)
        assertTrue(hourly.lines(nextDay.plusSeconds(3600), 1, forCard = true, zone = zone, includeToday = false).isEmpty())
    }

    @Test fun legacyDailyCardSkipsTodayAndUsesActualDatesAcrossLocalMidnight() {
        val newYork = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 10, 31)
        val midnight = date.plusDays(1).atStartOfDay(newYork).toInstant()
        val beforeMidnight = midnight.minusSeconds(1)
        val days = (-1L..3L).map { offset ->
            val start = date.plusDays(offset).atStartOfDay(newYork).toInstant()
            com.dormpanel.app.data.WeatherForecast(start.toEpochMilli(), "sunny", 18, 27)
        }
        val demo = com.dormpanel.app.data.WeatherState("sunny", 22, 60, 18, 27, days)
        for (source in listOf(demo, demo.copy(entityId = "weather.home"))) {
            fun cardLines(now: Instant, forecast: List<com.dormpanel.app.data.WeatherForecast> = days) =
                source.copy(forecast = forecast).lines(now, 3, forCard = true, zone = newYork, includeToday = false)

            val before = cardLines(beforeMidnight)
            assertEquals(listOf("明天", "周一", "周二"), before.map { it.label })
            assertEquals(listOf("11月1日", "11月2日", "11月3日"), before.map { it.secondaryDate })
            assertEquals(listOf("明天", "周二"), cardLines(midnight).map { it.label })
            assertEquals(listOf("周一", "周二"), cardLines(beforeMidnight, days.filterIndexed { index, _ -> index != 2 }).map { it.label })
            assertTrue(cardLines(beforeMidnight, listOf(days[1])).isEmpty())

            val details = source.copy(forecast = listOf(days[1])).lines(beforeMidnight, 1, zone = newYork).single()
            assertEquals("10月31日", details.label)
            assertEquals("低 18°C / 高 27°C", details.temperature)
        }
    }

    private fun snapshot(block: WeatherBlock, vararg forecasts: WeatherForecast) =
        WeatherSnapshot(source, zone, WeatherQuery(block), WeatherState.FRESH, WeatherData.Forecast(forecasts.toList()))
}
