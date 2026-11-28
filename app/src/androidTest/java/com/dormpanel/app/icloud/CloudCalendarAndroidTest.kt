package com.dormpanel.app.icloud

import android.os.SystemClock
import android.widget.Button
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.IsolatedDashboardRule
import com.dormpanel.app.MainActivity
import com.dormpanel.app.R
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.schedule.PreferencesScheduleModeStore
import com.dormpanel.app.schedule.ScheduleMode
import com.dormpanel.app.schedule.scheduleText
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.hamcrest.Matchers.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CloudCalendarAndroidTest {
    @get:Rule val persistence = IsolatedDashboardRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val day = LocalDate.now()
    private val zone = ZoneId.systemDefault()

    @Test fun twoCalendarsMergeLocallyAndCardOverrideAndOfflineCacheSurviveRelaunch() {
        val mode = PreferencesScheduleModeStore(context)
        val priorMode = mode.read()
        val namespace = "test-caldav-${UUID.randomUUID()}"
        val store = CloudCalendarStore(context, namespace)
        val fixture = Fixture(day, zone)
        mode.write(ScheduleMode.CALENDAR)
        CloudCalendarService.overrideFactory = { context ->
            CloudCalendarService(context, fixture::open, { fixture.online }, CloudCalendarStore(context, namespace))
        }
        try {
            var cardId = ""
            var dayLabel = ""
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(scenario) { it.schedule.ready && it.schedule.editable && it.stateHolder.state.loaded && !it.cloudCalendar.isBusy }
                scenario.onActivity {
                    val model = model(it)
                    model.stateHolder.state.cards.toList().forEach { card -> model.stateHolder.delete(card.id) }
                    model.stateHolder.add(model.catalog.candidates.first { card -> card.providerType == "calendar" })
                    cardId = model.stateHolder.state.cards.single().id
                    model.stateHolder.resize(cardId, CardSize(3, 2))
                    val start = day.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
                    assertTrue(model.schedule.saveEvent("Local fixture", start, start + 3_600_000))
                }

                openCalendar()
                onView(allOf(withText("iCloud 日历"), isAssignableFrom(Button::class.java))).perform(click())
                onView(withText("连接 iCloud")).perform(click())
                onView(withHint("Apple Account 邮箱")).perform(replaceText(FIXTURE_EMAIL))
                onView(withHint("专用 App 密码")).perform(replaceText(FIXTURE_PASSWORD), closeSoftKeyboard())
                onView(withText("连接")).perform(click())
                await(scenario) { model ->
                    val service = model.cloudCalendar
                    service.calendars.size == 2 && service.eventsOn(day, zone).count { it.event.title == "Shared fixture" } == 2
                }
                onView(withText("关闭")).perform(click())
                dayLabel = context.scheduleText("全天", "All day")
                onView(withText(allOf(containsString(dayLabel), containsString("Main"), containsString("Shared fixture")))).check(matches(isDisplayed()))
                onView(withText(allOf(containsString(dayLabel), containsString("Family"), containsString("Shared fixture")))).check(matches(isDisplayed()))
                onView(withText(containsString("Cross-night fixture"))).check(matches(isDisplayed()))
                onView(withText(containsString("Local fixture"))).check(matches(isDisplayed()))
                onView(withContentDescription("Select $day")).check(matches(withText(containsString("•"))))
                screenshot("calendar")

                onView(withText(allOf(containsString("Family"), containsString("Shared fixture")))).perform(click())
                onView(withText("iCloud · Family · 账户只读")).check(matches(isDisplayed()))
                onView(withText("Remote description")).check(matches(isDisplayed()))
                onView(withHint("Event title")).check(doesNotExist())
                onView(withText("关闭")).perform(click())

                onView(allOf(withText("iCloud 日历"), isAssignableFrom(Button::class.java))).perform(click())
                onView(withText("显示的日历")).perform(click())
                onView(withText("Family · 账户只读")).perform(click())
                onView(withText("保存")).perform(click())
                await(scenario) { it.cloudCalendar.selected.size == 1 && !it.cloudCalendar.isBusy }
                onView(withText("自动刷新 · 每 30 分钟")).perform(click())
                onView(withText("15 分钟")).perform(click())
                await(scenario) { it.cloudCalendar.refreshMinutes == 15 && !it.cloudCalendar.isBusy }
                onView(withText("关闭")).perform(click())
                onView(withText(allOf(containsString("Family"), containsString("Shared fixture")))).check(doesNotExist())
                onView(withText(context.scheduleText("返回首页", "Home"))).perform(click())
                await(scenario) { it.cloudCalendar.eventsOn(day, zone).any { event -> event.calendar.name == "Main" } }
                onView(withText(allOf(containsString("Main"), containsString("Shared fixture")))).check(matches(isDisplayed()))
                onView(withText(allOf(containsString("Family"), containsString("Shared fixture")))).check(doesNotExist())

                onView(withId(R.id.dashboard_edit)).perform(click())
                onView(withContentDescription("配置卡片显示的日历")).perform(click())
                onView(withText("为此卡片选择日历")).perform(click())
                onView(withText("Main · 账户可写")).check(matches(isChecked()))
                screenshot("calendar-picker")
                onView(withText("Main · 账户可写")).perform(click())
                onView(withText("Family · 账户只读")).check(matches(isNotChecked())).perform(click())
                onView(withText("保存")).perform(click())
                await(scenario) { model ->
                    val expected = model.cloudCalendar.calendars.single { it.name == "Family" }.id
                    CardCalendars.decode(model.stateHolder.state.cards.single().configurationJson).selected == setOf(expected) &&
                        model.stateHolder.state.configurationSaves.isEmpty()
                }
                onView(withId(R.id.dashboard_done)).perform(click())
                await(scenario) { it.cloudCalendar.eventsOn(day, zone, cardSelection(it)).isNotEmpty() }
                onView(withText(allOf(containsString("Shared fixture"), containsString("Family")))).check(matches(isDisplayed()))
                onView(withText(allOf(containsString("Shared fixture"), containsString("Main")))).check(doesNotExist())
                screenshot("card")
                scenario.onActivity { model(it).stateHolder.resize(cardId, CardSize(2, 1)) }
                onView(withText(allOf(containsString("Shared fixture"), containsString("Family")))).check(matches(isDisplayed()))
                screenshot("compact-card")
                scenario.onActivity { model(it).stateHolder.resize(cardId, CardSize(3, 2)) }

                fixture.code = 401
                scenario.onActivity { model(it).cloudCalendar.refresh() }
                await(scenario) { it.cloudCalendar.connectionLabel().contains("认证失败") }
                onView(withText(allOf(containsString("Shared fixture"), containsString("Family")))).check(matches(isDisplayed()))
                // The service publishes before the card paints on the next animation frame.
                val redrawDeadline = SystemClock.uptimeMillis() + 2_000
                while (true) {
                    try {
                        onView(withText(containsString("缓存可能过期"))).check(matches(isDisplayed()))
                        break
                    } catch (e: androidx.test.espresso.NoMatchingViewException) {
                        if (SystemClock.uptimeMillis() >= redrawDeadline) throw e
                        Thread.sleep(25)
                    }
                }
                fixture.online = false
                scenario.recreate()
                await(scenario) { it.stateHolder.state.loaded && it.cloudCalendar.eventsOn(day, zone, cardSelection(it)).isNotEmpty() }
                onView(withText(allOf(containsString("Shared fixture"), containsString("Family")))).check(matches(isDisplayed()))
            }
            val restored = store.settings()
            assertEquals(15, restored.refreshMinutes)
            assertEquals(FIXTURE_EMAIL to FIXTURE_PASSWORD, store.login(restored))
            val raw = File(File(context.noBackupFilesDir, namespace), "account.json").readText()
            assertFalse(raw.contains(FIXTURE_PASSWORD))
            assertFalse(raw.contains(FIXTURE_EMAIL))

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(scenario) { it.stateHolder.state.loaded && it.cloudCalendar.eventsOn(day, zone, cardSelection(it)).isNotEmpty() }
                scenario.onActivity { assertEquals(cardId, model(it).stateHolder.state.cards.single().id) }
                onView(withText(allOf(containsString("Shared fixture"), containsString("Family")))).check(matches(isDisplayed()))
                openCalendar()
                await(scenario) { it.cloudCalendar.eventsOn(day, zone).any { event -> event.calendar.name == "Main" } }
                onView(withText(containsString("离线 · 缓存可能过期"))).check(matches(isDisplayed()))
                onView(withText(allOf(containsString(dayLabel), containsString("Main"), containsString("Shared fixture")))).check(matches(isDisplayed()))
                onView(withText(allOf(containsString("Family"), containsString("Shared fixture")))).check(doesNotExist())
                onView(withText(containsString("Local fixture"))).check(matches(isDisplayed()))
                onView(withText(context.scheduleText("+ 添加日程", "+ Add event"))).perform(click())
                onView(withHint("Event title")).perform(replaceText("Offline local CRUD"), closeSoftKeyboard())
                onView(withText(context.scheduleText("保存", "Save"))).perform(click())
                onView(withText(containsString("Offline local CRUD"))).perform(click())
                onView(withHint("Event title")).perform(replaceText("Offline local edited"), closeSoftKeyboard())
                onView(withText(context.scheduleText("保存", "Save"))).perform(click())
                onView(withText(containsString("Offline local edited"))).perform(click())
                onView(withText(context.scheduleText("删除", "Delete"))).perform(click())
                onView(withText(containsString("Offline local edited"))).check(doesNotExist())
                scenario.onActivity {
                    val model = model(it)
                    assertEquals(listOf("Local fixture"), model.schedule.state.events.map { event -> event.title })
                    assertTrue(model.schedule.state.entries.isEmpty())
                    assertTrue(model.schedule.state.sources.isEmpty())
                }
                screenshot("offline")
            }
            // An explicit empty override survives a new ViewModel, unlike the default inheritance mode.
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(scenario) { it.stateHolder.state.loaded && it.cloudCalendar.calendars.size == 2 }
                onView(withId(R.id.dashboard_edit)).perform(click())
                onView(withContentDescription("配置卡片显示的日历")).perform(click())
                onView(withText("Family · 账户只读")).check(matches(isChecked())).perform(click())
                onView(withText("保存")).perform(click())
                await(scenario) { cardSelection(it) == emptySet<String>() && it.stateHolder.state.configurationSaves.isEmpty() }
                scenario.recreate()
                await(scenario) { cardSelection(it) == emptySet<String>() }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(scenario) { it.stateHolder.state.loaded && cardSelection(it) == emptySet<String>() }
                onView(withId(R.id.dashboard_edit)).perform(click())
                onView(withContentDescription("配置卡片显示的日历")).perform(click())
                onView(withText("Main · 账户可写")).check(matches(isNotChecked()))
                onView(withText("继承全局日历选择")).perform(click())
                onView(withText("保存")).perform(click())
                await(scenario) { cardSelection(it) == null && it.stateHolder.state.configurationSaves.isEmpty() }
            }
            store.clearCache(restored.account)
            assertEquals(restored, store.settings())
            assertNull(store.snapshot(restored.account, CalendarCacheKey(restored.calendars.first().id, CalendarWindow(java.time.YearMonth.from(day), zone))))
            store.disconnect()
            assertFalse(store.settings().connected)
            assertNull(store.login(restored))
        } finally {
            CloudCalendarService.overrideFactory = null
            fixture.close()
            store.disconnect()
            File(context.noBackupFilesDir, namespace).resolve("account.json").delete()
            File(context.noBackupFilesDir, namespace).delete()
            mode.write(priorMode)
        }
    }

    @Test fun stoppingActivityCancelsLongReportAndResumeRecoversInTheSameViewModel() {
        val namespace = "test-caldav-cancel-${UUID.randomUUID()}"
        val store = CloudCalendarStore(context, namespace)
        val fixture = Fixture(day, zone)
        val reportStarted = java.util.concurrent.CountDownLatch(1)
        fixture.reportStarted = reportStarted
        fixture.stallReport = true
        CloudCalendarService.overrideFactory = {
            CloudCalendarService(it, fixture::open, { fixture.online }, store)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(scenario) { !it.cloudCalendar.isBusy }
                scenario.onActivity { model(it).cloudCalendar.connect(FIXTURE_EMAIL, FIXTURE_PASSWORD) }
                assertTrue(reportStarted.await(12, java.util.concurrent.TimeUnit.SECONDS))
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                fixture.stallReport = false
                val resumed = SystemClock.uptimeMillis()
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                await(scenario) { it.cloudCalendar.eventsOn(day, zone).isNotEmpty() }
                assertTrue("resume waited for the cancelled REPORT", SystemClock.uptimeMillis() - resumed < 10_000)
                fixture.code = 429
                scenario.onActivity { model(it).cloudCalendar.refresh() }
                await(scenario) { it.cloudCalendar.connectionLabel().contains("服务器限流") }
                val afterLimit = fixture.requestCount.get()
                scenario.onActivity { activity -> repeat(3) { model(activity).cloudCalendar.refresh() } }
                Thread.sleep(1200)
                assertEquals(afterLimit, fixture.requestCount.get())
            }
        } finally {
            CloudCalendarService.overrideFactory = null
            fixture.close()
            store.disconnect()
            File(context.noBackupFilesDir, namespace).resolve("account.json").delete()
            File(context.noBackupFilesDir, namespace).delete()
        }
    }

    @Test fun editingWritesSurviveOfflineRelaunchAndConcurrentServerChanges() {
        val mode = PreferencesScheduleModeStore(context)
        val priorMode = mode.read()
        val namespace = "test-caldav-write-${UUID.randomUUID()}"
        val store = CloudCalendarStore(context, namespace)
        val fixture = Fixture(day, zone)
        mode.write(ScheduleMode.CALENDAR)
        CloudCalendarService.overrideFactory = { CloudCalendarService(it, fixture::open, { fixture.online }, store) }
        val title = "Queued create fixture"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(scenario) { it.schedule.ready && !it.cloudCalendar.isBusy }
                scenario.onActivity { model(it).cloudCalendar.connect(FIXTURE_EMAIL, FIXTURE_PASSWORD) }
                await(scenario) { it.cloudCalendar.calendars.size == 2 && !it.cloudCalendar.isBusy }
                scenario.onActivity {
                    val service = model(it).cloudCalendar
                    service.setSelection(service.calendars.filter { cal -> cal.name == "Family" }.map { cal -> cal.id }.toSet())
                }
                await(scenario) { it.cloudCalendar.selected.size == 1 && !it.cloudCalendar.isBusy }
                scenario.onActivity {
                    val service = model(it).cloudCalendar
                    service.stop()
                    fixture.online = false
                    service.start()
                }
                openCalendar()
                onView(withText(context.scheduleText("+ 添加日程", "+ Add event"))).perform(click())
                onView(withText("本机日历")).check(matches(isDisplayed()))
                onView(withText("iCloud · Family")).check(doesNotExist())
                onView(withText("iCloud · Main")).perform(click())
                onView(withText("保存到 iCloud · Main")).check(matches(isDisplayed()))
                onView(withContentDescription(startsWith("开始时间 ·"))).perform(click())
                onView(isAssignableFrom(android.widget.TimePicker::class.java)).check(matches(isDisplayed()))
                androidx.test.espresso.Espresso.pressBack()
                onView(withText(startsWith("开始 ·"))).perform(click())
                onView(isAssignableFrom(android.widget.DatePicker::class.java)).check(matches(isDisplayed()))
                androidx.test.espresso.Espresso.pressBack()
                onView(withHint("日程标题")).perform(replaceText(title), closeSoftKeyboard())
                onView(withText("全天")).perform(click())
                onView(withText(startsWith("结束日（不包含）"))).check(matches(isDisplayed()))
                scenario.onActivity { model(it).appearance.setTheme(com.dormpanel.app.appearance.ThemeMode.DARK) }
                screenshot("write-editor-dark")
                scenario.onActivity { model(it).appearance.setTheme(com.dormpanel.app.appearance.ThemeMode.LIGHT) }
                onView(withText("保存到本机待写")).check(matches(isDisplayed()))
                screenshot("write-editor-light")
                scenario.onActivity { model(it).appearance.setTheme(com.dormpanel.app.appearance.ThemeMode.DARK) }
                onView(withText("保存到本机待写")).perform(click())
                await(scenario) { it.cloudCalendar.writes.singleOrNull()?.state == CalendarWriteState.QUEUED }
                onView(withText(containsString("等待写入 · 网络不可用"))).check(matches(isDisplayed()))
                val persisted = store.writes().single()
                scenario.onActivity { model(it).cloudCalendar.clearCache() }
                await(scenario) { !it.cloudCalendar.isBusy }
                assertEquals(persisted, store.writes().single())
                scenario.recreate()
                await(scenario) { it.cloudCalendar.writes.singleOrNull()?.id == persisted.id }
                assertEquals(0, fixture.mutations.get())
            }

            fixture.online = true
            fixture.dropNextWrite = true
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(scenario) { it.cloudCalendar.writes.singleOrNull()?.state == CalendarWriteState.CHECKING }
                assertEquals(1, fixture.mutations.get())
                assertTrue(store.writes().single().attempted)
                openCalendar()
                onView(withText(containsString("结果待确认"))).check(matches(isDisplayed()))
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(scenario) { it.cloudCalendar.writes.singleOrNull()?.state == CalendarWriteState.CONFIRMED }
                assertEquals(1, fixture.mutations.get())
                scenario.onActivity {
                    val service = model(it).cloudCalendar
                    service.setSelection(service.calendars.map { cal -> cal.id }.toSet())
                }
                await(scenario) { it.cloudCalendar.eventsOn(day, zone).any { event -> event.event.title == title } }
                openCalendar()
                onView(withText(allOf(containsString(title), containsString("Main")))).perform(click())
                awaitView("编辑 iCloud 日程")
                onView(withText("编辑 iCloud 日程")).perform(click())
                onView(withHint("日程标题")).perform(replaceText("Local edit fixture"), closeSoftKeyboard())
                fixture.changeTitle(title, "Concurrent remote fixture")
                onView(withText("保存到本机待写")).perform(click())
                await(scenario) { it.cloudCalendar.writes.any { write -> write.state == CalendarWriteState.CONFLICT } }
                assertEquals(1, fixture.mutations.get())
                onView(withText(startsWith("iCloud 写入记录 · 检测到远端冲突"))).perform(click())
                onView(withText(containsString("修改 · Main · Local edit fixture"))).perform(click())
                onView(withText("重新读取服务器版本")).perform(click())
                awaitView("用本机草稿重新编辑")
                screenshot("write-conflict")
                onView(withText("用本机草稿重新编辑")).perform(click())
                onView(withHint("日程标题")).check(matches(withText("Local edit fixture")))
                onView(withText("保存到本机待写")).perform(click())
                await(scenario) { it.cloudCalendar.eventsOn(day, zone).any { event -> event.event.title == "Local edit fixture" } }
                onView(withText("关闭")).perform(click())
                onView(withText("关闭")).perform(click())
                onView(withText(allOf(containsString("Local edit fixture"), containsString("Main")))).perform(click())
                awaitView("删除此 iCloud 日程")
                onView(withText("删除此 iCloud 日程")).perform(click())
                onView(withText("删除此日程")).perform(click())
                await(scenario) { it.cloudCalendar.writes.any { write ->
                    write.kind == CalendarWriteKind.DELETE && write.state == CalendarWriteState.CONFIRMED
                } && it.cloudCalendar.eventsOn(day, zone).none { event -> event.event.title == "Local edit fixture" } }
                assertEquals(3, fixture.mutations.get())
            }
        } finally {
            CloudCalendarService.overrideFactory = null
            fixture.close()
            store.disconnect()
            File(context.noBackupFilesDir, namespace).resolve("pending-writes.json").delete()
            File(context.noBackupFilesDir, namespace).resolve("account.json").delete()
            File(context.noBackupFilesDir, namespace).delete()
            mode.write(priorMode)
        }
    }

    private fun awaitView(label: String) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (true) {
            try {
                onView(withText(label)).check(matches(isDisplayed()))
                return
            } catch (e: androidx.test.espresso.NoMatchingViewException) {
                if (SystemClock.uptimeMillis() >= deadline) throw e
                Thread.sleep(40)
            }
        }
    }

    private fun model(activity: MainActivity) = ViewModelProvider(activity)[DashboardViewModel::class.java]
    private fun cardSelection(model: DashboardViewModel) =
        model.stateHolder.state.cards.firstOrNull()?.let { CardCalendars.decode(it.configurationJson).selected }

    private fun await(scenario: ActivityScenario<MainActivity>, check: (DashboardViewModel) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        do {
            var ready = false
            scenario.onActivity { ready = check(model(it)) }
            if (ready) return
            Thread.sleep(35)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Calendar did not reach the expected state")
    }

    private fun openCalendar() {
        onView(withId(R.id.page_container)).perform(swipeLeft())
        onView(withText(context.scheduleText("+ 添加日程", "+ Add event"))).check(matches(isDisplayed()))
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null), "pr101-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private class Fixture(private val day: LocalDate, private val zone: ZoneId) : AutoCloseable {
        private val server = MockWebServer()
        private val transport: OkHttpClient
        @Volatile var online = true
        @Volatile var code = 0
        @Volatile var reportStarted: java.util.concurrent.CountDownLatch? = null
        @Volatile var stallReport = false
        val requestCount = java.util.concurrent.atomic.AtomicInteger()
        val mutations = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var dropNextWrite = false
        private val resources = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
        private val fmt = DateTimeFormatter.BASIC_ISO_DATE
        private val utc = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
        private val home = "/fixture/"
        private val collection = "<D:resourcetype><D:collection/></D:resourcetype>"
        private val calendar = "<D:resourcetype><D:collection/><C:calendar/></D:resourcetype>"
        private val access = "<D:current-user-privilege-set><D:privilege><D:read/></D:privilege>"
        private val writable = "$access<D:privilege><D:write/></D:privilege></D:current-user-privilege-set>"

        init {
            val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("127.0.0.1").build()
            val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
            val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
            transport = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
            server.useHttps(serverTls.sslSocketFactory(), false)
            val shared = event("UID:shared\nDTSTART;VALUE=DATE:${fmt.format(day)}\n" +
                "DTEND;VALUE=DATE:${fmt.format(day.plusDays(2))}\nSUMMARY:Shared fixture\nDESCRIPTION:Remote description")
            resources["${home}main/shared.ics"] = "\"one\"" to shared
            resources["${home}family/shared.ics"] = "\"one\"" to shared
            val start = utc.format(day.atTime(23, 0).atZone(zone).toInstant())
            val end = utc.format(day.plusDays(1).atTime(2, 0).atZone(zone).toInstant())
            resources["${home}main/night.ics"] = "\"one\"" to event("UID:night\nDTSTART:$start\nDTEND:$end\nSUMMARY:Cross-night fixture")
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestCount.incrementAndGet()
                    if (!online) return MockResponse().setResponseCode(503)
                    if (code != 0) return MockResponse().setResponseCode(code).setHeader("Retry-After", "60")
                    val path = request.path.orEmpty()
                    val xml = request.body.readUtf8()
                    return when (path) {
                        "/.well-known/caldav" -> dav(item(path, "<C:calendar-home-set><D:href>$home</D:href></C:calendar-home-set>"))
                        home -> dav(item(home, collection) +
                            item("${home}main/", calendar + "<D:displayname>Main</D:displayname><A:calendar-color>#1976D2</A:calendar-color>" +
                                writable) +
                            item("${home}family/", calendar + "<D:displayname>Family</D:displayname><A:calendar-color>#FF9800</A:calendar-color>" +
                                "$access</D:current-user-privilege-set>"))
                        "${home}main/", "${home}family/" -> if (request.method == "PROPFIND") {
                            if ("resourcetype" in xml) dav(item(path, calendar +
                                if (path.endsWith("/main/")) writable else "$access</D:current-user-privilege-set>"))
                            else dav(item(path, "<D:sync-token/>", 404))
                        } else {
                            val payload = resources.filter { (file, _) ->
                                file.startsWith(path) && ("calendar-query" in xml || "$file</D:href>" in xml)
                            }.entries.joinToString("") { (file, data) ->
                                    item(file, "<D:getetag>${data.first}</D:getetag>" +
                                        if ("calendar-multiget" in xml) "<C:calendar-data><![CDATA[${data.second}]]></C:calendar-data>" else "")
                                }
                            reportStarted?.countDown()
                            if (stallReport) MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                            else dav(payload)
                        }
                        else -> if (path.startsWith(home) && path.endsWith(".ics")) objectResponse(request, path, xml)
                        else MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
        }

        fun open(email: String, password: String): CalDavClient = CalDavClient(email, password,
            server.url("/").newBuilder().host("127.0.0.1").build(), { it == "127.0.0.1" }, transport)

        fun changeTitle(old: String, next: String) {
            val (path, value) = resources.entries.single { "SUMMARY:$old" in it.value.second }
            resources[path] = "\"external\"" to value.second.replace("SUMMARY:$old", "SUMMARY:$next")
        }

        private fun objectResponse(request: RecordedRequest, path: String, body: String): MockResponse {
            val existing = resources[path]
            fun response(code: Int) = MockResponse().setResponseCode(code)
            return when (request.method) {
                "GET" -> if (existing == null) response(404) else response(200).setHeader("ETag", existing.first).setBody(existing.second)
                "PROPFIND" -> if (existing == null) response(404) else dav(item(path,
                    if (path.startsWith("${home}main/")) writable else "$access</D:current-user-privilege-set>"))
                "PUT", "DELETE" -> {
                    if (!path.startsWith("${home}main/")) return response(403)
                    val creating = request.getHeader("If-None-Match") == "*"
                    if (creating && existing != null || !creating &&
                        (existing == null || existing.first != request.getHeader("If-Match"))) return response(412)
                    val version = mutations.incrementAndGet()
                    if (request.method == "DELETE") resources.remove(path) else resources[path] = "\"write-$version\"" to body
                    if (dropNextWrite) {
                        dropNextWrite = false
                        response(503).setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    } else response(if (creating) 201 else 204)
                }
                else -> response(405)
            }
        }

        private fun item(path: String, props: String, status: Int = 200) = "<D:response><D:href>$path</D:href>" +
            "<D:propstat><D:prop>$props</D:prop><D:status>HTTP/1.1 $status Fixture</D:status></D:propstat></D:response>"
        private fun dav(body: String) = MockResponse().setResponseCode(207).setBody(
            "<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\" xmlns:A=\"http://apple.com/ns/ical/\">$body</D:multistatus>",
        )
        private fun event(body: String) = ("BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//DormPanel//Fixture//EN\nBEGIN:VEVENT\n" +
            "$body\nEND:VEVENT\nEND:VCALENDAR\n").replace("\n", "\r\n")

        override fun close() { server.shutdown() }
    }

    private companion object {
        const val FIXTURE_EMAIL = "fixture@example.invalid"
        const val FIXTURE_PASSWORD = "fixture-app-password"
    }
}
