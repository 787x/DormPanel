package com.dormpanel.phone

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matchers.startsWith
import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class CourseColorsTest {
    @Test fun launcherUpdatesBothPlacedWidgetsWithoutBusinessCommit() {
        val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
        require(c.packageName.endsWith(".pr68testbed"))
        val manager=android.appwidget.AppWidgetManager.getInstance(c)
        val compact=manager.getAppWidgetIds(android.content.ComponentName(c,TodayWidgetProvider::class.java))
        val wide=manager.getAppWidgetIds(android.content.ComponentName(c,WideTodayWidgetProvider::class.java))
        assertTrue(compact.isNotEmpty());assertTrue(wide.isNotEmpty())
        val f=CompletableFuture<Result<ScheduleState>>();ScheduleStores.create(c).load{f.complete(it)}
        val state=f.get(10,TimeUnit.SECONDS).getOrThrow()
        val candidate=CourseBatch.candidates(state).single{it.originalTitle=="高等数学 B-1"}
        val colors=CourseColors.snapshot(c,state);val key=colors.key(candidate)!!
        c.getSharedPreferences("pr68-test-evidence",0).edit().putInt("automatic",colors.rgb(key,null)).commit()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("input keyevent 3")).use{it.readBytes()}
        android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("input swipe 900 1000 100 1000 350")).use{it.readBytes()}
        Thread.sleep(500)
        fun matchesBoth(rgb:Int):Boolean {
            val nodes=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            fun visit(node:android.view.accessibility.AccessibilityNodeInfo?){if(node==null)return;if(node.viewIdResourceName==c.packageName+":id/widget_course_color")nodes.add(node);for(n in 0 until node.childCount)visit(node.getChild(n))}
            visit(i.uiAutomation.rootInActiveWindow)
            if(nodes.size<2)return false
            val image=i.uiAutomation.takeScreenshot()
            val matches=nodes.all { node->
                val r=android.graphics.Rect();node.getBoundsInScreen(r)
                var red=0L;var green=0L;var blue=0L;var count=0
                for(y in r.top+3 until r.bottom-3 step 2)for(x in r.left+2 until r.right-2){val pixel=image.getPixel(x,y);red+=android.graphics.Color.red(pixel);green+=android.graphics.Color.green(pixel);blue+=android.graphics.Color.blue(pixel);count++}
                count>0 && kotlin.math.abs(red/count-android.graphics.Color.red(rgb))<15 && kotlin.math.abs(green/count-android.graphics.Color.green(rgb))<15 && kotlin.math.abs(blue/count-android.graphics.Color.blue(rgb))<15
            }
            image.recycle();return matches
        }
        fun waitForColor(rgb:Int) { val until=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<until){if(matchesBoth(rgb))return;Thread.sleep(150)};fail("Launcher did not display saved accent in both widgets") }
        fun shot(name:String) {val b=i.uiAutomation.takeScreenshot();java.io.File(c.getExternalFilesDir(null),"pr68-launcher-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        CourseColors.save(c,key,0xb49138)
        waitForColor(0xffb49138.toInt())
        fun titleCount(node:android.view.accessibility.AccessibilityNodeInfo?):Int {if(node==null)return 0;return (if(node.text?.toString()=="高等数学 B-1")1 else 0)+(0 until node.childCount).sumOf{titleCount(node.getChild(it))}}
        assertTrue("Both Launcher widgets contain math",titleCount(i.uiAutomation.rootInActiveWindow)>=2)
        shot("manual")
        CourseColors.save(c,key,null)
        waitForColor(colors.rgb(key,null) or (0xff shl 24));shot("automatic")
        CourseColors.save(c,key,0xb49138);waitForColor(0xffb49138.toInt())
        val after=CompletableFuture<Result<ScheduleState>>();ScheduleStores.create(c).load{after.complete(it)}
        assertEquals(state,after.get(10,TimeUnit.SECONDS).getOrThrow())
    }
    @Test fun verifyRestartRetainsImportedManualColor() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        require(c.packageName.endsWith(".pr68testbed"))
        val f=CompletableFuture<Result<ScheduleState>>();ScheduleStores.create(c).load{f.complete(it)}
        val state=f.get(10,TimeUnit.SECONDS).getOrThrow()
        val candidate=CourseBatch.candidates(state).single{it.originalTitle=="高等数学 B-1"}
        val colors=CourseColors.snapshot(c,state);val key=colors.key(candidate)!!
        assertEquals(0xb49138,colors.manual(key))
        assertEquals(c.getSharedPreferences("pr68-test-evidence",0).getInt("automatic",-1),colors.rgb(key,null))
        val projected=ScheduleProjection.week(state,java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY),java.time.ZoneId.systemDefault())
        assertEquals(3,projected.filter{it.seriesId in candidate.series}.size)
        assertTrue(projected.filter{it.seriesId in candidate.series}.all{colors.rgb(it)==0xb49138})
    }
    @Test fun remoteViewsUseSavedLogicalAccentAndRenderReadErrors() {
        val i=InstrumentationRegistry.getInstrumentation(); val c=i.targetContext
        require(c.packageName.endsWith(".pr68testbed"))
        val now=java.time.Instant.now(); val date=java.time.LocalDate.now()
        val state=ScheduleState(entries=listOf(TimetableEntry("widget-color","小部件课程",date.dayOfWeek.value,0,1439)))
        val colors=CourseColors.snapshot(c,state); val key=colors.identity.key(null,"widget-color")!!
        CourseColors.save(c,key,0xb49138)
        val method=TodayWidgetUpdates::class.java.getDeclaredMethod("render",android.content.Context::class.java,Int::class.javaPrimitiveType,Pair::class.java,String::class.java)
        method.isAccessible=true
        i.runOnMainSync {
            val day=TodayCourses.project(state,now,java.time.ZoneId.systemDefault())
            val views=method.invoke(TodayWidgetUpdates,c,-1,state to day,null) as android.widget.RemoteViews
            val root=views.apply(c,android.widget.FrameLayout(c))
            val stripe=root.findViewById<android.widget.ImageView>(R.id.widget_course_color)
            assertEquals(0xffb49138.toInt(),(stripe.background as android.graphics.drawable.ColorDrawable).color)
            val error=method.invoke(TodayWidgetUpdates,c,-1,null,"读取失败") as android.widget.RemoteViews
            val errorRoot=error.apply(c,android.widget.FrameLayout(c))
            assertEquals(0,errorRoot.findViewById<android.widget.LinearLayout>(R.id.widget_courses).childCount)
        }
    }
    @Test fun isolatedPersistenceAndPickerCancellation() {
        val i=InstrumentationRegistry.getInstrumentation(); val c=i.targetContext
        require(c.packageName.endsWith(".pr68testbed"))
        val dark=InstrumentationRegistry.getArguments().getString("dark") == "true"
        i.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(if (dark) 2 else 1) }
        val executor=Executors.newSingleThreadExecutor()
        val name="pr68-${java.util.UUID.randomUUID()}"
        val store=RoomScheduleStore(Room.databaseBuilder(c,ScheduleDatabase::class.java,"$name.db").build(),executor)
        fun <T> await(call: ((Result<T>)->Unit)->Unit): T { val f=CompletableFuture<Result<T>>(); call { f.complete(it) }; return f.get(20,TimeUnit.SECONDS).getOrThrow() }
        fun shot(name:String) { i.waitForIdleSync(); val b=i.uiAutomation.takeScreenshot(); java.io.File(c.getExternalFilesDir(null),"pr68-$name-${if (dark) "dark" else "light"}.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; b.recycle() }
        try {
            repeat(9) { index -> await<Unit> { store.put(TimetableEntry("course$index",listOf("高等数学 B-1","大学英语","管理学原理","大学体育基础素质课","管理科学与工程导论","思想道德与法治","大学生心理健康教育","程序设计","物理实验")[index],index%7+1,480+index%3*120,570+index%3*120,"A101"),it) } }
            val state=await(store::load)
            val snapshot=CourseColors.snapshot(c,state); val key=snapshot.identity.key(null,"course0")!!
            CourseColors.save(c,key,0xb49138); android.util.Log.i("PR68","saved")
            assertEquals(0xb49138,CourseColors.snapshot(c,state).manual(key))
            assertEquals(0xb49138,CourseColors.snapshot(c,state.copy(entries=state.entries.map { it.copy(title="改名") })).manual(key))
            CourseColors.save(c,key,null)
            assertNull(CourseColors.snapshot(c,state).manual(key))
            ScheduleStores.overrideFactory={store}
            launchPhoneTestActivity().use { scenario ->
                Thread.sleep(1200); shot("week-light")
                scenario.onActivity { PhoneEditors(it,it.source).courseManagement() }
                Thread.sleep(300); shot("management-light")
                onView(withText(startsWith("高等数学 B-1\n"))).perform(click())
                shot("editor")
                onView(withText(startsWith("课程颜色 ·"))).perform(scrollTo(),click())
                shot("picker-light")
                onView(withContentDescription("预设颜色 #%06X".format(CourseColors.palette[0]))).perform(scrollTo(),click())
                onView(withText("取消")).perform(click())
                assertNull(CourseColors.snapshot(c,state).manual(key))
                onView(withText(startsWith("课程颜色 ·"))).perform(scrollTo(),click())
                onView(withContentDescription("预设颜色 #%06X".format(CourseColors.palette[0]))).perform(scrollTo(),click())
                onView(withText("使用此颜色")).perform(click())
                onView(withText("保存修改")).perform(click())
                assertEquals(CourseColors.palette[0],CourseColors.snapshot(c,state).manual(key))
                assertTrue(await(store::load).classOverrides.isEmpty())
            }
        } finally { i.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(-1) }; ScheduleStores.overrideFactory=null; store.close(); executor.shutdown() }
    }
}

