package com.dormpanel.app

import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.dashboard.persistence.*
import com.dormpanel.app.productivity.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Optional two-instrumentation-run protocol: phase=seed, force-stop, then phase=verify,
 * both with the same productivityRun UUID. The ordinary suite runs both phases locally.
 * Only UUID-named test files are used, including Dashboard layout persistence. */
class ProductivityRestartAndroidTest {
    @Test fun isolatedRestartRoundTrip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("productivityPhase")
        val token = args.getString("productivityRun") ?: UUID.randomUUID().toString()
        require(UUID.fromString(token).toString() == token)
        val dashboardName = "test-productivity-layout-$token.db"
        val contentName = "test-productivity-content-$token.db"
        com.dormpanel.app.schedule.WebDavSettings.overrideNamespace = "webdav_productivity_restart_test"
        val dashboard = Room.databaseBuilder(context, DashboardDatabase::class.java, dashboardName).build()
        val executors = mutableListOf<ExecutorService>()
        fun executor() = Executors.newSingleThreadExecutor().also { executors += it }
        DashboardStores.overrideFactory = { RoomDashboardStore(dashboard, executor()) }
        ProductivityStores.overrideFactory = {
            RoomProductivityStore(Room.databaseBuilder(context, ProductivityDatabase::class.java, contentName).build(), executor())
        }
        com.dormpanel.app.schedule.ScheduleStores.overrideFactory = {
            com.dormpanel.app.schedule.RoomScheduleStore(Room.inMemoryDatabaseBuilder(context,
                com.dormpanel.app.schedule.ScheduleDatabase::class.java).build(), executor())
        }
        fun run(seed: Boolean) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var ready = false; val deadline = System.currentTimeMillis() + 8000
                while (!ready && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { val vm = ViewModelProvider(it)[DashboardViewModel::class.java]; ready = vm.stateHolder.state.loaded && vm.productivity.ready }
                    if (!ready) Thread.sleep(30)
                }
                assertTrue(ready)
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[DashboardViewModel::class.java]
                    if (seed) {
                        vm.stateHolder.state.cards.toList().forEach { vm.stateHolder.delete(it.id) }
                        listOf("todo", "todo", "memo", "memo", "timer", "timer").forEach { type -> vm.stateHolder.add(vm.catalog.candidates.first { it.providerType == type }) }
                        val cards = vm.stateHolder.state.cards
                        assertEquals(6, cards.size)
                        vm.productivity.addTodo(cards[0].id, "First list")
                        vm.productivity.addTodo(cards[1].id, "Second list")
                        vm.productivity.saveMemo(cards[2].id, "First\nMemo")
                        vm.productivity.saveMemo(cards[3].id, "Second\nMemo")
                        vm.productivity.configureTimer(cards[4].id, 300000); vm.productivity.startTimer(cards[4].id)
                        vm.productivity.configureTimer(cards[5].id, 1000); vm.productivity.startTimer(cards[5].id)
                    } else {
                        val cards = vm.stateHolder.state.cards
                        assertEquals(6, cards.size)
                        assertEquals("First list", vm.productivity.state(cards[0].id).todos.single().text)
                        assertEquals("Second list", vm.productivity.state(cards[1].id).todos.single().text)
                        assertEquals("First\nMemo", vm.productivity.state(cards[2].id).memo)
                        assertEquals("Second\nMemo", vm.productivity.state(cards[3].id).memo)
                        assertEquals(TimerStatus.RUNNING, vm.productivity.state(cards[4].id).timer.status)
                        assertTrue(vm.productivity.state(cards[4].id).timer.remaining < 300000)
                        assertEquals(TimerStatus.FINISHED, vm.productivity.state(cards[5].id).timer.status)
                    }
                }
            }
            executors.forEach { assertTrue(it.awaitTermination(5, TimeUnit.SECONDS)) }
        }
        try {
            if (phase != "verify") run(true)
            if (phase == null) Thread.sleep(1200)
            if (phase != "seed") run(false)
        } finally {
            DashboardStores.overrideFactory = null; ProductivityStores.overrideFactory = null
            com.dormpanel.app.schedule.ScheduleStores.overrideFactory = null
            com.dormpanel.app.schedule.WebDavSettings.overrideNamespace = null
            executors.forEach { it.shutdown(); it.awaitTermination(5, TimeUnit.SECONDS) }
            dashboard.close()
            if (phase != "seed") { context.deleteDatabase(dashboardName); context.deleteDatabase(contentName) }
        }
    }
}
