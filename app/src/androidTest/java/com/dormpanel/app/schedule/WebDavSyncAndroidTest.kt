package com.dormpanel.app.schedule

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.IsolatedDashboardRule
import com.dormpanel.app.MainActivity
import com.dormpanel.app.dashboard.DashboardViewModel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.hamcrest.Matchers.containsString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WebDavSyncAndroidTest {
    @get:Rule val persistence = IsolatedDashboardRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun model(activity: MainActivity) = ViewModelProvider(activity)[DashboardViewModel::class.java]
    private fun await(latch: CountDownLatch) = assertTrue("Async operation timed out", latch.await(12, TimeUnit.SECONDS))
    private fun ready(scenario: ActivityScenario<MainActivity>) {
        val deadline = System.currentTimeMillis() + 12000
        var loaded = false
        while (!loaded && System.currentTimeMillis() < deadline) {
            scenario.onActivity { loaded = model(it).schedule.ready }
            if (!loaded) Thread.sleep(30)
        }
        assertTrue(loaded)
    }
    private fun listing(vararg files: Pair<String, String>) = "<d:multistatus xmlns:d='DAV:'>" +
        files.joinToString("") { (name, date) ->
            "<d:response><d:href>/dav/$name</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop>" +
                "<d:displayname>$name</d:displayname><d:resourcetype/><d:getlastmodified>$date</d:getlastmodified>" +
                "</d:prop></d:propstat></d:response>"
        } + "</d:multistatus>"
    private fun waitText(value: String) {
        val deadline = System.currentTimeMillis() + 12000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { onView(withText(value)).check(matches(isDisplayed())) }.isSuccess) return
            Thread.sleep(80)
        }
        onView(withText(value)).check(matches(isDisplayed()))
    }

    @Test fun setupBrowseAndFirstImportUsesPreviewOnX08e() {
        MockWebServer().use { server -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario)
            val raw = instrumentation.context.assets.open("wakeup.ics").use { it.readBytes().toString(Charsets.UTF_8) }
            server.enqueue(MockResponse().setResponseCode(207).setBody(listing("current.ics" to "Thu, 24 Sep 2026 09:00:00 GMT")))
            server.enqueue(MockResponse().setBody(raw).addHeader("ETag", "\"v1\""))
            val serverUrl = server.url("/dav/").toString()
            var ui: ScheduleImportUi? = null
            try {
                scenario.onActivity { activity ->
                    val vm = model(activity)
                    vm.webDav.settings.saveAccount(WebDavAccount(serverUrl, "user", "secret"))
                    ui = ScheduleImportUi(activity, vm.schedule, vm.appearance, vm.webDav) {}
                    ui!!.sources()
                }
                onView(withText("Add WebDAV timetable")).perform(click())
                waitText("Select a folder, .ics, or .csv file.")
                onView(withText(containsString("current.ics"))).perform(click())
                waitText("Timetable import preview")
                scenario.onActivity { assertTrue(model(it).schedule.state.sources.isEmpty()) }
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "webdav-preview-x08e.png")
                    .outputStream().use { output -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }
                bitmap.recycle()
                onView(withText("Import")).perform(click())
                val deadline = System.currentTimeMillis() + 12000
                var imported = false
                while (!imported && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { imported = model(it).schedule.state.sources.size == 1 }
                    if (!imported) Thread.sleep(30)
                }
                assertTrue(imported)
                scenario.onActivity {
                    val vm = model(it)
                    assertEquals("webdav", vm.schedule.state.sources.single().kind)
                    assertEquals(WebDavMode.FILE, vm.webDav.binding(vm.schedule.state.sources.single().id)?.mode)
                }
            } finally { scenario.onActivity { ui?.close() } }
        } }
    }

    @Test fun previewFirstThenConditionalAndAtomicSourceUpdates() {
        MockWebServer().use { server -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario)
            val raw = instrumentation.context.assets.open("wakeup.ics").use { it.readBytes().toString(Charsets.UTF_8) }
            val changed = raw.replace("SUMMARY:高等数学B-1", "SUMMARY:新数学")
            val newest = changed.replace("SUMMARY:新数学", "SUMMARY:更新数学")
            val remote = server.url("/dav/current.ics").toString()
            val account = WebDavAccount(server.url("/dav/").toString(), "user", "secret")
            val previewLatch = CountDownLatch(1)
            val first = AtomicReference<Result<Pair<ImportPreview, WebDavItem>>>()
            server.enqueue(MockResponse().setBody(raw).addHeader("ETag", "\"v1\""))
            scenario.onActivity { model(it).webDav.initial(account, WebDavMode.FILE, remote) { result ->
                first.set(result); previewLatch.countDown()
            } }
            await(previewLatch)
            val (preview, item) = first.get().getOrThrow()
            assertEquals("GET", server.takeRequest().method)
            assertEquals("webdav", preview.kind)
            assertFalse(preview.locator!!.contains("user"))
            scenario.onActivity { assertTrue(model(it).schedule.state.sources.isEmpty()) }

            val importLatch = CountDownLatch(1)
            val imported = AtomicReference<Result<ImportCommit>>()
            scenario.onActivity { activity ->
                val vm = model(activity)
                vm.schedule.saveEntry("Manual", 5, 600, 660)
                vm.schedule.import(preview, "Remote", null) { result -> imported.set(result); importLatch.countDown() }
            }
            await(importLatch)
            val id = imported.get().getOrThrow().source.id
            scenario.onActivity { model(it).webDav.apply {
                settings.saveAccount(account)
                bind(WebDavBinding(id, WebDavMode.FILE, remote, false, item.etag, item.lastModified,
                    item.url, System.currentTimeMillis()))
            } }
            server.enqueue(MockResponse().setResponseCode(304))
            scenario.onActivity { model(it).webDav.apply { bind(binding(id)!!.copy(autoSync = true, lastAttempt = 0)) } }
            val autoDeadline = System.currentTimeMillis() + 12000
            var autoFinished = false
            while (!autoFinished && System.currentTimeMillis() < autoDeadline) {
                scenario.onActivity { val controller = model(it).webDav
                    autoFinished = controller.binding(id)!!.lastAttempt > 0 && !controller.isSyncing(id) }
                if (!autoFinished) Thread.sleep(30)
            }
            assertTrue("Due automatic sync did not complete", autoFinished)
            assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
            lateinit var retainedController: WebDavSyncController
            scenario.onActivity { retainedController = model(it).webDav }
            val requestsBeforeRecreation = server.requestCount
            scenario.recreate()
            ready(scenario)
            scenario.onActivity { assertSame("Activity recreation must retain the process scheduler",
                retainedController, model(it).webDav) }
            assertEquals(requestsBeforeRecreation, server.requestCount)
            scenario.onActivity { model(it).webDav.apply { bind(binding(id)!!.copy(autoSync = false)) } }

            fun sync(response: MockResponse): Result<Boolean> {
                server.enqueue(response)
                val latch = CountDownLatch(1); val result = AtomicReference<Result<Boolean>>()
                scenario.onActivity { model(it).webDav.sync(id) { value -> result.set(value); latch.countDown() } }
                await(latch)
                return result.get()
            }
            assertFalse(sync(MockResponse().setResponseCode(304)).getOrThrow())
            assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
            assertTrue(sync(MockResponse().setBody(changed).addHeader("ETag", "\"v2\"")).getOrThrow())
            val stableHash = AtomicReference<String>()
            scenario.onActivity {
                val state = model(it).schedule.state
                assertEquals(1, state.sources.size)
                assertEquals(1, state.entries.size)
                assertEquals(id, state.sources.single().id)
                stableHash.set(state.sources.single().sha256)
            }
            assertTrue(sync(MockResponse().setBody("invalid").addHeader("ETag", "\"v3\"")).isFailure)
            assertTrue(sync(MockResponse().setResponseCode(404)).isFailure)
            scenario.onActivity { assertEquals(stableHash.get(), model(it).schedule.state.sources.single().sha256) }

            val date = "Thu, 24 Sep 2026 09:00:00 GMT"
            scenario.onActivity { model(it).webDav.bind(WebDavBinding(id, WebDavMode.FOLDER_LATEST_ICS,
                account.baseUrl, false, currentFile = remote)) }
            server.enqueue(MockResponse().setResponseCode(207).setBody(listing("current.ics" to date,
                "new.ics" to "Fri, 25 Sep 2026 09:00:00 GMT")))
            server.enqueue(MockResponse().setBody(newest).addHeader("ETag", "\"v4\""))
            val folderLatch = CountDownLatch(1); val folderResult = AtomicReference<Result<Boolean>>()
            scenario.onActivity { model(it).webDav.sync(id) { value -> folderResult.set(value); folderLatch.countDown() } }
            await(folderLatch)
            assertTrue(folderResult.get().getOrThrow())
            scenario.onActivity {
                val vm = model(it)
                assertEquals(1, vm.schedule.state.sources.size)
                assertEquals("new.ics", vm.schedule.state.sources.single().filename)
                assertEquals(1, vm.schedule.state.entries.size)
            }
            server.enqueue(MockResponse().setResponseCode(207).setBody(listing()))
            val emptyLatch = CountDownLatch(1); val emptyResult = AtomicReference<Result<Boolean>>()
            scenario.onActivity { model(it).webDav.sync(id) { value -> emptyResult.set(value); emptyLatch.countDown() } }
            await(emptyLatch)
            assertTrue(emptyResult.get().isFailure)
            scenario.onActivity { assertEquals("new.ics", model(it).schedule.state.sources.single().filename) }
            val deleteLatch = CountDownLatch(1)
            scenario.onActivity { model(it).schedule.deleteImport(id) { value -> assertTrue(value.isSuccess); deleteLatch.countDown() } }
            await(deleteLatch)
            scenario.onActivity {
                val vm = model(it)
                assertTrue(vm.schedule.state.sources.isEmpty())
                assertNull(vm.webDav.binding(id))
                assertEquals("secret", vm.webDav.settings.account()?.password)
                vm.webDav.settings.saveAccount(account)
                vm.webDav.bind(WebDavBinding("orphan", WebDavMode.FILE, remote))
                vm.schedule.refresh()
                assertNull(vm.webDav.binding("orphan"))
                assertEquals("secret", vm.webDav.settings.account()?.password)
            }
        } }
    }
}
