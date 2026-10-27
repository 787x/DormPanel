package com.dormpanel.phone

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import org.hamcrest.Matchers.*
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.util.UUID

class PhoneTimetableTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var dbName: String
    @Before fun setup() {
        for (name in listOf("TimetableActivity", "com.dormpanel.phone.TimetableActivity"))
            instrumentation.targetContext.getSharedPreferences(name, 0).edit().clear().commit()
        dbName = "phone-test-${UUID.randomUUID()}.db"
        ScheduleStores.overrideFactory = { context -> RoomScheduleStore(Room.databaseBuilder(context, ScheduleDatabase::class.java, dbName).build()) }
        TermScheduleProfileStore.overrideNamespace = "phone-test-${UUID.randomUUID()}"
        Intents.init()
        // Start and verify the test-only provider before handing a URI to the picker.
        instrumentation.context.contentResolver.query(Uri.parse("content://${instrumentation.context.packageName}.fixtures/wakeup.ics"), null, null, null, null)!!.use {
            assertTrue("Fixture provider must expose the requested file", it.moveToFirst())
        }
    }
    @After fun teardown() {
        Intents.release(); ScheduleStores.overrideFactory = null
        instrumentation.targetContext.deleteDatabase(dbName)
        TermScheduleProfileStore.overrideNamespace?.let { instrumentation.targetContext.getSharedPreferences(it, 0).edit().clear().commit() }
        TermScheduleProfileStore.overrideNamespace = null
    }
    private fun waitFor(s: ActivityScenario<TimetableActivity>, condition: (TimetableActivity) -> Boolean) {
        var ready = false
        val deadline = System.currentTimeMillis() + 10000
        while (!ready && System.currentTimeMillis() < deadline) { s.onActivity { ready = condition(it) }; if (!ready) Thread.sleep(40) }
        assertTrue("Timed out waiting for phone state", ready)
        instrumentation.waitForIdleSync()
    }
    private fun open() = launchPhoneTestActivity().also { scenario -> scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }; Thread.sleep(700); waitFor(scenario) { a -> a.source.ready }; onView(withText("日视图")).perform(click()) }
    private fun pick(filename: String) {
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://${instrumentation.context.packageName}.fixtures/$filename"))))
        onView(withText("管理")).perform(click())
        onView(withText("导入 ICS / 湖北大学 CSV、XLS")).perform(click())
    }
    private fun preview(s: ActivityScenario<TimetableActivity>) {
        val deadline = System.currentTimeMillis() + 10000
        while (true) {
            try { onView(withText("确认导入")).check(matches(isDisplayed())); break }
            catch (e: androidx.test.espresso.NoMatchingViewException) {
                if (System.currentTimeMillis() >= deadline) throw e
                Thread.sleep(80)
            }
        }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(350) // Let native dialog transitions finish before visual QA.
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "phone-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }; bitmap.recycle()
    }
    // A time block can be taller than the viewport. Tap its visible portion, as a user does.
    private fun timeBlockClick() = object : androidx.test.espresso.ViewAction {
        override fun getConstraints() = isAssignableFrom(android.view.View::class.java)
        override fun getDescription() = "Reveal and tap visible course block"
        override fun perform(controller: androidx.test.espresso.UiController, view: android.view.View) {
            view.requestRectangleOnScreen(android.graphics.Rect(0, 0, view.width, minOf(view.height, view.context.dp(48))), true)
            controller.loopMainThreadUntilIdle()
            androidx.test.espresso.action.GeneralClickAction(androidx.test.espresso.action.Tap.SINGLE, { v ->
                val rect = android.graphics.Rect()
                check(v.getGlobalVisibleRect(rect))
                floatArrayOf(rect.exactCenterX(), rect.exactCenterY())
            }, androidx.test.espresso.action.Press.FINGER).perform(controller, view)
        }
    }
    private fun grid(a: TimetableActivity): PhoneWeekView {
        fun find(v: android.view.View): PhoneWeekView? {
            if (v is PhoneWeekView) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return find(a.window.decorView)!!
    }
    @Test fun pr62ReviewCustomTimingKeepsSourceLabelButNeverShowsItAsCurrent() {
        val ctx = instrumentation.targetContext
        for (name in listOf("TimetableActivity","com.dormpanel.phone.TimetableActivity")) ctx.getSharedPreferences(name,0).edit()
            .putString("date","2026-10-27").putString("term",BuiltInProfiles.TERM_2026_2027_1)
            .putBoolean("periodMode",true).putBoolean("weekdaysOnly",true).commit()
        open().use { scenario ->
            val parsed = instrumentation.context.assets.open("hubei_2026-2027-1.csv").use {
                TimetableImporter(profiles=TermScheduleProfileStore(ctx)).parse(ScheduleArtifact.read("review.csv",it))
            }
            scenario.onActivity { a -> a.source.import(parsed,"边界验收 CSV",null) { assertTrue(it.isSuccess) } }
            waitFor(scenario) { it.source.state.imported.size==131 }
            lateinit var original: ClassOccurrence
            fun projected(a: TimetableActivity) = ScheduleProjection.week(a.source.state,LocalDate.of(2026,10,26),a.source.clock.zone())
            scenario.onActivity { a -> original=projected(a).single { it.date==LocalDate.of(2026,10,27) && ScheduleProjection.periods(it.periodLabel)==(3 to 4) } }
            fun currentCard() = withContentDescription(startsWith("${original.entry.title}，2026-10-27"))
            fun inspect(custom: Boolean) {
                scenario.onActivity { a ->
                    val current = projected(a).single { it.seriesId==original.seriesId && it.anchorDate==original.anchorDate }
                    assertEquals(original.periodLabel,current.periodLabel)
                    assertEquals(original.imported,a.source.state.imported.single { it.id==original.imported!!.id })
                    assertEquals(!custom,current.linked)
                    assertEquals(if (custom) null else "3–4节",CoursePresentation.currentPeriods(current,ScheduleProjection.profile(a.source.state,current)))
                    val col = grid(a).getChildAt(2) as android.view.ViewGroup
                    val card = (0 until col.childCount).map { col.getChildAt(it) }.filterIsInstance<CourseCard>().single { it.contentDescription.startsWith("${original.entry.title}，2026-10-27") }
                    assertEquals(if (custom) "" else "3–4节",(card.getChildAt(3) as android.widget.TextView).text.toString())
                    if (custom) {
                        assertFalse(card.contentDescription.contains("3–4节"))
                        assertEquals(android.view.View.VISIBLE,card.getChildAt(2).visibility)
                        assertTrue((card.getChildAt(2) as android.widget.TextView).text.contains("13:15"))
                        assertTrue((card.getChildAt(2) as android.widget.TextView).text.contains("14:45"))
                    }
                }
            }
            onView(withText("周视图")).perform(click()); inspect(false); screenshot("review-linked-before")
            onView(currentCard()).perform(timeBlockClick())
            onView(withText(containsString("当前节次：3–4节"))).check(matches(isDisplayed()))
            onView(withText("Edit")).perform(click()); onView(withText("This week")).perform(click())
            onView(isAssignableFrom(android.widget.Spinner::class.java)).perform(spinner(1))
            fun pickTime(prefix: String, hour: Int, minute: Int) {
                onView(withText(startsWith(prefix))).perform(scrollTo(),click())
                onView(isAssignableFrom(android.widget.TimePicker::class.java)).perform(object : androidx.test.espresso.ViewAction {
                    override fun getConstraints()=isAssignableFrom(android.widget.TimePicker::class.java)
                    override fun getDescription()="Set custom time"
                    override fun perform(controller: androidx.test.espresso.UiController,view: android.view.View) {
                        (view as android.widget.TimePicker).apply { this.hour=hour; this.minute=minute }; controller.loopMainThreadUntilIdle()
                    }
                })
                onView(withId(android.R.id.button1)).perform(click())
            }
            pickTime("Start ·",13,15); pickTime("End ·",14,45)
            onView(withText("Save")).perform(click())
            waitFor(scenario) { a -> projected(a).any { it.seriesId==original.seriesId && it.anchorDate==original.anchorDate && !it.linked && it.entry.startMinute==795 } }
            inspect(true); screenshot("review-custom-after")
            onView(currentCard()).perform(timeBlockClick())
            onView(withText(containsString("原始导入节次（非当前节次）"))).check(matches(isDisplayed()))
            androidx.test.espresso.Espresso.pressBack()
            scenario.recreate(); waitFor(scenario) { it.source.ready }; inspect(true)
            onView(currentCard()).perform(timeBlockClick()); onView(withText("Edit")).perform(scrollTo(),click())
            onView(withText("This week")).perform(click())
            onView(isAssignableFrom(android.widget.Spinner::class.java)).check { view,error -> if(error!=null) throw error; assertEquals(1,(view as android.widget.Spinner).selectedItemPosition) }
            onView(withText("Cancel")).perform(click())
            onView(currentCard()).perform(timeBlockClick()); onView(withText("Reset this week")).perform(scrollTo(),click())
            waitFor(scenario) { it.source.state.classOverrides.isEmpty() }; inspect(false)
            scenario.onActivity { a -> assertTrue(a.source.saveDayAdjustment(LocalDate.of(2026,10,27),"weekday",1)) }
            waitFor(scenario) { it.source.state.dayAdjustments.isNotEmpty() }
            scenario.onActivity { a -> projected(a).filter { it.date==LocalDate.of(2026,10,27) && it.linked }.forEach {
                assertNotNull(CoursePresentation.currentPeriods(it,ScheduleProjection.profile(a.source.state,it)))
            }; assertTrue(a.source.saveDayAdjustment(LocalDate.of(2026,10,27),"normal")) }
            waitFor(scenario) { it.source.state.dayAdjustments.isEmpty() }; inspect(false)
        }
    }
    @Test fun pr62ReviewCompressedGapUsesNearbyAndKeepsBodyAndCancelSemantics() {
        open().use { scenario ->
            val monday = LocalDate.of(2026,10,26); val zone=java.time.ZoneId.systemDefault()
            fun item(id: String,day: Int,start: Int,end: Int)=ClassOccurrence(TimetableEntry(id,id,day+1,start,end.coerceAtMost(1439)),monday.plusDays(day.toLong()),
                monday.plusDays(day.toLong()).atStartOfDay(zone).plusMinutes(start.toLong()).toInstant(),monday.plusDays(day.toLong()).atStartOfDay(zone).plusMinutes(end.toLong()).toInstant())
            val a=item("相邻课程 A",0,480,525); val b=item("相邻课程 B",0,535,580)
            val tiny=item("一分钟课程命中",1,480,481)
            val overlap=listOf(item("连叠 A",2,480,540),item("连叠 B",2,510,570),item("连叠 C",2,560,600))
            val night=item("跨日原始命中",3,1410,1470)
            var chosen: ClassOccurrence?=null
            lateinit var g: PhoneWeekView
            scenario.onActivity { activity ->
                g=PhoneWeekView(activity,listOf(a,b,tiny,night)+overlap,monday,zone,{chosen=it},7,true,BuiltInProfiles.term2026.copy(timezone=zone.id))
                activity.setContentView(android.widget.ScrollView(activity).apply { addView(g) })
            }
            onView(withContentDescription(startsWith("相邻课程 A，"))).perform(timeBlockClick()); assertSame(a,chosen)
            onView(withContentDescription(startsWith("相邻课程 B，"))).perform(timeBlockClick()); assertSame(b,chosen)
            fun touch(day: Int,y: Float,cancel: Boolean=false,drag: Boolean=false) {
                scenario.onActivity { activity ->
                    val column=g.getChildAt(day+1)
                    val density=activity.resources.displayMetrics.density
                    val now=android.os.SystemClock.uptimeMillis()
                    fun send(action:Int,at:Float) { android.view.MotionEvent.obtain(now,now,action,column.width/2f,at*density,0).also { column.dispatchTouchEvent(it); it.recycle() } }
                    send(android.view.MotionEvent.ACTION_DOWN,y)
                    if(drag) send(android.view.MotionEvent.ACTION_MOVE,y+30)
                    send(if(cancel) android.view.MotionEvent.ACTION_CANCEL else android.view.MotionEvent.ACTION_UP,if(drag) y+30 else y)
                }
            }
            val gap=(g.axes[0].y(525f)+g.axes[0].y(535f))/2
            chosen=null; touch(0,gap,true); assertNull(chosen)
            touch(0,gap,drag=true); assertNull(chosen)
            fun awaitDialog(title: String) {
                val deadline=System.currentTimeMillis()+3000
                while(true) {
                    try { onView(withText(title)).check(matches(isDisplayed())); return }
                    catch(error: androidx.test.espresso.NoMatchingViewException) { if(System.currentTimeMillis()>deadline) throw error; Thread.sleep(40) }
                }
            }
            touch(0,gap)
            awaitDialog("附近课程"); screenshot("review-nearby-gap")
            onView(withText(startsWith("相邻课程 B\n"))).perform(click()); assertSame(b,chosen)
            chosen=null; touch(1,g.axes[1].y(481f)+4); assertSame(tiny,chosen)
            chosen=null
            var scrollBefore=0
            scenario.onActivity { scrollBefore=(g.parent as android.widget.ScrollView).scrollY }
            onView(isAssignableFrom(android.widget.ScrollView::class.java)).perform(swipeUp())
            scenario.onActivity { assertTrue((g.parent as android.widget.ScrollView).scrollY>scrollBefore); assertNull(chosen) }
            screenshot("review-overlap-before-click")
            for (item in overlap) {
                onView(withText("3 门重叠")).perform(timeBlockClick())
                awaitDialog("重叠课程")
                onView(withText(startsWith(item.entry.title+"\n"))).perform(click()); assertSame(item,chosen)
            }
            for (day in listOf(3,4)) scenario.onActivity {
                val column=g.getChildAt(day+1) as android.view.ViewGroup
                (0 until column.childCount).map { column.getChildAt(it) }.filterIsInstance<CourseCard>().single().performClick(); assertSame(night,chosen)
            }
        }
    }
    @Test fun pr62ReviewFiveDayRangeKeepsWeekdayFragmentsAndRestoresAnchor() {
        val ctx=instrumentation.targetContext
        for(name in listOf("TimetableActivity","com.dormpanel.phone.TimetableActivity")) ctx.getSharedPreferences(name,0).edit()
            .putString("date","2026-10-27").putString("term",BuiltInProfiles.TERM_2026_2027_1).commit()
        open().use { scenario ->
            scenario.onActivity { a ->
                a.source.saveEntry("工作日正常",1,480,1020)
                a.source.saveEntry("周六深夜",6,1320,1430)
            }
            waitFor(scenario) { it.source.state.entries.size==2 }; onView(withText("周视图")).perform(click())
            fun checkRange(count: Int,periods: Boolean) {
                scenario.onActivity { a ->
                    val g=grid(a); assertEquals(count,g.dayCount); assertEquals(periods,g.usesPeriods)
                    assertEquals(if(count==5) { if(periods) 1295f else 1080f } else 1430f,g.axes.first().points.last().first,.01f)
                    assertEquals(2,a.source.state.entries.size)
                    if(count==7) assertTrue((g.getChildAt(6) as android.view.ViewGroup).let { col -> (0 until col.childCount).any { col.getChildAt(it) is CourseCard } })
                    val scroller=a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll")
                    scroller.scrollTo(0,0)
                }
            }
            checkRange(7,false)
            scenario.onActivity { a -> a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll").let { it.scrollTo(0,(it.getChildAt(0).height-it.height).coerceAtLeast(0)) } }
            screenshot("review-range-seven-time")
            onView(withText("七天 · 切换五天")).perform(click()); checkRange(5,false)
            scenario.onActivity { a -> a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll").let { it.scrollTo(0,(it.getChildAt(0).height-it.height).coerceAtLeast(0)) } }
            screenshot("review-range-five-time")
            onView(withText("按时间 ▾")).perform(click()); onView(withText("按节次 · 压缩课间")).perform(click()); checkRange(5,true)
            onView(withText("五天 · 切换七天")).perform(click()); checkRange(7,true)
            var anchor=480f
            scenario.onActivity { a -> val g=grid(a); val scroller=a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll"); scroller.scrollTo(0,g.offsetAt(600f)); anchor=g.minuteAt(scroller.scrollY) }
            onView(withText("七天 · 切换五天")).perform(click())
            scenario.onActivity { a ->
                val g=grid(a); val scroller=a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll")
                val expected=g.offsetAt(anchor).coerceAtMost((g.height-scroller.height).coerceAtLeast(0))
                assertTrue(kotlin.math.abs(expected-scroller.scrollY)<=2)
                assertEquals(5,g.dayCount); assertTrue(g.usesPeriods)
            }
            scenario.recreate(); waitFor(scenario) { it.source.ready }; checkRange(5,true)
            scenario.onActivity { a -> a.source.saveEntry("工作日晚间",5,1380,1439) }
            waitFor(scenario) { it.source.state.entries.size==3 }
            scenario.onActivity { a -> assertEquals(1439f,grid(a).axes.first().points.last().first,.01f) }
        }
    }
    @Test fun pr62AxesRangesClockAndRecreationWithRealCsv() {
        val ctx = instrumentation.targetContext
        for (name in listOf("TimetableActivity","com.dormpanel.phone.TimetableActivity")) ctx.getSharedPreferences(name,0).edit()
            .putString("date","2026-10-27").putString("term",BuiltInProfiles.TERM_2026_2027_1).commit()
        try {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
            open().use { s ->
                if (InstrumentationRegistry.getArguments().getString("userCsv") == "true") {
                    val file = java.io.File(ctx.getExternalFilesDir(null),"user-timetable.csv")
                    val parsed = file.inputStream().use { TimetableImporter(profiles=TermScheduleProfileStore(ctx)).parse(ScheduleArtifact.read("user.csv",it)) }
                    s.onActivity { a -> a.source.import(parsed,"实测 CSV",null) { assertTrue(it.isSuccess) } }
                    waitFor(s) { it.source.state.imported.size == parsed.occurrences.size }
                } else {
                    pick("hubei_2026-2027-1.csv"); preview(s); onView(withText("确认导入")).perform(click())
                    waitFor(s) { it.source.state.imported.size == 131 }
                }
                onView(withText("今天")).perform(click())
                onView(withText("周视图")).perform(click())
                screenshot("pr62-time-seven-light")
                onView(withText("按时间 ▾")).perform(click())
                onView(withText("按节次 · 压缩课间")).perform(click())
                screenshot("pr62-period-seven-light")
                var narrow = 0
                var anchor = 480f
                s.onActivity { a ->
                    val g = grid(a); assertTrue(g.usesPeriods); narrow = g.getChildAt(1).width
                    val cards = (1 until g.childCount).sumOf { (g.getChildAt(it) as android.view.ViewGroup).childCount }
                    val today = a.source.clock.today()
                    g.updateNow(today.atTime(10,0).atZone(a.source.clock.zone()))
                    g.updateNow(today.atTime(10,1).atZone(a.source.clock.zone()))
                    assertEquals(cards,(1 until g.childCount).sumOf { (g.getChildAt(it) as android.view.ViewGroup).childCount })
                    val scroller = a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll")
                    scroller.scrollTo(0,g.offsetAt(600f))
                    anchor = g.minuteAt(scroller.scrollY)
                }
                onView(withText("七天 · 切换五天")).perform(click())
                s.onActivity { a ->
                    val g = grid(a); assertEquals(6,g.childCount); assertTrue(g.getChildAt(1).width > narrow)
                    val y = a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll").scrollY
                    assertEquals(anchor,g.minuteAt(y),3f)
                }
                s.onActivity { a -> a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll").scrollTo(0,0) }
                screenshot("pr62-period-five-light")
                s.recreate(); waitFor(s) { it.source.ready }
                s.onActivity { assertEquals(5,grid(it).dayCount); assertTrue(grid(it).usesPeriods) }
                instrumentation.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) }
                Thread.sleep(1000); waitFor(s) { it.source.ready }
                s.onActivity { a -> a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll").scrollTo(0,0) }
                screenshot("pr62-period-five-dark")
                onView(withText("五天 · 切换七天")).perform(click())
                screenshot("pr62-period-seven-dark")
                s.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                Thread.sleep(1000); waitFor(s) { it.source.ready }; screenshot("pr62-period-landscape-dark")
                s.onActivity { a ->
                    val scroller = a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll")
                    val parent = scroller.parent as android.view.View
                    assertTrue("Landscape content height ${scroller.height}",scroller.height > (parent.height-parent.paddingTop-parent.paddingBottom)*.60)
                }
            }
        } finally { instrumentation.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) } }
    }
    @Test fun pr62SyntheticOriginalsPhasesShortCardsAndClock() {
        open().use { scenario ->
            var chosen: ClassOccurrence? = null
            val monday = LocalDate.of(2026,10,5)
            val zone = java.time.ZoneId.systemDefault()
            fun item(id: String, day: Int, start: Int, end: Int) = ClassOccurrence(
                TimetableEntry(id,id,day+1,start,end.coerceAtMost(1439),"长地点 B310 通识多媒体教室"),monday.plusDays(day.toLong()),
                monday.plusDays(day.toLong()).atStartOfDay(zone).plusMinutes(start.toLong()).toInstant(),
                monday.plusDays(day.toLong()).atStartOfDay(zone).plusMinutes(end.toLong()).toInstant())
            val long = item("很长的课程名称需要显示三行以上",0,480,580)
            val short = item("一分钟课程",0,780,781)
            val night = item("跨午夜原始实例",2,1410,1470)
            val overlapA = item("连续重叠 A",1,480,540)
            val overlapB = item("连续重叠 B",1,510,570)
            val overlapC = item("连续重叠 C",1,560,600)
            val weekend = item("周末课程",6,600,660)
            val items = listOf(long,short,night,overlapA,overlapB,overlapC,weekend)
            scenario.onActivity { a ->
                val profile = BuiltInProfiles.term2026.copy(timezone=zone.id)
                val g = PhoneWeekView(a,items,monday,zone,{ chosen=it },7,true,profile)
                a.setContentView(android.widget.ScrollView(a).apply { addView(g) })
                assertTrue(g.usesPeriods)
                assertEquals(g.offsetAt(870f,0),g.offsetAt(840f,3))
                assertTrue(g.offsetAt(780f,0) < g.offsetAt(870f,0))
                val mondayColumn = g.getChildAt(1) as android.widget.FrameLayout
                val originals = (0 until mondayColumn.childCount).map { mondayColumn.getChildAt(it) }
                g.updateNow(monday.atTime(8,10).atZone(zone))
                val line = mondayColumn.getChildAt(mondayColumn.childCount-1)
                assertEquals(android.view.View.VISIBLE,line.visibility)
                assertEquals(g.offsetAt(490f,0),(line.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin)
                g.updateNow(monday.atTime(8,11).atZone(zone))
                originals.forEachIndexed { i,v -> assertSame(v,mondayColumn.getChildAt(i)) }
                g.updateNow(monday.plusDays(1).atTime(8,11).atZone(zone))
                assertEquals(android.view.View.GONE,line.visibility)
                assertEquals(android.view.View.VISIBLE,(g.getChildAt(2) as android.widget.FrameLayout).getChildAt((g.getChildAt(2) as android.widget.FrameLayout).childCount-1).visibility)
                // Both clipped overnight pieces retain the exact same editor object.
                for (day in listOf(2,3)) {
                    val col = g.getChildAt(day+1) as android.view.ViewGroup
                    (0 until col.childCount).map { col.getChildAt(it) }.filterIsInstance<CourseCard>().single().performClick()
                    assertSame(night,chosen)
                }
                val small = (0 until mondayColumn.childCount).map { mondayColumn.getChildAt(it) }.filterIsInstance<CourseCard>().last()
                small.performClick(); assertSame(short,chosen)
            }
            onView(withText("3 门重叠")).perform(timeBlockClick())
            onView(withText(startsWith("连续重叠 C"))).perform(click())
            assertSame(overlapC,chosen)
            scenario.onActivity { a ->
                val config = android.content.res.Configuration(a.resources.configuration).apply { fontScale=1.6f }
                val large = a.createConfigurationContext(config)
                val g = PhoneWeekView(large,items,monday,zone,{},5,true,BuiltInProfiles.term2026.copy(timezone=zone.id))
                g.measure(android.view.View.MeasureSpec.makeMeasureSpec(a.dp(320),android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED))
                g.layout(0,0,g.measuredWidth,g.measuredHeight)
                fun inspect(view: android.view.View) {
                    if (view is CourseCard) for (i in 0 until view.childCount) {
                        val child = view.getChildAt(i)
                        if (child.visibility == android.view.View.VISIBLE) {
                            assertTrue(child.bottom <= view.height-view.paddingBottom)
                            assertTrue(child.right <= view.width-view.paddingRight)
                        }
                    }
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) inspect(view.getChildAt(i))
                }
                inspect(g); assertEquals(6,g.childCount)
            }
        }
    }
    @Test fun pr62VisibleMinuteTickPreservesCardsAndStopsOffscreen() {
        open().use { scenario ->
            scenario.onActivity { a -> a.source.saveEntry("实时时钟验证",a.source.clock.today().dayOfWeek.value,480,1080) }
            waitFor(scenario) { it.source.state.entries.isNotEmpty() }
            onView(withText("今天")).perform(click()); onView(withText("周视图")).perform(click())
            var original: PhoneWeekView? = null
            var line: android.view.View? = null
            var card: CourseCard? = null
            var handler: android.os.Handler? = null
            var tick: Runnable? = null
            var staleTop = 0
            scenario.onActivity { a ->
                val g = grid(a); original = g
                val day = a.source.clock.today().dayOfWeek.value-1
                val col = g.getChildAt(day+1) as android.view.ViewGroup
                card = (0 until col.childCount).map { col.getChildAt(it) }.filterIsInstance<CourseCard>().single()
                line = col.getChildAt(col.childCount-1)
                g.updateNow(java.time.ZonedDateTime.now(a.source.clock.zone()).minusMinutes(2))
                staleTop = (line!!.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin
                handler = g.handler
                tick = PhoneWeekView::class.java.getDeclaredField("tick").apply { isAccessible=true }.get(g) as Runnable
                if (android.os.Build.VERSION.SDK_INT >= 29) assertTrue(handler!!.hasCallbacks(tick!!))
            }
            var updated = false
            val deadline = System.currentTimeMillis()+65_000
            while (!updated && System.currentTimeMillis() < deadline) {
                Thread.sleep(1000)
                scenario.onActivity { a ->
                    assertSame(original,grid(a))
                    updated = (line!!.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin != staleTop
                    if (updated) {
                        val now = java.time.ZonedDateTime.now(a.source.clock.zone())
                        assertTrue(kotlin.math.abs(original!!.offsetAt(now.toLocalTime().toSecondOfDay()/60f,now.dayOfWeek.value-1)-
                            (line!!.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin) <= a.dp(2))
                        val parent = card!!.parent as android.view.ViewGroup
                        assertTrue((0 until parent.childCount).any { parent.getChildAt(it) === card })
                    }
                }
            }
            assertTrue("Visible clock must refresh at the next real minute",updated)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            instrumentation.runOnMainSync { if (android.os.Build.VERSION.SDK_INT >= 29) assertFalse(handler!!.hasCallbacks(tick!!)) }
        }
    }
    @Test fun sevenColumnsRotateRefreshAndOpenDetail() {
        open().use { s ->
            s.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            Thread.sleep(1000); instrumentation.waitForIdleSync()
            onView(withText("今天")).perform(click())
            s.onActivity { a ->
                a.source.saveEntry("周视图测试", LocalDate.now().dayOfWeek.value, 480, 540, "测试教室")
            }
            waitFor(s) { it.source.state.entries.isNotEmpty() }
            onView(withText("周视图")).perform(click())
            fun checkColumns() {
                s.onActivity { a ->
                    fun find(v: android.view.View): PhoneWeekView? {
                        if (v is PhoneWeekView) return v
                        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                        return null
                    }
                    val view = find(a.window.decorView)!!
                    fun inspect(v: android.view.View) {
                        if (v is CourseCard) for (j in 0 until v.childCount) {
                            val text = v.getChildAt(j)
                            if (text.visibility == android.view.View.VISIBLE) {
                                assertTrue("Text right edge", text.right <= v.width - v.paddingRight)
                                assertTrue("Text bottom edge", text.bottom <= v.height - v.paddingBottom)
                                assertTrue(text.left >= v.paddingLeft && text.top >= v.paddingTop)
                            }
                        }
                        if (v is android.view.ViewGroup) for (j in 0 until v.childCount) inspect(v.getChildAt(j))
                    }
                    inspect(view)
                    assertEquals(8, view.childCount)
                    for (i in 1..7) {
                        val column = view.getChildAt(i)
                        assertTrue(column.width > 0)
                        assertTrue(column.right <= view.width)
                    }
                }
            }
            instrumentation.waitForIdleSync(); checkColumns(); screenshot("week-portrait")
            onView(withText(startsWith("周视图测试"))).perform(timeBlockClick())
            onView(withText("Edit")).check(matches(isDisplayed()))
            androidx.test.espresso.Espresso.pressBack()
            s.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            Thread.sleep(1000); instrumentation.waitForIdleSync(); checkColumns(); screenshot("week-landscape")
            s.recreate(); waitFor(s) { it.source.ready }; checkColumns()
            onView(withText("日视图")).perform(click())
            onView(withText(startsWith("周视图测试"))).check(matches(isDisplayed()))
            s.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }
    @Test fun overlappingInstancesLateScrollAndSyncRefresh() {
        open().use { s ->
            onView(withText("今天")).perform(click())
            s.onActivity { a ->
                val day = a.source.clock.today().dayOfWeek.value
                a.source.saveEntry("Overlap A", day, 480, 540, "A101")
                a.source.saveEntry("Overlap B", day, 510, 570, "B202")
                a.source.saveEntry("Late class", day, 1320, 1380, "C303")
            }
            waitFor(s) { it.source.state.entries.size == 3 }
            onView(withText("周视图")).perform(click())
            for (title in listOf("Overlap A", "Overlap B")) {
                onView(withText(startsWith("2 门重叠"))).perform(timeBlockClick())
                onView(withText(startsWith(title))).perform(click())
                onView(withText(title)).check(matches(isDisplayed()))
                onView(withText("Edit")).perform(scrollTo()).check(matches(isDisplayed()))
                androidx.test.espresso.Espresso.pressBack()
            }
            s.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            Thread.sleep(1000); instrumentation.waitForIdleSync()
            onView(withText(startsWith("Late class"))).perform(timeBlockClick())
            onView(withText("Late class")).check(matches(isDisplayed()))
            androidx.test.espresso.Espresso.pressBack()
            var offset = 0
            s.onActivity { a ->
                val scroller = a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll")
                assertTrue("Landscape course viewport", scroller.height >= a.dp(90))
                offset = scroller.scrollY
                assertTrue(offset > 0)
                a.sharedSync.changed?.invoke()
            }
            instrumentation.waitForIdleSync()
            s.onActivity { a -> assertEquals(offset, a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll").scrollY) }
            s.recreate(); waitFor(s) { it.source.ready }; instrumentation.waitForIdleSync()
            s.onActivity { a -> assertEquals(offset, a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll").scrollY) }
            screenshot("late-landscape")
            onView(withText("下周 ›")).perform(click())
            var date = ""
            s.onActivity { a ->
                date = a.window.decorView.findViewWithTag<android.widget.TextView>("week-range").text.toString()
                a.sharedSync.changed?.invoke()
            }
            instrumentation.waitForIdleSync()
            s.onActivity { a -> assertEquals(date, a.window.decorView.findViewWithTag<android.widget.TextView>("week-range").text.toString()) }
            s.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }
    @Test fun browseFolderSelectAndCancelAreDraftOnly() {
        val server = okhttp3.mockwebserver.MockWebServer()
        fun xml(path: String, child: String = "") = "<d:multistatus xmlns:d=\"DAV:\">" +
            listOf(path, child).filter { it.isNotEmpty() }.joinToString("") { "<d:response><d:href>$it</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" } + "</d:multistatus>"
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(207).setBody(xml("/dav/", "/dav/room/")))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(207).setBody(xml("/dav/room/")))
        server.start()
        val serverRoot = server.url("/dav/").toString()
        try { open().use { s ->
            var selected: String? = null
            s.onActivity { WebDavFolderDialog.show(it, WebDavAccount(serverRoot, "user", "secret")) { value -> selected = value } }
            val deadline = System.currentTimeMillis() + 10000
            while (true) {
                try { onView(withText("▸ room")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click()); break }
                catch (e: androidx.test.espresso.NoMatchingViewException) { if (System.currentTimeMillis() > deadline) throw e; Thread.sleep(50) }
            }
            Thread.sleep(500); instrumentation.waitForIdleSync()
            onView(withText("选择此文件夹")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
            assertEquals(server.url("/dav/room/").toString(), selected)
            s.onActivity { assertNull(it.source.state.sync); assertTrue(it.source.state.entries.isEmpty()) }
            assertEquals("PROPFIND", server.takeRequest().method)
            assertEquals("PROPFIND", server.takeRequest().method)
        } } finally { server.shutdown() }
    }
    @Test fun folderErrorsCancellationAndLateResponses() {
        val server = okhttp3.mockwebserver.MockWebServer(); server.start()
        val rootUrl = server.url("/dav/").toString()
        fun listing(path: String, child: Boolean) = "<d:multistatus xmlns:d=\"DAV:\">" +
            (listOf(path) + if (child) listOf("/dav/child/") else emptyList()).joinToString("") { "<d:response><d:href>$it</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" } + "</d:multistatus>"
        fun awaitText(value: String) {
            val until = System.currentTimeMillis() + 10000
            while (true) { try { onView(withText(startsWith(value))).check(matches(isDisplayed())); return }
                catch (e: androidx.test.espresso.NoMatchingViewException) { if (System.currentTimeMillis() > until) throw e; Thread.sleep(40) } }
        }
        try { open().use { s ->
            var selected: String? = null
            var dialog: android.app.AlertDialog? = null
            fun show() { s.onActivity { dialog = WebDavFolderDialog.show(it, WebDavAccount(rootUrl,"u","secret")) { selected = it } }; waitFor(s) { dialog?.window?.decorView?.hasWindowFocus() == true } }
            for (response in listOf(okhttp3.mockwebserver.MockResponse().setResponseCode(403), okhttp3.mockwebserver.MockResponse().setResponseCode(404), okhttp3.mockwebserver.MockResponse().setResponseCode(207).setBody("invalid"))) {
                server.enqueue(response); show(); awaitText("无法列出目录")
                onView(withText("取消")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click()); assertNull(selected)
            }
            s.onActivity { dialog = WebDavFolderDialog.show(it, WebDavAccount("http://127.0.0.1:1/dav/","u","secret")) { selected = it } }
            awaitText("无法列出目录"); onView(withText("取消")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click()); assertNull(selected)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(207).setBody(listing("/dav/",false)))
            show(); awaitText("没有子文件夹"); onView(withText("取消")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click()); assertNull(selected)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(207).setBody(listing("/dav/",true)))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(207).setBody(listing("/dav/child/",false)).setBodyDelay(400, java.util.concurrent.TimeUnit.MILLISECONDS))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(207).setBody(listing("/dav/",true)))
            show(); awaitText("▸ child"); onView(withText("▸ child")).perform(click())
            onView(withText("上一级")).perform(click()); awaitText("▸ child")
            onView(withText(rootUrl)).check(matches(isDisplayed()))
            onView(withText("取消")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click()); assertNull(selected)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(207).setBody(listing("/dav/",false)).setBodyDelay(400, java.util.concurrent.TimeUnit.MILLISECONDS))
            show(); onView(withText("取消")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click()); Thread.sleep(650)
            s.onActivity { assertFalse(dialog!!.isShowing); assertNull(it.source.state.sync); assertTrue(it.source.state.entries.isEmpty()) }
            assertNull(selected)
            repeat(server.requestCount) { assertEquals("PROPFIND", server.takeRequest().method) }
        } } finally { server.shutdown() }
    }
    @Test fun cardTextBoundsAndIdentityColors() {
        open().use { scenario -> scenario.onActivity { a ->
            val date = LocalDate.now()
            val start = date.atTime(8,0).atZone(java.time.ZoneId.systemDefault()).toInstant()
            val original = ClassOccurrence(TimetableEntry("id", "很长的课程名称用于验证测量边界与确定性的截断规则",1,480,580,"很长的教学楼和教室名称 A101"),date,start,start.plusSeconds(6000),seriesId="seriesA")
            fun color(card: CourseCard) = ((card.background as android.graphics.drawable.RippleDrawable).getDrawable(0) as android.graphics.drawable.GradientDrawable).color!!.defaultColor
            val first = CourseCard(a,listOf(original),java.time.ZoneId.systemDefault()) {}
            assertEquals(color(first),color(CourseCard(a,listOf(original.copy(date=date.plusWeeks(1))),java.time.ZoneId.systemDefault()) {}))
            assertNotEquals(color(first),color(CourseCard(a,listOf(original.copy(seriesId="seriesB")),java.time.ZoneId.systemDefault()) {}))
            for (palette in 0..5) for (width in listOf(34,80,120)) for (height in listOf(1,16,48,80,200)) {
                val card = CourseCard(a,listOf(original.copy(seriesId="palette-$palette")),java.time.ZoneId.systemDefault()) {}
                card.measure(android.view.View.MeasureSpec.makeMeasureSpec(a.dp(width),android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(a.dp(height),android.view.View.MeasureSpec.EXACTLY))
                card.layout(0,0,card.measuredWidth,card.measuredHeight)
                for (j in 0 until card.childCount) {
                    val text = card.getChildAt(j) as android.widget.TextView
                    if (text.visibility == android.view.View.VISIBLE) {
                        assertTrue("Width $width height $height text bottom",text.bottom <= card.height-card.paddingBottom)
                        assertTrue(text.right <= card.width-card.paddingRight)
                        val ink = androidx.core.graphics.ColorUtils.setAlphaComponent(text.currentTextColor,(text.alpha*255).toInt())
                        val effective = androidx.core.graphics.ColorUtils.compositeColors(ink,color(card))
                        assertTrue("Text contrast",androidx.core.graphics.ColorUtils.calculateContrast(effective,color(card)) >= 4.5)
                    }
                }
            }
        } }
    }
    @Test fun midnightAndShortIcsInstancesKeepOriginalDetails() {
        open().use { scenario ->
            pick("night.ics"); preview(scenario); onView(withText("确认导入")).perform(click())
            waitFor(scenario) { it.source.state.imported.size == 3 }
            onView(withText("周视图")).perform(click())
            scenario.onActivity { a ->
                fun find(v: android.view.View): PhoneWeekView? {
                    if (v is PhoneWeekView) return v
                    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                    return null
                }
                val week = find(a.window.decorView)!!
                week.getChildAt(1).tag = "test-monday"; week.getChildAt(2).tag = "test-tuesday"
            }
            for (day in listOf("test-monday", "test-tuesday")) {
                onView(allOf(withContentDescription(startsWith("夜间跨日测试")),isDescendantOfA(withTagValue(equalTo(day as Any))))).perform(timeBlockClick())
                onView(withText("夜间跨日测试")).check(matches(isDisplayed()))
                onView(withText("Edit")).perform(scrollTo()).check(matches(isDisplayed()))
                androidx.test.espresso.Espresso.pressBack()
            }
            onView(withContentDescription(startsWith("短时测试"))).perform(timeBlockClick())
            onView(withText("短时测试")).check(matches(isDisplayed()))
            androidx.test.espresso.Espresso.pressBack()
        }
    }
    @Test fun emptyLocalEditorAndRestartPersistence() {
        open().use { s ->
            onView(withText(startsWith("今天没有课程"))).check(matches(isDisplayed()))
            screenshot("empty")
            onView(withText("＋ 课程")).perform(click())
            onView(withHint("Class title")).perform(replaceText("Phone local"), closeSoftKeyboard())
            onView(withText("Save")).perform(click())
            waitFor(s) { it.source.state.entries.size == 1 }
            screenshot("local")
        }
        open().use { s ->
            waitFor(s) { it.source.state.entries.singleOrNull()?.title == "Phone local" }
            onView(withText(startsWith("Phone local"))).perform(scrollTo(), click())
            onView(withText("Edit")).perform(scrollTo(), click())
            onView(withText("All weeks for this timetable item")).perform(click())
            onView(withHint("Course name")).perform(replaceText("Edited local"), closeSoftKeyboard())
            onView(withText("Save")).perform(click())
            waitFor(s) { it.source.state.classOverrides.size == 1 }
        }
        open().use { s ->
            waitFor(s) { it.source.state.classOverrides.singleOrNull()?.title == "Edited local" }
            onView(withText(startsWith("Edited local"))).perform(scrollTo(), click())
            onView(withText("Reset all-weeks edit")).perform(scrollTo(), click())
            onView(withText(startsWith("Phone local"))).check(matches(isDisplayed()))
        }
    }
    @Test fun csvPickerPreviewConfirmationAndRestart() = verifyPicker("csv")
    @Test fun xlsPickerPreviewConfirmationAndRestart() = verifyPicker("xls")
    private fun verifyPicker(format: String) {
        open().use { s ->
            pick("hubei_2026-2027-1.$format"); preview(s)
            s.onActivity { assertTrue(it.source.state.sources.isEmpty()) }
            screenshot("csv-preview")
            onView(withText("确认导入")).perform(click())
            waitFor(s) { it.source.state.imported.size == 131 }
            s.onActivity { assertEquals(13, it.source.state.imported.map { c -> c.seriesId }.distinct().size) }
            onView(withText("周视图")).perform(click())
            repeat(4) { onView(withText("下周 ›")).perform(click()) }
            repeat(8) {
                var hasCard = false
                s.onActivity { a ->
                    fun contains(v: android.view.View): Boolean = v is CourseCard || (v is android.view.ViewGroup && (0 until v.childCount).any { contains(v.getChildAt(it)) })
                    hasCard = contains(a.window.decorView)
                }
                if (!hasCard) onView(withText("下周 ›")).perform(click())
            }
            s.onActivity { a ->
                fun first(v: android.view.View): CourseCard? {
                    if (v is CourseCard) return v
                    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) first(v.getChildAt(i))?.let { return it }
                    return null
                }
                first(a.window.decorView)?.let { it.requestRectangleOnScreen(android.graphics.Rect(0,0,it.width,it.height), true) }
            }
            screenshot("csv-week")
            s.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            Thread.sleep(1000); instrumentation.waitForIdleSync(); screenshot("csv-landscape")
            s.onActivity { a ->
                val scroll = a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll")
                assertTrue("Landscape timetable should occupy most of the window", scroll.height >= ((scroll.parent as android.view.View).height - (scroll.parent as android.view.View).paddingTop - (scroll.parent as android.view.View).paddingBottom) * .60)
            }
            s.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            Thread.sleep(1000); instrumentation.waitForIdleSync()
            onView(withText("日视图")).perform(click())
            onView(withText("日期调整")).perform(click())
            onView(withText("Cancel")).perform(click())
        }
        open().use { s -> waitFor(s) { it.source.state.imported.size == 131 }; screenshot("csv-reopened") }
    }
    @Test fun icsPickerAndInvalidFileLeaveExistingDataIntact() {
        open().use { s ->
            pick("wakeup.ics"); preview(s)
            onView(withText("确认导入")).perform(click())
            waitFor(s) { it.source.state.sources.size == 1 }
            var count = 0
            s.onActivity { count = it.source.state.imported.size; assertTrue(count > 0) }
            screenshot("ics")
            // A fresh intended result takes precedence after reinitialization.
            Intents.release(); Intents.init()
            pick("bad.ics")
            val deadline = System.currentTimeMillis() + 10000
            while (true) {
                try { onView(withText("确定")).check(matches(isDisplayed())); break }
                catch (e: androidx.test.espresso.NoMatchingViewException) { if (System.currentTimeMillis() > deadline) throw e }
                Thread.sleep(40)
            }
            onView(withText("确定")).perform(click())
            s.onActivity { assertEquals(1, it.source.state.sources.size); assertEquals(count, it.source.state.imported.size) }
        }
    }
    private fun spinner(index: Int) = object : androidx.test.espresso.ViewAction {
        override fun getConstraints() = isAssignableFrom(android.widget.Spinner::class.java)
        override fun getDescription() = "Choose spinner $index"
        override fun perform(controller: androidx.test.espresso.UiController, view: android.view.View) {
            (view as android.widget.Spinner).setSelection(index); controller.loopMainThreadUntilIdle()
        }
    }
    @Test fun importedScopesDayAdjustmentReplacementAndDelete() {
        open().use { s ->
            pick("wakeup.ics"); preview(s); onView(withText("确认导入")).perform(click())
            waitFor(s) { it.source.state.sources.size == 1 }
            onView(withText("日视图")).perform(click())
            var title = ""
            var id = ""
            s.onActivity { a ->
                title = a.source.state.imported.minBy { it.start }.title
                id = a.source.state.sources.single().id
            }
            onView(withText(startsWith(title))).perform(scrollTo(), click())
            onView(withText("Edit")).perform(scrollTo(), click())
            onView(withText("This week")).perform(click())
            onView(withHint("Course name")).perform(replaceText("Week only"), closeSoftKeyboard())
            onView(withText("Save")).perform(click())
            onView(withText(startsWith("Week only"))).perform(scrollTo(), click())
            onView(withText("Reset this week")).perform(scrollTo(), click())
            onView(withText(startsWith(title))).perform(scrollTo(), click())
            onView(withText("Edit")).perform(scrollTo(), click())
            onView(withText("All weeks for this timetable item")).perform(click())
            onView(withHint("Course name")).perform(replaceText("All weeks"), closeSoftKeyboard())
            onView(withText("Save")).perform(click())
            waitFor(s) { it.source.state.classOverrides.any { e -> e.anchorDate == null && e.title == "All weeks" } }
            onView(withText("日期调整")).perform(click())
            onView(withContentDescription("Day adjustment mode")).perform(spinner(1))
            onView(withText("Save")).perform(click())
            waitFor(s) { it.source.state.dayAdjustments.singleOrNull()?.mode == "none" }
            onView(withText("日期调整")).perform(click())
            onView(withText("Reset day to normal")).perform(click())
            onView(withText(startsWith("All weeks"))).check(matches(isDisplayed()))
            // Change profile-independent ICS source content while retaining stable UID/DTSTART identity.
            var original = ""
            instrumentation.context.assets.open("wakeup.ics").use { original = it.bufferedReader().readText() }
            val changed = IcsScheduleImporter().parse(ScheduleArtifact("changed.ics", original.replace("LOCATION:", "LOCATION:Updated ").toByteArray()))
            s.onActivity { a -> a.source.import(changed, "Replacement", id) { assertTrue(it.isSuccess) } }
            waitFor(s) { it.source.state.sources.single().filename == "changed.ics" }
            s.onActivity { a -> assertEquals(id, a.source.state.sources.single().id); assertEquals("All weeks", a.source.state.classOverrides.single().title) }
            onView(withText("管理")).perform(click())
            onView(withText("导入来源（替换 / 删除）")).perform(click())
            onView(withText(startsWith("Replacement ·"))).perform(scrollTo(), click())
            onView(withText("替换")).perform(click())
            preview(s)
            onView(withText("确认导入")).perform(click())
            waitFor(s) { it.source.state.sources.single().filename == "wakeup.ics" }
            s.onActivity { a -> assertEquals(id, a.source.state.sources.single().id); assertEquals("All weeks", a.source.state.classOverrides.single().title) }
            onView(withText("管理")).perform(click())
            onView(withText("导入来源（替换 / 删除）")).perform(click())
            onView(withText(startsWith("Replacement ·"))).perform(scrollTo(), click())
            onView(withText("删除")).perform(click())
            onView(withText("删除")).perform(click())
            waitFor(s) { it.source.state.sources.isEmpty() && it.source.state.imported.isEmpty() && it.source.state.classOverrides.isEmpty() }
        }
    }

    @Test fun missingCsvProfileCanBeConfiguredBeforeConfirmation() {
        open().use { s ->
            pick("future.csv"); waitFor(s) { !it.busy }
            onView(withContentDescription("第一周周一 yyyy-MM-dd")).perform(scrollTo(), replaceText("2027-08-30"), closeSoftKeyboard())
            // Keep the final editor/IME geometry stable. Closing IME immediately before the
            // footer tap races MIUI's input-window transition and can consume that tap.
            onView(withContentDescription("分阶段节次")).perform(scrollTo(), replaceText(TermProfileForm.phasesToText(BuiltInProfiles.term2026)))
            instrumentation.waitForIdleSync()
            onView(withContentDescription("分阶段节次")).check { view, error ->
                if (error != null) throw error
                val field = view as android.widget.EditText
                assertEquals(6, field.maxLines)
                val metrics = field.paint.fontMetricsInt
                val fontPadding = (metrics.ascent - metrics.top) + (metrics.bottom - metrics.descent)
                val limit = maxOf(field.minimumHeight, 6 * field.lineHeight + field.compoundPaddingTop + field.compoundPaddingBottom + fontPadding)
                assertTrue("Phase editor height ${field.height}, six-line limit $limit", field.height <= limit)
            }
            onView(withText("保存")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
            s.onActivity { println("PROFILE DIAGNOSTIC ${it.profileSaveStage} busy=${it.busy} profiles=${it.source.state.profiles.keys}"); assertEquals("done", it.profileSaveStage) }
            preview(s)
            s.onActivity { assertTrue(it.source.state.imported.isEmpty()) }
            onView(withText("确认导入")).perform(click())
            waitFor(s) { it.source.state.imported.size == 131 }
            s.onActivity { assertEquals("2027-2028-1", it.source.state.sources.single().termKey); assertEquals(13, it.source.state.imported.map { c -> c.seriesId }.distinct().size) }
        }
        open().use { scenario ->
            waitFor(scenario) { it.source.state.imported.size == 131 }
            scenario.onActivity { assertEquals(13,it.source.state.imported.map { c -> c.seriesId }.distinct().size) }
        }
    }

}
