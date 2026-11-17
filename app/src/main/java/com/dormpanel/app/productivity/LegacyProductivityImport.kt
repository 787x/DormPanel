package com.dormpanel.app.productivity

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.dormpanel.app.schedule.ProductivityRecord
import com.dormpanel.app.schedule.ProductivityVersion
import com.dormpanel.app.schedule.RoomScheduleStore
import com.dormpanel.app.schedule.SharedProductivity
import com.dormpanel.app.schedule.SharedRecord
import com.dormpanel.app.schedule.SharedRecordCodec
import java.io.File
import java.util.UUID

object LegacyProductivityImport {
    enum class Kind { TODO, MEMO }
    data class Key(val owner: String, val kind: Kind)
    data class Owner(val id: String, val todos: List<TodoItem>, val memo: String,
        val todoError: String? = null, val memoError: String? = null)
    data class Item(val key: Key, val records: List<SharedRecord>, val error: String?)
    class Checked internal constructor(internal val version: ProductivityVersion, internal val records: List<SharedRecord>)

    /** Worker-thread read of a single SQLite snapshot. Mode 1 has no migration or creation path here. */
    fun scan(context: Context): List<Owner> = scan(context.getDatabasePath("productivity.db"))

    internal fun scan(file: File): List<Owner> {
        if (!file.exists()) return emptyList()
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            // Reject concurrent commits during a multi-window cursor scan.
            fun version(): Long = db.rawQuery("PRAGMA data_version", null).use {
                check(it.moveToFirst())
                it.getLong(0)
            }
            val openedVersion = version()
            val memos = linkedMapOf<String, String>()
            val todos = linkedMapOf<String, MutableList<TodoItem>>()
            val memoErrors = mutableMapOf<String, String>()
            val todoErrors = mutableMapOf<String, String>()
            // A single oversized text must not fill a CursorWindow and block every owner.
            db.rawQuery("""
                SELECT owner, 0 AS kind, '' AS id,
                    CASE WHEN length(CAST(memo AS BLOB)) <= ${SharedProductivity.MAX_MEMO_BYTES} THEN memo END AS text,
                    0 AS completed, 0 AS position, length(CAST(memo AS BLOB)) AS bytes FROM owners
                UNION ALL
                SELECT owner, 1 AS kind, id,
                    CASE WHEN length(CAST(text AS BLOB)) <= ${SharedProductivity.MAX_TASK_BYTES} THEN text END,
                    completed, position, length(CAST(text AS BLOB)) FROM todos
                ORDER BY owner, kind, position, id
            """.trimIndent(), null).use { rows ->
                while (rows.moveToNext()) {
                    val owner = rows.getString(0)
                    val bytes = rows.getLong(6)
                    if (rows.getInt(1) == 0) {
                        if (rows.isNull(3)) memoErrors[owner] = "备忘正文为 $bytes UTF-8 字节，超过 ${SharedProductivity.MAX_MEMO_BYTES}"
                        else memos[owner] = rows.getString(3)
                    } else {
                        val items = todos.getOrPut(owner, ::mutableListOf)
                        when {
                            rows.isNull(3) -> todoErrors[owner] = "任务正文为 $bytes UTF-8 字节，超过 ${SharedProductivity.MAX_TASK_BYTES}"
                            items.size >= SharedProductivity.MAX_TASKS -> todoErrors[owner] = "任务条数超过共享预算"
                            else -> items.add(TodoItem(rows.getString(2), rows.getString(3), rows.getInt(4) != 0))
                        }
                    }
                }
            }
            check(version() == openedVersion) { "本地效率数据已变化，请重新扫描" }
            (memos.keys + todos.keys + memoErrors.keys).sorted().map {
                Owner(it, todos[it].orEmpty(), memos[it].orEmpty(), todoErrors[it], memoErrors[it])
            }
        }
    }

    /** Pure conversion. The caller may supply display labels for the owner IDs. */
    fun preview(owners: List<Owner>, title: (Key) -> String = { key ->
        if (key.kind == Kind.TODO) "Todo ${key.owner}" else "备忘 ${key.owner}"
    }): List<Item> {
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

    /** Checks the exact selected records against all existing shared and course data. */
    fun check(store: RoomScheduleStore, version: ProductivityVersion, preview: List<Item>, selected: Set<Key>,
        callback: (Result<Checked>) -> Unit) {
        val result = runCatching {
            val items = preview.filter { it.key in selected }
            require(items.isNotEmpty() && items.size == selected.size && items.map { it.key }.toSet() == selected)
            items.flatMap { item ->
                require(item.error == null) { "${item.key.owner} / ${item.key.kind} · ${item.error}" }
                item.records
            }
        }
        result.fold(onSuccess = { rows ->
            store.previewProductivityImport(version, rows) { result -> callback(result.map { Checked(version, rows) }) }
        }, onFailure = { callback(Result.failure(it)) })
    }

    /** Dropping the checked preview cancels the operation. Confirmation rechecks the Writer version. */
    fun confirm(store: RoomScheduleStore, checked: Checked, callback: (Result<Unit>) -> Unit) =
        store.importProductivity(checked.version, checked.records, callback)
}
