package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

class ScheduleV2BindingRoomTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun root() = SnapshotFixture.snapshot().let { ScheduleCommit(it.datasetId, it.revision, UUID.randomUUID().toString(), emptyList(), it.state) }
    private fun preview(root: ScheduleCommit): SharedScheduleRemote {
        val store = object : CommitStore {
            override val bindingKey = "a".repeat(64)
            override fun list() = listOf(root.commitId)
            override fun read(commitId: String) = ScheduleCommitCodec.encode(root)
            override fun publish(bytes: ByteArray, allowCreate: Boolean) { error("read only") }
        }
        return ScheduleV2Binding.inspect(store)
    }
    private fun <T> call(block: ((Result<T>) -> Unit) -> Unit): T {
        val result = CompletableFuture<Result<T>>(); block { result.complete(it) }; return result.get(20, TimeUnit.SECONDS).getOrThrow()
    }
    private inner class Device : AutoCloseable {
        val name = "pr41-${UUID.randomUUID()}"
        var executor = Executors.newSingleThreadExecutor()
        val profiles: TermScheduleProfileStore
        var db: ScheduleDatabase
        var store: RoomScheduleStore
        var fail = false
        init {
            TermScheduleProfileStore.overrideNamespace = name; profiles = TermScheduleProfileStore(context)
            db = open(); store = store()
        }
        private fun open() = Room.databaseBuilder(context, ScheduleDatabase::class.java, "$name.db").build()
        private fun store() = RoomScheduleStore(db, executor, profiles = profiles, afterSnapshotRows = { if (fail) error("injected bind failure") })
        fun reopen() { store.close(); check(executor.awaitTermination(10, TimeUnit.SECONDS)); executor = Executors.newSingleThreadExecutor(); db = open(); store = store() }
        fun records(): Map<Int, String> = db.openHelper.readableDatabase.query("SELECT id, payload FROM schedule_sync_checkpoint ORDER BY id").use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getInt(0), digest(cursor.getBlob(1))) }
        }
        override fun close() {
            store.close(); check(executor.awaitTermination(10, TimeUnit.SECONDS)); context.deleteDatabase("$name.db")
            context.getSharedPreferences(name, 0).edit().clear().commit(); TermScheduleProfileStore.overrideNamespace = null
        }
    }
    @Test fun atomicBindingRetainsIndependentWriterHistoryAndStateAcrossReopen() {
        val root = root(); val remote = preview(root)
        Device().use { d ->
            val writer = d.store.v2Frame().writerId
            d.store.v2Bind(remote)
            val records = d.records(); d.reopen()
            val frame = d.store.v2Frame()
            assertTrue(ScheduleMerge.same(root.state, frame.local))
            assertArrayEquals(ScheduleCommitCodec.encode(root), ScheduleCommitCodec.encode(frame.baseline!!)); assertEquals(listOf(root.commitId), frame.frontier)
            assertEquals(writer, frame.writerId); assertNotEquals(root.writerId, writer); assertNull(frame.pending)
            assertEquals(records, d.records()); assertEquals(remote.bindingKey, d.db.dao().syncRecord(102)!!.payload.toString(Charsets.US_ASCII))
        }
    }
    @Test fun failedFirstBindingRollsBackEveryRowAndCheckpointThenRetriesAfterReopen() {
        Device().use { d ->
            val before = d.store.v2Frame(); val records = d.records(); d.fail = true
            assertThrows(IllegalStateException::class.java) { d.store.v2Bind(preview(root())) }
            assertTrue(ScheduleMerge.same(before.local, d.store.v2Frame().local)); assertEquals(records, d.records())
            d.reopen(); assertNull(d.store.v2Frame().baseline); assertEquals(before.writerId, d.store.v2Frame().writerId)
            d.fail = false; d.store.v2Bind(preview(root())); assertNotNull(d.store.v2Frame().baseline)
        }
    }
    @Test fun failureSavingFinalBindingMetadataRollsBackCoursesBaselineFrontierAndHistory() {
        Device().use { d ->
            val before = d.store.v2Frame(); val records = d.records()
            d.db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_binding_target BEFORE INSERT ON schedule_sync_checkpoint
                WHEN NEW.id = 102 BEGIN SELECT RAISE(ABORT, 'injected final checkpoint failure'); END""")
            assertThrows(Exception::class.java) { d.store.v2Bind(preview(root())) }
            assertTrue(ScheduleMerge.same(before.local, d.store.v2Frame().local)); assertEquals(records, d.records())
            d.reopen(); assertNull(d.store.v2Frame().baseline); assertEquals(records, d.records())
            d.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_binding_target")
            d.store.v2Bind(preview(root())); assertNotNull(d.store.v2Frame().baseline)
        }
    }
    @Test fun eachProtectedRecoveryRecordRejectsBindingWithoutChangingAnyCheckpoint() {
        val root = root()
        (listOf(1,3,4,5,6,7,21,22,23,24,25,26,27,29,96,97,100,101,111,114,115) + listOf(30,93)).forEach { id ->
            Device().use { d ->
                d.db.dao().checkpoint(ScheduleSyncCheckpoint(id, byteArrayOf(1,2,3)))
                val records = d.records()
                assertThrows(Exception::class.java) { d.store.v2Bind(preview(root)) }
                assertEquals("checkpoint $id", records, d.records()); assertTrue(d.db.dao().entries().isEmpty())
            }
        }
    }
    @Test fun localCourseEventImportCustomTermOrAdjustmentRefusesWithoutChangingState() {
        val root = root()
        listOf<(Device) -> Unit>(
            { d -> call<Unit> { d.store.put(root.state.entries.single(), it) } },
            { d -> call<Unit> { d.store.put(root.state.events.single(), it) } },
            { d -> val source = root.state.sources.first(); call<ImportCommit> { d.store.commitImport(source, root.state.imported.filter { it.sourceId == source.id }, false, it) } },
            { d -> d.profiles.put(root.state.profiles.getValue("custom-term")) },
            { d -> call<Unit> { d.store.putDayAdjustment(root.state.dayAdjustments.first(), root.state.dayAdjustments.first().date, it) } }
        ).forEach { seed -> Device().use { d ->
            seed(d); val before = d.store.v2Frame(); val records = d.records()
            assertThrows(IllegalStateException::class.java) { d.store.v2Bind(preview(root)) }
            assertTrue(ScheduleMerge.same(before.local, d.store.v2Frame().local)); assertEquals(records, d.records())
        } }
    }
}
