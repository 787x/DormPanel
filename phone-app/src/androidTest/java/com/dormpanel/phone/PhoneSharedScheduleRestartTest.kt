package com.dormpanel.phone

import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

/** Isolated test DB only. pr31Phase=seed|interrupt|verify; reuse the same pr31Run UUID.
 * Host force-stops the test process after the interruption marker, then runs verify. */
class PhoneSharedScheduleRestartTest {
    @Test fun committedCatalogSurvivesProcessDeathDuringNextApply() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("pr31Phase") ?: "single"
        val token = args.getString("pr31Run") ?: UUID.randomUUID().toString()
        require(UUID.fromString(token).toString() == token)
        val dbName = "pr31-restart-$token.db"; val namespace = "pr31-restart-$token"
        val first = SnapshotFixture.snapshot(revision = "22222222-2222-4222-8222-222222222222")
        val executors = mutableListOf<ExecutorService>()
        TermScheduleProfileStore.overrideNamespace = namespace
        SharedScheduleSettings.overrideNamespace = namespace; WebDavSettings.overrideNamespace = namespace
        fun store(interrupt: Boolean = false): RoomScheduleStore = RoomScheduleStore(
            Room.databaseBuilder(context, ScheduleDatabase::class.java, dbName).build(),
            Executors.newSingleThreadExecutor().also { executors += it }, profiles = TermScheduleProfileStore(context),
            afterSnapshotRows = if (!interrupt) null else ({
                android.util.Log.i("DormPanelSnapshotTest", "INTERRUPT_READY $token")
                CountDownLatch(1).await(60, TimeUnit.SECONDS)
                error("Host must force-stop after INTERRUPT_READY")
            }))
        fun apply(store: RoomScheduleStore, bytes: ByteArray) {
            val done = CompletableFuture<Result<Unit>>()
            instrumentation.runOnMainSync { store.applySnapshot(bytes) { done.complete(it) } }
            done.get(70, TimeUnit.SECONDS).getOrThrow()
        }
        try {
            if (phase == "seed" || phase == "single") {
                val seedStore = store()
                apply(seedStore, ScheduleSnapshotCodec.encode(first))
                instrumentation.runOnMainSync { seedStore.close() }; executors.last().awaitTermination(10, TimeUnit.SECONDS)
            }
            if (phase == "interrupt") {
                val interrupted = store(true)
                val next = first.copy(revision = UUID.randomUUID().toString(), parentRevision = first.revision, generation = 2,
                    state = first.state.copy(entries = first.state.entries.map { it.copy(title = "Interrupted revision") }, profiles = first.state.profiles.mapValues { (_, p) -> p.copy(timezone = "Asia/Tokyo") }))
                apply(interrupted, ScheduleSnapshotCodec.encode(next))
                error("Interruption phase must be force-stopped")
            }
            if (phase == "verify" || phase == "single") {
                ScheduleStores.overrideFactory = { store() }
                ActivityScenario.launch(TimetableActivity::class.java).use { scenario ->
                    val deadline = System.currentTimeMillis() + 10000
                    var ready = false
                    while (!ready && System.currentTimeMillis() < deadline) {
                        scenario.onActivity { ready = it.source.ready }; if (!ready) Thread.sleep(40)
                    }
                    assertTrue(ready)
                    scenario.onActivity {
                        val v = it.source.state
                        assertFalse(it.source.readOnly); assertEquals(first.revision, v.sync!!.revision)
                        assertEquals("local", v.entries.single().id); assertEquals("stable-source", v.sources.single().id)
                        assertEquals("stable-instance", v.imported.single().id); assertEquals(3, v.classOverrides.size)
                        assertEquals(first.state.profiles, v.profiles); assertEquals("Asia/Shanghai", v.profiles["custom-term"]!!.timezone)
                        assertTrue(it.source.saveEntry(v.entries.single().title, v.entries.single().day, v.entries.single().startMinute, v.entries.single().endMinute, v.entries.single().location, v.entries.single().id))
                    }
                    assertEquals("{}", context.getSharedPreferences(namespace, 0).getString("overrides", "{}"))
                }
            }
        } finally {
            ScheduleStores.overrideFactory = null; TermScheduleProfileStore.overrideNamespace = null
            SharedScheduleSettings.overrideNamespace = null; WebDavSettings.overrideNamespace = null
            executors.forEach { it.shutdown(); it.awaitTermination(10, TimeUnit.SECONDS) }
            if (phase == "single" || phase == "verify") { context.deleteDatabase(dbName); context.getSharedPreferences(namespace, 0).edit().clear().commit() }
        }
    }
}
