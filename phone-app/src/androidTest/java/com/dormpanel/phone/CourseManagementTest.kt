package com.dormpanel.phone

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import com.dormpanel.app.schedule.*
import org.junit.Test
import org.junit.Assert.*
import java.time.*
import java.util.UUID
import java.util.concurrent.*

class CourseManagementTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = i.targetContext
    private fun <T> await(call: ((Result<T>) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1); var result: Result<T>? = null
        call { result = it; latch.countDown() }
        check(latch.await(20, TimeUnit.SECONDS)); return result!!.getOrThrow()
    }
    private var screenshotSuffix = ""
    private fun shot(name: String) {
        i.waitForIdleSync()
        val bitmap = i.uiAutomation.takeScreenshot()
        java.io.File(c.getExternalFilesDir(null), "pr65-$name$screenshotSuffix.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }; bitmap.recycle()
    }
    @Test fun activeV3BatchPublishesExistingRecords() {
        com.dormpanel.app.schedule.testing.V3UiFixture(c).use { fixture ->
            val activeStore = ScheduleStores.create(c) as RoomScheduleStore
            activeStore.prepareV3(fixture.account.baseUrl)
            repeat(16) { activeStore.synchronizeV3(fixture.account) }
            ScheduleStores.overrideFactory = { activeStore }
            launchPhoneTestActivity().use { scenario ->
                fun ready() {
                    val end = System.currentTimeMillis() + 60000
                    while (System.currentTimeMillis() < end) {
                        var ok = false
                        scenario.onActivity { ok = it.source.editable && !it.sharedSync.busy }
                        if (ok) return
                        Thread.sleep(100)
                    }
                    error("v3 did not become editable")
                }
                ready()
                scenario.onActivity { assertTrue(it.source.saveEntry("A",1,540,600,id=null)) }; ready()
                scenario.onActivity { assertTrue(it.source.saveEntry("B",2,660,720,id=null)) }; ready()
                val complete = CountDownLatch(1); var failure: Throwable? = null
                scenario.onActivity { activity ->
                    val captured = activity.source.state
                    assertEquals(2,captured.entries.size)
                    activity.source.saveClassOverrides(captured,captured.entries.map {
                        ClassOverride.of(null,it.id,null,ClassPatch(title="V3 批量课程",teacher="统一教师"))
                    }) { failure = it.exceptionOrNull(); complete.countDown() }
                }
                assertTrue(complete.await(20,TimeUnit.SECONDS)); failure?.let { throw it }; ready()
                scenario.onActivity { assertEquals(2,it.source.state.classOverrides.size); it.sharedSync.sync() }; ready()
                val end = System.currentTimeMillis() + 30000
                var published = false
                while (System.currentTimeMillis() < end && !published) {
                    published = synchronized(fixture.files) { fixture.files.values.any { bytes ->
                        runCatching { SyncV3Codec.decode(bytes).state?.classOverrides?.count { it.title == "V3 批量课程" } == 2 }.getOrDefault(false)
                    } }
                    if (!published) Thread.sleep(100)
                }
                var status = ""
                scenario.onActivity { status = it.sharedSync.status + " / " + it.source.state.sync }
                val summary = synchronized(fixture.files) { fixture.files.map { (path, bytes) ->
                    path + "=" + runCatching { SyncV3Codec.decode(bytes).state?.classOverrides.toString() }.getOrElse { it.message.orEmpty() }
                }.joinToString("\n") }
                assertTrue("v3 immutable object must contain both overrides: $status\n$summary",published)
                synchronized(fixture.files) {
                    val raw = fixture.files.values.first { bytes -> runCatching {
                        SyncV3Codec.decode(bytes).state?.classOverrides?.count { it.title == "V3 批量课程" } == 2
                    }.getOrDefault(false) }
                    java.io.File(c.getExternalFilesDir(null),"pr65-v3.dpv").writeBytes(raw)
                }
            }
        }
    }

    @Test fun csvXlsMixedSourcesIndependentDraftCancelAndSelection() {
        require(c.packageName.endsWith(".testbed"))
        val name="pr65-csv-${UUID.randomUUID()}"
        TermScheduleProfileStore.overrideNamespace=name;WebDavSettings.overrideNamespace=name
        val executor=Executors.newSingleThreadExecutor();val profiles=TermScheduleProfileStore(c)
        val store=RoomScheduleStore(Room.databaseBuilder(c,ScheduleDatabase::class.java,"$name.db").build(),executor,profiles=profiles)
        try {
            fun imported(ext:String):ImportCommit {
                val bytes=i.context.assets.open("hubei_2026-2027-1.$ext").readBytes()
                val preview=TimetableImporter().parse(ScheduleArtifact("fixture.$ext",bytes))
                return await { IcsScheduleImporter().commit(preview,store,"$ext 测试来源",null,1,it) }
            }
            val csv=imported("csv");imported("xls")
            await<Unit>{store.put(TimetableEntry("local-a","高等数学B-1",1,540,600),it)}
            await<Unit>{store.put(TimetableEntry("local-b","高等数学B-1",2,540,600),it)}
            val initial=await<ScheduleState>(store::load).copy(profiles=mapOf(BuiltInProfiles.term2026.termKey to BuiltInProfiles.term2026))
            val group=CourseBatch.candidates(initial).single{it.sourceId==csv.source.id&&it.originalTitle=="高等数学B-1"}.let { group ->
                group.copy(series=CourseBatch.occurrences(initial,group,ZoneId.systemDefault(),LocalDate.of(2026,10,26)).map{it.seriesId}.distinct())
            }
            assertTrue(group.series.size>=2)
            val old=group.series.mapIndexed{n,id->ClassOverride.of(csv.source.id,id,null,ClassPatch(teacher="教师$n",note=if(n==0)"" else "保留备注"))}
            await<Unit>{store.putClassOverrides(initial,old,it)}
            val occurrence=CourseBatch.occurrences(initial,group,ZoneId.systemDefault(),LocalDate.of(2026,10,26)).first{it.seriesId==group.series.first()}
            val sourceRange=ScheduleProjection.periods(occurrence.periodLabel)
            val newFirst=if(sourceRange?.first==1)3 else 1
            val newLast=newFirst+1
            val weekly=ClassOverride.of(csv.source.id,group.series.first(),occurrence.anchorDate.toString(),ClassPatch(title="指定周名称",teacher="例外教师",location="例外教室"))
            await<Unit>{store.putClassOverride(weekly,weekly.key,it)}
            val baseline=await<ScheduleState>(store::load)
            ScheduleStores.overrideFactory={store}
            c.getSharedPreferences("com.dormpanel.phone.TimetableActivity",0).edit().putString("date","2026-10-26").commit()
            launchPhoneTestActivity().use { scenario ->
                Thread.sleep(800)
                onView(withText("管理")).perform(click());onView(withText("课程管理")).perform(click())
                fun openGroup() {onView(withText(org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.startsWith("高等数学B-1\n"),org.hamcrest.Matchers.containsString("csv 测试来源")))).perform(scrollTo(),click())}
                fun draft() {
                    onView(withText("编辑时段 1")).perform(scrollTo(),click())
                    onView(withContentDescription("Location")).perform(scrollTo(),replaceText("独立教室"),closeSoftKeyboard())
                    onView(withContentDescription("First period")).perform(scrollTo(),replaceText(newFirst.toString()),closeSoftKeyboard())
                    onView(withContentDescription("Last period")).perform(scrollTo(),replaceText(newLast.toString()),closeSoftKeyboard())
                    onView(withText("Save")).perform(click())
                    onView(withText("时段 2 · 参与本次修改")).perform(scrollTo(),click())
                    onView(withText("统一修改课程名称")).perform(scrollTo(),click())
                    onView(withContentDescription("课程名称")).perform(scrollTo(),replaceText("CSV整门课草稿"),closeSoftKeyboard())
                }
                openGroup();draft()
                assertEquals(baseline.classOverrides,await<ScheduleState>(store::load).classOverrides)
                onView(withText("保存修改")).perform(click())
                onView(withText("继续编辑")).perform(click())
                onView(withText("取消")).inRoot(isDialog()).perform(click())
                assertEquals(baseline.classOverrides,await<ScheduleState>(store::load).classOverrides)
                openGroup();draft();onView(withText("保存修改")).perform(click());shot("csv-preview")
                onView(withText("确认保存")).perform(click());Thread.sleep(800)
                val saved=await<ScheduleState>(store::load)
                assertEquals(baseline.imported,saved.imported)
                assertEquals(weekly,saved.classOverrides.single{it.key==weekly.key})
                val changed=saved.classOverrides.single{it.seriesId==group.series.first()&&it.anchorDate==null}
                assertEquals("CSV整门课草稿",changed.title);assertEquals("教师0",changed.teacher);assertEquals("",changed.note)
                assertEquals("独立教室",changed.location);assertEquals(newFirst,changed.periodStart);assertEquals(newLast,changed.periodEnd)
                assertEquals(old[1],saved.classOverrides.single{it.key==old[1].key})
            }
        } finally {ScheduleStores.overrideFactory=null;TermScheduleProfileStore.overrideNamespace=null;WebDavSettings.overrideNamespace=null;store.close();executor.shutdown();executor.awaitTermination(20,TimeUnit.SECONDS)}
    }

    @Test fun localCustomTimeWeekdayAndReadOnlyBrowsing() {
        require(c.packageName.endsWith(".testbed"))
        val name="pr65-custom-${UUID.randomUUID()}"
        val executor=Executors.newSingleThreadExecutor()
        val db=Room.databaseBuilder(c,ScheduleDatabase::class.java,"$name.db").build()
        val store=RoomScheduleStore(db,executor)
        WebDavSettings.overrideNamespace=name
        try {
            await<Unit>{store.put(TimetableEntry("manual","本地自定义",1,540,600,"原教室"),it)}
            ScheduleStores.overrideFactory={store}
            launchPhoneTestActivity().use {
                Thread.sleep(800)
                onView(withText("管理")).perform(click());onView(withText("课程管理")).perform(click())
                onView(withText(org.hamcrest.Matchers.startsWith("本地自定义\n"))).perform(click())
                onView(withText("编辑时段 1")).perform(scrollTo(),click())
                onView(withContentDescription("Class weekday")).perform(scrollTo(),click())
                onView(withText("Wednesday")).inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup()).perform(click())
                fun time(prefix:String,hour:Int,minute:Int) {
                    onView(withText(org.hamcrest.Matchers.startsWith(prefix))).perform(scrollTo(),click())
                    onView(isAssignableFrom(android.widget.TimePicker::class.java)).perform(object:androidx.test.espresso.ViewAction {
                        override fun getConstraints()=isAssignableFrom(android.widget.TimePicker::class.java)
                        override fun getDescription()="Choose custom class time"
                        override fun perform(controller:androidx.test.espresso.UiController,view:android.view.View) {
                            (view as android.widget.TimePicker).apply{this.hour=hour;this.minute=minute};controller.loopMainThreadUntilIdle()
                        }
                    })
                    onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                }
                time("Start ·",11,20);time("End ·",12,10)
                onView(withContentDescription("Location")).perform(scrollTo(),replaceText("自定义教室"),closeSoftKeyboard())
                onView(withText("Save")).perform(click());onView(withText("保存修改")).perform(click());onView(withText("确认保存")).perform(click())
                Thread.sleep(800)
                val edit=await<ScheduleState>(store::load).classOverrides.single()
                assertEquals(3,edit.weekday);assertEquals(680,edit.startMinute);assertEquals(730,edit.endMinute);assertEquals("自定义教室",edit.location)
                executor.submit{db.dao().checkpoint(ScheduleSyncCheckpoint(200,byteArrayOf(1)))}.get()
                onView(withText(org.hamcrest.Matchers.startsWith("本地自定义\n"))).perform(click())
                onView(withText("保存修改")).check(androidx.test.espresso.assertion.ViewAssertions.matches(org.hamcrest.Matchers.not(isEnabled())))
                onView(withText("取消")).perform(click())
                executor.submit{db.dao().deleteSyncRecord(200)}.get()
            }
        } finally {ScheduleStores.overrideFactory=null;WebDavSettings.overrideNamespace=null;store.close();executor.shutdown();executor.awaitTermination(20,TimeUnit.SECONDS)}
    }

    @Test fun lightCourseUi() {
        screenshotSuffix = "-light"
        i.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) }
        try { roomBatchAndCourseUi() }
        finally { i.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) } }
    }
    @Test fun roomBatchAndCourseUi() {
        require(c.packageName.endsWith(".testbed"))
        val name = "pr65-${UUID.randomUUID()}"
        val executor = Executors.newSingleThreadExecutor()
        val db = Room.databaseBuilder(c, ScheduleDatabase::class.java, "$name.db").build()
        val zone = ZoneId.systemDefault()
        val start = LocalDate.of(2026,10,26).atTime(9,0).atZone(zone).toInstant().toEpochMilli()
        val rows = listOf(0, 8, 14).mapIndexed { index, day ->
            ImportedClassOccurrence("instance-$index", "source", if(index == 1) "b" else "a", null,
                start + day*86400000L, "高等数学 B-1", start + day*86400000L,
                start + day*86400000L + 3600000L, zone.id, "教室 $index", "", null)
        }
        val source = ImportSource("source","PR65 隔离数据","pr65.ics","ics",null,null,"0".repeat(64),0,zone.id,rows.size,start,rows.last().end)
        executor.submit { db.runInTransaction { db.dao().insertSource(source); db.dao().insertOccurrences(rows) } }.get()
        val store = RoomScheduleStore(db,executor)
        try {
            val before = await<ScheduleState>(store::load)
            val edits = listOf("a","b").map { ClassOverride.of("source",it,null,ClassPatch(title="批量数学",teacher="教师")) }
            await<Unit> { store.putClassOverrides(before, edits, it) }
            val saved = await<ScheduleState>(store::load)
            java.io.File(c.getExternalFilesDir(null),"pr65-v2.dpc").writeBytes(ScheduleCommitCodec.encode(
                ScheduleCommit(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),emptyList(),saved.copy(sync=null))))
            assertEquals(2,saved.classOverrides.size)
            val failure = runCatching { await<Unit> { store.putClassOverrides(before, edits.map { e -> e.copy(title="旧草稿") },it) } }
            assertTrue(failure.isFailure)
            assertEquals(saved.classOverrides,await<ScheduleState>(store::load).classOverrides)
            val bad = edits + ClassOverride.of("source","gone",null,ClassPatch(title="不存在"))
            assertTrue(runCatching { await<Unit> { store.putClassOverrides(saved,bad,it) } }.isFailure)
            assertEquals(saved.classOverrides,await<ScheduleState>(store::load).classOverrides)
            executor.submit { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER pr65_abort BEFORE INSERT ON class_overrides WHEN NEW.seriesId = 'b' BEGIN SELECT RAISE(ABORT, 'pr65 injected failure'); END") }.get()
            assertTrue(runCatching { await<Unit> { store.putClassOverrides(saved,edits.map { e -> e.copy(title="不应部分保存") },it) } }.isFailure)
            assertEquals(saved.classOverrides,await<ScheduleState>(store::load).classOverrides)
            executor.submit { db.openHelper.writableDatabase.execSQL("DROP TRIGGER pr65_abort") }.get()
            ScheduleStores.overrideFactory = { store }
            WebDavSettings.overrideNamespace = name
            c.getSharedPreferences("com.dormpanel.phone.TimetableActivity",0).edit().putString("date","2026-10-26").commit()
            launchPhoneTestActivity().use {
                Thread.sleep(800)
                onView(withText("管理")).perform(click())
                onView(withText("课程管理")).perform(click()); shot("list")
                onView(withText("返回")).perform(click())
                onView(withText("日视图")).perform(click())
                onView(withText(org.hamcrest.Matchers.startsWith("批量数学\n"))).perform(scrollTo(),click())
                onView(withText("管理整门课")).perform(scrollTo(),click()); shot("editor")
                onView(withText("统一修改课程名称")).perform(click())
                onView(withContentDescription("课程名称")).perform(replaceText("整门课新名称"),click())
                onView(withText("保存修改")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
                onView(withText("取消")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
                shot("keyboard")
                onView(withContentDescription("课程名称")).perform(closeSoftKeyboard())
                onView(withText("保存修改")).perform(click()); shot("preview")
                onView(withText("确认保存")).perform(click())
                Thread.sleep(800)
                assertTrue(await<ScheduleState>(store::load).classOverrides.all { e -> e.title == "整门课新名称" })
            }
        } finally {
            ScheduleStores.overrideFactory = null; WebDavSettings.overrideNamespace = null
            store.close(); executor.shutdown(); executor.awaitTermination(10,TimeUnit.SECONDS)
        }
        val reopenedExecutor = Executors.newSingleThreadExecutor()
        val reopened = RoomScheduleStore(Room.databaseBuilder(c,ScheduleDatabase::class.java,"$name.db").build(),reopenedExecutor)
        try { assertEquals(2,await<ScheduleState>(reopened::load).classOverrides.size) }
        finally { reopened.close(); reopenedExecutor.shutdown() }
    }
}
