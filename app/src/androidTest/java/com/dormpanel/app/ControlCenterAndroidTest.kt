package com.dormpanel.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import androidx.appcompat.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotEnabled
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.PreferencesAppearanceStore
import com.dormpanel.app.appearance.ThemeMode
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.device.DeviceControlController
import com.dormpanel.app.device.DeviceControlStore
import com.dormpanel.app.device.MediaVolumePort
import com.dormpanel.app.device.SystemBrightnessPort
import com.dormpanel.app.device.SystemBrightnessState
import com.dormpanel.app.ha.BackendMode
import com.dormpanel.app.ha.HaConnectionSettings
import com.dormpanel.app.ha.HaSettingsStore
import com.dormpanel.app.ui.ControlCenterView
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ControlCenterAndroidTest {
    @get:Rule val dashboard = IsolatedDashboardRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun settingsAndSystemActionsStayReachableFromTheCompactPage() {
        val ha = HaSettingsStore(context)
        val savedHa = ha.read()
        val boot = context.getSharedPreferences("startup_policy", Context.MODE_PRIVATE)
        val savedBoot = boot.all["start_after_boot"] as? Boolean
        ha.write(HaConnectionSettings())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                openControlCenter()
                onView(withText("${context.getString(R.string.control_center_not_configured)} · Demo backend")).check(matches(isDisplayed()))
                onView(withText(R.string.control_center_not_configured)).check(matches(isDisplayed()))
                onView(withText(R.string.control_center_mute_unavailable)).check(matches(isDisplayed()))
                onView(withText(R.string.control_center_mute)).check(matches(not(isDisplayed())))
                onView(withContentDescription(R.string.control_center_grant_description)).check(matches(isDisplayed()))
                onView(withContentDescription(R.string.control_center_system_brightness)).check(matches(isNotEnabled()))
                checkInlineAction(withText("Home Assistant"), withContentDescription(R.string.ha_settings))
                checkHeadingAction("WebDAV", "WebDAV 连接设置")
                checkInlineAction(withText(R.string.control_center_awake), withText(R.string.control_center_blackout))

                onView(withContentDescription(R.string.ha_settings)).perform(click())
                onView(withText("Home Assistant base URL")).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText("Close")).inRoot(isDialog()).perform(click())
                onView(withContentDescription("WebDAV 连接设置")).perform(click())
                onView(withText("全局 WebDAV 导入账号")).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText("关闭")).inRoot(isDialog()).perform(click())
                onView(withContentDescription("天气服务与地点")).perform(click())
                onView(withText("搜索城市或区县")).inRoot(isDialog()).check(matches(isDisplayed()))
                androidx.test.espresso.Espresso.pressBack()
                onView(withText("扫码配置")).perform(click())
                onView(withContentDescription("配置门户二维码")).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText("关闭扫码会话")).inRoot(isDialog()).perform(click())

                val bootSwitch = onView(withId(R.id.start_after_boot))
                bootSwitch.perform(scrollTo(), click())
                scenario.onActivity {
                    val enabled = ViewModelProvider(it)[DashboardViewModel::class.java].startupPolicy.startAfterBoot
                    assertEquals(!(savedBoot ?: false), enabled)
                    assertEquals(enabled, it.findViewById<CompoundButton>(R.id.start_after_boot).isChecked)
                }
                onView(withText(R.string.control_center_blackout)).perform(scrollTo(), click())
                onView(withId(R.id.blackout_surface)).check(matches(isDisplayed())).perform(click())
                onView(withContentDescription(R.string.control_center_home_description)).perform(click())
                onView(withText(R.string.control_center_title)).check(doesNotExist())
            }
        } finally {
            ha.write(savedHa)
            val editor = boot.edit()
            if (savedBoot == null) editor.remove("start_after_boot") else editor.putBoolean("start_after_boot", savedBoot)
            editor.commit()
        }
    }

    @Test fun scrollAndReturnUseSeparateTouchRegionsAndThemeKeepsScrollPosition() {
        val store = PreferencesAppearanceStore(context)
        val previous = store.read()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                openControlCenter()
                // Constrain the actual viewport to exercise overflow at the device's density.
                scenario.onActivity {
                    val scroll = it.findViewById<ScrollView>(R.id.control_center_scroll)
                    scroll.layoutParams = (scroll.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                        height = (200 * scroll.resources.displayMetrics.density).toInt()
                        weight = 0f
                    }
                }
                val scroll = onView(withId(R.id.control_center_scroll))
                // Start in the column gutter, away from sliders.
                val drag = GeneralSwipeAction(Swipe.FAST,
                    { view -> point(view, .50f, .88f) }, { view -> point(view, .50f, .15f) }, Press.FINGER)
                repeat(3) { scroll.perform(drag) }
                onView(withText(R.string.control_center_title)).check(matches(isDisplayed()))
                onView(withId(R.id.open_miui_home)).check(matches(isDisplayed()))
                scenario.onActivity {
                    val viewport = it.findViewById<ScrollView>(R.id.control_center_scroll)
                    assertTrue(viewport.scrollY > 0)
                    val savedOffset = viewport.scrollY
                    val appearance = ViewModelProvider(it)[DashboardViewModel::class.java].appearance
                    onThemeSwitch(appearance)
                    assertSame(viewport, it.findViewById<ScrollView>(R.id.control_center_scroll))
                    assertEquals(savedOffset, viewport.scrollY)
                }
                onView(withText(R.string.return_home_up_here)).perform(swipeUpFrom(.50f, .50f))
                onView(withText(R.string.control_center_title)).check(doesNotExist())

                for ((x, y) in listOf(.25f to .88f, .75f to .47f)) {
                    openControlCenter()
                    scenario.recreate()
                    scenario.onActivity {
                        val viewport = it.findViewById<ScrollView>(R.id.control_center_scroll)
                        assertFalse(viewport.canScrollVertically(1))
                    }
                    onView(withText(R.string.return_home_up)).check(matches(isDisplayed()))
                    scroll.perform(swipeUpFrom(x, y))
                    onView(withText(R.string.control_center_title)).check(doesNotExist())
                }
            }
        } finally {
            store.write(previous)
        }
    }

    @Test fun haErrorIsObservedAndLongStatusWrapsBelowTheActions() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"auth_invalid"}""")
                }
            }))
            server.start()
            val endpoint = server.url("/").toString()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                openControlCenter()
                scenario.onActivity {
                    val backend = ViewModelProvider(it)[DashboardViewModel::class.java].dataSource
                    backend.ha.configure(HaConnectionSettings(BackendMode.HOME_ASSISTANT, endpoint), "test")
                }
                val text = "${context.getString(R.string.control_center_auth_error)} · Authentication rejected; re-enter credentials."
                val statusText = onView(withText(text))
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                var ready = false
                while (!ready && android.os.SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity {
                        ready = ViewModelProvider(it)[DashboardViewModel::class.java].dataSource.ha.status.state ==
                            com.dormpanel.app.ha.HaConnectionState.AUTH_ERROR
                    }
                    if (!ready) android.os.SystemClock.sleep(50)
                }
                assertTrue("Expected the WebSocket auth error", ready)
                val longText = "$text ".repeat(12).trimEnd()
                statusText.check { view, error ->
                    if (error != null) throw error
                    (view as TextView).text = longText
                }
                checkInlineAction(withText("Home Assistant"), withContentDescription(R.string.ha_settings))
                val actionBounds = Rect()
                onView(withContentDescription(R.string.ha_settings)).check { view, _ ->
                    assertTrue(view.getGlobalVisibleRect(actionBounds))
                }
                onView(withText(longText)).check { view, _ ->
                    val status = view as TextView
                    val bounds = Rect()
                    assertTrue(status.getGlobalVisibleRect(bounds))
                    assertTrue(bounds.top >= actionBounds.bottom)
                    assertTrue(status.lineCount > 1)
                    assertEquals(longText.length, status.layout.getLineEnd(status.lineCount - 1))
                }
            }
        }
    }

    @Test fun increasedFontKeepsControlsReadableAndTouchable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
                val scaled = ContextThemeWrapper(activity, R.style.Theme_DormPanel).apply {
                    applyOverrideConfiguration(Configuration().apply { fontScale = 1.5f })
                }
                val panel = ControlCenterView(scaled, model.appearance, model.deviceControls, {},
                    backend = model.dataSource, startupPolicy = model.startupPolicy,
                    webDavSettings = model.webDav.settings, webDavSync = model.webDav, onReturnHome = {})
                model.dataSource.ha.configure(HaConnectionSettings(), null)
                activity.findViewById<ViewGroup>(R.id.page_container).apply {
                    removeAllViews()
                    addView(panel)
                }
                for (mode in ThemeMode.entries) panel.applyAppearance(model.appearance.state.copy(themeMode = mode))
                assertEquals(1.5f, panel.resources.configuration.fontScale, 0f)
            }
            checkInlineAction(withText("Home Assistant"), withContentDescription(R.string.ha_settings))
            checkHeadingAction("WebDAV", "WebDAV 连接设置")
            onView(withText("${context.getString(R.string.control_center_not_configured)} · Demo backend")).check(matches(isDisplayed()))
            onView(withText(R.string.control_center_follow)).check(matches(isDisplayed()))
            onView(withId(R.id.open_miui_home)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withContentDescription("天气服务与地点")).perform(scrollTo(), click())
            onView(withText("搜索城市或区县")).inRoot(isDialog()).check(matches(isDisplayed()))
        }
    }

    @Test fun compatibleAudioAndBrightnessRenderObservedState() {
        var muted = false
        var volume = 6
        var system = SystemBrightnessState(canWrite = true, automatic = true, raw = 120)
        val device = DeviceControlController(object : DeviceControlStore {
            override fun brightness() = 45
            override fun keepAwake() = true
            override fun lastAudibleVolume() = 6
            override fun saveBrightness(value: Int) = Unit
            override fun saveKeepAwake(value: Boolean) = Unit
            override fun saveLastAudibleVolume(value: Int) = Unit
        }, object : MediaVolumePort {
            override val max = 10
            override val muteAvailable = true
            override fun current() = volume
            override fun set(value: Int) { volume = value }
            override fun muted() = muted
            override fun mute(value: Boolean) { muted = value }
        }, object : SystemBrightnessPort {
            override fun read() = system
            override fun setBrightness(raw: Int): Boolean { system = system.copy(raw = raw); return true }
            override fun setAutomatic(enabled: Boolean): Boolean {
                system = system.copy(automatic = enabled)
                return true
            }
        })

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
                val panel = ControlCenterView(activity, model.appearance, device, {},
                    startupPolicy = model.startupPolicy, onReturnHome = {})
                activity.findViewById<ViewGroup>(R.id.page_container).apply {
                    removeAllViews()
                    addView(panel)
                }
                panel.applyAppearance(model.appearance.state)
            }
            onView(withText(R.string.control_center_mute)).perform(click())
            assertTrue(muted)
            onView(withText(R.string.control_center_unmute)).perform(click())
            assertFalse(muted)
            scenario.onActivity {
                volume = 8
                device.refreshAudio()
            }
            onView(withText(context.getString(R.string.control_center_volume_value, 80))).check(matches(isDisplayed()))
            onView(withContentDescription(R.string.control_center_volume)).check { view, _ -> assertEquals(0, (view as SeekBar).min) }
            onView(withContentDescription(R.string.control_center_system_brightness)).check(matches(isNotEnabled()))
            onView(withContentDescription(R.string.control_center_auto_description)).perform(click())
            onView(withContentDescription(R.string.control_center_system_brightness)).check(matches(isEnabled()))
            onView(withText(R.string.control_center_follow)).perform(click())
            onView(withContentDescription(R.string.control_center_brightness)).check(matches(isNotEnabled()))
        }
    }

    private fun onThemeSwitch(appearance: AppearanceController) {
        appearance.setTheme(if (appearance.state.themeMode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK)
    }

    private fun openControlCenter() = onView(withId(R.id.page_container)).perform(swipeDown())

    private fun checkHeadingAction(title: String, description: String) =
        checkInlineAction(withText(title), withContentDescription(description))

    private fun checkInlineAction(leading: org.hamcrest.Matcher<View>, trailing: org.hamcrest.Matcher<View>) {
        val heading = Rect()
        onView(leading).check { view, _ -> assertTrue(view.getGlobalVisibleRect(heading)) }
        onView(trailing).check { view, _ ->
            val action = Rect()
            assertTrue(view.getGlobalVisibleRect(action))
            val density = view.resources.displayMetrics.density
            assertTrue(action.height() >= (56 * density).toInt())
            assertTrue(action.width() >= (144 * density).toInt())
            assertTrue(heading.right <= action.left)
            assertTrue(heading.centerY() in action.top until action.bottom)
        }
    }

    private fun swipeUpFrom(fractionX: Float, fractionY: Float) = GeneralSwipeAction(Swipe.FAST,
        { view -> point(view, fractionX, fractionY) },
        { view -> point(view, fractionX, fractionY).apply { this[1] -= 160 * view.resources.displayMetrics.density } },
        Press.FINGER)

    private fun point(view: View, fractionX: Float, fractionY: Float): FloatArray {
        val position = IntArray(2)
        view.getLocationOnScreen(position)
        return floatArrayOf(position[0] + view.width * fractionX, position[1] + view.height * fractionY)
    }
}
