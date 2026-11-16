package com.dormpanel.app.ha

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.MainActivity
import com.dormpanel.app.dashboard.DashboardViewModel
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class HaAndroidTest {
    @get:org.junit.Rule val dashboardPersistence = com.dormpanel.app.IsolatedDashboardRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun addingDeviceHelpersPreservesExistingSettings() {
        val store = HaSettingsStore(context)
        val saved = store.read()
        try {
            val old = HaConnectionSettings(BackendMode.HOME_ASSISTANT, "https://example.invalid", "weather.old",
                "input_select.theme", "input_number.opacity")
            store.write(old)
            assertEquals(old, store.read())
            val withHelpers = old.copy(displayBrightnessEntity = "input_number.display", mediaVolumeEntity = "input_number.volume")
            store.write(withHelpers)
            assertEquals(withHelpers, store.read())
            val dual = withHelpers.copy(systemBrightnessEntity = "input_number.system", blackoutEntity = "input_boolean.blackout")
            store.write(dual)
            assertEquals(dual, store.read())
            val toggles = dual.copy(systemAutomaticEntity = "input_boolean.automatic", followSystemEntity = "input_boolean.follow",
                keepAwakeEntity = "input_boolean.awake", startAfterBootEntity = "input_boolean.boot")
            store.write(toggles)
            assertEquals(toggles, store.read())
        } finally { store.write(saved) }
    }
    @Test fun androidKeystoreRoundtripAndTamper() {
        val isolated = context.createDeviceProtectedStorageContext()
        val store = HaTokenStore(isolated)
        // This fixture uses separate preferences; restore them afterward.
        val prefs = isolated.getSharedPreferences("ha_credentials", Context.MODE_PRIVATE)
        val saved = prefs.getString("payload", null)
        try {
            store.write("instrumentation-only-token")
            assertEquals("instrumentation-only-token", HaTokenStore(isolated).read())
            assertFalse(prefs.getString("payload", "")!!.contains("instrumentation-only-token"))
            prefs.edit().putString("payload", "corrupt").commit()
            assertNull(store.read())
        } finally { prefs.edit().putString("payload", saved).commit() }
    }
    @Test fun settingsSurfaceAndRealWebSocketOnApi28() {
        val preferences = HaSettingsStore(context)
        val saved = preferences.read()
        val tokenPrefs = context.getSharedPreferences("ha_credentials", Context.MODE_PRIVATE)
        val savedPayload = tokenPrefs.getString("payload", null)
        val savedBinding = tokenPrefs.getString("endpoint", null)
        val appearancePrefs = com.dormpanel.app.appearance.PreferencesAppearanceStore(context)
        val savedAppearance = appearancePrefs.read()
        val server = MockWebServer()
        val other = MockWebServer()
        other.start()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"auth_required"}""") }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = JSONObject(text)
                if (message.optString("type") == "auth") { webSocket.send("""{"type":"auth_ok"}"""); return }
                val result: Any = when (message.optString("type")) {
                    "get_states" -> org.json.JSONArray("""[{"entity_id":"light.api28","state":"on","attributes":{"friendly_name":"API 28 test light","brightness":200,"supported_color_modes":["brightness"]}}]""")
                    "config/entity_registry/list_for_display" -> JSONObject("""{"entities":[]}""")
                    "config/entity_registry/list" -> org.json.JSONArray()
                    else -> JSONObject.NULL
                }
                webSocket.send(JSONObject().put("id", message.getInt("id")).put("type", "result").put("success", true).put("result", result).toString())
            }
        }))
        server.start()
        preferences.write(HaConnectionSettings())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val backend = AtomicReference<DashboardBackend>()
                scenario.onActivity { activity ->
                    val value = ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource
                    backend.set(value)
                    HaSettingsDialog(activity, value).show()
                }
                onView(withText("Home Assistant")).check(matches(isDisplayed()))
                onView(withText("Home Assistant base URL")).check(matches(isDisplayed()))
                onView(withText("Access token (blank keeps saved token)")).check(matches(isDisplayed()))
                onView(withText("Save / Reconnect")).check(matches(isDisplayed()))
                onView(withContentDescription("Advanced settings")).perform(click())
                onView(withContentDescription("Backend")).perform(click())
                onView(withText("HOME_ASSISTANT")).inRoot(isPlatformPopup()).perform(click())
                onView(withContentDescription("Home Assistant base URL")).perform(replaceText(server.url("/").toString()), closeSoftKeyboard())
                onView(withContentDescription("Access token (blank keeps saved token)")).perform(replaceText("instrumentation-token"), closeSoftKeyboard())
                onView(withText("Save / Reconnect")).perform(click())
                onView(withText("Close")).perform(click())
                var connected = false
                val deadline = System.currentTimeMillis() + 10000
                while (!connected && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { connected = backend.get().ha.connected }
                    Thread.sleep(25)
                }
                assertTrue("Mock HA did not connect", connected)
                scenario.onActivity {
                    assertEquals("API 28 test light", backend.get().state.lights.getValue("ha:light.api28").name)
                    assertFalse(backend.get().state.lights.containsKey("desk"))
                    HaSettingsDialog(it, backend.get()).show()
                }
                onView(withText("Home Assistant")).check(matches(isDisplayed()))
                val urlField = onView(withContentDescription("Home Assistant base URL"))
                urlField.perform(replaceText(other.url("/proxy").toString()), closeSoftKeyboard())
                onView(withText("Test Connection")).perform(scrollTo(), click())
                onView(withText("服务器地址已改变，请重新输入该服务器的访问令牌")).check(matches(isDisplayed()))
                onView(withText("Save / Reconnect")).perform(click())
                assertEquals(0, other.requestCount)
                scenario.onActivity {
                    val value = backend.get()
                    assertTrue(value.ha.connected)
                    assertEquals(HaEndpoint.parse(server.url("/").toString()).base, value.settings.baseUrl)
                    assertEquals("instrumentation-token", HaTokenStore(context).read())
                    try {
                        value.save(value.settings.copy(mode = BackendMode.DEMO, baseUrl = other.url("/proxy").toString()), "")
                        fail("DEMO accepted reuse at another endpoint")
                    } catch (_: HaTokenRequired) {}
                    assertEquals(BackendMode.HOME_ASSISTANT, value.settings.mode)
                }

                other.enqueue(MockResponse().setBody("{}"))
                onView(withContentDescription("Access token (blank keeps saved token)"))
                    .perform(scrollTo(), replaceText("replacement-token"), closeSoftKeyboard())
                onView(withText("Test Connection")).perform(scrollTo(), click())
                val diagnostic = other.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
                assertNotNull(diagnostic)
                assertEquals("/proxy/api/config", diagnostic!!.path)
                assertEquals("Bearer replacement-token", diagnostic.getHeader("Authorization"))
                onView(withText("Close")).perform(click())
                scenario.onActivity {
                    val value = backend.get()
                    value.save(value.settings.copy(mode = BackendMode.DEMO, baseUrl = other.url("/proxy").toString()), "replacement-token")
                    assertEquals("replacement-token", HaTokenStore(context).read())
                    assertNull(HaTokenStore(context).readFor(HaEndpoint.parse(server.url("/").toString()).base))
                    assertEquals("replacement-token", value.testToken(HaEndpoint.parse(other.url("/proxy/api/config").toString()), ""))
                }

            }
        } finally {
            other.close(); server.close(); preferences.write(saved); tokenPrefs.edit().putString("payload", savedPayload).putString("endpoint", savedBinding).commit(); appearancePrefs.write(savedAppearance)
        }
    }
}
