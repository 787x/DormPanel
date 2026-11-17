package com.dormpanel.app.schedule

import org.json.JSONObject
import java.util.Base64

sealed interface ProductivityEdit {
    data class Create(val value: ProductivityRecord) : ProductivityEdit
    data class RenameList(val id: String, val name: String) : ProductivityEdit
    data class EditTask(val id: String, val text: String) : ProductivityEdit
    data class SetTaskCompleted(val id: String, val completed: Boolean) : ProductivityEdit
    data class RenameMemo(val id: String, val title: String) : ProductivityEdit
    data class EditMemo(val id: String, val body: String) : ProductivityEdit
    data class DeleteList(val id: String, val taskIds: Set<String> = emptySet()) : ProductivityEdit
    data class DeleteTask(val id: String) : ProductivityEdit
    data class DeleteMemo(val id: String) : ProductivityEdit
}

internal fun ScheduleState.editProductivity(edit: ProductivityEdit): ScheduleState {
    val rows = requireNotNull(sharedRecords) { "共享效率数据需要 DPV3/4" }
    val registry = SharedTypes.registry()
    fun record(type: String, id: String) = requireNotNull(rows.find { it.typeId == type && it.recordId == id }) {
        "记录已不存在"
    }
    fun read(type: String, id: String) = SharedProductivity.decode(record(type, id))
    fun put(value: ProductivityRecord) = registry.replace(rows, SharedProductivity.encode(value))
    fun delete(type: String, id: String): List<SharedRecord> {
        read(type, id)
        return registry.remove(rows, type, id)
    }

    val next = when (edit) {
        is ProductivityEdit.Create -> {
            val value = edit.value
            val record = SharedProductivity.encode(value)
            require(rows.none { it.key == record.key }) { "创建 ID 已使用，请重新读取" }
            registry.replace(rows, record)
        }
        is ProductivityEdit.RenameList -> put((read(SharedProductivity.LIST, edit.id) as ProductivityRecord.TodoList).copy(name = edit.name))
        is ProductivityEdit.EditTask -> put((read(SharedProductivity.TASK, edit.id) as ProductivityRecord.Task).copy(text = edit.text))
        is ProductivityEdit.SetTaskCompleted -> put((read(SharedProductivity.TASK, edit.id) as ProductivityRecord.Task).copy(completed = edit.completed))
        is ProductivityEdit.RenameMemo -> put((read(SharedProductivity.MEMO, edit.id) as ProductivityRecord.Memo).copy(title = edit.title))
        is ProductivityEdit.EditMemo -> put((read(SharedProductivity.MEMO, edit.id) as ProductivityRecord.Memo).copy(body = edit.body))
        is ProductivityEdit.DeleteTask -> delete(SharedProductivity.TASK, edit.id)
        is ProductivityEdit.DeleteMemo -> delete(SharedProductivity.MEMO, edit.id)
        is ProductivityEdit.DeleteList -> {
            val parent = record(SharedProductivity.LIST, edit.id)
            SharedProductivity.decode(parent)
            val tasks = rows.filter { it.typeId == SharedProductivity.TASK }.map {
                require(SharedProductivity.supports(it)) { "有未知任务版本，无法验证清单删除范围" }
                SharedProductivity.decode(it) as ProductivityRecord.Task
            }.filter { it.listId == edit.id }
            require(tasks.map { it.id }.toSet() == edit.taskIds) { "请明确选择清单下的所有任务" }
            rows.filterNot { it.key == parent.key || it.typeId == SharedProductivity.TASK && it.recordId in edit.taskIds }
                .also(registry::writable)
        }
    }
    return copy(sharedRecords = next)
}

data class ProductivityVersion(val datasetId: String, val revision: Long, val businessHash: String)
data class ProductivityConflict(val token: String, val revision: Long, val rows: List<SharedProductivity.ConflictRow>)
data class ProductivityView(val content: SharedProductivity.Content, val version: ProductivityVersion?,
    val writeBlock: String?, val conflict: ProductivityConflict?, val pending: Boolean,
    val baselineReadback: Boolean = false)

data class ProductivityBindingSnapshot(val version: ProductivityVersion, val records: List<SharedRecord>)

internal fun productivityFrameState(frame: JSONObject, key: String): ScheduleState =
    requireNotNull(SyncV3Codec.decode(Base64.getDecoder().decode(frame.getString(key))).state)

internal fun productivityVersion(frame: JSONObject, state: ScheduleState): ProductivityVersion? =
    frame.optJSONObject("binding")?.takeIf { it.optInt("format") == 4 }?.let {
        ProductivityVersion(it.getString("datasetId"), frame.getLong("revision"), SyncV3Codec.businessHash(state))
    }
