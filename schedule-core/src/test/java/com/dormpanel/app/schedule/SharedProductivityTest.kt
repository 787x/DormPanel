package com.dormpanel.app.schedule

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharedProductivityTest {
    private val vector = listOf(File("../test-vectors"), File("test-vectors")).first { it.isDirectory }
        .resolve("sync-v3-productivity/vector.json").readText().let(::JSONObject)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private val listId = "00000000-0000-0000-0000-000000000001"
    private val task = ProductivityRecord.Task("00000000-0000-0000-0000-000000000002", listId, "  茶 ☕  ", true, 7)
    private val memo = ProductivityRecord.Memo("00000000-0000-0000-0000-000000000003", "Plan 🌙", "一\r\n二\n\uFEFF")
    private val empty = ProductivityRecord.Memo("00000000-0000-0000-0000-000000000004", "Empty", "")
    private val base = ScheduleState(sharedRecords = listOf(ProductivityRecord.TodoList(listId, "购物"), task, memo, empty)
        .map(SharedProductivity::encode))

    @Test fun canonicalAndInvalidRecords() {
        val records = base.sharedRecords!!
        assertEquals(vector.getString("recordBlockHex"), hex(SharedRecordCodec.encode(records)))
        assertEquals(vector.getString("rawHex"), hex(SyncV3Codec.stateBytes(base)))
        assertEquals(vector.getString("businessHash"), SyncV3Codec.businessHash(base))
        val registry = SharedTypes.registry()
        assertEquals(memo, SharedProductivity.project(records).memos.first())
        for (bad in listOf(records + records.first(), records.drop(1),
            records.map { if (it.typeId == SharedProductivity.TASK) it.copy(required = false) else it },
            records.map { if (it.typeId == SharedProductivity.MEMO) it.copy(payload = it.payload + 0.toByte()) else it })) {
            assertTrue(runCatching { registry.validate(bad) }.isFailure)
        }
        for (text in listOf(" \n\t", "甲".repeat(1366), "\uD800", "a\u0000b")) {
            assertTrue(runCatching { SharedProductivity.encode(task.copy(text = text)) }.isFailure)
        }
        val many = List(33) { index -> SharedProductivity.encode(memo.copy(id = java.util.UUID(0, index.toLong() + 1).toString(), body = "a".repeat(32768))) }
        assertTrue(runCatching { registry.validate(many) }.isFailure)
        val old = SharedTypeRegistry(listOf(SharedCourseColor, SharedCourseSchedule))
        old.validate(records)
        assertThrows(IllegalArgumentException::class.java) { old.writable(records) }
        old.writable(records.filter { it.typeId == SharedProductivity.MEMO })
        assertThrows(IllegalArgumentException::class.java) {
            registry.validate(records.map { if (it.typeId == SharedProductivity.TASK) it.copy(dependencies = emptyList()) else it })
        }
        val a = base.copy(entries = listOf(TimetableEntry("x", "旧客户端新课程", 1, 480, 540)))
        assertEquals(records.toSet(), ScheduleMerge.merge(base, a, base, sharedRegistry = old).state.sharedRecords!!.toSet())
        assertTrue(runCatching { ScheduleState().editProductivity(ProductivityEdit.DeleteMemo(memo.id)) }.isFailure)
    }

    @Test fun independentEditsAndLinkedDeletion() {
        val secondList = ProductivityRecord.TodoList("00000000-0000-0000-0000-000000000005", "分类")
        val secondTask = task.copy(id = "00000000-0000-0000-0000-000000000006", text = "第二条")
        val createTask = ProductivityEdit.Create(secondTask)
        val source = base.editProductivity(ProductivityEdit.Create(secondList)).editProductivity(createTask)
        val left = source.editProductivity(ProductivityEdit.SetTaskCompleted(task.id, false))
            .editProductivity(ProductivityEdit.EditMemo(memo.id, "本地\n第二行"))
        val right = source.editProductivity(ProductivityEdit.EditTask(secondTask.id, "远端"))
            .editProductivity(ProductivityEdit.RenameMemo(empty.id, "远端改名"))
        val merged = ScheduleMerge.merge(source, left, right)
        assertTrue(merged.conflicts.isEmpty())
        val view = SharedProductivity.project(merged.state.sharedRecords!!)
        assertEquals(listOf(listId, secondList.id), view.lists.map { it.id })
        assertEquals(listOf(task.id, secondTask.id), view.tasks(listId).map { it.id })
        assertFalse(view.tasks.first().completed)
        assertEquals("远端", view.tasks.last().text)
        assertEquals("本地\n第二行", view.memos.first().body)
        assertTrue(ScheduleMerge.merge(source, left, left).conflicts.isEmpty())

        val different = source.editProductivity(ProductivityEdit.EditMemo(memo.id, "另一份\r\n"))
            .editProductivity(ProductivityEdit.EditTask(task.id, "同一任务的远端修改"))
        val conflict = ScheduleMerge.merge(source, left, different)
        val sides = SharedProductivity.conflictRows(conflict.conflicts.flatMap { it.keys }.toSet(), source, left, different)
        assertEquals(2, sides.size)
        assertEquals("本地\n第二行", (sides.first().local as ProductivityRecord.Memo).body)
        assertEquals("另一份\r\n", (sides.first().remote as ProductivityRecord.Memo).body)
        val taskDeleted = source.editProductivity(ProductivityEdit.DeleteTask(task.id))
        assertEquals(1, ScheduleMerge.merge(source, taskDeleted, left).conflicts.size)
        val memoDeleted = source.editProductivity(ProductivityEdit.DeleteMemo(memo.id))
        assertEquals(1, ScheduleMerge.merge(source, memoDeleted, different).conflicts.size)
        assertTrue(runCatching { base.editProductivity(ProductivityEdit.DeleteList(listId)) }.isFailure)
        val deleted = base.editProductivity(ProductivityEdit.DeleteList(listId, setOf(task.id)))
        val added = base.editProductivity(createTask)
        val linked = ScheduleMerge.merge(base, deleted, added)
        assertEquals(3, linked.conflicts.single().keys.size)
        val resolved = ScheduleMerge.merge(base, deleted, added, ScheduleMerge.Choice.REMOTE)
        assertEquals(2, SharedProductivity.project(resolved.state.sharedRecords!!).tasks.size)
    }
}
