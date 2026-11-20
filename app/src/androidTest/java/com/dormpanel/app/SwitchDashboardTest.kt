package com.dormpanel.app

import android.view.View
import android.view.ViewConfiguration
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

    @Test fun minimumSwitchHitTargetsAreDisjointAtDeviceDensity() {
        val settings = HaSettingsStore(context)
        val saved = settings.read()
        settings.write(saved.copy(mode = BackendMode.DEMO))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario) { it.stateHolder.state.loaded }
                val density = context.resources.displayMetrics.density
                fun bounds(view: View, minimumDp: Int): android.graphics.Rect {
                    val rect = android.graphics.Rect()
                    assertTrue(view.getGlobalVisibleRect(rect))
                    assertTrue("${rect.width() / density}×${rect.height() / density} dp",
                        rect.width() / density >= minimumDp && rect.height() / density >= minimumDp)
                    return rect
                }
                var single = ""
                scenario.onActivity {
                    val model = vm(it)
                    val holder = model.stateHolder
                    holder.state.cards.forEach { card -> holder.delete(card.id) }
                    assertTrue(holder.add(model.catalog.candidates.first { card -> card.candidateId == "switch:switch:desk" })
                        is LayoutMutationResult.Success)
                    single = holder.state.cards.single().id
                    assertTrue(holder.move(single, 6, 4) is LayoutMutationResult.Success)
                }
                onView(withId(R.id.dashboard_edit)).perform(click())
                val chrome = withParent(hasSibling(withTagValue(equalTo<Any>(single))))
                val configure = onView(allOf(chrome, withContentDescription(R.string.switch_configure)))
                val controls = listOf(configure to 48, onView(allOf(chrome, withContentDescription(
                    context.getString(R.string.dashboard_delete_card, context.getString(R.string.card_switch))))) to 44,
                    onView(allOf(chrome, withContentDescription(
                        context.getString(R.string.dashboard_resize_card, context.getString(R.string.card_switch))))) to 48)
                val editRects = controls.map { (control, minimum) ->
                    lateinit var rect: android.graphics.Rect
                    control.check { view, error ->
                        if (error != null) throw error
                        rect = bounds(view, minimum)
                    }
                    rect
                }
                assertTrue(editRects.indices.all { a -> (0 until a).none { b -> android.graphics.Rect.intersects(editRects[a], editRects[b]) } })
                android.util.Log.i("SwitchGeometry", "density=$density, edit rectangles in px=$editRects")
                configure.perform(click())
                onView(withText(R.string.switch_save)).check(matches(isDisplayed()))
                onView(withText(android.R.string.cancel)).perform(click())
                onView(withId(R.id.dashboard_done)).perform(click())
                val groups = listOf("desk" to listOf("Desk outlet"), "demo-switch-2" to listOf("左键", "右键"),
                    "demo-switch-3" to listOf("左键", "中键", "右键"))
                groups.forEach { (device, labels) ->
                    var group = ""
                    scenario.onActivity {
                        val model = vm(it)
                        val holder = model.stateHolder
                        assertTrue(holder.add(model.catalog.candidates.first { it.candidateId == "switch-group:$device" })
                            is LayoutMutationResult.Success)
                        group = holder.state.cards.single { card -> card.id != single }.id
                    }
                    listOf(CardSize(2, 1), CardSize(1, 2)).forEach { size ->
                        scenario.onActivity { assertTrue(vm(it).stateHolder.resize(group, size) is LayoutMutationResult.Success) }
                        val hitRects = labels.map { label ->
                            lateinit var rect: android.graphics.Rect
                            tile(group, label).check { view, error ->
                                if (error != null) throw error
                                rect = bounds(view, 48)
                            }.perform(click())
                            rect
                        }
                        assertTrue(hitRects.indices.all { a -> (0 until a).none { b -> android.graphics.Rect.intersects(hitRects[a], hitRects[b]) } })
                        android.util.Log.i("SwitchGeometry", "$size $device touch rectangles in px=$hitRects")
                    }
                    scenario.onActivity { vm(it).stateHolder.delete(group) }
                }
            }
        } finally { settings.write(saved) }
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
                var complete: ((Result<Unit>) -> Unit)? = null
                scenario.onActivity { persistence.interceptNextSave = { complete = it } }
                onView(withText(R.string.switch_save)).perform(click())
                onView(withText(R.string.switch_saving)).check(matches(isDisplayed()))
                onView(withText(R.string.switch_save)).check(matches(not(isEnabled())))
                scenario.onActivity { checkNotNull(complete)(Result.failure(IllegalStateException("test write failed"))) }
                onView(withText(R.string.switch_save_failed)).check(matches(isDisplayed()))
                onView(withHint(R.string.switch_custom_channel)).check(matches(withText(alias)))
                onView(withText(R.string.switch_save)).perform(click())
                ready(scenario) { it.stateHolder.state.configurationSaves.isEmpty() && !it.stateHolder.state.storageError }
                onView(withText(R.string.switch_save)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
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
                val configure = onView(allOf(withParent(deskChrome), withContentDescription(R.string.switch_configure)))
                configure.perform(click())
                onView(withHint(R.string.switch_custom_channel)).perform(replaceText("Closing while saving"))
                scenario.onActivity { persistence.interceptNextSave = { complete = it } }
                onView(withText(R.string.switch_save)).perform(click())
                onView(withText(R.string.switch_saving)).check(matches(isDisplayed()))
                onView(withText(android.R.string.cancel)).perform(click())
                configure.perform(click())
                onView(withHint(R.string.switch_custom_channel)).perform(replaceText("New dialog draft"))
                scenario.onActivity { checkNotNull(complete)(Result.failure(IllegalStateException("obsolete window"))) }
                onView(withText(R.string.switch_save)).check(matches(allOf(isDisplayed(), isEnabled())))
                onView(withHint(R.string.switch_custom_channel)).check(matches(withText("New dialog draft")))
                onView(withText(R.string.switch_save_failed)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                onView(withText(android.R.string.cancel)).perform(click())
                onView(allOf(withParent(deskChrome), withContentDescription(
                    context.getString(R.string.dashboard_delete_card, context.getString(R.string.card_switch))))).perform(click())
                ready(scenario) { it.stateHolder.state.cards.none { card -> card.id == desk } }
            }
        } finally {
            settings.write(saved)
        }
    }

    @Test fun pendingIndependentAddCannotCloseAReplacementDialog() {
        val settings = HaSettingsStore(context)
        val saved = settings.read()
        settings.write(saved.copy(mode = BackendMode.DEMO))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario) { it.stateHolder.state.loaded }
                onView(withId(R.id.dashboard_edit)).perform(click())
                onView(withId(R.id.dashboard_add)).perform(click())
                onView(withText(R.string.category_home)).perform(click())
                val outlet = onView(allOf(withText("Desk outlet"), hasSibling(withText(R.string.card_switch_description))))
                outlet.perform(scrollTo(), click())
                lateinit var persist: () -> Unit
                scenario.onActivity { persistence.deferNextSave = { persist = it } }
                onView(withText(R.string.switch_save)).perform(click())
                onView(withText(R.string.switch_saving)).check(matches(isDisplayed()))
                onView(withText(android.R.string.cancel)).perform(click())
                outlet.perform(scrollTo(), click())
                onView(withHint(R.string.switch_custom_channel)).perform(replaceText("Replacement draft"))
                scenario.onActivity { persist() }
                ready(scenario) { it.stateHolder.state.configurationSaves.isEmpty() }
                onView(withText(R.string.switch_save)).check(matches(allOf(isDisplayed(), isEnabled())))
                onView(withHint(R.string.switch_custom_channel)).check(matches(withText("Replacement draft")))
                scenario.onActivity { assertEquals(1, vm(it).stateHolder.state.cards.count { card -> card.providerType == SWITCH_CARD }) }
            }
        } finally { settings.write(saved) }
    }

    @Test fun addResizeReorderAndReloadSwitchCards() {
        val settings = HaSettingsStore(context)
        val saved = settings.read()
        settings.write(saved.copy(mode = BackendMode.DEMO))
        try {
            var single = ""
            var group = ""
            var savedJson = ""
            var compactNamePixels = 0f
            val three = "Demo · 3键开关"
            fun channelName(card: String) = onView(allOf(withText("中键"), isDescendantOfA(withTagValue(equalTo<Any>(card)))))
            fun checkGlyph(card: String, label: String, expected: SwitchGlyph.State) {
                tile(card, label).check { view, error ->
                    if (error != null) throw error
                    val glyph = (view as android.view.ViewGroup).getChildAt(0) as SwitchGlyph
                    assertEquals(expected, glyph.state)
                    assertTrue(glyph.width > glyph.height && glyph.height > 0)
                    assertFalse(glyph.isClickable)
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario) { it.stateHolder.state.loaded }
                scenario.onActivity {
                    val holder = vm(it).stateHolder
                    holder.state.cards.toList().forEach { card -> holder.delete(card.id) }
                }
                onView(withId(R.id.dashboard_edit)).perform(click())
                onView(withId(R.id.dashboard_add)).perform(click())
                onView(withText(R.string.category_home)).perform(click())
                onView(allOf(withText(three), hasSibling(withText(R.string.card_switch_group_description)))).perform(scrollTo())
                onView(withText(three)).perform(scrollTo(), click())
                val alias = "Hall panel"
                onView(withHint(R.string.switch_custom_device)).perform(replaceText(alias))
                onView(withText(startsWith("1. 左键"))).perform(click())
                onView(withText(startsWith("右键  ·"))).perform(click())
                lateinit var failWrite: (Result<Unit>) -> Unit
                scenario.onActivity { persistence.interceptNextSave = { failWrite = it } }
                onView(withText(R.string.switch_save)).perform(click())
                onView(withText(R.string.switch_saving)).check(matches(isDisplayed()))
                scenario.onActivity {
                    group = vm(it).stateHolder.state.cards.single().id
                    failWrite(Result.failure(IllegalStateException("test")))
                }
                onView(withText(R.string.switch_save_failed)).check(matches(isDisplayed()))
                onView(withHint(R.string.switch_custom_device)).check(matches(withText(alias)))
                onView(withText(startsWith("1. 右键"))).check(matches(isDisplayed()))
                lateinit var persistRetry: () -> Unit
                scenario.onActivity { persistence.deferNextSave = { persistRetry = it } }
                onView(withText(R.string.switch_save)).perform(click())
                onView(withText(R.string.switch_saving)).check(matches(isDisplayed()))
                onView(withText(R.string.switch_save)).check(matches(not(isEnabled())))
                scenario.onActivity {
                    assertEquals(group, vm(it).stateHolder.state.cards.single().id)
                    persistRetry()
                }
                ready(scenario) { it.stateHolder.state.configurationSaves.isEmpty() }
                onView(withId(R.id.dashboard_done)).perform(click())
                scenario.onActivity {
                    val model = vm(it)
                    val holder = model.stateHolder
                    assertEquals(group, holder.state.cards.single().id)
                    val savedBinding = SwitchCardConfiguration.decode(holder.state.cards.single().configurationJson)!!
                    assertEquals(alias, savedBinding.name)
                    assertEquals("switch:demo-switch-3-2", savedBinding.channels.first().identity.entityId)
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
                assertTrue(position(group, "右键").first < position(group, "左键").first)
                position(single, "左键")
                channelName(group).check { view, error ->
                    if (error != null) throw error
                    compactNamePixels = (view as android.widget.TextView).textSize
                }
                checkGlyph(group, "左键", SwitchGlyph.State.OFF)
                tile(group, "中键").perform(click())
                tile(single, "左键").perform(click())
                checkGlyph(group, "左键", SwitchGlyph.State.ON)
                checkGlyph(single, "左键", SwitchGlyph.State.ON)
                checkGlyph(group, "右键", SwitchGlyph.State.OFF)
                scenario.onActivity {
                    val model = vm(it)
                    val channels = model.dataSource.state.switches.channels.values.filter { channel ->
                        channel.identity.deviceId == "demo-switch-3"
                    }
                    assertEquals(listOf(true, true, false), channels.map { channel -> channel.isOn })
                    assertTrue(model.dataSource.homeState.entities.single { entity -> entity.id == "switch:demo-switch-3-0" }.isOn)
                }
                tile(group, "左键").check(matches(withContentDescription(containsString(context.getString(R.string.switch_on)))))
                onView(allOf(withText(alias), isDescendantOfA(withTagValue(equalTo<Any>(group))))).perform(click())
                tile(group, "左键").check(matches(withContentDescription(containsString(context.getString(R.string.switch_on)))))
                // Give the preceding tap its own gesture sequence on fast emulators.
                Thread.sleep(ViewConfiguration.getDoubleTapTimeout().toLong())
                tile(group, "右键").perform(swipe(.5f, .5f, 520, 0, Swipe.FAST))
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
                onView(withText(startsWith("1. 右键"))).perform(click())
                onView(withText(startsWith("左键  ·"))).perform(click())
                onView(withText(R.string.switch_save)).perform(click())
                ready(scenario) { it.stateHolder.state.configurationSaves.isEmpty() && !it.stateHolder.state.storageError }
                onView(withId(R.id.dashboard_done)).perform(click())
                assertTrue(position(group, "左键").second < position(group, "右键").second)
                scenario.onActivity {
                    vm(it).appearance.setTheme(com.dormpanel.app.appearance.ThemeMode.LIGHT)
                    vm(it).appearance.setCardOpacity(.3f)
                }
                position(group, "中键")
                scenario.onActivity {
                    val card = vm(it).stateHolder.state.cards.single { card -> card.id == group }
                    savedJson = card.configurationJson
                    assertEquals(listOf("switch:demo-switch-3-0", "switch:demo-switch-3-1", "switch:demo-switch-3-2"),
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
                    assertTrue(vm(it).stateHolder.resize(group, CardSize(3, 2)) is LayoutMutationResult.Success)
                }
                channelName(group).check { view, error ->
                    if (error != null) throw error
                    assertTrue((view as android.widget.TextView).textSize > compactNamePixels)
                }
            }
        } finally {
            settings.write(saved)
        }
    }
}
