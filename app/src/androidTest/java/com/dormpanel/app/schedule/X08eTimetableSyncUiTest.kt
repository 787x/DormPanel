package com.dormpanel.app.schedule

import android.content.res.Configuration
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.IsolatedDashboardRule
import com.dormpanel.app.MainActivity
import com.dormpanel.app.R
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.appearance.ThemeMode
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.schedule.testing.V3UiFixture
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class X08eTimetableSyncUiTest {
    @get:Rule val persistence = IsolatedDashboardRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var page: SchedulePageView
    private lateinit var vm: DashboardViewModel

    private fun button() = requireNotNull(page.findViewWithTag<Button>("timetable-sync"))
    private fun waitFor(scenario: ActivityScenario<MainActivity>, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        do {
            var ready = false
            scenario.onActivity { ready = condition() }
            if (ready) return
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        scenario.onActivity {
            error("同步按钮状态未就绪 · ${button().text} · ${vm.sharedSchedule.presentation} · ${vm.sharedSchedule.status}")
        }
    }
    private fun install(activity: MainActivity, fontScale: Float = 1f, locale: Locale = Locale.SIMPLIFIED_CHINESE) {
        vm = ViewModelProvider(activity)[DashboardViewModel::class.java]
        val config = Configuration(activity.resources.configuration).apply { setLocale(locale); this.fontScale = fontScale }
        val localized = ContextThemeWrapper(activity, R.style.Theme_DormPanel).apply { applyOverrideConfiguration(config) }
        val session = ScheduleSession(vm.schedule.clock).apply { mode = ScheduleMode.TIMETABLE }
        page = SchedulePageView(localized, vm.schedule, session, vm.appearance, {}, sharedSchedule = vm.sharedSchedule)
        activity.setContentView(page)
    }

    @Test fun v3ShortcutTracksThisRunAcrossLayoutAndThemeChanges() {
        val preferences = X08eSchedulePreferences(context)
        val saved = preferences.read()
        val failCommitReceipt = AtomicBoolean(false)
        try {
            V3UiFixture(context, v3Fault = { phase ->
                if (phase == "after-commit" && failCommitReceipt.compareAndSet(true, false)) error("隔离测试：提交回执中断")
            }).use { fixture ->
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { install(it) }
                    waitFor(scenario) { vm.sharedSchedule.presentation.mode == SharedMode.UNBOUND && !vm.sharedSchedule.busy }
                    scenario.onActivity {
                        assertFalse(button().isEnabled)
                        button().performClick()
                        assertFalse(vm.sharedSchedule.syncing)
                    }
                    val store = requireNotNull(vm.schedule.syncStore)
                    store.prepareV3(fixture.account.baseUrl)
                    // Initialize the isolated fixture until its bounded discovery reaches an active binding.
                    val setupResults = mutableListOf<String>()
                    repeat(8) {
                        if (runCatching { store.v3EndpointFolder() }.isFailure) {
                            setupResults += store.synchronizeV3(fixture.account)
                        }
                    }
                    check(runCatching { store.v3EndpointFolder() }.isSuccess) { "隔离 v3 绑定未就绪 · $setupResults" }
                    scenario.onActivity { vm.schedule.reload() }
                    waitFor(scenario) { button().isEnabled }

                    for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
                        for (layout in X08eScheduleLayout.entries) {
                            preferences.write(saved.copy(layout = layout))
                            scenario.onActivity { install(it, fontScale = 1.3f, locale = locale) }
                            waitFor(scenario) { button().width > 0 && button().isEnabled }
                            scenario.onActivity {
                                val sync = button()
                                val row = sync.parent as LinearLayout
                                val management = row.getChildAt(row.indexOfChild(sync) - 1) as Button
                                val weekRange = page.findViewWithTag<TextView>("timetable-week-range")
                                val width = weekRange.width - weekRange.paddingLeft - weekRange.paddingRight
                                val textWidth = weekRange.paint.measureText(weekRange.text.toString())
                                assertTrue("$locale $layout 日期范围宽度 $width < $textWidth", width >= textWidth)
                                val expected = if (locale.language == "zh") "课表管理"
                                    else if (layout == X08eScheduleLayout.CLASSIC) "Sources" else "Manage"
                                assertEquals(expected, management.text.toString())
                                assertEquals(management.right, sync.left)
                                assertTrue(sync.height >= page.context.dp(48))
                                assertTrue(row.getChildAt(row.childCount - 1).right <= row.width)
                                assertSame(row, weekRange.parent)
                                assertTrue(page.findViewWithTag<android.view.View>("timetable-swipe").height > page.height / 2)
                            }
                        }
                    }
                    scenario.onActivity { install(it) }
                    val checking = CountDownLatch(1)
                    val resume = CountDownLatch(1)
                    fixture.interceptRequest = {
                        checking.countDown()
                        check(resume.await(15, TimeUnit.SECONDS))
                        null
                    }
                    try {
                        scenario.onActivity {
                            assertTrue(button().performClick())
                            assertEquals("同步中", button().text.toString())
                            assertFalse(button().isEnabled)
                            button().performClick()
                            val range = page.findViewWithTag<TextView>("timetable-week-range")
                            val row = range.parent as LinearLayout
                            row.getChildAt(row.indexOfChild(range) - 1).performClick()
                            assertEquals("同步中", button().text.toString())
                            page.applyAppearance(vm.appearance.state.copy(themeMode = ThemeMode.DARK))
                            assertEquals("同步中", button().text.toString())
                            assertFalse(vm.sharedSchedule.sync())
                        }
                        if (!checking.await(10, TimeUnit.SECONDS)) scenario.onActivity {
                            error("同步未发起测试请求 · ${button().text} · ${vm.sharedSchedule.status}")
                        }
                    } finally {
                        resume.countDown()
                        fixture.interceptRequest = null
                    }
                    waitFor(scenario) { button().text == "成功" }
                    scenario.onActivity {
                        page.applyAppearance(vm.appearance.state.copy(themeMode = ThemeMode.DARK))
                        assertEquals(0xFF81E8A0.toInt(), button().currentTextColor)
                        page.applyAppearance(vm.appearance.state.copy(themeMode = ThemeMode.LIGHT))
                        assertEquals(0xFF146C35.toInt(), button().currentTextColor)
                    }
                    val successAt = SystemClock.elapsedRealtime()
                    waitFor(scenario) { button().text == "同步" }
                    assertTrue(SystemClock.elapsedRealtime() - successAt in 2_400..4_000)
                    scenario.onActivity {
                        page.applyAppearance(vm.appearance.state.copy(themeMode = ThemeMode.LIGHT))
                        assertEquals(PanelPalette.forMode(ThemeMode.LIGHT).text, button().currentTextColor)
                        button().performClick()
                    }
                    waitFor(scenario) { button().text == "成功" }

                    fixture.interceptRequest = { MockResponse().setResponseCode(401) }
                    scenario.onActivity { button().performClick() }
                    waitFor(scenario) { button().text == "失败" && button().isEnabled }
                    Thread.sleep(3_100) // The prior success reset must leave the newer failure intact.
                    scenario.onActivity {
                        page.applyAppearance(vm.appearance.state.copy(themeMode = ThemeMode.DARK))
                        assertEquals("失败", button().text.toString())
                        assertEquals(0xFFFFA6A0.toInt(), button().currentTextColor)
                        install(it)
                        assertEquals("同步", button().text.toString())
                    }
                    fixture.interceptRequest = null
                    scenario.onActivity { SharedScheduleDialog.show(it, vm.schedule, vm.sharedSchedule, true) }
                    val before = fixture.requests.size
                    onView(withText("立即同步")).perform(scrollTo(), click())
                    waitFor(scenario) { fixture.requests.size > before && !vm.sharedSchedule.busy }
                    scenario.onActivity { assertEquals("同步", button().text.toString()) }
                    onView(withText("关闭")).perform(click())

                    failCommitReceipt.set(true)
                    val saveFailed = CountDownLatch(1)
                    store.put(TimetableEntry("pr85-recovery", "隔离恢复测试", 1, 480, 530)) {
                        if (it.isFailure) saveFailed.countDown()
                    }
                    assertTrue(saveFailed.await(10, TimeUnit.SECONDS))
                    val beforeRecovery = fixture.requests.size
                    scenario.onActivity { vm.sharedSchedule.refreshPresentation() }
                    waitFor(scenario) { vm.sharedSchedule.presentation.recovery }
                    scenario.onActivity {
                        assertFalse(button().isEnabled)
                        button().performClick()
                        assertFalse(vm.sharedSchedule.syncing)
                    }
                    assertEquals(beforeRecovery, fixture.requests.size)
                }
            }
        } finally {
            preferences.write(saved)
        }
    }

    @Test fun v2ReadFailureAndPartialCheckCannotReuseAnEarlierSuccess() {
        V3UiFixture(context, initialReadFailure = true).use { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { install(it) }
                waitFor(scenario) { vm.schedule.error }
                scenario.onActivity {
                    assertFalse(button().isEnabled)
                    button().performClick()
                    assertFalse(vm.sharedSchedule.syncing)
                }
                assertTrue(fixture.requests.isEmpty())
                fixture.repairInitialReadFailure()
                val folder = vm.sharedSchedule.settings.folder
                scenario.onActivity {
                    vm.sharedSchedule.settings.folder = ""
                    vm.sharedSchedule.connectionSaved()
                    assertTrue(vm.schedule.retryLocalRead())
                }
                waitFor(scenario) { vm.schedule.ready && vm.sharedSchedule.presentation.mode == SharedMode.V2 }
                scenario.onActivity {
                    assertFalse(button().isEnabled)
                    button().performClick()
                    assertFalse(vm.sharedSchedule.syncing)
                    vm.sharedSchedule.settings.folder = folder
                    vm.sharedSchedule.connectionSaved()
                }
                waitFor(scenario) { button().isEnabled }
                assertTrue(fixture.requests.isEmpty())
                scenario.onActivity { button().performClick() }
                waitFor(scenario) { button().text == "成功" }

                // A newly observed but unverifiable commit leaves an otherwise healthy v2 check partial.
                val unreadable = "/dormpanel-sync-v2/commit-${UUID.randomUUID()}.dpc"
                synchronized(fixture.files) { fixture.files[unreadable] = byteArrayOf(1) }
                scenario.onActivity { button().performClick() }
                waitFor(scenario) { button().text == "失败" && vm.sharedSchedule.presentation.result == "PARTIAL" }
                scenario.onActivity {
                    assertTrue(button().isEnabled)
                    assertTrue(vm.sharedSchedule.presentation.lastSuccess > 0)
                }
            }
        }
    }
}
