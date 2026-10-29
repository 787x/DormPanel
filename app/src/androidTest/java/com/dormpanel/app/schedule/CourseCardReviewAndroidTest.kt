package com.dormpanel.app.schedule

import android.view.*
import android.widget.*
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.hamcrest.Matchers.anyOf
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.MainActivity
import com.dormpanel.app.IsolatedDashboardRule
import com.dormpanel.app.R
import com.dormpanel.app.appearance.*
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.dashboard.card.*
import com.dormpanel.app.dashboard.model.*
import com.dormpanel.app.navigation.PanelPage
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.Locale

class CourseCardReviewAndroidTest {
    @get:Rule val isolation=IsolatedDashboardRule()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private fun views(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun model(a: MainActivity)=ViewModelProvider(a)[DashboardViewModel::class.java]
    private fun store(read: () -> ScheduleState)=object : ScheduleStore {
        override fun load(callback: (Result<ScheduleState>)->Unit)=callback(Result.success(read()))
        override fun put(event: CalendarEvent, callback: (Result<Unit>)->Unit)=error("write")
        override fun put(entry: TimetableEntry, callback: (Result<Unit>)->Unit)=error("write")
        override fun deleteEvent(id: String, callback: (Result<Unit>)->Unit)=error("write")
        override fun deleteEntry(id: String, callback: (Result<Unit>)->Unit)=error("write")
        override fun close()=Unit
    }
    private fun fixture(n: Int,date: LocalDate=LocalDate.now())=ScheduleState(entries=(0 until n).flatMap { i -> listOf(date,date.plusDays(1)).map { d ->
        TimetableEntry("$d-$i","课程 $i",d.dayOfWeek.value,0,1439,"教室 $i")
    } })
    private fun await(s: ActivityScenario<MainActivity>) {
        repeat(100) {
            var loaded=false
            s.onActivity { loaded=model(it).stateHolder.state.loaded && model(it).schedule.ready }
            if(loaded) { inst.waitForIdleSync(); return }
            Thread.sleep(50)
        }
        fail("load timeout")
    }
    private fun page(s: ActivityScenario<MainActivity>): PanelPage {
        var page=PanelPage.HOME
        s.onActivity {
            page=MainActivity::class.java.getDeclaredField("currentPage").apply { isAccessible=true }.get(it) as PanelPage
            val root=it.findViewById<ViewGroup>(R.id.page_container)
            val visible=root.getChildAt(root.childCount-1)
            val expected=when(page) {
                PanelPage.HOME -> "DashboardPageView"
                PanelPage.CONTROL_CENTER -> "ControlCenterView"
                PanelPage.APPS -> "AppsPageView"
                PanelPage.CALENDAR -> "SchedulePageView"
                PanelPage.HOME_CONTROL -> "HomeControlView"
            }
            assertEquals(expected,visible.javaClass.simpleName)
            assertTrue(visible.isShown)
        }
        return page
    }
    private fun home(s: ActivityScenario<MainActivity>) {
        s.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        Thread.sleep(350); inst.waitForIdleSync()
        assertEquals(PanelPage.HOME,page(s))
    }
    /** Inject through the real Activity dispatch path, not directly into the course list. */
    private fun swipe(s: ActivityScenario<MainActivity>,list: RecyclerView,dy: Float,dx: Float=0f) {
        var x=0f; var y=0f
        s.onActivity {
            val location=IntArray(2); list.getLocationOnScreen(location)
            x=location[0]+list.width*.5f; y=location[1]+list.height*.5f
        }
        val down=SystemClock.uptimeMillis()
        for(i in 0..12) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),when(i) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE },x+dx*i/12,y+dy*i/12,0)
            inst.sendPointerSync(event); event.recycle(); Thread.sleep(10)
        }
        Thread.sleep(400); inst.waitForIdleSync()
    }
    @Test fun dashboardEdgesColumnsClicksAndDateNavigation() {
        var data=fixture(50)
        ScheduleStores.overrideFactory={ store { data } }
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            await(s)
            s.onActivity {
                val vm=model(it); val h=vm.stateHolder
                h.state.cards.toList().forEach { card -> h.delete(card.id) }
                h.add(vm.catalog.candidates.first { c -> c.providerType=="schedule_recent" })
                val card=h.state.cards.single(); h.move(card.id,0,0); h.resize(card.id,CardSize(8,6))
            }
            Thread.sleep(350); inst.waitForIdleSync()
            fun lists(): List<RecyclerView> {
                var result=emptyList<RecyclerView>()
                s.onActivity { result=views(it.findViewById(R.id.dashboard_grid)).filterIsInstance<RecyclerView>() }
                return result
            }
            var list=lists().first()
            swipe(s,list,-220f) // top inward
            assertEquals(PanelPage.HOME,page(s))
            s.onActivity {
                assertTrue((list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()>0)
                assertEquals(0,(lists()[1].layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
                val col=list.parent as ViewGroup
                assertTrue(col.getChildAt(0) is TextView)
                assertTrue(col.getChildAt(0).height>0)
                list.scrollToPosition(0)
            }
            Thread.sleep(200)
            swipe(s,list,220f) // top outward
            assertEquals(PanelPage.CONTROL_CENTER,page(s)); home(s)
            list=lists()[1]
            swipe(s,list,-220f) // right column inward, independent
            assertEquals(PanelPage.HOME,page(s))
            s.onActivity { list.scrollToPosition(list.adapter!!.itemCount-1) }; Thread.sleep(200)
            swipe(s,list,220f) // bottom inward
            assertEquals(PanelPage.HOME,page(s))
            s.onActivity { list.scrollToPosition(list.adapter!!.itemCount-3) }; Thread.sleep(200)
            swipe(s,list,-220f) // already-owned stream reaches bottom, stays in list
            assertEquals(PanelPage.HOME,page(s))
            s.onActivity { list.scrollToPosition(list.adapter!!.itemCount-1) }; Thread.sleep(200)
            swipe(s,list,-220f) // bottom outward
            assertEquals(PanelPage.APPS,page(s)); home(s)
            list=lists().first()
            swipe(s,list,0f,-220f)
            assertEquals(PanelPage.CALENDAR,page(s)); home(s)
            // A stationary tap opens a course; the preceding swipes did not open a dialog.
            list=lists().first()
            var tapX=0f; var tapY=0f
            s.onActivity {
                val location=IntArray(2); list.getLocationOnScreen(location)
                tapX=location[0]+list.width*.5f; tapY=location[1]+list.height*.5f
            }
            val tapTime=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val event=MotionEvent.obtain(tapTime,SystemClock.uptimeMillis(),action,tapX,tapY,0)
                inst.sendPointerSync(event); event.recycle(); Thread.sleep(30)
            }
            onView(anyOf(withText("Close"),withText("关闭"))).perform(click())
            assertEquals(PanelPage.HOME,page(s))
            // Editing uses the real Dashboard overlay; content cannot take ownership.
            s.onActivity { it.findViewById<View>(R.id.dashboard_edit).performClick() }
            swipe(s,lists().first(),-220f)
            assertEquals(PanelPage.HOME,page(s))
            s.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            Thread.sleep(200)
            // A card date must undo an old browsed week and render the actual current week.
            s.onActivity {
                val vm=model(it); vm.scheduleSession.weekStart=LocalDate.now().minusWeeks(8)
                val card=views(it.findViewById(R.id.dashboard_grid)).filterIsInstance<CourseListCardView>().single()
                (card.getChildAt(0) as TextView).performClick()
            }
            assertEquals(PanelPage.CALENDAR,page(s))
            fun range(date: LocalDate) {
                s.onActivity {
                    val monday=date.minusDays(date.dayOfWeek.value-1L)
                    assertTrue(views(it.findViewById(R.id.page_container)).filterIsInstance<TextView>().any { v -> v.text.toString()=="$monday – ${monday.plusDays(6)}" })
                }
            }
            range(LocalDate.now())
            for(date in listOf(LocalDate.of(2026,12,28),LocalDate.of(2027,1,1),LocalDate.of(2027,2,1))) {
                home(s)
                s.onActivity { model(it).scheduleSession.select(date.minusDays(1)); it.openScheduleDate(date) }
                range(date)
            }
            home(s)
            // Empty, non-scrollable card still permits vertical home navigation.
            s.onActivity { data=ScheduleState(); model(it).schedule.reload() }
            Thread.sleep(250)
            swipe(s,lists().first(),220f)
            assertEquals(PanelPage.CONTROL_CENTER,page(s)); home(s)
        }
    }
    @Test fun snapshotRenderReuseLargeListsAndTheme() {
        var zone=ZoneId.of("Asia/Shanghai")
        var now=LocalDate.of(2026,10,28).atTime(23,59,59).atZone(zone).toInstant()
        var reads=0
        val clock=object : ScheduleClock {
            override fun instant(): Instant { reads++; return now.also { now=now.plusMillis(1) } }
            override fun zone()=zone
            override fun locale()=Locale.SIMPLIFIED_CHINESE
        }
        var data=fixture(50,LocalDate.of(2026,10,28))
        lateinit var source: ScheduleSource
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity {
                source=ScheduleSource(store { data },clock); source.reload()
                reads=0
                val snapshot=CourseCardSnapshot.capture(source)
                assertEquals(1,reads)
                now=LocalDate.of(2026,10,29).atStartOfDay(zone).toInstant()
                val projected=CourseCardDays.get(source,snapshot)
                assertEquals(snapshot.today,projected[0].date)
                assertEquals(snapshot.today.plusDays(1),projected[1].date)
                assertEquals(1,reads)
            }
            s.onActivity {
                val appearance=AppearanceController(object: AppearanceStore {
                    override fun read()=AppearanceState(ThemeMode.DARK)
                    override fun write(state: AppearanceState)=Unit
                })
                val provider=CourseListCardProvider(false,source,appearance)
                val view=provider.createView(it) as CourseListCardView
                now=LocalDate.of(2026,10,28).atTime(23,59,59,999000000).atZone(zone).toInstant()
                reads=0
                provider.bind(view,PlacedCard("midnight",provider.typeKey,0,0,CardSize(4,4)),CardInteractionScope(true){})
                assertEquals(1,reads)
                assertTrue((view.getChildAt(0) as TextView).text.contains("10/28"))
                assertTrue(views(view).filterIsInstance<TextView>().none { v -> v.text.contains("10/29") })
                source.refresh() // unattached view is not subscribed
                assertEquals(1,view.fullRenderCount)
                now=LocalDate.of(2026,10,29).atStartOfDay(zone).toInstant()
            }
            for(n in listOf(50,200)) {
                lateinit var view: CourseListCardView
                lateinit var provider: CourseListCardProvider
                lateinit var appearance: AppearanceController
                s.onActivity {
                    data=fixture(n,LocalDate.of(2026,10,29)); source.reload()
                    appearance=AppearanceController(object: AppearanceStore {
                        override fun read()=AppearanceState(ThemeMode.DARK)
                        override fun write(state: AppearanceState)=Unit
                    })
                    provider=CourseListCardProvider(true,source,appearance)
                    view=provider.createView(it) as CourseListCardView
                    val start=SystemClock.uptimeMillis()
                    provider.bind(view,PlacedCard("perf",provider.typeKey,0,0,CardSize(4,4)),CardInteractionScope(true){})
                    assertEquals(1,view.fullRenderCount)
                    android.util.Log.i("PR74Review","count=$n firstBindMs=${SystemClock.uptimeMillis()-start}")
                    it.setContentView(FrameLayout(it).apply { addView(view,FrameLayout.LayoutParams(600,480)) })
                }
                Thread.sleep(300); inst.waitForIdleSync()
                s.onActivity {
                    val count=view.fullRenderCount
                    val lists=views(view).filterIsInstance<RecyclerView>()
                    assertEquals(2,lists.size)
                    assertEquals(n,lists[0].adapter!!.itemCount)
                    assertTrue("Visible holders must not scale with dataset",view.holdersCreated<n)
                    val header=(lists[0].parent as ViewGroup).getChildAt(0)
                    lists[0].scrollToPosition(n-1)
                    provider.bind(view,PlacedCard("perf",provider.typeKey,0,0,CardSize(4,4)),CardInteractionScope(true){})
                    assertEquals(count,view.fullRenderCount)
                    appearance.setTheme(ThemeMode.LIGHT); view.applyAppearance(appearance.state)
                    assertEquals(count,view.fullRenderCount)
                    assertEquals(PanelPalette.forMode(ThemeMode.LIGHT).text,(view.getChildAt(0) as TextView).currentTextColor)
                    assertSame(header,(lists[0].parent as ViewGroup).getChildAt(0))
                }
                Thread.sleep(200); inst.waitForIdleSync()
                s.onActivity {
                    val list=views(view).filterIsInstance<RecyclerView>().first()
                    assertTrue((list.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()>=n-1)
                    android.util.Log.i("PR74Review","count=$n fullRenders=${view.fullRenderCount} holders=${view.holdersCreated}")
                    assertSame(CourseCardDays.get(source)[0].all,CourseCardDays.get(source)[0].all)
                    val before=view.fullRenderCount
                    source.refresh(); assertEquals(before,view.fullRenderCount)
                    data=data.copy()
                    source.reload()
                }
                Thread.sleep(200); inst.waitForIdleSync()
                s.onActivity {
                    val list=views(view).filterIsInstance<RecyclerView>().first()
                    assertTrue("Data refresh preserves scroll",(list.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()>=n-1)
                    val before=view.fullRenderCount
                    now=now.plusDaysForTest(); source.refresh()
                    assertTrue(view.fullRenderCount>before)
                    assertTrue((view.getChildAt(0) as TextView).text.contains("10/30"))
                    now=LocalDate.of(2026,10,29).atStartOfDay(zone).toInstant()
                    zone=ZoneId.of("America/Los_Angeles"); source.refresh()
                    assertTrue((view.getChildAt(0) as TextView).text.contains("10/28"))
                    zone=ZoneId.of("Asia/Shanghai"); source.refresh()
                    assertTrue((view.getChildAt(0) as TextView).text.contains("10/29"))
                }
            }
            s.onActivity { it.setContentView(FrameLayout(it)) }
            val listeners=ScheduleSource::class.java.getDeclaredField("listeners").apply { isAccessible=true }
            s.onActivity { assertEquals(0,(listeners.get(source) as Set<*>).size); source.close() }
        }
    }
    @Test fun visibleEndAndMidnightTimersUseRenderedBoundary() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            for(midnight in listOf(false,true)) {
                val zone=ZoneId.of("Asia/Shanghai")
                val date=LocalDate.of(2026,10,29)
                val base=(if(midnight) date.atTime(23,59,59,500000000) else date.atTime(10,0,59,500000000)).atZone(zone).toInstant()
                val started=SystemClock.elapsedRealtime()
                val clock=object : ScheduleClock {
                    override fun instant()=base.plusMillis(SystemClock.elapsedRealtime()-started)
                    override fun zone()=zone
                    override fun locale()=Locale.SIMPLIFIED_CHINESE
                }
                lateinit var source: ScheduleSource
                lateinit var view: CourseListCardView
                s.onActivity {
                    val data=ScheduleState(entries=listOf(TimetableEntry("timer","边界课程",date.dayOfWeek.value,0,601,"A")))
                    source=ScheduleSource(store { data },clock); source.reload()
                    val appearance=AppearanceController(object: AppearanceStore {
                        override fun read()=AppearanceState(ThemeMode.DARK)
                        override fun write(state: AppearanceState)=Unit
                    })
                    val provider=CourseListCardProvider(true,source,appearance)
                    view=provider.createView(it) as CourseListCardView
                    provider.bind(view,PlacedCard("timer",provider.typeKey,0,0,CardSize(4,4)),CardInteractionScope(true){})
                    it.setContentView(FrameLayout(it).apply { addView(view,FrameLayout.LayoutParams(600,480)) })
                }
                Thread.sleep(800); inst.waitForIdleSync()
                s.onActivity {
                    val labels=views(view).filterIsInstance<TextView>().map { v -> v.text.toString() }
                    if(midnight) {
                        assertTrue((view.getChildAt(0) as TextView).text.contains("10/30"))
                        assertTrue(labels.any { v -> v.contains("10/31") })
                        assertFalse(labels.any { v -> v.contains("10/29") })
                    } else {
                        assertTrue(labels.any { v -> v.contains("课程已结束") || v.contains("courses finished") })
                        assertFalse(labels.any { v -> v=="边界课程" })
                    }
                    it.setContentView(FrameLayout(it))
                }
                val before=view.fullRenderCount
                Thread.sleep(150)
                s.onActivity { source.refresh(); assertEquals(before,view.fullRenderCount); source.close() }
            }
        }
    }
    private fun Instant.plusDaysForTest()=plusSeconds(86400)
}
