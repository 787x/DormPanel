package com.dormpanel.app.icloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

internal data class CloudCalendarEvent(val calendar: CloudCalendar, val event: CloudEvent, val ref: CloudObjectRef? = null)

/** Activity-scoped demand and timers; credential, cache and network work is serialized off the UI thread. */
class CloudCalendarService internal constructor(
    context: Context,
    private val openClient: (String, String) -> CalDavClient = { email, password -> CalDavClient(email, password) },
    private val networkAvailable: (() -> Boolean)? = null,
    private val store: CloudCalendarStore = CloudCalendarStore(context),
) : AutoCloseable {
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
    private var activeRound: CalDavRound? = null
    private val inspections = mutableSetOf<CalDavRound>()
    private var writeRequested = false
    private val manualWrites = mutableSetOf<String>()
    private var savedWrites = emptyList<CalendarWrite>()
    private var writingId: String? = null
    private var queueUnavailable = false
    @Volatile private var syncingKey: CalendarCacheKey? = null
    @Volatile private var wanted = emptySet<CalendarCacheKey>()
    @Volatile private var working = false
    private val refreshMillis get() = configured.refreshMinutes * 60_000L
    internal val calendars get() = configured.calendars
    internal val selected get() = configured.selected
    internal val connected get() = configured.connected
    internal val refreshMinutes get() = configured.refreshMinutes
    internal val isBusy get() = settingsBusy
    internal val createCalendars get() = calendars.filter { it.privileges.bind && !queueUnavailable &&
        !settingsBusy && issue !in setOf(CalDavProblem.AUTH, CalDavProblem.ACCESS) }
    internal val accountId get() = configured.account
    internal val isOffline get() = offline
    internal val writes get() = savedWrites.filter { it.ref.account == accountId || it.state != CalendarWriteState.CONFIRMED }
        .map { if (it.id == writingId) it.copy(state = CalendarWriteState.WRITING) else it }
    internal val hasPendingWrites get() = queueUnavailable || writes.any {
        it.ref.account == accountId && it.state != CalendarWriteState.CONFIRMED
    }

    private data class ObjectProjection(val href: String, val etag: String, val event: CloudEvent)

    private data class WindowView(
        val checkedAt: Long, val collection: String?, val events: List<ObjectProjection>,
        val issue: CalDavProblem? = null, val retryAt: Long = 0,
    ) {
        val bytes: Long = run {
            val strings = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<String, Boolean>())
            events.forEach { (href, etag, event) ->
                strings.addAll(listOfNotNull(href, etag, event.uid, event.recurrence, event.title, event.location, event.description))
            }
            events.size * 200L + strings.sumOf { 40L + it.length * 2L }
        }
        fun due(now: Long, refreshMillis: Long) = max(retryAt,
            if (checkedAt > now || issue != null) now else checkedAt + refreshMillis)
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
            val writes = runCatching(store::writes)
            main.post {
                if (!closed) {
                    result.onSuccess { configured = it }.onFailure { issue = CalDavProblem.STORAGE }
                    writes.onSuccess { savedWrites = it }.onFailure { queueUnavailable = true }
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
        writeRequested = true
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
        activeRound?.cancel()
        inspections.forEach { it.cancel() }
        demandChanged()
    }

    private fun updateNetwork() {
        if (!started) return
        // A LAN-only or captive connection can still be present when iCloud is unreachable.
        val down = !(networkAvailable?.invoke() ?: (connectivity.activeNetwork != null))
        working = !settingsBusy && !down
        if (offline != down) {
            offline = down
            if (down) {
                activeRound?.cancel()
                inspections.forEach { it.cancel() }
            }
            if (!down) {
                writeRequested = true
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
                val view = visible[key] ?: continue
                view.events.forEach { (href, etag, event) ->
                    if (seen.add(Triple(calendar.id, href, event.recurrence))) {
                        val ref = href.toHttpUrlOrNull()?.takeIf { view.collection == calendar.url.toString() }?.let {
                            CloudObjectRef(accountId, calendar.id, calendar.url, it, event.uid, event.recurrence, etag, calendar.resourceId)
                        }
                        add(CloudCalendarEvent(calendar, event, ref))
                    }
                }
            }
        }
    }

    internal fun eventsOn(date: LocalDate, zone: ZoneId, selection: Set<String>? = null): List<CloudCalendarEvent> {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        return events(CloudRange.between(date, date, zone, selection)).filter { it.event.overlaps(start, end, zone) }
    }

    internal fun selectionNeedsReview(selection: Set<String>? = null): Boolean =
        connected && (selection ?: selected).any { id -> calendars.none { it.id == id } }

    internal fun connectionLabel(range: CloudRange? = null): String {
        val keys = range?.let(::keys) ?: wanted
        val error = issue ?: keys.firstNotNullOfOrNull { visible[it]?.issue }
        val now = now()
        val stale = keys.any { (visible[it]?.checkedAt ?: 0) !in (now - refreshMillis * 2)..now }
        val label = when {
            settingsBusy -> "iCloud · 正在处理"
            queueUnavailable -> "iCloud · 本机待写记录不可读"
            error != null -> "iCloud · ${error.label}${if (connected) " · 缓存可能过期" else ""}"
            !connected -> "iCloud · 未连接"
            offline -> "iCloud · 离线 · 缓存可能过期"
            syncing -> "iCloud · 刷新中"
            stale -> "iCloud · 缓存可能过期"
            else -> "iCloud · 已连接"
        }
        return if (selectionNeedsReview(range?.selection)) "$label · 请重新选择日历" else label
    }

    internal fun hasStaleData(range: CloudRange): Boolean {
        val now = now()
        return keys(range).any { key ->
            offline || issue != null || visible[key]?.issue != null ||
                (visible[key]?.checkedAt ?: 0) !in (now - refreshMillis * 2)..now
        }
    }

    internal fun connect(email: String, password: String, keepPending: Boolean = false) {
        val round = CalDavRound()
        changeSettings(round = round) { old ->
            val same = old.connected && store.login(old)?.first.equals(email, true)
            if (!same) checkAccountChange(old, keepPending)
            val found = openClient(email, password).discover(round)
            val binding = if (same) old.rediscovered(found) else CloudSettings(
                calendars = found, selected = found.mapTo(linkedSetOf()) { it.id }, refreshMinutes = old.refreshMinutes,
            )
            val updated = binding.copy(account = if (same) old.account else UUID.randomUUID().toString(),
                encryptedLogin = store.encrypt(email, password))
            round.check()
            store.save(updated)
            runCatching { store.pruneAccounts(updated.account) }
            updated
        }
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

    internal fun disconnect(keepPending: Boolean = false) = changeSettings(resetWindows = true) {
        checkAccountChange(it, keepPending)
        store.disconnect()
        CloudSettings()
    }

    private fun checkAccountChange(old: CloudSettings, keepPending: Boolean) {
        if (!keepPending && store.writes().any { it.ref.account == old.account && it.state != CalendarWriteState.CONFIRMED })
            throw CalDavFailure(CalDavProblem.ACCESS)
    }

    private fun changeSettings(
        resetWindows: Boolean = false, forceRefresh: Boolean = true, round: CalDavRound? = null,
        change: (CloudSettings) -> CloudSettings,
    ) {
        if (closed || settingsBusy) return
        settingsBusy = true
        working = false
        activeRound?.cancel()
        inspections.forEach { it.cancel() }
        activeRound = round
        val old = configured
        publish()
        worker.execute {
            val base = runCatching { store.settings() }.getOrNull()?.takeIf { it.account == old.account } ?: old
            val updated = runCatching { change(base) }
            val recovered = if (updated.isFailure) runCatching { store.settings() }.getOrNull() else null
            main.post {
                if (closed) return@post
                if (activeRound === round) activeRound = null
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
                        writeRequested = true
                    }
                }.onFailure {
                    if (recovered != null && recovered.account != old.account) {
                        configured = recovered
                        visible.clear()
                    }
                    if (problem(it) != CalDavProblem.CANCELLED) {
                        issue = problem(it)
                        retryAt = retryTime(it)
                    }
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
        writeRequested = true
        discoveryAt = 0
        demandChanged()
    }

    internal fun writeFor(ref: CloudObjectRef?) = ref?.let { target -> writes.firstOrNull { it.sameTarget(target) } }

    internal fun inspect(ref: CloudObjectRef, zone: ZoneId, allowNewer: Boolean = false,
        result: (CloudEditBase?, String?) -> Unit,
    ) {
        if (!working || settingsBusy || queueUnavailable) {
            result(null, if (offline) "网络不可用，联网后可核对日程" else "暂时无法核对日程")
            return
        }
        if (ref.recurrenceId != null) { result(null, "重复日程实例为只读"); return }
        val round = CalDavRound()
        inspections.add(round)
        worker.execute {
            val checked = runCatching {
                val config = store.settings()
                val calendar = config.calendars.singleOrNull { ref.matches(config.account, it) }
                    ?: throw IllegalArgumentException("账户或日历地址已变化，请重新打开日程")
                val (email, password) = store.login(config) ?: throw CalDavFailure(CalDavProblem.STORAGE)
                val client = openClient(email, password)
                val privileges = client.verifyCollection(calendar, round)
                val remote = client.readObject(ref.collection, ref.href, round)
                    ?: throw IllegalArgumentException("服务器日程已不存在")
                if (!strongCalendarEtag(remote.etag)) throw IllegalArgumentException("服务器未提供强 ETag，日程为只读")
                val newer = remote.etag != ref.etag
                if (newer && !allowNewer) throw IllegalArgumentException("服务器版本已变化，请重新读取")
                val event = try { EditableCalendarObject.parse(remote.ics, ref.uid, zone) }
                catch (e: CalDavFailure) { throw IllegalArgumentException("日程资源含无法安全编辑的属性或组件") }
                val writeContent = try { client.objectPrivileges(ref.collection, ref.href, round).content }
                catch (e: CalDavFailure) { if (e.problem == CalDavProblem.ACCESS) false else throw e }
                CloudEditBase(ref.copy(etag = remote.etag), calendar.name, event, writeContent, privileges.unbind, newer)
            }
            main.post {
                inspections.remove(round)
                if (!closed && started && !settingsBusy && ref.account == accountId) {
                    val error = checked.exceptionOrNull()
                    result(checked.getOrNull(), when (error) {
                        null -> null
                        is IllegalArgumentException -> error.message?.takeIf { it.any { ch -> ch in '\u4e00'..'\u9fff' } }
                            ?: "日程内容无法安全编辑"
                        is CalDavFailure -> error.problem.label
                        else -> "无法核对服务器日程"
                    })
                }
            }
        }
    }

    internal fun saveCreate(account: String, calendar: CloudCalendar, draft: CloudEventDraft, done: (String?) -> Unit) {
        editQueue(done) { config, entries ->
            val target = config.calendars.singleOrNull { it.id == calendar.id }
            require(config.account == account && target == calendar && target.privileges.bind) { "账户或创建权限已变化，请重新选择" }
            val id = UUID.randomUUID().toString()
            val uid = "$id@dormpanel"
            val href = target.url.newBuilder().encodedPath(target.url.encodedPath.trimEnd('/') + "/").addPathSegment("$id.ics").build()
            val ref = CloudObjectRef(account, target.id, target.url, href, uid, null, null, target.resourceId)
            entries + CalendarWrite(id, ref, target.name, CalendarWriteKind.CREATE, draft,
                EditableCalendarObject.create(uid, draft, Instant.now()))
        }
    }

    internal fun saveEdit(base: CloudEditBase, draft: CloudEventDraft, delete: Boolean = false,
        replacing: String? = null, done: (String?) -> Unit,
    ) {
        editQueue(done) { config, entries ->
            val ref = base.ref
            require(config.calendars.any { ref.matches(config.account, it) } && strongCalendarEtag(ref.etag) &&
                ref.recurrenceId == null && (if (delete) base.canDelete else base.canEdit)) { "账户、日历或日程权限已变化" }
            val active = entries.singleOrNull { it.sameTarget(ref) && it.state != CalendarWriteState.CONFIRMED }
            require(if (replacing == null) active == null else active?.id == replacing && active.state == CalendarWriteState.CONFLICT) {
                "已有待写修改，请在待写记录中处理"
            }
            val kind = if (delete) CalendarWriteKind.DELETE else CalendarWriteKind.UPDATE
            if (!delete && replacing == null && draft == base.event.draft) return@editQueue entries
            val payload = if (delete) null else base.event.edit(draft, Instant.now())
            entries.filterNot { it.id == replacing } +
                CalendarWrite(UUID.randomUUID().toString(), ref, base.calendarName, kind, draft, payload)
        }
    }

    internal fun retryWrite(id: String) {
        if (writes.none { it.id == id && it.ref.account == accountId && it.retryAt <= now() &&
                it.state in setOf(CalendarWriteState.QUEUED, CalendarWriteState.CHECKING, CalendarWriteState.FAILED) }) return
        manualWrites.add(id)
        demandChanged()
    }

    internal fun discardWrite(id: String, done: (String?) -> Unit) {
        editQueue(done, kick = false) { config, entries ->
            val entry = entries.singleOrNull { it.id == id } ?: return@editQueue entries
            if (entry.attempted && entry.ref.account == config.account) store.invalidateObject(entry.ref)
            entries.filterNot { it.id == id }
        }
    }

    private fun editQueue(done: (String?) -> Unit, kick: Boolean = true,
        change: (CloudSettings, List<CalendarWrite>) -> List<CalendarWrite>,
    ) {
        if (closed || settingsBusy || queueUnavailable) { done("本机待写记录暂不可用"); return }
        worker.execute {
            val result = runCatching {
                change(store.settings(), store.writes()).let { next -> store.saveWrites(next); store.writes() }
            }
            main.post {
                if (closed) return@post
                val error = result.exceptionOrNull()
                result.onSuccess {
                    savedWrites = it
                    if (kick) writeRequested = true else { visible.clear(); force = true }
                    demandChanged()
                    publish()
                }
                done(when (error) {
                    null -> null
                    is IllegalArgumentException -> error.message
                    is CalDavFailure -> error.problem.label
                    else -> "无法保存本机待写记录"
                })
            }
        }
    }

    private fun demandChanged() {
        val next = activeRanges().flatMapTo(linkedSetOf()) { keys(it) }
        if (next != wanted) {
            visible.entries.removeAll { it.value.retryAt == MEMORY_DEFERRED }
            // A navigation can add an urgent window even when the current one still
            // belongs to the foreground refresh set.
            if (syncingKey?.let { it !in next || next.any { key -> key !in wanted } } == true) activeRound?.cancel()
        }
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
        val writeIds = if (!canSync || queueUnavailable) emptySet() else savedWrites.filter {
            it.ref.account == accountId && it.retryAt <= now && (
                it.id in manualWrites || writeRequested && it.state in setOf(CalendarWriteState.QUEUED, CalendarWriteState.CHECKING)
            )
        }.mapTo(linkedSetOf()) { it.id }
        if (!discover && requested.isEmpty() && writeIds.isEmpty()) { scheduleNext(); return }
        val settings = configured
        val knownWrites = savedWrites
        val coolingDown = visible.filterValues { it.retryAt > now }.keys.toSet()
        val round = CalDavRound()
        if (canSync) activeRound = round
        syncing = true
        if (canSync) {
            force = false
            writeRequested = false
            manualWrites.removeAll(writeIds)
        }
        publish()
        worker.execute {
            val memory = requested.toSet()
            var effective = settings
            var failure: Throwable? = null
            var discovered = false
            val invalidated = mutableSetOf<String>()
            var receipts = knownWrites.filter { it.state == CalendarWriteState.CONFIRMED && it.ref.account == settings.account }
            val unprocessed = writeIds.toMutableSet()
            fun dispatch(key: CalendarCacheKey, view: WindowView) {
                main.post {
                    if (!closed && configured.account == settings.account && key in wanted) {
                        val occupied = visible.entries.sumOf { (other, current) -> if (key == other) 0L else current.bytes }
                        visible[key] = if (occupied + view.bytes <= MAX_VIEW_BYTES) view else
                            (visible[key] ?: WindowView(0, null, emptyList())).copy(issue = CalDavProblem.CAPACITY, retryAt = MEMORY_DEFERRED)
                        publish()
                    }
                }
            }
            fun read(key: CalendarCacheKey) = runCatching { store.snapshot(effective.account, key) }
            fun load(key: CalendarCacheKey) = read(key).fold(
                onSuccess = { dispatch(key, it.toView()) },
                onFailure = { dispatch(key, WindowView(0, null, emptyList(), CalDavProblem.STORAGE)) },
            )
            try {
                if (writeIds.isNotEmpty()) {
                    val persisted = store.settings()
                    effective = persisted
                    if (persisted.account != settings.account || persisted.encryptedLogin != settings.encryptedLogin) {
                        throw CalDavFailure(CalDavProblem.CANCELLED)
                    }
                }
                // Publish disk data before any network request, even if authentication is down.
                missing.forEach { if (it in wanted) load(it) }
                if (canSync && working) {
                    val (email, password) = store.login(settings) ?: throw CalDavFailure(CalDavProblem.STORAGE)
                    val client = openClient(email, password)
                    if (discover) {
                        val updated = effective.rediscovered(client.discover(round))
                        round.check()
                        if (updated != effective) {
                            store.save(updated)
                            effective = updated
                        }
                        discovered = true
                    }
                    if (writeIds.isNotEmpty()) {
                        var entries = store.writes()
                        for (id in writeIds) {
                            if (!working) break
                            round.check()
                            unprocessed.remove(id)
                            val entry = entries.singleOrNull { it.id == id } ?: continue
                            if (entry.state in setOf(CalendarWriteState.CONFIRMED, CalendarWriteState.CONFLICT)) continue
                            val calendar = effective.calendars.singleOrNull { entry.ref.matches(effective.account, it) }
                            fun persist(next: CalendarWrite) {
                                val saved = entries.map { if (it.id == id) next else it }
                                store.saveWrites(saved)
                                entries = store.writes()
                            }
                            main.post {
                                if (!closed) { writingId = id; publish() }
                            }
                            val next = if (calendar == null) entry.copy(state = CalendarWriteState.CONFLICT,
                                detail = "无法确认原账户、日历或资源地址")
                            else processCalendarWrite(client, calendar, entry, round, ::persist)
                            if (next.state == CalendarWriteState.CONFIRMED) {
                                store.invalidateObject(next.ref)
                                invalidated.add(next.ref.calendarId)
                            }
                            persist(next.copy(ics = next.ics.takeUnless { next.state == CalendarWriteState.CONFIRMED }))
                            receipts = entries.filter { it.state == CalendarWriteState.CONFIRMED && it.ref.account == settings.account }
                            val updated = entries
                            main.post {
                                if (!closed) {
                                    writingId = null
                                    savedWrites = updated
                                    if (next.state == CalendarWriteState.CONFIRMED) {
                                        visible.replaceAll { key, view ->
                                            if (key.calendarId != next.ref.calendarId) view else view.copy(
                                                checkedAt = 0, events = view.events.filterNot { it.href == next.ref.href.toString() },
                                            )
                                        }
                                    }
                                    publish()
                                }
                            }
                            if (next.retryAt > now()) throw CalDavFailure(CalDavProblem.LIMITED, next.retryAt)
                        }
                    }
                    val scopes = if (discover || invalidated.isNotEmpty()) active.flatMapTo(linkedSetOf()) { keys(it, effective) } else memory
                    for (key in scopes) {
                        if (!working) break
                        syncingKey = key
                        if (key !in wanted) continue
                        if (!refresh && key.calendarId !in invalidated && key in coolingDown) continue
                        val old = read(key)
                        val previous = old.getOrNull()
                        val calendar = effective.calendars.firstOrNull { it.id == key.calendarId } ?: continue
                        if (!refresh && previous?.problem == null && previous?.collectionUrl == calendar.url.toString() &&
                            previous.hasCurrentProjection && now - previous.checkedAt in 0 until settings.refreshMinutes * 60_000L) continue
                        try {
                            val snapshot = client.synchronize(calendar, key.window, previous, now(), round)
                            val obsolete = receipts.filter { receipt ->
                                receipt.ref.matches(effective.account, calendar) && receipt.ref.etag != null &&
                                    receipt.ref.etag != receipt.verifiedEtag &&
                                    snapshot.objects[receipt.ref.href.toString()]?.etag == receipt.ref.etag
                            }.mapTo(hashSetOf()) { it.ref.href.toString() }
                            val next = if (obsolete.isEmpty()) snapshot else snapshot.copy(
                                checkedAt = 0, collectionToken = null, objects = snapshot.objects - obsolete, problem = CalDavProblem.DATA,
                            )
                            round.check()
                            store.saveSnapshot(effective.account, key, next, wanted)
                            val retry = next.problem?.let { retryTime(CalDavFailure(it)) } ?: 0
                            dispatch(key, next.toView(retryAt = retry))
                        } catch (e: Exception) {
                            if (problem(e) != CalDavProblem.CANCELLED) dispatch(key, previous.toView(problem(e), retryTime(e)))
                            if (problem(e) in GLOBAL_FAILURES || problem(e) in setOf(CalDavProblem.BUDGET, CalDavProblem.CANCELLED) ||
                                (e is CalDavFailure && e.retryAt > now())) {
                                failure = e
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) { failure = e }
            finally { syncingKey = null }
            val recoveredWrites = if (writeIds.isNotEmpty()) runCatching(store::writes).getOrNull() else null
            main.post {
                if (closed) return@post
                syncing = false
                writingId = null
                if (recoveredWrites != null) savedWrites = recoveredWrites
                if (activeRound === round) activeRound = null
                if (configured.account == settings.account && !settingsBusy) {
                    if (effective.account == settings.account) manualWrites.addAll(unprocessed) else {
                        visible.clear()
                        manualWrites.clear()
                    }
                    configured = effective
                    val next = activeRanges().flatMapTo(linkedSetOf()) { keys(it) }
                    wanted = next
                    visible.keys.retainAll(next)
                    if (discovered) discoveryAt = now()
                    if (canSync && failure?.let(::problem) != CalDavProblem.CANCELLED) {
                        issue = failure?.let(::problem)
                        retryAt = failure?.let(::retryTime) ?: 0
                        serverRetryAt = (failure as? CalDavFailure)?.retryAt ?: 0
                    }
                }
                publish()
                if (pending || wanted.any { it !in visible } || manualWrites.isNotEmpty()) { pending = false; demandChanged() } else scheduleNext()
            }
        }
    }

    private fun CalendarSnapshot?.toView(issue: CalDavProblem? = null, retryAt: Long = 0) = WindowView(
        // Legacy derived events remain viewable offline and are revalidated on refresh.
        this?.takeIf { it.hasCurrentProjection }?.checkedAt ?: 0,
        this?.collectionUrl, this?.objects?.flatMap { (href, obj) -> obj.events.map { ObjectProjection(href, obj.etag, it) } }.orEmpty(),
        issue ?: this?.problem, retryAt,
    )

    private fun scheduleNext() {
        if (!started || closed || settingsBusy || offline || !connected) return
        val now = now()
        val due = minOf(discoveryAt + refreshMillis, wanted.minOfOrNull { visible[it]?.due(now, refreshMillis) ?: now } ?: Long.MAX_VALUE)
        main.removeCallbacks(timer)
        main.postDelayed(timer, (max(due, retryAt) - now).coerceAtLeast(1000))
    }

    private fun retryTime(failure: Throwable): Long {
        val delay = when (problem(failure)) {
            CalDavProblem.AUTH, CalDavProblem.ACCESS -> refreshMillis
            CalDavProblem.BUDGET -> 60_000L
            else -> 5 * 60_000L
        }
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
    }

    internal companion object {
        // Keep disk transactions serialized across Activity replacement and process-local service instances.
        private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
            allowCoreThreadTimeOut(true)
        }
        private const val MAX_VIEW_BYTES = 32L * 1024 * 1024
        private const val MEMORY_DEFERRED = Long.MAX_VALUE
        private val GLOBAL_FAILURES = setOf(CalDavProblem.AUTH, CalDavProblem.NETWORK, CalDavProblem.TLS, CalDavProblem.LIMITED, CalDavProblem.SERVER)
        internal var overrideFactory: ((Context) -> CloudCalendarService)? = null
        fun create(context: Context) = overrideFactory?.invoke(context) ?: CloudCalendarService(context)
    }
}
