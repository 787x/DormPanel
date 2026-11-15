package com.dormpanel.app.weather

import com.dormpanel.app.ha.HaScheduler
import java.time.Instant
import java.time.ZoneId
import java.util.LinkedHashMap

/** Main-thread API. Each subscription owns its demand and must be closed by its consumer. */
class WeatherRepository internal constructor(
    val places: WeatherPlaceStore,
    private val provider: (WeatherSourceRef) -> WeatherProvider,
    private val cache: WeatherCache,
    private val scheduler: HaScheduler,
    private val now: () -> Instant,
) : AutoCloseable {
    interface Subscription : AutoCloseable {
        fun refresh()
    }

    private inner class Demand(
        val placeId: String,
        val source: WeatherSourceRef,
        val query: WeatherQuery,
        val automatic: Boolean,
        val listener: (WeatherSnapshot) -> Unit,
    ) : Subscription {
        var entry: Entry? = null
        var zone: ZoneId? = null
        var boundQuery: WeatherQuery? = null
        var requested = false
        var manualActive = false
        var last: WeatherSnapshot? = null
        override fun refresh() {
            if (this !in demands) return
            requested = true
            reconcile()
        }
        override fun close() {
            if (demands.remove(this)) {
                entry = null
                reconcile()
            }
        }
        fun emit(value: WeatherSnapshot) {
            if (last != value) {
                last = value
                listener(value)
            }
        }
    }

    private class Entry(val plan: WeatherPlan) {
        var diskLoaded = false
        var loadingDisk = false
        var record: WeatherCacheRecord? = null
        val parsed = mutableMapOf<ZoneId, ParsedWeather>()
        var failure: WeatherFailure? = null
        var nextAttempt = Instant.EPOCH
        var running = false
        var inFlight = false
        var cancel: (() -> Unit)? = null
        var generation = 0
        var retryCount = 0
    }

    private val demands = linkedSetOf<Demand>()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var timer: (() -> Unit)? = null
    private var foreground = true
    private var closed = false
    private var reconciling = false
    private var repeat = false
    private val stopPlaces = places.observe(::reconcile)

    fun observe(placeId: String, source: WeatherSourceRef, query: WeatherQuery,
                automatic: Boolean = query.block.core, listener: (WeatherSnapshot) -> Unit): Subscription {
        check(!closed)
        return Demand(placeId, source, query, automatic, listener).also {
            demands += it
            reconcile()
        }
    }

    fun capabilities(placeId: String, source: WeatherSourceRef): WeatherCapabilities {
        val place = places.get(placeId)
        if (place == null || source !in place.sources) return WeatherCapabilities(
            WeatherBlock.entries.associateWith { WeatherState.UNSUPPORTED })
        val instant = now()
        return WeatherCapabilities(WeatherBlock.entries.associateWith { block ->
            val plan = provider(source).plan(source, place.zone, WeatherQuery(block), instant)
            val entry = entries[plan.key]
            plan.unavailable?.state ?: entry?.failure?.state ?: when {
                entry?.inFlight == true -> WeatherState.LOADING
                entry?.record != null -> snapshot(entry, source, place.zone, plan.query, instant).state
                else -> WeatherState.UNLOADED
            }
        })
    }

    fun setForeground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        reconcile()
    }

    internal fun onNetworkAvailable() {
        val earliest = now().plusSeconds(5)
        entries.values.filter { it.failure?.recoverOnNetwork == true && it.retryCount == 1 }.forEach {
            it.nextAttempt = minOf(it.nextAttempt, earliest)
        }
        reconcile()
    }

    internal fun onConfigurationChanged(resetBudget: Boolean = false, resetAuthorization: Boolean = false) {
        entries.values.filter {
            (resetBudget && it.failure?.state == WeatherState.BUDGET) ||
                (resetAuthorization && it.plan.key.startsWith("qw:") && it.failure?.statusCode == 401)
        }.forEach {
            it.failure = null
            it.nextAttempt = Instant.EPOCH
        }
        reconcile()
    }

    fun clearCache(placeId: String? = null) {
        val groups = placeId?.let { id ->
            val place = places.get(id) ?: return
            place.sources.flatMap { source -> WeatherBlock.entries.map { block ->
                provider(source).plan(source, place.zone, WeatherQuery(block), now()).group
            } }.toSet()
        }
        cache.clear(groups)
        // Keep failure throttling and minimum request interval when dropping successful values.
        entries.values.filter { groups == null || it.plan.group in groups }.forEach {
            stop(it)
            it.record = null
            it.parsed.clear()
            it.diskLoaded = true
            it.loadingDisk = false
        }
        reconcile()
    }

    private fun stop(entry: Entry) {
        entry.generation++
        entry.cancel?.invoke()
        entry.cancel = null
        entry.running = false
        entry.inFlight = false
        entry.loadingDisk = false
    }

    private fun reconcile() {
        if (closed) return
        if (reconciling) {
            repeat = true
            return
        }
        reconciling = true
        try {
            do {
                repeat = false
                update()
            } while (repeat && !closed)
        } finally {
            reconciling = false
        }
    }

    private fun update() {
        timer?.invoke()
        timer = null
        val instant = now()
        val active = linkedMapOf<Entry, MutableList<Demand>>()
        demands.toList().forEach { demand ->
            val place = places.get(demand.placeId)
            if (place == null || demand.source !in place.sources) {
                demand.entry = null
                demand.emit(WeatherSnapshot(demand.source, demand.zone ?: java.time.ZoneOffset.UTC, demand.query,
                    WeatherState.UNSUPPORTED, error = "地点或绑定的来源已移除"))
                return@forEach
            }
            demand.zone = place.zone
            val plan = provider(demand.source).plan(demand.source, place.zone, demand.query, instant)
            val entry = entries.getOrPut(plan.key) { Entry(plan) }
            if (demand.entry != entry) demand.manualActive = false
            demand.entry = entry
            demand.boundQuery = plan.query
            if (plan.unavailable == null) active.getOrPut(entry, ::mutableListOf) += demand
            if (plan.unavailable != null) {
                demand.requested = false
                demand.emit(WeatherSnapshot(demand.source, place.zone, plan.query, plan.unavailable.state,
                    error = plan.unavailable.message))
            }
        }

        entries.values.toList().filter { it !in active }.forEach {
            if (it.running || it.loadingDisk) stop(it)
            // Reattaching a live consumer must observe HA state immediately.
            if (it.plan.live) it.nextAttempt = Instant.EPOCH
        }
        if (closed) return
        var next: Instant? = null
        fun wake(at: Instant?) {
            if (at != null && at > instant && (next == null || at < next)) next = at
        }
        var httpSlots = 2 - active.keys.count { it.running && !it.plan.live }
        val ordered = active.entries.sortedByDescending { it.key.plan.query.block.core }
        for ((entry, consumers) in ordered) {
            val eligible = consumers.filter { it in demands }
            val durable = eligible.any { !places.isTemporary(it.placeId) }
            val needed = eligible.any { it.automatic || it.requested || it.manualActive } ||
                (entry.plan.live && entry.running && eligible.isNotEmpty())
            if ((!foreground || !needed) && entry.running) stop(entry)
            if (eligible.isEmpty()) continue
            if (durable && !entry.diskLoaded && !entry.loadingDisk && entry.record == null) load(entry)
            val expiry = eligible.mapNotNull { expiry(entry, checkNotNull(it.zone)) }.minOrNull()
            val onDemand = eligible.any { it.requested }
            val auto = eligible.any { it.automatic } && entry.plan.query.block != WeatherBlock.HISTORY
            val permanent = entry.failure?.state in setOf(WeatherState.UNAUTHORIZED, WeatherState.UNSUPPORTED)
            val due = if (entry.plan.live || onDemand || entry.failure != null) entry.nextAttempt else maxOf(
                entry.nextAttempt, expiry ?: Instant.EPOCH)
            if (foreground && !entry.running && !entry.loadingDisk && (onDemand || auto) && (!permanent || onDemand)) {
                if (instant >= due && (entry.plan.live || httpSlots > 0)) {
                    if (!entry.plan.live) httpSlots--
                    eligible.forEach {
                        it.manualActive = it.requested
                        it.requested = false
                    }
                    start(entry, durable, instant)
                } else wake(due)
            } else if (entry.running) eligible.filter { it.requested }.forEach {
                it.manualActive = true
                it.requested = false
            }
            wake(expiry)
            for (demand in eligible) {
                if (demand !in demands || closed) continue
                val zone = checkNotNull(demand.zone)
                val query = checkNotNull(demand.boundQuery)
                val snapshot = snapshot(entry, demand.source, zone, query, instant)
                demand.emit(snapshot)
                if (query.date != null || query.block in setOf(WeatherBlock.DAILY, WeatherBlock.AIR_DAILY, WeatherBlock.INDICES))
                    wake(instant.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant())
                (entry.parsed[zone]?.data as? WeatherData.Alerts)?.entries?.forEach {
                    wake(it.effectiveAt)
                    wake(it.onsetAt)
                    wake(it.expiresAt)
                }
            }
        }
        if (entries.size > 64) entries.entries.iterator().let { iterator ->
            while (entries.size > 64 && iterator.hasNext()) if (iterator.next().value !in active) iterator.remove()
        }
        if (foreground && !closed) next?.let { at ->
            val delay = (at.toEpochMilli() - now().toEpochMilli()).coerceIn(1, 86_400_000)
            timer = scheduler.after(delay, ::reconcile)
        }
    }

    private fun load(entry: Entry) {
        entry.loadingDisk = true
        val generation = entry.generation
        cache.read(entry.plan.group, entry.plan.key) { record ->
            if (closed || generation != entry.generation) return@read
            entry.loadingDisk = false
            entry.diskLoaded = true
            entry.record = record?.takeIf { it.fetchedAt <= now() }
            reconcile()
        }
    }

    private fun start(entry: Entry, durable: Boolean, instant: Instant) {
        entry.running = true
        entry.inFlight = true
        entry.nextAttempt = instant.plusSeconds(30)
        val generation = ++entry.generation
        entry.cancel = entry.plan.start { result ->
            scheduler.execute {
                if (closed || generation != entry.generation || !entry.running) return@execute
                entry.inFlight = false
                if (!entry.plan.live) {
                    demands.filter { it.entry == entry }.forEach { it.manualActive = false }
                    entry.running = false
                    entry.cancel = null
                }
                val received = now()
                when (result) {
                    is WeatherResult.Data -> {
                        entry.failure = null
                        entry.retryCount = 0
                        val record = WeatherCacheRecord(result.wire, received)
                        val hasData = result.parsed.data != null
                        entry.nextAttempt = received.plusSeconds(if (hasData) 30 else entry.plan.query.block.minutes * 60)
                        if (hasData || entry.record == null) {
                            entry.record = record
                            entry.parsed.clear()
                            result.zone?.let { entry.parsed[it] = result.parsed }
                            if (durable || demands.any { it.entry == entry && !places.isTemporary(it.placeId) })
                                cache.write(entry.plan.group, entry.plan.key, record)
                        } else {
                            entry.failure = WeatherFailure(WeatherState.EMPTY, "服务当前无数据，保留上次结果")
                        }
                    }
                    is WeatherResult.Failed -> {
                        entry.failure = result.failure
                        val seconds = (60L * (1 shl entry.retryCount.coerceAtMost(5)))
                            .coerceAtLeast(entry.plan.query.block.minutes * 60)
                        entry.retryCount++
                        entry.nextAttempt = maxOf(received.plusSeconds(seconds), result.failure.retryAt ?: Instant.MIN)
                    }
                }
                reconcile()
            }
        }
    }

    private fun expiry(entry: Entry, zone: ZoneId): Instant? {
        val record = entry.record ?: return null
        val block = entry.plan.query.block
        val expiresAt = record.fetchedAt.plusSeconds(block.minutes * 60)
        if (block !in setOf(WeatherBlock.DAILY, WeatherBlock.AIR_DAILY, WeatherBlock.INDICES)) return expiresAt
        val nextDay = record.fetchedAt.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        return minOf(expiresAt, nextDay)
    }
    private fun snapshot(entry: Entry, source: WeatherSourceRef, zone: ZoneId,
                         query: WeatherQuery, instant: Instant): WeatherSnapshot {
        val record = entry.record
        val parsed = record?.let { entry.parsed.getOrPut(zone) {
            runCatching { entry.plan.parse(it.wire, zone) }.getOrElse { ParsedWeather(null) }
        } }
        val expiresAt = expiry(entry, zone)
        val stale = record != null && (instant >= checkNotNull(expiresAt) || entry.failure != null)
        var data = parsed?.data
        if (data is WeatherData.Alerts) {
            data = if (stale) null else activeAlerts(data, instant)
        }
        val state = entry.failure?.state ?: when {
            entry.inFlight || entry.loadingDisk -> WeatherState.LOADING
            record == null -> WeatherState.UNLOADED
            stale -> WeatherState.STALE
            data == null -> WeatherState.EMPTY
            else -> WeatherState.FRESH
        }
        return WeatherSnapshot(source, zone, query, state, data, record?.fetchedAt, parsed?.dataAt, expiresAt, stale,
            parsed?.attribution ?: WeatherAttribution(), entry.failure?.message, parsed?.tag, entry.inFlight)
    }

    private fun activeAlerts(data: WeatherData.Alerts, instant: Instant): WeatherData.Alerts {
        val superseded = data.entries.flatMap { it.supersedes }.toSet()
        val candidates = data.entries.filter {
            it.id !in superseded && it.messageType in setOf("alert", "update") && it.urgency != "past" &&
                it.expiresAt?.let { end -> instant < end } != false
        }
        val (active, upcoming) = candidates.partition { it.effectiveAt?.let { start -> instant >= start } != false }
        val changes = data.entries.filter { it.messageType == "cancel" }
        return WeatherData.Alerts(active, upcoming, changes)
    }

    override fun close() {
        if (closed) return
        closed = true
        stopPlaces()
        timer?.invoke()
        demands.clear()
        entries.values.forEach(::stop)
        entries.clear()
    }
}
