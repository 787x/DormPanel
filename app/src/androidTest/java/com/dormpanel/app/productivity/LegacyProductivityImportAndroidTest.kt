package com.dormpanel.app.productivity

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.RoomScheduleStore
import com.dormpanel.app.schedule.ScheduleDatabase
import com.dormpanel.app.schedule.TermScheduleProfileStore
import com.dormpanel.app.schedule.WebDavAccount
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LegacyProductivityImportAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun <T> await(call: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val result = LinkedBlockingQueue<Result<T>>()
        call { result.put(it) }
        return requireNotNull(result.poll(20, TimeUnit.SECONDS))
    }

    @Test fun scanPreviewThenSelectAndConfirmWithoutChangingLegacyRows() {
        val id = UUID.randomUUID()
        val legacyName = "pr87-legacy-$id.db"
        val legacyFile = context.getDatabasePath(legacyName)
        val owners = listOf(
            ProductivityEntity("a", "第一行\r\n第二行\n", 600, 500, "RUNNING", 1234),
            ProductivityEntity("b", "另一个备忘\n", 800, 700, "PAUSED", 0),
            ProductivityEntity("oversized", "甲".repeat(11000), 0, 0, "READY", 0),
            ProductivityEntity("timer", "", 1200, 1100, "PAUSED", 0)
        )
        val todos = listOf(
            TodoEntity("a", "z", "Second", false, 9),
            TodoEntity("a", "m", "  First  ", true, 2),
            TodoEntity("a", "n", "Tie", false, 2),
            TodoEntity("b", "m", "Other list", false, 0)
        )
        fun openLegacy() = Room.databaseBuilder(context, ProductivityDatabase::class.java, legacyName).build()
        fun writeRows(owner: ProductivityEntity = owners.first(), tasks: List<TodoEntity> = todos.filter { it.owner == owner.owner }) {
            val writer = openLegacy()
            try {
                writer.runInTransaction {
                    writer.dao().putOwner(owner)
                    writer.dao().deleteTodos(owner.owner)
                    writer.dao().putTodos(tasks)
                }
            } finally { writer.close() }
        }
        owners.forEach { writeRows(it) }
        fun scan() = await<LegacyProductivityImport.Scan> { LegacyProductivityImport.scan(legacyFile, it) }.getOrThrow()
        try {
            val scanned = scan()
            val preview = LegacyProductivityImport.preview(scanned)
            assertEquals(preview.items, LegacyProductivityImport.preview(scan()).items)
            assertEquals(5, preview.items.size)
            assertEquals(1, preview.items.count { it.error != null })
            assertEquals("oversized", preview.items.single { it.error != null }.key.owner)
            assertEquals(listOf("  First  ", "Tie", "Second"), scanned.owners.first().todos.map { it.text })
            assertEquals(listOf(true, false, false), scanned.owners.first().todos.map { it.completed })
            assertEquals(owners.first().memo, scanned.owners.first().memo)
            assertEquals(scanned.owners, scan().owners)
            val url = InstrumentationRegistry.getArguments().getString("productivityImportUrl")
            assumeTrue("requires isolated endpoint for confirmation", url != null)
            require(Regex("^http://127\\.0\\.0\\.1:\\d+/pr87-import-[0-9a-f-]{36}/$").matches(url!!))
            val scheduleName = "pr87-import-$id.db"
            val store = RoomScheduleStore(Room.databaseBuilder(context, ScheduleDatabase::class.java, scheduleName).build(),
                profiles = TermScheduleProfileStore(context))
            try {
                store.prepareV3(url, sharedFormat = true)
                val account = WebDavAccount(url, "fixture", "fixture")
                store.synchronizeV3(account)
                fun load() = await(store::loadProductivity).getOrThrow()
                val version = requireNotNull(load().version)
                val failed = await<LegacyProductivityImport.Checked> {
                    LegacyProductivityImport.check(store, version, preview, preview.items.map { it.key }.toSet(), it)
                }
                assertTrue(failed.isFailure)
                assertTrue(load().content.lists.isEmpty())
                val selected = preview.items.filter { it.error == null && !(it.key.kind == LegacyProductivityImport.Kind.MEMO && it.key.owner == "b") }
                    .map { it.key }.toSet()
                fun checked() = await<LegacyProductivityImport.Checked> {
                    LegacyProductivityImport.check(store, requireNotNull(load().version), preview, selected, it)
                }.getOrThrow()
                fun rejectStale(change: () -> Unit) {
                    val candidate = checked()
                    change()
                    val afterSourceEdit = scan().owners
                    val outcome = await<Unit> { LegacyProductivityImport.confirm(store, candidate, it) }
                    assertTrue(outcome.exceptionOrNull()?.message.orEmpty().contains("重新扫描"))
                    assertEquals(afterSourceEdit, scan().owners)
                    assertEquals(version, load().version)
                    assertTrue(await<LegacyProductivityImport.Checked> {
                        LegacyProductivityImport.check(store, version, preview, selected, it)
                    }.isFailure)
                    writeRows()
                }
                val tasks = todos.filter { it.owner == "a" }
                rejectStale { writeRows(tasks = tasks + TodoEntity("a", "new", "新增", false, 10)) }
                rejectStale { writeRows(tasks = tasks.map { if (it.id == "m") it.copy(text = "正文改变") else it }) }
                rejectStale { writeRows(tasks = tasks.map { if (it.id == "m") it.copy(completed = false) else it }) }
                rejectStale { writeRows(tasks = tasks.filterNot { it.id == "m" }) }
                rejectStale { writeRows(tasks = tasks.map { if (it.id == "m") it.copy(position = 3) else it }) }
                rejectStale { writeRows(owner = owners.first().copy(memo = "改动\r\n")) }
                rejectStale {
                    val writer = openLegacy()
                    try { writer.openHelper.writableDatabase.execSQL("DELETE FROM owners WHERE owner = 'a'") }
                    finally { writer.close() }
                }
                val queuedCandidate = checked()
                val gate = CountDownLatch(1)
                val localWriter = RoomProductivityStore(openLegacy(), ProductivityStores.legacyQueue, ownsExecutor = false)
                try {
                    ProductivityStores.legacyQueue.execute { check(gate.await(10, TimeUnit.SECONDS)) }
                    localWriter.save("a", ProductivityState(todos = listOf(TodoItem("queued", "已排队")), memo = owners.first().memo)) {}
                    val observed = await<Unit> { callback ->
                        LegacyProductivityImport.confirm(store, queuedCandidate, callback)
                        gate.countDown()
                    }
                    assertTrue(observed.exceptionOrNull()?.message.orEmpty().contains("重新扫描"))
                    assertEquals("queued", scan().owners.first().todos.single().id)
                    assertEquals(version, load().version)
                } finally { gate.countDown(); localWriter.close() }
                writeRows()

                checked() // Cancel by discarding the checked preview.
                assertTrue(load().content.lists.isEmpty())
                val checked = checked()
                // b's Todo is selected; its independently edited Memo is outside the import.
                writeRows(owners[1].copy(memo = "未选中的变化"))
                await<Unit> { LegacyProductivityImport.confirm(store, checked, it) }.getOrThrow()
                await<Unit> { LegacyProductivityImport.validateSource(checked, it) }.getOrThrow()
                writeRows(owners[1])
                writeRows(owners.first().copy(memo = "确认后的本地变化"))
                assertTrue(await<Unit> { LegacyProductivityImport.validateSource(checked, it) }.isFailure)
                writeRows()
                assertTrue(await<Unit> { LegacyProductivityImport.confirm(store, checked, it) }.isFailure)
                await<Unit> { LegacyProductivityImport.confirm(store, checked(), it) }.getOrThrow()
                val view = load()
                assertEquals(2, view.content.lists.size)
                assertEquals(4, view.content.tasks.size)
                assertEquals(listOf(owners.first().memo), view.content.memos.map { it.body })
                val firstList = view.content.lists.single { it.name == "Todo a" }
                assertEquals(scanned.owners.first().todos.map { it.text }, view.content.tasks(firstList.id).map { it.text })
            } finally { store.close() }
            assertEquals(scanned.owners, scan().owners)
            val legacy = openLegacy()
            try {
                assertEquals(owners.toSet(), legacy.dao().owners().toSet())
                assertEquals(todos.toSet(), legacy.dao().todos().toSet())
            } finally { legacy.close() }
        } finally { context.deleteDatabase(legacyName) }
    }
}
