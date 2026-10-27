package com.dormpanel.phone

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import com.dormpanel.app.schedule.testing.SwipeEvents
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.UUID

class TimetableSwipeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("com.dormpanel.phone.TimetableActivity",Context.MODE_PRIVATE)
    private val week = LocalDate.of(2026,10,26)
    private var fixture = ScheduleState()
    private val profile get() = BuiltInProfiles.term2026.copy(timezone=ZoneId.systemDefault().id)
    @Before fun setup() {
        require(context.packageName.endsWith(".testbed"))
        prefs.edit().clear().putString("date","2026-10-27").putString("term",profile.termKey).commit()
        TermScheduleProfileStore.overrideNamespace = "pr64-${UUID.randomUUID()}"
        TermScheduleProfileStore(context).put(profile)
        WebDavSettings.overrideNamespace = "pr64-${UUID.randomUUID()}"
        fixture = ScheduleState(entries=(1..7).flatMap { day -> (0..19).map { n ->
            TimetableEntry("$day-$n","测试课程 $day/$n",day,n*70,45+n*70,"教学楼 A201")
        } },profiles=mapOf(profile.termKey to profile))
        ScheduleStores.overrideFactory = { object : ScheduleStore {
            override fun load(callback: (Result<ScheduleState>) -> Unit) = callback(Result.success(fixture))
            override fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit) = error("Navigation must not write")
            override fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit) = error("Navigation must not write")
            override fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit) = error("Navigation must not write")
            override fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit) = error("Navigation must not write")
            override fun close() = Unit
        } }
    }
    @After fun restore() {
        ScheduleStores.overrideFactory = null
        TermScheduleProfileStore.overrideNamespace?.let { context.getSharedPreferences(it,0).edit().clear().commit() }
        TermScheduleProfileStore.overrideNamespace = null; WebDavSettings.overrideNamespace = null
        prefs.edit().clear().commit()
        instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }
    }
    private fun open() = launchPhoneTestActivity().also { s ->
        s.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        idle()
    }
    private fun idle() { instrumentation.waitForIdleSync(); Thread.sleep(500); instrumentation.waitForIdleSync() }
    private fun swipe(a: TimetableActivity) = SwipeEvents.views(a.window.decorView).filterIsInstance<TimetableSwipeLayout>().single()
    private fun grid(a: TimetableActivity) = SwipeEvents.views(a.window.decorView).filterIsInstance<PhoneWeekView>().single()
    private fun selected(a: TimetableActivity): LocalDate = TimetableActivity::class.java.getDeclaredField("selected").let { it.isAccessible=true; it.get(a) as LocalDate }
    private fun seed(date: String, weekView: Boolean, enabled: Boolean = true) { prefs.edit().putString("date",date).putBoolean("weekView",weekView).putBoolean("swipeDates",enabled).commit() }
    private fun screenshot(name: String) {
        idle()
        val b = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null),"pr64-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }; b.recycle()
    }
    @Test fun dayBoundariesSettingsButtonsAndRecreation() {
        seed("2026-12-31",false,false)
        open().use { s ->
            s.onActivity { a -> assertFalse(swipe(a).swipeEnabled); SwipeEvents.drag(swipe(a),-swipe(a).width*.4f,cancel=true) }
            idle(); s.onActivity { assertEquals(LocalDate.of(2026,12,31),selected(it)) }
            onView(withText("管理")).perform(click()); onView(withText("课表显示设置")).perform(click())
            onView(withText("横划切换日期")).perform(click()); onView(withText("保存")).perform(click())
            s.onActivity { a -> SwipeEvents.drag(swipe(a),-swipe(a).width*.4f,during={ assertTrue(swipe(a).getChildAt(0).translationX<0) }) }
            idle(); s.onActivity { assertEquals(LocalDate.of(2027,1,1),selected(it)); assertEquals(0f,swipe(it).getChildAt(0).translationX,0f) }
            s.recreate(); idle()
            s.onActivity { a -> assertTrue(swipe(a).swipeEnabled); assertEquals(LocalDate.of(2027,1,1),selected(a)); SwipeEvents.drag(swipe(a),swipe(a).width*.4f) }
            idle(); s.onActivity { assertEquals(LocalDate.of(2026,12,31),selected(it)) }
            onView(withText("后一天 ›")).perform(click()); s.onActivity { assertEquals(LocalDate.of(2027,1,1),selected(it)) }
            onView(withText("‹ 前一天")).perform(click())
            onView(withText("今天")).perform(click()); s.onActivity { assertEquals(it.source.clock.today(),selected(it)) }
            assertTrue(prefs.getBoolean("swipeDates",false))
        }
        for (date in listOf("2026-10-31","2026-11-01")) {
            seed(date,false)
            open().use { s ->
                s.onActivity { a -> SwipeEvents.drag(swipe(a),-swipe(a).width*.4f) }; idle()
                s.onActivity { assertEquals(LocalDate.parse(date).plusDays(1),selected(it)) }
            }
        }
    }
    @Test fun nativeTouchAnimationAndDateContentStayTogether() {
        seed("2026-10-27",true)
        prefs.edit().putBoolean("periodMode",true).commit()
        open().use { s ->
            val target=androidx.test.espresso.matcher.ViewMatchers.withTagValue(org.hamcrest.Matchers.equalTo("timetable-swipe" as Any))
            onView(target).perform(SwipeEvents.nativeDrag(.7f,.25f)); idle()
            s.onActivity { assertEquals(LocalDate.of(2026,11,3),selected(it)); assertEquals(0f,swipe(it).getChildAt(0).translationX,0f) }
            onView(target).perform(SwipeEvents.nativeDrag(.5f,.42f)); idle()
            s.onActivity { assertEquals(LocalDate.of(2026,11,3),selected(it)) }
            onView(target).perform(SwipeEvents.nativeDrag(.3f,.75f)); idle()
            s.onActivity { assertEquals(LocalDate.of(2026,10,27),selected(it)) }
            onView(withText("日视图")).perform(click())
            onView(target).perform(SwipeEvents.nativeDrag(.7f,.25f)); idle()
            s.onActivity { assertEquals(LocalDate.of(2026,10,28),selected(it)); assertTrue(SwipeEvents.views(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text.startsWith("2026-10-28") }) }
        }
    }
    @Test fun weekModesAnchorsCancellationRefreshAndVerticalScroll() {
        for (five in listOf(false,true)) for (periods in listOf(false,true)) {
            seed("2026-10-27",true)
            prefs.edit().putBoolean("periodMode",periods).putBoolean("weekdaysOnly",five).commit()
            open().use { s ->
                var anchor = 0f
                s.onActivity { a ->
                    val sc = SwipeEvents.views(a.window.decorView).filterIsInstance<ScrollView>().single()
                    sc.scrollTo(0,grid(a).offsetAt(650f)); anchor=grid(a).minuteAt(sc.scrollY)
                    SwipeEvents.drag(swipe(a),-swipe(a).width*.4f)
                }; idle()
                s.onActivity { a ->
                    assertEquals(LocalDate.of(2026,11,3),selected(a)); assertEquals(if(five) 5 else 7,grid(a).dayCount); assertEquals(periods,grid(a).usesPeriods)
                    val sc = SwipeEvents.views(a.window.decorView).filterIsInstance<ScrollView>().single()
                    assertEquals(anchor,grid(a).minuteAt(sc.scrollY),3f)
                    SwipeEvents.drag(swipe(a),swipe(a).width*.4f)
                }; idle()
                s.onActivity { a -> assertEquals(LocalDate.of(2026,10,27),selected(a)) }
                for (kind in 0..4) {
                    s.onActivity { a -> val v=swipe(a)
                        when(kind) {
                            0 -> SwipeEvents.drag(v,-v.width*.08f,during={ assertTrue(v.getChildAt(0).translationX<0) })
                            1 -> SwipeEvents.drag(v,-v.width*.4f,cancel=true)
                            2 -> SwipeEvents.drag(v,-v.width*.3f,-v.width*.3f)
                            3 -> SwipeEvents.multiTouch(v)
                            4 -> SwipeEvents.drag(v,-v.width*.4f,during={ a.source.refresh() })
                        }
                    }; idle()
                    s.onActivity { a -> assertEquals("kind=$kind",LocalDate.of(2026,10,27),selected(a)); assertEquals(0f,swipe(a).getChildAt(0).translationX,0f) }
                }
                var old = 0
                s.onActivity { a ->
                    val sc=SwipeEvents.views(a.window.decorView).filterIsInstance<ScrollView>().single(); sc.scrollTo(0,0); old=sc.scrollY
                    SwipeEvents.drag(swipe(a),0f,-a.dp(90).toFloat(),y=swipe(a).height*.7f)
                }; idle()
                s.onActivity { a -> val sc=SwipeEvents.views(a.window.decorView).filterIsInstance<ScrollView>().single(); assertTrue("five=$five periods=$periods y=${sc.scrollY} old=$old height=${sc.height} content=${sc.getChildAt(0).height}",sc.scrollY>old) }
                // A quick, unambiguous fling commits even below the distance threshold.
                s.onActivity { a -> SwipeEvents.drag(swipe(a),-a.dp(65).toFloat(),duration=40) }; idle()
                s.onActivity { assertEquals(LocalDate.of(2026,11,3),selected(it)); assertEquals(fixture.entries,it.source.state.entries) }
                s.recreate(); idle(); s.onActivity { assertEquals(LocalDate.of(2026,11,3),selected(it)); assertEquals(periods,grid(it).usesPeriods) }
            }
        }
    }
    @Test fun periodGutterComparableScreenshotsThemesFontAndPhaseFallback() {
        seed("2026-10-27",true)
        prefs.edit().putBoolean("periodMode",true).commit()
        instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO) }
        open().use { s ->
            var original: View? = null
            s.onActivity { a ->
                val g=grid(a); val gutter=g.getChildAt(0) as FrameLayout; assertEquals(a.dp(32),gutter.width)
                original = gutter.getChildAt(0)
                // Exact pre-PR64 label construction on the same grid/projection for a comparable before.
                gutter.removeAllViews()
                g.axes.first().labels.forEach { (minute,label) -> gutter.addView(TextView(a).apply { text=label; textSize=12f; includeFontPadding=false },
                    FrameLayout.LayoutParams(-1,a.dp(24)).apply { topMargin=g.offsetAt(minute.toFloat()) }) }
            }
            screenshot("phone-axis-before-light-seven")
            s.onActivity { a -> a.source.refresh() }; idle()
            screenshot("phone-axis-after-light-seven")
            for (dark in listOf(false,true)) {
                instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(if(dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO) }; idle()
                for (five in listOf(false,true)) {
                    if (five) onView(withText("七天 · 切换五天")).perform(click())
                    for (scale in listOf(1f,1.3f,1.6f)) s.onActivity { a ->
                        val ctx=a.createConfigurationContext(Configuration(a.resources.configuration).apply { fontScale=scale })
                        val g=PhoneWeekView(ctx,ScheduleProjection.week(a.source.state,week,a.source.clock.zone()),week,a.source.clock.zone(),{},if(five) 5 else 7,true,profile)
                        g.measure(View.MeasureSpec.makeMeasureSpec(a.dp(360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)); g.layout(0,0,g.measuredWidth,g.measuredHeight)
                        assertTrue(g.usesPeriods); val gutter=g.getChildAt(0) as FrameLayout; assertEquals(a.dp(32),gutter.width)
                        assertEquals(profile.phaseOn(week)!!.periods.size,gutter.childCount)
                        for (label in SwipeEvents.views(gutter).filterIsInstance<TextView>()) { assertTrue(label.right<=a.dp(32)); assertTrue("scale=$scale label=${label.text} measured=${label.paint.measureText(label.text.toString())} width=${label.width}",label.paint.measureText(label.text.toString())<=label.width); assertTrue(label.bottom<=(label.parent as View).height) }
                        assertTrue(SwipeEvents.views(gutter).any { it.contentDescription?.contains("08:00") == true })
                    }
                    screenshot("phone-axis-after-${if(dark) "dark" else "light"}-${if(five) "five" else "seven"}")
                }
                onView(withText("五天 · 切换七天")).perform(click())
            }
            s.onActivity { a ->
                val mixed=profile.copy(phases=profile.phases+SchedulePhase(week.plusDays(2),profile.phaseOn(week)!!.periods.map { it.copy(start=it.start.plusMinutes(5),end=it.end.plusMinutes(5)) }))
                val g=PhoneWeekView(a,ScheduleProjection.week(a.source.state,week,a.source.clock.zone()),week,a.source.clock.zone(),{},7,true,mixed)
                assertFalse(g.usesPeriods); assertEquals(fixture.entries,a.source.state.entries)
                assertNotNull(original)
            }
        }
    }
    @Test fun actualCourseDispatchCancelsClickAndPreservesNearbyShortAndOverlapSelection() {
        seed("2026-10-27",true)
        open().use { s ->
            var chosen: ClassOccurrence? = null
            var navigations = 0
            lateinit var host: TimetableSwipeLayout
            lateinit var g: PhoneWeekView
            val zone=ZoneId.systemDefault()
            fun item(id: String,start: Int,end: Int) = ClassOccurrence(TimetableEntry(id,id,1,start,end),week,
                week.atStartOfDay(zone).plusMinutes(start.toLong()).toInstant(),week.atStartOfDay(zone).plusMinutes(end.toLong()).toInstant())
            val short=item("一分钟",600,601)
            s.onActivity { a ->
                g=PhoneWeekView(a,listOf(item("普通课程",480,530),short,item("重叠A",660,720),item("重叠B",690,750)),week,zone,{ chosen=it })
                host=TimetableSwipeLayout(a) { navigations++ }.apply { swipeEnabled=true; addView(ScrollView(a).apply { addView(g) }) }
                a.setContentView(host)
            }; idle()
            fun coords(minute: Float): Pair<Float,Float> {
                val col=g.getChildAt(1); val xy=IntArray(2); col.getLocationOnScreen(xy)
                val origin=IntArray(2); host.getLocationOnScreen(origin)
                return (xy[0]-origin[0]+col.width/2f) to (xy[1]-origin[1]+g.offsetAt(minute)).toFloat()
            }
            s.onActivity {
                val (x,y)=coords(500f)
                SwipeEvents.drag(host,-host.width*.35f,x=x,y=y)
            }; idle()
            assertNull(chosen); assertEquals(1,navigations)
            s.onActivity { val (x,y)=coords(500f); SwipeEvents.drag(host,0f,duration=80,x=x,y=y) }; idle()
            assertEquals("普通课程",chosen?.entry?.title); chosen=null
            s.onActivity { val (x,y)=coords(600f); SwipeEvents.drag(host,0f,duration=80,x=x,y=y+12*context.resources.displayMetrics.density) }; idle()
            assertSame(short,chosen); chosen=null
            s.onActivity { val (x,y)=coords(700f); SwipeEvents.drag(host,0f,duration=80,x=x,y=y) }; idle()
            onView(withText(org.hamcrest.Matchers.startsWith("重叠B\n"))).perform(click()); assertEquals("重叠B",chosen?.entry?.title)
            chosen=null
            s.onActivity { val (x,y)=coords(500f); SwipeEvents.drag(host,-host.width*.08f,x=x,y=y) }; idle()
            assertNull(chosen); assertEquals(1,navigations)
            s.onActivity { assertEquals(0f,host.getChildAt(0).translationX,0f) }
            // Grab an arrival before its posted animator runs, and commit in the same UI turn.
            // A stale arrival runnable must not replace the new outgoing completion callback.
            s.onActivity {
                host.arrive(1)
                val (x,y)=coords(500f)
                SwipeEvents.drag(host,-host.width*.35f,x=x,y=y)
            }; idle()
            assertEquals(2,navigations); assertNull(chosen)
        }
    }
}
