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
            "Cached fixture", color = null, CalendarAccess.WRITABLE, privileges = CalendarPrivileges(bind = true))
        val uid = "$run@dormpanel"
        val settings = if (phase == "seed") {
            CloudSettings(run, store.encrypt("seed@example.invalid", "local-test-value"), listOf(calendar), setOf(calendar.id), 60).also {
                store.save(it)
                val key = CalendarCacheKey(calendar.id, window)
                val description = "Long fixture description for daily recurring events. ".repeat(75)
                val repeated = (0 until 400).map { index ->
                    val day = if (index == 0) date else date.plusDays(1)
                    CloudEvent("uid", "fixture-$index", "Persistent cloud event", "", description, 0, 0, day, day.plusDays(1))
                }
                val snapshot = CalendarSnapshot(System.currentTimeMillis(), null, mapOf("test" to CachedObject("\"test\"", repeated)))
                assertTrue(repeated.sumOf { event -> event.description.length } > 512 * 1024)
                store.saveSnapshot(run, key, snapshot)
                try {
                    store.saveSnapshot(run, key, snapshot.copy(objects = mapOf("test" to CachedObject("\"larger\"", repeated.mapIndexed { i, event ->
                        event.copy(description = "$i " + "界".repeat(3100))
                    }))))
                    fail("oversized snapshot unexpectedly saved")
                } catch (e: CalDavFailure) {
                    assertEquals(CalDavProblem.CAPACITY, e.problem)
                }
                assertEquals(snapshot, store.snapshot(run, key))
                // An interrupted atomic replacement leaves the old complete file in its backup slot.
                val directory = File(File(context.noBackupFilesDir, namespace), run)
                val base = directory.listFiles().orEmpty().single { file -> file.name.endsWith(".json") }
                base.copyTo(File(directory, base.name + ".bak"))
                base.writeText("{unfinished")
                assertEquals(snapshot, store.snapshot(run, key))
            }
        } else store.settings()
        try {
            assertEquals(run, settings.account)
            assertEquals(setOf(calendar.id), settings.selected)
            assertEquals(60, settings.refreshMinutes)
            assertEquals("seed@example.invalid" to "local-test-value", store.login(settings))
            assertEquals(400, store.snapshot(run, CalendarCacheKey(calendar.id, window))?.objects?.values?.single()?.events?.size)
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
                    model.cloudCalendar.saveCreate(UUID.fromString(run), run, calendar, CloudEventDraft(uid, "测试", "等待写回",
                        date.atStartOfDay(), date.plusDays(1).atStartOfDay(), true, zone)) { error -> assertNull(error) }
                }
                waitFor { it.cloudCalendar.writes.singleOrNull()?.let { write ->
                    write.state == CalendarWriteState.QUEUED && write.draft.title == uid && write.ref.href.encodedPath.endsWith(".ics")
                } == true }
                val pending = store.writes().single()
                val accountFolder = File(context.noBackupFilesDir, namespace)
                if (phase == "seed") {
                    File(accountFolder, "pending-writes.json").copyTo(File(accountFolder, "pending-writes.json.bak"))
                    File(accountFolder, "pending-writes.json").writeText("{unfinished")
                    File(accountFolder, "expected-resource").writeText(pending.ref.href.toString())
                } else {
                    assertEquals(File(accountFolder, "expected-resource").readText(), pending.ref.href.toString())
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
                File(context.noBackupFilesDir, namespace).resolve("pending-writes.json").delete()
                File(context.noBackupFilesDir, namespace).resolve("expected-resource").delete()
                File(context.noBackupFilesDir, namespace).resolve("account.json").delete()
                File(context.noBackupFilesDir, namespace).delete()
            }
        }
    }
}
