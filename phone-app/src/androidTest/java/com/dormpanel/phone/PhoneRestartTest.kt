package com.dormpanel.phone

import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

/** Run with phonePhase=seed, force-stop, then phonePhase=verify and the same phoneRun UUID. */
class PhoneRestartTest {
    @Test fun processRestartRestoresAllScheduleData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("phonePhase")
        val token = args.getString("phoneRun") ?: UUID.randomUUID().toString()
        require(UUID.fromString(token).toString() == token)
        val dbName = "phone-restart-$token.db"
        val preferences = "phone-restart-$token"
        val executors = mutableListOf<ExecutorService>()
        TermScheduleProfileStore.overrideNamespace = preferences
        ScheduleStores.overrideFactory = { c ->
            val executor = Executors.newSingleThreadExecutor().also { executors += it }
            RoomScheduleStore(Room.databaseBuilder(c, ScheduleDatabase::class.java, dbName).build(), executor)
        }
        fun launch(seed: Boolean) {
            ActivityScenario.launch(TimetableActivity::class.java).use { scenario ->
                var ready = false; val deadline = System.currentTimeMillis() + 10000
                while (!ready && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { ready = it.source.ready }; if (!ready) Thread.sleep(40)
                }
                assertTrue(ready)
                if (seed) {
                    val profile = BuiltInProfiles.term2026.copy(timezone = "Asia/Tokyo")
                    TermScheduleProfileStore(context).put(profile)
                    val preview = instrumentation.context.assets.open("hubei_2026-2027-1.csv").use {
                        TimetableImporter(profiles = TermScheduleProfileStore(context)).parse(ScheduleArtifact.read("term.csv", it))
                    }
                    val imported = CountDownLatch(1)
                    scenario.onActivity { a ->
                        assertTrue(a.source.saveEntry("Restart local", 1, 540, 600, "Room"))
                        assertTrue(a.source.saveDayAdjustment(java.time.LocalDate.parse("2026-10-12"), "none"))
                        a.source.import(preview, "Restart source", null) {
                            val commit = it.getOrThrow()
                            assertTrue(a.source.saveClassOverride(commit.source.id, commit.occurrences.first().seriesId, null, ClassPatch(note = "Retained")))
                            imported.countDown()
                        }
                    }
                    assertTrue(imported.await(10, TimeUnit.SECONDS))
                } else scenario.onActivity { a ->
                    assertEquals("Restart local", a.source.state.entries.single().title)
                    assertEquals(131, a.source.state.imported.size)
                    assertEquals("Retained", a.source.state.classOverrides.single().note)
                    assertEquals(a.source.state.sources.single().id, a.source.state.classOverrides.single().sourceId)
                    assertEquals("none", a.source.state.dayAdjustments.single().mode)
                    assertEquals("Asia/Tokyo", TermScheduleProfileStore(context).get(BuiltInProfiles.TERM_2026_2027_1)!!.timezone)
                }
            }
            executors.forEach { assertTrue(it.awaitTermination(10, TimeUnit.SECONDS)) }
        }
        try { if (phase != "verify") launch(true); if (phase != "seed") launch(false) }
        finally {
            ScheduleStores.overrideFactory = null; TermScheduleProfileStore.overrideNamespace = null
            executors.forEach { it.shutdown(); it.awaitTermination(10, TimeUnit.SECONDS) }
            if (phase != "seed") { context.deleteDatabase(dbName); context.getSharedPreferences(preferences, 0).edit().clear().commit() }
        }
    }
}
