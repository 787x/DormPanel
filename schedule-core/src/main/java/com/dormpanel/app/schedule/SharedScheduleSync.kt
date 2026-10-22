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

data class SharedScheduleRemote(val head: SharedScheduleHead, val etag: String?, val bytes: ByteArray, val snapshot: ScheduleSnapshot, val commitBytes: ByteArray? = null, val witness: Map<String, String> = emptyMap(), val unverifiable: Set<String> = emptySet(), val bindingKey: String? = null)
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
    var status = settings.lastResult
        get() = if (!busy && !savedConflict && source.state.sync?.pending == true && field.startsWith("已")) "本地已保存 · 待发布" else field
        private set
    var conflicts: List<ScheduleMerge.Conflict> = emptyList(); private set
    private var savedConflict = false
    private var viewedV2Conflict: String? = null
    var changed: (() -> Unit)? = null
    private val localListener: () -> Unit = {
        source.markLocalPending()
        if (settings.folder.isNotBlank()) {
            status = if (savedConflict) "存在冲突 · 本地修改已保存" else "本地已保存 · 待发布"
            settings.resetRetries(); settings.result(status); notifyChanged()
            ScheduleSyncJobs.schedule(this.context)
            main.removeCallbacks(automatic); main.postDelayed(automatic, 1500)
        }
    }
    private val automatic = Runnable { if (source.state.sync != null) synchronize(false) }
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
            else -> "同步失败；本地数据已保留：${error.message?.take(200) ?: "请检查账号或远端版本"}"
        }
        if (savedConflict) status = "存在冲突 · $status"
        settings.result(status); busy = false
        if (source.ready && source.syncStore != null) source.reload { notifyChanged() }
        notifyChanged()
        if (settings.retryAllowed()) ScheduleSyncJobs.schedule(context, 30_000)
    }
    private fun <T> run(task: () -> T, done: (T) -> Unit) {
        if (busy || closed) return
        busy = true; status = "正在检查远端版本"; notifyChanged()
        worker.execute { val result = runCatching(task); main.post { if (!closed) result.onSuccess(done).onFailure(::failure) } }
    }
    private fun complete(message: String, verified: Boolean = true) {
        settings.result(message, verified); status = message; busy = false; notifyChanged()
    }
    private fun v2Local(): ScheduleV2Store = requireNotNull(source.syncStore) as ScheduleV2Store
    private fun v2Remote(): WebDavCommitStore {
        val account = account()
        return WebDavCommitStore(account, v2Folder(account))
    }
    private fun v2Folder(account: WebDavAccount = account()) = WebDavClient().url(transport.folder(account, settings.folder)).newBuilder()
        .addPathSegment("dormpanel-sync-v2").addPathSegment("").build().toString()
    private fun changingStore() = object : ScheduleV2Store by v2Local() {
        override fun v2Apply(plan: ScheduleV2Plan) = SharedScheduleViews.changing { v2Local().v2Apply(plan) }
        override fun v2Receipt(bytes: ByteArray) = SharedScheduleViews.changing { v2Local().v2Receipt(bytes) }
    }
    fun inspect(done: (SharedScheduleRemote) -> Unit) = run({
        ScheduleV2Binding.inspect(v2Remote())
    }) { busy = false; status = "v2 提交已验证，等待确认" + if (it.unverifiable.isNotEmpty()) " · 另有暂不可验证文件" else ""; notifyChanged(); done(it) }
    fun bind(remote: SharedScheduleRemote) = run({
        synchronized(SharedScheduleRunLock) {
            check(settings.publishedRevision == null && settings.pendingRevision == null) { "旧 v1 发布或待确认回执尚未恢复；请使用迁移入口" }
            val verified = ScheduleV2Binding.inspect(v2Remote(), remote)
            SharedScheduleViews.changing { v2Local().v2Bind(verified) }
        }
    }) { source.reload { result -> result.onSuccess { complete("v2 已绑定 · 本地已保存；其他设备接收情况未知", false); sync() }.onFailure(::failure) } }
    fun sync() { settings.resetRetries(); synchronize(false) }
    fun publish() { settings.resetRetries(); synchronize(true) }
    /** Explicit UI switch, after all old clients have been stopped. Read v1 only. */
    fun migrate() = run({
        synchronized(SharedScheduleRunLock) {
            val state = v2Local().v2Frame()
            check(state.baseline == null && state.pending == null) { "v2 已启用；禁止重复迁移旧协议" }
            val store = requireNotNull(source.syncStore)
            val verified = V1ScheduleMigration.prepare(VisibleScheduleSyncStore(store), transport, account(), settings.folder, settings.legacy())
            val frame = store.syncFrame()
            WebDavClient().makeCollection(account(), v2Folder())
            if (v2Remote().list().isEmpty()) {
                v2Local().v2Start(ScheduleCommit(verified.datasetId, UUID.randomUUID().toString(), v2Local().v2Frame().writerId, emptyList(), frame.local), false)
            } else {
                ScheduleCommitGraph(v2Remote(), verified.datasetId).load()
                v2Local().v2Migrate(requireNotNull(frame.baseline))
            }
            val outcome = AppendOnlyScheduleSync(changingStore(), v2Remote()).synchronize()
            outcome to v2Local().v2Frame().baseline?.state
        }
    }) { (outcome, baseline) -> finishV2(outcome, baseline) }
    private fun synchronize(create: Boolean) {
        if (!source.ready || settings.folder.isBlank() || source.syncStore == null) return
        run({ synchronized(SharedScheduleRunLock) {
            val frame = v2Local().v2Frame()
            savedConflict = frame.conflict != null
            if (frame.baseline == null && frame.pending == null) {
                val old = source.syncStore!!.syncFrame()
                check(old.baseline == null && old.pending == null && settings.publishedRevision == null && settings.pendingRevision == null) { "旧 v1 绑定需要明确切换；请停止所有旧客户端后迁移" }
                if (!create) return@synchronized (ScheduleSyncEngine.Outcome("尚未绑定 v2 共享课表") to null)
                WebDavClient().makeCollection(account(), v2Folder())
            }
            val outcome = AppendOnlyScheduleSync(changingStore(), v2Remote()).synchronize(if (create) settings.datasetId else null)
            outcome to v2Local().v2Frame().baseline?.state
        } }) { (outcome, baseline) -> finishV2(outcome, baseline) }
    }
    private fun finishV2(outcome: ScheduleSyncEngine.Outcome, baseline: ScheduleState?) {
        conflicts = outcome.conflicts; savedConflict = conflicts.isNotEmpty()
        source.reload { result -> result.onSuccess {
            val dirty = baseline != null && !ScheduleMerge.same(baseline, source.state)
            val message = if (dirty && outcome.conflicts.isEmpty()) "本地已保存 · 待发布" else outcome.message
            complete(message, !dirty && outcome.conflicts.isEmpty() && outcome.message.startsWith("已检查观察到的版本"))
            if (dirty && outcome.conflicts.isEmpty() && outcome.message.startsWith("已检查观察到的版本")) {
                ScheduleSyncJobs.schedule(context)
                main.removeCallbacks(automatic); main.postDelayed(automatic, 1500)
            } else if (outcome.conflicts.isEmpty() && (dirty || outcome.message.startsWith("有待上传") || outcome.message.contains("暂不可验证"))) {
                if (settings.retryAllowed()) ScheduleSyncJobs.schedule(context, 30_000)
                else { status += " · 自动重试已暂停"; settings.result(status); notifyChanged() }
            }
        }.onFailure(::failure) }
    }
    fun loadConflicts(done: (List<ScheduleMerge.Conflict>) -> Unit) = run({
        val frame = v2Local().v2Frame()
        if (frame.conflict != null) frame.conflict.merge(frame.local).conflicts to frame.conflict.token() else {
            val old = requireNotNull(source.syncStore).syncFrame()
            old.conflict?.let { remote ->
                val ancestor = if (old.pending?.revision == remote.revision) requireNotNull(old.captured) else requireNotNull(old.baseline).state
                ScheduleMerge.merge(ancestor, old.local, remote.state).conflicts
            }.orEmpty() to null
        }
    }) { (items, token) -> viewedV2Conflict = token; conflicts = items; busy = false; status = settings.lastResult; notifyChanged(); done(items) }
    fun resolve(choice: ScheduleMerge.Choice) = run({
        synchronized(SharedScheduleRunLock) { SharedScheduleViews.changing {
            if (v2Local().v2Frame().conflict != null) v2Local().v2Resolve(choice, requireNotNull(viewedV2Conflict) { "请先查看当前冲突" })
            else requireNotNull(source.syncStore).resolveConflict(choice)
        } }
    }) {
        conflicts = emptyList(); savedConflict = false; viewedV2Conflict = null
        source.reload { result -> result.onSuccess { complete("冲突决定已保存 · 待发布", false); ScheduleSyncJobs.schedule(context); sync() }.onFailure(::failure) }
    }
    fun diagnose() = run({ AppendOnlyWebDavDiagnostic.run(account(), settings.folder) }) { complete(it, false) }
    fun close() {
        closed = true; changed = null; source.localCommitted = null; source.unsubscribe(readyListener)
        SharedScheduleViews.remove(source)
        main.removeCallbacks(automatic); worker.shutdown()
    }
}

internal object SharedScheduleRunLock
internal fun SharedScheduleSettings.legacy() = ScheduleSyncEngine.Legacy(datasetId, publishedRevision, publishedGeneration,
    pendingRevision, pendingHash, pendingGeneration)
