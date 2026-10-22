package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Run explicit phases in separate instrumentation processes; host force-stops at each barrier. */
class ScheduleDatasetProcessRecoveryTest {
    @Test fun durableBoundary() {
        val phase = InstrumentationRegistry.getArguments().getString("migrationPhase") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "pr47-process-only"; TermScheduleProfileStore.overrideNamespace = name; SharedScheduleSettings.overrideNamespace = name
        val profiles = TermScheduleProfileStore(context); val settings = SharedScheduleSettings(context)
        val executor = Executors.newSingleThreadExecutor(); val db = Room.databaseBuilder(context, ScheduleDatabase::class.java, "$name.db").build()
        val store = RoomScheduleStore(db, executor, profiles = profiles)
        val targetId = "dc000000-0000-4000-8000-000000000047"; val commitId = "dc000000-0000-4000-8000-000000000048"
        fun remote(c: ScheduleCommit, key: String): CommitStore = object : CommitStore {
            override val bindingKey = digest(key.toByteArray())
            override fun list() = listOf(c.commitId)
            override fun read(commitId: String) = if (commitId == c.commitId) ScheduleCommitCodec.encode(c) else null
            override fun publish(bytes: ByteArray, allowCreate: Boolean) { error("unexpected PUT") }
        }
        try {
            when (phase) {
                "inspect" -> {
                    check(store.v2Frame().baseline == null) { "Isolated process fixture already exists" }
                    val s = SnapshotFixture.snapshot(); val old = ScheduleCommit(s.datasetId, s.revision, store.v2Frame().writerId, emptyList(), s.state)
                    store.v2Bind(ScheduleV2Binding.inspect(remote(old, "process-old")))
                    settings.folder = "https://process.test/old/"
                    store.v2BeginDatasetInspection(settings.folder, "https://process.test/new/", targetId)
                }
                "prepare" -> {
                    assertNotNull(store.v2DatasetInspection()); assertNull(store.v2Migration())
                    val old = requireNotNull(store.v2Frame().baseline); val target = old.copy(datasetId = targetId, commitId = commitId, parents = emptyList())
                    val (source, next) = ScheduleDatasetSwitch.inspect(store, remote(old, "process-old"), remote(target, "process-new"))
                    store.v2PrepareMigration(ScheduleDatasetMigration("dc000000-0000-4000-8000-000000000049", "prepared", settings.folder, "https://process.test/new/", source, next, store.v2Frame().writerId))
                }
                "commit" -> {
                    val j = requireNotNull(store.v2Migration()); assertEquals("prepared", j.phase)
                    assertEquals(j.source.head.datasetId, store.v2Frame().baseline!!.datasetId)
                    store.v2CommitMigration(j.id)
                }
                "preferences" -> {
                    val j = requireNotNull(store.v2Migration()); assertEquals("committed", j.phase)
                    assertEquals(j.oldFolder, settings.folder); settings.folder = j.newFolder
                }
                "verify" -> {
                    val j = requireNotNull(store.v2Migration()); val frame = store.v2Frame()
                    assertEquals("committed", j.phase); assertEquals(j.newFolder, settings.folder)
                    assertEquals(j.writerId, frame.writerId); assertEquals(targetId, frame.baseline!!.datasetId)
                    assertTrue(ScheduleMerge.same(j.source.snapshot.state, frame.local)); assertEquals(j.target.witness, store.v2Witness())
                    val target = ScheduleCommitCodec.decode(requireNotNull(j.target.commitBytes)); val backend = remote(target, "process-new")
                    assertThrows(Exception::class.java) { AppendOnlyScheduleSync(store, backend).synchronize() }
                    store.v2FinishMigration(j.id); assertNull(store.v2Migration())
                    assertTrue(AppendOnlyScheduleSync(store, backend).synchronize().conflicts.isEmpty())
                }
                else -> error("Unknown isolated process phase")
            }
            if (phase != "verify") {
                assertThrows(Exception::class.java) { store.v2RequireSyncAllowed() }
                context.openFileOutput("pr47-process-boundary", 0).use { it.write(phase.toByteArray()); it.fd.sync() }
                Thread.sleep(120_000) // Host must force-stop; this is a durable process-death barrier.
                error("Host did not terminate process at barrier")
            }
        } finally {
            store.close(); executor.awaitTermination(10, TimeUnit.SECONDS)
            if (phase == "verify") { context.deleteDatabase("$name.db"); context.getSharedPreferences(name, 0).edit().clear().commit(); context.deleteFile("pr47-process-boundary") }
            TermScheduleProfileStore.overrideNamespace = null; SharedScheduleSettings.overrideNamespace = null
        }
    }
}
