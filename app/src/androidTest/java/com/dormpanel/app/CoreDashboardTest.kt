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
import com.dormpanel.app.dashboard.card.DashboardCardView
import com.dormpanel.app.dashboard.card.millisUntilNextMinute
import com.dormpanel.app.dashboard.model.CardSize
import org.junit.Before
import org.junit.After
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreDashboardTest {
    @get:org.junit.Rule val dashboardPersistence = com.dormpanel.app.IsolatedDashboardRule()
    private var savedHa = com.dormpanel.app.ha.HaConnectionSettings()
    private var savedAppearance = AppearanceState()
    @Before fun isolateDashboardFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val haStore = com.dormpanel.app.ha.HaSettingsStore(context)
        savedHa = haStore.read()
        haStore.write(savedHa.copy(mode = com.dormpanel.app.ha.BackendMode.DEMO))
        savedAppearance = PreferencesAppearanceStore(context).read()
        PreferencesAppearanceStore(context).write(AppearanceState())
    }
    @After fun restoreDashboardFixture() {
        PreferencesAppearanceStore(InstrumentationRegistry.getInstrumentation().targetContext).write(savedAppearance)
        com.dormpanel.app.ha.HaSettingsStore(InstrumentationRegistry.getInstrumentation().targetContext).write(savedHa)
    }
    private fun model(activity: MainActivity) = ViewModelProvider(activity)[DashboardViewModel::class.java]
    private fun card(name: String) = allOf(isAssignableFrom(DashboardCardView::class.java), withContentDescription(containsString(name)))
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
                assertTrue(light.brightness!! in 75..95)
                assertTrue(light.colorTemperature!! in 5700..6500)
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
                val view = descendants(it.findViewById(R.id.dashboard_grid)).filterIsInstance<DashboardCardView>().first { card -> card.contentDescription.contains("Desk light") }
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

    @Test fun inlineAndQuickBrightnessHaveNonOffMinimumAndExplicitPower() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForCards(scenario)
            scenario.onActivity { activity ->
                val vm = model(activity)
                vm.stateHolder.state.cards.toList().forEach { vm.stateHolder.delete(it.id) }
                vm.stateHolder.add(vm.catalog.candidates.first { it.candidateId == "light:desk" })
                vm.stateHolder.resize(vm.stateHolder.state.cards.single().id, CardSize(3, 3))
            }
            val inline = onView(withContentDescription("Inline brightness"))
            inline.check { view, error ->
                if (error != null) throw error
                val slider = view as android.widget.SeekBar
                assertEquals(1, slider.min); assertEquals(100, slider.max)
            }
            inline.perform(swipe(.8f, .5f, 0f, .5f))
            onView(withText("Brightness · 1%")).check(matches(isDisplayed()))
            scenario.onActivity {
                val light = model(it).dataSource.state.lights.getValue("desk")
                assertEquals(1, light.brightness); assertTrue(light.isOn)
            }
            // Tap/long press the card header, away from the sliders.
            fun header(tap: Tap) = GeneralClickAction(tap, { view ->
                val pos = IntArray(2); view.getLocationOnScreen(pos)
                floatArrayOf(pos[0] + view.width / 2f, pos[1] + 24f)
            }, Press.FINGER, 0, 0)
            onView(card("Desk light")).perform(header(Tap.LONG))
            val quick = onView(withContentDescription("Light brightness"))
            quick.check { view, error ->
                if (error != null) throw error
                val slider = view as android.widget.SeekBar
                assertEquals(1, slider.min); assertEquals(100, slider.max)
            }
            quick.perform(swipe(.8f, .5f, 0f, .5f))
            onView(withText("Turn off")).check(matches(isDisplayed()))
            scenario.onActivity {
                val light = model(it).dataSource.state.lights.getValue("desk")
                assertEquals(1, light.brightness); assertTrue(light.isOn)
            }
            onView(withText("Turn off")).perform(ViewActions.click())
            scenario.onActivity { assertFalse(model(it).dataSource.state.lights.getValue("desk").isOn) }
            quick.perform(swipe(.8f, .5f, 0f, .5f))
            scenario.onActivity {
                val light = model(it).dataSource.state.lights.getValue("desk")
                assertEquals(1, light.brightness); assertTrue(light.isOn)
            }
            onView(withText("Done")).perform(ViewActions.click())
            onView(card("Desk light")).perform(header(Tap.SINGLE))
            scenario.onActivity { assertFalse(model(it).dataSource.state.lights.getValue("desk").isOn) }
        }
    }

    @Test fun zeroAuthoritativeBrightnessDoesNotInventObservedControlValue() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val demo = com.dormpanel.app.data.FakeDashboardDataSource()
                val zero = demo.state.copy(lights = demo.state.lights.mapValues { (_, light) -> light.copy(isOn = false, brightness = 0) })
                var commands = 0
                val source = object : com.dormpanel.app.data.DashboardDataSource by demo {
                    override val state = zero
                    override fun addListener(listener: (com.dormpanel.app.data.DashboardData) -> Unit) { listener(state) }
                    override fun removeListener(listener: (com.dormpanel.app.data.DashboardData) -> Unit) {}
                    override fun setBrightness(id: String, percent: Int) { commands++ }
                }
                val appearance = model(activity).appearance
                val scope = com.dormpanel.app.dashboard.card.CardInteractionScope(true) {}
                val inline = com.dormpanel.app.dashboard.card.LightCardView(activity, appearance, source)
                inline.bind(com.dormpanel.app.dashboard.model.PlacedCard("zero", "light", 0, 0, CardSize(3, 3), "{\"lightId\":\"desk\"}"), scope)
                val slider = descendants(inline).filterIsInstance<android.widget.SeekBar>().first()
                assertEquals(1, slider.min); assertEquals(1, slider.progress)
                assertTrue(descendants(inline).filterIsInstance<TextView>().any { it.text.toString() == "Brightness · Unknown" })
                val dialog = com.dormpanel.app.dashboard.card.LightQuickControls(activity, source, appearance, "desk", scope) {}
                try {
                    dialog.show()
                    val quick = descendants(dialog.window!!.decorView).filterIsInstance<android.widget.SeekBar>().first()
                    assertEquals(1, quick.min); assertEquals(1, quick.progress)
                    assertEquals(0, source.state.lights.getValue("desk").brightness)
                    assertFalse(source.state.lights.getValue("desk").isOn)
                    assertEquals(0, commands)
                } finally { dialog.dismiss() }
            }
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
                descendants(root).filterIsInstance<DashboardCardView>().forEach { view ->
                    assertEquals(1f, view.alpha)
                    val fill = ((view.parent as View).background as android.graphics.drawable.GradientDrawable).color!!.defaultColor
                    assertEquals(0, Color.alpha(fill))
                }
                model(it).appearance.setCardOpacity(1f)
            }
            listOf(
                Triple(swipe(.5f,.8f,.5f,.2f), "Apps", swipe(.5f,.05f,.5f,.8f)),
                Triple(swipe(.8f,.85f,.2f,.85f), "Calendar", swipe(.2f,.85f,.8f,.85f)),
                Triple(swipe(.2f,.85f,.8f,.85f), "Home Control", swipe(.8f,.85f,.2f,.85f)),
            ).forEach { (out, title, back) ->
                navigate(out)
                onView(withText(title)).check(matches(isDisplayed()))
                when (title) {
                    "Apps" -> onView(withId(R.id.apps_home)).perform(ViewActions.click())
                    "Calendar" -> onView(withText("Home")).perform(ViewActions.click())
                    else -> navigate(back)
                }
                onView(card("Desk light")).check(matches(isDisplayed()))
            }
        }
    }

    @Test fun responsiveCardsKeepTheirViewsAndRenderCompactContent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForCards(scenario)
            onView(allOf(withText(R.string.temperature), isDescendantOfA(card("Room climate")))).check(matches(isDisplayed()))
            onView(allOf(withText(R.string.humidity), isDescendantOfA(card("Room climate")))).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val holder = model(activity).stateHolder
                val originals = holder.state.cards
                val grid = activity.findViewById<ViewGroup>(R.id.dashboard_grid)
                val views = descendants(grid).filterIsInstance<DashboardCardView>().toList()
                originals.forEach { holder.resize(it.id, CardSize(2, 1)) }
                assertEquals(views, descendants(grid).filterIsInstance<DashboardCardView>().toList())
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

    @Test fun categorizedPickerCreatesDistinctDevicesAndResponsiveInlineControls() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForCards(scenario)
            captureReview("01-seed-dark")
            scenario.onActivity { activity -> model(activity).stateHolder.state.cards.toList().forEach { model(activity).stateHolder.delete(it.id) } }
            onView(withId(R.id.dashboard_edit)).perform(ViewActions.click())
            listOf("Bedside light", "Ceiling light", "Room climate", "Desk climate").forEachIndexed { index, name ->
                onView(withId(R.id.dashboard_add)).perform(ViewActions.click())
                onView(withText("Information")).check(matches(isDisplayed()))
                if (index == 0) captureReview("02-picker-information-dark")
                onView(withText("Home")).perform(ViewActions.click())
                if (index == 0) captureReview("03-picker-home-dark")
                onView(withContentDescription("Add $name")).perform(ViewActions.click())
            }
            onView(withId(R.id.dashboard_done)).perform(ViewActions.click())
            onView(card("Bedside light")).perform(ViewActions.click())
            scenario.onActivity {
                val vm = model(it)
                assertTrue(vm.dataSource.state.lights.getValue("bedside").isOn)
                assertFalse(vm.dataSource.state.lights.getValue("ceiling").isOn)
                assertEquals(setOf("{\"lightId\":\"bedside\"}", "{\"lightId\":\"ceiling\"}", "{\"sensorId\":\"room\"}", "{\"sensorId\":\"desk\"}"), vm.stateHolder.state.cards.map { c -> c.configurationJson }.toSet())
            }
            onView(card("Room climate")).check(matches(withContentDescription(containsString("23.6"))))
            onView(card("Desk climate")).check(matches(withContentDescription(containsString("24.1"))))
            captureReview("04-distinct-instances")
            scenario.onActivity { activity ->
                val vm = model(activity)
                vm.stateHolder.state.cards.toList().forEach { vm.stateHolder.delete(it.id) }
                listOf("clock", "weather", "sensor:room", "light:desk").forEach { id ->
                    assertTrue(vm.stateHolder.add(vm.catalog.candidates.first { c -> c.candidateId == id }) is com.dormpanel.app.dashboard.layout.LayoutMutationResult.Success)
                }
                vm.stateHolder.state.cards.filter { it.providerType == "sensor" || it.providerType == "light" }.forEach {
                    assertTrue(vm.stateHolder.resize(it.id, CardSize(4, 3)) is com.dormpanel.app.dashboard.layout.LayoutMutationResult.Success)
                }
            }
            onView(withContentDescription("Inline brightness")).perform(swipe(.2f,.5f,.8f,.5f))
            onView(withContentDescription("Inline color temperature")).perform(swipe(.2f,.5f,.85f,.5f))
            onView(withId(R.id.dashboard_edit)).check(matches(isDisplayed()))
            scenario.onActivity {
                val light = model(it).dataSource.state.lights.getValue("desk")
                assertTrue(light.brightness!! >= 75)
                assertTrue(light.colorTemperature!! >= 5700)
            }
            captureReview("05-large-dark")
            scenario.onActivity { model(it).appearance.setTheme(ThemeMode.LIGHT) }
            captureReview("06-large-light")
            onView(withId(R.id.dashboard_edit)).perform(ViewActions.click())
            onView(withContentDescription("Inline brightness")).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            onView(withContentDescription("Inline color temperature")).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            onView(withId(R.id.dashboard_add)).perform(ViewActions.click())
            onView(withText("Home")).perform(ViewActions.click())
            captureReview("07-picker-home-light")
            onView(withText(android.R.string.cancel)).perform(ViewActions.click())
            onView(withId(R.id.dashboard_done)).perform(ViewActions.click())
            onView(card("Desk light")).perform(GeneralClickAction(Tap.LONG,
                { v -> val p = IntArray(2); v.getLocationOnScreen(p); floatArrayOf(p[0] + v.width / 2f, p[1] + 30f) }, Press.FINGER))
            onView(withContentDescription("Light brightness")).check(matches(isDisplayed()))
            captureReview("08-light-dialog")
            onView(withText("Done")).perform(ViewActions.click())
            scenario.onActivity { activity ->
                val vm = model(activity)
                vm.appearance.setTheme(ThemeMode.DARK)
                vm.stateHolder.state.cards.toList().forEach { vm.stateHolder.resize(it.id, CardSize(2, 1)) }
                vm.stateHolder.state.cards.toList().forEachIndexed { index, card -> vm.stateHolder.move(card.id, index * 2, 0) }
            }
            captureReview("09-compact-dark")
            scenario.onActivity { activity ->
                val holder = model(activity).stateHolder
                val light = holder.state.cards.first { it.providerType == "light" }
                holder.move(light.id, 4, 3)
                holder.resize(light.id, CardSize(3, 2))
                holder.state.cards.first { it.providerType == "sensor" }.let { holder.resize(it.id, CardSize(3, 2)) }
            }
            onView(withContentDescription("Inline brightness")).check(matches(isDisplayed())).perform(swipe(.8f,.5f,.3f,.5f))
            onView(withContentDescription("Inline color temperature")).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withId(R.id.dashboard_edit)).check(matches(isDisplayed()))
            captureReview("10-wide-sensor-and-light")
            scenario.onActivity { activity -> model(activity).stateHolder.let { holder -> holder.resize(holder.state.cards.first { it.providerType == "light" }.id, CardSize(3, 3)) } }
            onView(withContentDescription("Inline color temperature")).check(matches(isDisplayed()))
            captureReview("11-light-3x3")
        }
    }

    @Test fun pr12ClockResizeRestoresTypographyAndBestFitKeepsNeighbors() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForCards(scenario)
            var clockId = ""
            var retained: DashboardCardView? = null
            var largeSize = 0f
            scenario.onActivity { activity ->
                val holder = model(activity).stateHolder
                holder.state.cards.toList().filter { it.providerType != "clock" }.forEach { holder.delete(it.id) }
                clockId = holder.state.cards.single().id
            }
            for ((index, size) in listOf(CardSize(4, 3), CardSize(2, 1), CardSize(3, 3), CardSize(4, 3)).withIndex()) {
                scenario.onActivity { assertTrue(model(it).stateHolder.resize(clockId, size) is com.dormpanel.app.dashboard.layout.LayoutMutationResult.Success) }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                onView(withId(R.id.dashboard_edit)).check(matches(isDisplayed()))
                scenario.onActivity { activity ->
                    val view = descendants(activity.findViewById(R.id.dashboard_grid)).filterIsInstance<com.dormpanel.app.dashboard.card.ClockCardView>().single()
                    val time = descendants(view).filterIsInstance<com.dormpanel.app.dashboard.card.FittingValueView>().single()
                    if (index == 0) { retained = view; largeSize = time.textSize }
                    else assertSame(retained, view)
                    if (size.rowSpan == 3) {
                        assertEquals(104f, time.maximumTextSp, 0f)
                        assertEquals(largeSize, time.textSize, 1f)
                    } else assertTrue(time.textSize < largeSize)
                }
                captureReview("pr12-clock-${size.columnSpan}x${size.rowSpan}-$index")
                if (size.rowSpan == 3) {
                    val position = IntArray(2)
                    var width = 0; var height = 0
                    scenario.onActivity { retained!!.getLocationOnScreen(position); width = retained!!.width; height = retained!!.height }
                    var brightPixels = 0
                    // waitForIdleSync reports the UI thread idle before X08E's compositor
                    // necessarily presents its next frame. Give the physical frame a
                    // bounded chance to appear instead of judging the first capture.
                    val deadline = System.currentTimeMillis() + 3000
                    do {
                        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                        brightPixels = 0
                        for (y in position[1] until position[1] + height) for (x in position[0] until position[0] + width) {
                            val pixel = bitmap.getPixel(x, y)
                            if (Color.red(pixel) > 180 && Color.green(pixel) > 180 && Color.blue(pixel) > 180) brightPixels++
                        }
                        bitmap.recycle()
                        if (brightPixels > 2000) break
                        Thread.sleep(100)
                    } while (System.currentTimeMillis() < deadline)
                    assertTrue("Expanded Clock must actually draw its text: $brightPixels bright pixels", brightPixels > 2000)
                }
            }
            scenario.onActivity { activity ->
                val vm = model(activity); val holder = vm.stateHolder
                holder.delete(clockId)
                val sensor = vm.catalog.candidates.first { it.candidateId == "sensor:room" }
                repeat(23) {
                    // Directly add minimum-size sensors to leave exactly one 2x1 opening.
                    assertTrue(holder.add(sensor) is com.dormpanel.app.dashboard.layout.LayoutMutationResult.Success)
                    val added = holder.state.cards.last()
                    assertTrue(holder.resize(added.id, CardSize(2, 1)) is com.dormpanel.app.dashboard.layout.LayoutMutationResult.Success)
                }
                val before = holder.state.cards.toList()
                assertTrue(holder.add(vm.catalog.candidates.first { it.candidateId == "clock" }) is com.dormpanel.app.dashboard.layout.LayoutMutationResult.Success)
                assertEquals(before, holder.state.cards.dropLast(1))
                assertEquals(CardSize(2, 1), holder.state.cards.last().size)
            }
            captureReview("pr12-best-fit-full-dashboard")
        }
    }

    @Test fun pr12LongSensorCompactDetailPartialStaleAndEditMode() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForCards(scenario)
            lateinit var sensor: com.dormpanel.app.dashboard.card.SensorCardView
            val name = "Dormitory south-facing balcony Xiaomi temperature and humidity sensor"
            val demo = com.dormpanel.app.data.FakeDashboardDataSource()
            var data = demo.state.copy(sensors = mapOf("room" to com.dormpanel.app.data.SensorState("room", name, 23.6, 54, temperatureUnit = "°F", humidityUnit = "% RH")))
            val source = object : com.dormpanel.app.data.DashboardDataSource by demo {
                override val state get() = data
                override fun addListener(listener: (com.dormpanel.app.data.DashboardData) -> Unit) { listener(data) }
                override fun removeListener(listener: (com.dormpanel.app.data.DashboardData) -> Unit) {}
            }
            val placed = com.dormpanel.app.dashboard.model.PlacedCard("long-sensor", "sensor", 0, 0, CardSize(2, 1))
            scenario.onActivity { activity ->
                sensor = com.dormpanel.app.dashboard.card.SensorCardView(activity, model(activity).appearance, source)
                sensor.bind(placed, com.dormpanel.app.dashboard.card.CardInteractionScope(true) {})
                val root = android.widget.FrameLayout(activity)
                root.addView(sensor, android.widget.FrameLayout.LayoutParams(280, 100))
                activity.setContentView(root)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val labels = descendants(sensor).filterIsInstance<TextView>().toList()
                val title = labels.single { it.text.toString() == name }
                assertEquals(1, title.maxLines); assertEquals(1, title.lineCount)
                listOf("23.6°F", "54% RH").forEach { expected ->
                    val value = labels.single { it.text.toString().replace(" ", "") == expected.replace(" ", "") }
                    assertEquals(1, value.lineCount)
                    assertEquals(0, value.layout.getEllipsisCount(0))
                    assertTrue(value.layout.getLineWidth(0) <= value.width + 1)
                    val rect = android.graphics.Rect()
                    assertTrue(value.getLocalVisibleRect(rect)); assertEquals(value.height, rect.height())
                }
                sensor.performClick()
            }
            onView(withText(name)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed()))
            captureReview("pr12-sensor-detail-both")
            onView(withText("Done")).perform(ViewActions.click())
            scenario.onActivity {
                data = data.copy(sensors = mapOf("room" to data.sensors.getValue("room").copy(humidity = null, availability = com.dormpanel.app.data.Availability.STALE)))
                sensor.bind(placed, com.dormpanel.app.dashboard.card.CardInteractionScope(true) {})
                sensor.performClick()
            }
            onView(withText("Stale")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed()))
            onView(withText("Humidity")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            captureReview("pr12-sensor-detail-partial-stale")
            onView(withText("Done")).perform(ViewActions.click())
            scenario.onActivity {
                data = data.copy(sensors = mapOf("room" to data.sensors.getValue("room").copy(availability = com.dormpanel.app.data.Availability.UNAVAILABLE)))
                sensor.bind(placed, com.dormpanel.app.dashboard.card.CardInteractionScope(true) {}); sensor.performClick()
            }
            onView(withText("Unavailable")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed()))
            onView(withText("Temperature")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            scenario.onActivity { sensor.bind(placed, com.dormpanel.app.dashboard.card.CardInteractionScope(false) {}); assertFalse(sensor.performClick()) }
            onView(withText("Done")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            scenario.onActivity {
                sensor.bind(placed, com.dormpanel.app.dashboard.card.CardInteractionScope(true) {}); sensor.performClick()
                (sensor.parent as ViewGroup).removeView(sensor)
            }
            onView(withText("Done")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        }
    }

    @Test fun pr12PrecisionNumericControlsValidateAndShareOffSemantics() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForCards(scenario)
            val demo = com.dormpanel.app.data.FakeDashboardDataSource()
            lateinit var dialog: com.dormpanel.app.dashboard.card.LightQuickControls
            scenario.onActivity { activity ->
                demo.setLightPower("desk", false)
                dialog = com.dormpanel.app.dashboard.card.LightQuickControls(activity, demo, model(activity).appearance,
                    "desk", com.dormpanel.app.dashboard.card.CardInteractionScope(true) {}) {}
                dialog.show()
                assertTrue(dialog.window!!.attributes.width >= 900 * activity.resources.displayMetrics.density)
            }
            onView(withText("Color temperature · 4000 K")).perform(ViewActions.click())
            onView(isAssignableFrom(android.widget.EditText::class.java)).perform(ViewActions.replaceText("2600"), ViewActions.closeSoftKeyboard())
            onView(withText(android.R.string.ok)).perform(ViewActions.click())
            onView(withText("Enter a whole number from 2700 to 6500")).check(matches(isDisplayed()))
            assertEquals(4000, demo.state.lights.getValue("desk").controlTemperature)
            onView(isAssignableFrom(android.widget.EditText::class.java)).perform(ViewActions.replaceText("5100"), ViewActions.closeSoftKeyboard())
            onView(withText(android.R.string.ok)).perform(ViewActions.click())
            assertFalse(demo.state.lights.getValue("desk").isOn)
            captureReview("pr12-numeric-after-submit")
            assertEquals(5100, demo.state.lights.getValue("desk").controlTemperature)
            onView(withText("Brightness · 65%")).perform(ViewActions.click())
            onView(isAssignableFrom(android.widget.EditText::class.java)).perform(ViewActions.replaceText("101"), ViewActions.closeSoftKeyboard())
            onView(withText(android.R.string.ok)).perform(ViewActions.click())
            onView(withText("Enter a whole number from 1 to 100")).check(matches(isDisplayed()))
            onView(isAssignableFrom(android.widget.EditText::class.java)).perform(ViewActions.replaceText("37"), ViewActions.closeSoftKeyboard())
            onView(withText(android.R.string.ok)).perform(ViewActions.click())
            assertTrue(demo.state.lights.getValue("desk").isOn)
            assertEquals(37, demo.state.lights.getValue("desk").controlBrightness)
            assertEquals(5100, demo.state.lights.getValue("desk").controlTemperature)
            captureReview("pr12-precision-dialog")
            scenario.onActivity { dialog.dismiss() }
        }
    }

    @Test fun pr12EverySupportedCoreSizeFitsPhysicalBounds() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForCards(scenario)
            scenario.onActivity { activity ->
                val vm = model(activity)
                vm.stateHolder.state.cards.toList().forEach { vm.stateHolder.delete(it.id) }
                listOf("clock", "weather", "sensor:room", "light:desk").forEach { id -> vm.stateHolder.add(vm.catalog.candidates.first { it.candidateId == id }) }
            }
            for (rows in 1..3) for (columns in 2..4) {
                scenario.onActivity { activity ->
                    val holder = model(activity).stateHolder
                    holder.state.cards.toList().forEach { assertTrue(holder.resize(it.id, CardSize(columns, rows)) is com.dormpanel.app.dashboard.layout.LayoutMutationResult.Success) }
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                onView(withId(R.id.dashboard_edit)).check(matches(isDisplayed()))
                scenario.onActivity { activity ->
                    descendants(activity.findViewById(R.id.dashboard_grid)).filterIsInstance<DashboardCardView>().forEach { card ->
                        descendants(card).filter { it.isShown && (it is TextView || it is android.widget.SeekBar) }.forEach { value ->
                            if (value is TextView && value.text.isEmpty()) return@forEach
                            val visible = android.graphics.Rect()
                            assertTrue("$columns x $rows: ${value.contentDescription} not visible", value.getLocalVisibleRect(visible))
                            assertEquals("$columns x $rows: ${(value as? TextView)?.text} clipped vertically", value.height, visible.height())
                            if (value is TextView) assertTrue("$columns x $rows: ${value.text} exceeds its measured text area",
                                value.layout.height <= value.height - value.compoundPaddingTop - value.compoundPaddingBottom + 1)
                            if (value is android.widget.SeekBar) assertTrue(value.height >= 48 * activity.resources.displayMetrics.density)
                        }
                    }
                }
                captureReview("pr12-core-${columns}x${rows}")
            }
        }
    }

    private fun captureReview(name: String) {
        if (InstrumentationRegistry.getArguments().getString("reviewScreenshots") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(750) // Let the physical device compositor present the completed layout.
        val directory = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "pr3-review").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        if (name == "pr12-clock-4x3-3") {
            instrumentation.uiAutomation.executeShellCommand("screencap -p ${directory.absolutePath}/$name-adb.png").close()
            Thread.sleep(500)
        }
    }

    @Test fun clockCrossesMinuteBoundaryAndStopsWhenDetached() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForCards(scenario)
            var clock: DashboardCardView? = null
            var before = ""
            scenario.onActivity {
                clock = descendants(it.findViewById(R.id.dashboard_grid)).filterIsInstance<DashboardCardView>().first { view -> view.contentDescription.contains("LOCAL TIME") }
                before = clock!!.contentDescription.toString()
            }
            Thread.sleep(millisUntilNextMinute(System.currentTimeMillis()) + 150)
            scenario.onActivity { assertNotEquals(before, clock!!.contentDescription.toString()) }
            navigate(swipe(.5f,.8f,.5f,.2f))
            onView(withText("Apps")).check(matches(isDisplayed()))
            // Apps is visible before the outgoing page's 160 ms transition removes it.
            val detachDeadline = System.currentTimeMillis() + 2000
            var attached = true
            while (attached && System.currentTimeMillis() < detachDeadline) {
                scenario.onActivity { attached = clock!!.isAttachedToWindow }
                if (attached) Thread.sleep(20)
            }
            assertFalse(attached)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
