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
import com.dormpanel.app.dashboard.card.ClockCardView
import com.dormpanel.app.dashboard.card.ClockTiming
import com.dormpanel.app.dashboard.card.WeatherCardConfiguration
import com.dormpanel.app.dashboard.card.WeatherDetailDialog
import com.dormpanel.app.dashboard.card.coreCardRegistry
import com.dormpanel.app.dashboard.model.PlacedCard
import com.dormpanel.app.dashboard.ui.DashboardGridView
import com.dormpanel.app.weather.*
import java.time.Instant
import java.time.ZoneId
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
            onView(withText(R.string.theme_light)).perform(ViewActions.click())
            onView(withContentDescription(R.string.card_opacity)).perform(swipe(.9f, .5f, .05f, .5f))
            onView(withText(R.string.control_center_title)).check(matches(isDisplayed()))
            scenario.onActivity {
                assertEquals(ThemeMode.LIGHT, model(it).appearance.state.themeMode)
                assertTrue(model(it).appearance.state.cardSurfaceOpacity < .15f)
                assertEquals(model(it).appearance.state, PreferencesAppearanceStore(it).read())
            }
            onView(withContentDescription(R.string.control_center_home_description)).perform(ViewActions.click())
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
                // "Calendar" also matches the home-page edge hint; require the
                // Schedule tab Button when that title is checked.
                val titleMatcher = if (title == "Calendar")
                    allOf(org.hamcrest.Matchers.anyOf(withText(title), withText("日历")), isAssignableFrom(android.widget.Button::class.java))
                else withText(title)
                onView(titleMatcher).check(matches(isDisplayed()))
                when (title) {
                    "Apps" -> onView(withId(R.id.apps_home)).perform(ViewActions.click())
                    "Calendar" -> onView(org.hamcrest.Matchers.anyOf(withText("Home"), withText("返回首页"))).perform(ViewActions.click())
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
                assertFalse(views.first { it.contentDescription.contains("多云") }.contentDescription.contains("Tomorrow"))
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
                Triple("Weather", "多云", "seed-weather"),
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

    @Test fun weatherPickerAndDetailRebindOnlySelectedCardAndRestoreLayout() {
        val district = com.dormpanel.app.weather.WeatherSourceRef.QWeatherCity("101200101")
        val shanghai = com.dormpanel.app.weather.WeatherSourceRef.QWeatherCity("101020100")
        val home = com.dormpanel.app.weather.WeatherSourceRef.HomeAssistant("weather.home", "test-server")
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val prefix = "pr79-" + java.util.UUID.randomUUID()
        val city = com.dormpanel.app.weather.WeatherPlace("$prefix-wuhan", "测试武汉", zone, listOf(district, home))
        val destination = com.dormpanel.app.weather.WeatherPlace("$prefix-shanghai", "测试上海", zone, listOf(shanghai))
        val legacy = com.dormpanel.app.dashboard.card.WeatherCardConfiguration.Default
        val bound = com.dormpanel.app.dashboard.card.WeatherCardConfiguration.Bound(city.id, district, "测试武汉宿舍")
        var saved = emptyList<com.dormpanel.app.dashboard.model.PlacedCard>()

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitForCards(scenario)
                scenario.onActivity {
                    val vm = model(it)
                    val places = vm.dataSource.weatherRepository.places
                    places.save(city)
                    places.save(destination)
                    vm.stateHolder.state.cards.filter { card -> card.id != "seed-weather" }.forEach { card ->
                        vm.stateHolder.delete(card.id)
                    }
                }
                onView(card("Demo 天气")).perform(ViewActions.click())
                onView(withText(containsString("演示天气"))).check(matches(isDisplayed()))
                onView(withText("‹ 返回")).perform(ViewActions.click())
                onView(withId(R.id.dashboard_grid)).perform(GeneralClickAction(Tap.LONG, { view ->
                    val position = IntArray(2).also(view::getLocationOnScreen)
                    floatArrayOf(position[0] + view.width * .25f, position[1] + view.height * .80f)
                }, Press.FINGER, 0, 0))
                onView(withId(R.id.dashboard_add)).perform(ViewActions.click())
                onView(withContentDescription("Add Weather")).perform(ViewActions.click())
                onView(withText("取消")).perform(ViewActions.click())
                scenario.onActivity { assertEquals(1, model(it).stateHolder.state.cards.size) }
                onView(withContentDescription("Add Weather")).perform(ViewActions.click())
                onView(withText("测试武汉   ·   Asia/Shanghai")).perform(ViewActions.click())
                onView(withText(containsString("weather.home"))).check(matches(isDisplayed()))
                onView(withText(containsString("101200101"))).perform(ViewActions.click())
                onView(withContentDescription("卡片名称")).perform(ViewActions.replaceText("测试武汉宿舍"), ViewActions.closeSoftKeyboard())
                onView(withText("确认")).perform(ViewActions.click())
                var rowDelta = 0f
                var weatherDragLabel = ""
                scenario.onActivity { activity ->
                    val grid = activity.findViewById<View>(R.id.dashboard_grid)
                    val gap = 10 * activity.resources.displayMetrics.density
                    rowDelta = 3 * ((grid.height + gap) / 6)
                    weatherDragLabel = activity.getString(R.string.dashboard_drag_card)
                    val weather = descendants(grid).filterIsInstance<DashboardCardView>().first { it.contentDescription.contains("测试武汉") }
                    assertFalse(weather.isClickable)
                    assertFalse(weather.isLongClickable)
                }
                onView(allOf(withContentDescription(weatherDragLabel), withParent(hasDescendant(card("测试武汉")))))
                    .perform(GeneralSwipeAction(Swipe.SLOW, GeneralLocation.CENTER, { view ->
                        val center = GeneralLocation.CENTER.calculateCoordinates(view)
                        floatArrayOf(center[0], center[1] + rowDelta)
                    }, Press.FINGER))
                onView(withId(R.id.dashboard_done)).perform(ViewActions.click())
                scenario.onActivity {
                    saved = model(it).stateHolder.state.cards
                    assertEquals(legacy, com.dormpanel.app.dashboard.card.WeatherCardConfiguration.decode(saved.first().configurationJson))
                    assertEquals(bound.encode(), saved.last().configurationJson)
                    assertEquals(3, saved.last().row)
                }
                onView(card("测试武汉")).perform(ViewActions.click())
                fun selection(activity: MainActivity) = (activity.findViewById<android.view.ViewGroup>(R.id.page_container)
                    .getChildAt(0) as com.dormpanel.app.weather.WeatherCenterView).selection
                scenario.onActivity { assertEquals(district, selection(it).source); assertEquals(city.id, selection(it).placeId) }
                onView(withText("和风天气")).perform(ViewActions.click())
                onView(withText(containsString("weather.home"))).perform(ViewActions.click())
                scenario.recreate()
                scenario.onActivity {
                    assertEquals(home, selection(it).source)
                    assertEquals(city.id, selection(it).placeId)
                    assertEquals(saved, model(it).stateHolder.state.cards)
                }
                scenario.onActivity { model(it).dataSource.weatherRepository.places.save(city.copy(sources = listOf(district))) }
                onView(withText("原地点或来源已失效")).check(matches(isDisplayed()))
                scenario.recreate()
                onView(withText("原地点或来源已失效")).check(matches(isDisplayed()))
                scenario.onActivity {
                    assertEquals(home, selection(it).source)
                    assertEquals(city.id, selection(it).placeId)
                    assertEquals(saved, model(it).stateHolder.state.cards)
                    model(it).dataSource.weatherRepository.places.save(city)
                }
                androidx.test.espresso.Espresso.pressBack()
                onView(card("测试武汉")).perform(ViewActions.longClick())
                onView(withText("查看天气")).perform(ViewActions.click())
                scenario.onActivity { assertEquals(district, selection(it).source) }
                onView(withText("‹ 返回")).perform(ViewActions.click())
                repeat(2) {
                    onView(card("测试武汉")).perform(ViewActions.longClick())
                    onView(withText("测试武汉")).check(matches(isDisplayed()))
                    onView(withText("关闭")).perform(ViewActions.click())
                    onView(withId(R.id.dashboard_edit)).check(matches(isDisplayed()))
                }
                onView(allOf(withContentDescription("和风天气官网"), isDescendantOfA(card("测试武汉"))))
                    .perform(ViewActions.longClick())
                captureReview("pr79-detail")
                onView(withText("切换天气")).perform(ViewActions.click())
                onView(withText("测试上海   ·   Asia/Shanghai")).perform(ViewActions.click())
                onView(withText(containsString("101020100"))).perform(ViewActions.click())
                onView(withText("取消")).perform(ViewActions.click())
                scenario.onActivity { assertEquals(saved, model(it).stateHolder.state.cards) }
                onView(withText("切换天气")).perform(ViewActions.click())
                onView(withText("测试上海   ·   Asia/Shanghai")).perform(ViewActions.click())
                onView(withText(containsString("101020100"))).perform(ViewActions.click())
                onView(withContentDescription("卡片名称")).check(matches(withText("")))
                lateinit var complete: (Result<Unit>) -> Unit
                scenario.onActivity { dashboardPersistence.interceptNextSave = { complete = it } }
                onView(withText("确认")).perform(ViewActions.click())
                onView(withText("正在保存卡片设置…")).check(matches(isDisplayed()))
                scenario.onActivity { complete(Result.failure(IllegalStateException("test disk failure"))) }
                onView(withText(containsString("卡片设置未保存"))).check(matches(isDisplayed()))
                onView(withText("重试保存")).perform(ViewActions.click())
                onView(withText("测试上海")).check(matches(isDisplayed()))
                onView(withText("卡片显示设置")).perform(ViewActions.click())
                onView(withContentDescription("卡片名称")).perform(ViewActions.replaceText("测试上海阳台"), ViewActions.closeSoftKeyboard())
                onView(withText("风速")).perform(ViewActions.click())
                onView(withText("保存")).perform(ViewActions.click())
                onView(withText("卡片显示设置")).perform(ViewActions.click())
                onView(withContentDescription("卡片名称")).check(matches(withText("测试上海阳台")))
                onView(withContentDescription("卡片名称")).perform(ViewActions.replaceText(""), ViewActions.closeSoftKeyboard())
                onView(withText("取消")).perform(ViewActions.click())
                onView(withText("关闭")).perform(ViewActions.click())
                onView(card("测试上海阳台")).check(matches(isDisplayed()))
                onView(card("测试上海阳台")).perform(ViewActions.longClick())
                onView(withText("卡片显示设置")).perform(ViewActions.click())
                onView(withContentDescription("卡片名称")).perform(ViewActions.replaceText(""), ViewActions.closeSoftKeyboard())
                onView(withText("保存")).perform(ViewActions.click())
                onView(withText("关闭")).perform(ViewActions.click())
                val newJson = bound.select(destination.id, shanghai)
                    .copy(metric = com.dormpanel.app.dashboard.card.WeatherCardMetric.WIND).encode()
                saved = listOf(saved.first(), saved.last().copy(configurationJson = newJson))
                scenario.onActivity { assertEquals(saved, model(it).stateHolder.state.cards) }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitForCards(scenario)
                scenario.onActivity {
                    val vm = model(it)
                    assertEquals(saved.associateBy { it.id }, vm.stateHolder.state.cards.associateBy { it.id })
                    vm.dataSource.weatherRepository.places.save(destination.copy(sources = listOf(home)))
                }
                onView(card("地点或来源已失效")).perform(ViewActions.click())
                onView(withText("原地点或来源已失效")).check(matches(isDisplayed()))
                onView(withText("Home Assistant · weather.home")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                onView(withText("‹ 返回")).perform(ViewActions.click())
                scenario.onActivity { model(it).dataSource.weatherRepository.places.delete(destination.id) }
                onView(card("地点或来源已失效")).perform(ViewActions.click())
                onView(withText("原地点或来源已失效")).check(matches(isDisplayed()))
                onView(withText("逐小时预报")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                onView(withText("测试武汉")).perform(ViewActions.click())
                onView(withText("地点管理")).check(matches(isDisplayed()))
                scenario.onActivity { assertEquals(saved.associateBy { c -> c.id }, model(it).stateHolder.state.cards.associateBy { c -> c.id }) }
                onView(withText("‹ 返回")).perform(ViewActions.click())
                onView(card("地点或来源已失效")).check(matches(isDisplayed()))
                onView(card("地点或来源已失效")).perform(ViewActions.longClick())
                onView(withText("地点或来源已失效，请重新选择")).check(matches(isDisplayed()))
                onView(withText("关闭")).perform(ViewActions.click())
            }
        } finally {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val places = com.dormpanel.app.weather.WeatherPlaceStore(context)
            places.delete(city.id)
            places.delete(destination.id)
        }
    }

    @Test fun fourWeatherCardsRenderIndependentFakeSourcesAndReleaseOnDetach() {
        val wuhan = WeatherSourceRef.QWeatherCity("101200101")
        val beijing = WeatherSourceRef.QWeatherCity("101010100")
        val home = WeatherSourceRef.HomeAssistant("weather.home", "fake-ha")
        val zone = ZoneId.of("Asia/Shanghai")
        val values = mapOf(wuhan to 23, beijing to 8, home to 19)
        val places = WeatherPlaceStore("[]") { true }
        listOf(WeatherPlace("wuhan", "武汉", zone, listOf(wuhan)),
            WeatherPlace("beijing", "北京", zone, listOf(beijing)),
            WeatherPlace("home", "家", zone, listOf(home))).forEach(places::save)

        val started = mutableListOf<Pair<WeatherSourceRef, WeatherBlock>>()
        val stopped = mutableListOf<Pair<WeatherSourceRef, WeatherBlock>>()
        val provider = object : WeatherProvider {
            override fun plan(source: WeatherSourceRef, zone: ZoneId, query: WeatherQuery, now: Instant): WeatherPlan {
                val temp = values.getValue(source).toDouble()
                val code = when (source) { home -> "lightning-rainy"; wuhan -> "100"; else -> "500" }
                val current = WeatherConditions(code, temperature = temp, feelsLike = temp - 2)
                val forecast = List(4) { day ->
                    WeatherForecast(WeatherPeriod(date = now.atZone(zone).toLocalDate().plusDays(day.toLong())),
                        WeatherConditions(code, minTemperature = temp - 3, maxTemperature = temp + 3))
                }
                val parsed = when (query.block) {
                    WeatherBlock.DAILY -> ParsedWeather(WeatherData.Forecast(forecast))
                    WeatherBlock.TWICE_DAILY -> ParsedWeather(WeatherData.Forecast(listOf(
                        WeatherForecast(WeatherPeriod(start = now, isDaytime = false), current)
                    )))
                    WeatherBlock.AIR_CURRENT -> QWeatherParser.parse(org.json.JSONObject("""
                        {"refer":{"sources":["空气站 A","空气站 B"]},
                            "metadata":{"attributions":["https://developer.qweather.com/attribution.html"]},
                            "indexes":[{"code":"cn-mee","aqi":39,"category":"优"}]}
                    """.trimIndent()), zone, query)
                    WeatherBlock.HOURLY -> ParsedWeather(WeatherData.Forecast(List(6) { hour ->
                        WeatherForecast(WeatherPeriod(start = now.plusSeconds(3600L * hour)),
                            current.copy(precipitationProbability = 42.0))
                    }))
                    WeatherBlock.ALERTS -> QWeatherParser.parse(org.json.JSONObject("""
                        {"refer":{"sources":["预警源 A","预警源 B"]},"alerts":[{"id":"pr79-demo","messageType":{"code":"alert"},
                            "headline":"大风预警","description":"请关好门窗"}]}
                    """.trimIndent()), zone, query)
                    else -> ParsedWeather(WeatherData.Current(current), haForecastFeatures = if (source == home) 4 else null)
                }
                return WeatherPlan("$source/${query.block}", source.toString(), query, { _, _ -> parsed }, { emit ->
                    val key = source to query.block
                    started += key
                    if (query.block == WeatherBlock.AIR_CURRENT && started.count { it == key } > 1) {
                        emit(WeatherResult.Failed(WeatherFailure(WeatherState.UNAUTHORIZED, "测试 AQI 权限")))
                    } else {
                        emit(WeatherResult.Data(org.json.JSONObject(), parsed, zone))
                    }
                    val cancel: () -> Unit = { stopped += key }
                    cancel
                }, live = true)
            }
        }
        val cache = object : WeatherCache {
            override fun read(group: String, key: String, done: (WeatherCacheRecord?) -> Unit) = done(null)
            override fun write(group: String, key: String, record: WeatherCacheRecord, done: (Boolean) -> Unit) = done(true)
            override fun clear(groups: Set<String>?, done: (Boolean) -> Unit) = done(true)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForCards(scenario)
            lateinit var grid: DashboardGridView
            lateinit var repository: WeatherRepository
            scenario.onActivity { activity ->
                val vm = model(activity)
                repository = WeatherRepository(places, { provider }, cache,
                    com.dormpanel.app.ha.MainHaScheduler(), Instant::now)
                val registry = coreCardRegistry(vm.dataSource, vm.appearance, repository)
                grid = DashboardGridView(activity).apply {
                    setBackgroundColor(Color.BLACK)
                    bind(vm.stateHolder, registry, {}, {}, onOperationRejected = { fail(it) })
                }
                val cards = listOf(
                    PlacedCard("a", "weather", 0, 0, CardSize(4, 3), WeatherCardConfiguration.Bound("wuhan", wuhan).encode()),
                    PlacedCard("b", "weather", 4, 0, CardSize(2, 3), WeatherCardConfiguration.Bound("beijing", beijing).encode()),
                    PlacedCard("c", "weather", 6, 0, CardSize(2, 2), WeatherCardConfiguration.Bound("home", home).encode()),
                    PlacedCard("d", "weather", 0, 3, CardSize(2, 1), WeatherCardConfiguration.Bound("wuhan", wuhan, "武汉副卡").encode()),
                )
                activity.addContentView(grid, android.widget.FrameLayout.LayoutParams(-1, -1).apply { setMargins(48, 56, 48, 56) })
                grid.submitCards(cards)
            }
            var detail: WeatherDetailDialog? = null
            try {
                scenario.onActivity {
                    val views = descendants(grid).filterIsInstance<DashboardCardView>().associateBy { view -> view.tag }
                    assertTrue(views.getValue("a").contentDescription.contains("23°C"))
                    assertTrue(views.getValue("b").contentDescription.contains("8°C"))
                    assertTrue(views.getValue("c").contentDescription.contains("19°C"))
                    assertTrue(views.getValue("d").contentDescription.contains("23°C"))
                    assertEquals(6, started.size)
                    assertTrue((home to WeatherBlock.TWICE_DAILY) in started)
                    assertFalse((home to WeatherBlock.DAILY) in started)
                    assertEquals(started.toSet().size, started.size)
                }
                onView(allOf(withText(containsString("夜间")), isDescendantOfA(card("家")),
                    withEffectiveVisibility(Visibility.VISIBLE))).check { view, error ->
                    if (error != null) throw error
                    val bounds = android.graphics.Rect()
                    assertTrue(view.getLocalVisibleRect(bounds))
                    assertEquals(view.height, bounds.height())
                    assertEquals("夜间", (view as TextView).text.toString().substringAfterLast(' '))
                    val forecast = view.parent as ViewGroup
                    assertTrue(descendants(forecast).filterIsInstance<TextView>().any { it.text == "19°C" && it.isShown })
                    assertTrue(descendants(forecast).filterIsInstance<com.dormpanel.app.dashboard.card.WeatherIconView>()
                        .any { it.symbol == com.dormpanel.app.dashboard.card.WeatherSymbol.STORM && it.isShown })
                }
                onView(allOf(withText("和风天气"), isDescendantOfA(card("武汉副卡")))).check(matches(isDisplayed()))
                onView(allOf(withText("HA"), isDescendantOfA(card("家")))).check(matches(isDisplayed()))
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val filter = android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply {
                    addDataScheme("https")
                    addDataAuthority("www.qweather.com", null)
                }
                val monitor = instrumentation.addMonitor(filter, null, true)
                try {
                    onView(allOf(withContentDescription("和风天气官网"), isDescendantOfA(card("武汉副卡"))))
                        .perform(ViewActions.click())
                    assertEquals(1, monitor.hits)
                } finally {
                    instrumentation.removeMonitor(monitor)
                }
                captureReview("pr83-four-fake-sources")
                repeat(2) { iteration ->
                    scenario.onActivity { activity ->
                        val vm = model(activity)
                        detail = WeatherDetailDialog(activity, vm.appearance, vm.dataSource, repository,
                            WeatherCardConfiguration.Bound("wuhan", wuhan), changeBinding = {}, more = {}).also { it.show() }
                    }
                    onView(withText("23°C  晴")).check(matches(isDisplayed()))
                    onView(withText(containsString("降水 42%"))).check(matches(isDisplayed()))
                    onView(withText(containsString("大风预警"))).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
                    onView(withText("和风天气官网")).check(matches(isDisplayed()))
                    onView(withText(containsString("预警源 A\n预警源 B"))).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
                    onView(withText(containsString("空气站 A\n空气站 B"))).check { view, error ->
                        if (error != null) throw error
                        val text = (view as TextView).text as android.text.Spanned
                        assertTrue(text.getSpans(0, text.length, android.text.style.URLSpan::class.java)
                            .any { it.url == "https://developer.qweather.com/attribution.html" })
                    }
                    if (iteration == 1) onView(withText(containsString("测试 AQI 权限")))
                        .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
                    if (iteration == 0) captureReview("pr79-detail-fake")
                    onView(withText("关闭")).perform(ViewActions.click())
                    scenario.onActivity {
                        assertEquals(iteration + 1, stopped.count { key -> key == (wuhan to WeatherBlock.HOURLY) })
                        assertEquals(0, stopped.count { key -> key == (wuhan to WeatherBlock.CURRENT) })
                    }
                }
            } finally {
                scenario.onActivity {
                    detail?.dismiss()
                    (grid.parent as ViewGroup).removeView(grid)
                    repository.close()
                }
            }
            assertEquals(started.toSet(), stopped.toSet())
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

    @Test fun weatherCardFitsLargeFontAndRecoversAfterResize() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val appearance = model(activity).appearance
                for (scale in listOf(1f, 1.3f)) {
                    val context = activity.createConfigurationContext(android.content.res.Configuration(activity.resources.configuration).apply {
                        fontScale = scale
                    })
                    val demo = com.dormpanel.app.data.FakeDashboardDataSource()
                    val sample = demo.state.weather.copy(
                        name = "很长的地点名称用于验证天气卡片在最窄尺寸下仍然显示来源和状态",
                        temperature = -123, low = -123, high = 105, temperatureUnit = "°F",
                        conditions = WeatherConditions(code = "rainy", description = "持续性暴雨转雨夹雪",
                            temperature = (-123.5 - 32) / 1.8, sourceTemperatureUnit = "°F",
                            humidity = 95.0, feelsLike = -82.0, windSpeed = 123.4),
                        availability = com.dormpanel.app.data.Availability.STALE,
                        forecast = demo.state.weather.forecast.take(1).map { it.copy(low = -123, high = 105) },
                    )
                    val source = object : com.dormpanel.app.data.DashboardDataSource by demo {
                        override val state get() = demo.state.copy(weather = sample)
                        override fun addListener(listener: (com.dormpanel.app.data.DashboardData) -> Unit) = listener(state)
                        override fun removeListener(listener: (com.dormpanel.app.data.DashboardData) -> Unit) = Unit
                    }
                    val card = com.dormpanel.app.dashboard.card.WeatherCardView(context, appearance, source)
                    val host = android.widget.FrameLayout(activity)
                    activity.addContentView(host, android.widget.FrameLayout.LayoutParams(-1, -1))
                    host.addView(card)
                    try {
                        val sizes = listOf(CardSize(4, 3)) + (1..3).flatMap { rows -> (2..4).map { CardSize(it, rows) } } + CardSize(4, 3)
                        val sizesPx = sizes.map { size ->
                            card.bind(PlacedCard("fit", "weather", 0, 0, size),
                                com.dormpanel.app.dashboard.card.CardInteractionScope(false) {})
                            fun exact(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)
                            card.measure(exact(148 * size.columnSpan - 10), exact(114 * size.rowSpan - 10))
                            card.layout(0, 0, card.measuredWidth, card.measuredHeight)
                            card.applyAppearance(AppearanceState(themeMode = if (scale == 1f) ThemeMode.DARK else ThemeMode.LIGHT))
                            val shown = descendants(card).filter { it.isShown }.toList()
                            val text = shown.filterIsInstance<TextView>().filter { it.text.isNotEmpty() }
                            text.forEach { label ->
                                val rect = android.graphics.Rect(0, 0, label.width, label.height)
                                card.offsetDescendantRectToMyCoords(label, rect)
                                assertTrue("$scale $size ${label.text}: $rect", rect.left >= 0 && rect.top >= 0 &&
                                    rect.right <= card.width && rect.bottom <= card.height)
                                assertTrue("$scale $size ${label.text}: text layout ${label.layout.height}px in ${label.height}px",
                                    label.layout.height <= label.height + 1)
                                if (label.text.toString() != sample.name) {
                                    assertTrue("$scale $size ${label.text}: line width ${label.layout.getLineWidth(0)}px in ${label.width}px",
                                        label.layout.getLineWidth(0) <= label.width + 1)
                                }
                            }
                            assertTrue(text.any { it.text == "-123–105°F" })
                            assertTrue(text.any { it.text == "离线 · 已过期" })
                            val forecastIcons = shown.filterIsInstance<com.dormpanel.app.dashboard.card.WeatherIconView>()
                            assertEquals(if (size.rowSpan > 1) 2 else 1, forecastIcons.size)
                            assertTrue(forecastIcons.all { it.width >= 20 && it.height >= 20 })
                            text.first { it.text == "-123.5°F" }.textSize
                        }
                        assertEquals(sizesPx.first(), sizesPx.last(), 0.1f)
                        assertTrue(sizesPx.first() > sizesPx[1])
                    } finally {
                        (host.parent as ViewGroup).removeView(host)
                    }
                }
            }
        }
    }

    @Test fun weatherCardUpdatesDateAtMidnightWithoutAFeedChange() {
        val demo = com.dormpanel.app.data.FakeDashboardDataSource()
        val zone = ZoneId.systemDefault()
        val midnight = java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
        val now = java.util.concurrent.atomic.AtomicReference(midnight.minusMillis(350))
        val updated = java.util.concurrent.CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val card = com.dormpanel.app.dashboard.card.WeatherCardView(activity, model(activity).appearance, demo,
                    clock = { now.get().also { if (it == midnight) updated.countDown() } })
                card.bind(PlacedCard("midnight", "weather", 0, 0, CardSize(3, 3)),
                    com.dormpanel.app.dashboard.card.CardInteractionScope(false) {})
                activity.addContentView(card, android.widget.FrameLayout.LayoutParams(434, 332))
                assertTrue(card.contentDescription.contains("明天 20–26°C"))
                now.set(midnight)
            }
            assertTrue("Card did not render at local midnight", updated.await(4, java.util.concurrent.TimeUnit.SECONDS))
            onView(card("今天 20–26°C")).check(matches(isDisplayed()))
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

    private class ControlledClock : ClockTiming {
        var now = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.SEPTEMBER, 24, 12, 34, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        var scheduled: Runnable? = null
        var delay = -1L
        override fun nowMillis() = now
        override fun schedule(view: View, callback: Runnable, delayMillis: Long) {
            assertNull("Clock must have one scheduled callback", scheduled)
            scheduled = callback; delay = delayMillis
        }
        override fun cancel(view: View, callback: Runnable) {
            if (scheduled === callback) scheduled = null
        }
        fun advanceMinute() {
            now += 60_000L
            val callback = scheduled ?: error("Clock callback was not scheduled")
            scheduled = null
            callback.run()
        }
    }

    @Test fun clockCrossesMinuteBoundaryAndStopsWhenDetached() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForCards(scenario)
            val timing = ControlledClock()
            var clock: ClockCardView? = null
            var before = ""
            scenario.onActivity {
                clock = descendants(it.findViewById(R.id.dashboard_grid)).filterIsInstance<ClockCardView>().single()
                clock!!.replaceTimingForTest(timing)
                before = clock!!.contentDescription.toString()
                assertTrue(before.contains("12:34"))
                assertEquals(60_000L, timing.delay)
            }
            scenario.onActivity {
                timing.advanceMinute()
                assertSame(clock, descendants(it.findViewById(R.id.dashboard_grid)).filterIsInstance<ClockCardView>().single())
                assertNotEquals(before, clock!!.contentDescription.toString())
                assertTrue(clock!!.contentDescription.contains("12:35"))
                assertEquals(60_000L, timing.delay)
            }
            for (action in listOf(android.content.Intent.ACTION_TIME_CHANGED,
                android.content.Intent.ACTION_TIMEZONE_CHANGED, android.content.Intent.ACTION_DATE_CHANGED)) {
                scenario.onActivity {
                    timing.now += 60_000L
                    clock!!.dispatchTimeChangeForTest(action)
                    assertTrue(clock!!.contentDescription.contains(
                        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timing.now))))
                }
            }
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
            assertNull("Detached Clock must cancel its minute callback", timing.scheduled)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
