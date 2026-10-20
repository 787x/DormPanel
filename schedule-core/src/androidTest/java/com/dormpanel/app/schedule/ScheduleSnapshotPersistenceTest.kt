package com.dormpanel.app.schedule

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.*

class ScheduleSnapshotPersistenceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun <T> call(action: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val result = CompletableFuture<Result<T>>()
        instrumentation.runOnMainSync { action { result.complete(it) } }
        return result.get(15, TimeUnit.SECONDS)
    }
    private fun database(name: String) = Room.databaseBuilder(context, ScheduleDatabase::class.java, name)
        .addMigrations(ScheduleDatabase.MIGRATION_1_2, ScheduleDatabase.MIGRATION_2_3, ScheduleDatabase.MIGRATION_3_4).build()
    @Test fun twoDatabasesExportRestoreIdempotencyProjectionAndDiskReopen() {
        val names = List(2) { "snapshot-${UUID.randomUUID()}.db" }
        val namespaces = List(2) { "snapshot-${UUID.randomUUID()}" }
        val executors = mutableListOf<ExecutorService>()
        fun store(i: Int): RoomScheduleStore {
            TermScheduleProfileStore.overrideNamespace = namespaces[i]
            return RoomScheduleStore(database(names[i]), Executors.newSingleThreadExecutor().also { executors += it }, profiles = TermScheduleProfileStore(context))
        }
        var publisher = store(0); var phone = store(1)
        try {
            val seed = SnapshotFixture.snapshot()
            val db = database(names[0])
            db.runInTransaction {
                val dao = db.dao(); val v = seed.state
                v.entries.forEach { dao.put(TimetableRow(it.id, it.title, it.day, it.startMinute, it.endMinute, it.location)) }
                v.events.forEach { dao.put(EventRow(it.id, it.title, it.start, it.end, it.note)) }
                v.sources.forEach(dao::insertSource); dao.insertOccurrences(v.imported)
                v.dayAdjustments.forEach(dao::put); v.classOverrides.forEach(dao::put)
            }; db.close()
            TermScheduleProfileStore.overrideNamespace = namespaces[0]
            val config = TermScheduleProfileStore(context)
            seed.state.profiles.values.forEach(config::put)
            val bytes = call<ByteArray> { publisher.exportSnapshot(seed.datasetId, null, 1, it) }.getOrThrow()
            val exported = ScheduleSnapshotCodec.decode(bytes)
            assertEquals(config.snapshotCatalog().second, exported.state.customProfileKeys)
            call<Unit> { phone.applySnapshot(bytes, it) }.getOrThrow()
            val first = call<ScheduleState>(phone::load).getOrThrow()
            assertEquals(exported.state.copy(sync = SnapshotIdentity(exported.datasetId, exported.revision)), first)
            assertEquals(ScheduleProjection.week(exported.state, LocalDate.parse("2026-10-12"), ZoneId.of("Asia/Shanghai")),
                ScheduleProjection.week(first, LocalDate.parse("2026-10-12"), ZoneId.of("Asia/Shanghai")))
            call<Unit> { phone.applySnapshot(bytes, it) }.getOrThrow()
            assertEquals(first, call<ScheduleState>(phone::load).getOrThrow())
            val reusedRevision = exported.copy(state = exported.state.copy(entries = exported.state.entries.map { it.copy(title = "Changed content with reused revision") }))
            assertTrue(call<Unit> { phone.applySnapshot(ScheduleSnapshotCodec.encode(reusedRevision), it) }.isFailure)
            assertEquals(first, call<ScheduleState>(phone::load).getOrThrow())
            assertTrue(call<Unit> { phone.put(first.entries.single(), it) }.isSuccess)
            val next = exported.copy(revision = UUID.randomUUID().toString(), parentRevision = exported.revision, generation = 2,
                state = exported.state.copy(entries = exported.state.entries.map { it.copy(title = "New revision") },
                    profiles = exported.state.profiles.mapValues { (_, p) -> p.copy(timezone = "Asia/Tokyo") }))
            call<Unit> { phone.applySnapshot(ScheduleSnapshotCodec.encode(next), it) }.getOrThrow()
            assertTrue(call<Unit> { phone.applySnapshot(bytes, it) }.isFailure)
            instrumentation.runOnMainSync { phone.close() }; assertTrue(executors.last().awaitTermination(10, TimeUnit.SECONDS))
            phone = store(1)
            val reopened = call<ScheduleState>(phone::load).getOrThrow()
            assertEquals("New revision", reopened.entries.single().title)
            assertEquals("Asia/Tokyo", reopened.profiles["custom-term"]!!.timezone)
            assertEquals(next.revision, reopened.sync!!.revision)
            assertEquals("stable-source", reopened.imported.single().sourceId)
        } finally {
            instrumentation.runOnMainSync { phone.close(); publisher.close() }
            executors.forEach { it.awaitTermination(10, TimeUnit.SECONDS) }; names.forEach(context::deleteDatabase)
            namespaces.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }; TermScheduleProfileStore.overrideNamespace = null
        }
    }
    @Test fun interruptedTransactionRollsBackCoursesCatalogAndRevisionBeforeReopen() {
        val name = "snapshot-crash-${UUID.randomUUID()}.db"
        val namespace = "snapshot-crash-${UUID.randomUUID()}"
        TermScheduleProfileStore.overrideNamespace = namespace
        val profiles = TermScheduleProfileStore(context)
        var fail = false
        var executor = Executors.newSingleThreadExecutor()
        var store = RoomScheduleStore(database(name), executor, profiles = profiles, afterSnapshotRows = { if (fail) error("Simulated process interruption before checkpoint") })
        try {
            val first = SnapshotFixture.snapshot(); val bytes = ScheduleSnapshotCodec.encode(first)
            call<Unit> { store.applySnapshot(bytes, it) }.getOrThrow()
            val before = call<ScheduleState>(store::load).getOrThrow()
            val next = SnapshotFixture.snapshot(parent = first.revision).let { it.copy(state = it.state.copy(entries = emptyList(), profiles = emptyMap(), customProfileKeys = emptySet(), sources = emptyList(), imported = emptyList(), classOverrides = emptyList())) }
            fail = true
            assertTrue(call<Unit> { store.applySnapshot(ScheduleSnapshotCodec.encode(next), it) }.isFailure)
            assertEquals(before, call<ScheduleState>(store::load).getOrThrow())
            instrumentation.runOnMainSync { store.close() }; assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            executor = Executors.newSingleThreadExecutor(); store = RoomScheduleStore(database(name), executor, profiles = profiles)
            assertEquals(before, call<ScheduleState>(store::load).getOrThrow())
            val damaged = bytes.copyOf(); damaged[60] = (damaged[60].toInt() xor 1).toByte()
            assertTrue(call<Unit> { store.applySnapshot(damaged, it) }.isFailure)
            assertEquals(before, call<ScheduleState>(store::load).getOrThrow())
        } finally {
            instrumentation.runOnMainSync { store.close() }; executor.awaitTermination(10, TimeUnit.SECONDS); context.deleteDatabase(name)
            context.getSharedPreferences(namespace, 0).edit().clear().commit(); TermScheduleProfileStore.overrideNamespace = null
        }
    }
    @Test fun initialBindRefusesLocalDataAndCustomPreferences() {
        val name = "snapshot-nonempty-${UUID.randomUUID()}.db"; val namespace = "snapshot-${UUID.randomUUID()}"
        TermScheduleProfileStore.overrideNamespace = namespace
        val profiles = TermScheduleProfileStore(context)
        val executor = Executors.newSingleThreadExecutor(); val store = RoomScheduleStore(database(name), executor, profiles = profiles)
        val bytes = ScheduleSnapshotCodec.encode(SnapshotFixture.snapshot())
        try {
            profiles.put(BuiltInProfiles.term2026.copy(timezone = "Asia/Tokyo"))
            assertTrue(call<Unit> { store.applySnapshot(bytes, it) }.isFailure)
            profiles.remove(BuiltInProfiles.term2026.termKey)
            call<Unit> { store.put(TimetableEntry("keep", "Local", 1, 1, 2), it) }.getOrThrow()
            assertTrue(call<Unit> { store.applySnapshot(bytes, it) }.isFailure)
            assertEquals("keep", call<ScheduleState>(store::load).getOrThrow().entries.single().id)
            assertNull(call<ScheduleState>(store::load).getOrThrow().sync)
        } finally {
            instrumentation.runOnMainSync { store.close() }; executor.awaitTermination(10, TimeUnit.SECONDS); context.deleteDatabase(name)
            context.getSharedPreferences(namespace, 0).edit().clear().commit(); TermScheduleProfileStore.overrideNamespace = null
        }
    }
    @Test fun v3MigrationKeepsExistingEditsAndAddsEmptyCheckpoint() {
        val name = "snapshot-v3-${UUID.randomUUID()}.db"
        val original = database(name)
        original.dao().put(TimetableRow("keep", "Old class", 1, 1, 2, "room")); original.dao().put(DayAdjustment("2026-10-12", "none")); original.close()
        try {
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE schedule_sync_checkpoint"); it.version = 3
            }
            val db = database(name)
            try {
                assertEquals("keep", db.dao().entries().single().id)
                assertEquals("none", db.dao().dayAdjustments().single().mode)
                assertNull(db.dao().checkpoint())
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
