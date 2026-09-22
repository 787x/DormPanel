package com.dormpanel.app

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.appearance.*
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.schedule.*
import org.hamcrest.Matchers.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class ScheduleImportAndroidTest {
    @get:Rule val persistence = IsolatedDashboardRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun model(activity: MainActivity) = ViewModelProvider(activity)[DashboardViewModel::class.java]
    private fun waitUntil(scenario: ActivityScenario<MainActivity>, condition: (DashboardViewModel) -> Boolean) {
        val deadline = System.currentTimeMillis() + 8000
        var done = false
        while (!done && System.currentTimeMillis() < deadline) { scenario.onActivity { done = condition(model(it)) }; if (!done) Thread.sleep(30) }
        assertTrue(done)
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "ics-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }; bitmap.recycle()
    }
    @Test fun previewBeforeWriteWeekNavigationReadOnlyDetailAndSourceDeletion() {
        // Same byte-identical fixture as JVM tests, packaged only in the test APK.
        val bytes = instrumentation.context.assets.open("wakeup.ics").use { it.readBytes() }
        val preview = IcsScheduleImporter().parse(ScheduleArtifact("课表.ics", bytes))
        assertEquals(175, preview.occurrences.size)
        val saved = PreferencesAppearanceStore(instrumentation.targetContext).read()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitUntil(scenario) { it.schedule.ready && it.stateHolder.state.loaded }
                lateinit var ui: ScheduleImportUi
                try {
                    scenario.onActivity { activity ->
                        val vm = model(activity)
                        vm.schedule.saveEntry("Manual survives import", 5, 600, 660)
                        vm.schedule.saveEvent("Calendar remains separate", 1, 2)
                        vm.scheduleSession.mode = ScheduleMode.TIMETABLE
                        vm.scheduleSession.weekStart = LocalDate.parse("2026-08-31")
                        ui = ScheduleImportUi(activity, vm.schedule, vm.appearance) {}
                        ui.preview(preview)
                        assertTrue(vm.schedule.state.sources.isEmpty())
                    }
                    onView(withText("Timetable import preview")).check(matches(isDisplayed()))
                    onView(withText(containsString("7 series · 175 classes"))).check(matches(isDisplayed()))
                    screenshot("preview-dark")
                    scenario.onActivity { model(it).appearance.setTheme(ThemeMode.LIGHT) }; screenshot("preview-light")
                    onView(withText("Import")).perform(click())
                    waitUntil(scenario) { it.schedule.state.sources.size == 1 }
                    onView(withText("OK")).perform(click())
                    onView(withId(R.id.page_container)).perform(androidx.test.espresso.action.GeneralSwipeAction(
                        androidx.test.espresso.action.Swipe.FAST,
                        { v -> val p = IntArray(2); v.getLocationOnScreen(p); floatArrayOf(p[0] + v.width * .8f, p[1] + v.height * .85f) },
                        { v -> val p = IntArray(2); v.getLocationOnScreen(p); floatArrayOf(p[0] + v.width * .2f, p[1] + v.height * .85f) }, androidx.test.espresso.action.Press.FINGER))
                    onView(withText("Import ICS")).check(matches(isDisplayed()))
                    screenshot("week-light")
                    onView(withText(startsWith("高等数学B-1\n"))).perform(click())
                    onView(withText(containsString("Read-only."))).check(matches(isDisplayed()))
                    screenshot("detail"); onView(withText("Close")).perform(click())
                    onView(withContentDescription("Next week")).perform(click())
                    scenario.onActivity { assertEquals(LocalDate.parse("2026-09-07"), model(it).scheduleSession.weekStart) }
                    onView(withText("Home")).perform(click())
                    scenario.onActivity { activity ->
                        val vm = model(activity)
                        vm.appearance.setTheme(ThemeMode.DARK)
                        val content = ScheduleProjection.week(vm.schedule.state, LocalDate.parse("2027-03-01"), vm.schedule.clock.zone())
                        assertTrue(content.all { it.imported == null }); assertEquals(1, content.size)
                        assertEquals(1, vm.schedule.state.events.size)
                        ui.sources()
                    }
                    screenshot("sources")
                    onView(withText("Delete 课表")).perform(click())
                    onView(withText("Delete source")).perform(click())
                    waitUntil(scenario) { it.schedule.state.sources.isEmpty() }
                    scenario.onActivity {
                        assertTrue(model(it).schedule.state.imported.isEmpty())
                        assertEquals(1, model(it).schedule.state.entries.size)
                        assertEquals(1, model(it).schedule.state.events.size)
                    }
                    onView(withText("Close")).perform(click())
                } finally { scenario.onActivity { ui.close() } }
            }
        } finally { PreferencesAppearanceStore(instrumentation.targetContext).write(saved) }
    }
}
