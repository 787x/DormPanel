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
        val payload = client.readResource(account, path(account, dir, "snapshot-${h.revision}.dps"), ScheduleSnapshotCodec.MAX_BYTES)
            ?: throw WebDavException("Published snapshot is missing; cached timetable retained.")
        require(digest(payload.bytes) == h.sha256) { "Published snapshot checksum failed" }
        val snapshot = ScheduleSnapshotCodec.decode(payload.bytes)
        require(snapshot.datasetId == h.datasetId && snapshot.revision == h.revision) { "Published snapshot identity mismatch" }
        return SharedScheduleRemote(h, pointer.etag, payload.bytes, snapshot)
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
            check(current != null && SharedScheduleHead.parse(current.bytes).let { it.datasetId == snapshot.datasetId && it.revision == expectedRevision } &&
                current.etag == expectedEtag) { "Remote revision changed; publication refused" }
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
        check(client.conditionalPut(account, path(account, dir, "dormpanel-head-v1"), pointer.bytes(), expectedEtag) in listOf(200, 201, 204)) {
            "Publication conflict (409/412); remote version retained"
        }
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
        set(v) { check(prefs.edit().putString("folder", v).commit()) }
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
    fun result(message: String, success: Boolean = false) {
        val edit = prefs.edit().putString("last_result", message)
        if (success) edit.putLong("last_success", System.currentTimeMillis())
        check(edit.commit())
    }
    fun published(revision: String, etag: String, generation: Long) {
        check(prefs.edit().putString("published_revision", revision).putString("published_etag", etag).putLong("published_generation", generation)
            .remove("pending_revision").remove("pending_hash").remove("pending_generation").commit())
        result("发布成功", true)
    }
}

/** Network and orchestration live outside Views. No timers or background service. */
class SharedScheduleController(context: Context, private val source: ScheduleSource,
    val accounts: WebDavSettings = WebDavSettings(context), val settings: SharedScheduleSettings = SharedScheduleSettings(context),
    private val transport: SharedScheduleTransport = SharedScheduleTransport()) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var closed = false
    var busy = false; private set
    var status = settings.lastResult; private set
    var changed: (() -> Unit)? = null
    private fun notifyChanged() { if (!closed) changed?.invoke() }
    private fun account() = accounts.account() ?: throw WebDavException("WebDAV credentials unavailable; configure account")
    private fun failure(error: Throwable) {
        // Do not surface server bodies, URLs, passwords or implementation exception text.
        status = when (error) {
            is WebDavException -> if (error.reason.startsWith("Network")) "离线 / 超时；显示缓存课表" else error.reason
            is IllegalArgumentException, is IllegalStateException -> error.message?.takeIf { it.length <= 220 } ?: "无效快照或服务器不兼容"
            else -> "同步失败；保留已缓存课表"
        }
        settings.result(status); busy = false; notifyChanged()
    }
    private fun <T> run(task: () -> T, done: (T) -> Unit) {
        if (busy || closed) return
        busy = true; status = "同步中"; notifyChanged()
        worker.execute { val result = runCatching(task); main.post { if (!closed) result.onSuccess(done).onFailure(::failure) } }
    }
    private fun complete(message: String) { settings.result(message, true); status = message; busy = false; notifyChanged() }
    fun inspect(done: (SharedScheduleRemote) -> Unit) = run({ transport.inspect(account(), settings.folder) }) {
        busy = false; status = "快照已验证，等待确认"; notifyChanged(); done(it)
    }
    fun bind(remote: SharedScheduleRemote) {
        if (busy || closed) return
        busy = true; status = "同步中"; notifyChanged()
        source.applySnapshot(remote.bytes) { if (!closed) it.onSuccess { complete("已更新 · 只读跟随") }.onFailure(::failure) }
    }
    fun sync() {
        if (!source.ready || source.state.sync == null || settings.folder.isBlank()) return
        run({ transport.inspect(account(), settings.folder) }) { remote ->
            if (remote.head.datasetId != source.state.sync?.datasetId) { failure(IllegalStateException("远端 dataset 已变化；拒绝替换")); return@run }
            // Reapply also validates content of an allegedly unchanged revision.
            val same = source.state.sync?.revision == remote.head.revision
            source.applySnapshot(remote.bytes) { if (!closed) it.onSuccess { complete(if (same) "未变化 · 只读跟随" else "已更新 · 只读跟随") }.onFailure(::failure) }
        }
    }
    fun diagnose() = run({ transport.diagnose(account(), settings.folder) }) { complete(it) }
    fun publish() {
        if (busy || closed || !source.ready) return
        busy = true; status = "同步中"; notifyChanged()
        worker.execute {
            val recovered = runCatching {
                if (settings.pendingRevision != null) {
                    val a = account()
                    val head = transport.head(a, settings.folder)?.let { SharedScheduleHead.parse(it.bytes) }
                    if (head?.revision == settings.pendingRevision) {
                        val remote = transport.inspect(a, settings.folder)
                        check(remote.head.datasetId == settings.datasetId && remote.head.sha256 == settings.pendingHash &&
                            remote.snapshot.generation == settings.pendingGeneration) { "Pending publication does not match remote" }
                        settings.published(remote.head.revision, WebDavClient().requireStrongEtag(remote.etag), remote.snapshot.generation)
                    }
                }
            }
            main.post { if (!closed) recovered.onSuccess { exportAndPublish() }.onFailure(::failure) }
        }
    }
    private fun exportAndPublish() {
        source.exportSnapshot(settings.datasetId, settings.publishedRevision, settings.publishedGeneration + 1) { result ->
            if (closed) return@exportSnapshot
            result.onSuccess { bytes ->
                worker.execute {
                    val published = runCatching {
                        val snapshot = ScheduleSnapshotCodec.decode(bytes)
                        settings.pending(snapshot, bytes)
                        val etag = transport.publish(account(), settings.folder, bytes, settings.publishedRevision, settings.publishedEtag)
                        // Save the durable receipt even when the screen closed during upload.
                        settings.published(snapshot.revision, etag, snapshot.generation)
                    }
                    main.post { if (!closed) published.onSuccess {
                        status = settings.lastResult; busy = false; notifyChanged()
                    }.onFailure(::failure) }
                }
            }.onFailure(::failure)
        }
    }
    fun close() { closed = true; changed = null; worker.shutdown() }
}
