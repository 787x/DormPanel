package com.dormpanel.app

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.*
import androidx.test.espresso.action.*
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.apps.*
import com.dormpanel.app.appearance.*
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.dashboard.model.CardSize
import org.hamcrest.Matchers.allOf
import org.junit.*
import org.junit.Assert.*

class AppsAndroidTest {
    @get:Rule val persistence = IsolatedDashboardRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var savedAppearance: AppearanceState
    private lateinit var savedHa: com.dormpanel.app.ha.HaConnectionSettings
    private var launched = mutableListOf<String>()
    private val settingsPackages = mutableListOf<String>()
    private val favorites = object : FavoriteStore {
        var ids = emptySet<String>()
        override fun read() = ids
        override fun write(components: Set<String>) { ids = components.toSet() }
    }
    @Before fun prepare() {
        savedAppearance = PreferencesAppearanceStore(context).read()
        savedHa = com.dormpanel.app.ha.HaSettingsStore(context).read()
        com.dormpanel.app.ha.HaSettingsStore(context).write(savedHa.copy(mode = com.dormpanel.app.ha.BackendMode.DEMO))
    }
    @After fun cleanup() {
        AppSources.overrideFactory = null; PreferencesAppearanceStore(context).write(savedAppearance)
        com.dormpanel.app.ha.HaSettingsStore(context).write(savedHa)
    }
    private fun fakeApps() {
        AppSources.overrideFactory = { AndroidInstalledApps(it,
            discovery = { (0..119).map { n -> InstalledApp("fixture/fixture.App$n", "App %03d".format(n), "fixture") } },
            launcher = { component -> launched.add(component); true }, favorites = favorites,
            settingsLauncher = { pkg -> settingsPackages.add(pkg); true }) }
    }
    private fun model(activity: MainActivity) = ViewModelProvider(activity)[DashboardViewModel::class.java]
    private fun waitReady(scenario: ActivityScenario<MainActivity>) {
        val deadline = System.currentTimeMillis() + 8000
        var ready = false
        while (!ready && System.currentTimeMillis() < deadline) {
            scenario.onActivity { ready = model(it).apps.apps.isNotEmpty() && model(it).stateHolder.state.loaded }
            if (!ready) Thread.sleep(30)
        }
        assertTrue("App discovery/dashboard load timed out", ready)
    }
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) = GeneralSwipeAction(Swipe.FAST,
        { v -> val p = IntArray(2); v.getLocationOnScreen(p); floatArrayOf(p[0]+v.width*x1, p[1]+v.height*y1) },
        { v -> val p = IntArray(2); v.getLocationOnScreen(p); floatArrayOf(p[0]+v.width*x2, p[1]+v.height*y2) }, Press.FINGER)
    private fun apps() { onView(withId(R.id.page_container)).perform(swipe(.98f,.8f,.98f,.2f)) }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun gridOwnsScrollHomeButtonReturnsAndFavoritesSurviveNewViewModel() {
        fakeApps()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitReady(scenario); apps()
            onView(withId(R.id.apps_grid)).perform(swipeUp())
            scenario.onActivity {
                val grid = it.findViewById<RecyclerView>(R.id.apps_grid)
                assertTrue((grid.layoutManager as GridLayoutManager).findFirstVisibleItemPosition() > 0)
                assertTrue(grid.childCount < 120)
            }
            onView(withId(R.id.apps_grid)).perform(swipeDown())
            onView(withId(R.id.apps_grid)).check(matches(isDisplayed()))
            scenario.onActivity { it.findViewById<RecyclerView>(R.id.apps_grid).scrollToPosition(0) }
            onView(withContentDescription("App 002")).perform(longClick())
            onView(withText(R.string.apps_settings)).check(matches(isDisplayed()))
            onView(withText(R.string.apps_pin)).perform(click())
            scenario.onActivity {
                assertEquals("fixture/fixture.App2", model(it).apps.apps.first().component)
                model(it).appearance.setTheme(ThemeMode.LIGHT)
            }
            screenshot("pr7-apps-light.png")
            scenario.onActivity {
                model(it).appearance.setTheme(ThemeMode.DARK)
                assertEquals(Color.BLACK, (it.findViewById<View>(R.id.apps_header).parent.let { p -> (p as View).background } as ColorDrawable).color)
            }
            screenshot("pr7-apps-dark.png")
            scenario.recreate(); waitReady(scenario)
            onView(withId(R.id.apps_grid)).check(matches(isDisplayed()))
            onView(withId(R.id.page_container)).perform(swipe(.5f,.04f,.5f,.8f))
            onView(withId(R.id.apps_grid)).check(matches(isDisplayed()))
            onView(withId(R.id.apps_grid)).perform(swipeUp())
            onView(withId(R.id.apps_home)).perform(click())
            onView(withId(R.id.dashboard_edit)).check(matches(isDisplayed()))
            apps(); pressBack()
            onView(withId(R.id.dashboard_edit)).check(matches(isDisplayed()))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitReady(scenario); apps()
            scenario.onActivity { assertEquals("fixture/fixture.App2", model(it).apps.apps.first().component) }
            onView(withContentDescription("App 002")).perform(longClick())
            onView(withText(R.string.apps_unpin)).perform(click())
            scenario.onActivity { assertEquals("fixture/fixture.App0", model(it).apps.apps.first().component) }
        }
    }
    @Test fun shortcutPickerEditGesturesAndPersistence() {
        fakeApps()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitReady(scenario)
            scenario.onActivity { val holder = model(it).stateHolder; holder.state.cards.toList().forEach { c -> holder.delete(c.id) } }
            onView(withId(R.id.dashboard_edit)).perform(click())
            for (name in listOf("App 000", "App 001")) {
                onView(withId(R.id.dashboard_add)).perform(click())
                onView(allOf(withText("Apps"), isAssignableFrom(Button::class.java))).perform(click())
                onView(withContentDescription("Add $name")).perform(click())
            }
            scenario.onActivity {
                assertEquals(2, model(it).stateHolder.state.cards.map { c -> c.configurationJson }.distinct().size)
            }
            onView(withId(R.id.dashboard_done)).perform(click())
            onView(allOf(isAssignableFrom(AppCardView::class.java), withContentDescription("App 000, App shortcut"))).perform(click())
            assertEquals(listOf("fixture/fixture.App0"), launched)
            launched.clear()
            onView(withId(R.id.dashboard_edit)).perform(click())
            onView(allOf(withContentDescription("Drag dashboard card"), withParent(hasDescendant(withContentDescription("App 000, App shortcut"))))).perform(swipe(.5f,.5f,.5f,2.5f))
            scenario.onActivity {
                val holder = model(it).stateHolder
                val first = holder.state.cards.first()
                assertTrue("Drag moved the shortcut", first.row > 0)
                holder.resize(first.id, CardSize(2,2))
            }
            onView(allOf(withContentDescription("Resize Apps card"), withParent(withParent(hasDescendant(withContentDescription("App 000, App shortcut")))))).perform(swipe(.5f,.5f,.5f,-1.8f))
            scenario.onActivity { assertEquals(CardSize(2,1), model(it).stateHolder.state.cards.first().size) }
            assertTrue(launched.isEmpty())
            assertTrue(settingsPackages.isEmpty())
            onView(withId(R.id.dashboard_done)).perform(click())
            scenario.onActivity {
                val holder = model(it).stateHolder
                holder.resize(holder.state.cards.first().id, CardSize(2,2))
                holder.resize(holder.state.cards.last().id, CardSize(1,1))
            }
            screenshot("pr7-shortcuts.png")
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitReady(scenario)
            scenario.onActivity { assertEquals(2, model(it).stateHolder.state.cards.count { c -> c.providerType == "app" }) }
        }
    }
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    @Test fun favoritePreferencesRoundTrip() {
        val name = "test_app_favorites"
        try {
            PreferencesFavoriteStore(context, name).write(setOf("fixture/fixture.First", "fixture/fixture.Second"))
            assertEquals(setOf("fixture/fixture.First", "fixture/fixture.Second"), PreferencesFavoriteStore(context, name).read())
            PreferencesFavoriteStore(context, name).write(emptySet())
            assertTrue(PreferencesFavoriteStore(context, name).read().isEmpty())
        } finally { context.getSharedPreferences(name, 0).edit().clear().commit() }
    }
    @Test fun coalescedRefreshUpdatesCatalogAndMissingCardsWithoutPolling() {
        val app = InstalledApp("fixture/fixture.Exact", "Exact", "fixture")
        val discovered = java.util.concurrent.atomic.AtomicReference(listOf(app))
        val count = java.util.concurrent.atomic.AtomicInteger()
        AppSources.overrideFactory = { AndroidInstalledApps(it,
            discovery = { count.incrementAndGet(); discovered.get() }, launcher = { launched.add(it); true }, favorites = favorites) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitReady(scenario)
            var original = emptyList<com.dormpanel.app.dashboard.model.PlacedCard>()
            scenario.onActivity {
                val m = model(it)
                m.stateHolder.state.cards.toList().forEach { c -> m.stateHolder.delete(c.id) }
                m.stateHolder.add(m.catalog.candidates.single { c -> c.providerType == "app" })
                original = m.stateHolder.state.cards
            }
            val before = count.get()
            discovered.set(emptyList())
            scenario.onActivity { repeat(8) { _ -> model(it).apps.refresh() } }
            Thread.sleep(600)
            scenario.onActivity {
                assertEquals(before + 1, count.get())
                assertTrue(model(it).catalog.candidates.none { c -> c.providerType == "app" })
                assertEquals(original, model(it).stateHolder.state.cards)
                assertFalse(model(it).apps.launch(app.component))
                assertFalse(model(it).apps.openAppSettings(app.component))
            }
            onView(withText(R.string.state_unavailable)).check(matches(isDisplayed()))
            discovered.set(listOf(app))
            scenario.onActivity { model(it).apps.refresh() }
            waitReady(scenario)
            onView(withText("Exact")).check(matches(isDisplayed()))
            scenario.onActivity { assertEquals(original, model(it).stateHolder.state.cards); assertTrue(model(it).apps.launch(app.component)) }
            val settled = count.get(); Thread.sleep(700); assertEquals(settled, count.get())
        }
    }
    @Test fun realDiscoveryIconsAndExactExternalLaunchReturn() {
        // Real PackageManager/actions with isolated favorites, never modify the user's favorites.
        AppSources.overrideFactory = { AndroidInstalledApps(it, favorites = favorites) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitReady(scenario); apps(); screenshot("pr7-real-apps.png")
            var chosen: InstalledApp? = null
            var retained: DashboardViewModel? = null
            scenario.onActivity {
                retained = model(it)
                chosen = model(it).apps.apps.first { a -> a.packageName == "com.android.calculator2" }
                assertFalse(model(it).apps.apps.any { a -> a.packageName == context.packageName })
            }
            onView(withContentDescription(chosen!!.label)).perform(click())
            Thread.sleep(600)
            assertTrue(shell("dumpsys activity activities").lineSequence().any { it.contains("mResumedActivity") && it.contains("com.android.calculator2/.Calculator") })
            shell("input keyevent 4"); Thread.sleep(400)
            onView(withId(R.id.apps_grid)).check(matches(isDisplayed()))
            scenario.onActivity { assertSame(retained, model(it)) }
            onView(withContentDescription(chosen!!.label)).perform(longClick())
            onView(withText(R.string.apps_pin)).perform(click())
            scenario.onActivity { assertTrue(model(it).apps.isFavorite(chosen!!.component)) }
            onView(withContentDescription(chosen!!.label)).perform(longClick())
            onView(withText(R.string.apps_unpin)).perform(click())
            scenario.onActivity { assertFalse(model(it).apps.isFavorite(chosen!!.component)) }
            onView(withContentDescription(chosen!!.label)).perform(longClick())
            onView(withText(R.string.apps_settings)).perform(click())
            verifyCalculatorSettingsAndReturn("pr7-app-settings.png")
            onView(withId(R.id.apps_grid)).check(matches(isDisplayed()))
            pressBack()
            scenario.onActivity {
                val holder = model(it).stateHolder; holder.state.cards.toList().forEach { c -> holder.delete(c.id) }
                val candidates = model(it).catalog.candidates.filter { c -> c.providerType == "app" }
                holder.add(candidates.first { c -> AppConfiguration.decode(c.configurationJson).component == chosen!!.component })
                holder.add(candidates.first { c -> AppConfiguration.decode(c.configurationJson).component != chosen!!.component })
                assertEquals(2, holder.state.cards.map { c -> c.configurationJson }.distinct().size)
                val used = holder.state.cards.map { c -> c.configurationJson }
                holder.add(candidates.first { c -> c.configurationJson !in used })
                holder.resize(holder.state.cards.first().id, CardSize(1,1))
                holder.resize(holder.state.cards.last().id, CardSize(2,2))
            }
            screenshot("pr7-real-shortcuts.png")
            val shortcut = allOf(isAssignableFrom(AppCardView::class.java), withContentDescription("${chosen!!.label}, App shortcut"))
            onView(shortcut).perform(longClick())
            onView(withText(R.string.apps_open)).check(matches(isDisplayed()))
            onView(withText(R.string.apps_settings)).perform(click())
            verifyCalculatorSettingsAndReturn("pr7-card-settings.png")
            onView(shortcut).perform(longClick())
            onView(withText(R.string.apps_open)).perform(click())
            Thread.sleep(500)
            assertTrue(shell("dumpsys activity activities").lineSequence().any { it.contains("mResumedActivity") && it.contains("com.android.calculator2/.Calculator") })
            shell("input keyevent 4")
        }
    }

    private fun verifyCalculatorSettingsAndReturn(screenshotName: String) {
        Thread.sleep(600)
        val dump = shell("dumpsys activity activities")
        assertTrue(dump.lineSequence().any { it.contains("mResumedActivity") && it.contains("com.android.settings") })
        assertTrue(dump.contains("dat=package:com.android.calculator2"))
        screenshot(screenshotName)
        shell("input keyevent 4"); Thread.sleep(400)
    }

    @Test fun bothMenusUseSharedSettingsActionAndUnavailableShortcutFailsSafely() {
        fakeApps()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitReady(scenario); apps()
            onView(withContentDescription("App 001")).perform(longClick())
            onView(withText(R.string.apps_pin)).check(matches(isDisplayed()))
            onView(withText(R.string.apps_settings)).perform(click())
            assertEquals(listOf("fixture"), settingsPackages)
            onView(withId(R.id.apps_home)).perform(click())
            scenario.onActivity {
                val m = model(it)
                m.stateHolder.state.cards.toList().forEach { c -> m.stateHolder.delete(c.id) }
                m.stateHolder.add(m.catalog.candidates.single { c -> c.candidateId == "app:fixture/fixture.App1" })
            }
            onView(isAssignableFrom(AppCardView::class.java)).perform(longClick())
            onView(withText(R.string.apps_open)).check(matches(isDisplayed()))
            onView(withText(R.string.apps_settings)).perform(click())
            assertEquals(listOf("fixture", "fixture"), settingsPackages)
            // Same package, absent component: no fallback to any of its 120 other components.
            scenario.onActivity {
                val m = model(it)
                m.stateHolder.state.cards.toList().forEach { c -> m.stateHolder.delete(c.id) }
                val candidate = m.catalog.candidates.first { c -> c.providerType == "app" }
                m.stateHolder.add(candidate.copy(configurationJson = AppConfiguration("fixture/fixture.Removed").encode()))
            }
            onView(isAssignableFrom(AppCardView::class.java)).perform(longClick())
            onView(withText(R.string.apps_settings)).perform(click())
            assertEquals(2, settingsPackages.size)
            assertTrue(launched.isEmpty())
        }
    }
}
