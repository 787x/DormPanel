package com.dormpanel.app.weather

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WeatherCenterPresentationTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test fun oldPlacesKeepIdentityAndOrderThroughFavoriteAndReopen() {
        val source = WeatherSourceRef.QWeather(WeatherCoordinates("30.5928123456789".toBigDecimal(), "114.305391".toBigDecimal()))
        val old = """[{"id":"wuhan","name":"武汉","zone":"Asia/Shanghai","sources":[{"kind":"qw","lat":"30.5928123456789","lon":"114.305391"}]}]"""
        var disk = old
        val store = WeatherPlaceStore(old) { disk = it; true }
        val wuhan = checkNotNull(store.get("wuhan"))
        assertFalse(wuhan.favorite)
        assertEquals(listOf(source), wuhan.sources)
        store.save(wuhan.copy(id = "second"))
        store.save(wuhan.copy(id = "third"))
        store.move("third", -1)
        store.save(wuhan.copy(name = "武汉市", favorite = true))
        val reopened = WeatherPlaceStore(disk) { false }
        assertEquals(listOf("wuhan", "third", "second"), reopened.orderedPlaces.map { it.id })
        assertEquals(listOf(source), reopened.get("wuhan")?.sources)
        assertTrue(reopened.get("wuhan")!!.favorite)
        assertEquals("武汉市", reopened.get("wuhan")?.name)
        assertThrows(IllegalStateException::class.java) { reopened.move("second", -1) }
        assertEquals(listOf("wuhan", "third", "second"), reopened.orderedPlaces.map { it.id })
    }

    @Test fun selectionKeepsExplicitSourceAndRecoversAfterDeletion() {
        val ha = WeatherSourceRef.HomeAssistant("weather.wuhan", "server")
        val city = WeatherSourceRef.QWeatherCity("101200101")
        val store = WeatherPlaceStore("[]") { true }
        val wuhan = WeatherPlace("wuhan", "武汉", shanghai, listOf(city, ha))
        store.save(wuhan)
        store.save(wuhan.copy(id = "other", sources = listOf(city), favorite = true))
        val entry = WeatherCenterSelection(wuhan.id, ha)
        assertEquals(entry, entry.resolve(store))
        assertEquals(WeatherCenterSelection("other", city), WeatherCenterSelection("other", ha).resolve(store))
        assertEquals(listOf(city, ha), store.get("wuhan")?.sources)
        store.save(wuhan.copy(sources = listOf(city)))
        assertEquals(WeatherCenterSelection("wuhan", city), entry.resolve(store))
        store.delete("wuhan")
        assertEquals(WeatherCenterSelection("other", city), entry.resolve(store))
        assertTrue(WeatherCenterSelection(legacy = true).resolve(store).legacy)
    }

    @Test fun repeatedLocalHoursStayDistinctAndMissingReadingsStayMissing() {
        val entries = listOf(forecast("2026-11-01T04:00:00Z", 10.0), forecast("2026-11-01T05:00:00Z", null),
            forecast("2026-11-01T06:00:00Z", 0.0), forecast("2026-11-01T08:00:00Z", 4.0))
        val now = Instant.parse("2026-11-01T04:30:00Z")
        val points = hourlyTrend(entries.reversed(), ZoneId.of("America/New_York"), now, 24)
        assertEquals(listOf("11/1 00:00", "01:00 -04:00", "01:00 -05:00", "03:00"), points.map { it.label })
        assertEquals(listOf(10.0, null, 0.0, 4.0), points.map { it.value })
        assertEquals(7200, points[3].time.epochSecond - points[2].time.epochSecond)
    }

    @Test fun springTransitionUsesElapsedTimeAndLocalMidnightSeparatesDays() {
        val entries = listOf(forecast("2026-03-08T04:00:00Z", 4.0), forecast("2026-03-08T05:00:00Z", 4.0),
            forecast("2026-03-08T06:00:00Z", 4.0), forecast("2026-03-08T07:00:00Z", 4.0))
        val points = hourlyTrend(entries, ZoneId.of("America/New_York"), entries.first().period.start!!, 24)
        assertEquals(listOf("3/7 23:00", "3/8 00:00", "01:00", "03:00"), points.map { it.label })
        assertEquals(listOf(true, true, false, false), points.map { it.dateBreak })
        assertEquals(3600, points[3].time.epochSecond - points[2].time.epochSecond)
    }

    @Test fun dailyRangeUsesAllDaysAndDailyDateUsesPlaceZone() {
        val date = LocalDate.parse("2026-11-15")
        val rows = (0L..11L).map {
            WeatherForecast(WeatherPeriod(date = date.plusDays(it)),
                WeatherConditions(minTemperature = -2.0 + it, maxTemperature = 6.0 + it))
        }
        val now = Instant.parse("2026-11-14T16:00:00Z")
        assertEquals(rows.take(10), futureDays(rows, shanghai, now))
        assertEquals(rows.drop(1).take(10), futureDays(rows, shanghai, now.plusSeconds(86400)))
        val scale = weatherScale(rows.take(10).flatMap { listOfNotNull(it.conditions.minTemperature, it.conditions.maxTemperature) })!!
        assertEquals(WeatherScale(-2.0, 15.0), scale)
        assertEquals(0f, scale.fraction(-2.0), .001f)
        assertEquals(1f, scale.fraction(15.0), .001f)
        assertNull(weatherScale(emptyList()))
        assertEquals(listOf("温度  0°C"), conditionMetrics(WeatherConditions(temperature = 0.0)))
    }

    @Test fun hourlyMetricsExcludeInvalidReadingsAndKeepRealZero() {
        val rows = listOf(WeatherConditions(temperature = -4.5, precipitationProbability = 18.0,
            precipitation = .3, windSpeed = 7.0, windDirection = "北风"),
            WeatherConditions(precipitationProbability = 105.0, precipitation = -.1, windSpeed = -1.0),
            WeatherConditions(temperature = 0.0, precipitationProbability = 0.0, precipitation = 0.0, windSpeed = 0.0))
        assertEquals(WeatherTrendSeries(listOf(-4.5, null, 0.0), WeatherScale(-5.0, 0.0), "°C"),
            HourlyMetric.TEMPERATURE.series(rows))
        assertEquals(WeatherTrendSeries(listOf(18.0, null, 0.0), WeatherScale(0.0, 100.0), "%"),
            HourlyMetric.RAIN_CHANCE.series(rows))
        assertEquals(WeatherTrendSeries(listOf(.3, null, 0.0), WeatherScale(0.0, .4), " mm", true),
            HourlyMetric.RAIN_AMOUNT.series(rows))
        assertEquals(WeatherTrendSeries(listOf(7.0, null, 0.0), WeatherScale(0.0, 7.0), " m/s"),
            HourlyMetric.WIND.series(rows))
        HourlyMetric.entries.forEach { assertNull(it.series(listOf(WeatherConditions())).scale) }
    }

    @Test fun skyWaitsForDailyAndOnlyRequestsMissingSupplement() {
        val now = Instant.parse("2026-11-14T16:30:00Z")
        val date = LocalDate.parse("2026-11-15")
        val source = WeatherSourceRef.QWeatherCity("101200101")
        val pending = WeatherSnapshot(source, shanghai, WeatherQuery(WeatherBlock.DAILY), WeatherState.LOADING)
        val sun = mapOf(AstronomicalEvent.SUNRISE to date.atTime(6, 10), AstronomicalEvent.SUNSET to date.atTime(17, 15))
        val astro = WeatherAstronomy(date, emptyMap(), "上弦月", emptyList(), sun)
        fun daily(value: WeatherAstronomy) = pending.copy(state = WeatherState.FRESH, data = WeatherData.Forecast(
            listOf(WeatherForecast(WeatherPeriod(date = date), WeatherConditions(), astronomy = value))))
        assertTrue(centerAstronomy(WeatherBlock.SUN, pending, null, now, shanghai).waitingForDaily)
        assertFalse(centerAstronomy(WeatherBlock.MOON, pending, null, now, shanghai).needsSupplement)
        val solar = centerAstronomy(WeatherBlock.SUN, daily(astro), null, now, shanghai)
        assertFalse(solar.needsSupplement)
        assertEquals(sun, solar.daily?.localEvents)
        val moon = centerAstronomy(WeatherBlock.MOON, daily(astro), null, now, shanghai)
        assertFalse(moon.needsSupplement)
        assertEquals("上弦月", moon.daily?.phase)
        assertTrue(centerAstronomy(WeatherBlock.MOON, daily(astro.copy(phase = null)), null, now, shanghai).needsSupplement)
    }

    @Test fun skyPreservesDailyFieldsAndAttributionAcrossSupplementFailureAndSuccess() {
        val now = Instant.parse("2026-11-14T16:30:00Z")
        val date = LocalDate.parse("2026-11-15")
        val source = WeatherSourceRef.QWeatherCity("101200101")
        val sunrise = date.atTime(6, 10)
        val dailyData = WeatherAstronomy(date, emptyMap(), null, emptyList(), mapOf(AstronomicalEvent.SUNRISE to sunrise))
        val daily = WeatherSnapshot(source, shanghai, WeatherQuery(WeatherBlock.DAILY), WeatherState.FRESH,
            WeatherData.Forecast(listOf(WeatherForecast(WeatherPeriod(date = date), WeatherConditions(), astronomy = dailyData))),
            attribution = WeatherAttribution(sources = listOf("每日预报来源")))
        val before = WeatherSnapshot(source, shanghai, WeatherQuery(WeatherBlock.SUN, date = date), WeatherState.UNSUPPORTED)
        val failed = centerAstronomy(WeatherBlock.SUN, daily, before, now, shanghai)
        assertEquals(sunrise, failed.daily?.localEvents?.get(AstronomicalEvent.SUNRISE))
        assertEquals(listOf("日落"), failed.missing)
        assertEquals(WeatherState.UNSUPPORTED, failed.supplementSnapshot?.state)
        val data = dailyData.copy(localEvents = mapOf(AstronomicalEvent.SUNRISE to date.atTime(7, 0),
            AstronomicalEvent.SUNSET to date.atTime(17, 0)))
        val success = before.copy(state = WeatherState.FRESH, data = WeatherData.Astronomy(data),
            attribution = WeatherAttribution(sources = listOf("天文预报来源")))
        val combined = centerAstronomy(WeatherBlock.SUN, daily, success, now, shanghai)
        assertTrue(combined.missing.isEmpty())
        assertEquals(setOf(AstronomicalEvent.SUNSET), combined.supplement?.localEvents?.keys)
        assertEquals(daily.attribution, combined.forecastSnapshot?.attribution)
        assertEquals(success.attribution, combined.supplementSnapshot?.attribution)
        assertNull(centerAstronomy(WeatherBlock.SUN, daily, success, now.plusSeconds(86400), shanghai).supplement)
    }

    private fun forecast(time: String, temperature: Double?) =
        WeatherForecast(WeatherPeriod(start = Instant.parse(time)), WeatherConditions(temperature = temperature))
}
