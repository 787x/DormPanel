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
            dismissTestPasswordPrompt()
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
            if (request.method == "PROPFIND" && request.path in listOf("/", "/share/")) {
                val paths = if (request.path == "/") listOf("/", "/share/") else listOf("/share/")
                val rows = paths.joinToString("") { "<d:response><d:href>$it</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" }
                return MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">$rows</d:multistatus>")
            }
            v2Listing(files, request)?.let { return it }
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
    @Test fun publishPreviewConfirmBidirectionalEditingAndOfflineReopen() {
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
                launchPhoneTestActivity().use { scenario ->
                    waitFor(scenario) { it.source.ready && it.hasWindowFocus() && !it.sharedSync.busy && !controller!!.busy }
                    scenario.onActivity { dialog = SharedScheduleDialog.show(it, writer!!, controller!!, true) }
                    waitFor(scenario) { dialog?.window?.decorView?.hasWindowFocus() == true }
                    onView(withText("创建新的共享课表")).perform(scrollTo(), click())
                    waitFor(scenario) { !controller!!.busy }
                    assertNotNull(writerStore.v2Frame().baseline?.commitId)
                    onView(withText("关闭")).perform(click())
                    waitFor(scenario) { it.hasWindowFocus() && !it.sharedSync.busy }
                    waitFor(scenario) { it.hasWindowFocus() && !it.sharedSync.busy }
                    instrumentation.waitForIdleSync()
                    Thread.sleep(350) // Native window dismissal is not tracked by Espresso idleness.

                    onView(withText("管理")).perform(click())
                    onView(withText("WebDAV 共享课表（双向同步）")).perform(click())
                    onView(withHint("WebDAV 服务地址")).perform(scrollTo(), replaceText(account.baseUrl), closeSoftKeyboard())
                    onView(withHint("用户名")).perform(scrollTo(), replaceText("user"), closeSoftKeyboard())
                    onView(withHint("密码（留空保留已保存密码）")).perform(scrollTo(), replaceText("secret"), closeSoftKeyboard())
                    onView(withText("浏览共享目录")).perform(scrollTo(), click())
                    val folderDeadline = System.currentTimeMillis() + 10000
                    while (true) { try { onView(withText("▸ share")).perform(click()); break }
                        catch (e: androidx.test.espresso.NoMatchingViewException) { if (System.currentTimeMillis() > folderDeadline) throw e; Thread.sleep(40) } }
                    Thread.sleep(300); instrumentation.waitForIdleSync()
                    onView(withText("选择此文件夹")).perform(click())
                    onView(withHint("共享目录完整 URL")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(withText(publisherSettings.folder)))
                    onView(withText("测试连接 / 检查远端快照")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(scrollTo(), click())
                    // Transport finishes asynchronously; wait for its confirmation dialog without accepting it early.
                    val deadline = System.currentTimeMillis() + 20000
                    while (System.currentTimeMillis() < deadline) {
            dismissTestPasswordPrompt()
                        try { onView(withText("确认下载并绑定")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed())); break } catch (_: AssertionError) { Thread.sleep(40) }
                        catch (_: androidx.test.espresso.NoMatchingViewException) { Thread.sleep(40) }
                    }
                    scenario.onActivity { assertFalse(it.source.readOnly); assertTrue(it.source.state.entries.isEmpty()) }
                    onView(withText("确认下载并绑定")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
                    waitFor(scenario) { it.source.state.sync != null && !it.source.applyingSnapshot && !it.sharedSync.busy }
                    onView(withText("浏览共享目录")).perform(scrollTo(), click())
                    onView(withText("已绑定，请使用安全换库入口")).check(matches(isDisplayed()))
                    onView(withText("更换共享课表数据集 / 恢复换库")).perform(scrollTo(), click())
                    val switchDeadline = System.currentTimeMillis() + 10000
                    while (true) { try { onView(withText("浏览新共享目录")).perform(click()); break }
                        catch (e: androidx.test.espresso.NoMatchingViewException) { if (System.currentTimeMillis() > switchDeadline) throw e; Thread.sleep(40) } }
                    val navigationDeadline = System.currentTimeMillis() + 10000
                    while (true) { try { onView(withText("▸ share")).perform(click()); break }
                        catch (e: androidx.test.espresso.NoMatchingViewException) { if (System.currentTimeMillis() > navigationDeadline) throw e; Thread.sleep(40) } }
                    Thread.sleep(300); instrumentation.waitForIdleSync()
                    onView(withText("选择此文件夹")).perform(click())
                    onView(withHint("新共享目录完整 URL（使用当前 WebDAV 账号）")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(withText(publisherSettings.folder)))
                    onView(withHint("FPK 显示的目标 datasetId")).check(matches(withText("")))
                    onView(withText("取消")).perform(click())
                    onView(withText("关闭")).perform(click())
                    waitFor(scenario) { it.hasWindowFocus() && !it.sharedSync.busy }
                    onView(withText("＋ 课程")).check(matches(isDisplayed()))
                    onView(withText("日期调整")).check(matches(isDisplayed()))
                    scenario.onActivity {
                        assertEquals("stable-source", it.source.state.sources.single().id)
                        assertEquals("stable-instance", it.source.state.imported.single().id)
                        assertEquals(3, it.source.state.classOverrides.size)
                        assertEquals(seed.state.profiles, it.source.state.profiles)
                        val entry = it.source.state.entries.single()
                        assertTrue(it.source.saveEntry(entry.title, entry.day, entry.startMinute, entry.endMinute, entry.location, entry.id))
                    }
                    val secrets = context.getSharedPreferences("${followerNamespace}_credentials", 0).getString("payload", null)!!
                    assertFalse(secrets.contains("secret")); assertEquals("secret", WebDavSettings(context).account()!!.password)
                    val firstId = writerStore.v2Frame().baseline?.commitId
                    // Recreate a publisher that died after remote commit but before saving its receipt.
                    val remoteReceipt = V2TestTransport().inspect(account, publisherSettings.folder)
                    val capture = writerStore.v2Frame()
                    val candidate = ScheduleCommit(capture.baseline!!.datasetId, UUID.randomUUID().toString(), capture.writerId, capture.frontier, capture.local)
                    val candidateBytes = ScheduleCommitCodec.encode(candidate)
                    writerStore.v2Stage(candidateBytes)
                    WebDavCommitStore(account, server.url("/share/dormpanel-sync-v2/").toString()).publish(candidateBytes)
                    instrumentation.runOnMainSync {
                        controller!!.close()
                        controller = SharedScheduleController(context, writer!!, publisherAccount, publisherSettings)
                    }
                    // Recreating the controller starts an asynchronous receipt recovery.
                    // Submit the edit only outside its brief transactional UI write gate.
                    waitFor(scenario) {
                        if (controller!!.busy || writer!!.applyingSnapshot) false
                        else {
                            assertTrue(writer!!.saveEntry("New revision", 3, 700, 745, id = "local"))
                            true
                        }
                    }
                    writerProfiles.put(writerProfiles.get("custom-term")!!.copy(timezone = "Asia/Tokyo"))
                    instrumentation.runOnMainSync { controller!!.publish() }
                    waitFor(scenario) { !controller!!.busy }
                    assertNotEquals(firstId, writerStore.v2Frame().baseline?.commitId)
                    assertNull(writerStore.v2Frame().pending)
                    onView(withText("管理")).perform(click())
                    onView(withText("WebDAV 共享课表（双向同步）")).perform(click())
                    onView(withText("立即同步")).perform(scrollTo(), click())
                    waitFor(scenario) { it.source.state.entries.singleOrNull()?.title == "New revision" && !it.source.applyingSnapshot }
                    scenario.onActivity { assertEquals("Asia/Tokyo", it.source.state.profiles["custom-term"]!!.timezone) }
                    onView(withText("关闭")).perform(click())
                    waitFor(scenario) { it.hasWindowFocus() && !it.sharedSync.busy }
                    // Bad remote bytes must leave the successful revision intact.
                    dav.files["/share/dormpanel-sync-v2/commit-${writerStore.v2Frame().baseline?.commitId}.dpc"] = byteArrayOf(1, 2)
                    onView(withText("管理")).perform(click())
                    onView(withText("WebDAV 共享课表（双向同步）")).perform(click())
                    onView(withText("立即同步")).perform(scrollTo(), click())
                    waitFor(scenario) { !it.sharedSync.busy }
                    scenario.onActivity { assertEquals("New revision", it.source.state.entries.single().title); assertTrue(it.sharedSync.status.contains("同步失败")) }
                    onView(withText("关闭")).perform(click())
                    waitFor(scenario) { it.hasWindowFocus() && !it.sharedSync.busy }
                    dav.offline = true
                }
                launchPhoneTestActivity().use { scenario ->
                    waitFor(scenario) { it.source.ready && it.source.state.sync != null }
                    waitFor(scenario) { !it.sharedSync.busy }
                    scenario.onActivity {
                        assertEquals("New revision", it.source.state.entries.single().title)
                        assertEquals("Asia/Tokyo", it.source.state.profiles["custom-term"]!!.timezone)
                        assertTrue(it.sharedSync.status.contains("离线"))
                    }
                    onView(withText("＋ 课程")).check(matches(isDisplayed()))
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
