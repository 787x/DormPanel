package com.dormpanel.phone

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import org.hamcrest.Matchers.*
import com.dormpanel.app.schedule.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.*

class CourseColorAcceptanceTest {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val c get()=i.targetContext
    private fun <T> await(call:((Result<T>)->Unit)->Unit):T {val f=CompletableFuture<Result<T>>();call{f.complete(it)};return f.get(20,TimeUnit.SECONDS).getOrThrow()}
    private fun isolated(block:(RoomScheduleStore)->Unit) {
        require(c.packageName.endsWith(".pr68testbed"))
        val e=Executors.newSingleThreadExecutor();val name="pr68-${java.util.UUID.randomUUID()}"
        val store=RoomScheduleStore(Room.databaseBuilder(c,ScheduleDatabase::class.java,"$name.db").build(),e)
        try { block(store) } catch(error:Throwable) {
            val b=i.uiAutomation.takeScreenshot();java.io.File(c.getExternalFilesDir(null),"pr68-acceptance-failure.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()
            throw error
        } finally { ScheduleStores.overrideFactory=null;store.close();e.shutdown() }
    }
    private fun focus(scenario:androidx.test.core.app.ActivityScenario<TimetableActivity>) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${c.packageName}/com.dormpanel.phone.TimetableActivity")).use{it.readBytes()}
        val until=System.currentTimeMillis()+10000;var ready=false
        while(!ready && System.currentTimeMillis()<until){scenario.onActivity{ready=it.source.ready && !it.source.error && it.hasWindowFocus()};Thread.sleep(100)}
        assertTrue("Activity readable and focused",ready)
    }
    private fun chooseCustom() {
        onView(withText(startsWith("课程颜色 ·"))).perform(scrollTo(),click())
        onView(withHint("自定义颜色 #RRGGBB")).perform(scrollTo(),replaceText("bad"),closeSoftKeyboard())
        onView(withId(android.R.id.button1)).perform(click())
        onView(withHint("自定义颜色 #RRGGBB")).check(matches(isDisplayed()))
        // A preset must clear a prior validation error, then custom input must override it.
        onView(withContentDescription("预设颜色 #%06X".format(CourseColors.palette[0]))).perform(scrollTo(),click())
        onView(withHint("自定义颜色 #RRGGBB")).perform(scrollTo(),replaceText("#B49138"),closeSoftKeyboard())
        onView(withText("使用此颜色")).perform(click())
    }
    @Test fun businessFailureRetainsColorDraftThenRetriesAtomically()=isolated {store->
        await<Unit>{store.put(TimetableEntry("a","验收课程",1,540,600),it)}
        CourseColors.save(c,CourseColors.snapshot(c,await(store::load)).identity.key(null,"a")!!,null)
        val unconfirmed=InstrumentationRegistry.getArguments().getString("unconfirmed")=="true"
        var fail=true
        ScheduleStores.overrideFactory={object:ScheduleStore by store {
            override fun putClassOverrides(expected:ScheduleState,items:List<ClassOverride>,callback:(Result<Unit>)->Unit) {
                if(fail) callback(Result.failure(if(unconfirmed) ScheduleCommitUnconfirmed(IllegalStateException("PR68 注入待确认")) else IllegalStateException("PR68 注入业务失败"))) else store.putClassOverrides(expected,items,callback)
            }
        }}
        launchPhoneTestActivity().use {scenario->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${c.packageName}/com.dormpanel.phone.TimetableActivity")).use{it.readBytes()}
            val until=System.currentTimeMillis()+10000
            var ready=false
            while(!ready && System.currentTimeMillis()<until){scenario.onActivity{ready=it.source.ready && !it.source.error && it.hasWindowFocus()};Thread.sleep(100)}
            assertTrue("Activity readable and focused",ready)
            scenario.onActivity { (TimetableActivity::class.java.getDeclaredField("editors").apply{isAccessible=true}.get(it) as PhoneEditors).courseManagement() }
            onView(withText(startsWith("验收课程\n"))).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());chooseCustom()
            onView(withText("统一修改课程名称")).perform(scrollTo(),click())
            onView(withContentDescription("课程名称")).perform(scrollTo(),replaceText("成功后新名称"),closeSoftKeyboard())
            onView(withText("保存修改")).perform(click());onView(withText("确认保存")).perform(click())
            if(unconfirmed) {
                onView(withText("保存结果尚待确认")).check(matches(isDisplayed()))
                onView(withText("知道了")).perform(click())
                val saved=await<ScheduleState>(store::load);val color=CourseColors.snapshot(c,saved)
                assertNull(color.manual(color.identity.key(null,"a")!!));assertTrue(saved.classOverrides.isEmpty())
                onView(withText(startsWith("课程颜色 · 手动"))).perform(scrollTo()).check(matches(isDisplayed()))
                return@use
            }
            onView(withText("PR68 注入业务失败")).perform(scrollTo()).check(matches(isDisplayed()))
            val state=await<ScheduleState>(store::load);val key=CourseColors.snapshot(c,state).identity.key(null,"a")!!
            assertNull(CourseColors.snapshot(c,state).manual(key));assertTrue(state.classOverrides.isEmpty())
            fail=false
            onView(withText("保存修改")).perform(click());onView(withText("确认保存")).perform(click())
            Thread.sleep(500)
            assertEquals(0xb49138,CourseColors.snapshot(c,await(store::load)).manual(key))
            assertEquals("成功后新名称",await<ScheduleState>(store::load).classOverrides.single().title)
        }
    }
    @Test fun readonlyAllowsLocalColorButRejectsBusinessWriteAndReadFailureShowsNoCachedList()=isolated {store->
        await<Unit>{store.put(TimetableEntry("r","只读课程",1,540,600),it)}
        CourseColors.save(c,CourseColors.snapshot(c,await(store::load)).identity.key(null,"r")!!,null)
        var badRead=false;var writes=0
        ScheduleStores.overrideFactory={object:ScheduleStore by store,ScheduleV2Store by store {
            override fun v2DatasetPaused()=true
            override fun load(callback:(Result<ScheduleState>)->Unit) { if(badRead)callback(Result.failure(IllegalStateException("read failure")))else store.load(callback) }
            override fun putClassOverrides(expected:ScheduleState,items:List<ClassOverride>,callback:(Result<Unit>)->Unit) {writes++;callback(Result.failure(IllegalStateException("must not write")))}
        }}
        launchPhoneTestActivity().use {scenario->
            focus(scenario);scenario.onActivity {assertFalse(it.source.editable);(TimetableActivity::class.java.getDeclaredField("editors").apply{isAccessible=true}.get(it) as PhoneEditors).courseManagement()}
            onView(withText(startsWith("只读课程\n"))).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());chooseCustom()
            onView(withText("统一修改课程名称")).perform(scrollTo(),click())
            onView(withContentDescription("课程名称")).perform(scrollTo(),replaceText("不应写入"),closeSoftKeyboard())
            onView(withText("保存修改")).perform(click())
            assertEquals(0,writes)
            onView(withText("当前课表只读，业务草稿保留")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("统一修改课程名称")).perform(scrollTo(),click())
            onView(withText("保存修改")).perform(click())
            val state=await<ScheduleState>(store::load);val key=CourseColors.snapshot(c,state).identity.key(null,"r")!!
            assertEquals(0xb49138,CourseColors.snapshot(c,state).manual(key));assertEquals(0,writes)
            badRead=true;scenario.onActivity{it.source.reload()};Thread.sleep(200)
            onView(withText("本地课程读取失败，请关闭并恢复读取")).check(matches(isDisplayed()))
        }
    }
    @Test fun committedBusinessReadFailureCanRecoverWithoutLosingColorDraft()=isolated {store->
        await<Unit>{store.put(TimetableEntry("recovery","恢复验收",1,540,600),it)}
        val initial=await<ScheduleState>(store::load);val key=CourseColors.snapshot(c,initial).identity.key(null,"recovery")!!
        CourseColors.save(c,key,null)
        var badRead=false;var writes=0
        ScheduleStores.overrideFactory={object:ScheduleStore by store {
            override fun load(callback:(Result<ScheduleState>)->Unit){if(badRead)callback(Result.failure(IllegalStateException("注入回读失败")))else store.load(callback)}
            override fun putClassOverrides(expected:ScheduleState,items:List<ClassOverride>,callback:(Result<Unit>)->Unit){writes++;store.putClassOverrides(expected,items){badRead=true;callback(it)}}
        }}
        launchPhoneTestActivity().use {scenario->
            focus(scenario);scenario.onActivity{(TimetableActivity::class.java.getDeclaredField("editors").apply{isAccessible=true}.get(it) as PhoneEditors).courseManagement()}
            onView(withText(startsWith("恢复验收\n"))).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());chooseCustom()
            onView(withText("统一修改课程名称")).perform(scrollTo(),click())
            onView(withContentDescription("课程名称")).perform(scrollTo(),replaceText("已提交课程"),closeSoftKeyboard())
            onView(withText("保存修改")).perform(click());onView(withText("确认保存")).perform(click())
            onView(withText("业务修改已提交；颜色保存失败，请重试颜色保存")).perform(scrollTo()).check(matches(isDisplayed()))
            assertEquals(1,writes);assertNull(CourseColors.snapshot(c,initial).manual(key))
            assertEquals("已提交课程",await<ScheduleState>(store::load).classOverrides.single().title)
            badRead=false
            onView(withText("重试本地读取（保留草稿）")).perform(scrollTo(),click())
            onView(withText("保存修改")).perform(click())
            assertEquals(1,writes);assertEquals(0xb49138,CourseColors.snapshot(c,await(store::load)).manual(key))
        }
    }
    @Test fun importReplacementAndFreshProcessColorSeed()=isolated {store->
        val bytes=i.context.assets.open("hubei_2026-2027-1.csv").readBytes()
        val preview=TimetableImporter().parse(ScheduleArtifact("fixture.csv",bytes))
        val imported=await<ImportCommit>{IcsScheduleImporter().commit(preview,store,"颜色导入验收",null,1,it)}
        val initial=await<ScheduleState>(store::load)
        val candidate=CourseBatch.candidates(initial).first{it.sourceId==imported.source.id&&it.series.size>=2}
        val colors=CourseColors.snapshot(c,initial);val key=colors.key(candidate)!!
        CourseColors.save(c,key,0x123456)
        // The actual importer replaces the source, with a changed file hash and new instance IDs.
        val changed=TimetableImporter().parse(ScheduleArtifact("fixture.csv",bytes+"\n".toByteArray()))
        await<ImportCommit>{IcsScheduleImporter().commit(changed,store,"颜色导入验收",imported.source.id,2,it)}
        val replaced=await<ScheduleState>(store::load)
        assertEquals(imported.source.id,replaced.sources.single().id)
        assertEquals(0x123456,CourseColors.snapshot(c,replaced).manual(key))
        val renamed=replaced.copy(classOverrides=candidate.series.map{ClassOverride.of(candidate.sourceId,it,null,ClassPatch(title="覆盖名称"))})
        assertEquals(key,CourseColors.snapshot(c,renamed).key(candidate))
        val resized=replaced.copy(imported=replaced.imported.filterNot{it.seriesId==candidate.series.last()})
        assertEquals(0x123456,CourseColors.snapshot(c,resized).manual(key))
        await<Unit>{store.deleteImport(imported.source.id,it)}
        val newImport=await<ImportCommit>{IcsScheduleImporter().commit(preview,store,"颜色导入验收",null,3,it)}
        assertNotEquals(imported.source.id,newImport.source.id)
        val newState=await<ScheduleState>(store::load);val newColors=CourseColors.snapshot(c,newState)
        val newCandidate=CourseBatch.candidates(newState).first{it.originalTitle==candidate.originalTitle}
        assertNotEquals(key,newColors.key(newCandidate));assertNull(newColors.manual(newColors.key(newCandidate)!!))
    }
}
