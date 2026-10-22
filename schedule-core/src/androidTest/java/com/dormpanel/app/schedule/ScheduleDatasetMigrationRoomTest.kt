package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScheduleDatasetMigrationRoomTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun remote(c: ScheduleCommit, label: String): CommitStore = object : CommitStore {
        override val bindingKey = digest(label.toByteArray())
        override fun list() = listOf(c.commitId)
        override fun read(commitId: String) = if (commitId == c.commitId) ScheduleCommitCodec.encode(c) else null
        override fun publish(bytes: ByteArray, allowCreate: Boolean) { error("no writes") }
    }
    @Test fun checkpointsAndCourseRowsSurviveEveryDurableBoundaryAndCommitFailure() {
        for (failAt in listOf(0, 21, 96, 102, 200)) {
            val name = "pr47-${UUID.randomUUID()}"; TermScheduleProfileStore.overrideNamespace = name
            val profiles = TermScheduleProfileStore(context)
            var executor = Executors.newSingleThreadExecutor()
            fun open() = Room.databaseBuilder(context, ScheduleDatabase::class.java, "$name.db").build()
            var db = open(); var store = RoomScheduleStore(db, executor, profiles = profiles)
            fun reopen() { store.close(); check(executor.awaitTermination(10, TimeUnit.SECONDS)); executor = Executors.newSingleThreadExecutor(); db = open(); store = RoomScheduleStore(db, executor, profiles = profiles) }
            try {
                val s = SnapshotFixture.snapshot(); val old = ScheduleCommit(s.datasetId, s.revision, UUID.randomUUID().toString(), emptyList(), s.state)
                val target = old.copy(datasetId = UUID.randomUUID().toString(), commitId = UUID.randomUUID().toString())
                val oldBackend = remote(old, "old"); val nextBackend = remote(target, "new")
                store.v2Bind(ScheduleV2Binding.inspect(oldBackend)); val writer = store.v2Frame().writerId
                store.v2BeginDatasetInspection("https://test/old/", "https://test/new/", target.datasetId); reopen()
                assertNotNull(store.v2DatasetInspection()); assertNull(store.v2Migration())
                assertThrows(Exception::class.java) { AppendOnlyScheduleSync(store, oldBackend).synchronize() }
                val (source, next) = ScheduleDatasetSwitch.inspect(store, oldBackend, nextBackend)
                val j = ScheduleDatasetMigration(UUID.randomUUID().toString(), "prepared", "https://test/old/", "https://test/new/", source, next, writer)
                store.v2PrepareMigration(j); reopen()
                assertEquals("prepared", store.v2Migration()!!.phase); assertEquals(writer, store.v2Frame().writerId)
                assertThrows(Exception::class.java) { AppendOnlyScheduleSync(store, nextBackend).synchronize() }
                if (failAt > 0) {
                    db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_migration BEFORE INSERT ON schedule_sync_checkpoint WHEN NEW.id = $failAt BEGIN SELECT RAISE(ABORT, 'injected migration failure'); END")
                    assertThrows(Exception::class.java) { store.v2CommitMigration(j.id) }; reopen()
                    assertEquals(old.datasetId, store.v2Frame().baseline!!.datasetId); assertEquals("prepared", store.v2Migration()!!.phase)
                    db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_migration")
                }
                store.v2CommitMigration(j.id); reopen()
                assertEquals("committed", store.v2Migration()!!.phase); assertEquals(target.datasetId, store.v2Frame().baseline!!.datasetId)
                assertEquals(writer, store.v2Frame().writerId); assertTrue(ScheduleMerge.same(old.state, store.v2Frame().local))
                assertEquals(mapOf(target.commitId to digest(ScheduleCommitCodec.encode(target))), store.v2Witness())
                assertThrows(Exception::class.java) { AppendOnlyScheduleSync(store, nextBackend).synchronize() }
                // Simulate process loss before/after preference commit. Journal always blocks network.
                SharedScheduleSettings.overrideNamespace = name
                val settings = SharedScheduleSettings(context); settings.folder = j.newFolder; reopen()
                assertEquals(j.newFolder, settings.folder); assertNotNull(store.v2Migration())
                store.v2FinishMigration(j.id); reopen(); assertNull(store.v2Migration())
                assertTrue(store.v2DatasetResult()!!.contains(writer)); assertTrue(store.v2DatasetResult()!!.contains(target.datasetId))
                assertTrue(AppendOnlyScheduleSync(store, nextBackend).synchronize().conflicts.isEmpty())
                val archives = db.dao().syncRecords().filter { it.id >= 1000 }; assertTrue(archives.size >= 2)
                val envelope = archives.mapNotNull { runCatching { org.json.JSONObject(String(it.payload, Charsets.UTF_8)) }.getOrNull() }.first { it.has("body") }
                val body = java.util.Base64.getDecoder().decode(envelope.getString("body")); assertEquals(envelope.getString("sha256"), digest(body))
                val recovery = org.json.JSONObject(String(body, Charsets.UTF_8)); val records = recovery.getJSONObject("records")
                val saved = records.getJSONObject("21"); val original = db.dao().syncRecord(saved.getInt("recordId"))!!.payload
                assertEquals(saved.getString("sha256"), digest(original)); assertArrayEquals(ScheduleCommitCodec.encode(old), original)
            } finally {
                store.close(); executor.awaitTermination(10, TimeUnit.SECONDS); context.deleteDatabase("$name.db")
                context.getSharedPreferences(name, 0).edit().clear().commit(); TermScheduleProfileStore.overrideNamespace = null; SharedScheduleSettings.overrideNamespace = null
            }
        }
    }
    @Test fun interruptedInspectionCancelRetainsObservedPathsForNormalSync() {
        val name = "pr47-inspect-${UUID.randomUUID()}"; TermScheduleProfileStore.overrideNamespace = name
        val profiles = TermScheduleProfileStore(context); val executor = Executors.newSingleThreadExecutor()
        val db = Room.databaseBuilder(context, ScheduleDatabase::class.java, "$name.db").build()
        val store = RoomScheduleStore(db, executor, profiles = profiles)
        try {
            val s = SnapshotFixture.snapshot(); val root = ScheduleCommit(s.datasetId, s.revision, UUID.randomUUID().toString(), emptyList(), s.state)
            val backend = remote(root, "inspection-old"); store.v2Bind(ScheduleV2Binding.inspect(backend))
            store.v2BeginDatasetInspection("https://test/old/", "https://test/new/", UUID.randomUUID().toString())
            val unseen = UUID.randomUUID().toString(); store.v2MigrationPaths(requireNotNull(backend.bindingKey), listOf(unseen))
            store.v2CancelDatasetInspection()
            assertNull(store.v2DatasetInspection()); assertTrue(unseen in store.v2Frame().unverifiable)
            val outcome = AppendOnlyScheduleSync(store, backend).synchronize()
            assertTrue(outcome.message.contains("暂不可验证")); assertTrue(unseen in store.v2Frame().unverifiable)
        } finally { store.close(); executor.awaitTermination(10, TimeUnit.SECONDS); context.deleteDatabase("$name.db"); context.getSharedPreferences(name, 0).edit().clear().commit(); TermScheduleProfileStore.overrideNamespace = null }
    }
}
