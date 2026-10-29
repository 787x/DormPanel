package com.dormpanel.app.schedule

import android.graphics.Bitmap
import android.view.MotionEvent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.IsolatedDashboardRule
import com.dormpanel.app.MainActivity
import com.dormpanel.app.appearance.*
import com.dormpanel.app.dashboard.card.*
import com.dormpanel.app.dashboard.model.*
import org.junit.*
import org.junit.Assert.*
import java.time.*

class CourseListCardsAndroidTest {
    @get:Rule val isolation = IsolatedDashboardRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun descendants(v: View): List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    @Test fun dashboardRegistrationCollisionAndRoomRestore() {
        var saved=emptyList<PlacedCard>()
        fun model(activity: MainActivity)=androidx.lifecycle.ViewModelProvider(activity)[com.dormpanel.app.dashboard.DashboardViewModel::class.java]
        fun loaded(scenario: ActivityScenario<MainActivity>) {
            var ready=false
            repeat(100) {
                scenario.onActivity { ready=model(it).stateHolder.state.loaded }
                if(ready) return
                Thread.sleep(50)
            }
            assertTrue(ready)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            loaded(scenario)
            scenario.onActivity { activity ->
                val vm=model(activity); val holder=vm.stateHolder
                holder.state.cards.toList().forEach { holder.delete(it.id) }
                for(key in listOf("calendar","timetable","schedule_today","schedule_today","schedule_recent")) {
                    val candidate=vm.catalog.candidates.first { it.providerType==key }
                    assertTrue(holder.add(candidate) is com.dormpanel.app.dashboard.layout.LayoutMutationResult.Success)
                }
                val today=holder.state.cards.first { it.providerType=="schedule_today" }
                val before=holder.state.cards
                assertFalse(holder.resize(today.id,CardSize(8,6)) is com.dormpanel.app.dashboard.layout.LayoutMutationResult.Success)
                assertEquals(before,holder.state.cards)
                assertEquals(2,holder.state.cards.count { it.providerType=="schedule_today" })
                holder.state.cards.filter { it.id!=today.id }.forEach { holder.delete(it.id) }
                assertTrue(holder.move(today.id,0,0) is com.dormpanel.app.dashboard.layout.LayoutMutationResult.Success)
                assertTrue(holder.resize(today.id,CardSize(8,6)) is com.dormpanel.app.dashboard.layout.LayoutMutationResult.Success)
                assertTrue(holder.resize(today.id,CardSize(2,1)) is com.dormpanel.app.dashboard.layout.LayoutMutationResult.Success)
                assertTrue(holder.move(today.id,4,3) is com.dormpanel.app.dashboard.layout.LayoutMutationResult.Success)
                saved=holder.state.cards
            }
            Thread.sleep(500)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            loaded(scenario)
            scenario.onActivity { assertEquals(saved,model(it).stateHolder.state.cards) }
        }
    }
    @Test fun measuredSizesThemesScrollAndRefresh() {
        val zone=ZoneId.of("Asia/Shanghai")
        var now=LocalDate.of(2026,10,28).atTime(7,0).atZone(zone).toInstant()
        val clock=object: ScheduleClock {
            override fun instant()=now
            override fun zone()=zone
            override fun locale()=java.util.Locale.SIMPLIFIED_CHINESE
        }
        val data=ScheduleState(entries=(0..11).flatMap { i -> listOf(3,4).map { d -> TimetableEntry("$d-$i","测试课程 $i · 有效课程名称",d,480+i*60,525+i*60,"教学楼 A${i+100}") } })
        lateinit var source: ScheduleSource
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                source=ScheduleSource(object: ScheduleStore {
                    override fun load(callback: (Result<ScheduleState>)->Unit)=callback(Result.success(data))
                    override fun put(event: CalendarEvent, callback: (Result<Unit>)->Unit)=error("write")
                    override fun put(entry: TimetableEntry, callback: (Result<Unit>)->Unit)=error("write")
                    override fun deleteEvent(id: String, callback: (Result<Unit>)->Unit)=error("write")
                    override fun deleteEntry(id: String, callback: (Result<Unit>)->Unit)=error("write")
                    override fun close()=Unit
                },clock);source.reload()
            }
            for(theme in listOf(ThemeMode.DARK,ThemeMode.LIGHT)) for(recent in listOf(false,true)) {
                lateinit var view: CourseListCardView
                lateinit var provider: CourseListCardProvider
                var claims=0
                scenario.onActivity { activity ->
                    val appearance=AppearanceController(object: AppearanceStore {
                        override fun read()=AppearanceState(theme)
                        override fun write(state: AppearanceState)=Unit
                    })
                    provider=CourseListCardProvider(recent,source,appearance)
                    for(w in 2..8) for(h in 1..6) assertTrue(provider.sizePolicy.allows(CardSize(w,h)))
                    val localized=android.view.ContextThemeWrapper(activity,com.dormpanel.app.R.style.Theme_DormPanel).apply {
                        applyOverrideConfiguration(android.content.res.Configuration(activity.resources.configuration).apply { setLocale(java.util.Locale.SIMPLIFIED_CHINESE) })
                    }
                    view=provider.createView(localized) as CourseListCardView
                    activity.setContentView(FrameLayout(activity).apply { setBackgroundColor(PanelPalette.forMode(theme).background); addView(view) })
                    activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                }
                for(size in listOf(CardSize(2,1),CardSize(2,3),CardSize(4,2),CardSize(4,4),CardSize(6,3),CardSize(8,6))) {
                    scenario.onActivity {
                        provider.bind(view,PlacedCard("test",provider.typeKey,0,0,size),CardInteractionScope(true){ claims++ })
                        view.layoutParams=FrameLayout.LayoutParams(size.columnSpan*150,size.rowSpan*120)
                    }
                    instrumentation.waitForIdleSync()
                    Thread.sleep(250)
                    scenario.onActivity {
                        assertTrue("Header must be measured after resize",view.getChildAt(0).height>0)
                        val scrolls=descendants(view).filterIsInstance<ScrollView>()
                        assertEquals(if(recent && size.columnSpan>=4) 2 else 1,scrolls.size)
                        assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.contains("测试课程") })
                        val scroll=scrolls.first()
                        val stamp=SystemClock.uptimeMillis()
                        fun touch(action: Int,x: Float,y: Float,time: Long) {
                            val event=MotionEvent.obtain(stamp,time,action,x,y,0)
                            scroll.dispatchTouchEvent(event); event.recycle()
                        }
                        val scrollable=scroll.canScrollVertically(1) || scroll.canScrollVertically(-1)
                        val before=claims
                        touch(MotionEvent.ACTION_DOWN,60f,80f,stamp)
                        touch(MotionEvent.ACTION_MOVE,60f,20f,stamp+40)
                        touch(MotionEvent.ACTION_UP,60f,20f,stamp+80)
                        if(scrollable) assertTrue("Vertical stream must claim page gesture",claims>before)
                        else assertEquals("Non-scrolling area preserves page navigation",before,claims)
                        val after=claims
                        touch(MotionEvent.ACTION_DOWN,60f,60f,stamp+100)
                        touch(MotionEvent.ACTION_MOVE,140f,60f,stamp+140)
                        touch(MotionEvent.ACTION_UP,140f,60f,stamp+180)
                        assertEquals("Horizontal stream stays available to home",after,claims)
                        scroll.scrollTo(0,100)
                        if(scrolls.size==2) assertEquals(0,scrolls[1].scrollY)
                        view.visibility=View.VISIBLE
                        view.alpha=1f
                        repeat(2) {
                            view.forceLayout()
                            view.measure(View.MeasureSpec.makeMeasureSpec(size.columnSpan*150,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size.rowSpan*120,View.MeasureSpec.EXACTLY))
                            view.layout(0,0,size.columnSpan*150,size.rowSpan*120)
                        }
                        assertTrue(view.getChildAt(0).height>0)
                        val bitmap=Bitmap.createBitmap(1280,800,Bitmap.Config.ARGB_8888)
                        val canvas=android.graphics.Canvas(bitmap)
                        canvas.drawColor(PanelPalette.forMode(theme).background)
                        view.draw(canvas)
                        java.io.File(it.getExternalFilesDir(null),"pr74-${provider.typeKey}-${size.columnSpan}x${size.rowSpan}-$theme-render.png").outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG,100,out) }; bitmap.recycle()
                    }
                    instrumentation.waitForIdleSync()
                    Thread.sleep(250)
                    val actual=instrumentation.uiAutomation.takeScreenshot()
                    java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"pr74-${provider.typeKey}-${size.columnSpan}x${size.rowSpan}-$theme-screen.png").outputStream().use { out -> actual.compress(Bitmap.CompressFormat.PNG,100,out) }; actual.recycle()
                }
                scenario.onActivity {
                    provider.bind(view,PlacedCard("test",provider.typeKey,0,0,CardSize(4,4)),CardInteractionScope(false){ fail("Edit mode claimed") })
                    descendants(view).filterIsInstance<TextView>().first().performClick()
                    provider.bind(view,PlacedCard("test",provider.typeKey,0,0,CardSize(4,4)),CardInteractionScope(true){ claims++ })
                    now=now.plusSeconds(60); source.reload()
                }
            }
            scenario.onActivity { source.close() }
        }
    }
}
