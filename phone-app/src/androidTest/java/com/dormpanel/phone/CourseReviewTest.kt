package com.dormpanel.phone

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import com.dormpanel.app.schedule.*
import org.hamcrest.Matchers.*
import org.junit.Test
import org.junit.Assert.*
import java.time.*
import java.util.UUID
import java.util.concurrent.*

class CourseReviewTest {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val c get()=i.targetContext
    private fun <T> await(call:((Result<T>)->Unit)->Unit):T {
        val f=CompletableFuture<Result<T>>();call{f.complete(it)};return f.get(20,TimeUnit.SECONDS).getOrThrow()
    }
    private fun runStore(test:(RoomScheduleStore)->Unit) {
        require(c.packageName.endsWith(".testbed"))
        val name="pr65-review-${UUID.randomUUID()}";val executor=Executors.newSingleThreadExecutor()
        WebDavSettings.overrideNamespace=name
        val store=RoomScheduleStore(Room.databaseBuilder(c,ScheduleDatabase::class.java,"$name.db").build(),executor)
        try {test(store)} finally {ScheduleStores.overrideFactory=null;WebDavSettings.overrideNamespace=null;store.close();executor.shutdown();executor.awaitTermination(20,TimeUnit.SECONDS)}
    }
    private fun openManagement() {Thread.sleep(500);onView(withText("管理")).perform(click());onView(withText("课程管理")).perform(click())}
    private fun editLocation(number:Int,value:String) {
        onView(withText("编辑时段 $number")).perform(scrollTo(),click())
        onView(withContentDescription("Location")).perform(scrollTo(),replaceText(value),closeSoftKeyboard())
        onView(withText("Save")).perform(click())
    }
    private fun shot(name:String) {
        i.waitForIdleSync();val image=i.uiAutomation.takeScreenshot()
        java.io.File(c.getExternalFilesDir(null),"pr65-review-$name.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    @Test fun fullyHiddenSeriesStillEditableAndDayAdjustmentPreserved()=runStore {store->
        val zone=ZoneId.systemDefault();val monday=LocalDate.of(2026,10,26)
        val rows=listOf("a","b").mapIndexed{n,id->val start=monday.plusDays(n.toLong()).atTime(9,0).atZone(zone).toInstant().toEpochMilli()
            ImportedClassOccurrence("instance-$id","source",id,null,start,"隐藏测试",start,start+3600000L,zone.id,"原教室","",null)}
        val source=ImportSource("source","隔离来源","test.ics","ics",null,null,"0".repeat(64),0,zone.id,2,rows.first().start,rows.last().end)
        await<ImportCommit>{store.commitImport(source,rows,false,it)}
        val day=DayAdjustment("2026-10-27","none")
        await<Unit>{store.putDayAdjustment(day,day.date,it)}
        val old=ClassOverride.of("source","b",null,ClassPatch(teacher="原教师",note=""))
        val weekly=ClassOverride.of("source","b","2026-10-27",ClassPatch(location="单周隐藏教室"))
        await<Unit>{store.putClassOverride(old,old.key,it)};await<Unit>{store.putClassOverride(weekly,weekly.key,it)}
        ScheduleStores.overrideFactory={store}
        launchPhoneTestActivity().use {
            openManagement();onView(withText(startsWith("隐藏测试\n"))).perform(click())
            onView(withText("编辑时段 2")).perform(scrollTo()).check(matches(isDisplayed()))
            shot("hidden")
            editLocation(2,"隐藏系列新教室")
            onView(withText("保存修改")).perform(click());onView(withText("确认保存")).perform(click());Thread.sleep(500)
            val saved=await<ScheduleState>(store::load)
            assertEquals(day,saved.dayAdjustments.single());assertEquals(rows,saved.imported)
            val edited=saved.classOverrides.single{it.anchorDate==null}
            assertEquals("b",edited.seriesId);assertEquals("隐藏系列新教室",edited.location)
            assertEquals("原教师",edited.teacher);assertEquals("",edited.note);assertEquals(weekly,saved.classOverrides.single{it.anchorDate!=null})
            assertEquals(1,CourseBatch.occurrences(saved,CourseBatch.candidates(saved).single(),zone,monday).size)
        }
    }
    @Test fun localExplicitAddAutoSelectionAndExcludedDraftDecision()=runStore {store->
        listOf(TimetableEntry("a","同名课程",1,540,600),TimetableEntry("b","同名课程",2,660,720)).forEach{row->await<Unit>{store.put(row,it)}}
        ScheduleStores.overrideFactory={store}
        launchPhoneTestActivity().use {
            openManagement();onView(withText(containsString("星期1 ·"))).perform(click())
            onView(withText("编辑时段 2")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            onView(withText("添加其他本地课程")).perform(scrollTo(),click())
            onView(withText(startsWith("同名课程 · 星期2"))).perform(click())
            onView(withText("添加条目")).perform(click())
            onView(withText("时段 2 · 参与本次修改")).perform(scrollTo()).check(matches(not(isChecked())))
            editLocation(2,"附加教室")
            onView(withText("时段 2 · 已修改草稿，已纳入保存")).perform(scrollTo()).check(matches(isChecked()))
            // Re-excluding an edited slot requires an explicit decision before any preview/save.
            onView(withText("时段 2 · 已修改草稿，已纳入保存")).perform(click())
            editLocation(1,"当前教室")
            onView(withText("保存修改")).perform(click())
            onView(withText("存在未参与保存的已编辑时段")).check(matches(isDisplayed()))
            assertTrue(await<ScheduleState>(store::load).classOverrides.isEmpty())
            onView(withText("纳入保存")).perform(click());shot("participation")
            onView(withText("继续编辑")).perform(click())
            onView(withText("时段 2 · 已修改草稿，已纳入保存")).perform(scrollTo(),click())
            onView(withText("保存修改")).perform(click());onView(withText("放弃这些草稿")).perform(click())
            onView(withText("变更预览 · 1 个时段")).check(matches(isDisplayed()))
            onView(withText("继续编辑")).perform(click())
            onView(withText("时段 2 · 参与本次修改")).perform(scrollTo()).check(matches(not(isChecked())))
            editLocation(2,"附加教室")
            onView(withText("保存修改")).perform(click())
            onView(withText("确认保存")).perform(click());Thread.sleep(500)
            val saved=await<ScheduleState>(store::load).classOverrides
            assertEquals(2,saved.size);assertEquals("附加教室",saved.single{it.seriesId=="b"}.location)
            assertEquals("当前教室",saved.single{it.seriesId=="a"}.location)
        }
    }
    @Test fun concurrentChangeReloadDiscardsDraftAndReplacesOldEditor()=runStore {store->
        val original=TimetableEntry("a","重载课程",1,540,600)
        await<Unit>{store.put(original,it)};ScheduleStores.overrideFactory={store}
        launchPhoneTestActivity().use {
            openManagement();onView(withText(startsWith("重载课程\n"))).perform(click())
            editLocation(1,"旧草稿教室")
            await<Unit>{store.put(original.copy(title="并发名称"),it)}
            onView(withText("保存修改")).perform(click());onView(withText("确认保存")).perform(click());Thread.sleep(500)
            onView(withText(containsString("课表或活动版本已变化"))).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("放弃草稿并重新加载")).perform(scrollTo(),click())
            onView(withText("放弃并重新加载")).perform(click());Thread.sleep(500)
            onView(withContentDescription("课程名称")).check(matches(withText("并发名称")))
            assertTrue(await<ScheduleState>(store::load).classOverrides.isEmpty())
            onView(withText("取消")).perform(click())
            onView(withText("课程管理")).check(matches(isDisplayed()))
            onView(withText("编辑时段 1")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        }
    }
    @Test fun committedRefreshFailureShowsAccurateDialog()=runStore {store->
        await<Unit>{store.put(TimetableEntry("a","回执课程",1,540,600),it)}
        var failRead=false
        val wrapper=object:ScheduleStore by store {
            override fun load(callback:(Result<ScheduleState>)->Unit) {
                if(failRead)callback(Result.failure(IllegalStateException("injected refresh"))) else store.load(callback)
            }
            override fun putClassOverrides(expected:ScheduleState,items:List<ClassOverride>,callback:(Result<Unit>)->Unit) {
                store.putClassOverrides(expected,items){result->if(result.isSuccess)failRead=true;callback(result)}
            }
        }
        ScheduleStores.overrideFactory={wrapper}
        launchPhoneTestActivity().use {scenario->
            openManagement();onView(withText(startsWith("回执课程\n"))).perform(click())
            editLocation(1,"已提交教室")
            onView(withText("保存修改")).perform(click());onView(withText("确认保存")).perform(click());Thread.sleep(500)
            onView(withText("修改已提交，刷新失败")).check(matches(isDisplayed()));shot("receipt")
            onView(withText(startsWith("已保存到本机，但重新读取失败"))).check(matches(isDisplayed()))
            assertEquals("已提交教室",await<ScheduleState>(store::load).classOverrides.single().location)
            scenario.onActivity{assertTrue(it.source.error);assertFalse(it.source.editable)}
            failRead=false;onView(withText("恢复读取")).perform(click());Thread.sleep(500)
            scenario.onActivity{assertTrue(it.source.editable)}
            assertEquals(1,await<ScheduleState>(store::load).classOverrides.size)
        }
    }
    @Test fun committedButRefreshFailedReportsSuccessAndKeepsReadGuard()=runStore {store->
        await<Unit>{store.put(TimetableEntry("a","刷新课程",1,540,600),it)}
        var failRead=false;var syncNotifications=0
        val wrapper=object:ScheduleStore by store {
            override fun load(callback:(Result<ScheduleState>)->Unit) {
                if(failRead)callback(Result.failure(IllegalStateException("injected refresh"))) else store.load(callback)
            }
            override fun putClassOverrides(expected:ScheduleState,items:List<ClassOverride>,callback:(Result<Unit>)->Unit) {
                store.putClassOverrides(expected,items){result->if(result.isSuccess)failRead=true;callback(result)}
            }
        }
        lateinit var source:ScheduleSource
        i.runOnMainSync{source=ScheduleSource(wrapper)}
        await<Unit>{callback->i.runOnMainSync{source.reload(callback)}}
        i.runOnMainSync{source.localCommitted={syncNotifications++}}
        val receipt=await<ClassBatchSaveReceipt>{callback->i.runOnMainSync{source.saveClassOverrides(source.state,listOf(ClassOverride.of(null,"a",null,ClassPatch(location="已提交教室"))),callback)}}
        assertFalse(receipt.refreshed);assertTrue(receipt.message.contains("已保存到本机"));assertTrue(receipt.message.contains("已经提交"))
        assertEquals("已提交教室",await<ScheduleState>(store::load).classOverrides.single().location)
        i.runOnMainSync{assertTrue(source.error);assertFalse(source.editable)}
        assertEquals(0,syncNotifications)
        failRead=false;await<Unit>{callback->i.runOnMainSync{source.reload(callback)}}
        i.runOnMainSync{assertTrue(source.editable);source.close()}
    }
}
