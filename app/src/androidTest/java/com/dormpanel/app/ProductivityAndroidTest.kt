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
import com.dormpanel.app.dashboard.catalog.CardCategory
import com.dormpanel.app.dashboard.layout.*
import com.dormpanel.app.dashboard.model.*
import com.dormpanel.app.dashboard.persistence.RawDashboardCard
import com.dormpanel.app.productivity.*
import org.hamcrest.Matchers.*
import org.junit.*
import org.junit.Assert.*

class ProductivityAndroidTest {
    @get:Rule val persistence = IsolatedDashboardRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var savedAppearance: AppearanceState
    private lateinit var savedHa: com.dormpanel.app.ha.HaConnectionSettings
    @Before fun prepare() {
        val context = instrumentation.targetContext
        savedAppearance = PreferencesAppearanceStore(context).read()
        savedHa = com.dormpanel.app.ha.HaSettingsStore(context).read()
        com.dormpanel.app.ha.HaSettingsStore(context).write(savedHa.copy(mode = com.dormpanel.app.ha.BackendMode.DEMO))
    }
    @After fun restore() {
        PreferencesAppearanceStore(instrumentation.targetContext).write(savedAppearance)
        com.dormpanel.app.ha.HaSettingsStore(instrumentation.targetContext).write(savedHa)
    }
    private fun model(activity: MainActivity) = ViewModelProvider(activity)[DashboardViewModel::class.java]
    private fun ready(scenario: ActivityScenario<MainActivity>) {
        val deadline = System.currentTimeMillis() + 8000
        var ready = false
        while (!ready && System.currentTimeMillis() < deadline) {
            scenario.onActivity { ready = model(it).stateHolder.state.loaded && model(it).productivity.ready }
            if (!ready) Thread.sleep(30)
        }
        assertTrue(ready)
    }
    private fun card(id: String) = allOf(withTagValue(equalTo(id)), isAssignableFrom(ProductivityCardView::class.java))
    private fun add(scenario: ActivityScenario<MainActivity>, vararg types: String): List<String> {
        val ids = mutableListOf<String>()
        scenario.onActivity { activity ->
            val vm = model(activity)
            vm.stateHolder.state.cards.toList().forEach { vm.stateHolder.delete(it.id) }
            types.forEach { type ->
                assertTrue(vm.stateHolder.add(vm.catalog.candidates.first { it.providerType == type }) is LayoutMutationResult.Success)
                ids += vm.stateHolder.state.cards.last().id
            }
        }
        return ids
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(300) // Wait for the compositor to present the frame after UI mutations.
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "productivity-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    @Test fun catalogPickerIndependentOwnersRepairAndEditMode() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario)
            scenario.onActivity { activity ->
                val vm = model(activity)
                assertEquals(setOf("todo", "memo", "timer", "calendar", "timetable"), vm.catalog.candidates.filter { it.category == CardCategory.PRODUCTIVITY }.map { it.providerType }.toSet())
                assertTrue(vm.catalog.candidates.any { it.providerType == "clock" })
                assertTrue(vm.catalog.candidates.any { it.providerType == "light" })
                vm.stateHolder.state.cards.toList().forEach { vm.stateHolder.delete(it.id) }
            }
            onView(withId(R.id.dashboard_edit)).perform(click())
            repeat(2) {
                onView(withId(R.id.dashboard_add)).perform(click())
                onView(withText("Productivity")).perform(click())
                onView(withContentDescription("Add Todo")).perform(click())
            }
            onView(withId(R.id.dashboard_done)).perform(click())
            scenario.onActivity { activity ->
                val vm = model(activity); val cards = vm.stateHolder.state.cards
                assertEquals(2, cards.map { it.id }.distinct().size)
                var layoutNotifications = 0
                val observe: (com.dormpanel.app.dashboard.DashboardUiState) -> Unit = { layoutNotifications++ }
                vm.stateHolder.addListener(observe)
                val initialNotifications = layoutNotifications
                vm.productivity.addTodo(cards[0].id, "Only first")
                assertEquals(initialNotifications, layoutNotifications)
                vm.stateHolder.removeListener(observe)
                assertTrue(vm.productivity.state(cards[1].id).todos.isEmpty())
                val repaired = DashboardLayoutRepair(DashboardGridPolicy.definition, vm.registry).repair(cards.map(RawDashboardCard::from))
                assertEquals(cards, repaired.cards); assertTrue(repaired.quarantine.isEmpty())
                val allTypes = listOf("todo", "memo", "timer").mapIndexed { index, type -> PlacedCard("repair-$type", type, index * 2, 0, CardSize(2, 2)) }
                val allRepaired = DashboardLayoutRepair(DashboardGridPolicy.definition, vm.registry).repair(allTypes.map(RawDashboardCard::from))
                assertEquals(allTypes, allRepaired.cards); assertTrue(allRepaired.quarantine.isEmpty())
                val before = vm.productivity.state(cards[0].id)
                assertTrue(vm.stateHolder.move(cards[0].id, 0, 3) is LayoutMutationResult.Success)
                assertTrue(vm.stateHolder.resize(cards[0].id, CardSize(3, 2)) is LayoutMutationResult.Success)
                assertEquals(before, vm.productivity.state(cards[0].id))
                vm.stateHolder.delete(cards[0].id)
                assertEquals(before, vm.productivity.state(cards[0].id))
                listOf("todo", "memo", "timer").forEach { type ->
                    val provider = vm.registry.provider(type)!!
                    val view = provider.createView(activity)
                    provider.bind(view, PlacedCard("edit-$type", type, 0, 0, CardSize(2, 2)), CardInteractionScope(false) { fail("claimed disabled interaction") })
                    assertFalse(view.performClick())
                    if (type == "timer") {
                        fun visit(v: View) {
                            if (v is android.widget.Button) { assertFalse(v.isEnabled); v.performClick() }
                            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                        }
                        visit(view)
                        assertEquals(TimerStatus.READY, vm.productivity.state("edit-timer").timer.status)
                    }
                }
            }
        }
    }
    @Test fun todoAndMemoDialogsPersistAcrossActivityAndSourceRecreation() {
        var ids = emptyList<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario); ids = add(scenario, "todo", "todo", "memo", "memo")
            onView(card(ids[0])).perform(click())
            onView(withHint(R.string.productivity_task_hint)).perform(replaceText("First task"), closeSoftKeyboard())
            onView(withText(R.string.productivity_add)).perform(click())
            onView(withText("First task")).perform(click())
            scenario.onActivity { assertTrue(model(it).productivity.state(ids[0]).todos.single().completed) }
            onView(withText("First task")).perform(click())
            onView(withText(R.string.productivity_edit)).perform(click())
            onView(isAssignableFrom(android.widget.EditText::class.java)).perform(replaceText("Edited task"), closeSoftKeyboard())
            onView(withText(R.string.productivity_save)).perform(click())
            onView(withText(R.string.productivity_delete)).perform(click())
            onView(withHint(R.string.productivity_task_hint)).perform(replaceText("Retained task"), closeSoftKeyboard())
            onView(withText(R.string.productivity_add)).perform(click())
            screenshot("todo-editor")
            onView(withText(android.R.string.cancel)).perform(click())
            onView(card(ids[1])).perform(click())
            onView(withHint(R.string.productivity_task_hint)).perform(replaceText("Second list"), closeSoftKeyboard())
            onView(withText(R.string.productivity_add)).perform(click())
            onView(withText(android.R.string.cancel)).perform(click())
            onView(card(ids[2])).perform(click())
            onView(isAssignableFrom(android.widget.EditText::class.java)).perform(replaceText("Line one\nLine two"), closeSoftKeyboard())
            screenshot("memo-editor")
            onView(withText(R.string.productivity_save)).perform(click())
            onView(card(ids[2])).perform(click())
            onView(withText(R.string.productivity_clear)).perform(click())
            onView(withText(android.R.string.cancel)).perform(click())
            onView(card(ids[3])).perform(click())
            onView(isAssignableFrom(android.widget.EditText::class.java)).perform(replaceText("Other\nnote"), closeSoftKeyboard())
            onView(withText(R.string.productivity_save)).perform(click())
            scenario.recreate(); ready(scenario)
            scenario.onActivity { assertEquals("Line one\nLine two", model(it).productivity.state(ids[2]).memo) }
            screenshot("home-dark")
            scenario.onActivity { model(it).appearance.setTheme(ThemeMode.LIGHT) }
            screenshot("home-light")
            onView(card(ids[2])).perform(click())
            screenshot("memo-light")
            onView(withText(android.R.string.cancel)).perform(click())
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario)
            scenario.onActivity { activity ->
                val source = model(activity).productivity
                assertEquals("Retained task", source.state(ids[0]).todos.single().text)
                assertEquals("Second list", source.state(ids[1]).todos.single().text)
                assertEquals("Line one\nLine two", source.state(ids[2]).memo)
                assertEquals("Other\nnote", source.state(ids[3]).memo)
            }
            onView(card(ids[3])).perform(click())
            onView(withText(R.string.productivity_clear)).perform(click())
            onView(withText(R.string.productivity_save)).perform(click())
            scenario.onActivity { assertEquals("", model(it).productivity.state(ids[3]).memo) }
        }
    }
    @Test fun timerControlsExpiryVisibilityAndSourceRecovery() {
        var ids = emptyList<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario); ids = add(scenario, "timer", "timer")
            onView(card(ids[0])).perform(click())
            onView(withHint(R.string.productivity_seconds)).perform(replaceText("30"), closeSoftKeyboard())
            onView(withText(R.string.productivity_set_duration)).perform(click())
            onView(withText(R.string.productivity_start)).perform(click())
            screenshot("timer-dialog")
            onView(withText(android.R.string.cancel)).perform(click())
            scenario.onActivity { model(it).productivity.configureTimer(ids[1], 2000); model(it).productivity.startTimer(ids[1]) }
            onView(allOf(withText(R.string.productivity_pause), isDescendantOfA(card(ids[0])))).perform(click())
            var paused = 0L
            scenario.onActivity { paused = model(it).productivity.state(ids[0]).timer.remaining }
            onView(withId(R.id.page_container)).perform(androidx.test.espresso.action.GeneralSwipeAction(
                androidx.test.espresso.action.Swipe.FAST,
                { view -> val location = IntArray(2); view.getLocationOnScreen(location); floatArrayOf(location[0] + view.width * .98f, location[1] + view.height * .8f) },
                { view -> val location = IntArray(2); view.getLocationOnScreen(location); floatArrayOf(location[0] + view.width * .98f, location[1] + view.height * .2f) },
                androidx.test.espresso.action.Press.FINGER))
            onView(withId(R.id.apps_home)).perform(click())
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            Thread.sleep(2200)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertEquals(paused, model(it).productivity.state(ids[0]).timer.remaining)
                assertEquals(TimerStatus.FINISHED, model(it).productivity.state(ids[1]).timer.status)
            }
            onView(allOf(withText(R.string.productivity_resume), isDescendantOfA(card(ids[0])))).perform(click())
            onView(allOf(withText(R.string.productivity_reset), isDescendantOfA(card(ids[1])))).perform(click())
            scenario.onActivity {
                assertEquals(TimerStatus.RUNNING, model(it).productivity.state(ids[0]).timer.status)
                assertEquals(TimerStatus.READY, model(it).productivity.state(ids[1]).timer.status)
                assertTrue(model(it).stateHolder.resize(ids[0], CardSize(2, 1)) is LayoutMutationResult.Success)
                assertTrue(model(it).stateHolder.resize(ids[1], CardSize(3, 2)) is LayoutMutationResult.Success)
            }
            screenshot("timer-sizes")
            onView(withId(R.id.dashboard_edit)).perform(click())
            onView(allOf(withText(R.string.productivity_reset), isDescendantOfA(card(ids[1])))).check(matches(not(isEnabled())))
            onView(withId(R.id.dashboard_done)).perform(click())
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario)
            scenario.onActivity {
                assertEquals(TimerStatus.RUNNING, model(it).productivity.state(ids[0]).timer.status)
                assertTrue(model(it).productivity.state(ids[0]).timer.remaining < 30000)
                assertEquals(TimerStatus.READY, model(it).productivity.state(ids[1]).timer.status)
            }
        }
    }
}
