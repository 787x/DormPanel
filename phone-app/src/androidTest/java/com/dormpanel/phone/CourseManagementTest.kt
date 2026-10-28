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
                onView(withText(org.hamcrest.Matchers.startsWith("高等数学 B-1\n"))).perform(click()); shot("editor")
                onView(withText("统一修改课程名称")).perform(click())
                onView(withContentDescription("课程名称")).perform(replaceText("整门课新名称"), closeSoftKeyboard())
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
