package com.dormpanel.app

import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.dashboard.persistence.*
import com.dormpanel.app.productivity.*
import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

/** phase=seed / force-stop / phase=verify with the same scheduleRun UUID tests process death.
 * Every database owned by this test has a unique test name; production files are never opened. */
class ScheduleRestartAndroidTest {
    @Test fun isolatedRestartRoundTrip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("schedulePhase")
        val token = args.getString("scheduleRun") ?: UUID.randomUUID().toString()
        require(UUID.fromString(token).toString() == token)
        val dashboardName = "test-schedule-layout-$token.db"
        val scheduleName = "test-schedule-content-$token.db"
        val dashboard = Room.databaseBuilder(context, DashboardDatabase::class.java, dashboardName).build()
        val executors = mutableListOf<ExecutorService>()
        fun executor() = Executors.newSingleThreadExecutor().also { executors += it }
        DashboardStores.overrideFactory = { RoomDashboardStore(dashboard, executor()) }
        ProductivityStores.overrideFactory = { RoomProductivityStore(Room.inMemoryDatabaseBuilder(context, ProductivityDatabase::class.java).build(), executor()) }
        ScheduleStores.overrideFactory = { RoomScheduleStore(Room.databaseBuilder(context, ScheduleDatabase::class.java, scheduleName).build(), executor()) }
        fun run(seed: Boolean) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var ready = false; val deadline = System.currentTimeMillis() + 8000
                while (!ready && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { val vm = ViewModelProvider(it)[DashboardViewModel::class.java]; ready = vm.stateHolder.state.loaded && vm.schedule.ready }
                    if (!ready) Thread.sleep(30)
                }
                assertTrue(ready)
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[DashboardViewModel::class.java]
                    if (seed) {
                        vm.stateHolder.state.cards.toList().forEach { vm.stateHolder.delete(it.id) }
                        listOf("calendar", "timetable").forEach { kind -> vm.stateHolder.add(vm.catalog.candidates.first { it.providerType == kind }) }
                        assertTrue(vm.schedule.saveEvent("Persistent event", 1790000000000L, 1790003600000L, "Retained note"))
                        assertTrue(vm.schedule.saveEntry("Persistent class", 1, 540, 600, "Room 201"))
                    } else {
                        assertEquals(setOf("calendar", "timetable"), vm.stateHolder.state.cards.map { it.providerType }.toSet())
                        assertEquals("Persistent event", vm.schedule.state.events.single().title)
                        assertEquals("Retained note", vm.schedule.state.events.single().note)
                        assertEquals(1790000000000L, vm.schedule.state.events.single().start)
                        assertEquals("Persistent class", vm.schedule.state.entries.single().title)
                        assertEquals(540, vm.schedule.state.entries.single().startMinute)
                    }
                }
            }
            executors.forEach { assertTrue(it.awaitTermination(5, TimeUnit.SECONDS)) }
        }
        try { if (phase != "verify") run(true); if (phase != "seed") run(false) }
        finally {
            DashboardStores.overrideFactory = null; ProductivityStores.overrideFactory = null; ScheduleStores.overrideFactory = null
            executors.forEach { it.shutdown(); it.awaitTermination(5, TimeUnit.SECONDS) }; dashboard.close()
            if (phase != "seed") { context.deleteDatabase(dashboardName); context.deleteDatabase(scheduleName) }
        }
    }
}
