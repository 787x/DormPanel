package com.dormpanel.phone

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import com.dormpanel.app.schedule.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.*

class SharedCourseManagementTest {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val c get()=i.targetContext
    private fun <T> await(call: ((Result<T>)->Unit)->Unit): T {
        val latch=CountDownLatch(1);var result:Result<T>?=null
        call {result=it;latch.countDown()};check(latch.await(30,TimeUnit.SECONDS));return result!!.getOrThrow()
    }
    private var shotSuffix=""
    private fun shot(name:String) {i.waitForIdleSync();Thread.sleep(500);val bitmap=i.uiAutomation.takeScreenshot()
        java.io.File(c.getExternalFilesDir(null),"pr70-$name$shotSuffix.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    @Test fun rotationRetainsAcceptedCourseDraftWithoutWriting() {
        require(c.packageName.endsWith(".testbed"))
        val state=ScheduleState(entries=listOf(TimetableEntry("draft","草稿恢复",1,480,540)),sharedRecords=emptyList())
        ScheduleStores.overrideFactory={object:ScheduleStore {
            override fun load(callback:(Result<ScheduleState>)->Unit)=callback(Result.success(state))
            override fun put(event:CalendarEvent,callback:(Result<Unit>)->Unit)=error("unexpected write")
            override fun put(entry:TimetableEntry,callback:(Result<Unit>)->Unit)=error("unexpected write")
            override fun deleteEvent(id:String,callback:(Result<Unit>)->Unit)=error("unexpected write")
            override fun deleteEntry(id:String,callback:(Result<Unit>)->Unit)=error("unexpected write")
            override fun close() {}
        }}
        fun editor(activity:TimetableActivity)=TimetableActivity::class.java.getDeclaredField("editors").apply {isAccessible=true}.get(activity) as PhoneEditors
        try {launchPhoneTestActivity().use { scenario ->
            scenario.onActivity { activity ->editor(activity).courseManagement(ScheduleProjection.week(state,java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY),activity.source.clock.zone()).first())}
            Thread.sleep(500)
            onView(withText("统一修改教师")).inRoot(isDialog()).perform(scrollTo(),click())
            onView(withContentDescription("教师")).inRoot(isDialog()).perform(scrollTo(),replaceText("旋转保留教师"),closeSoftKeyboard())
            onView(withText("新增安排")).inRoot(isDialog()).perform(scrollTo(),click())
            onView(withContentDescription("有效周次（逗号分隔，1–104）")).inRoot(isDialog()).perform(scrollTo(),replaceText("1,3,5"),closeSoftKeyboard())
            onView(withText("保留草稿")).inRoot(isDialog()).perform(click())
            onView(withText("管理原时段周次 / 复制 · draft")).inRoot(isDialog()).perform(scrollTo(),click())
            onView(withContentDescription("有效周次（逗号分隔，1–104）")).inRoot(isDialog()).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText("")))
            onView(withContentDescription("有效周次（逗号分隔，1–104）")).inRoot(isDialog()).perform(scrollTo(),replaceText("1,3,5"),closeSoftKeyboard())
            onView(withText("保留草稿")).inRoot(isDialog()).perform(click())
            onView(withText("请明确确认无限重复改为有限范围的影响")).inRoot(isDialog()).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withText("明确将原无限重复课程改为所选有限周；范围外不再上课")).inRoot(isDialog()).perform(scrollTo(),click())
            onView(withText("保留草稿")).inRoot(isDialog()).perform(click())
            scenario.recreate();Thread.sleep(800)
            scenario.onActivity { activity ->
                val draft=editor(activity).captureDraft()!!
                assertEquals(2,draft.slots.size);assertTrue(draft.slots.all {it.weeks==listOf(1,3,5)})
                assertEquals(true to "旋转保留教师",draft.common[1])
                assertEquals(state,draft.expected)
            }
        }} finally {ScheduleStores.overrideFactory=null}
    }
    @Test fun lightCourseManagementAndPreview() {
        val previous=androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
        try {
            shotSuffix="-light";androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
            atomicColorScheduleAndManagementPreview()
        } finally {androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(previous)}
    }
    @Test fun sharedScheduleWidgetProjectionAndAccent() {
        val slot=CourseSlot("widget-slot",null,"x",null,"2026-09-07",listOf(1,3),2,480,540,0,0,true,"共享 Widget 课程","A","T","N")
        val state=SharedCourseColors.change(ScheduleState(entries=listOf(TimetableEntry("x","Owner",1,480,540)),sharedRecords=listOf(SharedCourseSchedule.record(slot))),null,"x",0x336699)
        val zone=java.time.ZoneId.systemDefault();val now=java.time.LocalDate.parse("2026-09-08").atTime(7,0).atZone(zone).toInstant()
        val day=TodayCourses.project(state,now,zone);assertEquals("widget-slot",day.all.single().entry.id)
        val method=TodayWidgetUpdates::class.java.getDeclaredMethod("render",android.content.Context::class.java,Int::class.javaPrimitiveType,Pair::class.java,String::class.java).apply {isAccessible=true}
        i.runOnMainSync {
            val views=method.invoke(TodayWidgetUpdates,c,-1,state to day,null) as android.widget.RemoteViews
            val root=views.apply(c,android.widget.FrameLayout(c))
            val stripe=root.findViewById<android.widget.ImageView>(R.id.widget_course_color)
            assertEquals(0xff336699.toInt(),(stripe.background as android.graphics.drawable.ColorDrawable).color)
        }
        val disabled=state.copy(sharedRecords=state.sharedRecords!!.filterNot {it.typeId=="course.schedule"}+SharedCourseSchedule.record(slot.copy(enabled=false)))
        assertTrue(TodayCourses.project(disabled,now,zone).all.isEmpty())
    }
    @Test fun nodeFixtureCommitsThroughRoomWriter() {
        require(c.packageName.endsWith(".testbed"))
        val file=java.io.File(c.getExternalFilesDir(null),"pr70-node-state.dpv")
        org.junit.Assume.assumeTrue("explicit Node fixture required",file.isFile)
        val node=SyncV3Codec.decode(file.readBytes()).state!!
        com.dormpanel.app.schedule.testing.V3UiFixture(c).use { fixture ->
            val store=ScheduleStores.create(c) as RoomScheduleStore
            try {
                await<Unit>{store.put(node.entries.single(),it)}
                store.prepareV3(fixture.account.baseUrl,sharedFormat=true);repeat(16){store.synchronizeV3(fixture.account)}
                val expected=await<ScheduleState>(store::load)
                val candidate=CourseCandidate(null,node.entries.single().title,listOf("x"))
                await<Unit>{store.saveCourseWithColor(expected,node.classOverrides,candidate,SharedCourseColors.rgb(node,null,"x"),node.sharedRecords,it)}
                assertEquals(SyncV3Codec.businessHash(node),SyncV3Codec.businessHash(await<ScheduleState>(store::load)))
            } finally {store.close()}
        }
    }
    @Test fun atomicColorScheduleAndManagementPreview() {
        require(c.packageName.endsWith(".testbed"))
        com.dormpanel.app.schedule.testing.V3UiFixture(c).use { fixture ->
            val store=ScheduleStores.create(c) as RoomScheduleStore
            try {
            await<Unit> {store.put(TimetableEntry("x","PR70 离散周课程",1,480,540,"原教室"),it)}
            store.prepareV3(fixture.account.baseUrl,sharedFormat=true)
            repeat(16) {store.synchronizeV3(fixture.account)}
            val expected=await<ScheduleState>(store::load)
            assertNotNull(expected.sharedRecords)
            val slot=CourseSlot("slot-70",null,"x",null,"2026-09-07",listOf(1,3,5,9),2,600,660,0,0,true,"PR70 离散周课程","新教室","教师","备注")
            val candidate=CourseCandidate(null,"PR70 离散周课程",listOf("x"))
            val edit=ClassOverride.of(null,"x",null,ClassPatch(teacher="公共教师"))
            await<Unit> {store.saveCourseWithColor(expected,listOf(edit),candidate,0x336699,listOf(SharedCourseSchedule.record(slot)),it)}
            val saved=await<ScheduleState>(store::load)
            assertEquals(0x336699,SharedCourseColors.rgb(saved,null,"x"));assertEquals(slot,SharedCourseSchedule.slots(saved).single())
            assertEquals(edit,saved.classOverrides.single())
            val colors=CourseColors.snapshot(c,saved)
            assertEquals(0x336699,colors.rgb(colors.key(candidate)!!))
            assertEquals(SharedCourseColors.rgb(saved.copy(sharedRecords=saved.sharedRecords!!.filterNot { it.typeId=="course.color" }),null,"x"),colors.rgb(colors.key(candidate)!!,null))
            java.io.File(c.getExternalFilesDir(null),"pr70-room-state.dpv").writeBytes(SyncV3Codec.encode(V3Object(2,java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),
                epochId=java.util.UUID.randomUUID().toString(),epochNumber=1,descriptorHash="1".repeat(64),state=saved)))
            java.io.File(c.getExternalFilesDir(null),"pr70-room-hash.txt").writeText(SyncV3Codec.businessHash(saved))
            assertTrue(runCatching {await<Unit> {store.saveCourseWithColor(expected,emptyList(),candidate,null,emptyList(),it)}}.isFailure)
            assertEquals(SyncV3Codec.businessHash(saved),SyncV3Codec.businessHash(await<ScheduleState>(store::load)))
            ScheduleStores.overrideFactory={store}
            try {launchPhoneTestActivity().use { scenario ->
                Thread.sleep(1000)
                onView(withText("管理")).perform(click());onView(withText("课程管理")).perform(click());shot("course-list")
                onView(withText("返回")).perform(click())
                scenario.onActivity { activity ->
                    val row=ScheduleProjection.week(activity.source.state,java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY),activity.source.clock.zone()).first { it.seriesId=="x" }
                    val editor=TimetableActivity::class.java.getDeclaredField("editors").apply {isAccessible=true}.get(activity) as PhoneEditors
                    editor.courseManagement(row)
                }
                shot("course-editor")
                onView(withText(org.hamcrest.Matchers.startsWith("课程颜色 ·"))).perform(scrollTo(),click());shot("color-picker")
                onView(withText("取消")).perform(click())
                onView(withText("新增安排")).perform(scrollTo(),click());shot("slot-editor")
                onView(withContentDescription("有效周次（逗号分隔，1–104）")).perform(scrollTo(),replaceText("1,3,5"),closeSoftKeyboard())
                onView(withText("保留草稿")).perform(click())
                onView(withText("保存修改")).perform(click());shot("save-preview")
                onView(withText("确认保存")).perform(click());Thread.sleep(1000)
                assertEquals(2,SharedCourseSchedule.slots(await<ScheduleState>(store::load)).size)
            }} finally {ScheduleStores.overrideFactory=null}
            } finally {store.close()}
        }
    }
}
