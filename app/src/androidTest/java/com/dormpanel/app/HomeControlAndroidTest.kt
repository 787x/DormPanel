package com.dormpanel.app

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.appearance.*
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.ha.*
import com.dormpanel.app.home.*
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeControlAndroidTest {
    @get:org.junit.Rule val dashboardPersistence = com.dormpanel.app.IsolatedDashboardRule()
    private var savedHa = HaConnectionSettings()
    private var savedAppearance = AppearanceState()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun isolate() {
        savedHa = HaSettingsStore(context).read(); savedAppearance = PreferencesAppearanceStore(context).read()
        HaSettingsStore(context).write(savedHa.copy(mode = BackendMode.DEMO))
    }
    @After fun restore() { HaSettingsStore(context).write(savedHa); PreferencesAppearanceStore(context).write(savedAppearance) }
    private fun model(activity: MainActivity) = ViewModelProvider(activity)[DashboardViewModel::class.java]
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) = GeneralSwipeAction(Swipe.FAST,
        { v -> val p = IntArray(2); v.getLocationOnScreen(p); floatArrayOf(p[0] + v.width*x1, p[1] + v.height*y1) },
        { v -> val p = IntArray(2); v.getLocationOnScreen(p); floatArrayOf(p[0] + v.width*x2, p[1] + v.height*y2) }, Press.FINGER)
    private fun navigate(right: Boolean) { onView(withId(R.id.page_container)).perform(if (right) swipe(.2f,.5f,.8f,.5f) else swipe(.8f,.08f,.3f,.08f)) }
    private fun choose(name: String) { onView(allOf(withText(name), isAssignableFrom(Button::class.java))).perform(ViewActions.click()) }
    private fun rowButton(entity: String, button: String) = allOf(withText(button), isAssignableFrom(Button::class.java),
        withParent(hasDescendant(withText(entity))))
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(250) // Wait for the next rendered frame, beyond main-thread queue idleness.
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun areasActionsDetailsGesturesAppearanceAndSessionSelection() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            navigate(true)
            onView(withText("Home Control")).check(matches(isDisplayed()))
            choose("Study")
            screenshot("pr5-study.png")
            onView(withText("Study · Desk")).check(matches(isDisplayed()))
            onView(rowButton("Desk outlet", "Turn on")).perform(ViewActions.click())
            scenario.onActivity { assertTrue(model(it).dataSource.homeState.entities.single { e -> e.kind == HomeKind.SWITCH }.isOn) }
            onView(rowButton("Desk light", "Turn off")).perform(ViewActions.click())
            scenario.onActivity { assertFalse(model(it).dataSource.state.lights.getValue("desk").isOn) }
            onView(rowButton("Desk light", "Details")).perform(ViewActions.click())
            onView(withContentDescription("Light brightness")).perform(swipe(.9f,.5f,.01f,.5f))
            scenario.onActivity { assertEquals(1, model(it).dataSource.state.lights.getValue("desk").brightness); assertTrue(model(it).dataSource.state.lights.getValue("desk").isOn) }
            onView(withContentDescription("Light color temperature")).perform(swipe(.8f,.5f,.2f,.5f))
            choose("Done")
            onView(withText("Home Control")).check(matches(isDisplayed()))
            choose("Run")
            navigate(false)
            onView(withId(R.id.dashboard_edit)).check(matches(isDisplayed()))
            navigate(true)
            scenario.onActivity { assertEquals("study", model(it).dataSource.homeSelection.areaId) }
            choose("Unassigned")
            onView(withText("Ceiling light")).check(matches(isDisplayed()))
            choose("Bedroom")
            onView(isAssignableFrom(RecyclerView::class.java)).perform(ViewActions.swipeUp())
            choose("Activate")
            scenario.onActivity { assertTrue(model(it).dataSource.state.lights.values.none { light -> light.isOn }) }
            onView(isAssignableFrom(RecyclerView::class.java)).perform(ViewActions.swipeDown())
            onView(withText("23.6 °C")).check(matches(isDisplayed()))
            choose("All")
            onView(isAssignableFrom(RecyclerView::class.java)).perform(ViewActions.swipeUp())
            onView(withText("Home Control")).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val list = descendants(activity.findViewById(R.id.page_container)).filterIsInstance<RecyclerView>().single()
                assertTrue((list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition() > 0)
            }
            choose("Study")
            scenario.onActivity { activity ->
                val m = model(activity); m.appearance.setTheme(ThemeMode.LIGHT); m.appearance.setCardOpacity(.3f)
                val page = descendants(activity.findViewById(R.id.page_container)).filterIsInstance<HomeControlView>().single()
                assertEquals(PanelPalette.forMode(ThemeMode.LIGHT).background, (page.background as ColorDrawable).color)
            }
            screenshot("pr5-light.png")
            scenario.onActivity { activity ->
                model(activity).appearance.setTheme(ThemeMode.DARK)
                val page = descendants(activity.findViewById(R.id.page_container)).filterIsInstance<HomeControlView>().single()
                assertEquals(Color.BLACK, (page.background as ColorDrawable).color)
            }
            screenshot("pr5-dark.png")
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val list = descendants(activity.findViewById(R.id.page_container)).filterIsInstance<RecyclerView>().single()
                val fills = (0 until list.childCount).mapNotNull { (list.getChildAt(it).background as? GradientDrawable)?.color?.defaultColor }.filter { Color.alpha(it) > 0 }
                assertTrue(fills.isNotEmpty()); assertTrue(fills.all { Color.alpha(it) in 75..77 })
            }
            pressBack()
            onView(withId(R.id.dashboard_edit)).check(matches(isDisplayed()))
        }
    }
}
