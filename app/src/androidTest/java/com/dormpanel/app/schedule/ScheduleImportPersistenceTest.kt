package com.dormpanel.app.schedule

import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

class ScheduleImportPersistenceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val importer = IcsScheduleImporter()
    private fun preview(count: Int = 3, title: String = "Imported") = importer.parse(ScheduleArtifact("semester.ics", """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//DormPanel tests//EN
        BEGIN:VEVENT
        UID:series
        SUMMARY:$title
        DTSTART;TZID=Asia/Shanghai:20260904T080000
        DTEND;TZID=Asia/Shanghai:20260904T095000
        RRULE:FREQ=WEEKLY;COUNT=$count
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().toByteArray()))
    private fun <T> await(operation: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val future = CompletableFuture<Result<T>>()
        instrumentation.runOnMainSync { operation { result ->
            if (Looper.myLooper() != Looper.getMainLooper()) future.completeExceptionally(AssertionError("Callback not on main"))
            else future.complete(result)
        } }
        return future.get(10, TimeUnit.SECONDS)
    }
    @Test fun preservingV1UpgradeValidatesRoomSchemaAndRetainsManualRows() {
        val name = "test-schedule-upgrade-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("CREATE TABLE events (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, note TEXT NOT NULL)")
                db.execSQL("CREATE TABLE timetable (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, day INTEGER NOT NULL, startMinute INTEGER NOT NULL, endMinute INTEGER NOT NULL, location TEXT NOT NULL)")
                db.execSQL("INSERT INTO events VALUES ('event', '原有日历', 1000, 2000, 'keep note')")
                db.execSQL("INSERT INTO timetable VALUES ('manual', '每周课程', 5, 480, 590, 'B310')")
                db.version = 1
            }
            val db = Room.databaseBuilder(context, ScheduleDatabase::class.java, name).addMigrations(ScheduleDatabase.MIGRATION_1_2).build()
            try {
                assertEquals(EventRow("event", "原有日历", 1000, 2000, "keep note"), db.dao().events().single())
                assertEquals(TimetableRow("manual", "每周课程", 5, 480, 590, "B310"), db.dao().entries().single())
                assertTrue(db.dao().sources().isEmpty()); assertTrue(db.dao().imported().isEmpty())
                val p = preview()
                db.runInTransaction { db.dao().insertSource(p.source("one", "New source", 1)); db.dao().insertOccurrences(p.forSource("one")) }
                assertEquals(3, db.dao().imported().size)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun importNoOpReplaceRollbackDeleteAndDiskRestartAreSourceScoped() {
        val name = "test-schedule-import-${UUID.randomUUID()}.db"
        fun database() = Room.databaseBuilder(context, ScheduleDatabase::class.java, name).addMigrations(ScheduleDatabase.MIGRATION_1_2).build()
        var executor = Executors.newSingleThreadExecutor()
        var store = RoomScheduleStore(database(), executor)
        fun commit(p: ImportPreview, target: String? = null) = await<ImportCommit> { importer.commit(p, store, "Semester", target, 1234, it) }.getOrThrow()
        fun state() = await<ScheduleState> { store.load(it) }.getOrThrow()
        try {
            await<Unit> { store.put(CalendarEvent("event", "Calendar", 1, 2), it) }.getOrThrow()
            await<Unit> { store.put(TimetableEntry("manual", "Weekly", 1, 540, 600), it) }.getOrThrow()
            val first = commit(preview())
            assertFalse(first.unchanged); assertEquals(1, state().sources.size); assertEquals(3, state().imported.size)
            assertTrue(commit(preview(), first.source.id).unchanged); assertEquals(3, state().imported.size)
            val other = commit(preview(1, "Other"))
            commit(preview(2), first.source.id)
            assertEquals(2, state().imported.count { it.sourceId == first.source.id })
            commit(preview(4, "Updated"), first.source.id)
            assertEquals(4, state().imported.count { it.sourceId == first.source.id })
            assertTrue(state().imported.filter { it.sourceId == first.source.id }.all { it.title == "Updated" })
            val before = state()
            assertTrue(runCatching { importer.parse(ScheduleArtifact("bad.ics", "broken".toByteArray())) }.isFailure)
            assertEquals(before, state())
            // Fail INSERT after old rows have already been deleted INSIDE the transaction.
            val changed = preview(2, "Must roll back")
            val rows = changed.forSource(first.source.id)
            val failure = await<ImportCommit> { store.commitImport(changed.source(first.source.id, "Bad", 2),
                listOf(rows.first(), rows.first()), true, it) }
            assertTrue(failure.isFailure); assertEquals(before, state())
            val oversized = rows.map { it.copy(description = "x".repeat(2_000_000)) }
            val textLimit = await<ImportCommit> { store.commitImport(changed.source(first.source.id, "Too large", 2), oversized, true, it) }
            assertTrue(textLimit.exceptionOrNull() is ScheduleImportException); assertEquals(before, state())
            instrumentation.runOnMainSync { store.close() }; assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            executor = Executors.newSingleThreadExecutor(); store = RoomScheduleStore(database(), executor)
            assertEquals(before, state())
            await<Unit> { store.deleteImport(first.source.id, it) }.getOrThrow()
            val remaining = state()
            assertEquals(other.source.id, remaining.sources.single().id)
            assertEquals(other.source.id, remaining.imported.single().sourceId)
            assertEquals("manual", remaining.entries.single().id); assertEquals("event", remaining.events.single().id)
        } finally {
            instrumentation.runOnMainSync { store.close() }; assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); context.deleteDatabase(name)
        }
    }
    @Test fun acceptedImportDrainsAfterCloseWithoutLateCallback() {
        val db = Room.inMemoryDatabaseBuilder(context, ScheduleDatabase::class.java).build()
        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        executor.execute { gate.await(5, TimeUnit.SECONDS) }
        val store = RoomScheduleStore(db, executor, closeDatabase = false)
        var callbacks = 0
        try {
            val p = preview()
            instrumentation.runOnMainSync {
                importer.commit(p, store, "Accepted", null, 1) { callbacks++ }
                store.close()
                importer.commit(p, store, "Rejected", null, 1) { callbacks++ }
            }
            gate.countDown(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
            assertEquals(0, callbacks); assertEquals("Accepted", db.dao().sources().single().displayName)
        } finally { gate.countDown(); store.close(); executor.awaitTermination(10, TimeUnit.SECONDS); db.close() }
    }
}
