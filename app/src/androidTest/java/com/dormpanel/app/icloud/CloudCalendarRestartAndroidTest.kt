package com.dormpanel.app.icloud

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.IsolatedDashboardRule
import com.dormpanel.app.MainActivity
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.dashboard.persistence.DashboardDatabase
import com.dormpanel.app.dashboard.persistence.DashboardStores
import com.dormpanel.app.dashboard.persistence.RoomDashboardStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CloudCalendarRestartAndroidTest {
    @get:Rule val persistence = IsolatedDashboardRule()

    /** Use the same cloudRestartRun UUID for seed / force-stop / verify on the isolated test package. */
    @Test fun persistsCalendarCardSelectionCredentialsAndWindowAcrossProcessDeath() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("cloudRestartPhase")
        assumeTrue(phase in setOf("seed", "verify"))
        val run = requireNotNull(args.getString("cloudRestartRun"))
        require(UUID.fromString(run).toString() == run)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "test-caldav-restart-$run"
        val dbName = "$namespace.db"
        val store = CloudCalendarStore(context, namespace)
        val dashboard = Room.databaseBuilder(context, DashboardDatabase::class.java, dbName).build()
        val executor = Executors.newSingleThreadExecutor()
        DashboardStores.overrideFactory = { RoomDashboardStore(dashboard, executor) }
        CloudCalendarService.overrideFactory = {
            CloudCalendarService(it, { _, _ -> error("Offline fixture must not request CalDAV") }, { false }, store)
        }
        val zone = ZoneId.systemDefault()
        val date = LocalDate.now(zone)
        val window = CalendarWindow(YearMonth.from(date), zone)
        val calendar = CloudCalendar("a".repeat(64), "https://caldav.icloud.com/fixture/".toHttpUrl(),
            "Cached fixture", color = null, CalendarAccess.READ_ONLY)
        val settings = if (phase == "seed") {
            CloudSettings(run, store.encrypt("seed@example.invalid", "local-test-value"), listOf(calendar), setOf(calendar.id), 60).also {
                store.save(it)
                store.saveSnapshot(run, CalendarCacheKey(calendar.id, window), CalendarSnapshot(System.currentTimeMillis(), null,
                    mapOf("test" to CachedObject("\"test\"", listOf(CloudEvent("uid", null, "Persistent cloud event", "", "",
                        0, 0, date, date.plusDays(1)))))))
            }
        } else store.settings()
        try {
            assertEquals(run, settings.account)
            assertEquals(setOf(calendar.id), settings.selected)
            assertEquals(60, settings.refreshMinutes)
            assertEquals("seed@example.invalid" to "local-test-value", store.login(settings))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun waitFor(check: (DashboardViewModel) -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 10_000
                    do {
                        var ready = false
                        scenario.onActivity { ready = check(ViewModelProvider(it)[DashboardViewModel::class.java]) }
                        if (ready) return
                        Thread.sleep(30)
                    } while (SystemClock.uptimeMillis() < deadline)
                    fail("Persistent calendar state did not become available")
                }
                waitFor { it.stateHolder.state.loaded && it.cloudCalendar.eventsOn(date, zone).size == 1 }
                if (phase == "seed") scenario.onActivity {
                    val model = ViewModelProvider(it)[DashboardViewModel::class.java]
                    model.stateHolder.state.cards.forEach { card -> model.stateHolder.delete(card.id) }
                    model.stateHolder.add(model.catalog.candidates.single { card -> card.providerType == "calendar" }, run)
                    model.stateHolder.updateConfiguration(run, CardCalendars(setOf(calendar.id)).encode())
                }
                waitFor {
                    val card = it.stateHolder.state.cards.singleOrNull()
                    card?.id == run && CardCalendars.decode(card.configurationJson).selected == setOf(calendar.id) &&
                        it.stateHolder.state.configurationSaves.isEmpty() && !it.stateHolder.state.storageError &&
                        it.cloudCalendar.eventsOn(date, zone).singleOrNull()?.event?.title == "Persistent cloud event"
                }
            }
        } finally {
            CloudCalendarService.overrideFactory = null
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            dashboard.close()
            if (phase == "verify") {
                context.deleteDatabase(dbName)
                store.disconnect()
                File(context.noBackupFilesDir, namespace).resolve("account.json").delete()
                File(context.noBackupFilesDir, namespace).delete()
            }
        }
    }
}
