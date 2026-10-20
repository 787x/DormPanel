package com.dormpanel.app.schedule

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.Executors

/** Small CAS pointer references only an immutable, completely verified snapshot. */
data class SharedScheduleHead(val datasetId: String, val revision: String, val sha256: String) {
    fun bytes() = "DormPanelSchedule/1\n$datasetId\n$revision\n$sha256\n".toByteArray(Charsets.US_ASCII)
    companion object {
        fun parse(bytes: ByteArray): SharedScheduleHead {
            require(bytes.size <= 512)
            val lines = String(bytes, Charsets.US_ASCII).split('\n')
            require(lines.size == 5 && lines[0] == "DormPanelSchedule/1" && lines[4].isEmpty()) { "Invalid shared timetable pointer" }
            require(UUID.fromString(lines[1]).toString() == lines[1] && UUID.fromString(lines[2]).toString() == lines[2] && lines[3].matches(Regex("[0-9a-f]{64}")))
            return SharedScheduleHead(lines[1], lines[2], lines[3])
        }
    }
}
class SharedScheduleRace : IllegalStateException("远端已变化，正在重新比较")

data class SharedScheduleRemote(val head: SharedScheduleHead, val etag: String?, val bytes: ByteArray, val snapshot: ScheduleSnapshot)
class SharedScheduleTransport(private val client: WebDavClient = WebDavClient()) {
    fun folder(account: WebDavAccount, raw: String): String {
        val base = client.url(account.baseUrl)
        val dir = client.url(raw)
        require(base.scheme == dir.scheme && base.host == dir.host && base.port == dir.port) { "Shared folder must use the account server" }
        return dir.newBuilder().apply { if (!dir.encodedPath.endsWith('/')) addPathSegment("") }.build().toString()
    }
    private fun path(account: WebDavAccount, dir: String, name: String) = client.url(folder(account, dir)).newBuilder().addPathSegment(name).build().toString()
    fun head(account: WebDavAccount, dir: String) = client.readResource(account, path(account, dir, "dormpanel-head-v1"), 512)
    fun inspect(account: WebDavAccount, dir: String): SharedScheduleRemote {
        val pointer = head(account, dir) ?: throw WebDavException("Shared timetable has not been published in this folder.")
        val h = SharedScheduleHead.parse(pointer.bytes)
        val (bytes, snapshot) = historical(account, dir, h.datasetId, h.revision)
        require(digest(bytes) == h.sha256) { "Published snapshot checksum failed" }
        return SharedScheduleRemote(h, pointer.etag, bytes, snapshot)
    }
    fun historical(account: WebDavAccount, dir: String, dataset: String, revision: String): Pair<ByteArray, ScheduleSnapshot> {
        require(UUID.fromString(revision).toString() == revision)
        val payload = client.readResource(account, path(account, dir, "snapshot-$revision.dps"), ScheduleSnapshotCodec.MAX_BYTES)
            ?: throw WebDavException("共同基线历史快照缺失；保留本地数据，请恢复共享目录中的历史快照")
        val snapshot = ScheduleSnapshotCodec.decode(payload.bytes)
        require(snapshot.datasetId == dataset && snapshot.revision == revision) { "Historical snapshot identity mismatch" }
        return payload.bytes to snapshot
    }
    /** A unique disposable namespace: never probes user files. Retained as diagnostic history. */
    fun diagnose(account: WebDavAccount, dir: String): String {
        val target = path(account, dir, "probe-${UUID.randomUUID()}.dps")
        val first = "DormPanel conditional-write probe v1".toByteArray()
        check(client.conditionalPut(account, target, first, null) in listOf(200, 201, 204)) { "Probe create failed (409/412); check folder" }
        val read = client.readResource(account, target, 512) ?: error("Probe read failed")
        check(read.bytes.contentEquals(first)) { "Server changed uploaded bytes" }
        val etag = client.requireStrongEtag(read.etag)
        check(client.conditionalPut(account, target, first, null) == 412) { "Server ignores If-None-Match; publication refused" }
        check(client.conditionalPut(account, target, first, "\"dormpanel-invalid-${UUID.randomUUID()}\"") == 412) { "Server ignores If-Match; publication refused" }
        val changed = "DormPanel verified conditional-write probe v1".toByteArray()
        check(client.conditionalPut(account, target, changed, etag) in listOf(200, 201, 204)) { "Server rejects valid If-Match" }
        val verified = client.readResource(account, target, 512) ?: error("Probe verification failed")
        check(verified.bytes.contentEquals(changed) && client.requireStrongEtag(verified.etag) != etag) { "Server does not expose changed content with a new strong ETag" }
        return "GET + strong ETag + If-None-Match:* + stale/valid If-Match verified. Probe files are retained in the shared folder."
    }
    fun publish(account: WebDavAccount, dir: String, bytes: ByteArray, expectedRevision: String?, expectedEtag: String?): String {
        val snapshot = ScheduleSnapshotCodec.decode(bytes)
        check(snapshot.parentRevision == expectedRevision) { "Snapshot parent mismatch" }
        val current = head(account, dir)
        if (expectedRevision == null) check(current == null) { "Shared folder already contains a dataset; first publication refused" }
        else {
            if (current == null || !SharedScheduleHead.parse(current.bytes).let { it.datasetId == snapshot.datasetId && it.revision == expectedRevision } ||
                current.etag != expectedEtag) throw SharedScheduleRace()
            client.requireStrongEtag(expectedEtag)
            check(snapshot.generation == inspect(account, dir).snapshot.generation + 1) { "Publication generation mismatch" }
        }
        diagnose(account, dir)
        val target = path(account, dir, "snapshot-${snapshot.revision}.dps")
        check(client.conditionalPut(account, target, bytes, null) in listOf(200, 201, 204)) { "Snapshot path conflict (409/412)" }
        val verified = client.readResource(account, target, ScheduleSnapshotCodec.MAX_BYTES) ?: error("Uploaded snapshot missing")
        check(verified.bytes.contentEquals(bytes)) { "Uploaded snapshot verification failed" }
        ScheduleSnapshotCodec.decode(verified.bytes)
        val pointer = SharedScheduleHead(snapshot.datasetId, snapshot.revision, digest(bytes))
        if (client.conditionalPut(account, path(account, dir, "dormpanel-head-v1"), pointer.bytes(), expectedEtag) !in listOf(200, 201, 204)) throw SharedScheduleRace()
        val result = head(account, dir) ?: error("Publication could not be verified; check remote before retry")
        check(result.bytes.contentEquals(pointer.bytes())) { "Remote changed after publication; check before retry" }
        return client.requireStrongEtag(result.etag)
    }
}

/** Separate preferences: never touches import bindings or the existing account identity. */
class SharedScheduleSettings(context: Context) {
    companion object { @Volatile var overrideNamespace: String? = null }
    private val prefs = context.getSharedPreferences(overrideNamespace ?: "shared_schedule_v1", Context.MODE_PRIVATE)
    var folder: String
        get() = prefs.getString("folder", "").orEmpty()
        set(v) {
            if (v.isNotBlank()) WebDavClient().url(v) // reject credentials/query before plain preferences
            check(prefs.edit().putString("folder", v).commit())
        }
    val datasetId: String get() {
        prefs.getString("dataset", null)?.let { return it }
        val value = UUID.randomUUID().toString(); check(prefs.edit().putString("dataset", value).commit()); return value
    }
    val publishedGeneration get() = prefs.getLong("published_generation", 0)
    val pendingRevision get() = prefs.getString("pending_revision", null)
    val pendingHash get() = prefs.getString("pending_hash", null)
    val pendingGeneration get() = prefs.getLong("pending_generation", 0)
    fun pending(snapshot: ScheduleSnapshot, bytes: ByteArray) {
        check(prefs.edit().putString("pending_revision", snapshot.revision).putString("pending_hash", digest(bytes))
            .putLong("pending_generation", snapshot.generation).commit())
    }
    val publishedRevision get() = prefs.getString("published_revision", null)
    val publishedEtag get() = prefs.getString("published_etag", null)
    val lastSuccess get() = prefs.getLong("last_success", 0)
    val lastResult get() = prefs.getString("last_result", "未配置").orEmpty()
    fun retryAllowed(): Boolean {
        val attempts = prefs.getInt("retry_attempts", 0) + 1
        check(prefs.edit().putInt("retry_attempts", attempts).commit())
        return attempts <= 6
    }
    fun resetRetries() { check(prefs.edit().remove("retry_attempts").commit()) }
    fun result(message: String, success: Boolean = false) {
        val edit = prefs.edit().putString("last_result", message)
        if (success) edit.putLong("last_success", System.currentTimeMillis()).remove("retry_attempts")
        check(edit.commit())
    }
    fun published(revision: String, etag: String, generation: Long) {
        check(prefs.edit().putString("published_revision", revision).putString("published_etag", etag).putLong("published_generation", generation)
            .remove("pending_revision").remove("pending_hash").remove("pending_generation").commit())
    }
}

/** UI lifecycle adapter. Durable jobs handle retry and process restarts. */
class SharedScheduleController(context: Context, private val source: ScheduleSource,
    val accounts: WebDavSettings = WebDavSettings(context), val settings: SharedScheduleSettings = SharedScheduleSettings(context),
    private val transport: SharedScheduleTransport = SharedScheduleTransport()) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var closed = false
    var busy = false; private set
    var status = settings.lastResult; private set
    var conflicts: List<ScheduleMerge.Conflict> = emptyList(); private set
    private var savedConflict = false
    var changed: (() -> Unit)? = null
    private val localListener: () -> Unit = {
        if (settings.folder.isNotBlank()) {
            status = if (savedConflict) "存在冲突 · 本地修改已保存" else "有待上传修改"
            settings.resetRetries(); settings.result(status); notifyChanged()
            ScheduleSyncJobs.schedule(this.context)
            main.removeCallbacks(automatic); main.postDelayed(automatic, 1500)
        }
    }
    private val automatic = Runnable { sync() }
    private var initialChecked = false
    private val readyListener: () -> Unit = {
        if (!closed && source.ready && !initialChecked) { initialChecked = true; sync() }
        else if (!closed && !busy) { status = settings.lastResult; notifyChanged() }
    }
    init {
        source.localCommitted = localListener; source.subscribe(readyListener); SharedScheduleViews.add(source)
        main.post { readyListener() }
    }
    private fun notifyChanged() { if (!closed) changed?.invoke() }
    private fun account() = accounts.account() ?: throw WebDavException("WebDAV credentials unavailable; configure account")
    private fun failure(error: Throwable) {
        status = when (error) {
            is ScheduleHistoryPending -> error.message!!
            is WebDavException -> if (error.reason.startsWith("Network")) "离线 / 等待重试；本地修改已保留" else error.reason
            else -> "同步失败；本地数据已保留。请检查账号、历史快照或远端版本。"
        }
        if (savedConflict) status = "存在冲突 · $status"
        settings.result(status); busy = false; notifyChanged()
        if (settings.retryAllowed()) ScheduleSyncJobs.schedule(context, 30_000)
    }
    private fun <T> run(task: () -> T, done: (T) -> Unit) {
        if (busy || closed) return
        busy = true; status = "正在同步"; notifyChanged()
        worker.execute { val result = runCatching(task); main.post { if (!closed) result.onSuccess(done).onFailure(::failure) } }
    }
    private fun complete(message: String, verified: Boolean = true) {
        settings.result(message, verified); status = message; busy = false; notifyChanged()
    }
    fun inspect(done: (SharedScheduleRemote) -> Unit) = run({ transport.inspect(account(), settings.folder) }) {
        busy = false; status = "快照已验证，等待确认"; notifyChanged(); done(it)
    }
    fun bind(remote: SharedScheduleRemote) {
        if (busy || closed) return
        busy = true; status = "正在同步"; notifyChanged()
        source.applySnapshot(remote.bytes) { if (!closed) it.onSuccess {
            complete("已同步 · 双端可编辑"); ScheduleSyncJobs.schedule(context)
        }.onFailure(::failure) }
    }
    fun sync() { settings.resetRetries(); synchronize(false) }
    fun publish() { settings.resetRetries(); synchronize(true) }
    private fun synchronize(create: Boolean) {
        if (!source.ready || settings.folder.isBlank() || source.syncStore == null) return
        run({
            synchronized(SharedScheduleRunLock) {
                val frame = source.syncStore!!.syncFrame()
                val storedConflicts = frame.conflict?.let { remote ->
                    val ancestor = if (frame.pending?.revision == remote.revision) requireNotNull(frame.captured) else requireNotNull(frame.baseline).state
                    ScheduleMerge.merge(ancestor, frame.local, remote.state).conflicts
                }.orEmpty()
                main.post { if (!closed) {
                    savedConflict = frame.conflict != null; conflicts = storedConflicts
                    if (savedConflict) { status = "正在同步 · 已保存冲突"; notifyChanged() }
                } }
                if (!create && frame.baseline == null && frame.pending == null && settings.publishedRevision == null && settings.pendingRevision == null) {
                    return@synchronized ScheduleSyncEngine.Outcome("尚未绑定共享课表") to null
                }
                val outcome = ScheduleSyncEngine(VisibleScheduleSyncStore(source.syncStore!!), transport, account(), settings.folder).synchronize(
                    if (create) settings.datasetId else null, settings.legacy())
                val base = source.syncStore!!.syncFrame().baseline
                if (base != null && base.datasetId == settings.datasetId && outcome.conflicts.isEmpty()) {
                    val head = transport.head(account(), settings.folder)
                    if (head != null && SharedScheduleHead.parse(head.bytes).revision == base.revision) {
                        settings.published(base.revision, WebDavClient().requireStrongEtag(head.etag), base.generation)
                    }
                }
                outcome to base
            }
        }) { (outcome, baseline) ->
            conflicts = outcome.conflicts
            savedConflict = conflicts.isNotEmpty()
            source.reload { result -> result.onSuccess {
                val dirty = baseline != null && !ScheduleMerge.same(baseline.state, source.state)
                complete(if (outcome.conflicts.isEmpty() && dirty) "有待上传修改" else outcome.message,
                    baseline != null && outcome.conflicts.isEmpty() && !outcome.message.startsWith("有待上传"))
                if (dirty && outcome.conflicts.isEmpty()) ScheduleSyncJobs.schedule(context)
            }.onFailure(::failure) }
            if (outcome.message.startsWith("有待上传")) ScheduleSyncJobs.schedule(context, 30_000)
        }
    }
    fun loadConflicts(done: (List<ScheduleMerge.Conflict>) -> Unit) = run({
        val frame = requireNotNull(source.syncStore).syncFrame()
        frame.conflict?.let { remote ->
            val ancestor = if (frame.pending?.revision == remote.revision) requireNotNull(frame.captured) else frame.baseline!!.state
            ScheduleMerge.merge(ancestor, frame.local, remote.state).conflicts
        }.orEmpty()
    }) { conflicts = it; busy = false; status = settings.lastResult; notifyChanged(); done(it) }
    fun resolve(choice: ScheduleMerge.Choice) = run({
        synchronized(SharedScheduleRunLock) { VisibleScheduleSyncStore(requireNotNull(source.syncStore)).resolveConflict(choice) }
    }) {
        conflicts = emptyList(); savedConflict = false
        source.reload { result -> result.onSuccess {
            complete("冲突已处理 · 有待同步", false); ScheduleSyncJobs.schedule(context); sync()
        }.onFailure(::failure) }
    }
    fun diagnose() = run({ transport.diagnose(account(), settings.folder) }) { complete(it, false) }
    fun close() {
        closed = true; changed = null; source.localCommitted = null; source.unsubscribe(readyListener)
        SharedScheduleViews.remove(source)
        main.removeCallbacks(automatic); worker.shutdown()
    }
}

internal object SharedScheduleRunLock
internal fun SharedScheduleSettings.legacy() = ScheduleSyncEngine.Legacy(datasetId, publishedRevision, publishedGeneration,
    pendingRevision, pendingHash, pendingGeneration)
