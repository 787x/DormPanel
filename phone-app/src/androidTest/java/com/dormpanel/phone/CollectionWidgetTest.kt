package com.dormpanel.phone
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.concurrent.*
class CollectionWidgetTest {
    @Test fun timedExpiryFactoriesCountReadsAndRejectOldAdapters() {
        val i=InstrumentationRegistry.getInstrumentation(); val c=i.targetContext
        check(c.packageName.endsWith(".pr72testbed"))
        val manager=android.appwidget.AppWidgetManager.getInstance(c)
        val recentId=manager.getAppWidgetIds(android.content.ComponentName(c,RecentCoursesWidgetProvider::class.java)).first()
        val weekId=manager.getAppWidgetIds(android.content.ComponentName(c,WeekCoursesWidgetProvider::class.java)).first()
        val oldClock=CollectionWidgets.clock
        val zone=java.time.ZoneId.of("Asia/Shanghai")
        // Future instants keep the isolated app's real RTC alarms out of the past; system time is untouched.
        val sunday=LocalDate.now(zone).plusWeeks(2).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
        var time=sunday.atTime(9,0).atZone(zone).toInstant(); var currentZone=zone
        val clock=object: ScheduleClock { override fun instant()=time; override fun zone()=currentZone; override fun locale()=java.util.Locale.CHINA }
        val state=ScheduleState(entries=listOf(TimetableEntry("a","A",7,480,590),TimetableEntry("b","B",7,610,720),TimetableEntry("t","Tomorrow",1,480,540)))
        val store=ScheduleStores.create(c); val reads=java.util.concurrent.CopyOnWriteArrayList<(Result<ScheduleState>)->Unit>()
        val oldPeriods=WidgetTitleSettings.periods(c,weekId); val oldDays=WidgetTitleSettings.weekdays(c,weekId)
        fun drain() { i.runOnMainSync {} }
        fun basis()=CollectionBasis.at(time,currentZone)
        fun recent()=(0..1).map { CourseCollectionService.Factory(c,recentId,it,basis()) }
        fun load(f: List<CourseCollectionService.Factory>) { i.runOnMainSync { f.forEach { it.onCreate(); it.onDataSetChanged() } };drain() }
        try {
            i.runOnMainSync { CollectionWidgets.clock=clock; WidgetTitleSettings.save(c,weekId,WidgetTitleSettings.get(c,weekId) ?: "我的课表",periods=true,weekdays=false); CollectionWidgets.publish(c,Result.success(state),time,zone) }
            ScheduleStores.overrideFactory={ object: ScheduleStore by store { override fun loadPresentation(callback: (Result<ScheduleState>)->Unit) { reads+=callback } } }
            val old=recent();load(old);assertEquals(0,reads.size)
            val tomorrowBefore=old[1].data.rows
            time=sunday.atTime(9,40).atZone(zone).toInstant();load(recent());assertEquals(0,reads.size)
            time=sunday.atTime(9,50).atZone(zone).toInstant()
            assertFalse(CollectionWidgets.available(c,recentId))
            i.runOnMainSync { old.forEach { assertEquals(0,it.getCount()); assertNull(it.getViewAt(0)) } };drain();assertEquals(1,reads.size)
            reads[0](Result.success(state));drain()
            assertEquals(listOf("B"),CollectionWidgets.snapshot(false).rows.map { it.cells[0].text.substringBefore("\n") })
            i.runOnMainSync { old.forEach { it.onDataSetChanged();assertTrue(it.getCount()>0) } }
            assertEquals(tomorrowBefore,old[1].data.rows)
            assertEquals("B",old[0].data.rows.single().cells.single().text.substringBefore("\n"))
            time=sunday.atTime(10,10).atZone(zone).toInstant()
            i.runOnMainSync { old.forEach { assertEquals(0,it.getCount()) } };drain();assertEquals(2,reads.size)
            ScheduleCommitSignals.notifyChanged();drain();reads[1](Result.success(state));drain();assertEquals(3,reads.size)
            val edited=state.copy(entries=state.entries.map { if(it.id=="b") it.copy(title="B-new") else it })
            reads[2](Result.success(edited));drain();assertTrue(CollectionWidgets.snapshot(false).rows.first().cells[0].text.contains("B-new"))
            val week=CourseCollectionService.Factory(c,weekId,0,basis(),WeekWidgetMode())
            load(listOf(week));assertEquals(3,reads.size)
            time=sunday.plusDays(1).atTime(0,1).atZone(zone).toInstant()
            assertFalse(CollectionWidgets.available(c,weekId))
            i.runOnMainSync { assertEquals(0,week.getCount());assertNull(week.getViewAt(0)) };drain();assertEquals(4,reads.size)
            reads[3](Result.success(edited));drain()
            assertEquals(sunday.plusDays(1),CollectionWidgets.snapshot(true).grid!!.today)
            assertEquals(sunday.plusDays(1),CollectionWidgets.snapshot(true).grid!!.monday)
            i.runOnMainSync { week.onDataSetChanged();assertEquals(0,week.getCount()) };drain();assertEquals(4,reads.size)
            val monday=CourseCollectionService.Factory(c,weekId,0,basis(),WeekWidgetMode())
            val mondayTwin=CourseCollectionService.Factory(c,weekId,0,basis(),WeekWidgetMode())
            load(listOf(monday,mondayTwin));assertEquals(4,reads.size)
            time=sunday.plusDays(2).atTime(0,1).atZone(zone).toInstant()
            i.runOnMainSync { assertEquals(0,monday.getCount()); assertEquals(0,mondayTwin.getCount()); assertEquals(0,week.getCount()) };drain();assertEquals(5,reads.size)
            reads[4](Result.success(edited));drain();assertEquals(sunday.plusDays(2),CollectionWidgets.snapshot(true).grid!!.today)
            currentZone=java.time.ZoneId.of("UTC")
            assertFalse(CollectionWidgets.available(c,weekId));assertFalse(CollectionWidgets.available(c,recentId))
            val stale=recent();load(stale);assertEquals(6,reads.size);reads[5](Result.failure(IllegalStateException("read")));drain()
            val retry=recent();load(retry);assertEquals(7,reads.size);reads[6](Result.success(edited));drain()
            i.runOnMainSync {
                assertTrue(WidgetTitleSettings.save(c,weekId,WidgetTitleSettings.get(c,weekId) ?: "我的课表",periods=false,weekdays=true))
                CollectionWidgets.publish(c,Result.success(edited),time,currentZone)
                val five=CollectionWidgets.instanceSnapshot(c,weekId,true)
                assertEquals(5,five.grid!!.dayCount);assertTrue(five.grid.axis.periodLabels.isEmpty())
                monday.onDataSetChanged();assertEquals(0,monday.getCount())
                val fiveFactory=CourseCollectionService.Factory(c,weekId,0,basis(),WeekWidgetMode(false,5))
                fiveFactory.onCreate();fiveFactory.onDataSetChanged();assertTrue(fiveFactory.getCount()>0)
            }
            drain();assertEquals(7,reads.size)
        } finally {
            ScheduleStores.overrideFactory=null;CollectionWidgets.clock=oldClock
            WidgetTitleSettings.save(c,weekId,WidgetTitleSettings.get(c,weekId) ?: "我的课表",periods=oldPeriods,weekdays=oldDays)
            val done=CountDownLatch(1);TodayWidgetUpdates.request(c) { done.countDown() };assertTrue(done.await(10,TimeUnit.SECONDS))
        }
    }
    @Test fun dualFactoriesCountPhysicalReadsAndDoNotSwallowCommit() {
        val i=InstrumentationRegistry.getInstrumentation(); val c=i.targetContext
        check(c.packageName.endsWith(".pr72testbed"))
        val id=android.appwidget.AppWidgetManager.getInstance(c).getAppWidgetIds(android.content.ComponentName(c,RecentCoursesWidgetProvider::class.java)).first()
        val done=CountDownLatch(1); TodayWidgetUpdates.request(c) { done.countDown() }; assertTrue(done.await(10,TimeUnit.SECONDS))
        val store=ScheduleStores.create(c)
        val reads=java.util.concurrent.CopyOnWriteArrayList<(Result<ScheduleState>)->Unit>()
        ScheduleStores.overrideFactory={ object: ScheduleStore by store { override fun loadPresentation(callback: (Result<ScheduleState>)->Unit) { reads+=callback } } }
        fun drain() { i.runOnMainSync {} }
        fun state(title: String): ScheduleState {
            val day=LocalDate.now().dayOfWeek.value
            return ScheduleState(entries=listOf(TimetableEntry("today",title,day,0,1439),TimetableEntry("tomorrow",title,day%7+1,0,1439)))
        }
        val basis=CollectionBasis.at(java.time.Instant.now(),java.time.ZoneId.systemDefault())
        fun factories(reverse: Boolean)=listOf(0,1).let { if(reverse) it.reversed() else it }.map { CourseCollectionService.Factory(c,id,it,basis) }
        try {
            val warm=factories(false)
            i.runOnMainSync { warm.forEach { it.onCreate();it.onDataSetChanged();it.getCount() } };drain()
            assertEquals("warm factories do not read",0,reads.size)
            for(reverse in listOf(false,true)) {
                val offset=reads.size
                i.runOnMainSync { CollectionWidgets.publish(c,Result.failure(IllegalStateException("unavailable")),java.time.Instant.now(),basis.zone) }
                val cold=factories(reverse)
                i.runOnMainSync { cold.forEach { it.onCreate();it.onDataSetChanged();it.getCount() } };drain()
                assertEquals(offset+1,reads.size)
                if(!reverse) { reads[offset](Result.success(state("first")));drain();assertEquals(offset+1,reads.size) }
                else {
                    ScheduleCommitSignals.notifyChanged();drain()
                    reads[offset](Result.success(state("obsolete")));drain();assertEquals(offset+2,reads.size)
                    reads[offset+1](Result.success(state("new commit")));drain()
                    assertTrue(CollectionWidgets.snapshot(false).rows.first().cells.all { it.text.contains("new commit") })
                }
                i.runOnMainSync { cold.forEach { it.onDataSetChanged();assertTrue(it.getCount()>0) } };drain()
                assertEquals(offset+if(reverse) 2 else 1,reads.size)
            }
            i.runOnMainSync { CollectionWidgets.publish(c,Result.failure(IllegalStateException("unavailable")),java.time.Instant.now(),basis.zone) }
            val failed=factories(false);i.runOnMainSync { failed.forEach { it.onCreate() } };drain()
            val failureIndex=reads.lastIndex;reads[failureIndex](Result.failure(IllegalStateException("read failed")));drain()
            val retry=factories(true);i.runOnMainSync { retry.forEach { it.onCreate() } };drain()
            assertEquals(failureIndex+2,reads.size);reads.last()(Result.success(state("recovered")));drain()
            assertNull(CollectionWidgets.snapshot(false).error)
        } finally { ScheduleStores.overrideFactory=null;val restored=CountDownLatch(1);TodayWidgetUpdates.request(c) { restored.countDown() };assertTrue(restored.await(10,TimeUnit.SECONDS)) }
    }
    @Test fun recentMidnightSnapshotNeverGetsNewDateWithOldCourses() {
        val i=InstrumentationRegistry.getInstrumentation(); val c=i.targetContext
        check(c.packageName.endsWith(".pr72testbed"))
        val manager=android.appwidget.AppWidgetManager.getInstance(c)
        val id=manager.getAppWidgetIds(android.content.ComponentName(c,RecentCoursesWidgetProvider::class.java)).first()
        val zone=java.time.ZoneId.of("Asia/Shanghai"); val date=LocalDate.of(2026,10,29)
        val before=date.atTime(23,59,59).atZone(zone).toInstant(); val after=before.plusSeconds(2)
        val start=date.atTime(23,30).atZone(zone).toInstant().toEpochMilli()
        val state=ScheduleState(imported=listOf(ImportedClassOccurrence("midnight","s","midnight",null,start,"跨午夜",start,start+3600000,zone.id,"A","",null)))
        try {
            CollectionWidgets.publish(c,Result.success(state),before,zone)
            val old=CollectionWidgets.instanceSnapshot(c,id,false,before,zone)
            assertEquals(date,old.basis!!.date); assertTrue(old.rows.first().cells.all { it.text.contains("跨午夜") })
            val stale=CollectionWidgets.instanceSnapshot(c,id,false,after,zone)
            assertTrue(stale.rows.isEmpty()); assertNotNull(stale.error)
            assertEquals(date.plusDays(1),stale.basis!!.date)
            i.runOnMainSync {
                val oldView=CollectionWidgets.render(c,id,state,before,zone).apply(c,android.widget.FrameLayout(c))
                assertTrue(oldView.findViewById<android.widget.TextView>(R.id.header_0).text.toString().contains("10.29"))
                val staleView=CollectionWidgets.render(c,id,state,after,zone).apply(c,android.widget.FrameLayout(c))
                assertTrue(staleView.findViewById<android.widget.TextView>(R.id.header_0).text.toString().contains("10.30"))
                assertEquals(android.view.View.GONE,staleView.findViewById<android.view.View>(R.id.collection_list).visibility)
                assertEquals(android.view.View.GONE,staleView.findViewById<android.view.View>(R.id.recent_tomorrow_list).visibility)
            }
            CollectionWidgets.publish(c,Result.success(state),after,zone)
            val fresh=CollectionWidgets.instanceSnapshot(c,id,false,after,zone)
            assertEquals(date.plusDays(1),fresh.basis!!.date)
            assertEquals(date.plusDays(1),fresh.rows.first().cells[0].date)
            assertEquals(date.plusDays(2),fresh.rows.first().cells[1].date)
            assertTrue(fresh.rows.first().cells[0].text.contains("跨午夜"))
            i.runOnMainSync {
                val sunday=LocalDate.of(2026,11,1)
                val a=sunday.atTime(23,59,59).atZone(zone).toInstant(); val b=a.plusSeconds(2)
                val source=ImportSource("calendar","日历依据","demo.csv","csv",null,null,"0".repeat(64),0,zone.id,2,0,0,BuiltInProfiles.TERM_2026_2027_1)
                val records=listOf(sunday.minusDays(4),sunday.plusDays(4)).mapIndexed { n,d ->
                    val time=d.atTime(8,0).atZone(zone).toInstant().toEpochMilli()
                    ImportedClassOccurrence("calendar$n",source.id,"calendar$n",null,time,"日历课程",time,time+3600000,zone.id,"A","",null)
                }
                val calendar=ScheduleState(sources=listOf(source),imported=records,profiles=mapOf(source.termKey!! to BuiltInProfiles.term2026))
                CollectionWidgets.publish(c,Result.success(calendar),a,zone)
                assertEquals("第9-10周",CollectionWidgets.instanceSnapshot(c,id,false,a,zone).weekLabel)
                assertEquals("",CollectionWidgets.instanceSnapshot(c,id,false,b,zone).weekLabel)
                CollectionWidgets.publish(c,Result.success(calendar),b,zone)
                assertEquals("第10周",CollectionWidgets.instanceSnapshot(c,id,false,b,zone).weekLabel)
                assertEquals("",CollectionWidgets.instanceSnapshot(c,id,false,b,java.time.ZoneId.of("UTC")).weekLabel)
            }
            CollectionWidgets.publish(c,Result.failure(IllegalStateException("read")),after,zone)
            assertTrue(CollectionWidgets.instanceSnapshot(c,id,false,after,zone).rows.isEmpty())
            assertNotNull(CollectionWidgets.instanceSnapshot(c,id,false,after,zone).error)
        } finally { val done=CountDownLatch(1); TodayWidgetUpdates.request(c) { done.countDown() }; assertTrue(done.await(10,TimeUnit.SECONDS)) }
    }
    @Test fun recentDateHeadingsAndTomorrowHeaderClick() {
        val i=InstrumentationRegistry.getInstrumentation(); val c=i.targetContext
        check(c.packageName.endsWith(".pr72testbed"))
        val info=i.uiAutomation.serviceInfo; info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS; i.uiAutomation.serviceInfo=info
        fun nodes(n: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> = if(n==null) emptyList() else listOf(n)+(0 until n.childCount).flatMap { nodes(n.getChild(it)) }
        fun find(id: String): android.view.accessibility.AccessibilityNodeInfo {
            val end=System.currentTimeMillis()+5000
            while(System.currentTimeMillis()<end) {
                nodes(i.uiAutomation.rootInActiveWindow).firstOrNull { it.isVisibleToUser && it.viewIdResourceName?.endsWith(id)==true }?.let { return it }
                Thread.sleep(50)
            }; error("Missing $id")
        }
        val today=LocalDate.now(); val tomorrow=today.plusDays(1)
        fun title(label: String,date: LocalDate)="$label ${date.format(java.time.format.DateTimeFormatter.ofPattern("MM.dd"))} 周${"一二三四五六日"[date.dayOfWeek.value-1]}"
        assertEquals(title("今天",today),find("header_0").text.toString())
        val node=find("header_1"); assertEquals(title("明天",tomorrow),node.text.toString())
        assertTrue(nodes(i.uiAutomation.rootInActiveWindow).firstOrNull { it.viewIdResourceName?.endsWith("collection_date")==true }?.text?.toString().orEmpty().matches(Regex("(?:第[0-9]+(?:-[0-9]+)?周)?")))
        val bounds=android.graphics.Rect(); node.getBoundsInScreen(bounds); val down=android.os.SystemClock.uptimeMillis()
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            val e=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
            e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN; assertTrue(i.uiAutomation.injectInputEvent(e,true)); e.recycle()
        }
        var selected: LocalDate?=null; val end=System.currentTimeMillis()+5000
        while(selected!=tomorrow && System.currentTimeMillis()<end) {
            i.runOnMainSync {
                val a=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<TimetableActivity>().firstOrNull()
                if(a!=null) selected=TimetableActivity::class.java.getDeclaredField("selected").apply { isAccessible=true }.get(a) as LocalDate
            }; if(selected!=tomorrow) Thread.sleep(50)
        }
        assertEquals(tomorrow,selected)
    }
    @Test fun recentColumnsScrollIndependently() {
        val i=InstrumentationRegistry.getInstrumentation(); val c=i.targetContext
        check(c.packageName.endsWith(".pr72testbed"))
        val info=i.uiAutomation.serviceInfo; info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS; i.uiAutomation.serviceInfo=info
        fun nodes(n: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> = if(n==null) emptyList() else listOf(n)+(0 until n.childCount).flatMap { nodes(n.getChild(it)) }
        fun list(tomorrow: Boolean): android.view.accessibility.AccessibilityNodeInfo {
            val suffix=if(tomorrow) "recent_tomorrow_list" else "collection_list"
            return nodes(i.uiAutomation.rootInActiveWindow).first { it.viewIdResourceName?.endsWith(suffix)==true && it.isVisibleToUser }
        }
        fun titles(tomorrow: Boolean)=nodes(list(tomorrow)).filter { it.isVisibleToUser && it.viewIdResourceName?.endsWith("recent_title_0")==true }.map { it.text.toString() }
        fun scroll(tomorrow: Boolean) {
            val r=android.graphics.Rect(); list(tomorrow).getBoundsInScreen(r)
            val down=android.os.SystemClock.uptimeMillis()
            fun event(action: Int,y: Float) { val e=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,r.exactCenterX(),y,0); e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN; i.uiAutomation.injectInputEvent(e,true); e.recycle() }
            event(0,(r.bottom-15).toFloat())
            for(n in 1..12) { event(2,r.bottom-15f-(r.height()-30)*n/12); Thread.sleep(35) }
            event(1,(r.top+15).toFloat()); Thread.sleep(700)
        }
        val store=ScheduleStores.create(c); val day=LocalDate.now().dayOfWeek.value
        val state=ScheduleState(entries=(0..9).flatMap { n -> listOf(TimetableEntry("scroll-t$n","今日$n",day,n*15,1439,"A$n"),TimetableEntry("scroll-m$n","明日$n",day%7+1,n*15,1439,"B$n")) })
        ScheduleStores.overrideFactory={ object: ScheduleStore by store { override fun loadPresentation(callback: (Result<ScheduleState>)->Unit) { callback(Result.success(state)) } } }
        fun refresh() { val done=CountDownLatch(1); TodayWidgetUpdates.request(c) { done.countDown() }; assertTrue(done.await(10,TimeUnit.SECONDS)); Thread.sleep(3000) }
        try {
            refresh()
            val today=titles(false); val tomorrow=titles(true)
            assertTrue(today.isNotEmpty()); assertTrue(tomorrow.isNotEmpty())
            scroll(true); assertEquals(today,titles(false)); val movedTomorrow=titles(true); assertNotEquals(tomorrow,movedTomorrow)
            scroll(false); assertEquals(movedTomorrow,titles(true)); assertNotEquals(today,titles(false))
            assertTrue(i.uiAutomation.rootInActiveWindow.packageName.toString().contains("home") || i.uiAutomation.rootInActiveWindow.packageName.toString().contains("launcher"))
        } finally { ScheduleStores.overrideFactory=null; refresh() }
    }
    @Test fun realOccurrenceBudgetsStayIndependent() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext; check(c.packageName.endsWith(".pr72testbed"))
        val zone=ZoneId.systemDefault(); val date=LocalDate.now(zone)
        val start=date.atTime(23,0).atZone(zone).toInstant().toEpochMilli(); val end=date.plusDays(1).atTime(1,0).atZone(zone).toInstant().toEpochMilli()
        val recentHeavy=ScheduleState(imported=(0 until 300).map { n -> ImportedClassOccurrence("b$n","s","b$n",null,start,"预算课程",start,end,zone.id,"教室","",null) })
        try {
            CollectionWidgets.publish(c,Result.success(recentHeavy),date.atStartOfDay(zone).toInstant(),zone)
            assertNotNull(CollectionWidgets.snapshot(false).error); assertNull(CollectionWidgets.snapshot(true).error)
            assertNotNull(CollectionWidgets.snapshot(true).grid)
            val other=date.minusDays((date.dayOfWeek.value-1).toLong()).plusDays(if(date.dayOfWeek.value in 1..2) 4 else 0)
            val a=other.atTime(8,0).atZone(zone).toInstant().toEpochMilli()
            val weekHeavy=ScheduleState(imported=(0 until 513).map { n -> ImportedClassOccurrence("w$n","s","w$n",null,a,"周预算课程",a,a+3600000,zone.id,"教室","",null) })
            CollectionWidgets.publish(c,Result.success(weekHeavy),date.atStartOfDay(zone).toInstant(),zone)
            assertNull(CollectionWidgets.snapshot(false).error); assertNotNull(CollectionWidgets.snapshot(true).error)
            CollectionWidgets.publish(c,Result.failure(IllegalStateException("read")),java.time.Instant.now(),zone)
            assertNotNull(CollectionWidgets.snapshot(false).error); assertNotNull(CollectionWidgets.snapshot(true).error)
        } finally {
            val done=CountDownLatch(1); TodayWidgetUpdates.request(c) { done.countDown() }; assertTrue(done.await(10,TimeUnit.SECONDS))
        }
    }
    @Test fun markLifecycleInstances() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext; check(c.packageName.endsWith(".pr72testbed"))
        val manager=android.appwidget.AppWidgetManager.getInstance(c)
        val ids=listOf(RecentCoursesWidgetProvider::class.java,WeekCoursesWidgetProvider::class.java).flatMap { manager.getAppWidgetIds(android.content.ComponentName(c,it)).toList() }
        assertEquals(2,ids.size)
        c.getSharedPreferences("pr72-audit",0).edit().putString("oldIds",ids.joinToString(",")).commit()
        ids.forEach { WidgetTitleSettings.save(c,it,"旧实例-$it",periods=false,weekdays=true) }
        val done=CountDownLatch(1); TodayWidgetUpdates.request(c) { done.countDown() }; assertTrue(done.await(10,TimeUnit.SECONDS)); Thread.sleep(2000)
    }
    @Test fun allIsolatedInstancesRemoved() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext; check(c.packageName.endsWith(".pr72testbed"))
        val manager=android.appwidget.AppWidgetManager.getInstance(c)
        listOf(TodayWidgetProvider::class.java,WideTodayWidgetProvider::class.java,RecentCoursesWidgetProvider::class.java,WeekCoursesWidgetProvider::class.java).forEach {
            assertEquals(0,manager.getAppWidgetIds(android.content.ComponentName(c,it)).size)
        }
        val old=c.getSharedPreferences("pr72-audit",0).getString("oldIds","")!!.split(",").map(String::toInt)
        old.forEach { assertNull(WidgetTitleSettings.get(c,it)); assertFalse(WidgetTitleSettings.weekdays(c,it)); assertTrue(WidgetTitleSettings.periods(c,it)) }
    }
    @Test fun readdedInstancesHaveNewIdentityAndDefaults() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext; check(c.packageName.endsWith(".pr72testbed"))
        val old=c.getSharedPreferences("pr72-audit",0).getString("oldIds","")!!.split(",").map(String::toInt)
        val manager=android.appwidget.AppWidgetManager.getInstance(c)
        val ids=listOf(RecentCoursesWidgetProvider::class.java,WeekCoursesWidgetProvider::class.java).flatMap { manager.getAppWidgetIds(android.content.ComponentName(c,it)).toList() }
        assertEquals(2,ids.size)
        ids.forEach { assertFalse(it in old); assertNull(WidgetTitleSettings.get(c,it)); assertFalse(WidgetTitleSettings.weekdays(c,it)); assertTrue(WidgetTitleSettings.periods(c,it)) }
        val done=CountDownLatch(1); TodayWidgetUpdates.request(c) { done.countDown() }; assertTrue(done.await(10,TimeUnit.SECONDS))
        assertNull(CollectionWidgets.snapshot(false).error); assertNull(CollectionWidgets.snapshot(true).error)
        assertEquals(7,CollectionWidgets.snapshot(true).grid!!.dayCount); Thread.sleep(3000)
        println("old=$old new=$ids")
    }
    @Test fun weekDatesExistingActivityFiveDaysAndTitle() {
        val i=InstrumentationRegistry.getInstrumentation(); val c=i.targetContext
        check(c.packageName.endsWith(".pr72testbed"))
        val info=i.uiAutomation.serviceInfo; info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS; i.uiAutomation.serviceInfo=info
        fun nodes(n: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> =
            if(n==null) emptyList() else listOf(n)+(0 until n.childCount).flatMap { nodes(n.getChild(it)) }
        fun find(suffix: String): android.view.accessibility.AccessibilityNodeInfo {
            val end=System.currentTimeMillis()+5000
            while(System.currentTimeMillis()<end) {
                nodes(i.uiAutomation.rootInActiveWindow).firstOrNull { it.viewIdResourceName?.endsWith(suffix)==true && it.isVisibleToUser }?.let { return it }
                Thread.sleep(50)
            }; error("Missing Launcher node $suffix")
        }
        fun tap(node: android.view.accessibility.AccessibilityNodeInfo) {
            val bounds=android.graphics.Rect(); node.getBoundsInScreen(bounds)
            println("tap ${node.viewIdResourceName} $bounds")
            check(bounds.width()>0 && bounds.height()>0)
            val down=android.os.SystemClock.uptimeMillis()
            for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                val e=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
                e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN; assertTrue(i.uiAutomation.injectInputEvent(e,true)); e.recycle()
            }
        }
        fun activity(): TimetableActivity {
            var a: TimetableActivity?=null; val end=System.currentTimeMillis()+5000
            while(a==null && System.currentTimeMillis()<end) {
                i.runOnMainSync { a=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<TimetableActivity>().firstOrNull() }; Thread.sleep(50)
            }; return checkNotNull(a)
        }
        fun selected(a: TimetableActivity)=TimetableActivity::class.java.getDeclaredField("selected").apply { isAccessible=true }.get(a)
        fun home() {
            c.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            for(page in 0..4) {
                Thread.sleep(800)
                if(nodes(i.uiAutomation.rootInActiveWindow).any { it.viewIdResourceName?.endsWith("week_tile")==true && it.isVisibleToUser }) return
                i.uiAutomation.executeShellCommand("input swipe 950 900 150 900 450").close()
            }
            find("week_tile")
        }
        val monday=LocalDate.now().minusDays((LocalDate.now().dayOfWeek.value-1).toLong())
        find("week_tile")
        tap(find("cell_0"))
        fun expectDate(date: LocalDate): TimetableActivity {
            val end=System.currentTimeMillis()+5000
            var a=activity()
            while(selected(a)!=date && System.currentTimeMillis()<end) { Thread.sleep(50); a=activity() }
            assertEquals("action=${a.intent.action}, date=${a.intent.getStringExtra("widgetDate")}",date,selected(a)); return a
        }
        val first=expectDate(monday)
        home()
        tap(find("cell_3"))
        val second=expectDate(monday.plusDays(3)); assertSame(first,second)
        val manager=android.appwidget.AppWidgetManager.getInstance(c)
        val id=manager.getAppWidgetIds(android.content.ComponentName(c,WeekCoursesWidgetProvider::class.java)).first()
        val oldDays=WidgetTitleSettings.weekdays(c,id); val oldPeriod=WidgetTitleSettings.periods(c,id)
        try {
            WidgetTitleSettings.save(c,id,WidgetTitleSettings.get(c,id) ?: "我的课表",periods=false,weekdays=true)
            val done=CountDownLatch(1); TodayWidgetUpdates.request(c) { done.countDown() }; assertTrue(done.await(10,TimeUnit.SECONDS))
            home(); Thread.sleep(2000); find("week_tile")
            assertFalse(nodes(i.uiAutomation.rootInActiveWindow).any { it.viewIdResourceName?.endsWith("cell_5")==true || it.viewIdResourceName?.endsWith("cell_6")==true })
            tap(find("cell_4"))
            expectDate(monday.plusDays(4))
            home(); tap(find("collection_title"))
            val title=expectDate(LocalDate.now())
            assertTrue(TimetableActivity::class.java.getDeclaredField("weekView").apply { isAccessible=true }.getBoolean(title))
        } finally {
            WidgetTitleSettings.save(c,id,WidgetTitleSettings.get(c,id) ?: "我的课表",periods=oldPeriod,weekdays=oldDays)
            val done=CountDownLatch(1); TodayWidgetUpdates.request(c) { done.countDown() }; done.await(10,TimeUnit.SECONDS)
        }
    }
    @Test fun formalCommitRefreshesWithoutManualRequest() {
        val i=InstrumentationRegistry.getInstrumentation(); val c=i.targetContext
        check(c.packageName.endsWith(".pr72testbed"))
        val store=ScheduleStores.create(c)
        val day=LocalDate.now(); val title="正式提交刷新验收"
        val done=CountDownLatch(1); var result: Result<Unit>?=null
        store.put(TimetableEntry("pr72-refresh-proof",title,day.dayOfWeek.value,0,1439,"新地点验收")) { result=it; done.countDown() }
        assertTrue(done.await(10,TimeUnit.SECONDS)); result!!.getOrThrow()
        // No TodayWidgetUpdates.request here: exercise PhoneApplication's successful-commit signal.
        val end=System.currentTimeMillis()+7000
        while(System.currentTimeMillis()<end && CollectionWidgets.snapshot(false).rows.none { r -> r.cells.any { it.text.contains(title) } }) Thread.sleep(50)
        assertTrue(CollectionWidgets.snapshot(false).rows.any { r -> r.cells.any { it.text.contains(title) } })
        assertTrue(CollectionWidgets.snapshot(true).grid!!.segments.any { it.item.entry.id=="pr72-refresh-proof" && it.item.entry.location=="新地点验收" })
        val loaded=CountDownLatch(1)
        store.load { assertTrue(it.getOrThrow().entries.any { e -> e.id=="pr72-refresh-proof" && e.location=="新地点验收" }); loaded.countDown() }
        assertTrue(loaded.await(10,TimeUnit.SECONDS)); Thread.sleep(3000)
        val info=i.uiAutomation.serviceInfo; info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS; i.uiAutomation.serviceInfo=info
        fun texts(n: android.view.accessibility.AccessibilityNodeInfo?): String = if(n==null) "" else n.text.toString()+n.contentDescription.toString()+(0 until n.childCount).joinToString { texts(n.getChild(it)) }
        val visibleEnd=System.currentTimeMillis()+7000
        var visible=false
        while(!visible && System.currentTimeMillis()<visibleEnd) { visible=texts(i.uiAutomation.rootInActiveWindow).contains(title); if(!visible) Thread.sleep(100) }
        assertTrue("Launcher must show persisted change",visible)
    }
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
        val list=nodes(root).first { it.viewIdResourceName?.endsWith("recent_tomorrow_list")==true }
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
        val tomorrow=nodes(i.uiAutomation.rootInActiveWindow).first { it.viewIdResourceName?.endsWith("recent_tomorrow_list")==true }
        val cell=nodes(tomorrow).first { it.viewIdResourceName?.endsWith("cell_0")==true && it.isVisibleToUser }
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
            val refreshed=CountDownLatch(1); TodayWidgetUpdates.request(c) { refreshed.countDown() }; assertTrue(refreshed.await(10,TimeUnit.SECONDS))
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
