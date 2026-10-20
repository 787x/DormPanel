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
import org.hamcrest.Matchers.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

class PhoneSharedScheduleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> call(action: ((Result<T>) -> Unit) -> Unit): T {
        val future = CompletableFuture<Result<T>>()
        instrumentation.runOnMainSync { action { future.complete(it) } }
        return future.get(15, TimeUnit.SECONDS).getOrThrow()
    }
    private fun waitFor(scenario: ActivityScenario<TimetableActivity>, predicate: (TimetableActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) {
            var done = false; scenario.onActivity { done = predicate(it) }
            if (done) return
            Thread.sleep(40)
        }
        error("Phone sync did not reach expected state")
    }
    private class Dav : Dispatcher() {
        val files = ConcurrentHashMap<String, ByteArray>()
        var offline = false
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (offline) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            if (request.getHeader("Authorization") != "Basic dXNlcjpzZWNyZXQ=") return MockResponse().setResponseCode(401)
            val key = request.path!!; val old = files[key]; val etag = old?.let { "\"${digest(it)}\"" }
            if (request.method == "GET") return if (old == null) MockResponse().setResponseCode(404)
                else MockResponse().setBody(okio.Buffer().write(old)).addHeader("ETag", etag!!)
            if (request.method != "PUT") return MockResponse().setResponseCode(405)
            if (request.getHeader("If-None-Match") == "*" && old != null || request.getHeader("If-Match")?.let { it != etag } == true)
                return MockResponse().setResponseCode(412)
            files[key] = request.body.readByteArray()
            return MockResponse().setResponseCode(if (old == null) 201 else 204)
        }
    }
    @Test fun publishPreviewConfirmUpdateReadOnlyAndOfflineReopen() {
        val context = instrumentation.targetContext
        val token = UUID.randomUUID().toString()
        val writerName = "writer-$token.db"; val phoneName = "follower-$token.db"
        val writerNamespace = "writer-$token"; val followerNamespace = "follower-$token"
        val executors = mutableListOf<ExecutorService>()
        val writerExecutor = Executors.newSingleThreadExecutor().also { executors += it }
        var controller: SharedScheduleController? = null
        var writer: ScheduleSource? = null
        var dialog: android.app.AlertDialog? = null
        MockWebServer().use { server ->
            val dav = Dav(); server.dispatcher = dav
            try {
                val seed = SnapshotFixture.snapshot()
                val writerDb = Room.databaseBuilder(context, ScheduleDatabase::class.java, writerName).build()
                writerDb.runInTransaction {
                    val dao = writerDb.dao(); val v = seed.state
                    v.entries.forEach { dao.put(TimetableRow(it.id, it.title, it.day, it.startMinute, it.endMinute, it.location)) }
                    v.events.forEach { dao.put(EventRow(it.id, it.title, it.start, it.end, it.note)) }
                    v.sources.forEach(dao::insertSource); dao.insertOccurrences(v.imported)
                    v.dayAdjustments.forEach(dao::put); v.classOverrides.forEach(dao::put)
                }
                TermScheduleProfileStore.overrideNamespace = writerNamespace
                val writerProfiles = TermScheduleProfileStore(context)
                seed.state.profiles.values.forEach(writerProfiles::put)
                val writerStore = RoomScheduleStore(writerDb, writerExecutor, profiles = writerProfiles)
                instrumentation.runOnMainSync { writer = ScheduleSource(writerStore, profileResolver = writerProfiles::get) }
                call<ScheduleState>(writerStore::load)
                WebDavSettings.overrideNamespace = writerNamespace; SharedScheduleSettings.overrideNamespace = writerNamespace
                val account = WebDavAccount(server.url("/").toString(), "user", "secret")
                val publisherAccount = WebDavSettings(context).also { it.saveAccount(account) }
                val publisherSettings = SharedScheduleSettings(context).also { it.folder = server.url("/share/").toString() }
                instrumentation.runOnMainSync { controller = SharedScheduleController(context, writer!!, publisherAccount, publisherSettings) }

                TermScheduleProfileStore.overrideNamespace = followerNamespace
                WebDavSettings.overrideNamespace = followerNamespace; SharedScheduleSettings.overrideNamespace = followerNamespace
                ScheduleStores.overrideFactory = { c -> RoomScheduleStore(
                    Room.databaseBuilder(c, ScheduleDatabase::class.java, phoneName).build(),
                    Executors.newSingleThreadExecutor().also { executors += it }, profiles = TermScheduleProfileStore(c)) }
                ActivityScenario.launch(TimetableActivity::class.java).use { scenario ->
                    waitFor(scenario) { it.source.ready }
                    scenario.onActivity { dialog = SharedScheduleDialog.show(it, writer!!, controller!!, true) }
                    onView(withText("首次创建共享课表")).perform(scrollTo(), click())
                    waitFor(scenario) { !controller!!.busy }
                    assertNotNull(publisherSettings.publishedRevision)
                    onView(withText("关闭")).perform(click())
                    waitFor(scenario) { it.hasWindowFocus() && !it.sharedSync.busy }
                    instrumentation.waitForIdleSync()
                    Thread.sleep(350) // Native window dismissal is not tracked by Espresso idleness.

                    onView(withText("管理")).perform(click())
                    onView(withText("WebDAV 共享课表（只读订阅）")).perform(click())
                    onView(withHint("WebDAV 服务地址")).perform(scrollTo(), replaceText(account.baseUrl), closeSoftKeyboard())
                    onView(withHint("用户名")).perform(scrollTo(), replaceText("user"), closeSoftKeyboard())
                    onView(withHint("密码（留空保留已保存密码）")).perform(scrollTo(), replaceText("secret"), closeSoftKeyboard())
                    onView(withHint("共享目录完整 URL")).perform(scrollTo(), replaceText(publisherSettings.folder), closeSoftKeyboard())
                    onView(withText("测试连接 / 检查远端快照")).perform(scrollTo(), click())
                    // Transport finishes asynchronously; wait for its confirmation dialog without accepting it early.
                    val deadline = System.currentTimeMillis() + 20000
                    while (System.currentTimeMillis() < deadline) {
                        try { onView(withText("确认下载并绑定")).check(matches(isDisplayed())); break } catch (_: AssertionError) { Thread.sleep(40) }
                        catch (_: androidx.test.espresso.NoMatchingViewException) { Thread.sleep(40) }
                    }
                    scenario.onActivity { assertFalse(it.source.readOnly); assertTrue(it.source.state.entries.isEmpty()) }
                    onView(withText("确认下载并绑定")).perform(click())
                    waitFor(scenario) { it.source.readOnly && !it.source.applyingSnapshot }
                    onView(withText("关闭")).perform(click())
                    onView(withText("＋ 课程")).check(doesNotExist())
                    onView(withText("日期调整")).check(doesNotExist())
                    scenario.onActivity {
                        assertEquals("stable-source", it.source.state.sources.single().id)
                        assertEquals("stable-instance", it.source.state.imported.single().id)
                        assertEquals(3, it.source.state.classOverrides.size)
                        assertEquals(seed.state.profiles, it.source.state.profiles)
                        assertFalse(it.source.saveEntry("forbidden", 1, 1, 2))
                        assertFalse(it.source.saveDayAdjustment(java.time.LocalDate.parse("2026-10-12"), "none"))
                    }
                    val secrets = context.getSharedPreferences("${followerNamespace}_credentials", 0).getString("payload", null)!!
                    assertFalse(secrets.contains("secret")); assertEquals("secret", WebDavSettings(context).account()!!.password)
                    val firstId = publisherSettings.publishedRevision
                    // Recreate a publisher that died after remote commit but before saving its receipt.
                    val remoteReceipt = SharedScheduleTransport().inspect(account, publisherSettings.folder)
                    publisherSettings.pending(remoteReceipt.snapshot, remoteReceipt.bytes)
                    context.getSharedPreferences(writerNamespace, 0).edit().remove("published_revision").remove("published_etag").remove("published_generation").commit()
                    instrumentation.runOnMainSync {
                        controller!!.close()
                        controller = SharedScheduleController(context, writer!!, publisherAccount, publisherSettings)
                    }
                    instrumentation.runOnMainSync { assertTrue(writer!!.saveEntry("New revision", 3, 700, 745, id = "local")) }
                    writerProfiles.put(writerProfiles.get("custom-term")!!.copy(timezone = "Asia/Tokyo"))
                    instrumentation.runOnMainSync { controller!!.publish() }
                    waitFor(scenario) { !controller!!.busy }
                    assertNotEquals(firstId, publisherSettings.publishedRevision)
                    assertEquals(2L, publisherSettings.publishedGeneration)
                    onView(withText("管理")).perform(click())
                    onView(withText("立即同步")).perform(scrollTo(), click())
                    waitFor(scenario) { it.source.state.entries.singleOrNull()?.title == "New revision" && !it.source.applyingSnapshot }
                    scenario.onActivity { assertEquals("Asia/Tokyo", it.source.state.profiles["custom-term"]!!.timezone) }
                    onView(withText("关闭")).perform(click())
                    // Bad remote bytes must leave the successful revision intact.
                    dav.files["/share/snapshot-${publisherSettings.publishedRevision}.dps"] = byteArrayOf(1, 2)
                    onView(withText("管理")).perform(click())
                    onView(withText("立即同步")).perform(scrollTo(), click())
                    waitFor(scenario) { !it.sharedSync.busy }
                    scenario.onActivity { assertEquals("New revision", it.source.state.entries.single().title); assertTrue(it.sharedSync.status.contains("checksum")) }
                    onView(withText("关闭")).perform(click())
                    dav.offline = true
                }
                ActivityScenario.launch(TimetableActivity::class.java).use { scenario ->
                    waitFor(scenario) { it.source.ready && it.source.readOnly }
                    waitFor(scenario) { !it.sharedSync.busy }
                    scenario.onActivity {
                        assertEquals("New revision", it.source.state.entries.single().title)
                        assertEquals("Asia/Tokyo", it.source.state.profiles["custom-term"]!!.timezone)
                        assertTrue(it.sharedSync.status.contains("离线"))
                    }
                    onView(withText("＋ 课程")).check(doesNotExist())
                    instrumentation.waitForIdleSync()
                    val image = instrumentation.uiAutomation.takeScreenshot()
                    java.io.File(context.getExternalFilesDir(null), "pr31-follower.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                }
            } finally {
                instrumentation.runOnMainSync { dialog?.dismiss(); controller?.close(); writer?.close() }
                executors.forEach { it.awaitTermination(15, TimeUnit.SECONDS) }
                ScheduleStores.overrideFactory = null; TermScheduleProfileStore.overrideNamespace = null
                WebDavSettings.overrideNamespace = null; SharedScheduleSettings.overrideNamespace = null
                listOf(writerName, phoneName).forEach(context::deleteDatabase)
                listOf(writerNamespace, followerNamespace).forEach { ns ->
                    listOf(ns, "${ns}_settings", "${ns}_credentials").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
                }
            }
        }
    }
}
