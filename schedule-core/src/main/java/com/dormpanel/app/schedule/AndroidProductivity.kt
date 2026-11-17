package com.dormpanel.app.schedule

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

data class ProductivityTarget(val datasetId: String, val recordId: String) {
    fun encode() = "$datasetId/$recordId"

    companion object {
        fun decode(value: String?): ProductivityTarget? {
            val parts = value?.split('/') ?: return null
            return if (parts.size == 2 && parts.all { it.isNotBlank() }) ProductivityTarget(parts[0], parts[1]) else null
        }
    }
}

/** Application-scoped operations. Shared mode always goes through the active Room Writer. */
class AndroidProductivity(context: Context, val shared: RoomScheduleStore?,
    private val local: LocalProductivity? = null) {
    private val app = context.applicationContext
    private val sharedListeners = linkedSetOf<(Result<ProductivityView>) -> Unit>()
    private var sharedCache: Result<ProductivityView>? = null
    private var sharedObservation: AutoCloseable? = null

    fun load(dataset: String?, callback: (Result<ProductivityView>) -> Unit) {
        if (dataset == null && local != null) local.load(callback)
        else if (shared == null) callback(Result.failure(IllegalStateException("共享数据不可用")))
        else shared.loadProductivity { callback(it.mapCatching { view -> verify(dataset, view) }) }
    }

    fun observe(dataset: String?, callback: (Result<ProductivityView>) -> Unit): AutoCloseable {
        if (dataset == null && local != null) return local.observe(callback)
        val store = shared
        if (store != null) {
            val listener: (Result<ProductivityView>) -> Unit = { callback(it.mapCatching { view -> verify(dataset, view) }) }
            sharedListeners += listener
            if (sharedObservation == null) sharedObservation = store.observeProductivity { result ->
                sharedCache = result
                sharedListeners.toList().forEach { it(result) }
            }
            sharedCache?.let(listener)
            return AutoCloseable {
                sharedListeners -= listener
                if (sharedListeners.isEmpty()) {
                    sharedObservation?.close()
                    sharedObservation = null
                    sharedCache = null
                }
            }
        }
        load(dataset, callback)
        return AutoCloseable {}
    }

    private fun verify(dataset: String?, view: ProductivityView): ProductivityView {
        check(dataset == null || view.version?.datasetId == dataset) { "共享数据集已变化" }
        return view
    }

    fun save(expected: ProductivityVersion, edits: List<ProductivityEdit>, callback: (Result<Unit>) -> Unit,
        validate: () -> Unit = {}) {
        fun submit(view: ProductivityView) {
            check(view.version == expected) { "数据已变化，请关闭编辑后重新读取" }
            check(view.writeBlock == null) { view.writeBlock.orEmpty() }
            if (local?.identity == expected.datasetId) local.edit(expected, edits, callback, validate)
            else requireNotNull(shared).editProductivity(expected, edits, callback, validate)
        }
        val read: (Result<ProductivityView>) -> Unit = { result ->
            val error = runCatching { submit(result.getOrThrow()) }.exceptionOrNull()
            if (error != null) callback(Result.failure(error))
        }
        if (local?.identity == expected.datasetId) local.load(read) else load(expected.datasetId, read)
    }

    fun resolve(expected: ProductivityVersion, conflict: ProductivityConflict, choice: ScheduleMerge.Choice,
        callback: (Result<Unit>) -> Unit) = work(callback) {
        requireNotNull(shared).resolveProductivityConflict(expected, conflict, choice)
        ScheduleSyncJobs.schedule(app)
    }

    fun recover(callback: (Result<Unit>) -> Unit) = work(callback) {
        synchronized(SharedScheduleRunLock) { requireNotNull(shared).reopenV3Store() }
    }

    fun observeSync(listener: () -> Unit): AutoCloseable {
        val update: (SharedSyncUpdate) -> Unit = { listener() }
        ScheduleSyncJobs.addListener(update)
        val writer = shared?.observeProductivitySync(listener)
        return AutoCloseable { writer?.close(); ScheduleSyncJobs.removeListener(update) }
    }

    fun syncFacts(callback: (Result<SharedSchedulePresentation>) -> Unit) {
        val busy = ScheduleSyncJobs.latestRunning != null
        work(callback) {
            val store = requireNotNull(shared)
            val fact = store.productivitySyncFacts()
            val scope = runCatching { sharedCheckScope(store.v3Account(WebDavSettings(app)), store.v3SyncFolder()) }.getOrNull()
            val (code, time) = SharedScheduleSettings(app).checkFor(scope)
            fact.copy(busy = busy || fact.busy, result = code, lastSuccess = time)
        }
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())
        fun <T> work(callback: (Result<T>) -> Unit, operation: () -> T) {
            worker.execute {
                val result = runCatching(operation)
                main.post { callback(result) }
            }
        }
        fun id(): String = UUID.randomUUID().toString()
    }
}

/** Independent Phone-local data. The complete canonical record block and revision commit together. */
class LocalProductivity(context: Context, fileName: String = "phone-productivity") {
    private val file = AtomicFile(context.getFileStreamPath(fileName))
    internal val identity = "local"
    private val listeners = linkedSetOf<(Result<ProductivityView>) -> Unit>()

    private data class Snapshot(val revision: Long, val bytes: ByteArray, val records: List<SharedRecord>)

    private fun read(): Snapshot {
        val data = try { JSONObject(String(file.readFully(), Charsets.UTF_8)) }
        catch (error: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return Snapshot(0, SharedRecordCodec.encode(emptyList()), emptyList())
        }
        val bytes = java.util.Base64.getDecoder().decode(data.getString("records"))
        val records = SharedRecordCodec.decode(bytes)
        SharedTypes.registry().validate(records)
        val revision = data.getLong("revision")
        check(revision >= 0) { "本机效率数据版本无效" }
        return Snapshot(revision, bytes, records)
    }

    private fun Snapshot.version() = ProductivityVersion(identity, revision,
        java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))

    private fun Snapshot.view() = ProductivityView(SharedProductivity.project(records), version(), null, null, false)

    fun load(callback: (Result<ProductivityView>) -> Unit) = AndroidProductivity.work(callback) { read().view() }

    fun observe(callback: (Result<ProductivityView>) -> Unit): AutoCloseable {
        var active = true
        val listener: (Result<ProductivityView>) -> Unit = { if (active) callback(it) }
        listeners.add(listener)
        load(listener)
        return AutoCloseable { active = false; listeners.remove(listener) }
    }

    internal fun edit(expected: ProductivityVersion, edits: List<ProductivityEdit>, callback: (Result<Unit>) -> Unit,
        validate: () -> Unit = {}) {
        AndroidProductivity.work({ result: Result<ProductivityView> ->
            if (result.isSuccess) listeners.toList().forEach { it(result) }
            callback(result.map { Unit })
        }) {
            val before = read()
            check(before.version() == expected) { "本机数据已变化" }
            validate()
            val next = requireNotNull(edits.fold(ScheduleState(sharedRecords = before.records)) { state, edit ->
                state.editProductivity(edit)
            }.sharedRecords)
            val bytes = SharedRecordCodec.encode(next)
            if (before.bytes.contentEquals(bytes)) return@work before.view()
            val revision = Math.incrementExact(before.revision)
            val data = JSONObject().put("revision", revision)
                .put("records", java.util.Base64.getEncoder().encodeToString(bytes)).toString()
            val output = file.startWrite()
            try {
                output.write(data.toByteArray(Charsets.UTF_8))
            output.fd.sync()
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
            read().also { check(it.revision == revision && it.bytes.contentEquals(bytes)) { "本机保存结果待确认" } }.view()
        }
    }

    fun withVersion(version: ProductivityVersion, bind: () -> Unit) {
        check(read().version() == version) { "本机内容已变化" }
        bind()
    }

    fun transfer(store: RoomScheduleStore, source: ProductivityVersion, target: ProductivityVersion,
        confirm: Boolean, bind: () -> Unit, callback: (Result<Unit>) -> Unit) = AndroidProductivity.work(callback) {
        val before = read()
        check(before.version() == source) { "本机导入范围已变化" }
        val validate = { check(read().version() == source) { "本机导入范围已变化" } }
        val lists = before.records.filter { it.typeId == SharedProductivity.LIST }.map { it.recordId }.toSet()
        val imported = before.records.toSet()
        fun checkMembership(snapshot: ProductivityBindingSnapshot) {
            check(SharedProductivity.project(snapshot.records).tasks.none {
                it.listId in lists && SharedProductivity.encode(it) !in imported
            }) { "共享清单包含其他任务，请重新核对" }
        }
        val existing = store.readProductivityBindingSnapshot()
        check(existing.version == target) { "共享目标已变化" }
        checkMembership(existing)
        if (confirm) {
            store.importProductivity(target, before.records, validate)
            val after = store.readProductivityBindingSnapshot()
            check(after.version.datasetId == target.datasetId && after.records.containsAll(imported)) { "共享目标已变化" }
            checkMembership(after)
            store.withProductivityBinding(after.version, bind)
        } else store.previewProductivityImport(target, before.records, validate)
    }
}

/** Small durable configuration file. Cache is updated only after exact readback. */
class ProductivityBindings(context: Context, name: String) {
    private val file = AtomicFile(context.getFileStreamPath(name))
    private var values: JSONObject? = null

    @Synchronized fun get(key: String): String? = load().optString(key).ifEmpty { null }

    @Synchronized fun setIf(key: String, expected: String?, value: String?) {
        check(get(key) == expected) { "数据来源已变化，请重新选择" }
        set(key, value)
    }

    @Synchronized fun set(key: String, value: String?) {
        val next = JSONObject(load().toString())
        if (value == null) next.remove(key) else next.put(key, value)
        val bytes = next.toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        check(file.readFully().contentEquals(bytes)) { "数据来源设置保存失败" }
        values = next
    }

    private fun load(): JSONObject = values ?: try {
        JSONObject(String(file.readFully(), Charsets.UTF_8))
    } catch (error: java.io.FileNotFoundException) {
        if (file.baseFile.exists()) throw error
        JSONObject()
    }.also { values = it }
}

fun ProductivityView.status(local: Boolean, facts: SharedSchedulePresentation? = null): String = when {
    local -> "仅本机"
    conflict != null -> "共享冲突"
    writeBlock != null -> "共享只读 · $writeBlock"
    facts?.busy == true -> "共享 · 正在同步"
    facts?.receipting == true -> "共享 · 服务器回读已确认，待保存本机回执"
    facts?.confirming == true -> "共享 · 等待服务器回读确认"
    pending -> "共享 · 本机已保存，有修改待发布"
    facts?.result in setOf("NETWORK", "ERROR") -> "共享 · 本机已保存，服务器检查失败"
    facts != null && facts.lastSuccess > 0 && System.currentTimeMillis() - facts.lastSuccess > 86_400_000 ->
        "共享 · 本机已保存，服务器状态可能过期"
    baselineReadback -> "共享 · 服务器回读已确认"
    else -> "共享 · 本机已保存"
}
