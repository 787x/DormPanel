package com.dormpanel.app

import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.*
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.dashboard.card.*
import com.dormpanel.app.dashboard.layout.LayoutMutationResult
import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.ha.BackendMode
import com.dormpanel.app.ha.HaSettingsStore
import org.hamcrest.Matchers.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwitchDashboardTest {
    @get:Rule val persistence = IsolatedDashboardRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun vm(activity: MainActivity) = ViewModelProvider(activity)[DashboardViewModel::class.java]
    private fun ready(scenario: ActivityScenario<MainActivity>, condition: (DashboardViewModel) -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 5000
        do {
            var done = false
            scenario.onActivity { done = condition(vm(it)) }
            if (done) return
            Thread.sleep(25)
        } while (android.os.SystemClock.uptimeMillis() < end)
        fail("Dashboard did not reach the expected state")
    }

    private fun tile(cardId: String, label: String) = onView(allOf(
        isDescendantOfA(withTagValue(equalTo<Any>(cardId))),
        withContentDescription(startsWith(label)),
    ))

    private fun swipe(fromX: Float, fromY: Float, deltaX: Int, deltaY: Int, speed: Swipe = Swipe.SLOW): androidx.test.espresso.ViewAction {
        fun position(view: View, dx: Int, dy: Int): FloatArray {
            val at = IntArray(2)
            view.getLocationOnScreen(at)
            return floatArrayOf(at[0] + view.width * fromX + dx, at[1] + view.height * fromY + dy)
        }
        return GeneralSwipeAction(speed, { position(it, 0, 0) }, { position(it, deltaX, deltaY) }, Press.FINGER)
    }

    @Test fun oneAndTwoChannelCardsAndIndependentRebinding() {
        val settings = HaSettingsStore(context)
        val saved = settings.read()
        settings.write(saved.copy(mode = BackendMode.DEMO))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario) { it.stateHolder.state.loaded }
                val cards = mutableMapOf<String, String>()
                val candidates = listOf("switch:switch:desk", "switch-group:desk", "switch-group:demo-switch-2",
                    "switch:switch:demo-switch-2-0", "switch:switch:demo-switch-2-1")
                scenario.onActivity {
                    val model = vm(it)
                    val holder = model.stateHolder
                    // Confirm the default cards still load before making space for this fixture.
                    assertTrue(holder.state.cards.any { card -> card.providerType == "clock" })
                    holder.state.cards.toList().forEach { card -> holder.delete(card.id) }
                    candidates.forEach { id ->
                        val existing = holder.state.cards.map { card -> card.id }
                        assertTrue(holder.add(model.catalog.candidates.single { candidate ->
                            candidate.candidateId == id
                        }) is LayoutMutationResult.Success)
                        cards[id] = holder.state.cards.single { card -> card.id !in existing }.id
                    }
                }
                val desk = cards.getValue("switch:switch:desk")
                val deskGroup = cards.getValue("switch-group:desk")
                val two = cards.getValue("switch-group:demo-switch-2")
                val left = cards.getValue("switch:switch:demo-switch-2-0")
                val right = cards.getValue("switch:switch:demo-switch-2-1")
                fun checkPower(vararg expected: Pair<String, Boolean>) = scenario.onActivity { activity ->
                    expected.forEach { (entity, on) ->
                        assertEquals(on, vm(activity).dataSource.state.switches.channels.getValue(entity).isOn)
                    }
                }
                tile(desk, "Desk outlet").perform(click())
                checkPower("switch:desk" to true)
                tile(deskGroup, "Desk outlet").perform(click())
                checkPower("switch:desk" to false)
                tile(left, "左键").perform(click())
                checkPower("switch:demo-switch-2-0" to true, "switch:demo-switch-2-1" to false)
                tile(two, "右键").perform(click())
                checkPower("switch:demo-switch-2-0" to true, "switch:demo-switch-2-1" to true)
                tile(right, "右键").perform(click())
                checkPower("switch:demo-switch-2-0" to true, "switch:demo-switch-2-1" to false)
                onView(withId(R.id.dashboard_edit)).perform(click())
                val deskChrome = hasSibling(withTagValue(equalTo<Any>(desk)))
                onView(allOf(withParent(deskChrome), withContentDescription(R.string.switch_configure))).perform(click())
                onView(withText(containsString("switch:desk"))).perform(click())
                onView(withText(containsString("switch:demo-switch-2-1"))).perform(click())
                val alias = "Custom right channel with an intentionally long name"
                onView(withHint(R.string.switch_custom_channel)).perform(replaceText(alias))
                onView(withText(R.string.switch_save)).perform(click())
                ready(scenario) { it.stateHolder.state.configurationSaves.isEmpty() && !it.stateHolder.state.storageError }
                onView(withId(R.id.dashboard_done)).perform(click())
                onView(allOf(withText(alias), isDescendantOfA(withTagValue(equalTo<Any>(desk))))).check { view, error ->
                    if (error != null) throw error
                    val name = view as android.widget.TextView
                    assertEquals(1, name.lineCount)
                    assertTrue(name.layout.getEllipsisCount(0) > 0)
                }
                tile(desk, "Custom right").perform(click())
                checkPower("switch:desk" to false, "switch:demo-switch-2-0" to true, "switch:demo-switch-2-1" to true)
                onView(withId(R.id.dashboard_edit)).perform(click())
                onView(allOf(withParent(deskChrome), withContentDescription(
                    context.getString(R.string.dashboard_delete_card, context.getString(R.string.card_switch))))).perform(click())
                ready(scenario) { it.stateHolder.state.cards.none { card -> card.id == desk } }
            }
        } finally {
            settings.write(saved)
        }
    }

    @Test fun addResizeReorderAndReloadSwitchCards() {
        val settings = HaSettingsStore(context)
        val saved = settings.read()
        settings.write(saved.copy(mode = BackendMode.DEMO))
        try {
            var single = ""
            var group = ""
            var savedJson = ""
            val three = "Demo · 3键开关"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario) { it.stateHolder.state.loaded }
                scenario.onActivity {
                    val holder = vm(it).stateHolder
                    holder.state.cards.toList().forEach { card -> holder.delete(card.id) }
                }
                onView(withId(R.id.dashboard_edit)).perform(click())
                onView(withId(R.id.dashboard_add)).perform(click())
                onView(withText(R.string.category_home)).perform(click())
                onView(withText(three)).perform(scrollTo(), click())
                onView(withText(R.string.switch_save)).perform(click())
                onView(withId(R.id.dashboard_done)).perform(click())
                scenario.onActivity {
                    val model = vm(it)
                    val holder = model.stateHolder
                    group = holder.state.cards.single().id
                    assertEquals(CardSize(2, 1), holder.state.cards.single().size)
                    assertTrue(holder.add(model.catalog.candidates.single { candidate ->
                        candidate.candidateId == "switch:switch:demo-switch-3-0"
                    }) is LayoutMutationResult.Success)
                    single = holder.state.cards.single { card -> card.providerType == SWITCH_CARD }.id
                    assertEquals(CardSize(1, 1), holder.state.cards.single { card -> card.id == single }.size)
                    assertTrue(holder.resize(group, CardSize(1, 1)) is LayoutMutationResult.Failure)
                }
                fun position(card: String, label: String): Pair<Int, Int> {
                    var location = 0 to 0
                    tile(card, label).check { view, error ->
                        if (error != null) throw error
                        assertTrue("Hit target too small: ${view.width}×${view.height}", view.width >= 48 && view.height >= 48)
                        val xy = IntArray(2)
                        view.getLocationOnScreen(xy)
                        location = xy[0] to xy[1]
                    }
                    return location
                }
                assertTrue(position(group, "左键").first < position(group, "右键").first)
                position(single, "左键")
                tile(group, "中键").perform(click())
                tile(single, "左键").perform(click())
                scenario.onActivity {
                    val model = vm(it)
                    val channels = model.dataSource.state.switches.channels.values.filter { channel ->
                        channel.identity.deviceId == "demo-switch-3"
                    }
                    assertEquals(listOf(true, true, false), channels.map { channel -> channel.isOn })
                    assertTrue(model.dataSource.homeState.entities.single { entity -> entity.id == "switch:demo-switch-3-0" }.isOn)
                }
                tile(group, "左键").check(matches(withContentDescription(containsString(context.getString(R.string.switch_on)))))
                onView(allOf(withText(three), isDescendantOfA(withTagValue(equalTo<Any>(group))))).perform(click())
                tile(group, "左键").check(matches(withContentDescription(containsString(context.getString(R.string.switch_on)))))
                tile(group, "左键").perform(swipe(.5f, .5f, 520, 0, Swipe.FAST))
                onView(isAssignableFrom(com.dormpanel.app.home.HomeControlView::class.java)).check(matches(isDisplayed()))
                pressBack()
                tile(group, "左键").check(matches(allOf(isDisplayed(),
                    withContentDescription(containsString(context.getString(R.string.switch_on))))))
                onView(withId(R.id.dashboard_edit)).perform(click())
                // In edit mode the same screen region belongs to drag chrome.
                tile(group, "中键").check(matches(not(isClickable())))
                val groupChrome = hasSibling(withTagValue(equalTo<Any>(group)))
                val resize = onView(allOf(withParent(groupChrome), withContentDescription(
                    context.getString(R.string.dashboard_resize_card, context.getString(R.string.card_switch_group)))))
                var column = 0
                var row = 0
                scenario.onActivity {
                    val grid = it.findViewById<View>(R.id.dashboard_grid)
                    column = (grid.width + 10) / 8
                    row = (grid.height + 10) / 6
                }
                resize.perform(swipe(.5f, .5f, -column, row))
                ready(scenario) { it.stateHolder.state.cards.any { card -> card.id == group && card.size == CardSize(1, 2) } }
                onView(groupChrome).perform(swipe(.5f, .5f, 0, row))
                ready(scenario) { it.stateHolder.state.cards.any { card -> card.id == group && card.row == 1 } }
                val gear = onView(allOf(withParent(groupChrome), withContentDescription(R.string.switch_configure)))
                gear.perform(click())
                onView(withText(startsWith("1. 左键"))).perform(click())
                onView(withText(startsWith("右键  ·"))).perform(click())
                onView(withText(R.string.switch_save)).perform(click())
                ready(scenario) { it.stateHolder.state.configurationSaves.isEmpty() && !it.stateHolder.state.storageError }
                onView(withId(R.id.dashboard_done)).perform(click())
                assertTrue(position(group, "右键").second < position(group, "左键").second)
                scenario.onActivity {
                    vm(it).appearance.setTheme(com.dormpanel.app.appearance.ThemeMode.LIGHT)
                    vm(it).appearance.setCardOpacity(.3f)
                }
                position(group, "中键")
                scenario.onActivity {
                    val card = vm(it).stateHolder.state.cards.single { card -> card.id == group }
                    savedJson = card.configurationJson
                    assertEquals(listOf("switch:demo-switch-3-2", "switch:demo-switch-3-1", "switch:demo-switch-3-0"),
                        SwitchCardConfiguration.decode(savedJson)!!.channels.map { channel -> channel.identity.entityId })
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario) { it.stateHolder.state.loaded }
                scenario.onActivity {
                    val cards = vm(it).stateHolder.state.cards
                    assertEquals(CardSize(1, 2), cards.single { card -> card.id == group }.size)
                    assertEquals(1, cards.single { card -> card.id == group }.row)
                    assertEquals(savedJson, cards.single { card -> card.id == group }.configurationJson)
                    assertEquals(CardSize(1, 1), cards.single { card -> card.id == single }.size)
                }
            }
        } finally {
            settings.write(saved)
        }
    }
}
