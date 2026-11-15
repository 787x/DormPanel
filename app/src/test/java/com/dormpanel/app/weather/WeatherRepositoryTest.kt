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
        var restriction: WeatherFailure? = null
        var live = false
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
                }, live = live, restriction = { restriction })
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
        var writable = true
        override fun write(group: String, key: String, record: WeatherCacheRecord, done: (Boolean) -> Unit) {
            if (writable) values[group to key] = record
            done(writable)
        }
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
        assertEquals(WeatherState.STALE, snapshots.last().state)
        assertEquals(1, f.jobs.size)
        f.advance(3540)
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

    @Test fun presetsAndCustomPolicyRescheduleExistingDataWithoutClearingIt() = Fixture().use { f ->
        assertEquals(30L, WeatherRefreshPolicy(WeatherRefreshMode.SAVER).minutes(WeatherBlock.CURRENT))
        assertEquals(15L, WeatherRefreshPolicy().minutes(WeatherBlock.CURRENT))
        assertEquals(10L, WeatherRefreshPolicy(WeatherRefreshMode.TIMELY).minutes(WeatherBlock.CURRENT))
        assertThrows(IllegalArgumentException::class.java) {
            WeatherRefreshPolicy(WeatherRefreshMode.CUSTOM, mapOf(RefreshInterval.CURRENT to 1L))
        }
        val (_, current) = f.observe("a", WeatherBlock.CURRENT)
        f.observe("b", WeatherBlock.CURRENT)
        val (_, hourly) = f.observe("other", WeatherBlock.HOURLY)
        f.jobs[0].current(22)
        f.jobs[1].send("""{"hours":[{"forecastTime":"2026-11-15T15:00Z"}]}""")
        f.advance()
        val initial = f.instant
        f.repository.usePolicy(WeatherRefreshPolicy(WeatherRefreshMode.SAVER))
        val estimate = f.repository.estimatedRequests(WeatherRefreshPolicy(WeatherRefreshMode.SAVER), 30)
        assertEquals(4140 to 1920, estimate)
        assertEquals(initial.plusSeconds(1800), current.last().expiresAt)
        assertEquals(initial.plusSeconds(5400), hourly.last().expiresAt)
        f.advance(900)
        assertEquals(2, f.jobs.size)
        assertEquals(22.0, temperature(current)!!, 0.0)
        f.repository.usePolicy(WeatherRefreshPolicy(WeatherRefreshMode.CUSTOM, mapOf(RefreshInterval.CURRENT to 12L)))
        assertEquals(3, f.jobs.size)
        assertEquals(WeatherBlock.CURRENT, f.jobs.last().block)
        assertEquals(22.0, temperature(current)!!, 0.0)
        assertEquals(initial.plusSeconds(2700), hourly.last().expiresAt)
    }

    @Test fun manualRefreshUsesFreshCacheHonorsCooldownSharesFlightAndRetainsDataOnFailure() = Fixture().use { f ->
        val (a, snapshots) = f.observe("a", WeatherBlock.CURRENT)
        val (b, _) = f.observe("b", WeatherBlock.CURRENT)
        assertEquals(WeatherRefreshStatus.JOINED, b.refresh().status)
        f.jobs.single().current(22)
        f.advance()
        val first = snapshots.last().fetchedAt
        val blocked = a.refresh()
        assertEquals(WeatherRefreshStatus.BLOCKED, blocked.status)
        assertEquals(f.instant.plusSeconds(600), blocked.retryAt)
        f.advance(600)
        assertEquals(WeatherState.FRESH, snapshots.last().state)
        assertEquals(WeatherRefreshStatus.STARTED, a.refresh().status)
        assertEquals(WeatherRefreshStatus.JOINED, b.refresh().status)
        assertEquals(2, f.jobs.size)
        assertTrue(snapshots.last().refreshing)
        assertEquals(22.0, temperature(snapshots)!!, 0.0)
        f.jobs.last().fail(WeatherState.ERROR, network = true)
        f.advance()
        assertEquals(first, snapshots.last().fetchedAt)
        assertEquals(22.0, temperature(snapshots)!!, 0.0)
        assertEquals(WeatherRefreshStatus.BLOCKED, b.refresh().status)
    }

    @Test fun networkRecoveryAfterRepeatedFailuresIsLimitedToStaleCoreDemand() = Fixture().use { f ->
        f.observe("a", WeatherBlock.CURRENT)
        f.observe("other", WeatherBlock.AIR_CURRENT, automatic = true)
        f.jobs.first { it.block == WeatherBlock.AIR_CURRENT }.fail(WeatherState.ERROR, network = true)
        repeat(3) { attempt ->
            f.jobs.last { it.block == WeatherBlock.CURRENT }.fail(WeatherState.ERROR, network = true)
            f.advance()
            if (attempt < 2) f.advance(900)
        }
        val before = f.jobs.size
        repeat(5) { f.repository.onNetworkAvailable() }
        f.advance(5)
        assertEquals(before + 1, f.jobs.size)
        assertEquals(WeatherBlock.CURRENT, f.jobs.last().block)
        f.jobs.last().fail(WeatherState.ERROR, network = true)
        f.advance()
        f.repository.onNetworkAvailable()
        f.advance(5)
        assertEquals(before + 1, f.jobs.size)
    }

    @Test fun recoveryWhileScreenIsOffWaitsForForeground() = Fixture().use { f ->
        f.observe("a", WeatherBlock.CURRENT)
        f.jobs.single().fail(WeatherState.ERROR, network = true)
        f.advance()
        f.repository.setForeground(false)
        f.repository.onNetworkAvailable()
        f.advance(20)
        assertEquals(1, f.jobs.size)
        f.repository.setForeground(true)
        f.advance(5)
        assertEquals(2, f.jobs.size)
    }

    @Test fun equalUpstreamTagReportsUnchangedWhileKeepingDataVisible() = Fixture().use { f ->
        val (watch, snapshots) = f.observe("a", WeatherBlock.CURRENT)
        val payload = """{"temperature":{"value":22,"unit":"°C"},"metadata":{"tag":"same"}}"""
        f.jobs.single().send(payload)
        f.advance()
        assertFalse(snapshots.last().unchanged)
        f.advance(600)
        assertEquals(WeatherRefreshStatus.STARTED, watch.refresh().status)
        f.jobs.last().send(payload)
        f.advance()
        assertTrue(snapshots.last().unchanged)
        assertEquals(22.0, temperature(snapshots)!!, 0.0)
    }

    @Test fun manualAndNetworkRecoveryCannotBypassProviderRestrictions() = Fixture().use { f ->
        val (watch, _) = f.observe("a", WeatherBlock.CURRENT, automatic = false)
        for (error in listOf(
            WeatherFailure(WeatherState.UNAUTHORIZED, "401", statusCode = 401),
            WeatherFailure(WeatherState.UNAUTHORIZED, "403", statusCode = 403),
            WeatherFailure(WeatherState.ERROR, "429", f.instant.plusSeconds(3600), statusCode = 429),
            WeatherFailure(WeatherState.BUDGET, "budget", f.instant.plusSeconds(86400)),
        )) {
            f.restriction = error
            val blocked = watch.refresh()
            assertEquals(WeatherRefreshStatus.BLOCKED, blocked.status)
            assertEquals(error.message, blocked.reason)
            assertEquals(error.retryAt, blocked.retryAt)
            f.repository.onNetworkAvailable()
            f.advance(10)
        }
        assertTrue(f.jobs.isEmpty())
        f.restriction = null
        f.live = true
        f.account = "ha-connection"
        f.repository.onConfigurationChanged()
        assertTrue(watch.refresh().reason!!.contains("订阅"))
        assertTrue(f.jobs.isEmpty())
    }

    @Test fun diskFailurePreservesLatestMemoryValueAndLaterWriteSurvivesRecreation() = Fixture().use { f ->
        val (watch, snapshots) = f.observe("a", WeatherBlock.CURRENT)
        f.cache.writable = false
        f.jobs.single().current(22)
        f.advance()
        assertEquals(22.0, temperature(snapshots)!!, 0.0)
        assertEquals(WeatherPersistence.FAILED, snapshots.last().persistence)
        assertTrue(f.cache.values.isEmpty())
        f.advance(600)
        f.cache.writable = true
        assertEquals(WeatherRefreshStatus.STARTED, watch.refresh().status)
        f.jobs.last().current(23)
        f.advance()
        assertEquals(WeatherPersistence.SAVED, snapshots.last().persistence)
        f.repository.close()
        f.repository = f.create()
        val (_, restored) = f.observe("a", WeatherBlock.CURRENT)
        assertEquals(23.0, temperature(restored)!!, 0.0)
        assertEquals(WeatherPersistence.SAVED, restored.last().persistence)
    }

    @Test fun favoriteReplacesPreviewAtomicallyWithoutStoringRestrictedFieldsAndKeepsItsId() = Fixture().use { f ->
        val restricted = WeatherSourceRef.QWeather(WeatherCoordinates("21.987654".toBigDecimal(), "111.123456".toBigDecimal()))
        val preview = WeatherPlace("temporary", "Geo name", f.zone, listOf(restricted))
        f.places.addLookup(preview)
        assertThrows(IllegalArgumentException::class.java) { f.places.save(preview) }
        val favorite = preview.copy(name = "My place", sources = listOf(WeatherSourceRef.QWeatherCity("101010100")))
        f.places.saveFavorite(favorite)
        assertFalse(f.places.isTemporary(preview.id))
        assertFalse(f.serializedPlaces.contains("Geo name"))
        assertFalse(f.serializedPlaces.contains("21.987654"))
        f.places.save(favorite.copy(name = "My renamed place"))
        val restored = WeatherPlaceStore(f.serializedPlaces) { true }.get(preview.id)
        assertEquals(favorite.copy(name = "My renamed place"), restored)
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
