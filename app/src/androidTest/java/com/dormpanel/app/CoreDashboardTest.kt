package com.dormpanel.app

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.appearance.*
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.dashboard.card.CoreCardView
import com.dormpanel.app.dashboard.card.millisUntilNextMinute
import com.dormpanel.app.dashboard.model.CardSize
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreDashboardTest {
    private fun model(activity: MainActivity) = ViewModelProvider(activity)[DashboardViewModel::class.java]
    private fun card(name: String) = allOf(isAssignableFrom(CoreCardView::class.java), withContentDescription(containsString(name)))
    private fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float) = GeneralSwipeAction(
        Swipe.FAST,
        { view -> val pos = IntArray(2); view.getLocationOnScreen(pos); floatArrayOf(pos[0] + view.width * fromX, pos[1] + view.height * fromY) },
        { view -> val pos = IntArray(2); view.getLocationOnScreen(pos); floatArrayOf(pos[0] + view.width * toX, pos[1] + view.height * toY) },
        Press.FINGER,
    )
    private fun navigate(action: androidx.test.espresso.ViewAction) {
        onView(withId(R.id.page_container)).perform(action)
    }
    private fun waitForCards(scenario: ActivityScenario<MainActivity>) {
        val until = System.currentTimeMillis() + 5000
        var loaded = false
        while (!loaded && System.currentTimeMillis() < until) {
            scenario.onActivity { loaded = model(it).stateHolder.state.loaded }
            if (!loaded) Thread.sleep(20)
        }
        assertTrue(loaded)
    }

    @Test fun lightGesturesCapabilitiesAndEditIsolation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForCards(scenario)
            var before = false
            scenario.onActivity { before = model(it).dataSource.state.lights.getValue("desk").isOn }
            onView(card("Desk light")).perform(ViewActions.click())
            scenario.onActivity { assertEquals(!before, model(it).dataSource.state.lights.getValue("desk").isOn) }
            onView(card("Desk light")).perform(ViewActions.longClick())
            onView(withContentDescription("Light brightness")).perform(swipe(.1f, .5f, .85f, .5f))
            onView(withContentDescription("Light color temperature")).perform(swipe(.1f, .5f, .9f, .5f))
            scenario.onActivity {
                val light = model(it).dataSource.state.lights.getValue("desk")
                assertTrue(light.brightness in 75..95)
                assertTrue(light.colorTemperature in 5700..6500)
            }
            onView(withText("Done")).perform(ViewActions.click())
            onView(withId(R.id.dashboard_edit)).check(matches(isDisplayed()))
            onView(card("Bedside light")).perform(ViewActions.longClick())
            onView(withContentDescription("Light brightness")).check(matches(isDisplayed()))
            onView(withContentDescription("Light color temperature")).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withText("Done")).perform(ViewActions.click())
            onView(card("Ceiling light")).perform(ViewActions.longClick())
            onView(withContentDescription("Light brightness")).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withText("Turn on")).perform(ViewActions.click())
            onView(withText("Done")).perform(ViewActions.click())
            onView(withId(R.id.dashboard_edit)).perform(ViewActions.click())
            scenario.onActivity {
                val view = descendants(it.findViewById(R.id.dashboard_grid)).filterIsInstance<CoreCardView>().first { card -> card.contentDescription.contains("Desk light") }
                val original = model(it).dataSource.state
                assertFalse(view.isClickable)
                assertFalse(view.isLongClickable)
                view.performClick()
                view.performLongClick()
                assertEquals(original, model(it).dataSource.state)
            }
            navigate(swipe(.5f, .8f, .5f, .2f))
            onView(withId(R.id.dashboard_done)).check(matches(isDisplayed())).perform(ViewActions.click())
        }
    }

    @Test fun appearanceAppliesLiveAndAllDirectionsStillNavigate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForCards(scenario)
            navigate(swipe(.5f, .2f, .5f, .8f))
            onView(withText("Light")).perform(ViewActions.click())
            onView(withContentDescription("Card surface opacity")).perform(swipe(.9f, .5f, .05f, .5f))
            onView(withText("Control Center")).check(matches(isDisplayed()))
            scenario.onActivity {
                assertEquals(ThemeMode.LIGHT, model(it).appearance.state.themeMode)
                assertTrue(model(it).appearance.state.cardSurfaceOpacity < .15f)
                assertEquals(model(it).appearance.state, PreferencesAppearanceStore(it).read())
            }
            navigate(swipe(.5f, .8f, .5f, .2f))
            onView(card("Desk light")).check(matches(isDisplayed()))
            scenario.onActivity {
                val root = it.findViewById<ViewGroup>(R.id.page_container)
                assertEquals(PanelPalette.forMode(ThemeMode.LIGHT).background, (root.background as ColorDrawable).color)
                model(it).appearance.setTheme(ThemeMode.DARK)
                model(it).appearance.setCardOpacity(0f)
                assertEquals(Color.BLACK, (root.background as ColorDrawable).color)
                assertEquals(Color.BLACK, (root.getChildAt(root.childCount - 1).background as ColorDrawable).color)
                descendants(root).filterIsInstance<CoreCardView>().forEach { view ->
                    assertEquals(1f, view.alpha)
                    val fill = ((view.parent as View).background as android.graphics.drawable.GradientDrawable).color!!.defaultColor
                    assertEquals(0, Color.alpha(fill))
                }
                model(it).appearance.setCardOpacity(1f)
            }
            listOf(
                Triple(swipe(.5f,.8f,.5f,.2f), "Apps", swipe(.5f,.2f,.5f,.8f)),
                Triple(swipe(.8f,.85f,.2f,.85f), "Calendar", swipe(.2f,.85f,.8f,.85f)),
                Triple(swipe(.2f,.85f,.8f,.85f), "Home Control", swipe(.8f,.85f,.2f,.85f)),
            ).forEach { (out, title, back) ->
                navigate(out)
                onView(withText(title)).check(matches(isDisplayed()))
                navigate(back)
                onView(card("Desk light")).check(matches(isDisplayed()))
            }
        }
    }

    @Test fun responsiveCardsKeepTheirViewsAndRenderCompactContent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForCards(scenario)
            scenario.onActivity { activity ->
                val holder = model(activity).stateHolder
                val originals = holder.state.cards
                val grid = activity.findViewById<ViewGroup>(R.id.dashboard_grid)
                val views = descendants(grid).filterIsInstance<CoreCardView>().toList()
                originals.forEach { holder.resize(it.id, CardSize(2, 1)) }
                assertEquals(views, descendants(grid).filterIsInstance<CoreCardView>().toList())
                assertTrue(views.first().contentDescription.toString().contains(Regex("[0-9]+:[0-9]{2}")))
                assertFalse(views.first().contentDescription.contains("LOCAL TIME"))
                val sensor = views.first { it.contentDescription.contains("Room climate") }
                assertTrue(sensor.contentDescription.contains("54%"))
                assertFalse(views.first { it.contentDescription.contains("Partly cloudy") }.contentDescription.contains("Tomorrow"))
                originals.forEach { holder.resize(it.id, it.size) }
                assertTrue(views.first().contentDescription.contains("LOCAL TIME"))
            }
        }
    }

    @Test fun dragResizeUsesProviderPoliciesForEveryCoreCard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForCards(scenario)
            var original = emptyList<com.dormpanel.app.dashboard.model.PlacedCard>()
            scenario.onActivity { original = model(it).stateHolder.state.cards }
            onView(withId(R.id.dashboard_edit)).perform(ViewActions.click())
            listOf(
                Triple("Clock & date", "LOCAL TIME", "seed-clock"),
                Triple("Weather", "Partly cloudy", "seed-weather"),
                Triple("Room climate", "Room climate", "seed-sensor"),
                Triple("Light", "Desk light", "seed-light"),
            ).forEach { (providerName, contentName, id) ->
                var dx = 0f
                var dy = 0f
                scenario.onActivity {
                    val grid = it.findViewById<View>(R.id.dashboard_grid)
                    val placed = model(it).stateHolder.state.cards.first { c -> c.id == id }
                    val gap = 10 * it.resources.displayMetrics.density
                    dx = (placed.size.columnSpan - 2) * ((grid.width + gap) / 8)
                    dy = (placed.size.rowSpan - 1) * ((grid.height + gap) / 6)
                }
                val handle = allOf(withContentDescription("Resize $providerName card"),
                    withParent(withParent(hasDescendant(card(contentName)))))
                onView(handle).perform(GeneralSwipeAction(Swipe.SLOW,
                    { v -> val p = IntArray(2); v.getLocationOnScreen(p); floatArrayOf(p[0] + v.width / 2f, p[1] + v.height / 2f) },
                    { v -> val p = IntArray(2); v.getLocationOnScreen(p); floatArrayOf(p[0] + v.width / 2f - dx, p[1] + v.height / 2f - dy) }, Press.FINGER))
                scenario.onActivity { assertEquals(CardSize(2, 1), model(it).stateHolder.state.cards.first { c -> c.id == id }.size) }
            }
            onView(withId(R.id.dashboard_done)).perform(ViewActions.click())
            scenario.onActivity { activity ->
                original.forEach { model(activity).stateHolder.resize(it.id, it.size) }
                original.forEach { model(activity).stateHolder.move(it.id, it.column, it.row) }
            }
        }
    }

    @Test fun preferencesRestoreFromRealAndroidStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = PreferencesAppearanceStore(context)
        val original = store.read()
        try {
            val controller = AppearanceController(store)
            controller.update(AppearanceState(ThemeMode.LIGHT, .37f))
            assertEquals(controller.state, AppearanceController(PreferencesAppearanceStore(context)).state)
        } finally { store.write(original) }
    }

    @Test fun clockCrossesMinuteBoundaryAndStopsWhenDetached() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForCards(scenario)
            var clock: CoreCardView? = null
            var before = ""
            scenario.onActivity {
                clock = descendants(it.findViewById(R.id.dashboard_grid)).filterIsInstance<CoreCardView>().first { view -> view.contentDescription.contains("LOCAL TIME") }
                before = clock!!.contentDescription.toString()
            }
            Thread.sleep(millisUntilNextMinute(System.currentTimeMillis()) + 150)
            scenario.onActivity { assertNotEquals(before, clock!!.contentDescription.toString()) }
            navigate(swipe(.5f,.8f,.5f,.2f))
            onView(withText("Apps")).check(matches(isDisplayed()))
            scenario.onActivity { assertFalse(clock!!.isAttachedToWindow) }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
