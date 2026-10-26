package com.dormpanel.app.schedule

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.IsolatedDashboardRule
import com.dormpanel.app.MainActivity
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.schedule.testing.V3UiFixture
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class X08eReadFailureUiTest {
    @get:Rule val persistence = IsolatedDashboardRule()
    private fun waitFor(s: ActivityScenario<MainActivity>, condition: (DashboardViewModel) -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) {
            var done = false; s.onActivity { done = condition(ViewModelProvider(it)[DashboardViewModel::class.java]) }
            if (done) return
            Thread.sleep(50)
        }
        error("Read failure UI did not settle")
    }
    @Test fun cachedCoursesRemainReadableButPageActionsCannotWriteUntilReloadSucceeds() {
        V3UiFixture(InstrumentationRegistry.getInstrumentation().targetContext).use { f ->
            ActivityScenario.launch(MainActivity::class.java).use { s ->
                waitFor(s) { it.schedule.editable }
                s.onActivity {
                    val vm = ViewModelProvider(it)[DashboardViewModel::class.java]
                    assertTrue(vm.schedule.saveEntry("原课程", 1, 480, 530))
                    it.setContentView(SchedulePageView(it, vm.schedule, ScheduleSession(vm.schedule.clock), vm.appearance, {}))
                }
                val end = System.currentTimeMillis() + 10000
                while (f.entries().isEmpty()) { check(System.currentTimeMillis() < end); Thread.sleep(40) }
                val original = f.entries(); val restore = f.injectReadFailure()
                s.onActivity { ViewModelProvider(it)[DashboardViewModel::class.java].schedule.reload() }
                waitFor(s) { it.schedule.error && !it.schedule.applyingSnapshot }
                onView(withText(containsString("暂不可编辑"))).check(matches(isDisplayed()))
                onView(withText("+ Add event")).check(matches(not(isEnabled())))
                onView(withText("Timetable")).perform(click())
                for (label in listOf("+ Add class", "Import timetable", "Receive from phone/computer")) onView(withText(label)).check(matches(not(isEnabled())))
                s.onActivity {
                    val source = ViewModelProvider(it)[DashboardViewModel::class.java].schedule
                    assertFalse(source.saveEntry("不应新增", 1, 480, 530)); source.deleteEntry(original.single().id)
                }
                assertEquals(original, f.entries())
                restore(); s.onActivity { ViewModelProvider(it)[DashboardViewModel::class.java].schedule.reload() }
                waitFor(s) { it.schedule.editable }
                onView(withText("+ Add class")).check(matches(isEnabled()))
                s.onActivity { assertTrue(ViewModelProvider(it)[DashboardViewModel::class.java].schedule.saveEntry("恢复后课程", 1, 480, 530)) }
                val savedEnd = System.currentTimeMillis() + 10000
                while (f.entries().size != 2) { check(System.currentTimeMillis() < savedEnd); Thread.sleep(40) }
                assertTrue(f.entries().any { it.title == "原课程" })
            }
        }
    }
}
