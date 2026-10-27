package com.dormpanel.phone

import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import com.dormpanel.app.schedule.testing.SwipeEvents
import org.junit.Test
import org.junit.Assert.*

/** Two processes separated by force-stop. Only the disposable testbed preferences are touched. */
class PhoneDisplayRestartTest {
    @Test fun activityRestoresDisplayAndDateAfterProcessDeath() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        require(context.packageName.endsWith(".testbed"))
        val phase=InstrumentationRegistry.getArguments().getString("displayPhase")
        require(phase in listOf("seed","verify"))
        val prefs=context.getSharedPreferences("com.dormpanel.phone.TimetableActivity",0)
        if (phase == "seed") prefs.edit().clear().putString("date","2026-11-03")
            .putString("term",BuiltInProfiles.TERM_2026_2027_1).putBoolean("weekView",true)
            .putBoolean("swipeDates",true).putBoolean("periodMode",true).putBoolean("weekdaysOnly",true)
            .putFloat("weekAnchor",500f).commit()
        ScheduleStores.overrideFactory = { object : ScheduleStore {
            override fun load(callback: (Result<ScheduleState>) -> Unit) = callback(Result.success(ScheduleState()))
            override fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit) = error("Unexpected write")
            override fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit) = error("Unexpected write")
            override fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit) = error("Unexpected write")
            override fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit) = error("Unexpected write")
            override fun close() = Unit
        } }
        try {
            launchPhoneTestActivity().use { s ->
                instrumentation.waitForIdleSync(); Thread.sleep(600)
                s.onActivity { a ->
                    val views=SwipeEvents.views(a.window.decorView)
                    assertTrue(views.filterIsInstance<TimetableSwipeLayout>().single().swipeEnabled)
                    val g=views.filterIsInstance<PhoneWeekView>().single()
                    assertEquals(5,g.dayCount); assertTrue(g.usesPeriods)
                    assertEquals("2026-11-03",TimetableActivity::class.java.getDeclaredField("selected").let { it.isAccessible=true; it.get(a).toString() })
                    val sc=views.filterIsInstance<android.widget.ScrollView>().single()
                    // Use a nonzero anchor reachable in both pre/post-status-label viewports.
                    val expected=g.offsetAt(500f,1).coerceAtMost((g.height-sc.height).coerceAtLeast(0))
                    assertTrue(expected > 0)
                    assertEquals(expected,sc.scrollY)
                }
            }
        } finally {
            ScheduleStores.overrideFactory=null
            if (phase == "verify") prefs.edit().clear().commit()
        }
    }
}
