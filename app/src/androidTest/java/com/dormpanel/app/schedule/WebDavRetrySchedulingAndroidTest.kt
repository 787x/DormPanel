package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Isolated local imports, real Handler scheduling and MockWebDAV. Never opens daily data. */
class WebDavRetrySchedulingAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(call: () -> T): T {
        val value = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { value.set(runCatching(call)) }
        return value.get().getOrThrow()
    }
    private fun until(timeout: Long = 8000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(30) }
        error("File update did not reach expected state")
    }
    private inner class Fixture(initialFailure: Boolean, val period: Long = 2000, overdue: Boolean = true) : AutoCloseable {
        val context = instrumentation.targetContext
        val name = "pr60-file-retry-${UUID.randomUUID()}"
        val executor = Executors.newSingleThreadExecutor()
        val db = Room.databaseBuilder(context, ScheduleDatabase::class.java, "$name.db").build()
        val server = MockWebServer()
        val responseGate = AtomicReference<CountDownLatch?>()
        val raw = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//DormPanel isolated test//EN\r\nBEGIN:VEVENT\r\nUID:stable-course\r\nDTSTAMP:20260101T000000Z\r\nDTSTART:20261026T010000Z\r\nDTEND:20261026T020000Z\r\nSUMMARY:原课程\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val settings: WebDavSettings
        val store: RoomScheduleStore
        val id: String
        val original: WebDavBinding
        lateinit var source: ScheduleSource
        lateinit var controller: WebDavSyncController
        init {
            check(context.packageName.endsWith(".testbed"))
            WebDavSettings.overrideNamespace = name; TermScheduleProfileStore.overrideNamespace = name; SharedScheduleSettings.overrideNamespace = name
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    responseGate.get()?.let { check(it.await(10, TimeUnit.SECONDS)) }
                    return MockResponse().setBody(raw.replace("SUMMARY:原课程", "SUMMARY:远端更新"))
                        .setHeader("Content-Type", "text/calendar").setHeader("ETag", "\"updated\"")
                }
            }
            server.start()
            val profiles = TermScheduleProfileStore(context)
            store = RoomScheduleStore(db, executor, profiles = profiles)
            val remote = server.url("/course.ics").toString()
            val preview = IcsScheduleImporter().parse(ScheduleArtifact("course.ics", raw.toByteArray(), kind = "webdav", locator = remote))
            val seeded = AtomicReference<Result<ImportCommit>>()
            val done = CountDownLatch(1)
            IcsScheduleImporter().commit(preview, store, "保留的文件课表", null, System.currentTimeMillis()) { seeded.set(it); done.countDown() }
            check(done.await(10, TimeUnit.SECONDS)); id = seeded.get().getOrThrow().source.id
            settings = WebDavSettings(context).also { it.saveAccount(WebDavAccount(server.url("/").toString(), "fixture", "fixture-secret")) }
            original = WebDavBinding(id, WebDavMode.FILE, remote, autoSync = true, etag = "\"seed\"", currentFile = remote,
                lastAttempt = if (overdue) 0 else System.currentTimeMillis())
            settings.saveBindings(listOf(original))
            if (initialFailure) corrupt()
            main {
                source = ScheduleSource(store)
                controller = WebDavSyncController(context, source, settings, profiles = profiles, automaticIntervalMillis = period)
            }
            until { main { if (initialFailure) source.error else source.ready } }
        }
        fun corrupt() = db.dao().checkpoint(ScheduleSyncCheckpoint(1, byteArrayOf(1)))
        fun repair() = db.dao().deleteSyncRecord(1)
        fun snapshot() = db.dao().sources() to db.dao().imported()
        fun retry() {
            main { assertTrue(source.retryLocalRead()) }
            until { main { source.ready && !source.error && !source.retryingLocalRead } }
        }
        fun updated(timeout: Long = 8000) = until(timeout) { main {
            !controller.isSyncing(id) && (controller.binding(id)?.lastSuccess ?: 0) > 0 && source.state.imported.single().title == "远端更新"
        } }
        override fun close() {
            responseGate.get()?.countDown()
            main { controller.close(); source.close() }
            check(executor.awaitTermination(10, TimeUnit.SECONDS)); server.shutdown()
            WebDavSettings.overrideNamespace = null; TermScheduleProfileStore.overrideNamespace = null; SharedScheduleSettings.overrideNamespace = null
        }
    }
    @Test fun failedFirstReadRestoresFutureSchedulingWithoutImmediateFetchOrBindingCleanup() {
        Fixture(initialFailure = true).use { f ->
            val rows = f.snapshot()
            assertFalse(main { f.source.ready }); assertEquals(listOf(f.original), f.settings.bindings())
            assertNull(f.server.takeRequest(250, TimeUnit.MILLISECONDS))
            f.repair(); f.retry()
            assertNull("Manual reread must not immediately download", f.server.takeRequest(250, TimeUnit.MILLISECONDS))
            assertEquals(listOf(f.original), f.settings.bindings()); assertEquals(rows, f.snapshot())
            f.updated()
            assertEquals("GET", f.server.takeRequest(1, TimeUnit.SECONDS)!!.method)
            assertEquals(f.id, f.settings.bindings().single().sourceId)
            assertEquals("远端更新", f.db.dao().imported().single().title)
        }
    }
    @Test fun readFailureCancelsAlreadyScheduledAutomaticImportsWithoutChangingRowsOrBinding() {
        Fixture(initialFailure = false, period = 1200, overdue = false).use { f ->
            val rows = f.snapshot(); val binding = f.settings.bindings()
            f.corrupt(); main { f.source.reload() }
            until { main { f.source.error } }
            assertNull(f.server.takeRequest(1800, TimeUnit.MILLISECONDS))
            assertEquals(rows, f.snapshot()); assertEquals(binding, f.settings.bindings())
            assertFalse(main { f.controller.isSyncing(f.id) })
        }
    }
    @Test fun successfulFirstReadStillRunsDueAutomaticUpdate() {
        Fixture(initialFailure = false).use { f ->
            f.updated()
            assertEquals("GET", f.server.takeRequest(1, TimeUnit.SECONDS)!!.method)
            assertEquals(f.id, f.settings.bindings().single().sourceId)
        }
    }
    @Test fun futureDueTimeSurvivesRetryInsteadOfBeingPostponedAnotherFullPeriod() {
        Fixture(initialFailure = true, period = 6000).use { f ->
            f.repair()
            f.settings.saveBindings(listOf(f.original.copy(lastAttempt = System.currentTimeMillis() - 4000)))
            f.retry()
            assertNull(f.server.takeRequest(150, TimeUnit.MILLISECONDS))
            f.updated(timeout = 4000) // Existing due time is ~2s away, a new full interval would be 6s.
            assertEquals("GET", f.server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        }
    }
    @Test fun requestAlreadyInFlightCannotImportAfterLocalReadFails() {
        Fixture(initialFailure = false, period = 5000, overdue = false).use { f ->
            val rows = f.snapshot(); val gate = CountDownLatch(1); f.responseGate.set(gate)
            main { f.controller.sync(f.id) }
            assertNotNull(f.server.takeRequest(2, TimeUnit.SECONDS))
            f.corrupt(); main { f.source.reload() }; until { main { f.source.error } }
            gate.countDown()
            until { main { !f.controller.isSyncing(f.id) } }
            assertEquals(rows, f.snapshot())
            assertEquals(f.id, f.settings.bindings().single().sourceId)
            assertEquals(f.original.remote, f.settings.bindings().single().remote)
            assertNull(f.server.takeRequest(200, TimeUnit.MILLISECONDS))
        }
    }
}
