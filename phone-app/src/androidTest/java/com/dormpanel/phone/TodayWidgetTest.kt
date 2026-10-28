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
