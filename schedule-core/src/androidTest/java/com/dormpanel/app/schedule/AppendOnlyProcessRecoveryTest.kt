package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

/** Run seed, force-stop the isolated test package, then verify with the same pr35Run UUID. */
class AppendOnlyProcessRecoveryTest {
    @Test fun pendingCaptureSurvivesForceStop() {
        val args = InstrumentationRegistry.getArguments()
        val run = args.getString("pr35Run")
        org.junit.Assume.assumeTrue("跨进程验证需要显式传入 pr35Run/pr35Phase", run != null)
        require(UUID.fromString(run).toString() == run)
        val phase = requireNotNull(args.getString("pr35Phase"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "pr35-process-$run"
        TermScheduleProfileStore.overrideNamespace = name
        val executor = Executors.newSingleThreadExecutor()
        val store = RoomScheduleStore(Room.databaseBuilder(context, ScheduleDatabase::class.java, "$name.db").build(), executor, profiles = TermScheduleProfileStore(context))
        fun put(entry: TimetableEntry) {
            val f = CompletableFuture<Result<Unit>>(); store.put(entry) { f.complete(it) }; f.get(15, TimeUnit.SECONDS).getOrThrow()
        }
        try {
            if (phase == "seed") {
                val snapshot = SnapshotFixture.snapshot()
                val root = ScheduleCommit(snapshot.datasetId, snapshot.revision, UUID.randomUUID().toString(), emptyList(), snapshot.state)
                store.v2Start(root, true)
                put(snapshot.state.entries.single().copy(location = "capture"))
                val f = store.v2Frame()
                val c = ScheduleCommit(root.datasetId, UUID.randomUUID().toString(), f.writerId, f.frontier, f.local)
                store.v2Stage(ScheduleCommitCodec.encode(c)); store.v2Attempt()
                put(snapshot.state.entries.single().copy(location = "after capture"))
                assertNotNull(store.v2Frame().pending)
            } else {
                require(phase == "verify")
                val f = store.v2Frame()
                assertEquals("after capture", f.local.entries.single().location)
                assertEquals("capture", ScheduleCommitCodec.decode(f.pending!!).state.entries.single().location)
                assertTrue(f.attempted)
                // This phase verifies only durable replay after a separately confirmed readback.
                store.v2Receipt(f.pending)
                assertEquals("after capture", store.v2Frame().local.entries.single().location)
                assertFalse(ScheduleMerge.same(store.v2Frame().baseline!!.state, store.v2Frame().local))
            }
        } finally {
            store.close(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            if (phase == "verify") { context.deleteDatabase("$name.db"); context.getSharedPreferences(name, 0).edit().clear().commit() }
            TermScheduleProfileStore.overrideNamespace = null
        }
    }
}
