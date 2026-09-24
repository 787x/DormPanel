package com.dormpanel.app

import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.appearance.*
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.dashboard.card.CardInteractionScope
import com.dormpanel.app.dashboard.model.*
import com.dormpanel.app.schedule.*
import org.hamcrest.Matchers.*
import org.junit.*
import org.junit.Assert.*
import java.time.*

class ScheduleAndroidTest {
    @get:Rule val persistence = IsolatedDashboardRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var savedAppearance: AppearanceState
    @Before fun prepare() { savedAppearance = PreferencesAppearanceStore(instrumentation.targetContext).read() }
    @After fun restore() { PreferencesAppearanceStore(instrumentation.targetContext).write(savedAppearance) }
    private fun model(activity: MainActivity) = ViewModelProvider(activity)[DashboardViewModel::class.java]
    private fun ready(scenario: ActivityScenario<MainActivity>) {
        var ready = false; val deadline = System.currentTimeMillis() + 8000
        while (!ready && System.currentTimeMillis() < deadline) { scenario.onActivity { ready = model(it).schedule.ready && model(it).stateHolder.state.loaded }; if (!ready) Thread.sleep(30) }
        assertTrue(ready)
    }
    private fun open() {
        onView(withId(R.id.page_container)).perform(androidx.test.espresso.action.GeneralSwipeAction(
            androidx.test.espresso.action.Swipe.FAST,
            { v -> val p = IntArray(2); v.getLocationOnScreen(p); floatArrayOf(p[0] + v.width * .8f, p[1] + v.height * .85f) },
            { v -> val p = IntArray(2); v.getLocationOnScreen(p); floatArrayOf(p[0] + v.width * .2f, p[1] + v.height * .85f) }, androidx.test.espresso.action.Press.FINGER))
        onView(withText("+ Add event")).check(matches(isDisplayed()))
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(220)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "schedule-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun setTime(hour: Int, minute: Int) = object : androidx.test.espresso.ViewAction {
        override fun getConstraints() = isAssignableFrom(android.widget.TimePicker::class.java)
        override fun getDescription() = "Select native time"
        override fun perform(controller: androidx.test.espresso.UiController, view: View) {
            (view as android.widget.TimePicker).apply { this.hour = hour; this.minute = minute }
            controller.loopMainThreadUntilIdle()
        }
    }
    @Test fun pageCrudSessionNavigationAndAppearance() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario); open()
            var originalMonth: YearMonth? = null
            scenario.onActivity { originalMonth = model(it).scheduleSession.month }
            onView(withContentDescription("Next month")).perform(click())
            scenario.onActivity { assertEquals(originalMonth!!.plusMonths(1), model(it).scheduleSession.month) }
            onView(withContentDescription("Previous month")).perform(click()); onView(withText("Today")).perform(click())
            onView(withText("+ Add event")).perform(click())
            onView(withText("Save")).perform(click())
            onView(withText(startsWith("Enter a title"))).check(matches(isDisplayed()))
            onView(withHint("Event title")).perform(replaceText("Study group"), closeSoftKeyboard())
            onView(withHint("Note (optional)")).perform(replaceText("Bring the lab notes"), closeSoftKeyboard())
            onView(withContentDescription("Event end time")).perform(click())
            onView(isAssignableFrom(android.widget.TimePicker::class.java)).perform(setTime(8, 0))
            screenshot("time-picker"); onView(withText(android.R.string.ok)).perform(click())
            onView(withText("Save")).perform(click()); onView(withText("End must be after start.")).check(matches(isDisplayed()))
            onView(withContentDescription("Event end time")).perform(click())
            onView(isAssignableFrom(android.widget.TimePicker::class.java)).perform(setTime(10, 0))
            onView(withText(android.R.string.ok)).perform(click())
            onView(withContentDescription("Event start date")).perform(click())
            screenshot("date-picker"); onView(withText(android.R.string.cancel)).perform(click())
            screenshot("event-editor"); onView(withText("Save")).perform(click())
            var id = ""
            scenario.onActivity { id = model(it).schedule.state.events.single().id }
            onView(withText(containsString("Study group"))).perform(click())
            onView(withHint("Event title")).perform(replaceText("Cancelled draft"), closeSoftKeyboard()); onView(withText("Cancel")).perform(click())
            scenario.onActivity { assertEquals("Study group", model(it).schedule.state.events.single().title) }
            onView(withText(containsString("Study group"))).perform(click())
            onView(withHint("Event title")).perform(replaceText("Updated event"), closeSoftKeyboard()); onView(withText("Save")).perform(click())
            scenario.onActivity { activity ->
                val vm = model(activity); assertEquals(id, vm.schedule.state.events.single().id)
                val start = vm.schedule.clock.today().atTime(9, 30).atZone(vm.schedule.clock.zone()).toInstant().toEpochMilli()
                vm.schedule.saveEvent("Overlapping appointment", start, start + 3600000)
                vm.schedule.saveEvent("Tomorrow", start + 86400000, start + 90000000)
            }
            screenshot("calendar-dark")
            var tomorrow: LocalDate? = null
            scenario.onActivity { tomorrow = model(it).schedule.clock.today().plusDays(1) }
            onView(withContentDescription("Select $tomorrow")).perform(click())
            onView(withText("+ Add event")).perform(click())
            onView(withHint("Event title")).perform(replaceText("Second date event"), closeSoftKeyboard())
            onView(withText("Save")).perform(click()); screenshot("selected-date")
            scenario.onActivity { assertEquals(tomorrow, model(it).scheduleSession.selectedDate) }
            onView(withText("Today")).perform(click())
            onView(withText(containsString("Updated event"))).perform(click()); onView(withText("Delete")).perform(click())
            scenario.onActivity { assertFalse(model(it).schedule.state.events.any { e -> e.id == id }) }
            onView(withContentDescription("Select $tomorrow")).perform(click())
            onView(withText("Timetable")).perform(click()); onView(withText("+ Add class")).perform(click())
            onView(withHint("Class title")).perform(replaceText("Mathematics"), closeSoftKeyboard())
            onView(withHint("Location (optional)")).perform(replaceText("Room 201"), closeSoftKeyboard())
            screenshot("class-editor"); onView(withText("Save")).perform(click())
            onView(withText(startsWith("Mathematics"))).perform(click())
            onView(withHint("Class title")).perform(replaceText("Cancelled class"), closeSoftKeyboard()); onView(withText("Cancel")).perform(click())
            onView(withText(startsWith("Mathematics"))).perform(click())
            onView(withHint("Class title")).perform(replaceText("Physics"), closeSoftKeyboard()); onView(withText("Save")).perform(click())
            scenario.onActivity { activity ->
                val source = model(activity).schedule
                for (day in 1..7) source.saveEntry("Lab $day", day, 780, 840, "West wing")
                source.saveEntry("English", source.clock.today().dayOfWeek.value, 660, 720)
            }
            screenshot("timetable-dark")
            onView(withText(startsWith("Physics"))).perform(click()); onView(withText("Delete")).perform(click())
            scenario.onActivity { model(it).appearance.setTheme(ThemeMode.LIGHT) }; screenshot("timetable-light")
            scenario.recreate(); ready(scenario)
            scenario.onActivity { assertEquals(tomorrow, model(it).scheduleSession.selectedDate); assertEquals(YearMonth.from(tomorrow), model(it).scheduleSession.month) }
            onView(withText("+ Add class")).check(matches(isDisplayed()))
            onView(withId(R.id.page_container)).perform(swipeRight())
            onView(withText("+ Add class")).check(matches(isDisplayed()))
            onView(withText("Home")).perform(click()); onView(withId(R.id.dashboard_edit)).check(matches(isDisplayed()))
            // Reconstructed page uses the same session mode and selected date.
            onView(withId(R.id.page_container)).perform(swipeLeft())
            onView(withText("+ Add class")).check(matches(isDisplayed()))
            onView(org.hamcrest.Matchers.allOf(withText("Calendar"), isAssignableFrom(android.widget.Button::class.java))).perform(click()); onView(withText("Today")).perform(click())
            screenshot("calendar-light")
            androidx.test.espresso.Espresso.pressBack(); onView(withId(R.id.dashboard_edit)).check(matches(isDisplayed()))
        }
    }
    @Test fun providersSizesSharedDataAndDisabledBusinessActions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario)
            val ids = mutableListOf<String>()
            scenario.onActivity { activity ->
                val vm = model(activity); vm.appearance.setTheme(ThemeMode.DARK)
                val now = vm.schedule.clock.instant().toEpochMilli()
                repeat(4) { vm.schedule.saveEvent("Appointment ${it + 1}", now + it * 3600000, now + (it + 1) * 3600000) }
                val time = vm.schedule.clock.instant().atZone(vm.schedule.clock.zone()); val minute = time.hour * 60 + time.minute
                vm.schedule.saveEntry("Current lesson", time.dayOfWeek.value, (minute - 10).coerceAtLeast(0), (minute + 30).coerceAtMost(1439), "Room 201")
                vm.stateHolder.state.cards.toList().forEach { vm.stateHolder.delete(it.id) }
                listOf("calendar", "timetable").forEach { kind ->
                    val provider = vm.registry.provider(kind)!!; assertEquals(kind, provider.typeKey)
                    listOf(CardSize(2, 1), CardSize(2, 2), CardSize(3, 2)).forEach { size -> assertTrue(provider.sizePolicy.allows(size)) }
                    assertFalse(provider.sizePolicy.allows(CardSize(1, 1)))
                    val before = vm.schedule.state
                    val view = provider.createView(activity)
                    provider.bind(view, PlacedCard("disabled", kind, 0, 0, CardSize(2, 1)), CardInteractionScope(false) { fail() })
                    assertFalse(view.performClick()); assertEquals(before, vm.schedule.state)
                    vm.stateHolder.add(vm.catalog.candidates.first { it.providerType == kind }); ids += vm.stateHolder.state.cards.last().id
                }
            }
            for (size in listOf(CardSize(2, 1), CardSize(2, 2), CardSize(3, 2))) {
                scenario.onActivity { activity ->
                    val vm = model(activity)
                    vm.stateHolder.move(ids[1], 4, 0)
                    ids.forEach { vm.stateHolder.resize(it, size) }
                }
                screenshot("cards-${size.columnSpan}x${size.rowSpan}")
            }
            onView(withId(R.id.dashboard_edit)).perform(click()); screenshot("cards-edit")
            scenario.onActivity { activity ->
                val vm = model(activity); val before = vm.schedule.state
                vm.stateHolder.move(ids[0], 0, 3); vm.stateHolder.resize(ids[0], CardSize(2, 1))
                assertEquals(before, vm.schedule.state)
            }
            onView(withId(R.id.dashboard_done)).perform(click())
            scenario.onActivity { model(it).appearance.setTheme(ThemeMode.LIGHT) }; screenshot("cards-light")
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                model(activity).schedule.refresh()
                assertEquals(4, model(activity).schedule.state.events.size)
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> ready(scenario); scenario.onActivity { assertEquals(4, model(it).schedule.state.events.size) } }
    }
    @Test fun visibleCardExpiresAtOneShotBoundaryWithoutMutatingStoredEvent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario)
            var before = ScheduleState()
            scenario.onActivity { activity ->
                val vm = model(activity)
                vm.stateHolder.state.cards.toList().forEach { vm.stateHolder.delete(it.id) }
                vm.stateHolder.add(vm.catalog.candidates.first { it.providerType == "calendar" })
                val now = vm.schedule.clock.instant().toEpochMilli()
                vm.schedule.saveEvent("Short appointment", now - 1000, now + 3000)
                before = vm.schedule.state
            }
            onView(withText(containsString("Short appointment"))).check(matches(isDisplayed()))
            Thread.sleep(3300)
            onView(withText("No upcoming events")).check(matches(isDisplayed()))
            scenario.onActivity { assertEquals(before, model(it).schedule.state) }
        }
    }
    @Test fun timezoneChangeWhileEditingCannotReinterpretDraft() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario)
            var zone: ZoneId = ZoneOffset.UTC
            var writes = 0
            val event = CalendarEvent("existing", "Keep instant", 1709197200000, 1709200800000)
            val clock = object : ScheduleClock {
                override fun instant() = Instant.ofEpochMilli(event.start)
                override fun zone() = zone
                override fun locale() = java.util.Locale.US
            }
            val store = object : ScheduleStore {
                override fun load(callback: (Result<ScheduleState>) -> Unit) = callback(Result.success(ScheduleState(events = listOf(event))))
                override fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit) { writes++; callback(Result.success(Unit)) }
                override fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit) { writes++; callback(Result.success(Unit)) }
                override fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit) { writes++; callback(Result.success(Unit)) }
                override fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit) { writes++; callback(Result.success(Unit)) }
                override fun close() = Unit
            }
            lateinit var source: ScheduleSource
            lateinit var editor: ScheduleEditors
            try {
                scenario.onActivity { activity ->
                    source = ScheduleSource(store, clock)
                    editor = ScheduleEditors(activity, source, model(activity).appearance)
                    editor.event(clock.today(), event)
                    zone = ZoneId.of("Asia/Shanghai")
                }
                onView(withText("Save")).perform(click())
                onView(withText(startsWith("Timezone changed."))).check(matches(isDisplayed()))
                scenario.onActivity { assertEquals(0, writes); assertEquals(event, source.state.events.single()) }
            } finally { scenario.onActivity { editor.close(); source.close() } }
        }
    }
}
