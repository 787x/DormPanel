package com.dormpanel.app.productivity

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import com.dormpanel.app.schedule.ProductivityRecord
import com.dormpanel.app.schedule.ProductivityVersion
import com.dormpanel.app.schedule.RoomScheduleStore
import com.dormpanel.app.schedule.SharedProductivity
import com.dormpanel.app.schedule.SharedRecord
import com.dormpanel.app.schedule.SharedRecordCodec
import java.io.File
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

object LegacyProductivityImport {
    enum class Kind { TODO, MEMO }
    data class Key(val owner: String, val kind: Kind)
    data class Owner(val id: String, val todos: List<TodoEntity>, val memo: String,
        val todoError: String? = null, val memoError: String? = null, val exists: Boolean = true)
    data class Item(val key: Key, val records: List<SharedRecord>, val error: String?)
    class Scan internal constructor(internal val file: File, val owners: List<Owner>)
    class Preview internal constructor(internal val file: File, val items: List<Item>,
        internal val fingerprints: Map<Key, String>)
    class Checked internal constructor(internal val version: ProductivityVersion, internal val records: List<SharedRecord>,
        internal val file: File, internal val fingerprints: Map<Key, String>)

    /** Evidence of a completed local import transaction. Source and target can change after commit. */
    class Confirmed internal constructor(internal val checked: Checked)

    sealed interface BindingValidation {
        data class Verified(val version: ProductivityVersion) : BindingValidation
        data object SourceChanged : BindingValidation
        data class SourceUnavailable(val cause: Throwable) : BindingValidation
        data class DatasetChanged(val actualDatasetId: String) : BindingValidation
        data class TargetChanged(val recordKeys: Set<String>) : BindingValidation
        data class TargetMissing(val recordKeys: Set<String>) : BindingValidation
        data class TargetUnavailable(val cause: Throwable) : BindingValidation
    }

    private fun <T> queued(callback: (Result<T>) -> Unit, action: () -> T) {
        ProductivityStores.legacyQueue.execute {
            val result = runCatching(action)
            Handler(Looper.getMainLooper()).post { callback(result) }
        }
    }

    /** Scan after all admitted local writes, through a read-only SQLite connection. */
    fun scan(context: Context, callback: (Result<Scan>) -> Unit) =
        scan(context.getDatabasePath("productivity.db"), callback)

    fun scan(context: Context, key: Key, callback: (Result<Scan>) -> Unit) = queued(callback) {
        val file = context.getDatabasePath("productivity.db").canonicalFile
        Scan(file, read(file, setOf(key)))
    }

    internal fun scan(file: File, callback: (Result<Scan>) -> Unit) = queued(callback) {
        val origin = file.canonicalFile
        Scan(origin, read(origin))
    }

    private fun read(file: File, selection: Set<Key>? = null): List<Owner> {
        if (!file.exists()) return emptyList()
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            // Reject concurrent commits during a multi-window cursor scan.
            fun version(): Long = db.rawQuery("PRAGMA data_version", null).use {
                check(it.moveToFirst())
                it.getLong(0)
            }
            val openedVersion = version()
            val memos = linkedMapOf<String, String>()
            val todos = linkedMapOf<String, MutableList<TodoEntity>>()
            val memoErrors = mutableMapOf<String, String>()
            val todoErrors = mutableMapOf<String, String>()
            // A single oversized text must not fill a CursorWindow and block every owner.
            val owners = selection?.map { it.owner }?.distinct().orEmpty()
            val where = if (owners.isEmpty()) "" else "WHERE owner IN (${owners.joinToString(",") { "?" }})"
            db.rawQuery("""
                SELECT owner, 0 AS kind, '' AS id,
                    CASE WHEN length(CAST(memo AS BLOB)) <= ${SharedProductivity.MAX_MEMO_BYTES} THEN memo END AS text,
                    0 AS completed, 0 AS position, length(CAST(memo AS BLOB)) AS bytes FROM owners $where
                UNION ALL
                SELECT owner, 1 AS kind, id,
                    CASE WHEN length(CAST(text AS BLOB)) <= ${SharedProductivity.MAX_TASK_BYTES} THEN text END,
                    completed, position, length(CAST(text AS BLOB)) FROM todos $where
                ORDER BY owner, kind, position, id
            """.trimIndent(), (owners + owners).toTypedArray()).use { rows ->
                while (rows.moveToNext()) {
                    val owner = rows.getString(0)
                    val bytes = rows.getLong(6)
                    if (rows.getInt(1) == 0) {
                        if (rows.isNull(3)) memoErrors[owner] = "备忘正文为 $bytes UTF-8 字节，超过 ${SharedProductivity.MAX_MEMO_BYTES}"
                        else memos[owner] = rows.getString(3)
                    } else {
                        if (selection != null && Key(owner, Kind.TODO) !in selection) continue
                        val items = todos.getOrPut(owner, ::mutableListOf)
                        when {
                            rows.isNull(3) -> todoErrors[owner] = "任务正文为 $bytes UTF-8 字节，超过 ${SharedProductivity.MAX_TASK_BYTES}"
                            items.size >= SharedProductivity.MAX_TASKS -> todoErrors[owner] = "任务条数超过共享预算"
                            else -> items.add(TodoEntity(owner, rows.getString(2), rows.getString(3), rows.getInt(4) != 0, rows.getInt(5)))
                        }
                    }
                }
            }
            check(version() == openedVersion) { "本地效率数据已变化，请重新扫描" }
            (memos.keys + todos.keys + memoErrors.keys).sorted().map {
                Owner(it, todos[it].orEmpty(), memos[it].orEmpty(), todoErrors[it], memoErrors[it], it in memos || it in memoErrors)
            }
        }
    }

    private fun fingerprint(owner: Owner, kind: Kind): String? {
        if (kind == Kind.TODO && owner.todoError != null || kind == Kind.MEMO && owner.memoError != null) return null
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
        DataOutputStream(DigestOutputStream(sink, digest)).use { out ->
            fun text(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            out.writeBoolean(owner.exists)
            text(owner.id)
            if (kind == Kind.MEMO) text(owner.memo) else {
                out.writeInt(owner.todos.size)
                owner.todos.forEach { task ->
                    text(task.id)
                    text(task.text)
                    out.writeBoolean(task.completed)
                    out.writeInt(task.position)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Pure conversion with source evidence retained for the selected records. */
    fun preview(scan: Scan, title: (Key) -> String = { key ->
        if (key.kind == Kind.TODO) "Todo ${key.owner}" else "备忘 ${key.owner}"
    }): Preview {
        val items = convertOwners(scan.owners, title)
        val owners = scan.owners.associateBy { it.id }
        val fingerprints = items.filter { it.error == null }.associate {
            it.key to requireNotNull(fingerprint(owners.getValue(it.key.owner), it.key.kind))
        }
        return Preview(scan.file, items, fingerprints)
    }

    private fun convertOwners(owners: List<Owner>, title: (Key) -> String): List<Item> {
        require(owners.distinctBy { it.id }.size == owners.size)
        return owners.flatMap { owner ->
            buildList {
                if (owner.todoError != null) add(Item(Key(owner.id, Kind.TODO), emptyList(), owner.todoError))
                else if (owner.todos.isNotEmpty()) add(convert(Key(owner.id, Kind.TODO)) { key ->
                    require(owner.todos.size <= SharedProductivity.MAX_TASKS) { "任务条数超过共享预算" }
                    val listId = identity(key)
                    listOf(ProductivityRecord.TodoList(listId, title(key))) + owner.todos.mapIndexed { position, task ->
                        ProductivityRecord.Task(identity(key, task.id), listId, task.text, task.completed, position)
                    }
                })
                if (owner.memoError != null) add(Item(Key(owner.id, Kind.MEMO), emptyList(), owner.memoError))
                else if (owner.memo.isNotEmpty()) add(convert(Key(owner.id, Kind.MEMO)) { key ->
                    listOf(ProductivityRecord.Memo(identity(key), title(key), owner.memo))
                })
            }
        }
    }

    private fun identity(key: Key, task: String = ""): String =
        UUID.nameUUIDFromBytes("DormPanel/productivity-import/1/${key.kind.name}/${key.owner.length}:${key.owner}/$task"
            .toByteArray(Charsets.UTF_8)).toString()

    private fun convert(key: Key, values: (Key) -> List<ProductivityRecord>): Item {
        val result = runCatching {
            val records = values(key).map(SharedProductivity::encode)
            SharedRecordCodec.encode(records)
            records
        }
        return Item(key, result.getOrDefault(emptyList()), result.exceptionOrNull()?.let { it.message ?: "导入数据无效" })
    }

    private fun sourceMatches(file: File, expected: Map<Key, String>): Boolean {
        val current = read(file, expected.keys).associateBy { it.id }
        return expected.all { (key, hash) -> current[key.owner]?.let { fingerprint(it, key.kind) } == hash }
    }

    private fun verify(file: File, expected: Map<Key, String>) {
        check(sourceMatches(file, expected)) { "所选本地效率数据已变化，请重新扫描" }
    }

    fun check(store: RoomScheduleStore, version: ProductivityVersion, preview: Preview, selected: Set<Key>,
        callback: (Result<Checked>) -> Unit) {
        val result = runCatching {
            val items = preview.items.filter { it.key in selected }
            require(items.isNotEmpty() && items.size == selected.size && items.map { it.key }.toSet() == selected)
            val records = items.flatMap { item ->
                require(item.error == null) { "${item.key.owner} / ${item.key.kind} · ${item.error}" }
                item.records
            }
            Checked(version, SharedRecordCodec.decode(SharedRecordCodec.encode(records)),
                preview.file, selected.associateWith(preview.fingerprints::getValue))
        }
        result.fold(onSuccess = { checked ->
            queued(callback) {
                store.previewProductivityImport(version, checked.records) { verify(checked.file, checked.fingerprints) }
                checked
            }
        }, onFailure = { callback(Result.failure(it)) })
    }

    /** The in-process source queue stays occupied until the target transaction returns. */
    fun confirm(store: RoomScheduleStore, checked: Checked, callback: (Result<Confirmed>) -> Unit) =
        queued(callback) {
            store.importProductivity(checked.version, checked.records) { verify(checked.file, checked.fingerprints) }
            Confirmed(checked)
        }

    /** Point-in-time local verification. Recheck against the active Store immediately before binding. */
    fun validateBinding(store: RoomScheduleStore, confirmed: Confirmed, callback: (Result<BindingValidation>) -> Unit) =
        validateBinding(store, confirmed, callback) {}

    /** Persist while further writes through the legacy queue are still fenced. */
    fun validateBinding(store: RoomScheduleStore, confirmed: Confirmed, callback: (Result<BindingValidation>) -> Unit,
        persist: (ProductivityVersion) -> Unit) =
        queued(callback) {
            val checked = confirmed.checked
            val matches = runCatching { sourceMatches(checked.file, checked.fingerprints) }
                .getOrElse { return@queued BindingValidation.SourceUnavailable(it) }
            if (!matches) return@queued BindingValidation.SourceChanged
            val validation = runCatching { validateTarget(store, checked) }.getOrElse { BindingValidation.TargetUnavailable(it) }
            if (validation is BindingValidation.Verified) persist(validation.version)
            validation
        }

    private fun validateTarget(store: RoomScheduleStore, checked: Checked): BindingValidation {
        val snapshot = store.readProductivityBindingSnapshot()
        if (snapshot.version.datasetId != checked.version.datasetId) {
            return BindingValidation.DatasetChanged(snapshot.version.datasetId)
        }
        val expected = checked.records.associateBy { it.key }
        val listIds = checked.records.filter { it.typeId == SharedProductivity.LIST }.map { it.recordId }.toSet()
        val actual = snapshot.records.filter { record ->
            if (record.key in expected) true
            else if (record.typeId != SharedProductivity.TASK || listIds.isEmpty()) false
            else {
                check(SharedProductivity.supports(record)) { "共享任务版本未知，无法核对清单成员" }
                (SharedProductivity.decode(record) as ProductivityRecord.Task).listId in listIds
            }
        }.associateBy { it.key }
        val missing = expected.keys - actual.keys
        if (missing.isNotEmpty()) return BindingValidation.TargetMissing(missing)
        val changed = actual.keys.filterTo(linkedSetOf()) { actual[it] != expected[it] }
        if (changed.isNotEmpty()) return BindingValidation.TargetChanged(changed)
        return BindingValidation.Verified(snapshot.version)
    }
}
