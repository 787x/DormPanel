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
        fun refresh(): WeatherRefreshResult
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
        var unavailable: WeatherFailure? = null
        override fun refresh(): WeatherRefreshResult {
            val current = entry
            val instant = now()
            fun blocked(reason: String, at: Instant? = null) = WeatherRefreshResult(WeatherRefreshStatus.BLOCKED, reason, at)
            if (this !in demands || current == null) return blocked("地点或来源已移除")
            unavailable?.let { return blocked(it.message, it.retryAt) }
            if (current.plan.live) return blocked("HA 数据由状态与预报订阅更新")
            if (!foreground) return blocked("请在面板前台刷新")
            if (current.inFlight) {
                manualActive = true
                return WeatherRefreshResult(WeatherRefreshStatus.JOINED)
            }
            if (requested) return WeatherRefreshResult(WeatherRefreshStatus.QUEUED)
            val due = maxOf(current.lastAttempt.plusSeconds(minimumSeconds(current)), retryAt(current))
            current.plan.restriction()?.let { restriction ->
                return blocked(restriction.message, restriction.retryAt?.let { maxOf(it, due) })
            }
            current.failure?.takeIf { it.state == WeatherState.UNSUPPORTED || it.state == WeatherState.UNAUTHORIZED }
                ?.let { return blocked(it.message) }
            if (instant < due) return blocked(current.failure?.message ?: "刷新间隔未到", due)
            requested = true
            reconcile()
            return WeatherRefreshResult(if (manualActive) WeatherRefreshStatus.STARTED else WeatherRefreshStatus.QUEUED)
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
        var lastAttempt = Instant.EPOCH
        var failedAt = Instant.EPOCH
        var recoveredAt: Instant? = null
        var lastRecovery = Instant.EPOCH
        var persistence = WeatherPersistence.NONE
        var unchanged = false
        var running = false
        var inFlight = false
        var cancel: (() -> Unit)? = null
        var generation = 0
        var retryCount = 0
    }

    private val demands = linkedSetOf<Demand>()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var timer: (() -> Unit)? = null
    private var refreshPolicy = WeatherRefreshPolicy()

    internal fun usePolicy(policy: WeatherRefreshPolicy) {
        refreshPolicy = policy
        reconcile()
    }

    internal fun estimatedRequests(policy: WeatherRefreshPolicy, days: Int): Pair<Int, Int> {
        val savedLocations = places.places.filterNot { places.isTemporary(it.id) }
            .flatMap { it.sources }.mapNotNull {
                when (it) {
                    is WeatherSourceRef.QWeather -> it.coordinates.path
                    is WeatherSourceRef.QWeatherCity -> "city:" + it.locationId
                    else -> null
                }
            }.distinct()
        val active = demands.filter { it.automatic && it.source !is WeatherSourceRef.HomeAssistant }
            .mapNotNull { it.entry }.distinct().filter { !it.plan.live && it.plan.unavailable == null }
            .map { it.plan.query.block }.filter { it !in setOf(WeatherBlock.HISTORY, WeatherBlock.SUN, WeatherBlock.MOON) }
        return policy.estimate(savedLocations.flatMap { WeatherBlock.entries.filter(WeatherBlock::core) }, days) to
            policy.estimate(active, days)
    }

    private fun intervalSeconds(entry: Entry): Long = 60 *
        (if (entry.plan.live) entry.plan.query.block.minutes else refreshPolicy.minutes(entry.plan.query.block))

    private fun minimumSeconds(entry: Entry): Long = 60 *
        (RefreshInterval.forBlock(entry.plan.query.block)?.minimum ?: entry.plan.query.block.minutes)

    private fun retryAt(entry: Entry): Instant {
        val failure = entry.failure ?: return Instant.EPOCH
        val providerLimit = failure.retryAt ?: Instant.EPOCH
        if (failure.state == WeatherState.BUDGET) return providerLimit
        val backoff = (60L * (1 shl (entry.retryCount - 1).coerceIn(0, 5))).coerceAtLeast(intervalSeconds(entry))
        val local = entry.recoveredAt ?: entry.failedAt.plusSeconds(backoff)
        return maxOf(local, providerLimit)
    }
    private var foreground = true
    private var recoveryPending = false
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
        if (active && recoveryPending) onNetworkAvailable() else reconcile()
    }

    internal fun onNetworkAvailable() {
        if (closed) return
        recoveryPending = !foreground
        if (!foreground) return
        val instant = now()
        val candidates = demands.filter { it.automatic && it.query.block.core && it.zone != null }
            .mapNotNull { demand -> demand.entry?.takeIf { entry ->
                expiry(entry, checkNotNull(demand.zone))?.let { instant >= it } != false
            } }.distinct()
        candidates.filter {
            it.failure?.recoverOnNetwork == true && it.failure?.retryAt == null && it.recoveredAt == null &&
                instant >= it.lastRecovery.plusSeconds(60)
        }.forEach {
            it.lastRecovery = instant
            it.recoveredAt = instant.plusSeconds(5)
        }
        reconcile()
    }

    internal fun onNetworkLost() {
        recoveryPending = false
    }

    internal fun onConfigurationChanged(resetBudget: Boolean = false, resetAuthorization: Boolean = false) {
        entries.values.filter {
            (resetBudget && it.failure?.state == WeatherState.BUDGET) ||
                (resetAuthorization && it.plan.key.startsWith("qw:") && it.failure?.statusCode == 401)
        }.forEach {
            it.failure = null
            it.recoveredAt = null
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
            it.persistence = WeatherPersistence.NONE
            it.unchanged = false
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
        demands.filter { it.entry == entry }.forEach { it.manualActive = false }
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
                demand.requested = false
                demand.emit(WeatherSnapshot(demand.source, demand.zone ?: java.time.ZoneOffset.UTC, demand.query,
                    WeatherState.UNSUPPORTED, error = "地点或绑定的来源已移除"))
                return@forEach
            }
            demand.zone = place.zone
            val plan = provider(demand.source).plan(demand.source, place.zone, demand.query, instant)
            val entry = entries.getOrPut(plan.key) { Entry(plan) }
            if (demand.entry != entry) {
                demand.manualActive = false
                demand.requested = false
            }
            demand.unavailable = plan.unavailable
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
            val due = when {
                entry.plan.live -> Instant.EPOCH
                entry.recoveredAt != null -> retryAt(entry)
                onDemand -> maxOf(entry.lastAttempt.plusSeconds(minimumSeconds(entry)), retryAt(entry))
                else -> maxOf(
                    entry.lastAttempt.plusSeconds(minimumSeconds(entry)),
                    retryAt(entry), expiry ?: Instant.EPOCH,
                )
            }
            if (foreground && !entry.running && !entry.loadingDisk && (onDemand || auto) && (!permanent || entry.plan.live)) {
                if (instant >= due && (entry.plan.live || httpSlots > 0)) {
                    if (!entry.plan.live) httpSlots--
                    eligible.forEach {
                        it.manualActive = it.requested
                        it.requested = false
                    }
                    start(entry, instant)
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
            entry.record?.let {
                entry.persistence = WeatherPersistence.SAVED
                entry.lastAttempt = maxOf(entry.lastAttempt, it.fetchedAt)
            }
            reconcile()
        }
    }

    private fun start(entry: Entry, instant: Instant) {
        entry.running = true
        entry.inFlight = true
        val previousAttempt = entry.lastAttempt
        entry.lastAttempt = instant
        entry.recoveredAt = null
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
                entry.unchanged = false
                when (result) {
                    is WeatherResult.Data -> {
                        entry.failure = null
                        entry.retryCount = 0
                        val record = WeatherCacheRecord(result.wire, received)
                        val hasData = result.parsed.data != null
                        if (hasData || entry.record == null) {
                            val previous = entry.record?.wire
                            val previousTag = previous?.optJSONObject("metadata")?.text("tag")
                            val previousParsed = result.zone?.let(entry.parsed::get)
                            entry.unchanged = hasData && (
                                (previousTag != null && previousTag == result.parsed.tag) || previousParsed == result.parsed)
                            entry.record = record
                            entry.parsed.clear()
                            result.zone?.let { entry.parsed[it] = result.parsed }
                            entry.persistence = WeatherPersistence.SESSION_ONLY
                            if (demands.any { it.entry == entry && !places.isTemporary(it.placeId) })
                                save(entry, record)
                        } else {
                            entry.failedAt = received
                            entry.failure = WeatherFailure(WeatherState.EMPTY, "服务当前无数据，保留上次结果")
                        }
                    }
                    is WeatherResult.Failed -> {
                        if (!result.failure.attempted) entry.lastAttempt = previousAttempt
                        entry.failure = result.failure
                        entry.failedAt = received
                        entry.retryCount++
                    }
                }
                reconcile()
            }
        }
    }

    private fun save(entry: Entry, record: WeatherCacheRecord) {
        entry.persistence = WeatherPersistence.SAVING
        cache.write(entry.plan.group, entry.plan.key, record) { success ->
            if (!closed && entry.record === record && entries[entry.plan.key] === entry) {
                entry.persistence = if (success) WeatherPersistence.SAVED else WeatherPersistence.FAILED
                reconcile()
            }
        }
    }

    private fun expiry(entry: Entry, zone: ZoneId): Instant? {
        val record = entry.record ?: return null
        val block = entry.plan.query.block
        val expiresAt = record.fetchedAt.plusSeconds(intervalSeconds(entry))
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
            parsed?.attribution ?: WeatherAttribution(), entry.failure?.message, parsed?.tag, entry.inFlight, entry.persistence, entry.unchanged)
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
