package com.dormpanel.phone

import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

class PhoneBidirectionalUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun waitFor(s: ActivityScenario<TimetableActivity>, predicate: (TimetableActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + 25000
        while (System.currentTimeMillis() < deadline) {
            var done = false; s.onActivity { done = predicate(it) }
            if (done) return
            Thread.sleep(40)
        }
        error("Bidirectional phone UI did not reach expected state")
    }
    @Test fun conflictUiKeepsLocalOrAdoptsRemoteWithoutLosingIndependentChanges() {
        val context = instrumentation.targetContext
        for (keepLocal in listOf(true, false)) {
            val name = "pr32-ui-${UUID.randomUUID()}"
            val executors = mutableListOf<ExecutorService>()
            SharedScheduleSettings.overrideNamespace = name; WebDavSettings.overrideNamespace = name; TermScheduleProfileStore.overrideNamespace = name
            ScheduleStores.overrideFactory = { c -> RoomScheduleStore(Room.databaseBuilder(c, ScheduleDatabase::class.java, "$name.db").build(),
                Executors.newSingleThreadExecutor().also { executors += it }, profiles = TermScheduleProfileStore(c)) }
            MockWebServer().use { server ->
                val files = mutableMapOf<String, ByteArray>()
                val offline = java.util.concurrent.atomic.AtomicBoolean(false)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = synchronized(files) {
                        if (offline.get()) return@synchronized MockResponse().setResponseCode(503)
                        val key = request.path!!; val old = files[key]; val etag = old?.let { "\"${digest(it)}\"" }
                        if (request.method == "GET") return@synchronized if (old == null) MockResponse().setResponseCode(404)
                            else MockResponse().setBody(okio.Buffer().write(old)).addHeader("ETag", etag!!)
                        if (request.method != "PUT") return@synchronized MockResponse().setResponseCode(405)
                        if (request.getHeader("If-None-Match") == "*" && old != null || request.getHeader("If-Match")?.let { it != etag } == true)
                            return@synchronized MockResponse().setResponseCode(412)
                        files[key] = request.body.readByteArray(); MockResponse().setResponseCode(if (old == null) 201 else 204)
                    }
                }
                try {
                    val account = WebDavAccount(server.url("/shared/").toString(), "user", "secret")
                    WebDavSettings(context).saveAccount(account); SharedScheduleSettings(context).folder = account.baseUrl
                    val transport = SharedScheduleTransport(); val first = SnapshotFixture.snapshot()
                    transport.publish(account, account.baseUrl, ScheduleSnapshotCodec.encode(first), null, null)
                    val bound = transport.inspect(account, account.baseUrl)
                    ActivityScenario.launch(TimetableActivity::class.java).use { scenario ->
                        waitFor(scenario) { it.source.ready && !it.sharedSync.busy }
                        scenario.onActivity { it.sharedSync.bind(bound) }
                        waitFor(scenario) { it.source.state.sync != null && !it.sharedSync.busy }
                        // No manual sync: repeated committed edits must trigger automatic publication.
                        for (room in listOf("A200", "A201")) {
                            var revision: String? = null
                            scenario.onActivity {
                                revision = it.source.state.sync!!.revision
                                val entry = it.source.state.entries.single()
                                assertTrue(it.source.saveEntry(entry.title, entry.day, entry.startMinute, entry.endMinute, room, entry.id))
                            }
                            waitFor(scenario) { !it.sharedSync.busy && it.source.state.sync?.revision != revision && it.sharedSync.status == "已同步" }
                            assertEquals(room, transport.inspect(account, account.baseUrl).snapshot.state.entries.single().location)
                        }
                        val latest = transport.inspect(account, account.baseUrl)
                        val remote = latest.snapshot.copy(revision = UUID.randomUUID().toString(), parentRevision = latest.head.revision, generation = latest.snapshot.generation + 1,
                            state = latest.snapshot.state.copy(entries = latest.snapshot.state.entries.map { it.copy(location = "B201") }, events = emptyList()))
                        transport.publish(account, account.baseUrl, ScheduleSnapshotCodec.encode(remote), latest.head.revision, latest.etag)
                        scenario.onActivity {
                            // Manual sync makes the simulated offline conflict deterministic.
                            it.source.localCommitted = null
                            val entry = it.source.state.entries.single()
                            assertTrue(it.source.saveEntry(entry.title, entry.day, entry.startMinute, entry.endMinute, "A301", entry.id))
                            it.sharedSync.sync()
                        }
                        waitFor(scenario) { !it.sharedSync.busy && it.sharedSync.conflicts.isNotEmpty() }
                        if (keepLocal) {
                            offline.set(true); scenario.recreate()
                            waitFor(scenario) { it.source.ready && !it.sharedSync.busy && it.sharedSync.status.contains("冲突") }
                            scenario.onActivity { assertEquals("A301", it.source.state.entries.single().location) }
                            offline.set(false)
                        }
                        onView(withText("管理")).perform(click())
                        onView(withText("WebDAV 共享课表（双向同步）")).perform(click())
                        onView(withText("查看 / 处理同步冲突")).perform(scrollTo(), click())
                        waitFor(scenario) { !it.sharedSync.busy }
                        instrumentation.waitForIdleSync()
                        val bitmap = instrumentation.uiAutomation.takeScreenshot()
                        java.io.File(context.getExternalFilesDir(null), "pr32-conflict-${if (keepLocal) "local" else "remote"}.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }; bitmap.recycle()
                        onView(withText(if (keepLocal) "保留本地" else "采用远端")).perform(click())
                        waitFor(scenario) { !it.sharedSync.busy && it.sharedSync.conflicts.isEmpty() && it.sharedSync.status == "已同步" }
                        scenario.onActivity {
                            assertFalse(it.source.readOnly)
                            assertEquals(if (keepLocal) "A301" else "B201", it.source.state.entries.single().location)
                            assertTrue(it.source.state.events.isEmpty())
                        }
                        assertEquals(if (keepLocal) "A301" else "B201", transport.inspect(account, account.baseUrl).snapshot.state.entries.single().location)
                        onView(withText("关闭")).perform(click())
                    }
                } finally {
                    executors.forEach { it.shutdown(); it.awaitTermination(10, TimeUnit.SECONDS) }
                    ScheduleStores.overrideFactory = null; SharedScheduleSettings.overrideNamespace = null
                    WebDavSettings.overrideNamespace = null; TermScheduleProfileStore.overrideNamespace = null
                    context.deleteDatabase("$name.db")
                    listOf(name, "${name}_credentials", "${name}_settings").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
                }
            }
        }
    }
}
