package com.dormpanel.phone

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import com.dormpanel.app.schedule.*
import com.dormpanel.app.schedule.testing.V3UiFixture
import org.hamcrest.Matchers.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.*

class CourseCommonUxTest {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val c get()=i.targetContext
    private fun <T> await(call:((Result<T>)->Unit)->Unit):T {val f=CompletableFuture<Result<T>>();call{f.complete(it)};return f.get(20,TimeUnit.SECONDS).getOrThrow()}
    private fun runStore(block:(RoomScheduleStore)->Unit) {
        require(c.packageName.endsWith(".testbed"));val name="pr65-common-${UUID.randomUUID()}";WebDavSettings.overrideNamespace=name
        val executor=Executors.newSingleThreadExecutor();val store=RoomScheduleStore(Room.databaseBuilder(c,ScheduleDatabase::class.java,"$name.db").build(),executor)
        try{block(store)}finally{ScheduleStores.overrideFactory=null;WebDavSettings.overrideNamespace=null;store.close();executor.shutdown();executor.awaitTermination(20,TimeUnit.SECONDS)}
    }
    private fun open(title:String) {Thread.sleep(500);onView(withText("管理")).perform(click());onView(withText("课程管理")).perform(click());onView(withText(startsWith(title+"\n"))).perform(click())}
    private fun add(title:String) {onView(withText("添加其他本地课程")).perform(scrollTo(),click());onView(withText(startsWith(title+" · 星期"))).perform(click());onView(withText("添加条目")).perform(click())}
    private fun shot(name:String) {i.waitForIdleSync();val b=i.uiAutomation.takeScreenshot();java.io.File(c.getExternalFilesDir(null),"pr65-final-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    @Test fun commonValuesFollowSelectionButPreserveTypedDraftAndPreviewScope()=runStore {store->
        val entries=listOf(TimetableEntry("a","甲课",1,540,600),TimetableEntry("b","乙课",2,660,720),TimetableEntry("c","丙课",3,780,840))
        entries.forEach{entry->await<Unit>{store.put(entry,it)}}
        val old=entries.map{ClassOverride.of(null,it.id,null,ClassPatch(teacher="教师${it.id}",note="备注${it.id}"))}
        await<Unit>{store.putClassOverrides(await(store::load),old,it)}
        ScheduleStores.overrideFactory={store}
        launchPhoneTestActivity().use {
            open("甲课");add("乙课")
            onView(withText("时段 2 · 参与本次修改")).perform(scrollTo(),click())
            onView(withContentDescription("课程名称")).perform(scrollTo()).check(matches(withText("")))
            onView(withText("教师：各时段不同")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withContentDescription("教师")).check(matches(withText("")))
            onView(withText("时段 2 · 参与本次修改")).perform(scrollTo(),click())
            onView(withContentDescription("课程名称")).perform(scrollTo()).check(matches(withText("甲课")))
            onView(withContentDescription("课程名称")).perform(replaceText("保留统一草稿"),closeSoftKeyboard())
            onView(withText("统一修改课程名称")).perform(scrollTo(),click())
            onView(withContentDescription("备注")).perform(scrollTo(),replaceText("未启用的草稿"),closeSoftKeyboard())
            add("丙课");onView(withText("时段 3 · 参与本次修改")).perform(scrollTo(),click())
            onView(withContentDescription("课程名称")).perform(scrollTo()).check(matches(withText("保留统一草稿")))
            onView(withContentDescription("备注")).perform(scrollTo()).check(matches(withText("未启用的草稿")))
            onView(withText("保存修改")).perform(click())
            onView(withText(containsString("统一字段仅应用：时段 1、时段 3"))).check(matches(isDisplayed()));shot("common-scope")
            onView(withText("继续编辑")).perform(click())
            onView(withText("时段 3 · 参与本次修改")).perform(scrollTo(),click())
            onView(withContentDescription("课程名称")).perform(scrollTo()).check(matches(withText("保留统一草稿")))
            onView(withText("保存修改")).perform(click());onView(withText("确认保存")).perform(click());Thread.sleep(500)
            val state=await<ScheduleState>(store::load)
            assertEquals(old[1],state.editsByKey[old[1].key]);assertEquals(old[2],state.editsByKey[old[2].key])
            assertEquals("保留统一草稿",state.editsByKey[old[0].key]!!.title)
            assertEquals("教师a",state.editsByKey[old[0].key]!!.teacher);assertEquals("备注a",state.editsByKey[old[0].key]!!.note)
        }
    }
    @Test fun explicitRestoreRemovesOverrideAndNoOpDoesNotCreateEmptyRecord()=runStore {store->
        await<Unit>{store.put(TimetableEntry("a","原课程",1,540,600),it)}
        val old=ClassOverride.of(null,"a",null,ClassPatch(title="已改名称",note=""))
        val weekly=ClassOverride.of(null,"a","2026-10-26",ClassPatch(location="指定周教室"))
        listOf(old,weekly).forEach{edit->await<Unit>{store.putClassOverride(edit,edit.key,it)}}
        ScheduleStores.overrideFactory={store}
        launchPhoneTestActivity().use {
            open("已改名称")
            onView(withText("恢复时段 1 全部继承")).perform(scrollTo(),click())
            onView(withText("保存修改")).perform(click())
            onView(withText(containsString("移除全周覆盖，恢复全部继承"))).check(matches(isDisplayed()));shot("reset")
            onView(withText("确认保存")).perform(click());Thread.sleep(500)
            assertEquals(listOf(weekly),await<ScheduleState>(store::load).classOverrides)
            onView(withText(startsWith("原课程\n"))).perform(click())
            onView(withText("恢复时段 1 全部继承")).perform(scrollTo(),click())
            onView(withText("保存修改")).perform(click())
            onView(withText(startsWith("没有实际修改"))).perform(scrollTo()).check(matches(isDisplayed()))
            assertEquals(listOf(weekly),await<ScheduleState>(store::load).classOverrides)
        }
    }
    @Test fun unconfirmedCommitShowsProtectionInsteadOfFailureAndReadsRealStateAfterRecovery() {
        V3UiFixture(c).use {remote->
            val name="pr65-uncertain-${UUID.randomUUID()}.db";val executor=Executors.newSingleThreadExecutor()
            fun database()=Room.databaseBuilder(c,ScheduleDatabase::class.java,name).build()
            var fault=false
            val store=RoomScheduleStore(database(),executor,profiles=TermScheduleProfileStore(c),reopenDatabase=::database,v3Fault={if(fault&&it=="after-commit")error("injected acknowledgement")})
            try {
                await<Unit>{store.put(TimetableEntry("a","确认测试",1,540,600),it)}
                store.prepareV3(remote.account.baseUrl)
                for(n in 0..31){if(store.synchronizeV3(remote.account)=="OBSERVED")break}
                ScheduleStores.overrideFactory={store}
                launchPhoneTestActivity().use {scenario->
                    val deadline=System.currentTimeMillis()+30000
                    while(true){var ready=false;scenario.onActivity{ready=it.source.editable&&!it.sharedSync.busy};if(ready)break;check(System.currentTimeMillis()<deadline);Thread.sleep(100)}
                    open("确认测试")
                    onView(withText("编辑时段 1")).perform(scrollTo(),click())
                    onView(withContentDescription("Location")).perform(scrollTo(),replaceText("确认后教室"),closeSoftKeyboard());onView(withText("Save")).perform(click())
                    onView(withText("保存修改")).perform(click());fault=true;onView(withText("确认保存")).perform(click());Thread.sleep(500)
                    onView(withText("保存结果尚待确认")).check(matches(isDisplayed()));shot("uncertain")
                    assertTrue(store.businessPaused())
                    onView(withText("知道了")).perform(click())
                    onView(withText("保存修改")).check(matches(not(isEnabled())))
                    fault=false;store.reopenV3Store()
                    await<Unit>{callback->scenario.onActivity{it.source.reload(callback)}}
                    scenario.onActivity{assertEquals("确认后教室",it.source.state.classOverrides.single().location)}
                    onView(withText("放弃草稿并重新加载")).perform(scrollTo(),click());onView(withText("放弃并重新加载")).perform(click());Thread.sleep(500)
                    onView(withText("编辑时段 1")).perform(scrollTo(),click());onView(withContentDescription("Location")).perform(scrollTo()).check(matches(withText("确认后教室")))
                }
            }finally{ScheduleStores.overrideFactory=null;fault=false;store.close();executor.shutdown();executor.awaitTermination(20,TimeUnit.SECONDS)}
        }
    }
}
