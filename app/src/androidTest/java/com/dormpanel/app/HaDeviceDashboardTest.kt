package com.dormpanel.app

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.dashboard.model.DashboardGridPolicy
import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.ha.BackendMode
import com.dormpanel.app.ha.HaSettingsStore
import com.dormpanel.app.home.HomeDeviceState
import com.dormpanel.app.home.HomeKind
import org.hamcrest.Matchers.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HaDeviceDashboardTest {
    @get:Rule val persistence = IsolatedDashboardRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun vm(activity: MainActivity) = ViewModelProvider(activity)[DashboardViewModel::class.java]

    private fun ready(scenario: ActivityScenario<MainActivity>, condition: (DashboardViewModel) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            var done = false
            scenario.onActivity { done = condition(vm(it)) }
            if (done) return
            Thread.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Dashboard did not reach the expected state")
    }

    @Test fun pickerControlsResizeAndRestoresBindingsOnActivityRestart() {
        val settings = HaSettingsStore(context)
        val previous = settings.read()
        settings.write(previous.copy(mode = BackendMode.DEMO))
        val ids = mutableMapOf<String, String>()
        val configs = mutableMapOf<String, String>()
        fun inCard(type: String) = isDescendantOfA(withTagValue(equalTo<Any>(ids.getValue(type))))
        fun addDevice(name: String) {
            onView(withId(R.id.dashboard_add)).perform(click())
            onView(withText(R.string.category_home)).perform(click())
            onView(withContentDescription(context.getString(R.string.picker_add_description, name))).perform(scrollTo(), click())
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario) { it.stateHolder.state.loaded }
                scenario.onActivity { activity ->
                    val holder = vm(activity).stateHolder
                    holder.state.cards.forEach { holder.delete(it.id) }
                }
                onView(withId(R.id.dashboard_edit)).perform(click())
                addDevice("Demo 空调")
                addDevice("Demo 风扇")
                addDevice("Demo 虚拟开关")
                onView(withText(R.string.switch_save)).perform(click())
                ready(scenario) { it.stateHolder.state.cards.size == 3 && it.stateHolder.state.configurationSaves.isEmpty() }
                var rowStep = 0f
                scenario.onActivity { activity ->
                    vm(activity).stateHolder.state.cards.forEach { ids[it.providerType] = it.id; configs[it.providerType] = it.configurationJson }
                    val grid = activity.findViewById<View>(R.id.dashboard_grid)
                    rowStep = (grid.height + DashboardGridPolicy.GAP_DP * grid.resources.displayMetrics.density) /
                        DashboardGridPolicy.definition.rows
                }
                fun swipeRows(rows: Int) = GeneralSwipeAction(Swipe.SLOW, GeneralLocation.CENTER, { view ->
                    GeneralLocation.CENTER.calculateCoordinates(view).apply { this[1] += rowStep * rows }
                }, Press.FINGER)
                val resizeFan = withContentDescription(context.getString(R.string.dashboard_resize_card, context.getString(R.string.card_fan)))
                onView(resizeFan).perform(swipeRows(-1))
                ready(scenario) { it.stateHolder.state.cards.single { card -> card.id == ids["fan"] }.size == CardSize(2, 1) }
                val fanChrome = allOf(hasSibling(withTagValue(equalTo<Any>(ids.getValue("fan")))),
                    withContentDescription(R.string.dashboard_drag_card))
                onView(fanChrome).perform(swipeRows(2))
                ready(scenario) { it.stateHolder.state.cards.single { card -> card.id == ids["fan"] }.row == 2 }
                onView(withId(R.id.dashboard_done)).perform(click())
                val fanPower = onView(allOf(inCard("fan"), withText(R.string.light_turn_on)))
                fanPower.check { view, error ->
                    if (error != null) throw error
                    val bounds = Rect()
                    assertTrue(view.getGlobalVisibleRect(bounds))
                    assertTrue(bounds.height() >= (48 * view.resources.displayMetrics.density).toInt())
                }.perform(longClick())
                fanPower.check(matches(isDisplayed())).perform(click())
                onView(allOf(inCard("fan"), withText(R.string.light_turn_off))).check(matches(isDisplayed()))
                onView(allOf(inCard("climate"), withText(R.string.home_details))).perform(click())
                onView(withText(R.string.device_temperature)).check(matches(isDisplayed())).perform(click())
                onView(withContentDescription(R.string.device_temperature)).perform(replaceText("23.5"), closeSoftKeyboard())
                onView(withText(R.string.device_apply)).perform(click())
                onView(withText(containsString("23.5°C"))).check(matches(isDisplayed()))
                onView(withText(android.R.string.cancel)).perform(click())
                onView(allOf(inCard("switch"), withContentDescription(startsWith("Demo 虚拟开关")))).perform(click())
                scenario.onActivity { activity ->
                    val home = vm(activity).dataSource.homeState
                    assertTrue((home.entities.single { it.kind == HomeKind.FAN }.controls as HomeDeviceState.Fan).on == true)
                    assertTrue((home.entities.single { it.kind == HomeKind.INPUT_BOOLEAN }.controls as HomeDeviceState.VirtualSwitch).on == true)
                }
                onView(withId(R.id.dashboard_edit)).perform(click())
                onView(allOf(inCard("fan"), withText(R.string.light_turn_off))).check(matches(not(isEnabled())))
                onView(withContentDescription(context.getString(R.string.dashboard_resize_card, context.getString(R.string.card_fan))))
                    .check(matches(isDisplayed()))
                onView(withId(R.id.dashboard_done)).perform(click())
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario) { it.stateHolder.state.loaded && it.stateHolder.state.cards.size == 3 }
                scenario.onActivity { activity ->
                    val cards = vm(activity).stateHolder.state.cards
                    assertEquals(configs, cards.associate { it.providerType to it.configurationJson })
                    val fan = cards.single { it.providerType == "fan" }
                    assertEquals(CardSize(2, 1), fan.size)
                    assertEquals(2, fan.row)
                }
                onView(allOf(inCard("fan"), withText(R.string.light_turn_on))).check(matches(isDisplayed()))
            }
        } finally { settings.write(previous) }
    }
}
