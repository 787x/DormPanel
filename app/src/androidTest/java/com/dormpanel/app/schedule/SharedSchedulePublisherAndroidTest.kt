package com.dormpanel.app.schedule

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.IsolatedDashboardRule
import com.dormpanel.app.MainActivity
import com.dormpanel.app.dashboard.DashboardViewModel
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SharedSchedulePublisherAndroidTest {
    @get:Rule val persistence = IsolatedDashboardRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun waitFor(s: ActivityScenario<MainActivity>, predicate: (DashboardViewModel) -> Boolean) {
        val until = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < until) {
            var done = false; s.onActivity { done = predicate(ViewModelProvider(it)[DashboardViewModel::class.java]) }
            if (done) return
            Thread.sleep(40)
        }
        error("Publisher did not reach expected state")
    }
    @Test fun existingDatasetCanBePreviewedCancelledRevalidatedBoundAndEditedFromX08eUi() {
        val namespace = "pr41-binding-${UUID.randomUUID()}"
        SharedScheduleSettings.overrideNamespace = namespace
        TermScheduleProfileStore.overrideNamespace = namespace
        MockWebServer().use { server ->
            server.start()
            val files = ConcurrentHashMap<String, ByteArray>()
            var puts = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    v2Listing(files, request)?.let { return it }
                    val path = request.path!!
                    if (request.method == "GET") return files[path]?.let { MockResponse().setBody(okio.Buffer().write(it)) }
                        ?: MockResponse().setResponseCode(404)
                    if (request.method == "PUT") { puts++; files[path] = request.body.readByteArray(); return MockResponse().setResponseCode(201) }
                    return MockResponse().setResponseCode(405)
                }
            }
            val root = ScheduleCommit(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                emptyList(), ScheduleState(entries = listOf(TimetableEntry("existing", "Existing FPK class", 4, 540, 585, "A301"))))
            fun path(c: ScheduleCommit) = "/shared/dormpanel-sync-v2/commit-${c.commitId}.dpc"
            files[path(root)] = ScheduleCommitCodec.encode(root)
            val account = WebDavAccount(server.url("/").toString(), "user", "secret")
            val folder = server.url("/shared/").toString()
            var dialog: android.app.AlertDialog? = null
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { it.schedule.ready && !it.sharedSchedule.busy }
                    scenario.onActivity { activity ->
                        val vm = ViewModelProvider(activity)[DashboardViewModel::class.java]
                        vm.webDav.settings.saveAccount(account)
                        vm.sharedSchedule.settings.folder = folder
                        dialog = SharedScheduleDialog.show(activity, vm.schedule, vm.sharedSchedule, true)
                    }
                    onView(withText("绑定已有共享课表（预览）")).perform(scrollTo(), click())
                    waitFor(scenario) { !it.sharedSchedule.busy }
                    onView(withText("取消")).perform(click())
                    scenario.onActivity { assertNull(ViewModelProvider(it)[DashboardViewModel::class.java].schedule.state.sync) }
                    assertEquals(0, puts); assertEquals(1, files.size)
                    onView(withText("绑定已有共享课表（预览）")).perform(scrollTo(), click())
                    waitFor(scenario) { !it.sharedSchedule.busy }
                    val next = root.copy(commitId = UUID.randomUUID().toString(), parents = listOf(root.commitId))
                    files[path(next)] = ScheduleCommitCodec.encode(next)
                    onView(withText("确认下载并绑定")).perform(click())
                    waitFor(scenario) { !it.sharedSchedule.busy }
                    scenario.onActivity {
                        val vm = ViewModelProvider(it)[DashboardViewModel::class.java]
                        assertNull(vm.schedule.state.sync); assertTrue(vm.sharedSchedule.status.contains("重新预览"))
                    }
                    assertEquals(0, puts)
                    onView(withText("绑定已有共享课表（预览）")).perform(scrollTo(), click())
                    waitFor(scenario) { !it.sharedSchedule.busy }
                    onView(withText("确认下载并绑定")).perform(click())
                    waitFor(scenario) { !it.sharedSchedule.busy && it.schedule.state.sync != null && !it.schedule.applyingSnapshot }
                    assertEquals(0, puts); assertEquals(2, files.size)
                    scenario.onActivity { activity ->
                        val vm = ViewModelProvider(activity)[DashboardViewModel::class.java]
                        assertEquals(root.datasetId, vm.schedule.state.sync!!.datasetId)
                        assertEquals("Existing FPK class", vm.schedule.state.entries.single().title)
                        dialog!!.dismiss()
                    }
                    scenario.recreate()
                    waitFor(scenario) { it.schedule.ready && it.schedule.state.sync != null && !it.sharedSchedule.busy }
                    scenario.onActivity { activity ->
                        val vm = ViewModelProvider(activity)[DashboardViewModel::class.java]
                        assertEquals(root.datasetId, vm.schedule.state.sync!!.datasetId)
                        assertTrue(vm.schedule.saveEntry("X08E edit", 4, 540, 585, "B201", "existing"))
                        vm.sharedSchedule.sync()
                    }
                    waitFor(scenario) { !it.sharedSchedule.busy && files.size == 3 }
                    val latest = files.values.map(ScheduleCommitCodec::decode).single { it.commitId != root.commitId && it.commitId != next.commitId }
                    assertEquals(root.datasetId, latest.datasetId); assertNotEquals(root.writerId, latest.writerId)
                    assertEquals(listOf(next.commitId), latest.parents); assertEquals("X08E edit", latest.state.entries.single().title)
                    scenario.onActivity { ViewModelProvider(it)[DashboardViewModel::class.java].sharedSchedule.sync() }
                    waitFor(scenario) { !it.sharedSchedule.busy }
                    assertEquals(3, files.size)
                }
            } finally {
                instrumentation.runOnMainSync { dialog?.dismiss() }
                SharedScheduleSettings.overrideNamespace = null; TermScheduleProfileStore.overrideNamespace = null
                instrumentation.targetContext.getSharedPreferences(namespace, 0).edit().clear().commit()
            }
        }
    }
    @Test fun sourcesEntryCreatesAndPublishesWithoutChangingImportBindings() {
        val namespace = "pr31-publisher-${UUID.randomUUID()}"
        SharedScheduleSettings.overrideNamespace = namespace
        TermScheduleProfileStore.overrideNamespace = namespace
        MockWebServer().use { server ->
            server.start()
            val files = ConcurrentHashMap<String, ByteArray>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    v2Listing(files, request)?.let { return it }
            val key = request.path!!; val old = files[key]; val etag = old?.let { "\"${digest(it)}\"" }
                    if (request.getHeader("Authorization") != "Basic dXNlcjpzZWNyZXQ=") return MockResponse().setResponseCode(401)
                    if (request.method == "GET") return if (old == null) MockResponse().setResponseCode(404)
                        else MockResponse().setBody(okio.Buffer().write(old)).addHeader("ETag", etag!!)
                    if (request.method != "PUT") return MockResponse().setResponseCode(405)
                    if (request.getHeader("If-None-Match") == "*" && old != null || request.getHeader("If-Match")?.let { it != etag } == true)
                        return MockResponse().setResponseCode(412)
                    files[key] = request.body.readByteArray()
                    return MockResponse().setResponseCode(if (old == null) 201 else 204)
                }
            }
            var ui: ScheduleImportUi? = null
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { it.schedule.ready }
                    val account = WebDavAccount(server.url("/").toString(), "user", "secret")
                    scenario.onActivity { activity ->
                        val vm = ViewModelProvider(activity)[DashboardViewModel::class.java]
                        vm.webDav.settings.saveAccount(account)
                        vm.sharedSchedule.settings.folder = server.url("/shared/").toString()
                        assertTrue(vm.schedule.saveEntry("X08E class", 1, 540, 585))
                        ui = ScheduleImportUi(activity, vm.schedule, vm.appearance, vm.webDav, vm.termProfiles, vm.sharedSchedule) {}
                        ui!!.sources()
                    }
                    onView(withText("共享课表管理（WebDAV）")).perform(scrollTo(), click())
                    waitFor(scenario) { !it.sharedSchedule.busy }
                    onView(withText("创建新的共享课表")).perform(scrollTo(), click())
                    waitFor(scenario) { !it.sharedSchedule.busy && files.isNotEmpty() }
                    scenario.onActivity { assertTrue("${ViewModelProvider(it)[DashboardViewModel::class.java].sharedSchedule.status}; files=${files.keys}", files.isNotEmpty()) }
                    val first = V2TestTransport().inspect(account, server.url("/shared/").toString())
                    assertEquals("X08E class", first.snapshot.state.entries.single().title)
                    scenario.onActivity { activity ->
                        val vm = ViewModelProvider(activity)[DashboardViewModel::class.java]
                        assertTrue(vm.webDav.settings.bindings().isEmpty())
                        assertFalse(vm.schedule.readOnly)
                        assertTrue(vm.schedule.saveEntry("Later edit", 2, 600, 645))
                    }
                    onView(withText("立即同步")).perform(scrollTo(), click())
                    waitFor(scenario) { !it.sharedSchedule.busy }
                    val second = V2TestTransport().inspect(account, server.url("/shared/").toString())
                    assertEquals(2L, second.snapshot.generation); assertEquals(first.head.revision, second.snapshot.parentRevision)
                    assertEquals(2, second.snapshot.state.entries.size)
                    instrumentation.waitForIdleSync()
                    val bitmap = instrumentation.uiAutomation.takeScreenshot()
                    java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "pr31-publisher-x08e.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                    scenario.onActivity { ui!!.close(); ui = null }
                }
            } finally {
                SharedScheduleSettings.overrideNamespace = null
                TermScheduleProfileStore.overrideNamespace = null
                instrumentation.targetContext.getSharedPreferences(namespace, 0).edit().clear().commit()
            }
        }
    }
}
