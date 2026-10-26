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
    var presentationResult: String
        get() = prefs.getString("presentation_result", "UNCHECKED").orEmpty()
        set(value) { check(prefs.edit().putString("presentation_result", value).commit()) }
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
    var presentation = SharedSchedulePresentation(); private set
    private var presentationGeneration = 0
    fun refreshPresentation() {
        if (closed) return
        val generation = ++presentationGeneration
        val pending = source.state.sync?.pending == true
        worker.execute {
            val facts = runCatching {
                if (!source.ready && !source.error) SharedSchedulePresentation() else source.syncStore?.sharedPresentation(settings.folder.isNotBlank() && accounts.account() != null)
                    ?: SharedSchedulePresentation(mode = SharedMode.UNKNOWN, ready = true)
            }.getOrElse { SharedSchedulePresentation(mode = SharedMode.UNKNOWN, ready = source.ready || source.error) }
            main.post { if (!closed && generation == presentationGeneration) {
                val next = facts.copy(mode = if (facts.mode in setOf(SharedMode.UNBOUND, SharedMode.UNCONFIGURED) && (settings.publishedRevision != null || settings.pendingRevision != null)) SharedMode.V1 else facts.mode,
                    confirming = facts.confirming || settings.pendingRevision != null, pending = facts.pending || pending, busy = busy,
                    result = if (facts.result == "PARTIAL" && settings.presentationResult !in setOf("NETWORK", "ERROR")) facts.result else settings.presentationResult, lastSuccess = settings.lastSuccess)
                if (next != presentation) { presentation = next; changed?.invoke() }
            } }
        }
    }
    private val localListener: () -> Unit = {
        source.markLocalPending()
        if (settings.folder.isNotBlank() && (presentation.bound || source.state.sync != null)) {
            status = if (savedConflict) "存在冲突 · 本地修改已保存" else "本地已保存 · 待发布"
            settings.resetRetries(); settings.result(status); notifyChanged()
            ScheduleSyncJobs.schedule(this.context)
            main.removeCallbacks(automatic); main.postDelayed(automatic, 1500)
        }
    }
    private val automatic = Runnable { if (source.state.sync != null) synchronize(false) }
    private var initialChecked = false
    private val readyListener: () -> Unit = {
        if (!closed && source.ready && !initialChecked) { initialChecked = true; refreshPresentation(); sync() }
        else if (!closed && !busy) { status = settings.lastResult; notifyChanged() }
    }
    init {
        source.localCommitted = localListener; source.subscribe(readyListener); SharedScheduleViews.add(source)
        main.post { readyListener() }
    }
    private fun notifyChanged() { if (!closed) {
        val next = presentation.copy(busy = busy)
        if (next != presentation) { presentation = next; changed?.invoke() }
        refreshPresentation()
    } }
    private fun account() = accounts.account() ?: throw WebDavException("WebDAV credentials unavailable; configure account")
    private fun failure(error: Throwable) {
        settings.presentationResult = when (error) {
            is ScheduleHistoryPending -> "DISCOVERING"
            is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "NETWORK"
            is WebDavException -> if (error.reason.startsWith("Network")) "NETWORK" else "ERROR"
            else -> "ERROR"
        }
        status = when (error) {
            is ScheduleHistoryPending -> error.message!!
            is WebDavException -> if (error.reason.startsWith("Network")) "离线 / 等待重试；本地修改已保留" else error.reason
            is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "离线 / 等待重试；本地修改已保留"
            is android.database.sqlite.SQLiteException -> "本地事务需要恢复；数据已保留，请重开活动 Store"
            else -> {
                val message=error.message.orEmpty()
                if(message.contains("reopen")) "本地事务确认中断；已停止写入，请重开活动 Store"
                else if(Regex("(?i)(?:/data/|/var/|/vol[0-9]/|/home/|/proc/|/tmp/|[a-z]:\\\\|password=|token=)").containsMatchIn(message)) "本地或远端验证失败；数据与证据保留，请检查恢复状态"
                else "同步失败；本地数据已保留：${message.take(200).ifBlank { "请检查账号或远端版本" }}"
            }
        }
        if (savedConflict) status = "存在冲突 · $status"
        settings.result(status); busy = false
        if (source.ready && source.syncStore != null) source.reload { notifyChanged() }
        notifyChanged()
        if (presentation.bound && settings.retryAllowed()) ScheduleSyncJobs.schedule(context, 30_000)
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
        check(!requireNotNull(source.syncStore).isV3()) { "当前是新版共享课表，请使用立即同步" }
        ScheduleV2Binding.inspect(v2Remote())
    }) {
        busy = false; status = "v2 提交已验证，等待确认" + if (it.unverifiable.isNotEmpty()) " · 另有暂不可验证文件" else ""
        settings.presentationResult = if (it.unverifiable.isEmpty()) "VERIFIED" else "PARTIAL"
        settings.result(status, it.unverifiable.isEmpty()); notifyChanged(); done(it)
    }
    fun bind(remote: SharedScheduleRemote) = run({
        synchronized(SharedScheduleRunLock) {
            check(settings.publishedRevision == null && settings.pendingRevision == null) { "旧 v1 发布或待确认回执尚未恢复；请使用迁移入口" }
            val verified = ScheduleV2Binding.inspect(v2Remote(), remote)
            SharedScheduleViews.changing { v2Local().v2Bind(verified) }
        }
    }) { source.reload { result -> result.onSuccess { complete("v2 已绑定 · 本地已保存；其他设备接收情况未知", false); sync() }.onFailure(::failure) } }
    private fun migrationRemote(folder: String): CommitStore {
        val a = account()
        val backend = WebDavCommitStore(a, WebDavClient().url(transport.folder(a, folder)).newBuilder().addPathSegment("dormpanel-sync-v2").addPathSegment("").build().toString())
        return object : CommitStore by backend {
            override fun list(): List<String> {
                val ids = backend.list()
                return if (!v2Local().v2DatasetPaused()) ids else v2Local().v2MigrationPaths(backend.bindingKey, ids)
            }
        }
    }
    fun prepareDatasetSwitch(folder: String, datasetId: String, done: (ScheduleDatasetMigration) -> Unit) = run({
        synchronized(SharedScheduleRunLock) {
            check(settings.pendingRevision == null) { "旧 v1 待确认发布尚未恢复" }
            val store = v2Local()
            val newFolder = transport.folder(account(), folder)
            store.v2BeginDatasetInspection(settings.folder, newFolder, datasetId)
            val (old, target) = ScheduleDatasetSwitch.inspect(store, migrationRemote(settings.folder), migrationRemote(newFolder))
            check(target.head.datasetId == datasetId) { "目标 datasetId 不符" }
            val j = ScheduleDatasetMigration(UUID.randomUUID().toString(), "prepared", settings.folder, newFolder, old, target, store.v2Frame().writerId)
            store.v2PrepareMigration(j); j
        }
    }) { busy = false; status = "换库预览已持久保存；原自动同步及后台重试已暂停"; notifyChanged(); done(it) }
    fun inspectDatasetSwitch(done: (ScheduleDatasetMigration?, List<String>?, String?) -> Unit) = run({ Triple(v2Local().v2Migration(), v2Local().v2DatasetInspection(), v2Local().v2DatasetResult()) }) { busy = false; notifyChanged(); done(it.first, it.second, it.third) }
    fun cancelDatasetInspection() = run({ synchronized(SharedScheduleRunLock) { v2Local().v2CancelDatasetInspection() } }) { complete("已取消未完成预检；原课程与观察证据保留", false) }
    fun commitDatasetSwitch(id: String) = run({
        synchronized(SharedScheduleRunLock) {
            val store = v2Local(); val j = requireNotNull(store.v2Migration()); check(j.id == id)
            if (j.phase == "prepared") {
                check(settings.folder == j.oldFolder) { "旧目录配置变化，请恢复后继续" }
                ScheduleDatasetSwitch.inspect(store, migrationRemote(j.oldFolder), migrationRemote(j.newFolder), j)
                store.v2CommitMigration(id)
            }
            // Journal remains active across this SharedPreferences write and every restart.
            // Jobs/core refuse all sync until final local verification and explicit release.
            settings.folder = j.newFolder
        }
    }) { source.reload { complete("本机已切换到新数据集；保持停写。请在 FPK 登记验收，待管理员允许后完成验收并恢复同步", false) } }
    fun finishDatasetSwitch(id: String, cancel: Boolean = false) = run({
        synchronized(SharedScheduleRunLock) {
            val store = v2Local(); val j = requireNotNull(store.v2Migration()); check(j.id == id)
            if (cancel) {
                check(j.phase == "prepared") { "已经切换，禁止简单反向覆盖旧库" }
                settings.folder = j.oldFolder; store.v2FinishMigration(id, true)
            } else {
                check(j.phase == "committed")
                val oldBackend = migrationRemote(j.oldFolder)
                check(oldBackend.bindingKey == j.source.bindingKey)
                val old = ScheduleCommitGraph(oldBackend, j.source.head.datasetId).load(j.source.witness.keys.toList())
                check(old.deferred.isEmpty() && old.commits.mapValues { digest(ScheduleCommitCodec.encode(it.value)) } == j.source.witness) { "旧源变化，需要重新检查；禁止跨库合并" }
                val target = ScheduleV2Binding.inspect(migrationRemote(j.newFolder), j.target)
                check(store.v2Frame().writerId == j.writerId && ScheduleMerge.same(store.v2Frame().local, target.snapshot.state)) { "本机数据或身份变化" }
                settings.folder = j.newFolder
                store.v2FinishMigration(id)
            }
        }
    }) { source.reload { complete(if (cancel) "已取消准备；原数据与证据保留" else "Android 本机换库验收完成；恢复新库正常同步，其他设备由管理员验收", false); sync() } }
    fun sync() { settings.resetRetries(); synchronize(false) }
    fun publish() { settings.resetRetries(); synchronize(true) }
    fun createV3() = activateV3(null)
    fun reopenV3Store() = run({ synchronized(SharedScheduleRunLock) { SharedScheduleViews.changing { source.syncStore!!.reopenV3Store() } } }) {
        source.reload { complete("活动 v3 Store 已从同一数据库恢复；课程、候选与绑定保留",false) }
    }
    fun cancelUnpublishedV3Migration() = run({ synchronized(SharedScheduleRunLock) { source.syncStore!!.cancelUnpublishedV3Migration() } }) {
        source.reload { complete("已取消未创建目标的迁移；原 v2 本地修改和基线已恢复，证据保留", false) }
    }
    fun auditV3MigrationSource() = run({ synchronized(SharedScheduleRunLock) { source.syncStore!!.auditV3MigrationSource(account()) } }) { complete(it, false) }
    fun previewV3Migration(done: (ScheduleV3MigrationPreview) -> Unit) = run({
        synchronized(SharedScheduleRunLock) { requireNotNull(source.syncStore).previewV3Migration(v2Remote(), v2Folder()) }
    }) { busy = false; status = "v2 源历史及完整本地快照已验证；尚未切换"; notifyChanged(); done(it) }
    fun migrateV3(preview: ScheduleV3MigrationPreview) = run({
        synchronized(SharedScheduleRunLock) { requireNotNull(source.syncStore).prepareV3Migration(preview, v2Remote(), transport.folder(account(), settings.folder)) }
    }) { source.reload { complete("源 Writer 已持久暂停；v3 迁移等待精确回读及源重检", false); sync() } }
    fun previewExistingV3(descriptor:ByteArray,done:(ExistingV3MigrationPreview)->Unit) = run({
        synchronized(SharedScheduleRunLock) { source.syncStore!!.previewExistingV3Migration(v2Remote(),v2Folder(),account(),transport.folder(account(),settings.folder),descriptor) }
    }) { preview ->
        busy=false
        status=if(preview==null) "目标只读预检进度已保存；请再次选择同一目标继续" else "源与既有 v3 目标完整 RAW 等价；尚未切换"
        settings.result(status,false); notifyChanged(); if(preview!=null) done(preview)
    }
    fun joinMigratedV3(preview:ExistingV3MigrationPreview) = run({
        synchronized(SharedScheduleRunLock) { source.syncStore!!.prepareExistingV3Migration(preview,v2Remote(),account()) }
    }) { source.reload { complete("本机 v2 Writer 已暂停；已加入经验证的同一 v3 目标，未创建新 Genesis",false); sync() } }
    fun previewJoinV3(descriptor:ByteArray,done:(UnboundV3JoinPreview?)->Unit) = run({
        synchronized(SharedScheduleRunLock) { source.syncStore!!.previewUnboundV3Join(account(),transport.folder(account(),settings.folder),descriptor) }
    }) { preview ->
        busy=false;status=if(preview==null) "只读预检进度已保存，尚未绑定；请选择同一目标继续" else "目标身份、前沿及完整业务快照已验证，尚未绑定"
        settings.result(status,false);notifyChanged();done(preview)
    }
    fun joinV3(preview:UnboundV3JoinPreview) = run({
        synchronized(SharedScheduleRunLock) { source.syncStore!!.joinVerifiedV3(preview,account()) }
    }) { source.reload { complete("已加入指定 v3 身份；目标快照经精确回读，本地已保存",true);sync() } }
    private fun activateV3(descriptor: ByteArray?) = run({
        synchronized(SharedScheduleRunLock) {
            check(settings.publishedRevision == null && settings.pendingRevision == null) { "已有 v1 绑定，禁止隐式切换" }
            requireNotNull(source.syncStore).prepareV3(transport.folder(account(), settings.folder), descriptor)
        }
    }) { source.reload { complete("v3 本地绑定意图已保存；等待远端验证", false); sync() } }
    data class V3Candidate(val descriptor: V3Object, val bytes: ByteArray, val hash: String)
    fun discoverV3(done: (List<V3Candidate>) -> Unit) = run({
        val remote = V3WebDavStore(account(), transport.folder(account(), settings.folder), V3Budget())
        remote.list("").flatMap { scope ->
            if (!scope.matches(Regex("dataset-[0-9a-f-]{36}/"))) emptyList() else remote.list(scope).filter { it.substringAfterLast('/').startsWith("descriptor-") }.map { path ->
                val raw = requireNotNull(remote.read(path)); val d = V3Paths.match(path, raw); require(d.type == 1)
                V3Candidate(d, raw, SyncV3Codec.hash(raw))
            }
        }
    }) { busy = false; status = "v3 Descriptor 已验证；完整历史仍需验证，尚未绑定"; notifyChanged(); done(it) }
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
        if (source.syncStore!!.isV3()) {
            run({ synchronized(SharedScheduleRunLock) { source.syncStore!!.synchronizeV3(source.syncStore!!.v3Account(accounts)) } }) { result ->
                SharedScheduleViews.reloaded()
                savedConflict = result == "CONFLICT"
                source.reload { loaded -> loaded.onSuccess {
                    settings.presentationResult = result
                    complete(v3StatusMessage(result), result in listOf("OBSERVED","PUBLISHED"))
                    if (result in listOf("BUDGET", "DISCOVERING", "ROTATED") && settings.retryAllowed()) ScheduleSyncJobs.schedule(context, 30_000)
                }.onFailure(::failure) }
            }
            return
        }
        if (!create && source.state.sync == null && settings.publishedRevision == null && settings.pendingRevision == null) {
            notifyChanged(); return // Opening settings must not allocate a v2 Writer for an unbound local timetable.
        }
        run({ synchronized(SharedScheduleRunLock) {
            v2Local().v2RequireSyncAllowed()
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
    fun endpointPreview(candidate:WebDavAccount,folder:String,done:(org.json.JSONObject)->Unit) = endpointOperation(done) {
        source.syncStore!!.previewV3Endpoint(candidate,transport.folder(candidate,folder),accounts)
    }
    fun endpointContinue(token:String,done:(org.json.JSONObject)->Unit) = endpointOperation(done) { source.syncStore!!.continueV3Endpoint(token,accounts) }
    fun endpointConfirm(token:String,done:(org.json.JSONObject)->Unit) = endpointOperation(done) { source.syncStore!!.confirmV3Endpoint(token,accounts) }
    fun endpointCancel(token:String,done:(org.json.JSONObject)->Unit) = endpointOperation(done) { source.syncStore!!.cancelV3Endpoint(token) }
    private fun endpointOperation(done:(org.json.JSONObject)->Unit,operation:()->org.json.JSONObject) = run({
        synchronized(SharedScheduleRunLock) { runCatching(operation).getOrElse {
            org.json.JSONObject().put("status","OPERATION_FAILED").put("failure","端点操作未完成；绑定、本地数据及恢复证据保留，请关闭后重新查询")
        } }
    }) { result -> busy=false;status=when(result.optString("status")) {
        "READY"->"候选身份及已知历史验证完成，尚未切换"
        "COMMITTED"->"本机 v3 访问端点已原子切换；本地修改保留，其他设备接收未知"
        "CANCELLED"->"端点预检已取消，原绑定保留"
        "EXPIRED","STALE"->"端点预检已到期或失效，请开始新预检；原绑定保留"
        "OPERATION_FAILED"->"端点操作已安全停止，请关闭后重新查询；原绑定和证据保留"
        else->"端点只读预检进度已保存，可继续"
    };settings.result(status,false);notifyChanged();done(result) }
    private fun finishV2(outcome: ScheduleSyncEngine.Outcome, baseline: ScheduleState?) {
        settings.presentationResult = if (outcome.conflicts.isNotEmpty()) "CONFLICT" else outcome.checkState
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
        if (source.syncStore?.isV3() == true) {
            val conflict = source.syncStore!!.v3Control()?.optJSONObject("conflict")
            val rows = conflict?.getJSONArray("conflicts")
            return@run (if (rows == null) emptyList() else (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
                ScheduleMerge.Conflict(it.getString("key"), it.getString("description"))
            } }) to conflict?.getString("id")
        }
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
            if (source.syncStore?.isV3() == true) source.syncStore!!.resolveV3(requireNotNull(viewedV2Conflict) { "请先查看当前冲突" }, choice)
            else if (v2Local().v2Frame().conflict != null) v2Local().v2Resolve(choice, requireNotNull(viewedV2Conflict) { "请先查看当前冲突" })
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
