package com.dormpanel.app.schedule

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.IsolatedDashboardRule
import com.dormpanel.app.MainActivity
import com.dormpanel.app.appearance.*
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.Locale

/** Synthetic data only, in the testbed package or emulator. Never binds a real WebDAV/workspace. */
class X08eScheduleDisplayTest {
    @get:Rule val isolation = IsolatedDashboardRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val week = LocalDate.of(2026, 10, 26)
    private val zone = ZoneId.of("Asia/Shanghai")
    private var now = week.atTime(9, 10).atZone(zone).toInstant()
    private val clock = object : ScheduleClock {
        override fun instant() = now
        override fun zone() = this@X08eScheduleDisplayTest.zone
        override fun locale() = Locale.SIMPLIFIED_CHINESE
    }
    private lateinit var saved: X08eScheduleStyle
    private lateinit var source: ScheduleSource
    private lateinit var page: SchedulePageView
    private lateinit var appearance: AppearanceController
    private lateinit var fixtureData: ScheduleState
    private val viewport = ScheduleViewport()
    private val profile = TermScheduleProfile("synthetic", "Asia/Shanghai", week, listOf(SchedulePhase(week,
        listOf(8 to 0, 8 to 55, 10 to 0, 10 to 55, 14 to 0, 14 to 55, 16 to 0, 16 to 55, 19 to 0, 19 to 55).mapIndexed { i, (h,m) ->
            PeriodTime(i + 1, LocalTime.of(h,m), LocalTime.of(h,m).plusMinutes(45))
        })))
    private fun instant(day: Int, hour: Int, minute: Int) = week.plusDays(day.toLong()).atTime(hour,minute).atZone(zone).toInstant().toEpochMilli()
    private fun fixture(): ScheduleState {
        val titles = listOf("高等数学与应用", "大学英语", "线性代数", "计算机程序设计", "大学物理实验", "思想道德与法治", "自主学习")
        val imported = (0..6).flatMap { day -> (0..2).map { slot ->
            val a = listOf(8 to 0, 10 to 0, 14 to 0)[slot]; val b = listOf(9 to 40, 11 to 40, 15 to 40)[slot]
            ImportedClassOccurrence("$day-$slot", "source", "course-${(day+slot)%7}", null, instant(day,a.first,a.second), titles[(day+slot)%7],
                instant(day,a.first,a.second), instant(day,b.first,b.second), zone.id,
                listOf("教学楼 A201", "综合楼 305", "实验中心 2-103")[slot], "教师：测试教师；隔离合成课表", "${slot*2+1}-${slot*2+2}")
        } }
        val owner = ImportSource("source", "隔离演示课表", "fixture.csv", "csv", null, null, "synthetic", 0, zone.id, imported.size,
            imported.minOf { it.start }, imported.maxOf { it.end }, profile.termKey)
        val extras = listOf(
            ImportedClassOccurrence("short", "source", "short", null, instant(2,12,5), "短课程（1分钟）", instant(2,12,5), instant(2,12,6), zone.id, "会议室", "", null),
            ImportedClassOccurrence("overlap", "source", "overlap", null, instant(3,10,30), "重叠课程", instant(3,10,30), instant(3,11,0), zone.id, "实验楼", "", null),
            ImportedClassOccurrence("overnight", "source", "overnight", null, instant(4,23,30), "跨午夜研讨", instant(4,23,30), instant(5,0,30), zone.id, "线上", "", null))
        return ScheduleState(sources = listOf(owner), imported = imported + extras, profiles = mapOf(profile.termKey to profile))
    }
    @Before fun setup() { saved = X08eSchedulePreferences(context).read() }
    @After fun restore() { X08eSchedulePreferences(context).write(saved); if (::source.isInitialized) instrumentation.runOnMainSync { source.close() } }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun install(activity: MainActivity, style: X08eScheduleStyle, theme: ThemeMode = ThemeMode.DARK, session: ScheduleSession? = null) {
        X08eSchedulePreferences(context).write(style)
        val config = Configuration(activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val localized = android.view.ContextThemeWrapper(activity, com.dormpanel.app.R.style.Theme_DormPanel).apply { applyOverrideConfiguration(config) }
        if (!::source.isInitialized) {
            fixtureData = fixture()
            source = ScheduleSource(object : ScheduleStore {
                override fun load(callback: (Result<ScheduleState>) -> Unit) = callback(Result.success(fixtureData))
                override fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit) = error("Unexpected write")
                override fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit) = error("Unexpected write")
                override fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit) = error("Unexpected write")
                override fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit) = error("Unexpected write")
                override fun close() = Unit
            }, clock) { fixtureData.profiles[it] }
            source.reload()
        }
        appearance = AppearanceController(object : AppearanceStore {
            override fun read() = AppearanceState(theme)
            override fun write(state: AppearanceState) = Unit
        })
        val selected = session ?: ScheduleSession(clock).apply { mode = ScheduleMode.TIMETABLE }
        page = SchedulePageView(localized, source, selected, appearance, {}, viewport = viewport)
        activity.setContentView(page)
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(450)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertEquals(1280, bitmap.width); assertEquals(800, bitmap.height)
        java.io.File(context.getExternalFilesDir(null), "pr63-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Test fun allCombinationsThemesAndComparableScreenshots() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (layout in X08eScheduleLayout.entries) for (colors in listOf(false,true)) for (hierarchy in listOf(false,true)) {
                val style = X08eScheduleStyle(layout, colors, hierarchy)
                scenario.onActivity { install(it, style) }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals(style, X08eSchedulePreferences(context).read())
                    assertEquals(fixture().imported, source.state.imported)
                    if (layout != X08eScheduleLayout.CLASSIC) {
                        val grid = views(page).filterIsInstance<X08eWeekGrid>().single()
                        assertEquals(layout == X08eScheduleLayout.PERIODS, grid.usesPeriods)
                        assertEquals(1, views(page).filterIsInstance<ScrollView>().size)
                        val before = grid.getChildAt(1)
                        grid.updateNow(); assertSame(before, grid.getChildAt(1))
                        assertEquals(1, views(grid).count { v -> v.contentDescription == "当前时间" && v.visibility == View.VISIBLE })
                        now = now.plusSeconds(60); grid.updateNow(); assertSame(before, grid.getChildAt(1))
                    } else assertEquals(7, views(page).filterIsInstance<ScrollView>().size)
                    appearance.setTheme(ThemeMode.LIGHT); page.applyAppearance(appearance.state)
                }
                if (colors && hierarchy && layout == X08eScheduleLayout.PERIODS) screenshot("periods-light")
                scenario.onActivity { appearance.setTheme(ThemeMode.DARK); page.applyAppearance(appearance.state) }
                if (!colors && !hierarchy && layout == X08eScheduleLayout.CLASSIC) screenshot("classic-mono")
                if (colors && (!hierarchy || layout != X08eScheduleLayout.CLASSIC)) screenshot("${layout.name.lowercase()}-${if (hierarchy) "hierarchy" else "classic-text"}")
                if (colors && hierarchy && layout == X08eScheduleLayout.CLASSIC) screenshot("classic-hierarchy")
            }
        }
    }
    @Test fun settingsPersistScrollWeekAndClockSurviveRefreshReentryAndTheme() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val session = ScheduleSession(clock).apply { mode = ScheduleMode.TIMETABLE }
            scenario.onActivity { install(it, X08eScheduleStyle(), session = session) }
            onView(withText("显示设置")).perform(click()); screenshot("settings")
            onView(withContentDescription("课表布局")).perform(object : androidx.test.espresso.ViewAction {
                override fun getConstraints() = isAssignableFrom(Spinner::class.java)
                override fun getDescription() = "Select periods"
                override fun perform(controller: androidx.test.espresso.UiController, view: View) { (view as Spinner).setSelection(2); controller.loopMainThreadUntilIdle() }
            })
            onView(withText("按课程配色")).perform(click()); onView(withText("优化信息排版")).perform(click()); onView(withText("保存")).perform(click())
            onView(isAssignableFrom(ScrollView::class.java)).perform(androidx.test.espresso.action.ViewActions.swipeUp())
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(X08eScheduleStyle(X08eScheduleLayout.PERIODS,true,true), X08eSchedulePreferences(context).read())
                val scroll = views(page).filterIsInstance<ScrollView>().single(); assertTrue(scroll.scrollY > 0); scroll.scrollTo(0, 200)
                val grid = views(page).filterIsInstance<X08eWeekGrid>().single()
                val card = views(grid).filterIsInstance<X08eCourseCard>().first()
                appearance.setTheme(ThemeMode.LIGHT); page.applyAppearance(appearance.state)
                assertSame(card, views(grid).filterIsInstance<X08eCourseCard>().first())
                source.refresh()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(200, views(page).filterIsInstance<ScrollView>().single().scrollY)
                assertEquals(week, session.weekStart)
                install(it, X08eSchedulePreferences(context).read(), session = session)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(200, views(page).filterIsInstance<ScrollView>().single().scrollY)
                val grid = views(page).filterIsInstance<X08eWeekGrid>().single()
                now = now.plusSeconds(7*86400); grid.updateNow()
                assertEquals(0, views(grid).count { v -> v.contentDescription == "当前时间" && v.visibility == View.VISIBLE })
                now = week.atTime(9,10).atZone(zone).toInstant(); grid.openDay(3)
            }
            onView(withText("当天课程")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withText("关闭")).perform(click())
        }
    }
    @Test fun defaultsAreClassicAndCorruptLayoutFallsBackWithoutLosingIndependentOptions() {
        val store = X08eSchedulePreferences(context,"pr63_preferences_test")
        val prefs = context.getSharedPreferences("pr63_preferences_test",Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit(); assertEquals(X08eScheduleStyle(),store.read())
            prefs.edit().putString("layout","unknown").putBoolean("colors",true).commit()
            assertEquals(X08eScheduleStyle(colors=true),store.read())
            assertTrue(store.write(X08eScheduleStyle(X08eScheduleLayout.TIME,false,true)))
            assertEquals(X08eScheduleStyle(X08eScheduleLayout.TIME,false,true),X08eSchedulePreferences(context,"pr63_preferences_test").read())
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun shortOverlapFallbackAndDateAdjustmentRemainAccessible() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { install(it, X08eScheduleStyle(X08eScheduleLayout.PERIODS,true,true)) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { views(page).filterIsInstance<X08eWeekGrid>().single().openDay(2) }
            onView(withText(org.hamcrest.Matchers.startsWith("短课程（1分钟）\n"))).perform(click())
            onView(withText("短课程（1分钟）")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withText(org.hamcrest.Matchers.containsString("12:05–12:06"))).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withText("关闭")).perform(click())
            scenario.onActivity { views(page).filterIsInstance<X08eCourseCard>().single { v -> v.contentDescription.startsWith("重叠课程，") }.performClick() }
            onView(withText("重叠课程")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            screenshot("overlap-chooser")
            onView(withText("关闭")).perform(click())
            onView(withContentDescription("日期调整 ${week.plusDays(1)}")).perform(click())
            onView(withContentDescription("日期调整方式")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withText("取消")).perform(click())
            scenario.onActivity {
                val mixed = profile.copy(phases = profile.phases + SchedulePhase(week.plusDays(3), profile.phases.single().periods.map { p -> p.copy(start=p.start.plusMinutes(5),end=p.end.plusMinutes(5)) }))
                val grid = views(page).filterIsInstance<X08eWeekGrid>().single()
                views(page).filterIsInstance<ScrollView>().single().scrollTo(0, grid.offset(600f))
                fixtureData = fixtureData.copy(profiles=mapOf(profile.termKey to mixed))
                source.reload()
            }
            instrumentation.waitForIdleSync()
            onView(withText("本周作息缺失或不一致，暂按实际时间显示。")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            screenshot("periods-fallback")
            scenario.onActivity {
                val grid = views(page).filterIsInstance<X08eWeekGrid>().single()
                assertFalse(grid.usesPeriods); assertEquals(4, grid.dayCount(2))
                val scroll = views(page).filterIsInstance<ScrollView>().single()
                assertEquals(600f, grid.minuteAt(scroll.scrollY), 1f)
                fixtureData = fixtureData.copy(dayAdjustments=listOf(DayAdjustment(week.toString(),"none",null,"停课")))
                source.reload()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(0, views(page).filterIsInstance<X08eWeekGrid>().single().dayCount(0)) }
        }
    }
}
