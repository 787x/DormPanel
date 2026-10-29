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
import com.dormpanel.app.schedule.testing.SwipeEvents
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.Locale

/** Synthetic data only, in the testbed package or emulator. Never binds a real WebDAV/workspace. */
class X08eScheduleDisplayTest {
    @get:Rule val isolation = IsolatedDashboardRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var week = LocalDate.of(2026, 10, 26)
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
    private var networkState: ScheduleState? = null
    private var sharedDemo = false
    private fun fixture(): ScheduleState {
        networkState?.let { return it }
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
        val base=ScheduleState(sources = listOf(owner), imported = imported + extras, profiles = mapOf(profile.termKey to profile))
        if(!sharedDemo) return base
        val slot=CourseSlot("pr70-slot","source","course-0",null,week.toString(),listOf(1,3,5),2,480,580,1,2,true,"共享新增课程","共享教室 A301","教师","非连续周安排")
        return SharedCourseColors.change(base.copy(sharedRecords=listOf(SharedCourseSchedule.record(slot))),"source","course-0",0xd04f80)
    }
    @Before fun setup() {
        saved = X08eSchedulePreferences(context).read()
        if(InstrumentationRegistry.getArguments().getString("renderer")=="legacy") X08ePreviewTrace.cardOverride={ c,item,color,title,time,period,status,click ->
            X08eLegacyCourseCard(c,item,color,title,time,period,status,click)
        }
    }
    @After fun restore() { X08ePreviewTrace.cardOverride=null; X08eSchedulePreferences(context).write(saved); if (::source.isInitialized) instrumentation.runOnMainSync { source.close() } }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun install(activity: MainActivity, style: X08eScheduleStyle, theme: ThemeMode = ThemeMode.DARK, session: ScheduleSession? = null, persistStyle: Boolean = true,
        locale: Locale = Locale.SIMPLIFIED_CHINESE, homeAction: () -> Unit = {}) {
        if (persistStyle) X08eSchedulePreferences(context).write(style)
        val config = Configuration(activity.resources.configuration).apply { setLocale(locale) }
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
        page = SchedulePageView(localized, source, selected, appearance, homeAction, viewport = viewport)
        // Exercise the same MainActivity arbitration as a real displayed schedule page.
        MainActivity::class.java.getDeclaredField("activePageInteraction").apply { isAccessible=true }.set(activity,page)
        activity.setContentView(page)
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(450)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertEquals(1280, bitmap.width); assertEquals(800, bitmap.height)
        java.io.File(context.getExternalFilesDir(null), "pr63-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Test fun pr71NetworkThreeLayoutsAndMonochrome() {
        require(context.packageName.endsWith(".testbed"))
        val file=java.io.File(context.getExternalFilesDir(null),"pr71-network-state.dpv")
        require(file.isFile)
        networkState=SyncV3Codec.decode(file.readBytes()).state!!
        val config=org.json.JSONObject(java.io.File(context.getExternalFilesDir(null),"pr71-probe.private.json").readText())
        assertEquals(config.getString("expectedHash"),SyncV3Codec.businessHash(networkState!!))
        week=LocalDate.of(2026,9,7);now=week.atTime(9,10).atZone(zone).toInstant()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for(layout in X08eScheduleLayout.entries) for(color in listOf(true,false)) {
                scenario.onActivity { activity ->
                    install(activity,X08eScheduleStyle(layout,color,true))
                    val added=ScheduleProjection.week(source.state,week,zone).single{it.entry.id=="slot-a"}
                    assertEquals(0x336699,added.sharedRgb);assertEquals("fnOS 新教室",added.entry.location)
                    assertEquals(2,ScheduleProjection.week(source.state,week,zone).size)
                }
                screenshot("pr71-network-${layout.name.lowercase()}-$color")
            }
        }
    }
    @Test fun sharedSchedulesAndColorsThreeLayouts() {
        sharedDemo=true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for(layout in X08eScheduleLayout.entries) {
                scenario.onActivity { activity ->
                    install(activity,X08eScheduleStyle(layout,true,true))
                    val added=ScheduleProjection.week(source.state,week,zone).single { it.entry.id=="pr70-slot" }
                    assertEquals(0xd04f80,added.sharedRgb)
                    assertEquals("共享教室 A301",added.entry.location)
                    assertEquals(week.plusDays(1),added.academicDate)
                }
                screenshot("pr70-${layout.name.lowercase()}")
            }
        }
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
                val card = views(grid).filterIsInstance<X08eCourseTile>().map { it as View }.first()
                appearance.setTheme(ThemeMode.LIGHT); page.applyAppearance(appearance.state)
                assertSame(card, views(grid).filterIsInstance<X08eCourseTile>().map { it as View }.first())
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
    @Test fun pr64SwipeAllLayoutsDirectionCancelScrollAndRefresh() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (layout in X08eScheduleLayout.entries) {
                val session=ScheduleSession(clock).apply { mode=ScheduleMode.TIMETABLE }
                val enabled=X08eScheduleStyle(layout,true,true,true)
                scenario.onActivity { install(it,enabled,session=session) }
                fun idle() { instrumentation.waitForIdleSync(); Thread.sleep(500); instrumentation.waitForIdleSync() }
                fun swipe() = views(page).filterIsInstance<TimetableSwipeLayout>().single()
                idle()
                var anchor=0f
                scenario.onActivity {
                    if (layout != X08eScheduleLayout.CLASSIC) {
                        val grid=views(page).filterIsInstance<X08eWeekGrid>().single()
                        val scroll=views(page).filterIsInstance<ScrollView>().single()
                        scroll.scrollTo(0,grid.offset(600f)); anchor=grid.minuteAt(scroll.scrollY)
                    }
                    SwipeEvents.drag(swipe(),-swipe().width*.4f,during={ assertTrue(swipe().getChildAt(0).translationX<0) })
                }; idle()
                scenario.onActivity {
                    assertEquals(week.plusWeeks(1),session.weekStart)
                    assertEquals(0f,swipe().getChildAt(0).translationX,0f)
                    assertEquals(enabled,X08eSchedulePreferences(context).read())
                    if (layout != X08eScheduleLayout.CLASSIC) {
                        val g=views(page).filterIsInstance<X08eWeekGrid>().single()
                        assertEquals(anchor,g.minuteAt(views(page).filterIsInstance<ScrollView>().single().scrollY),2f)
                    }
                    SwipeEvents.drag(swipe(),swipe().width*.4f)
                }; idle()
                scenario.onActivity { assertEquals(week,session.weekStart) }
                for (kind in 0..5) {
                    scenario.onActivity { val v=swipe()
                        when(kind) {
                            0 -> SwipeEvents.drag(v,-v.width*.08f)
                            1 -> SwipeEvents.drag(v,-v.width*.4f,cancel=true)
                            2 -> SwipeEvents.drag(v,-v.width*.3f,-v.width*.3f)
                            3 -> SwipeEvents.multiTouch(v)
                            4 -> SwipeEvents.drag(v,-v.width*.4f,during={ source.refresh() })
                            5 -> SwipeEvents.drag(v,-v.width*.4f,during={ v.onWindowFocusChanged(false) })
                        }
                    }; idle()
                    scenario.onActivity { assertEquals("$layout kind=$kind",week,session.weekStart); assertEquals(0f,swipe().getChildAt(0).translationX,0f) }
                }
                if (layout == X08eScheduleLayout.CLASSIC) {
                    scenario.onActivity {
                        val columns=views(page).filterIsInstance<ScrollView>()
                        assertEquals(7,columns.size)
                        // Each classic column remains an independent vertical scroller.
                        columns[0].getChildAt(0).minimumHeight=2000; columns[0].requestLayout()
                    }; idle()
                    scenario.onActivity {
                        val col=views(page).filterIsInstance<ScrollView>()[0]
                        val pos=IntArray(2); col.getLocationOnScreen(pos); val loc=IntArray(2); swipe().getLocationOnScreen(loc)
                        SwipeEvents.drag(swipe(),0f,-100f,x=(pos[0]-loc[0]+col.width/2f),y=(pos[1]-loc[1]+col.height*.7f))
                    }; idle()
                    scenario.onActivity { val columns=views(page).filterIsInstance<ScrollView>(); assertTrue(columns[0].scrollY>0); assertEquals(0,columns[1].scrollY) }
                } else {
                    var old=0
                    scenario.onActivity { val sc=views(page).filterIsInstance<ScrollView>().single(); sc.scrollTo(0,0); old=sc.scrollY; SwipeEvents.drag(swipe(),0f,-100f,y=swipe().height*.7f) }; idle()
                    scenario.onActivity { assertTrue("$layout scroll ${views(page).filterIsInstance<ScrollView>().single().scrollY} old=$old",views(page).filterIsInstance<ScrollView>().single().scrollY>old) }
                }
                scenario.onActivity { SwipeEvents.drag(swipe(),-80f,duration=35) }; idle()
                scenario.onActivity {
                    assertEquals(week.plusWeeks(1),session.weekStart); assertEquals(fixtureData,source.state)
                    install(it,enabled.copy(swipeWeeks=false),session=session)
                }; idle()
                scenario.onActivity { SwipeEvents.drag(swipe(),-swipe().width*.4f) }; idle()
                scenario.onActivity { assertEquals(week.plusWeeks(1),session.weekStart); assertEquals(0f,swipe().getChildAt(0).translationX,0f) }
            }
        }
    }
    @Test fun pr64SettingsPersistAndDisableCancelsMotion() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val session=ScheduleSession(clock).apply { mode=ScheduleMode.TIMETABLE }
            scenario.onActivity { install(it,X08eScheduleStyle(),session=session) }
            onView(withText("显示设置")).perform(click())
            onView(withText("横划切换周次")).perform(click()); onView(withText("保存")).perform(click())
            scenario.onActivity { a ->
                assertTrue(X08eSchedulePreferences(context).read().swipeWeeks)
                install(a,X08eSchedulePreferences(context).read(),session=session)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val swipe=views(page).filterIsInstance<TimetableSwipeLayout>().single()
                SwipeEvents.drag(swipe,-swipe.width*.4f,during={ swipe.swipeEnabled=false })
                assertEquals(0f,swipe.getChildAt(0).translationX,0f); assertEquals(week,session.weekStart)
                assertFalse(page.shouldObservePageSwipe(android.view.MotionEvent.obtain(0,0,0,0f,0f,0)))
            }
            onView(withText("日历")).perform(click())
            scenario.onActivity { assertTrue(views(page).none { it is TimetableSwipeLayout }) }
            onView(withText("课表")).perform(click())
            scenario.onActivity { assertTrue(views(page).filterIsInstance<TimetableSwipeLayout>().single().swipeEnabled) }
        }
    }
    @Test fun pr64NativeTouchAnimationAcrossLayouts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (layout in X08eScheduleLayout.entries) {
                val session=ScheduleSession(clock).apply { mode=ScheduleMode.TIMETABLE }
                scenario.onActivity { install(it,X08eScheduleStyle(layout,true,true,true),session=session) }
                instrumentation.waitForIdleSync()
                val target=withTagValue(org.hamcrest.Matchers.equalTo("timetable-swipe" as Any))
                onView(target).perform(SwipeEvents.nativeDrag(.7f,.25f))
                Thread.sleep(500); instrumentation.waitForIdleSync()
                scenario.onActivity { assertEquals(week.plusWeeks(1),session.weekStart); assertSame(page,views(it.window.decorView).filterIsInstance<SchedulePageView>().single()) }
                onView(target).perform(SwipeEvents.nativeDrag(.5f,.42f))
                Thread.sleep(500); scenario.onActivity { assertEquals(week.plusWeeks(1),session.weekStart) }
                onView(target).perform(SwipeEvents.nativeDrag(.3f,.75f))
                Thread.sleep(500); scenario.onActivity { assertEquals(week,session.weekStart) }
            }
        }
    }
    private fun followScreenshot(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(150)
        val b=instrumentation.uiAutomation.takeScreenshot()
        assertEquals(1280,b.width); assertEquals(800,b.height)
        java.io.File(context.getExternalFilesDir(null),"pr64-follow-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }; b.recycle()
    }
    private fun datedPreviewFixture() {
        val imported=(-7..20).flatMap { day -> listOf(8,10,14).map { hour ->
            val start=instant(day,hour,0)
            ImportedClassOccurrence("preview-$day-$hour","source","preview-$day-$hour",null,start,"真实 ${week.plusDays(day.toLong())}",
                start,start+45*60_000,zone.id,"预览教室","",null)
        } }
        val p=profile.copy(phases=profile.phases.map { it.copy(effectiveFrom=week.minusWeeks(2)) })
        fixtureData=fixtureData.copy(entries=emptyList(),imported=imported,profiles=mapOf(p.termKey to p),
            sources=fixtureData.sources.map { it.copy(occurrenceCount=imported.size,firstStart=imported.minOf { c -> c.start },lastEnd=imported.maxOf { c -> c.end }) })
        source.reload()
    }
    @Test fun pr64CompactCardMatchesNativePixels() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { a ->
            install(a,X08eScheduleStyle(X08eScheduleLayout.TIME,true,true,true))
            val item=ScheduleProjection.week(source.state,week,zone).first()
            for(scale in listOf(1f,1.3f,1.6f)) {
            val cfg=Configuration(a.resources.configuration).apply { fontScale=scale }
            val ctx=android.view.ContextThemeWrapper(a.createConfigurationContext(cfg),com.dormpanel.app.R.style.Theme_DormPanel)
            for(theme in listOf(ThemeMode.DARK,ThemeMode.LIGHT)) for(width in listOf(44,80,166)) for(height in listOf(1,20,48,72,180,-1)) {
                val cards=listOf<View>(X08eLegacyCourseCard(ctx,item,true,"很长的课程名称 CJK 测试","08:00–09:40","第1–2节","已修改") {},
                    X08eCourseCard(ctx,item,true,"很长的课程名称 CJK 测试","08:00–09:40","第1–2节","已修改") {})
                val images=cards.map { v ->
                    (v as AppearanceAware).applyAppearance(AppearanceState(theme))
                    v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(maxOf(0,height),if(height<0) View.MeasureSpec.UNSPECIFIED else View.MeasureSpec.EXACTLY))
                    v.layout(0,0,v.measuredWidth,v.measuredHeight)
                    Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
                }
                assertEquals("height $width/$height",cards[0].height,cards[1].height)
                assertTrue("pixels $theme $width/$height",images[0].sameAs(images[1]))
                images.forEach { it.recycle() }
            }
            }
        } }
    }
    @Test fun pr64QuarterReleaseAndFlingAlwaysFinishComplete() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for(layout in X08eScheduleLayout.entries) for(fling in listOf(false,true)) {
                val session=ScheduleSession(clock).apply { mode=ScheduleMode.TIMETABLE }
                scenario.onActivity { install(it,X08eScheduleStyle(layout,true,true,true),session=session); datedPreviewFixture() }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val v=views(page).filterIsInstance<TimetableSwipeLayout>().single()
                    if(fling) SwipeEvents.drag(v,-80f,duration=35) else {
                        val stroke=SwipeEvents.Stroke(v,v.width*.9f); stroke.move(-v.width*.25f); stroke.end()
                    }
                }
                Thread.sleep(500); instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val target=week.plusWeeks(1)
                    assertEquals(target,session.weekStart)
                    val v=views(page).filterIsInstance<TimetableSwipeLayout>().single()
                    assertEquals(1,v.childCount)
                    val body=v.getChildAt(0)
                    val pending=body.javaClass.getDeclaredField("pending").let { it.isAccessible=true; it.get(body) as List<*> }
                    assertTrue("No orphan final-frame work: $layout fling=$fling",pending.isEmpty())
                    val courses=views(body).filter { it is X08eCourseTile || it is X08eClassicCourseButton }
                    assertEquals(21,courses.size)
                    assertTrue(courses.all { it.isClickable && it.hasOnClickListeners() })
                    for(day in 0..6) {
                        val date=target.plusDays(day.toLong())
                        assertEquals(1,views(body).filterIsInstance<Button>().count { it.contentDescription?.startsWith("日期调整 $date")==true })
                        assertEquals(3,courses.count { c -> if(c is TextView) c.text.contains("真实 $date") else c.contentDescription.contains("真实 $date") })
                    }
                    if(layout!=X08eScheduleLayout.CLASSIC) assertEquals(7,views(body).filterIsInstance<Button>().count { it.text.startsWith("查看 3 门") })
                }
            }
        }
    }
    @Test fun pr64PreviewPerformanceSamples() {
        val samples=org.json.JSONArray()
        val label=InstrumentationRegistry.getArguments().getString("perfLabel") ?: "sample"
        val perfScenario=InstrumentationRegistry.getArguments().getString("perfScenario") ?: "jump"
        val firstFraction=InstrumentationRegistry.getArguments().getString("perfFraction")?.toFloat() ?: .25f
        val main=android.os.Looper.getMainLooper().thread.id
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for(layout in X08eScheduleLayout.entries) {
                val session=ScheduleSession(clock).apply { mode=ScheduleMode.TIMETABLE }
                scenario.onActivity { install(it,X08eScheduleStyle(layout,true,true,true),session=session); datedPreviewFixture() }
                instrumentation.waitForIdleSync()
                for(index in 0 until 6) {
                    val events=java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())
                    val frames=java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())
                    var lock=0L; var end=0L; var currentDraw=0L; var targetDraw=0L
                    var firstMove=0L; var warmMove=0L; var up=0L
                    lateinit var stroke: SwipeEvents.Stroke
                    lateinit var v: TimetableSwipeLayout
                    val sink: (String,Long,Long)->Unit = { name,a,b -> events.add(org.json.JSONObject().put("phase",name).put("start",a).put("end",b).put("main",Thread.currentThread().id==main)) }
                    val draw=android.view.ViewTreeObserver.OnDrawListener {
                        if(lock>0 && currentDraw==0L && v.getChildAt(0).translationX!=0f) currentDraw=System.nanoTime()
                        if(lock>0 && targetDraw==0L && v.childCount==2 && kotlin.math.abs(v.getChildAt(1).translationX)<v.width) targetDraw=System.nanoTime()
                    }
                    val listener=android.view.Window.OnFrameMetricsAvailableListener { _,m,dropped ->
                        frames.add(org.json.JSONObject().put("vsync",m.getMetric(android.view.FrameMetrics.INTENDED_VSYNC_TIMESTAMP))
                            .put("total",m.getMetric(android.view.FrameMetrics.TOTAL_DURATION)).put("dropped",dropped))
                    }
                    try {
                        scenario.onActivity { a ->
                            X08ePreviewTrace.observer=sink
                            session.weekStart=week; viewport.gridMinute=480f; viewport.offsets.clear()
                            if(index==0 || index==3) install(a,X08eScheduleStyle(layout,true,true,true),session=session) else source.refresh()
                            a.window.addOnFrameMetricsAvailableListener(listener,android.os.Handler(android.os.Looper.getMainLooper()))
                        }
                        instrumentation.waitForIdleSync(); Thread.sleep(50)
                        scenario.onActivity { a ->
                            v=views(page).filterIsInstance<TimetableSwipeLayout>().single(); v.previewTiming=sink
                            a.window.decorView.viewTreeObserver.addOnDrawListener(draw)
                            stroke=SwipeEvents.Stroke(v,v.width*.9f)
                            if(perfScenario=="cold") {
                                val cache=SchedulePageView::class.java.getDeclaredField("displayCache").let { it.isAccessible=true; it.get(page) as X08eDisplayCache }
                                cache.clear()
                            }
                            lock=System.nanoTime(); stroke.move(-v.width*firstFraction); firstMove=System.nanoTime()-lock
                        }
                        if(perfScenario!="release") {
                            Thread.sleep(80); instrumentation.waitForIdleSync()
                            scenario.onActivity { val start=System.nanoTime(); stroke.move(-v.width*.8f); warmMove=System.nanoTime()-start }
                            Thread.sleep(50); instrumentation.waitForIdleSync()
                        }
                        scenario.onActivity { val start=System.nanoTime(); stroke.end(); up=System.nanoTime()-start }
                        Thread.sleep(350); instrumentation.waitForIdleSync()
                        scenario.onActivity {
                            end=System.nanoTime()
                            assertEquals(week.plusWeeks(1),session.weekStart)
                        }
                    } finally {
                        scenario.onActivity { a ->
                            X08ePreviewTrace.observer=null
                            a.window.decorView.viewTreeObserver.removeOnDrawListener(draw)
                            a.window.removeOnFrameMetricsAvailableListener(listener)
                        }
                    }
                    val sample=org.json.JSONObject().put("layout",layout.name).put("index",index).put("path",if(index==0||index==3) "new-page" else "repeat")
                        .put("scenario",perfScenario).put("firstFraction",firstFraction).put("lock",lock).put("end",end).put("move",firstMove).put("warmMove",warmMove).put("up",up)
                        .put("currentDraw",currentDraw).put("targetDraw",targetDraw)
                    synchronized(events) { sample.put("events",org.json.JSONArray(events)) }
                    synchronized(frames) { sample.put("frames",org.json.JSONArray(frames)) }
                    samples.put(sample)
                }
            }
        }
        java.io.File(context.getExternalFilesDir(null),"pr64-perf-$label.json").writeText(samples.toString(2))
    }
    @Test fun pr64NativeFramePerformanceSamples() {
        val samples=org.json.JSONArray()
        val label=InstrumentationRegistry.getArguments().getString("perfLabel") ?: "native"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for(layout in X08eScheduleLayout.entries) {
                val session=ScheduleSession(clock).apply { mode=ScheduleMode.TIMETABLE }
                scenario.onActivity { install(it,X08eScheduleStyle(layout,true,true,true),session=session); datedPreviewFixture() }
                instrumentation.waitForIdleSync()
                for(index in 0..2) {
                    val events=java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())
                    val frames=java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())
                    var lock=0L; var targetDraw=0L; var end=0L
                    lateinit var v: TimetableSwipeLayout
                    val sink: (String,Long,Long)->Unit = { n,a,b ->
                        if(n=="lock") lock=a
                        events.add(org.json.JSONObject().put("phase",n).put("start",a).put("end",b).put("main",Thread.currentThread()==android.os.Looper.getMainLooper().thread))
                    }
                    val draw=android.view.ViewTreeObserver.OnDrawListener {
                        if(lock>0 && targetDraw==0L && v.childCount==2 && kotlin.math.abs(v.getChildAt(1).translationX)<v.width) targetDraw=System.nanoTime()
                    }
                    val listener=android.view.Window.OnFrameMetricsAvailableListener { _,m,dropped -> frames.add(org.json.JSONObject()
                        .put("vsync",m.getMetric(android.view.FrameMetrics.INTENDED_VSYNC_TIMESTAMP)).put("total",m.getMetric(android.view.FrameMetrics.TOTAL_DURATION)).put("dropped",dropped)) }
                    try {
                        scenario.onActivity { a ->
                            v=views(page).filterIsInstance<TimetableSwipeLayout>().single(); v.previewTiming=sink
                            X08ePreviewTrace.observer=sink
                            a.window.decorView.viewTreeObserver.addOnDrawListener(draw)
                            a.window.addOnFrameMetricsAvailableListener(listener,android.os.Handler(android.os.Looper.getMainLooper()))
                        }
                        val target=withTagValue(org.hamcrest.Matchers.equalTo("timetable-swipe" as Any))
                        onView(target).perform(SwipeEvents.nativeDrag(if(index==1) .1f else .9f,if(index==1) .9f else .1f))
                        Thread.sleep(300); instrumentation.waitForIdleSync()
                        scenario.onActivity { end=System.nanoTime(); assertEquals(if(index==1) week else week.plusWeeks(1),session.weekStart) }
                    } finally {
                        scenario.onActivity { a -> X08ePreviewTrace.observer=null; a.window.decorView.viewTreeObserver.removeOnDrawListener(draw); a.window.removeOnFrameMetricsAvailableListener(listener) }
                    }
                    val row=org.json.JSONObject().put("layout",layout.name).put("index",index).put("lock",lock).put("targetDraw",targetDraw).put("end",end)
                    synchronized(events) {
                        val viewPhases=setOf("card-views","visible-views","staged-views","mount","measure","layout","commit")
                        assertTrue(events.filter { it.getString("phase") in viewPhases }.all { it.getBoolean("main") })
                        row.put("events",org.json.JSONArray(events))
                    }
                    synchronized(frames) { row.put("frames",org.json.JSONArray(frames)) }
                    samples.put(row)
                }
            }
        }
        java.io.File(context.getExternalFilesDir(null),"pr64-perf-$label.json").writeText(samples.toString(2))
    }
    @Test fun pr64NativeDistinctWeeksPreviewRecording() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val session=ScheduleSession(clock).apply { mode=ScheduleMode.TIMETABLE }
            scenario.onActivity { install(it,X08eScheduleStyle(X08eScheduleLayout.TIME,true,true,true),session=session); datedPreviewFixture() }
            instrumentation.waitForIdleSync()
            val target=withTagValue(org.hamcrest.Matchers.equalTo("timetable-swipe" as Any))
            onView(target).perform(SwipeEvents.nativeDrag(.9f,.1f)); Thread.sleep(450)
            scenario.onActivity { assertEquals(week.plusWeeks(1),session.weekStart) }
            onView(target).perform(SwipeEvents.nativeDrag(.5f,.42f)); Thread.sleep(450)
            scenario.onActivity { assertEquals(week.plusWeeks(1),session.weekStart) }
            onView(target).perform(SwipeEvents.nativeDrag(.1f,.9f)); Thread.sleep(450)
            scenario.onActivity { assertEquals(week,session.weekStart); assertEquals(1,views(page).filterIsInstance<TimetableSwipeLayout>().single().childCount) }
        }
    }
    @Test fun pr64StagedPreviewCleanupAndReadonlyViewport() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun idle() { instrumentation.waitForIdleSync(); Thread.sleep(120); instrumentation.waitForIdleSync() }
            for(layout in X08eScheduleLayout.entries) {
                val session=ScheduleSession(clock).apply { mode=ScheduleMode.TIMETABLE }
                scenario.onActivity { install(it,X08eScheduleStyle(layout,true,true,true),session=session); datedPreviewFixture() }; idle()
                if(layout==X08eScheduleLayout.CLASSIC) {
                    scenario.onActivity { views(page).filterIsInstance<ScrollView>().forEach { it.getChildAt(0).minimumHeight=2000; it.requestLayout() } }; idle()
                    scenario.onActivity { views(page).filterIsInstance<ScrollView>().forEachIndexed { i,v -> v.scrollTo(0,80+i*10) } }
                } else scenario.onActivity {
                    val grid=views(page).filterIsInstance<X08eWeekGrid>().single()
                    views(page).filterIsInstance<ScrollView>().single().scrollTo(0,grid.offset(600f))
                }
                lateinit var v: TimetableSwipeLayout
                lateinit var preview: View
                lateinit var stroke: SwipeEvents.Stroke
                val before=viewport.offsets.toMap(); val minute=viewport.gridMinute
                scenario.onActivity {
                    v=views(page).filterIsInstance<TimetableSwipeLayout>().single()
                    if(layout==X08eScheduleLayout.CLASSIC) {
                        val make=v.preparePreview!!
                        v.preparePreview={ d -> make(d).also { views(it).filterIsInstance<ScrollView>().forEach { s -> s.getChildAt(0).minimumHeight=2000 } } }
                    }
                    stroke=SwipeEvents.Stroke(v,v.width*.9f); stroke.move(-v.width*.25f); preview=v.getChildAt(1)
                    assertEquals(before,viewport.offsets); assertEquals(minute,viewport.gridMinute)
                }; idle()
                scenario.onActivity {
                    assertEquals(before,viewport.offsets); assertEquals(minute,viewport.gridMinute)
                    stroke.move(-v.width*.8f); stroke.end()
                }; Thread.sleep(400); idle()
                scenario.onActivity {
                    assertEquals(week.plusWeeks(1),session.weekStart)
                    assertSame(preview,views(page).filterIsInstance<TimetableSwipeLayout>().single().getChildAt(0))
                    if(layout==X08eScheduleLayout.CLASSIC) assertEquals(before,viewport.offsets) else assertEquals(minute!!,viewport.gridMinute!!,1f)
                    v=views(page).filterIsInstance<TimetableSwipeLayout>().single()
                    stroke=SwipeEvents.Stroke(v); stroke.move(-v.width*.05f); preview=v.getChildAt(1)
                    source.refresh(); stroke.end()
                    assertFalse(preview.isAttachedToWindow)
                }
                val count=views(preview).size; idle()
                scenario.onActivity { assertEquals(count,views(preview).size); assertEquals(week.plusWeeks(1),session.weekStart) }
                scenario.onActivity {
                    v=views(page).filterIsInstance<TimetableSwipeLayout>().single()
                    stroke=SwipeEvents.Stroke(v); stroke.move(v.width*.05f); preview=v.getChildAt(1)
                    v.onWindowFocusChanged(false); stroke.end()
                    assertFalse(preview.isAttachedToWindow); assertEquals(1,v.childCount)
                }; idle()
                // A mode change clears both pending View batches and immutable display data.
                onView(withText("日历")).perform(click()); idle()
                scenario.onActivity {
                    val cache=SchedulePageView::class.java.getDeclaredField("displayCache").let { it.isAccessible=true; it.get(page) as X08eDisplayCache }
                    assertNull(cache.get(session.weekStart)); assertNull(cache.get(session.weekStart.plusWeeks(1)))
                }
                onView(withText("课表")).perform(click()); idle()
                scenario.onActivity {
                    v=views(page).filterIsInstance<TimetableSwipeLayout>().single(); stroke=SwipeEvents.Stroke(v)
                    stroke.move(-v.width*.05f); preview=v.getChildAt(1)
                }
                scenario.recreate(); idle()
                assertFalse(preview.isAttachedToWindow)
                Thread.sleep(1100)
                assertTrue(Thread.getAllStackTraces().keys.none { it.name=="x08e-display-data" && it.isAlive })
            }
        }
    }
    @Test fun pr64DelayedSnapshotCancelAndRefreshCannotPublishStaleData() {
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val once=java.util.concurrent.atomic.AtomicBoolean(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val session=ScheduleSession(clock).apply { mode=ScheduleMode.TIMETABLE }
            try {
                scenario.onActivity {
                    install(it,X08eScheduleStyle(X08eScheduleLayout.TIME,true,true,true),session=session); datedPreviewFixture()
                    X08ePreviewTrace.observer={ name,_,_ ->
                        if(name=="projection" && Thread.currentThread().name=="x08e-display-data" && once.compareAndSet(true,false)) {
                            entered.countDown()
                            var waiting=true
                            while(waiting) try { release.await(); waiting=false } catch(_: InterruptedException) { }
                        }
                    }
                    source.refresh()
                }
                assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
                scenario.onActivity {
                    val v=views(page).filterIsInstance<TimetableSwipeLayout>().single()
                    val stroke=SwipeEvents.Stroke(v); stroke.move(-v.width*.25f)
                    assertEquals(2,v.childCount); val preview=v.getChildAt(1)
                    assertEquals(week,session.weekStart)
                    stroke.end(cancel=true); v.cancelMotion()
                    assertFalse(preview.isAttachedToWindow)
                    fixtureData=fixtureData.copy(imported=fixtureData.imported.map { c -> c.copy(title="新值 ${c.title}") })
                    source.reload()
                }
                release.countDown()
                var ready=false; val deadline=System.currentTimeMillis()+5000
                while(!ready && System.currentTimeMillis()<deadline) {
                    scenario.onActivity {
                        val cache=SchedulePageView::class.java.getDeclaredField("displayCache").let { f -> f.isAccessible=true; f.get(page) as X08eDisplayCache }
                        val data=cache.get(week.plusWeeks(1))
                        ready=data!=null && data.items.all { c -> c.entry.title.startsWith("新值") }
                    }
                    if(!ready) Thread.sleep(10)
                }
                assertTrue(ready)
                scenario.onActivity {
                    val v=views(page).filterIsInstance<TimetableSwipeLayout>().single()
                    val stroke=SwipeEvents.Stroke(v); stroke.move(-v.width*.5f)
                    assertTrue(views(v.getChildAt(1)).any { t -> if(t is TextView) t.text.startsWith("新值") else t.contentDescription?.startsWith("新值")==true })
                    stroke.end(cancel=true); v.cancelMotion()
                    assertEquals(1,v.childCount); assertEquals(week,session.weekStart)
                }
            } finally { release.countDown(); scenario.onActivity { X08ePreviewTrace.observer=null } }
        }
    }
    @Test fun pr64RealAdjacentPreviewGeometryPromotionAndCleanupAcrossLayouts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun idle() { instrumentation.waitForIdleSync(); Thread.sleep(450); instrumentation.waitForIdleSync() }
            fun swipe()=views(page).filterIsInstance<TimetableSwipeLayout>().single()
            for (layout in X08eScheduleLayout.entries) {
                val session=ScheduleSession(clock).apply { mode=ScheduleMode.TIMETABLE }
                scenario.onActivity { a ->
                    install(a,X08eScheduleStyle(layout,true,true,true),session=session)
                    datedPreviewFixture()
                }; idle()
                for (direction in listOf(1,-1)) {
                    val target=week.plusWeeks(direction.toLong())
                    lateinit var stroke: SwipeEvents.Stroke
                    lateinit var current: View
                    lateinit var preview: View
                    var offsets=emptyList<Int>()
                    scenario.onActivity {
                        val v=swipe(); current=v.getChildAt(0)
                        stroke=SwipeEvents.Stroke(v,if(direction==1) v.width*.9f else v.width*.1f)
                        assertEquals(1,v.childCount)
                    }
                    for (fraction in listOf(.25f,.5f,.8f)) {
                        scenario.onActivity {
                            val v=swipe(); val start=android.os.SystemClock.elapsedRealtimeNanos()
                            stroke.move(-direction*v.width*fraction)
                            if(fraction==.25f) println("PR64 preview x08e $layout build-layout-ms=${(android.os.SystemClock.elapsedRealtimeNanos()-start)/1_000_000f}")
                            assertEquals(2,v.childCount); preview=v.getChildAt(1)
                            assertEquals("x08e-page-$target",preview.tag)
                            assertEquals(-direction*v.width*fraction,current.translationX,1f)
                            assertEquals(direction*v.width*(1-fraction),preview.translationX,1f)
                            assertEquals(v.width,preview.width); assertEquals(v.height,preview.height)
                            assertEquals(week,session.weekStart)
                            val visibleDate=if(direction==1) target else target.plusDays(6)
                            assertTrue(views(preview).any { if(it is TextView) it.text.contains("真实 $visibleDate") else it.contentDescription?.contains("真实 $visibleDate")==true })
                            views(preview).filterIsInstance<Button>().forEach { it.performClick() }
                            views(preview).filterIsInstance<X08eCourseTile>().map { it as View }.firstOrNull()?.performClick()
                            assertTrue(page.hasWindowFocus())
                            views(preview).filterIsInstance<X08eWeekGrid>().forEach { g ->
                                assertFalse(g.clockAllowed)
                                assertEquals(false,X08eWeekGrid::class.java.getDeclaredField("active").let { it.isAccessible=true; it.get(g) })
                            }
                        }
                        if(fraction==.5f && direction==1) followScreenshot("${layout.name.lowercase()}-half")
                    }
                    scenario.onActivity { offsets=views(preview).filterIsInstance<ScrollView>().map { it.scrollY }; stroke.end() }; idle()
                    scenario.onActivity {
                        assertEquals(target,session.weekStart); assertSame(preview,swipe().getChildAt(0)); assertEquals(1,swipe().childCount)
                        assertFalse(current.isAttachedToWindow); assertEquals(0f,preview.translationX,0f)
                        assertEquals(offsets,views(preview).filterIsInstance<ScrollView>().map { it.scrollY })
                        assertEquals(fixtureData,source.state)
                    }
                    if(direction==1) {
                        scenario.onActivity {
                            views(preview).first { it is X08eCourseTile || it is X08eClassicCourseButton }.performClick()
                        }
                        onView(withText("真实 $target")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                            .check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
                        androidx.test.espresso.Espresso.pressBack()
                    }
                    scenario.onActivity { SwipeEvents.drag(swipe(),direction*swipe().width*.6f) }; idle()
                    scenario.onActivity {
                        assertEquals(week,session.weekStart)
                        val v=swipe(); val reversal=SwipeEvents.Stroke(v)
                        reversal.move(-v.width*.5f); val next=v.getChildAt(1)
                        reversal.move(v.width*.5f)
                        assertFalse(next.isAttachedToWindow); assertEquals(2,v.childCount)
                        assertEquals("x08e-page-${week.minusWeeks(1)}",v.getChildAt(1).tag)
                        reversal.end(cancel=true)
                    }; idle()
                    scenario.onActivity { assertEquals(week,session.weekStart); assertEquals(1,swipe().childCount); assertEquals(0f,swipe().getChildAt(0).translationX,0f) }
                }
                scenario.onActivity {
                    val v=swipe(); val drag=SwipeEvents.Stroke(v); drag.move(-v.width*.5f); val preview=v.getChildAt(1)
                    source.refresh(); assertFalse(preview.isAttachedToWindow); drag.end()
                }; idle()
                scenario.onActivity {
                    assertEquals(1,swipe().childCount); assertEquals(week,session.weekStart)
                    val v=swipe(); val drag=SwipeEvents.Stroke(v); drag.move(-v.width*.5f); val preview=v.getChildAt(1)
                    v.swipeEnabled=false; assertFalse(preview.isAttachedToWindow); assertEquals(1,v.childCount); drag.end()
                }
            }
        }
    }
    @Test fun pr64HomeBoundsChineseEnglishAndEdgeTouches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE,Locale.US)) {
                var clicks=0
                scenario.onActivity { install(it,X08eScheduleStyle(),locale=locale,homeAction={clicks++}) }
                instrumentation.waitForIdleSync()
                val name=if(locale.language=="zh") "返回首页" else "Home"
                scenario.onActivity {
                    val home=views(page).filterIsInstance<Button>().single { it.text==name }
                    assertTrue(home.width>=context.dp(112)); assertEquals(context.dp(56),home.height)
                    val header=page.getChildAt(0) as ViewGroup
                    assertTrue((0 until header.childCount-1).all { i -> header.getChildAt(i).right<=header.getChildAt(i+1).left })
                    assertTrue(home.right<=header.width); assertTrue(header.getChildAt(0).width>0)
                }
                followScreenshot("home-${locale.language}")
                for ((x,y) in listOf(.03f to .5f,.97f to .5f,.5f to .03f,.5f to .97f)) {
                    if (InstrumentationRegistry.getArguments().getString("homeDirect")=="true") scenario.onActivity {
                        val home=views(page).filterIsInstance<Button>().single { it.text==name }
                        val r=android.graphics.Rect(0,0,home.width,home.height); page.offsetDescendantRectToMyCoords(home,r)
                        SwipeEvents.Stroke(page,r.left+r.width()*x,r.top+r.height()*y).end()
                    } else onView(withText(name)).perform(androidx.test.espresso.action.GeneralClickAction(androidx.test.espresso.action.Tap.SINGLE,
                        { v -> val p=IntArray(2); v.getLocationOnScreen(p); floatArrayOf(p[0]+v.width*x,p[1]+v.height*y) },androidx.test.espresso.action.Press.FINGER))
                    instrumentation.waitForIdleSync()
                }
                assertEquals(4,clicks)
            }
        }
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
            scenario.onActivity { views(page).filterIsInstance<X08eCourseTile>().map { it as View }.single { v -> v.contentDescription.startsWith("重叠课程，") }.performClick() }
            onView(withText("重叠课程")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withText(org.hamcrest.Matchers.containsString("地点：实验楼"))).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            screenshot("overlap-direct-detail")
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

    private fun clickCourse(title: String) {
        onView(org.hamcrest.Matchers.allOf(isClickable(), org.hamcrest.Matchers.anyOf(
            withText(org.hamcrest.Matchers.startsWith("$title\n")),
            withContentDescription(org.hamcrest.Matchers.startsWith("$title，")))))
            .perform(click())
    }
    private fun detailContains(vararg values: String) {
        onView(withText(org.hamcrest.Matchers.allOf(values.map { org.hamcrest.Matchers.containsString(it) })))
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
    }
    private fun closeDetail() = onView(withText("关闭"))
        .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
    private fun selectLayout(index: Int) = object : androidx.test.espresso.ViewAction {
        override fun getConstraints() = isAssignableFrom(Spinner::class.java)
        override fun getDescription() = "Select layout $index"
        override fun perform(controller: androidx.test.espresso.UiController, view: View) {
            (view as Spinner).setSelection(index); controller.loopMainThreadUntilIdle()
        }
    }
    @Test fun individualCardsOpenTheirOwnDetailsForTwoThreeAndAdjacentCourses() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val targets = listOf(
                Triple("点击A", 0, 480 to 580), Triple("点击B", 0, 500 to 560),
                Triple("点击C", 1, 480 to 580), Triple("点击D", 1, 500 to 570), Triple("点击E", 1, 520 to 580),
                Triple("相邻F", 2, 600 to 645), Triple("相邻G", 2, 645 to 690))
            scenario.onActivity {
                install(it, X08eScheduleStyle(X08eScheduleLayout.TIME))
                fixtureData = fixtureData.copy(imported = targets.map { (id,day,bounds) ->
                    val start = instant(day,bounds.first/60,bounds.first%60)
                    ImportedClassOccurrence(id,"source",id,null,start,id,start,instant(day,bounds.second/60,bounds.second%60),
                        zone.id,"地点-$id","来源原文-$id",null)
                }, classOverrides=targets.map { (id,_,_) -> ClassOverride.of("source",id,null,
                    ClassPatch(teacher="教师-$id",note="备注-$id")) })
                source.reload()
            }
            for (layout in listOf(X08eScheduleLayout.TIME,X08eScheduleLayout.PERIODS))
                for (colors in listOf(false,true)) for (hierarchy in listOf(false,true)) {
                    scenario.onActivity { install(it,X08eScheduleStyle(layout,colors,hierarchy)) }
                    instrumentation.waitForIdleSync()
                    val clickTargets = if (colors == hierarchy) targets else targets.take(2)
                    for ((id,_,bounds) in clickTargets) {
                        // Espresso sends a real tap at the visible card; no performClick shortcut.
                        clickCourse(id)
                        val time = "${X08eWeekGeometry.timeLabel(bounds.first)}–${X08eWeekGeometry.timeLabel(bounds.second)}"
                        detailContains(id,time,"地点-$id","教师-$id","备注-$id","来源原文-$id","隔离演示课表")
                        if (layout == X08eScheduleLayout.PERIODS && colors && hierarchy && id == "点击B") screenshot("overlap-exact-target")
                        closeDetail()
                    }
                    val gap = FloatArray(2)
                    scenario.onActivity {
                        val grid = views(page).filterIsInstance<X08eWeekGrid>().single()
                        val column = grid.getChildAt(1)
                        val xy = IntArray(2); column.getLocationOnScreen(xy)
                        gap[0] = xy[0] + column.width / 2f; gap[1] = xy[1] + grid.offset(590f).toFloat()
                    }
                    onView(org.hamcrest.Matchers.sameInstance(page)).perform(androidx.test.espresso.action.GeneralClickAction(
                        androidx.test.espresso.action.Tap.SINGLE, { gap }, androidx.test.espresso.action.Press.FINGER))
                    onView(withText("编辑")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                    onView(withText("当天课程")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                }
            clickCourse("点击B")
            onView(withText("编辑")).perform(click()); onView(withText("仅本周")).perform(click())
            onView(withHint("课程名称")).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText("点击B")))
            onView(withHint("教师")).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText("教师-点击B")))
            onView(withText("取消")).perform(click())
            scenario.onActivity { assertEquals(fixtureData.imported, source.state.imported); assertEquals(fixtureData.classOverrides,source.state.classOverrides) }
        }
    }
    @Test fun fixedDayListsRetainShortSubminuteAndBothMidnightSlices() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                install(it,X08eScheduleStyle(X08eScheduleLayout.PERIODS,true,true))
                val start = instant(2,12,10)+1000
                fixtureData = fixtureData.copy(imported=fixtureData.imported + ImportedClassOccurrence("seconds","source","seconds",null,
                    start,"十秒课程",start,start+10000,zone.id,"短课地点","十秒来源",null))
                source.reload()
                assertEquals(5,views(page).filterIsInstance<X08eWeekGrid>().single().dayCount(2))
            }
            for ((day,title,room) in listOf(Triple(2,"短课程（1分钟）","会议室"),Triple(2,"十秒课程","短课地点"),
                Triple(4,"跨午夜研讨","线上"),Triple(5,"跨午夜研讨","线上"))) {
                scenario.onActivity { views(page).filterIsInstance<X08eWeekGrid>().single().openDay(day) }
                onView(withText(org.hamcrest.Matchers.startsWith("$title\n"))).perform(click())
                detailContains(title,room)
                if (title=="跨午夜研讨") detailContains("23:30–10/31 00:30","2026")
                closeDetail()
            }
            scenario.onActivity { assertEquals(fixtureData.imported,source.state.imported) }
        }
    }
    @Test fun classicCompatibilityDefaultsIndependentScrollingAndOperations() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val session=ScheduleSession(clock).apply { mode=ScheduleMode.TIMETABLE }
            lateinit var firstScroll: ScrollView
            scenario.onActivity {
                context.getSharedPreferences("x08e_schedule_display",Context.MODE_PRIVATE).edit().clear().commit()
                install(it,X08eScheduleStyle(),session=session,persistStyle=false)
                assertEquals(X08eScheduleStyle(),X08eSchedulePreferences(context).read())
                fixtureData=fixtureData.copy(entries=(0..23).map { i -> TimetableEntry("classic-$i","经典追加$i",1,360+i*25,380+i*25,"教室$i") }.reversed())
                source.reload()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val scrolls=views(page).filterIsInstance<ScrollView>(); assertEquals(7,scrolls.size)
                assertTrue(scrolls.zipWithNext().all { (a,b) -> a.left==b.left && kotlin.math.abs(a.width-b.width)<=1 && a.parent!==b.parent })
                val xs=scrolls.map { v -> IntArray(2).also { v.getLocationOnScreen(it) }[0] }
                assertTrue(xs.zipWithNext().all { (a,b) -> b>a })
                val expected=ScheduleProjection.week(source.state,week,zone).filter { it.date==week }.map { it.entry.title }
                val actual=views(scrolls[0]).filterIsInstance<X08eClassicCourseButton>().map { it.text.toString().substringBefore('\n') }
                assertEquals(expected,actual); assertTrue(views(page).filterIsInstance<X08eCourseTile>().map { it as View }.isEmpty())
                firstScroll=scrolls.first()
            }
            onView(org.hamcrest.Matchers.sameInstance(firstScroll)).perform(androidx.test.espresso.action.ViewActions.swipeUp())
            scenario.onActivity {
                val scrolls=views(page).filterIsInstance<ScrollView>(); assertTrue(scrolls[0].scrollY>0)
                assertTrue(scrolls.drop(1).all { it.scrollY==0 })
                appearance.setTheme(ThemeMode.LIGHT); page.applyAppearance(appearance.state)
                assertTrue(views(page).filterIsInstance<X08eClassicCourseButton>().all { v -> v.backgroundTintList!!.defaultColor==PanelPalette.forMode(ThemeMode.LIGHT).surface })
                appearance.setTheme(ThemeMode.DARK); page.applyAppearance(appearance.state)
            }
            onView(withText(org.hamcrest.Matchers.startsWith("经典追加0\n"))).perform(androidx.test.espresso.action.ViewActions.scrollTo(),click())
            detailContains("经典追加0","06:00–06:20","教室0")
            onView(withText("编辑")).perform(click()); onView(withText("仅本周")).perform(click())
            onView(withHint("课程名称")).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText("经典追加0")))
            onView(withText("取消")).perform(click())
            onView(withContentDescription("日期调整 $week")).perform(click())
            onView(withContentDescription("日期调整方式")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withText("取消")).perform(click())
            onView(withContentDescription("下一周")).perform(click())
            scenario.onActivity { assertEquals(week.plusWeeks(1),session.weekStart) }
            onView(withText("本周")).perform(click())
            for ((index,expected) in listOf(1 to X08eScheduleStyle(X08eScheduleLayout.TIME,true,true),0 to X08eScheduleStyle())) {
                onView(withText("显示设置")).perform(click())
                onView(withContentDescription("课表布局")).perform(selectLayout(index))
                onView(withText("按课程配色")).perform(click()); onView(withText("优化信息排版")).perform(click())
                onView(withText("保存")).perform(click())
                scenario.onActivity { assertEquals(expected,X08eSchedulePreferences(context).read()); assertEquals(week,session.weekStart) }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(7,views(page).filterIsInstance<ScrollView>().size); assertEquals(fixtureData.entries.toSet(),source.state.entries.toSet()) }
        }
    }

}
