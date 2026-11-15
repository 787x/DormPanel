package com.dormpanel.app.weather

import com.dormpanel.app.ha.ManualScheduler
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WeatherRepositoryTest {
    private class Fixture : AutoCloseable {
        val scheduler = ManualScheduler()
        var instant = Instant.parse("2026-11-15T14:59:00Z")
        var account = "one"
        var serializedPlaces = "[]"
        val zone = ZoneId.of("Asia/Tokyo")
        val places = WeatherPlaceStore(serializedPlaces) { serializedPlaces = it; true }
        val cache = MemoryCache()
        val jobs = mutableListOf<Job>()
        val source = source("35.687")
        val other = source("38.25")
        val provider = object : WeatherProvider {
            override fun plan(source: WeatherSourceRef, zone: ZoneId, query: WeatherQuery, now: Instant): WeatherPlan {
                source as WeatherSourceRef.QWeather
                val group = source.coordinates.path
                val key = "$account:$group:${query.block}:${query.language}"
                val parse = { wire: JSONObject, timeZone: ZoneId -> QWeatherParser.parse(wire, timeZone, query) }
                return WeatherPlan(key, group, query, parse, { callback ->
                    val job = Job(key, query.block) { result -> callback(result) }
                    jobs += job
                    val cancel: () -> Unit = { job.cancelled = true }
                    cancel
                })
            }
        }
        var repository = create()

        init {
            save("a", source)
            save("b", source("35.688"))
            save("other", other)
        }

        fun create() = WeatherRepository(places, { provider }, cache, scheduler) { instant }
        fun advance(seconds: Long = 0) {
            instant = instant.plusSeconds(seconds)
            scheduler.advance(seconds * 1000)
        }
        fun save(id: String, source: WeatherSourceRef.QWeather) {
            places.save(WeatherPlace(id, "Same label", zone, listOf(source)))
        }
        fun observe(id: String, block: WeatherBlock, source: WeatherSourceRef = places.get(id)!!.sources.single(),
                    automatic: Boolean = block.core): Pair<WeatherRepository.Subscription, MutableList<WeatherSnapshot>> {
            val updates = mutableListOf<WeatherSnapshot>()
            return repository.observe(id, source, WeatherQuery(block), automatic, updates::add) to updates
        }
        override fun close() = repository.close()
        companion object {
            private fun source(latitude: String) =
                WeatherSourceRef.QWeather(WeatherCoordinates(latitude.toBigDecimal(), "139.6999".toBigDecimal()))
        }
    }

    private class MemoryCache : WeatherCache {
        val values = mutableMapOf<Pair<String, String>, WeatherCacheRecord>()
        override fun read(group: String, key: String, done: (WeatherCacheRecord?) -> Unit) = done(values[group to key])
        override fun write(group: String, key: String, record: WeatherCacheRecord) { values[group to key] = record }
        override fun clear(groups: Set<String>?) { values.keys.removeAll { groups == null || it.first in groups } }
    }

    private class Job(val key: String, val block: WeatherBlock, val callback: (WeatherResult) -> Unit) {
        var cancelled = false
        fun send(json: String) {
            val wire = JSONObject(json)
            callback(WeatherResult.Data(wire, QWeatherParser.parse(wire, ZoneId.of("Asia/Tokyo"), WeatherQuery(block))))
        }
        fun current(value: Int) = send("""{"temperature":{"unit":"°C","value":$value}}""")
        fun fail(state: WeatherState, retry: Instant? = null, network: Boolean = false) =
            callback(WeatherResult.Failed(WeatherFailure(state, "test error", retry, network)))
    }

    private fun temperature(snapshots: List<WeatherSnapshot>): Double? =
        (snapshots.last().data as? WeatherData.Current)?.conditions?.temperature

    @Test fun fiveConsumersShareRoundedCoordinatesAndTwoCitiesAndTypesRemainIsolated() = Fixture().use { f ->
        val observers = List(5) { f.observe(if (it == 0) "b" else "a", WeatherBlock.CURRENT) }
        assertEquals(1, f.jobs.size)
        val (_, other) = f.observe("other", WeatherBlock.CURRENT)
        val (_, hourly) = f.observe("a", WeatherBlock.HOURLY)
        assertEquals(2, f.jobs.size)
        f.jobs[1].current(11)
        f.advance()
        assertEquals(11.0, temperature(other)!!, 0.0)
        val hourlyJob = f.jobs.last()
        assertEquals(WeatherBlock.HOURLY, hourlyJob.block)
        hourlyJob.send("""{"hours":[{"forecastTime":"2026-11-15T15:00Z"}]}""")
        f.jobs[0].current(22)
        f.advance()
        observers.forEach { (_, updates) -> assertEquals(22.0, temperature(updates)!!, 0.0) }
        assertEquals(11.0, temperature(other)!!, 0.0)
        assertEquals(1, (hourly.last().data as WeatherData.Forecast).entries.size)
        assertEquals(3, f.jobs.size)
    }

    @Test fun staleCacheSurvivesRestartAndLateResponsesCannotCrossAccountOrDeletedPlace() = Fixture().use { f ->
        val (_, snapshots) = f.observe("a", WeatherBlock.CURRENT)
        f.jobs.single().current(22)
        f.advance()
        assertEquals(22.0, temperature(snapshots)!!, 0.0)
        f.repository.close()
        f.advance(901)
        f.repository = f.create()
        val (_, restored) = f.observe("a", WeatherBlock.CURRENT)
        assertTrue(restored.any { it.stale && it.data != null })
        val offline = f.jobs.last()
        offline.fail(WeatherState.ERROR, network = true)
        f.advance()
        assertEquals(WeatherState.ERROR, restored.last().state)
        assertEquals(22.0, temperature(restored)!!, 0.0)
        f.repository.onNetworkAvailable()
        f.advance(5)
        val late = f.jobs.last()
        assertNotSame(offline, late)
        f.account = "two"
        f.repository.onConfigurationChanged()
        assertTrue(late.cancelled)
        assertNull(temperature(restored))
        late.current(99)
        f.advance()
        assertNull(temperature(restored))
        val replacement = f.jobs.last()
        assertTrue(replacement.key.startsWith("two:"))
        f.places.delete("a")
        assertTrue(replacement.cancelled)
        replacement.current(98)
        f.advance()
        assertEquals(WeatherState.UNSUPPORTED, restored.last().state)
        assertEquals(1, f.cache.values.size)
    }

    @Test fun alertCancellationEffectiveTimeAndExpiryAreIndependentOfCurrentWeather() = Fixture().use { f ->
        val (subscription, alerts) = f.observe("a", WeatherBlock.ALERTS)
        subscription.refresh()
        f.jobs.last().send("""{"alerts":[
            {"id":"old","messageType":{"code":"alert"}},
            {"id":"c","messageType":{"code":"cancel","supersedes":["old"]}},
            {"id":"new","messageType":{"code":"alert"},"effectiveTime":"2026-11-15T14:59:05Z","expireTime":"2026-11-15T14:59:10Z"}]}""")
        f.advance()
        fun ids() = (alerts.last().data as WeatherData.Alerts).entries.map { it.id }
        assertTrue(ids().isEmpty())
        f.advance(5)
        assertEquals(listOf("new"), ids())
        f.advance(5)
        assertTrue(ids().isEmpty())
        f.advance(600)
        assertNull(alerts.last().data)
        assertEquals(WeatherState.STALE, alerts.last().state)
        assertEquals(1, f.jobs.size)
        subscription.close()
    }

    @Test fun backgroundStopsDemandAndDailyRefreshesAtLocationMidnight() = Fixture().use { f ->
        val (subscription, snapshots) = f.observe("a", WeatherBlock.DAILY)
        f.jobs.single().send("""{"days":[{"forecastStartTime":"2026-11-14T15:00Z"}]}""")
        f.advance()
        assertEquals(Instant.parse("2026-11-15T15:00:00Z"), snapshots.last().expiresAt)
        f.advance(60)
        assertEquals(2, f.jobs.size)
        f.repository.setForeground(false)
        assertTrue(f.jobs.last().cancelled)
        val jobs = f.jobs.size
        f.advance(36000)
        assertEquals(jobs, f.jobs.size)
        f.repository.setForeground(true)
        assertEquals(jobs + 1, f.jobs.size)
        subscription.close()
        assertTrue(f.jobs.last().cancelled)
        f.advance(36000)
        assertEquals(jobs + 1, f.jobs.size)
    }

    @Test fun retryAfterAndTerminalErrorsNeverLoop() = Fixture().use { f ->
        val (subscription, snapshots) = f.observe("a", WeatherBlock.CURRENT)
        f.jobs.single().fail(WeatherState.ERROR, f.instant.plusSeconds(1800))
        f.advance()
        subscription.refresh()
        f.advance(1799)
        assertEquals(1, f.jobs.size)
        f.advance(1)
        assertEquals(2, f.jobs.size)
        f.jobs.last().fail(WeatherState.UNAUTHORIZED)
        f.advance()
        f.advance(43200)
        assertEquals(2, f.jobs.size)
        assertEquals(WeatherState.UNAUTHORIZED, snapshots.last().state)
    }

    @Test fun transientLookupFieldsDoNotEnterPlaceOrWeatherStorage() = Fixture().use { f ->
        val place = WeatherPlace("temporary", "temporary result", f.zone, listOf(f.other))
        f.places.addLookup(place)
        f.observe(place.id, WeatherBlock.CURRENT)
        f.jobs.single().current(18)
        f.advance()
        assertFalse(f.serializedPlaces.contains("temporary"))
        assertTrue(f.cache.values.isEmpty())
        assertNull(WeatherPlaceStore(f.serializedPlaces) { true }.get(place.id))
    }
}
