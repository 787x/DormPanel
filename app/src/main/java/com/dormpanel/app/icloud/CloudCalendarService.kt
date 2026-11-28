package com.dormpanel.app.icloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max

internal data class CloudRange(val months: Set<CalendarWindow>, val selection: Set<String>? = null) {
    companion object {
        fun between(from: LocalDate, through: LocalDate, zone: ZoneId, selection: Set<String>? = null): CloudRange {
            val start = YearMonth.from(from)
            val end = YearMonth.from(through)
            return CloudRange(generateSequence(start) { it.plusMonths(1) }.takeWhile { it <= end }
                .map { CalendarWindow(it, zone) }.toSet(), selection)
        }
    }
}

internal data class CloudCalendarEvent(val calendar: CloudCalendar, val event: CloudEvent)

/** Activity-scoped demand and timers; credential, cache and network work is serialized off the UI thread. */
class CloudCalendarService internal constructor(
    context: Context,
    private val openClient: (String, String) -> CalDavClient = { email, password -> CalDavClient(email, password) },
    private val networkAvailable: (() -> Boolean)? = null,
    private val store: CloudCalendarStore = CloudCalendarStore(context),
) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val watchers = linkedMapOf<Any, CloudRange>()
    private val listeners = linkedSetOf<() -> Unit>()
    private val visible = mutableMapOf<CalendarCacheKey, WindowView>()
    private val timer = Runnable { runPass() }
    private var started = false
    private var closed = false
    private var registered = false
    private var offline = false
    private var pending = false
    private var force = false
    private var discoveryAt = 0L
    private var retryAt = 0L
    private var serverRetryAt = 0L
    private var issue: CalDavProblem? = null
    private var configured = CloudSettings()
    private var settingsBusy = true
    private var syncing = false
    @Volatile private var wanted = emptySet<CalendarCacheKey>()
    @Volatile private var working = false
    private val refreshMillis get() = configured.refreshMinutes * 60_000L
    internal val calendars get() = configured.calendars
    internal val selected get() = configured.selected
    internal val connected get() = configured.connected
    internal val refreshMinutes get() = configured.refreshMinutes
    internal val isBusy get() = settingsBusy

    private data class WindowView(
        val checkedAt: Long, val events: List<CloudEvent>, val issue: CalDavProblem? = null, val retryAt: Long = 0,
    ) {
        fun due(now: Long, refreshMillis: Long) = max(retryAt, if (checkedAt > now) now else checkedAt + refreshMillis)
    }

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post(::updateNetwork) }
        override fun onLost(network: Network) { main.post(::updateNetwork) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { main.post(::updateNetwork) }
    }

    init {
        worker.execute {
            val result = runCatching {
                val saved = store.settings()
                runCatching { store.pruneAccounts(saved.account) }
                saved
            }
            main.post {
                if (!closed) {
                    result.onSuccess { configured = it }.onFailure { issue = CalDavProblem.STORAGE }
                    settingsBusy = false
                    working = started && !offline
                    demandChanged()
                    publish()
                }
            }
        }
    }

    fun start() {
        if (started || closed) return
        started = true
        working = !settingsBusy && !offline
        registered = runCatching { connectivity.registerDefaultNetworkCallback(network) }.isSuccess
        updateNetwork()
        demandChanged()
    }

    fun stop() {
        if (registered) connectivity.unregisterNetworkCallback(network)
        registered = false
        started = false
        working = false
        demandChanged()
    }

    private fun updateNetwork() {
        if (!started) return
        // A LAN-only or captive connection can still be present when iCloud is unreachable.
        val down = !(networkAvailable?.invoke() ?: (connectivity.activeNetwork != null))
        working = !settingsBusy && !down
        if (offline != down) {
            offline = down
            if (!down) {
                force = true
                if (issue == CalDavProblem.NETWORK) retryAt = 0
            }
            publish()
            demandChanged()
        }
    }

    internal fun addListener(listener: () -> Unit) { listeners.add(listener) }
    internal fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    internal fun watch(owner: Any, range: CloudRange?) {
        val old = if (range == null) watchers.remove(owner) else watchers.put(owner, range)
        if (old != range) demandChanged()
    }

    private fun keys(range: CloudRange, settings: CloudSettings = configured): Set<CalendarCacheKey> {
        val selected = range.selection ?: settings.selected
        return buildSet {
            range.months.forEach { window ->
                settings.calendars.forEach { if (it.id in selected) add(CalendarCacheKey(it.id, window)) }
            }
        }
    }

    internal fun events(range: CloudRange): List<CloudCalendarEvent> {
        val calendars = calendars.associateBy { it.id }
        val seen = hashSetOf<Triple<String, String, String?>>()
        return buildList {
            // Each month owns its own view. Callers query one day or filter by the exact
            // month of the supplied range so old adjacent snapshots cannot resurrect an event.
            for (key in keys(range)) {
                val calendar = calendars[key.calendarId] ?: continue
                visible[key]?.events?.forEach { if (seen.add(Triple(calendar.id, it.uid, it.recurrence))) add(CloudCalendarEvent(calendar, it)) }
            }
        }
    }

    internal fun eventsOn(date: LocalDate, zone: ZoneId, selection: Set<String>? = null): List<CloudCalendarEvent> {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        return events(CloudRange.between(date, date, zone, selection)).filter { it.event.overlaps(start, end, zone) }
    }

    internal fun connectionLabel(range: CloudRange? = null): String {
        val keys = range?.let(::keys).orEmpty()
        val error = issue ?: keys.firstNotNullOfOrNull { visible[it]?.issue }
        val now = now()
        val stale = keys.any { (visible[it]?.checkedAt ?: 0) !in (now - refreshMillis * 2)..now }
        return when {
            settingsBusy -> "iCloud · 正在处理"
            error != null -> "iCloud · ${error.label}${if (connected) " · 缓存可能过期" else ""}"
            !connected -> "iCloud · 未连接"
            offline -> "iCloud · 离线 · 缓存可能过期"
            syncing -> "iCloud · 刷新中"
            stale -> "iCloud · 缓存可能过期"
            else -> "iCloud · 已连接"
        }
    }

    internal fun hasStaleData(range: CloudRange): Boolean {
        val now = now()
        return keys(range).any { key ->
            offline || issue != null || visible[key]?.issue != null ||
                (visible[key]?.checkedAt ?: 0) !in (now - refreshMillis * 2)..now
        }
    }

    internal fun connect(email: String, password: String) = changeSettings { old ->
        val found = openClient(email, password).discover()
        // A replacement password for the same account leaves its cache and preferences usable.
        val same = old.connected && store.login(old)?.first.equals(email, true)
        val updated = old.copy(account = if (same) old.account else UUID.randomUUID().toString(),
            encryptedLogin = store.encrypt(email, password), calendars = found,
            selected = if (same) old.selected else found.mapTo(linkedSetOf()) { it.id })
        store.save(updated)
        runCatching { store.pruneAccounts(updated.account) }
        updated
    }

    internal fun setSelection(selected: Set<String>) = changeSettings(forceRefresh = false) { old ->
        old.copy(selected = selected).also(store::save)
    }

    internal fun setRefresh(minutes: Int) = changeSettings(forceRefresh = false) { old ->
        old.copy(refreshMinutes = minutes).also(store::save)
    }

    internal fun clearCache() = changeSettings(resetWindows = true) { old ->
        store.clearCache(old.account)
        old
    }

    internal fun disconnect() = changeSettings(resetWindows = true) {
        store.disconnect()
        CloudSettings()
    }

    private fun changeSettings(resetWindows: Boolean = false, forceRefresh: Boolean = true, change: (CloudSettings) -> CloudSettings) {
        if (closed || settingsBusy) return
        settingsBusy = true
        working = false
        val old = configured
        publish()
        worker.execute {
            val base = runCatching { store.settings() }.getOrNull()?.takeIf { it.account == old.account } ?: old
            val updated = runCatching { change(base) }
            val recovered = if (updated.isFailure) runCatching { store.settings() }.getOrNull() else null
            main.post {
                if (closed) return@post
                settingsBusy = false
                working = started && !offline
                updated.onSuccess {
                    if (resetWindows || it.account != old.account) visible.clear()
                    configured = it
                    if (forceRefresh) {
                        issue = null
                        retryAt = 0
                        serverRetryAt = 0
                        discoveryAt = now()
                        force = true
                    }
                }.onFailure {
                    if (recovered != null && recovered.account != old.account) {
                        configured = recovered
                        visible.clear()
                    }
                    issue = problem(it)
                    retryAt = retryTime(it)
                }
                demandChanged()
                publish()
            }
        }
    }

    internal fun refresh() {
        if (settingsBusy || !connected) return
        // Keep the server's Retry-After in force for explicit refreshes too.
        if (issue != CalDavProblem.LIMITED) retryAt = serverRetryAt
        force = true
        discoveryAt = 0
        demandChanged()
    }

    private fun demandChanged() {
        val next = activeRanges().flatMapTo(linkedSetOf()) { keys(it) }
        wanted = next
        visible.keys.retainAll(next)
        main.removeCallbacks(timer)
        if (started && !closed && !settingsBusy && configured.connected)
            main.postDelayed(timer, 120)
    }

    private fun activeRanges(): List<CloudRange> {
        if (!started) return watchers.values.toList()
        val zone = ZoneId.systemDefault()
        val current = CalendarWindow(YearMonth.now(zone), zone)
        return watchers.values.toList() + CloudRange(setOf(current))
    }

    private fun runPass() {
        if (!started || settingsBusy || !connected) return
        if (syncing) { pending = true; return }
        val now = now()
        val active = activeRanges()
        wanted = active.flatMapTo(linkedSetOf()) { keys(it) }
        visible.keys.retainAll(wanted)
        val canSync = !offline && now >= retryAt
        val refresh = canSync && force
        val discover = canSync && (now >= discoveryAt + refreshMillis || refresh)
        val requested = wanted.filter { key ->
            val view = visible[key]
            view == null || (canSync && (refresh || now >= view.due(now, refreshMillis)))
        }
        val missing = requested.filterNot(visible::containsKey)
        if (!discover && requested.isEmpty()) { scheduleNext(); return }
        val settings = configured
        val coolingDown = visible.filterValues { it.retryAt > now }.keys.toSet()
        syncing = true
        if (canSync) force = false
        publish()
        worker.execute {
            val results = mutableMapOf<CalendarCacheKey, WindowView>()
            val memory = requested.toSet()
            var effective = settings
            var failure: Throwable? = null
            var discovered = false
            fun dispatch(key: CalendarCacheKey, view: WindowView) {
                results[key] = view
                main.post {
                    if (!closed && configured.account == settings.account && key in wanted) {
                        visible[key] = view
                        publish()
                    }
                }
            }
            fun read(key: CalendarCacheKey) = runCatching { store.snapshot(effective.account, key) }
            fun load(key: CalendarCacheKey) = read(key).fold(
                onSuccess = { dispatch(key, it.toView()) },
                onFailure = { dispatch(key, WindowView(0, emptyList(), CalDavProblem.STORAGE)) },
            )
            try {
                // Publish disk data before any network request, even if authentication is down.
                missing.forEach { if (it in wanted) load(it) }
                if (canSync && working) {
                    val (email, password) = store.login(settings) ?: throw CalDavFailure(CalDavProblem.STORAGE)
                    val client = openClient(email, password)
                    if (discover) {
                        val updated = settings.copy(calendars = client.discover())
                        if (updated != settings) {
                            store.save(updated)
                            effective = updated
                        }
                        discovered = true
                    }
                    val scopes = if (discover) active.flatMapTo(linkedSetOf()) { keys(it, effective) } else memory
                    for (key in scopes) {
                        if (!working) break
                        if (key !in wanted) continue
                        if (!refresh && key in coolingDown) continue
                        val old = read(key)
                        val previous = old.getOrNull()
                        if (!refresh && previous != null && now - previous.checkedAt in 0 until settings.refreshMinutes * 60_000L) continue
                        val calendar = effective.calendars.firstOrNull { it.id == key.calendarId } ?: continue
                        try {
                            val next = client.synchronize(calendar, key.window, previous, now())
                            store.saveSnapshot(effective.account, key, next)
                            dispatch(key, next.toView())
                        } catch (e: Exception) {
                            dispatch(key, previous.toView(problem(e), retryTime(e)))
                            if (problem(e) in GLOBAL_FAILURES || (e is CalDavFailure && e.retryAt > now())) {
                                failure = e
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) { failure = e }
            main.post {
                if (closed) return@post
                syncing = false
                if (configured.account == settings.account && !settingsBusy) {
                    configured = effective
                    val next = activeRanges().flatMapTo(linkedSetOf()) { keys(it) }
                    wanted = next
                    visible.keys.retainAll(next)
                    results.forEach { (key, view) -> if (key in next) visible[key] = view }
                    if (canSync) {
                        issue = failure?.let(::problem)
                        retryAt = failure?.let(::retryTime) ?: 0
                        serverRetryAt = (failure as? CalDavFailure)?.retryAt ?: 0
                        if (discovered) discoveryAt = now()
                    }
                }
                publish()
                if (pending || wanted.any { it !in visible }) { pending = false; demandChanged() } else scheduleNext()
            }
        }
    }

    private fun CalendarSnapshot?.toView(issue: CalDavProblem? = null, retryAt: Long = 0) = WindowView(
        this?.checkedAt ?: 0, this?.objects?.values?.flatMap { it.events }.orEmpty(), issue, retryAt,
    )

    private fun scheduleNext() {
        if (!started || closed || settingsBusy || offline || !connected) return
        val now = now()
        val due = minOf(discoveryAt + refreshMillis, wanted.minOfOrNull { visible[it]?.due(now, refreshMillis) ?: now } ?: Long.MAX_VALUE)
        main.removeCallbacks(timer)
        main.postDelayed(timer, (max(due, retryAt) - now).coerceAtLeast(1000))
    }

    private fun retryTime(failure: Throwable): Long {
        val delay = if (problem(failure) in setOf(CalDavProblem.AUTH, CalDavProblem.ACCESS)) refreshMillis else 5 * 60_000L
        return max(now() + delay, (failure as? CalDavFailure)?.retryAt ?: 0L)
    }

    private fun problem(failure: Throwable) = (failure as? CalDavFailure)?.problem ?: CalDavProblem.STORAGE
    private fun now() = System.currentTimeMillis()
    private fun publish() { listeners.toList().forEach { it() } }

    override fun close() {
        stop()
        closed = true
        listeners.clear()
        watchers.clear()
        wanted = emptySet()
        worker.shutdown()
    }

    internal companion object {
        private val GLOBAL_FAILURES = setOf(CalDavProblem.AUTH, CalDavProblem.NETWORK, CalDavProblem.TLS, CalDavProblem.LIMITED, CalDavProblem.SERVER)
        internal var overrideFactory: ((Context) -> CloudCalendarService)? = null
        fun create(context: Context) = overrideFactory?.invoke(context) ?: CloudCalendarService(context)
    }
}
