package com.dormpanel.app.schedule

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

sealed interface ProductivityRecord {
    val id: String
    data class TodoList(override val id: String, val name: String) : ProductivityRecord
    data class Task(override val id: String, val listId: String, val text: String, val completed: Boolean,
        val position: Int) : ProductivityRecord
    data class Memo(override val id: String, val title: String, val body: String) : ProductivityRecord
}

object SharedProductivity {
    const val LIST = "todo.list"
    const val TASK = "todo.task"
    const val MEMO = "memo.text"
    private const val TITLE_BYTES = 256
    const val MAX_TASK_BYTES = 4096
    const val MAX_MEMO_BYTES = 32768
    const val MAX_TASKS = 2048
    private val uuid = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")

    private fun identity(id: String) = id.also {
        require(uuid.matches(it) && it != SyncV3Codec.ZERO) { "共享效率记录 ID 无效" }
    }

    private fun text(value: String, limit: Int, nonBlank: Boolean): ByteArray {
        require(value.length <= limit) { "文本超过 $limit UTF-8 字节" }
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= limit) { "文本超过 $limit UTF-8 字节" }
        require(bytes.toString(Charsets.UTF_8) == value && '\u0000' !in value) { "无效的纯文本" }
        require(!nonBlank || value.any { it != ' ' && it !in '\t'..'\r' }) { "文本不能为空" }
        return bytes
    }

    fun encode(value: ProductivityRecord): SharedRecord {
        val bytes = ByteArrayOutputStream()
        val type = DataOutputStream(bytes).use { out ->
            fun field(value: String, limit: Int, nonBlank: Boolean = true) {
                val raw = text(value, limit, nonBlank)
                out.writeInt(raw.size)
                out.write(raw)
            }
            when (value) {
                is ProductivityRecord.TodoList -> { field(value.name, TITLE_BYTES); LIST }
                is ProductivityRecord.Task -> {
                    out.write(identity(value.listId).toByteArray(Charsets.US_ASCII))
                    require(value.position >= 0) { "任务顺序无效" }
                    out.writeInt(value.position)
                    out.writeByte(if (value.completed) 1 else 0)
                    field(value.text, MAX_TASK_BYTES)
                    TASK
                }
                is ProductivityRecord.Memo -> { field(value.title, TITLE_BYTES); field(value.body, MAX_MEMO_BYTES, false); MEMO }
            }
        }
        return SharedRecord(type, identity(value.id), 1, required = type != MEMO,
            dependencies = if (type == TASK) listOf(LIST) else emptyList(), payload = bytes.toByteArray().toList())
    }

    private val types = setOf(LIST, TASK, MEMO)
    fun supports(record: SharedRecord) = record.schemaVersion == 1 && record.typeId in types

    fun decode(record: SharedRecord): ProductivityRecord {
        require(supports(record))
        val id = identity(record.recordId)
        val result = DataInputStream(record.payload.toByteArray().inputStream()).use { input ->
            fun take(size: Int): ByteArray {
                require(size in 0..input.available()) { "共享效率记录数据截断" }
                return ByteArray(size).also(input::readFully)
            }
            fun field(limit: Int): String {
                val size = input.readInt()
                require(size in 0..limit) { "文本超过 $limit UTF-8 字节" }
                return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(take(size))).toString()
            }
            val value = when (record.typeId) {
                LIST -> ProductivityRecord.TodoList(id, field(TITLE_BYTES))
                TASK -> {
                    val listId = take(36).toString(Charsets.US_ASCII)
                    val position = input.readInt()
                    val flag = input.readUnsignedByte()
                    require(flag in 0..1)
                    ProductivityRecord.Task(id, listId, field(MAX_TASK_BYTES), flag == 1, position)
                }
                else -> ProductivityRecord.Memo(id, field(TITLE_BYTES), field(MAX_MEMO_BYTES))
            }
            require(input.available() == 0)
            value
        }
        require(encode(result) == record) { "共享效率记录编码不规范" }
        return result
    }

    val adapters: List<SharedTypeAdapter> = listOf(
        adapter(LIST, 128, 4 + TITLE_BYTES),
        adapter(TASK, MAX_TASKS, 45 + MAX_TASK_BYTES),
        adapter(MEMO, 256, 8 + TITLE_BYTES + MAX_MEMO_BYTES)
    )

    private fun adapter(type: String, count: Int, bytes: Int) = object : SharedTypeAdapter {
        override val typeId = type
        override val schemaVersion = 1
        override val maxRecords = count
        override val maxPayload = bytes
        override fun validate(record: SharedRecord, records: List<SharedRecord>) {
            val value = decode(record)
            if (value is ProductivityRecord.Task) {
                require(records.any { it.typeId == LIST && it.recordId == value.listId && it.schemaVersion == 1 }) {
                    "任务引用的 Todo 清单不存在"
                }
            }
        }
        override fun relatedKeys(record: SharedRecord, state: ScheduleState): Set<String> =
            if (type == TASK) setOf("shared:$LIST/${(decode(record) as ProductivityRecord.Task).listId}") else emptySet()
    }

    data class Content(val lists: List<ProductivityRecord.TodoList>, val tasks: List<ProductivityRecord.Task>,
        val memos: List<ProductivityRecord.Memo>) {
        fun tasks(listId: String) = tasks.filter { it.listId == listId }
    }

    fun project(records: List<SharedRecord>): Content {
        val values = records.filter(::supports).map(::decode)
        return Content(values.filterIsInstance<ProductivityRecord.TodoList>().sortedBy { it.id },
            values.filterIsInstance<ProductivityRecord.Task>().sortedWith(
                compareBy<ProductivityRecord.Task> { it.listId }.thenBy { it.position }.thenBy { it.id }),
            values.filterIsInstance<ProductivityRecord.Memo>().sortedBy { it.id })
    }

    fun conflictRows(keys: Set<String>, base: ScheduleState, local: ScheduleState, remote: ScheduleState): List<ConflictRow> {
        val sides = listOf(base, local, remote).map { state ->
            state.sharedRecords.orEmpty().filter(::supports).associateBy { "shared:${it.key}" }
        }
        return keys.sorted().mapNotNull { key ->
            val (before, left, right) = sides.map { it[key]?.let(::decode) }
            if (before == null && left == null && right == null) null else ConflictRow(key, before, left, right)
        }
    }

    data class ConflictRow(val key: String, val base: ProductivityRecord?, val local: ProductivityRecord?,
        val remote: ProductivityRecord?)

    /** Only adds new identities. Repeating identical input preserves existing bytes. */
    fun importRecords(state: ScheduleState, additions: List<SharedRecord>): ScheduleState {
        val current = requireNotNull(state.sharedRecords).associateBy { it.key }
        require(additions.all(::supports) && additions.distinctBy { it.key }.size == additions.size)
        additions.forEach { require(current[it.key].let { old -> old == null || old == it }) { "导入身份已有不同内容" } }
        return state.copy(sharedRecords = (current.values + additions.filter { it.key !in current }).sortedBy { it.key })
            .also { SyncV3Codec.stateBytes(it) }
    }
}
