package com.dormpanel.phone
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.concurrent.*
class CollectionWidgetTest {
    @Test fun launcherRecentScrollThenClickTomorrow() {
        val i=InstrumentationRegistry.getInstrumentation(); val c=i.targetContext
        check(c.packageName.endsWith(".pr72testbed"))
        fun nodes(node: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> =
            if(node==null) emptyList() else listOf(node)+(0 until node.childCount).flatMap { nodes(node.getChild(it)) }
        val info=i.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        i.uiAutomation.serviceInfo=info
        var found=i.uiAutomation.rootInActiveWindow
        val rootDeadline=System.currentTimeMillis()+4000
        while(found==null && System.currentTimeMillis()<rootDeadline) { Thread.sleep(50); found=i.uiAutomation.rootInActiveWindow }
        val root=checkNotNull(found)
        check(root.packageName.toString().contains("launcher",true) || root.packageName=="com.miui.home")
        val list=nodes(root).first { it.viewIdResourceName?.endsWith("collection_list")==true }
        val bounds=android.graphics.Rect(); list.getBoundsInScreen(bounds)
        val down=android.os.SystemClock.uptimeMillis()
        fun input(action: Int,x: Float,y: Float) {
            val e=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0)
            e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN; i.uiAutomation.injectInputEvent(e,true); e.recycle()
        }
        val x=bounds.centerX().toFloat(); val y=(bounds.bottom-15).toFloat()
        input(0,x,y)
        for(n in 1..10) { input(2,x,y-n*(bounds.height()-30)/10); Thread.sleep(40) }
        input(1,x,(bounds.top+15).toFloat()); Thread.sleep(600)
        assertEquals(root.packageName,i.uiAutomation.rootInActiveWindow.packageName)
        val cell=nodes(i.uiAutomation.rootInActiveWindow).first { it.viewIdResourceName?.endsWith("cell_1")==true }
        assertTrue(cell.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        val until=System.currentTimeMillis()+5000
        var selected: LocalDate?=null
        while(selected==null && System.currentTimeMillis()<until) {
            i.runOnMainSync {
                val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<TimetableActivity>().firstOrNull()
                if(activity!=null) selected=TimetableActivity::class.java.getDeclaredField("selected").apply { isAccessible=true }.get(activity) as LocalDate
            }; Thread.sleep(50)
        }
        assertEquals(LocalDate.now().plusDays(1),selected)
    }
    @Test fun pinOnly() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        check(c.packageName.endsWith(".pr72testbed"))
        val cls=if(InstrumentationRegistry.getArguments().getString("widget")=="compact") TodayWidgetProvider::class.java else WideTodayWidgetProvider::class.java
        assertTrue(android.appwidget.AppWidgetManager.getInstance(c).requestPinAppWidget(android.content.ComponentName(c,cls),null,null))
    }
    @Test fun collectionFailureClearsEveryRenderModeAndRecovers() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        check(c.packageName.endsWith(".pr72testbed"))
        val store=ScheduleStores.create(c)
        fun refresh() {
            val done=CountDownLatch(1); TodayWidgetUpdates.request(c) { done.countDown() }; assertTrue(done.await(10,TimeUnit.SECONDS))
        }
        refresh()
        assertNotNull(CollectionWidgets.snapshot(true).grid)
        try {
            ScheduleStores.overrideFactory={ object: ScheduleStore by store {
                override fun loadPresentation(callback: (Result<ScheduleState>)->Unit) { callback(Result.failure(IllegalStateException("isolated failure"))) }
            } }
            refresh()
            assertNotNull(CollectionWidgets.snapshot(false).error)
            assertNotNull(CollectionWidgets.snapshot(true).error)
            assertNull(CollectionWidgets.snapshot(true).grid)
            assertNull(CollectionWidgets.snapshot(true).timeGrid)
        } finally { ScheduleStores.overrideFactory=null; refresh() }
        assertNull(CollectionWidgets.snapshot(true).error)
        assertNotNull(CollectionWidgets.snapshot(true).grid)
        Thread.sleep(3000)
    }
    @Test fun configurationKeepsIndependentWeekModesAfterRecreation() {
        val i=InstrumentationRegistry.getInstrumentation(); val c=i.targetContext
        check(c.packageName.endsWith(".pr72testbed"))
        val manager=android.appwidget.AppWidgetManager.getInstance(c)
        val id=manager.getAppWidgetIds(android.content.ComponentName(c,WeekCoursesWidgetProvider::class.java)).first()
        val title=WidgetTitleSettings.get(c,id) ?: "我的课表"
        val previousPeriods=WidgetTitleSettings.periods(c,id); val previousDays=WidgetTitleSettings.weekdays(c,id)
        try {
            val intent=android.content.Intent(c,WidgetTitleActivity::class.java).putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,id)
            androidx.test.core.app.ActivityScenario.launch<WidgetTitleActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<android.widget.Spinner>(R.id.widget_display_mode).setSelection(1)
                    activity.findViewById<android.widget.Spinner>(R.id.widget_days_mode).setSelection(1)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(1,activity.findViewById<android.widget.Spinner>(R.id.widget_display_mode).selectedItemPosition)
                    assertEquals(1,activity.findViewById<android.widget.Spinner>(R.id.widget_days_mode).selectedItemPosition)
                }
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("保存"))
                    .perform(androidx.test.espresso.action.ViewActions.click())
            }
            assertFalse(WidgetTitleSettings.periods(c,id)); assertTrue(WidgetTitleSettings.weekdays(c,id))
            assertTrue(WidgetTitleSettings.periods(c,Int.MAX_VALUE)); assertFalse(WidgetTitleSettings.weekdays(c,Int.MAX_VALUE))
            assertEquals(5,CollectionWidgets.instanceSnapshot(c,id,true).grid!!.dayCount)
            assertTrue(CollectionWidgets.instanceSnapshot(c,id,true).grid!!.axis.periodLabels.isEmpty())
        } finally { WidgetTitleSettings.save(c,id,title,periods=previousPeriods,weekdays=previousDays); val done=CountDownLatch(1); TodayWidgetUpdates.request(c) { done.countDown() }; assertTrue(done.await(10,TimeUnit.SECONDS)); Thread.sleep(3000) }
    }
    @Test fun seedImportedPeriodFixture() {
        val i=InstrumentationRegistry.getInstrumentation(); val c=i.targetContext
        check(c.packageName.endsWith(".pr72testbed"))
        val store=ScheduleStores.create(c)
        fun <T> await(call: ((Result<T>)->Unit)->Unit): T {
            val latch=CountDownLatch(1); var result: Result<T>?=null
            call { result=it; latch.countDown() }; assertTrue(latch.await(10,TimeUnit.SECONDS)); return result!!.getOrThrow()
        }
        for(day in 1..7) for(n in 0..7) await<Unit> { store.deleteEntry("demo-$day-$n",it) }
        val zone=ZoneId.of("Asia/Shanghai"); val today=LocalDate.now(zone); val monday=today.minusDays((today.dayOfWeek.value-1).toLong())
        val rows=(0..6).flatMap { day -> (0..2).map { n ->
            val date=monday.plusDays(day.toLong()); val start=date.atTime(8+n*4,0).atZone(zone).toInstant().toEpochMilli()
            ImportedClassOccurrence("period-$day-$n","pr72-demo","series-$day-$n",null,start,listOf("高等数学（演示）","大学英语（演示）","管理学原理（演示）")[n],start,start+100*60_000,"Asia/Shanghai","教学楼 B${day+1}0${n+1}","",null)
        } }
        await<Unit> { store.deleteImport("pr72-demo",it) }
        val source=ImportSource("pr72-demo","秋季课表（演示）","demo.csv","csv",null,null,"0".repeat(64),0,"Asia/Shanghai",rows.size,rows.minOf { it.start },rows.maxOf { it.end },BuiltInProfiles.TERM_2026_2027_1)
        await<ImportCommit> { store.commitImport(source,rows,false,it) }
        val done=CountDownLatch(1); TodayWidgetUpdates.request(c) { done.countDown() }; assertTrue(done.await(10,TimeUnit.SECONDS))
        assertTrue(CollectionWidgets.snapshot(true).grid!!.axis.periodLabels.isNotEmpty())
        Thread.sleep(3000) // Allow Launcher to bind and fetch its asynchronous collection rows before instrumentation exits.
    }
    @Test fun dragIsolatedPreviewToNewPage() {
        val i=InstrumentationRegistry.getInstrumentation()
        check(i.targetContext.packageName.endsWith(".pr72testbed"))
        val now=android.os.SystemClock.uptimeMillis()
        fun event(action: Int,x: Float,y: Float) {
            val e=android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),action,x,y,0)
            e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(i.uiAutomation.injectInputEvent(e,true)); e.recycle()
        }
        val x=InstrumentationRegistry.getArguments().getString("dragX","760")!!.toFloat()
        event(0,x,1850f); Thread.sleep(1200)
        for(n in 0..20) { event(2,x+(1060f-x)*n/20,1850f-n*47f); Thread.sleep(30) }
        Thread.sleep(2200)
        event(2,540f,850f); Thread.sleep(500); event(1,540f,850f)
    }
    @Test fun openLauncherEditWithPinch() {
        val i=InstrumentationRegistry.getInstrumentation()
        check(i.targetContext.packageName.endsWith(".pr72testbed"))
        i.targetContext.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(1000)
        val now=android.os.SystemClock.uptimeMillis()
        val props=Array(2) { n -> android.view.MotionEvent.PointerProperties().apply { id=n; toolType=android.view.MotionEvent.TOOL_TYPE_FINGER } }
        fun event(action: Int,count: Int,spread: Float) {
            val coords=Array(count) { n -> android.view.MotionEvent.PointerCoords().apply { x=540f+(if(n==0) -spread else spread); y=950f; pressure=1f; size=1f } }
            val e=android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),action,count,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
            i.uiAutomation.injectInputEvent(e,true); e.recycle()
        }
        event(android.view.MotionEvent.ACTION_DOWN,1,400f)
        event(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),2,400f)
        for(n in 0..20) { event(android.view.MotionEvent.ACTION_MOVE,2,400f-n*15f); Thread.sleep(20) }
        event(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl 8),2,100f)
        event(android.view.MotionEvent.ACTION_UP,1,100f)
    }
    @Test fun seedAndPinIsolatedWidget() {
        val i=InstrumentationRegistry.getInstrumentation(); val c=i.targetContext
        check(c.packageName.endsWith(".pr72testbed"))
        val store=ScheduleStores.create(c)
        fun await(call: ((Result<Unit>)->Unit)->Unit) {
            val latch=CountDownLatch(1); var result: Result<Unit>?=null
            call { result=it; latch.countDown() }; assertTrue(latch.await(10,TimeUnit.SECONDS)); result!!.getOrThrow()
        }
        for(day in 1..7) for(n in 0..7) await { store.put(TimetableEntry("demo-$day-$n","演示${n+1}·课程",day,480+n*90,540+n*90,"教室 A${n+1}"),it) }
        val done=CountDownLatch(1); TodayWidgetUpdates.request(c) { done.countDown() }; assertTrue(done.await(10,TimeUnit.SECONDS))
        val mode=InstrumentationRegistry.getArguments().getString("widget","recent")
        val cls=when(mode) { "week"->WeekCoursesWidgetProvider::class.java; "compact"->TodayWidgetProvider::class.java; "wide"->WideTodayWidgetProvider::class.java; else->RecentCoursesWidgetProvider::class.java }
        val manager=android.appwidget.AppWidgetManager.getInstance(c)
        assertTrue("Launcher pin support",manager.isRequestPinAppWidgetSupported)
        i.runOnMainSync { assertTrue(manager.requestPinAppWidget(android.content.ComponentName(c,cls),null,null)) }
    }
}
