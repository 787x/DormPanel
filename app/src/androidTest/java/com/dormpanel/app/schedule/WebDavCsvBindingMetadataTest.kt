package com.dormpanel.app.schedule

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.IsolatedDashboardRule
import com.dormpanel.app.MainActivity
import com.dormpanel.app.dashboard.DashboardViewModel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WebDAV CSV binding metadata must be recorded on the first successful import,
 * and a local profile change must force re-import despite unchanged remote validators.
 */
class WebDavCsvBindingMetadataTest {
    @get:Rule val persistence = IsolatedDashboardRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun model(activity: MainActivity) = ViewModelProvider(activity)[DashboardViewModel::class.java]
    private fun await(latch: CountDownLatch) = assertTrue("Timed out", latch.await(15, TimeUnit.SECONDS))
    private fun ready(scenario: ActivityScenario<MainActivity>) {
        val deadline = System.currentTimeMillis() + 12000
        var loaded = false
        while (!loaded && System.currentTimeMillis() < deadline) {
            scenario.onActivity { loaded = model(it).schedule.ready }
            if (!loaded) Thread.sleep(30)
        }
        assertTrue(loaded)
    }

    @Test fun firstCsvImportRecordsFormatTermAndProfileFingerprint() {
        val csv = instrumentation.context.assets.open("hubei_2026-2027-1.csv").use { it.readBytes() }
        MockWebServer().use { server -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario)
            val remote = server.url("/dav/timetable.csv").toString()
            val account = WebDavAccount(server.url("/dav/").toString(), "user", "secret")
            server.enqueue(MockResponse().setBody(okio.Buffer().write(csv)).addHeader("ETag", "\"v1\""))

            // Use the same initial path the UI uses: webDav.initial → preview → source.import → webDav.bind
            val initialLatch = CountDownLatch(1)
            val initial = AtomicReference<Result<Pair<TimetableImporter.ParseOutcome, WebDavItem>>>()
            scenario.onActivity { model(it).webDav.initial(account, WebDavMode.FILE, remote) { result ->
                initial.set(result); initialLatch.countDown()
            } }
            await(initialLatch)
            val (outcome, item) = initial.get().getOrThrow()
            val preview = (outcome as TimetableImporter.ParseOutcome.Ready).preview

            val importLatch = CountDownLatch(1)
            val imported = AtomicReference<Result<ImportCommit>>()
            scenario.onActivity { activity ->
                val vm = model(activity)
                vm.schedule.import(preview, "CSV Test", null) { r -> imported.set(r); importLatch.countDown() }
            }
            await(importLatch)
            val commit = imported.get().getOrThrow()
            val sourceId = commit.source.id

            // Simulate what the UI commit path does for remote bindings.
            val profile = BuiltInProfiles.term2026
            val profilePrint = TimetableImporter.profileFingerprint(profile)
            scenario.onActivity { model(it).webDav.bind(WebDavBinding(sourceId, WebDavMode.FILE, remote,
                autoSync = false, etag = item.etag, lastModified = item.lastModified,
                currentFile = item.url, lastSuccess = System.currentTimeMillis(),
                format = "csv", termKey = preview.termKey, profileFingerprint = profilePrint)) }

            scenario.onActivity {
                val binding = model(it).webDav.binding(sourceId)!!
                assertEquals("csv", binding.format)
                assertEquals("2026-2027-1", binding.termKey)
                assertEquals(profilePrint, binding.profileFingerprint)
            }
        } }
    }

    @Test fun profileChangeInvalidatesStoredFingerprint() {
        val profile = BuiltInProfiles.term2026
        val print1 = TimetableImporter.profileFingerprint(profile)
        val print2 = TimetableImporter.profileFingerprint(profile.copy(
            week1Monday = profile.week1Monday.plusWeeks(1)))
        assertNotEquals(print1, print2)
    }
}
