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
import java.time.ZoneId

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
    @Test fun overnightIcsDetailAllowsNonTimeEditWithoutChangingInstants() {
        val day = LocalDate.parse("2026-09-04")
        val zone = ZoneId.of("Asia/Shanghai")
        val start = day.atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atTime(1, 0).atZone(zone).toInstant().toEpochMilli()
        val sourceRow = ImportSource("ics", "Night import", "night.ics", "local_document", null,
            "Night", "hash", 0, zone.id, 1, start, end)
        val imported = ImportedClassOccurrence("night", "ics", "night-series", null, start, "Night lab",
            start, end, zone.id, "Old room", "", null)
        val initial = ScheduleState(sources = listOf(sourceRow), imported = listOf(imported))
        val store = object : ScheduleStore {
            override fun load(callback: (Result<ScheduleState>) -> Unit) = callback(Result.success(initial))
            override fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
            override fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
            override fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
            override fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
            override fun putClassOverride(item: ClassOverride?, key: String, callback: (Result<Unit>) -> Unit) =
                callback(Result.success(Unit))
            override fun close() = Unit
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var schedule: ScheduleSource
            lateinit var editors: ScheduleEditors
            scenario.onActivity { activity ->
                schedule = ScheduleSource(store)
                editors = ScheduleEditors(activity, schedule, model(activity).appearance)
                editors.classDetail(ScheduleProjection.week(schedule.state, day.minusDays(4), zone).single())
            }
            onView(withText("Edit")).perform(click())
            onView(withText("This week")).perform(click())
            onView(withHint("Location")).perform(replaceText("New room"), closeSoftKeyboard())
            onView(withHint("Teacher")).perform(replaceText("Professor"), closeSoftKeyboard())
            onView(withHint("User note")).perform(replaceText("Bring notes"), closeSoftKeyboard())
            onView(withText("Save")).perform(click())
            scenario.onActivity {
                val effective = ScheduleProjection.week(schedule.state, day.minusDays(4), zone).single()
                assertEquals(start, effective.start.toEpochMilli())
                assertEquals(end, effective.end.toEpochMilli())
                assertEquals("New room", effective.entry.location)
                assertEquals("Professor", effective.teacher)
                assertEquals("Bring notes", effective.note)
                assertEquals(start, schedule.state.imported.single().start)
                editors.close(); schedule.close()
            }
        }
    }
    @Test fun previewWeekNavigationEditableDetailAndSourceDeletion() {
        // Same byte-identical fixture as JVM tests, packaged only in the test APK.
        val bytes = instrumentation.context.assets.open("wakeup.ics").use { it.readBytes() }
        val preview = IcsScheduleImporter().parse(ScheduleArtifact("课表.ics", bytes))
        assertEquals(175, preview.occurrences.size)
        var editedRoom = ""
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
                        ui = ScheduleImportUi(activity, vm.schedule, vm.appearance, vm.webDav, vm.termProfiles) {}
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
                    onView(withText("Import timetable")).check(matches(isDisplayed()))
                    screenshot("week-light")
                    onView(withText(startsWith("高等数学B-1\n"))).perform(click())
                    onView(withText(containsString("Source data stays unchanged"))).check(matches(isDisplayed()))
                    onView(withText("Edit")).check(matches(isDisplayed()))
                    screenshot("detail"); onView(withText("Edit")).perform(click())
                    onView(withText("This week")).perform(click())
                    onView(withText(startsWith("Date · 2026-"))).check(matches(isDisplayed()))
                    onView(withHint("Course name")).perform(replaceText("Local one-week title"), closeSoftKeyboard())
                    onView(withHint("Teacher")).perform(replaceText("Professor"), closeSoftKeyboard())
                    onView(withHint("User note")).perform(replaceText("Bring notes"), closeSoftKeyboard())
                    onView(withText("Save")).perform(click())
                    scenario.onActivity { activity ->
                        val state = model(activity).schedule.state
                        assertEquals(1, state.classOverrides.size)
                        assertTrue(state.imported.any { it.title == "高等数学B-1" })
                        editedRoom = state.imported.first { it.seriesId == state.classOverrides.single().seriesId }.location
                    }
                    onView(withContentDescription("Next week")).perform(click())
                    scenario.onActivity { assertEquals(LocalDate.parse("2026-09-07"), model(it).scheduleSession.weekStart) }
                    onView(allOf(withText(startsWith("高等数学B-1\n")), withText(containsString(editedRoom)))).perform(click())
                    onView(withText("Edit")).perform(click())
                    onView(withText("All weeks for this timetable item")).perform(click())
                    onView(withText("Weekday")).check(matches(isDisplayed()))
                    onView(withHint("Location")).perform(replaceText("Series room"), closeSoftKeyboard())
                    onView(withText("Save")).perform(click())
                    scenario.onActivity { activity ->
                        val state = model(activity).schedule.state
                        assertEquals(2, state.classOverrides.size)
                        assertTrue(state.imported.none { it.location == "Series room" })
                    }
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
