package com.dormpanel.app.schedule

import android.content.Context
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
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WebDAV CSV profile-change regression: when the local profile changes but the
 * remote file/ETag are unchanged, sync must re-fetch and re-time occurrences —
 * it must not accept the 304 shortcut.
 */
class WebDavCsvProfileChangeSyncTest {
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

    @Test fun profileChangeForcesReimportWhenRemoteEtagUnchanged() {
        val csv = instrumentation.context.assets.open("hubei_2026-2027-1.csv").use { it.readBytes() }
        MockWebServer().use { server -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario)
            val remote = server.url("/dav/timetable.csv").toString()
            val account = WebDavAccount(server.url("/dav/").toString(), "user", "secret")

            // 1. Initial CSV import via the real controller path.
            server.enqueue(MockResponse().setBody(okio.Buffer().write(csv)).addHeader("ETag", "\"v1\""))
            val initialLatch = CountDownLatch(1)
            val initial = AtomicReference<Result<Pair<TimetableImporter.ParseOutcome, WebDavItem>>>()
            scenario.onActivity { model(it).webDav.initial(account, WebDavMode.FILE, remote) { r ->
                initial.set(r); initialLatch.countDown()
            } }
            await(initialLatch)
            val (outcome, item) = initial.get().getOrThrow()
            val preview = (outcome as TimetableImporter.ParseOutcome.Ready).preview

            val importLatch = CountDownLatch(1)
            val imported = AtomicReference<Result<ImportCommit>>()
            scenario.onActivity { activity ->
                val vm = model(activity)
                vm.schedule.import(preview, "CSV Sync", null) { r -> imported.set(r); importLatch.countDown() }
            }
            await(importLatch)
            val commit = imported.get().getOrThrow()
            val sourceId = commit.source.id
            val firstStarts = commit.occurrences.map { it.start }

            // 2. Record binding metadata exactly as the UI commit path does.
            val profile = BuiltInProfiles.term2026
            val profilePrint = TimetableImporter.profileFingerprint(profile)
            scenario.onActivity { model(it).webDav.apply {
                settings.saveAccount(account)
                bind(WebDavBinding(sourceId, WebDavMode.FILE, remote,
                    autoSync = false, etag = item.etag, lastModified = item.lastModified,
                    currentFile = item.url, lastSuccess = System.currentTimeMillis(),
                    format = "csv", termKey = "2026-2027-1", profileFingerprint = profilePrint))
            } }
            // Verify the binding is readable with the correct metadata before sync.
            scenario.onActivity {
                val b = model(it).webDav.binding(sourceId)!!
                assertEquals("csv", b.format)
                assertEquals("2026-2027-1", b.termKey)
                assertEquals(profilePrint, b.profileFingerprint)
            }

            // 3. Edit the local profile (shift Week 1 by one week).
            val changed = profile.copy(week1Monday = profile.week1Monday.plusWeeks(1))
            scenario.onActivity { model(it).termProfiles.put(changed) }
            // Verify the edit is visible through a fresh store instance (same prefs).
            val verifyStore = TermScheduleProfileStore(instrumentation.targetContext)
            assertEquals(LocalDate.parse("2026-09-07"), verifyStore.get("2026-2027-1")!!.week1Monday)
            val newProfilePrint = TimetableImporter.profileFingerprint(changed)
            assertNotEquals(profilePrint, newProfilePrint)
            // Diagnostic: verify sync code would detect the profile change.
            scenario.onActivity {
                val b = model(it).webDav.binding(sourceId)!!
                val ps = TermScheduleProfileStore(instrumentation.targetContext)
                val cur = b.termKey?.let { t -> ps.get(t)?.let { TimetableImporter.profileFingerprint(it) } }
                assertTrue("binding.format should be csv, was '${b.format}'", b.format == "csv")
                assertTrue("binding.profileFingerprint should differ from current, stored=${b.profileFingerprint} current=$cur",
                    b.profileFingerprint != null && b.profileFingerprint != cur)
            }

            // 4. Remote file and ETag are unchanged. The server will return 200 with
            //    the same ETag — if sync naively short-circuits on unchanged ETag it
            //    will return "up to date" (false) and the assertion below will fail.
            server.enqueue(MockResponse().setBody(okio.Buffer().write(csv)).addHeader("ETag", "\"v1\""))
            val syncLatch = CountDownLatch(1)
            val syncResult = AtomicReference<Result<Boolean>>()
            scenario.onActivity { model(it).webDav.sync(sourceId) { r ->
                syncResult.set(r); syncLatch.countDown()
            } }
            await(syncLatch)

            // 5. Assert binding stores the new profile fingerprint (real signal that sync ran).
            var updatedPrint: String? = null
            scenario.onActivity {
                val vm = model(it)
                val binding = vm.webDav.binding(sourceId)!!
                assertEquals("csv", binding.format)
                assertEquals("2026-2027-1", binding.termKey)
                updatedPrint = binding.profileFingerprint
            }
            val newPrint = TimetableImporter.profileFingerprint(BuiltInProfiles.term2026.copy(
                week1Monday = BuiltInProfiles.term2026.week1Monday.plusWeeks(1)))
            assertEquals(newPrint, updatedPrint)
            assertNotEquals(profilePrint, updatedPrint)

            // 6. Assert the sync did not simply return "up to date".
            assertTrue("Sync must succeed", syncResult.get().isSuccess)
            val syncChanged = syncResult.get().getOrDefault(false)
            val requestCount = server.requestCount
            assertTrue("Expected at least 2 GETs, got $requestCount",
                requestCount >= 2)
            assertTrue("Sync must re-import when profile changed (got $syncChanged, requests=$requestCount)",
                syncChanged)

        } }
    }
}
