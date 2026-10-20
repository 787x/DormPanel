package com.dormpanel.phone

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

/** pr32Phase=seed|verify, same pr32Run; the host force-stops the app between invocations. */
class PhoneBidirectionalRestartTest {
    @Test fun offlineEditsCatalogAndUncertainReceiptSurviveForceStop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("pr32Phase") ?: "single"
        val run = args.getString("pr32Run") ?: UUID.randomUUID().toString()
        require(UUID.fromString(run).toString() == run)
        val name = "pr32-restart-$run"
        TermScheduleProfileStore.overrideNamespace = name
        val profiles = TermScheduleProfileStore(context)
        val executor = Executors.newSingleThreadExecutor()
        val store = RoomScheduleStore(Room.databaseBuilder(context, ScheduleDatabase::class.java, "$name.db").build(), executor, profiles = profiles)
        fun <T> call(action: ((Result<T>) -> Unit) -> Unit): T {
            val future = CompletableFuture<Result<T>>(); action { future.complete(it) }
            return future.get(15, TimeUnit.SECONDS).getOrThrow()
        }
        try {
            if (phase == "seed" || phase == "single") {
                val initial = SnapshotFixture.snapshot(revision = "22222222-2222-4222-8222-222222222222")
                call<Unit> { store.applySnapshot(ScheduleSnapshotCodec.encode(initial), it) }
                call<Unit> { store.put(initial.state.entries.single().copy(location = "A301"), it) }
                val captured = store.syncFrame().local
                val pending = initial.copy(revision = "33333333-3333-4333-8333-333333333333", parentRevision = initial.revision, generation = 2, state = captured)
                store.stagePublication(ScheduleSnapshotCodec.encode(pending), captured)
                call<Unit> { store.put(initial.state.entries.single().copy(location = "B201"), it) }
                call<Unit> { store.deleteEvent("event", it) }
                profiles.put(BuiltInProfiles.term2026.copy(timezone = "Asia/Tokyo"))
                assertTrue(store.syncFrame().dirty)
            }
            if (phase == "verify" || phase == "single") {
                val frame = store.syncFrame()
                assertTrue(frame.dirty); assertEquals("22222222-2222-4222-8222-222222222222", frame.baseline!!.revision)
                assertEquals("A301", frame.captured!!.entries.single().location)
                assertEquals("B201", frame.local.entries.single().location)
                assertTrue(frame.local.events.isEmpty()); assertEquals("Asia/Tokyo", frame.local.profiles.getValue(BuiltInProfiles.term2026.termKey).timezone)
                assertTrue(store.integrateRemote(ScheduleSnapshotCodec.encode(frame.pending!!)).isEmpty())
                val after = store.syncFrame()
                assertNull(after.pending); assertTrue(after.dirty)
                assertEquals("B201", after.local.entries.single().location); assertTrue(after.local.events.isEmpty())
            }
        } finally {
            store.close(); executor.awaitTermination(10, TimeUnit.SECONDS); TermScheduleProfileStore.overrideNamespace = null
            if (phase != "seed") { context.deleteDatabase("$name.db"); context.getSharedPreferences(name, 0).edit().clear().commit() }
        }
    }
}
