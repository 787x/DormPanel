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
    @Test fun sourcesEntryCreatesAndPublishesWithoutChangingImportBindings() {
        val namespace = "pr31-publisher-${UUID.randomUUID()}"
        SharedScheduleSettings.overrideNamespace = namespace
        TermScheduleProfileStore.overrideNamespace = namespace
        MockWebServer().use { server ->
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
                    onView(withText("Publish shared timetable (WebDAV)")).perform(scrollTo(), click())
                    waitFor(scenario) { !it.sharedSchedule.busy }
                    onView(withText("首次创建共享课表")).perform(scrollTo(), click())
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
