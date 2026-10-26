package com.dormpanel.phone

import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import okhttp3.mockwebserver.*
import org.hamcrest.Matchers.containsString
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

/** Only isolated Room databases/preferences and Fake WebDAV; never touches daily data. */
class PhoneSharedScheduleInspectionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun waitFor(s: ActivityScenario<TimetableActivity>, predicate: (TimetableActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) {
            dismissTestPasswordPrompt()
            var done = false; s.onActivity { done = predicate(it) }
            if (done) return
            Thread.sleep(40)
        }
        error("Inspection UI did not reach expected state")
    }
    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
    private inner class Fixture : AutoCloseable {
        val context = instrumentation.targetContext
        val namespace = "pr41-inspect-${UUID.randomUUID()}"
        val db = Room.databaseBuilder(context, ScheduleDatabase::class.java, "$namespace.db").build()
        val executors = mutableListOf<ExecutorService>()
        val server = MockWebServer()
        val files = ConcurrentHashMap<String, ByteArray>()
        val writes = ConcurrentLinkedQueue<String>()
        val gate = AtomicReference<Gate?>()
        val root = SnapshotFixture.snapshot().let { ScheduleCommit(it.datasetId, it.revision, UUID.randomUUID().toString(), emptyList(), it.state) }
        val accounts: WebDavSettings
        val settings: SharedScheduleSettings
        val store: RoomScheduleStore
        init {
            TermScheduleProfileStore.overrideNamespace = namespace
            WebDavSettings.overrideNamespace = namespace; SharedScheduleSettings.overrideNamespace = namespace
            store = newStore()
            store.v2Frame() // Persist this installation's writer before measuring read-only operations.
            files[path(root)] = ScheduleCommitCodec.encode(root)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method !in listOf("GET", "PROPFIND")) writes += request.method.orEmpty()
                    if (request.method == "GET") gate.getAndSet(null)?.let {
                        it.entered.countDown(); check(it.release.await(20, TimeUnit.SECONDS))
                    }
                    v2Listing(files, request)?.let { return it }
                    return files[request.path]?.let { MockResponse().setBody(okio.Buffer().write(it)) }
                        ?: MockResponse().setResponseCode(404)
                }
            }
            val account = WebDavAccount(server.url("/").toString(), "user", "secret")
            accounts = WebDavSettings(context).also { it.saveAccount(account) }
            settings = SharedScheduleSettings(context).also { it.folder = server.url("/share/").toString() }
            ScheduleStores.overrideFactory = { newStore() }
        }
        private fun newStore() = RoomScheduleStore(db, Executors.newSingleThreadExecutor().also { executors += it },
            closeDatabase = false, profiles = TermScheduleProfileStore(context))
        fun path(c: ScheduleCommit) = "/share/dormpanel-sync-v2/commit-${c.commitId}.dpc"
        fun localBytes(): Map<String, String> = buildMap {
            listOf("events", "timetable", "import_sources", "imported_timetable_occurrences", "day_adjustments", "class_overrides", "schedule_sync_checkpoint").forEach { table ->
                db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { cursor ->
                    var row = 0
                    while (cursor.moveToNext()) {
                        val values = (0 until cursor.columnCount).joinToString("|") { col ->
                            if (cursor.isNull(col)) "null" else if (cursor.getType(col) == android.database.Cursor.FIELD_TYPE_BLOB) digest(cursor.getBlob(col)) else cursor.getString(col)
                        }
                        put("$table:${row++}", values)
                    }
                }
            }
        }
        fun open(s: ActivityScenario<TimetableActivity>) {
            waitFor(s) { it.source.ready && !it.sharedSync.busy && it.hasWindowFocus() }
            onView(withText("管理")).perform(click())
            onView(withText("WebDAV 共享课表（双向同步）")).perform(click())
        }
        fun inspect() {
            val bound = store.sharedPresentation(true).bound
            com.dormpanel.app.schedule.testing.FolderUiChecks.manage(if (bound) "技术诊断" else "旧版兼容与升级", if (bound) "只读检查旧版远端课表（v2）" else "预览并加入旧版课表（v2）")
        }
        override fun close() {
            gate.getAndSet(null)?.release?.countDown()
            store.close(); executors.forEach { check(it.awaitTermination(10, TimeUnit.SECONDS)) }; db.close()
            ScheduleStores.overrideFactory = null; TermScheduleProfileStore.overrideNamespace = null
            WebDavSettings.overrideNamespace = null; SharedScheduleSettings.overrideNamespace = null
            context.deleteDatabase("$namespace.db")
            listOf(namespace, "${namespace}_settings", "${namespace}_credentials").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
            server.close()
        }
    }
    @Test fun firstBindThenRepeatedBoundInspectionIsReadOnlyIncludingAllCheckpoints() {
        Fixture().use { f -> launchPhoneTestActivity().use { s ->
            f.open(s); onView(withText("加入已有共享课表")).perform(scrollTo(), click()); waitFor(s) { !it.sharedSync.busy }; dismissTestPasswordPrompt()
            val previewDeadline = System.currentTimeMillis() + 20000
            while (true) {
                try { onView(withText("确认下载并绑定")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed())); break }
                catch (e: androidx.test.espresso.NoMatchingViewException) {
                    if (System.currentTimeMillis() >= previewDeadline) throw e
                    Thread.sleep(40)
                }
            }
            onView(withText("确认下载并绑定")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
            waitFor(s) { it.source.state.sync != null && !it.source.applyingSnapshot && !it.sharedSync.busy }
            val before = f.localBytes()
            // A later remote version must remain a preview, rather than being applied locally by inspect.
            val next = f.root.copy(commitId = UUID.randomUUID().toString(), parents = listOf(f.root.commitId),
                state = f.root.state.copy(entries = f.root.state.entries.map { it.copy(title = "Remote inspection only") }))
            f.files[f.path(next)] = ScheduleCommitCodec.encode(next)
            val remoteBefore = f.files.mapValues { digest(it.value) }
            repeat(2) {
                f.inspect(); waitFor(s) { !it.sharedSync.busy }; dismissTestPasswordPrompt()
                onView(withText(containsString("Commit: ${next.commitId}"))).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed()))
                onView(withText(containsString("远端历史：2 个已验证提交"))).check(matches(isDisplayed()))
                onView(withText("确认下载并绑定")).check(doesNotExist())
                onView(withText("关闭预览")).perform(click())
                assertEquals(before, f.localBytes()); assertEquals(remoteBefore, f.files.mapValues { digest(it.value) })
                assertTrue("No PUT/MKCOL or other write allowed", f.writes.isEmpty())
            }
            onView(withText("关闭")).perform(click())
        } }
    }
    @Test fun bindingDuringAsyncInspectionReturnsReadOnlyPreview() {
        Fixture().use { f -> launchPhoneTestActivity().use { s ->
            f.open(s)
            val gate = Gate(); f.gate.set(gate)
            try {
                f.inspect(); assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
                f.store.v2Start(f.root, true)
                s.onActivity { it.source.reload() }
                waitFor(s) { it.source.state.sync != null && !it.source.applyingSnapshot }
                val before = f.localBytes(); gate.release.countDown()
                waitFor(s) { !it.sharedSync.busy }
                run {
                    val deadline = System.currentTimeMillis() + 20000
                    while (true) { try { onView(withText("已验证的远端课表")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed())); break }
                        catch (e: androidx.test.espresso.NoMatchingViewException) { if (System.currentTimeMillis() > deadline) throw e; Thread.sleep(40) } }
                }
                onView(withText("确认下载并绑定")).check(doesNotExist())
                onView(withText("关闭预览")).perform(click())
                assertEquals(before, f.localBytes()); assertTrue(f.writes.isEmpty())
                onView(withText("关闭")).perform(click())
            } finally { gate.release.countDown() }
        } }
    }
    @Test fun closingManagementDuringAsyncInspectionDoesNotOpenLatePreview() {
        Fixture().use { f -> launchPhoneTestActivity().use { s ->
            f.open(s); val before = f.localBytes()
            val gate = Gate(); f.gate.set(gate)
            try {
                f.inspect(); assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
                onView(withText("创建新的共享课表")).check(matches(org.hamcrest.Matchers.not(isEnabled())))
                onView(withText("加入已有共享课表")).check(matches(org.hamcrest.Matchers.not(isEnabled())))
                onView(withText("关闭")).perform(click()); gate.release.countDown()
                waitFor(s) { !it.sharedSync.busy && it.hasWindowFocus() }
                onView(withText("已验证的远端课表")).check(doesNotExist())
                onView(withText("确认下载并绑定")).check(doesNotExist())
                assertEquals(before, f.localBytes()); assertTrue(f.writes.isEmpty())
            } finally { gate.release.countDown() }
        } }
    }
}
