package com.dormpanel.phone

import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicitly restricted to PR66's fresh package: never touches a daily/testbed Room. */
class TodayWidgetTest {
    @Test fun configurationSavesNameAndTomorrowOptionForOneInstance() {
        val i=InstrumentationRegistry.getInstrumentation(); val c=i.targetContext
        check(c.packageName.endsWith(".pr66testbed"))
        val ids=android.appwidget.AppWidgetManager.getInstance(c).getAppWidgetIds(android.content.ComponentName(c,TodayWidgetProvider::class.java))
        check(ids.isNotEmpty())
        val id=ids.first(); val title=WidgetTitleSettings.get(c,id); val tomorrow=WidgetTitleSettings.tomorrow(c,id)
        try {
            val intent=android.content.Intent(c,WidgetTitleActivity::class.java).putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,id)
            androidx.test.core.app.ActivityScenario.launch<WidgetTitleActivity>(intent).use { scenario ->
                scenario.onActivity {
                    it.findViewById<android.widget.EditText>(android.R.id.edit).setText("我的秋季课表")
                    it.findViewById<android.widget.CheckBox>(android.R.id.checkbox).isChecked=false
                }
                scenario.recreate()
                scenario.onActivity {
                    assertEquals("我的秋季课表",it.findViewById<android.widget.EditText>(android.R.id.edit).text.toString())
                    assertFalse(it.findViewById<android.widget.CheckBox>(android.R.id.checkbox).isChecked)
                }
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("保存"))
                    .perform(androidx.test.espresso.action.ViewActions.click())
                assertEquals("我的秋季课表",WidgetTitleSettings.get(c,id)); assertFalse(WidgetTitleSettings.tomorrow(c,id))
            }
        } finally {
            if (title==null) {
                WidgetTitleSettings.remove(c,intArrayOf(id))
                c.getSharedPreferences("today-widget-titles",android.content.Context.MODE_PRIVATE).edit().putBoolean("tomorrow-$id",tomorrow).commit()
            } else WidgetTitleSettings.save(c,id,title,tomorrow)
            val done=CountDownLatch(1); TodayWidgetUpdates.request(c) { done.countDown() }; assertTrue(done.await(10,TimeUnit.SECONDS))
        }
    }
    @Test fun launcherBroadcastRefreshKeepsContentAndRecoversFromFailure() {
        val i = InstrumentationRegistry.getInstrumentation(); val c = i.targetContext
        check(c.packageName.endsWith(".pr66testbed"))
        val manager = android.appwidget.AppWidgetManager.getInstance(c)
        val ids = listOf(TodayWidgetProvider::class.java, WideTodayWidgetProvider::class.java).flatMap {
            manager.getAppWidgetIds(android.content.ComponentName(c,it)).toList()
        }
        check(ids.size >= 2) { "Add both isolated widgets and show their Launcher page first" }
        val originalNames=ids.associateWith { WidgetTitleSettings.get(c,it) }
        val originalTomorrow=ids.associateWith { WidgetTitleSettings.tomorrow(c,it) }
        WidgetTitleSettings.save(c,ids[0],"早八课表",false)
        WidgetTitleSettings.save(c,ids[1],"课程安排",true)
        val store = ScheduleStores.create(c)
        val reads = java.util.concurrent.CopyOnWriteArrayList<(Result<ScheduleState>) -> Unit>()
        fun state(title: String) = ScheduleState(entries=listOf(TimetableEntry("race",title,LocalDate.now().dayOfWeek.value,0,1439,"A101")))
        fun waitFor(message: String, condition: () -> Boolean) {
            val end = System.currentTimeMillis()+5000
            while (System.currentTimeMillis()<end) { if (condition()) return; Thread.sleep(50) }
            fail(message)
        }
        fun texts(): List<String> {
            val out=mutableListOf<String>()
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo?) {
                if (node==null) return
                node.text?.toString()?.let(out::add)
                for (index in 0 until node.childCount) visit(node.getChild(index))
            }
            visit(i.uiAutomation.rootInActiveWindow); return out
        }
        fun frame(title: String) = texts().count { it==title } >= 2
        fun broadcast() { c.sendBroadcast(android.content.Intent(c,TodayWidgetProvider::class.java)
            .setAction(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS,ids.toIntArray())) }
        ScheduleStores.overrideFactory = { object : ScheduleStore by store {
            override fun loadPresentation(callback: (Result<ScheduleState>) -> Unit) { reads.add(callback) }
        } }
        try {
            broadcast(); waitFor("first read") { reads.size==1 }
            i.runOnMainSync { reads[0](Result.success(state("Widget race A"))) }
            waitFor("both instances render A") { frame("Widget race A") }
            assertTrue(texts().containsAll(listOf("早八课表","课程安排")))
            broadcast(); waitFor("background read") { reads.size==2 }
            val retained = texts()
            assertTrue("no loading flash",retained.count { it=="Widget race A" } >= 2)
            assertFalse(retained.contains("课程仍在加载"))
            i.runOnMainSync { TodayWidgetUpdates.request(c) }; assertEquals(2,reads.size)
            i.runOnMainSync { reads[1](Result.success(state("obsolete B"))) }
            waitFor("coalesced read") { reads.size==3 }
            val unchanged = texts()
            assertTrue(unchanged.count { it=="Widget race A" } >= 2); assertFalse(unchanged.contains("obsolete B"))
            i.runOnMainSync { reads[2](Result.success(state("Widget race C"))) }
            waitFor("latest C") { frame("Widget race C") }
            i.runOnMainSync { reads[1](Result.success(state("late B"))) }
            assertTrue(frame("Widget race C"))
            broadcast(); waitFor("failure read") { reads.size==4 }
            i.runOnMainSync { reads[3](Result.failure(IllegalStateException("injected"))) }
            waitFor("explicit read error") { texts().count { it=="本地课表读取失败，请打开应用检查" } >= 2 }
            broadcast(); waitFor("recovery read") { reads.size==5 }
            i.runOnMainSync { reads[4](Result.success(state("Widget race recovered"))) }
            waitFor("both recover") { frame("Widget race recovered") }
            broadcast(); waitFor("per-instance tomorrow read") { reads.size==6 }
            val weekday=LocalDate.now().dayOfWeek.value
            val nextDay=ScheduleState(entries=listOf(TimetableEntry("ended","已结束",weekday,0,1),
                TimetableEntry("next","明日课程演示",weekday%7+1,540,600,"B202")))
            i.runOnMainSync { reads[5](Result.success(nextDay)) }
            waitFor("independent tomorrow option") {
                val visible=texts()
                visible.contains("今天的课程已结束") && visible.count { it=="明日课程演示" }==1 && visible.any { it.contains("·明日") }
            }
            i.waitForIdleSync()
            val bitmap=i.uiAutomation.takeScreenshot()
            java.io.File(c.getExternalFilesDir(null),"pr66-tomorrow-switch.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }; bitmap.recycle()
        } finally {
            originalNames.forEach { (id,title) ->
                if (title==null) {
                    WidgetTitleSettings.remove(c,intArrayOf(id))
                    c.getSharedPreferences("today-widget-titles",android.content.Context.MODE_PRIVATE).edit().putBoolean("tomorrow-$id",originalTomorrow.getValue(id)).commit()
                } else WidgetTitleSettings.save(c,id,title,originalTomorrow.getValue(id))
            }
            ScheduleStores.overrideFactory=null
            val done=CountDownLatch(1); TodayWidgetUpdates.request(c) { done.countDown() }
            assertTrue(done.await(10,TimeUnit.SECONDS))
        }
    }
    @Test fun coldAndExistingActivityOpenToday() {
        val i = InstrumentationRegistry.getInstrumentation()
        val c = i.targetContext
        check(c.packageName.endsWith(".pr66testbed"))
        val old = LocalDate.now().minusDays(30)
        val intent = android.content.Intent(c, TimetableActivity::class.java)
            .setAction("com.dormpanel.phone.OPEN_TODAY").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        // MIUI may refuse the instrumentation UID's initial background launch. Same shell
        // fallback as PhoneTestLaunch, restricted above to this PR's isolated package.
        val launched = java.util.concurrent.atomic.AtomicBoolean(false)
        if (android.os.Build.MANUFACTURER.equals("Xiaomi",true)) Thread {
            Thread.sleep(750)
            if (!launched.get()) android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(
                "am start -a com.dormpanel.phone.OPEN_TODAY -f 0x10008000 -n ${c.packageName}/com.dormpanel.phone.TimetableActivity"
            )).use { it.readBytes() }
        }.apply { isDaemon = true; start() }
        androidx.test.core.app.ActivityScenario.launch<TimetableActivity>(intent).use { scenario ->
            launched.set(true)
            val selected = TimetableActivity::class.java.getDeclaredField("selected").apply { isAccessible = true }
            scenario.onActivity {
                assertEquals(LocalDate.now(),selected.get(it))
                selected.set(it,old)
            }
            i.runOnMainSync { c.startActivity(android.content.Intent(c, TimetableActivity::class.java).setAction("com.dormpanel.phone.OPEN_TODAY").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            val deadline = System.currentTimeMillis() + 10000
            var today = false
            while (!today && System.currentTimeMillis() < deadline) {
                scenario.onActivity { today = selected.get(it) == LocalDate.now() }
                if (!today) Thread.sleep(100)
            }
            assertTrue("Existing Activity must handle the explicit today intent",today)
            scenario.recreate()
            scenario.onActivity { assertEquals(LocalDate.now(),selected.get(it)) }
        }
    }
    @Test fun seedIsolatedLauncherFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".pr66testbed"))
        val store = ScheduleStores.create(context)
        fun <T> await(call: ((Result<T>) -> Unit) -> Unit): T {
            val latch = CountDownLatch(1); var result: Result<T>? = null
            call { result = it; latch.countDown() }
            assertTrue(latch.await(10, TimeUnit.SECONDS)); return result!!.getOrThrow()
        }
        val weekday = LocalDate.now().dayOfWeek.value
        val mode = InstrumentationRegistry.getArguments().getString("fixtureMode")
        val day = if (mode == "noCourses") weekday % 7 + 1 else weekday
        val end = if (mode == "ended") 1 else 1439
        await<Unit> { store.put(TimetableEntry("widget-math","高等数学（演示）",day,0,end,"教学楼 A101"),it) }
        await<Unit> { store.put(TimetableEntry("widget-english","大学英语（演示）",day,0,end,"教学楼 B202"),it) }
        val loaded = await<ScheduleState> { store.load(it) }
        assertEquals(2,loaded.entries.size)
        if (mode == "firstUse") {
            await<Unit> { store.deleteEntry("widget-math",it) }
            await<Unit> { store.deleteEntry("widget-english",it) }
        }
        if (mode == "readFailure") ScheduleStores.overrideFactory = {
            object : ScheduleStore by store {
                override fun loadPresentation(callback: (Result<ScheduleState>) -> Unit) {
                    callback(Result.failure(IllegalStateException("injected read failure")))
                }
            }
        }
        val refreshed = CountDownLatch(1)
        try {
            TodayWidgetUpdates.request(context) { refreshed.countDown() }
            assertTrue("Widget refresh callback",refreshed.await(10, TimeUnit.SECONDS))
        } finally { ScheduleStores.overrideFactory = null }
    }
}
