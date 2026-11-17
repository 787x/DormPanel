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
        val seed = openLegacy()
        try {
            seed.runInTransaction {
                owners.forEach(seed.dao()::putOwner)
                seed.dao().putTodos(todos)
            }
        } finally { seed.close() }
        try {
            val scanned = LegacyProductivityImport.scan(legacyFile)
            val preview = LegacyProductivityImport.preview(scanned)
            assertEquals(preview, LegacyProductivityImport.preview(LegacyProductivityImport.scan(legacyFile)))
            assertEquals(5, preview.size)
            assertEquals(1, preview.count { it.error != null })
            assertEquals("oversized", preview.single { it.error != null }.key.owner)
            assertEquals(listOf("  First  ", "Tie", "Second"), scanned.first().todos.map { it.text })
            assertEquals(listOf(true, false, false), scanned.first().todos.map { it.completed })
            assertEquals(owners.first().memo, scanned.first().memo)
            assertEquals(scanned, LegacyProductivityImport.scan(legacyFile))
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
                    LegacyProductivityImport.check(store, version, preview, preview.map { it.key }.toSet(), it)
                }
                assertTrue(failed.isFailure)
                assertTrue(load().content.lists.isEmpty())
                val selected = preview.filter { it.error == null && !(it.key.kind == LegacyProductivityImport.Kind.MEMO && it.key.owner == "b") }
                    .map { it.key }.toSet()
                fun checked() = await<LegacyProductivityImport.Checked> {
                    LegacyProductivityImport.check(store, requireNotNull(load().version), preview, selected, it)
                }.getOrThrow()
                checked() // Cancel by discarding the checked preview.
                assertTrue(load().content.lists.isEmpty())
                val checked = checked()
                await<Unit> { LegacyProductivityImport.confirm(store, checked, it) }.getOrThrow()
                assertTrue(await<Unit> { LegacyProductivityImport.confirm(store, checked, it) }.isFailure)
                await<Unit> { LegacyProductivityImport.confirm(store, checked(), it) }.getOrThrow()
                val view = load()
                assertEquals(2, view.content.lists.size)
                assertEquals(4, view.content.tasks.size)
                assertEquals(listOf(owners.first().memo), view.content.memos.map { it.body })
                val firstList = view.content.lists.single { it.name == "Todo a" }
                assertEquals(scanned.first().todos.map { it.text }, view.content.tasks(firstList.id).map { it.text })
            } finally { store.close() }
            assertEquals(scanned, LegacyProductivityImport.scan(legacyFile))
            val legacy = openLegacy()
            try {
                assertEquals(owners.toSet(), legacy.dao().owners().toSet())
                assertEquals(todos.toSet(), legacy.dao().todos().toSet())
            } finally { legacy.close() }
        } finally { context.deleteDatabase(legacyName) }
    }
}
