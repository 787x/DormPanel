package com.dormpanel.app

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.ha.*
import com.dormpanel.app.schedule.WebDavAccount
import com.dormpanel.app.schedule.WebDavSettings
import org.hamcrest.Matcher
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference

class SettingsPhoneEntryAndroidTest {
    @get:Rule val isolation = IsolatedDashboardRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun find(view: View, description: String): View? {
        if (view.contentDescription == description) return view
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
            find(view.getChildAt(index), description)?.let { return it }
        }
        return null
    }

    private fun qrUrl(): String {
        val result = AtomicReference<String>()
        onView(withText(startsWith("http://"))).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(TextView::class.java)
            override fun getDescription() = "Read displayed capability URL"
            override fun perform(uiController: UiController, view: View) { result.set((view as TextView).text.toString()) }
        })
        return result.get()
    }

    private fun request(url: String, body: String? = null): String {
        val uri = URI(url)
        val path = uri.rawPath
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 5000
            val bytes = body?.toByteArray(Charsets.UTF_8)
            val header = if (bytes == null) "GET $path HTTP/1.1\r\nHost: ${uri.host}\r\nConnection: close\r\n\r\n" else
                "POST $path HTTP/1.1\r\nHost: ${uri.host}\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(header.toByteArray(Charsets.US_ASCII))
            if (bytes != null) socket.getOutputStream().write(bytes)
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun awaitHaField(scenario: ActivityScenario<MainActivity>, dialog: AtomicReference<AlertDialog>, value: String) {
        val deadline = System.currentTimeMillis() + 12000
        var ready = false
        while (!ready && System.currentTimeMillis() < deadline) {
            scenario.onActivity {
                val root = dialog.get().window!!.decorView
                ready = root.hasWindowFocus() &&
                    (find(root, "Home Assistant base URL") as? android.widget.EditText)?.text?.toString() == value
            }
            if (!ready) Thread.sleep(80)
        }
        assertTrue("HA settings did not regain focus with submitted fields", ready)
    }

    @Test fun advancedDefaultsCollapsedAndCollapsedSaveKeepsHelpers() {
        val store = HaSettingsStore(context)
        val before = store.read()
        val initial = HaConnectionSettings().copy(baseUrl = "https://ha.invalid", weatherEntity = "weather.old",
            themeEntity = "input_select.theme", opacityEntity = "input_number.opacity")
        store.write(initial)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val dialog = AtomicReference<AlertDialog>()
                scenario.onActivity { activity ->
                    dialog.set(HaSettingsDialog(activity, ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource).show())
                }
                onView(withText("Home Assistant base URL")).check(matches(isDisplayed()))
                onView(withText("Access token (blank keeps saved token)")).check(matches(isDisplayed()))
                onView(withText("Preferred weather (blank = first discovered)")).check(matches(isDisplayed()))
                scenario.onActivity {
                    val root = dialog.get().window!!.decorView
                    val theme = find(root, "Theme helper (optional input_select: light / dark)") as Spinner
                    val weather = find(root, "Preferred weather (blank = first discovered)") as Spinner
                    assertFalse(theme.isShown)
                    assertTrue(weather.isShown)
                    assertEquals("input_select.theme", theme.selectedItem.toString())
                    assertEquals(initial, HaSettingsStore(it).read())
                    (find(root, "Advanced settings") as Button).performClick()
                    assertTrue(theme.isShown)
                    (find(root, "Advanced settings") as Button).performClick()
                    assertFalse(theme.isShown)
                    assertEquals("input_select.theme", theme.selectedItem.toString())
                    weather.setSelection(0)
                    dialog.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertEquals("input_select.theme", HaSettingsStore(it).read().themeEntity)
                    assertEquals("input_number.opacity", HaSettingsStore(it).read().opacityEntity)
                    assertEquals("", HaSettingsStore(it).read().weatherEntity)
                    dialog.get().dismiss()
                }
            }
        } finally { store.write(before) }
    }

    @Test fun haPhoneSubmissionOnlySavesOnAndroidAction() {
        val store = HaSettingsStore(context)
        val before = store.read()
        val tokenPrefs = context.getSharedPreferences("ha_credentials", Context.MODE_PRIVATE)
        val oldPayload = tokenPrefs.getString("payload", null)
        store.write(HaConnectionSettings().copy(baseUrl = "https://old.invalid", themeEntity = "input_select.keep"))
        HaTokenStore(context).write("saved-ha-secret")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val dialog = AtomicReference<AlertDialog>()
                scenario.onActivity { activity ->
                    dialog.set(HaSettingsDialog(activity, ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource).show())
                }
                onView(withText("Fill from phone")).perform(click())
                val url = qrUrl()
                assertFalse(request(url).contains("saved-ha-secret"))
                assertTrue(request(url, "url=${encoded("https://new.invalid")}&token=${encoded("submitted-ha-secret")}").startsWith("HTTP/1.1 200"))
                awaitHaField(scenario, dialog, "https://new.invalid")
                onView(withContentDescription("Home Assistant base URL")).check(matches(withText("https://new.invalid")))
                onView(withContentDescription("Access token (blank keeps saved token)")).check(matches(withText("submitted-ha-secret")))
                assertEquals("https://old.invalid", store.read().baseUrl)
                assertEquals("saved-ha-secret", HaTokenStore(context).read())
                scenario.onActivity { dialog.get().dismiss() }
                assertEquals("saved-ha-secret", HaTokenStore(context).read())
                scenario.onActivity { activity ->
                    dialog.set(HaSettingsDialog(activity, ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource).show())
                }
                onView(withText("Fill from phone")).perform(click())
                assertTrue(request(qrUrl(), "url=${encoded("https://new.invalid")}&token=${encoded("submitted-ha-secret")}").startsWith("HTTP/1.1 200"))
                awaitHaField(scenario, dialog, "https://new.invalid")
                onView(withContentDescription("Home Assistant base URL")).check(matches(withText("https://new.invalid")))
                scenario.onActivity {
                    dialog.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertEquals("https://new.invalid", store.read().baseUrl)
                    assertEquals("input_select.keep", store.read().themeEntity)
                    dialog.get().dismiss()
                }
                assertEquals("submitted-ha-secret", HaTokenStore(context).read())
            }
        } finally {
            store.write(before)
            tokenPrefs.edit().putString("payload", oldPayload).commit()
        }
    }

    @Test fun haPhoneAcceptsExistingEndpointFormsWithoutSaving() {
        val store = HaSettingsStore(context)
        val before = store.read()
        val tokenPrefs = context.getSharedPreferences("ha_credentials", Context.MODE_PRIVATE)
        val oldPayload = tokenPrefs.getString("payload", null)
        store.write(HaConnectionSettings().copy(baseUrl = "https://old.invalid"))
        HaTokenStore(context).write("saved-ha-secret")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val dialog = AtomicReference<AlertDialog>()
                for (submittedUrl in listOf("homeassistant.local:8123",
                    "ws://homeassistant.local:8123/api/websocket", "wss://example.invalid/api/websocket")) {
                    scenario.onActivity { activity ->
                        dialog.set(HaSettingsDialog(activity,
                            ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource).show())
                    }
                    onView(withText("Fill from phone")).perform(click())
                    assertTrue(request(qrUrl(), "url=${encoded(submittedUrl)}&token=disposable-ha-token")
                        .startsWith("HTTP/1.1 200"))
                    awaitHaField(scenario, dialog, submittedUrl)
                    onView(withContentDescription("Home Assistant base URL")).check(matches(withText(submittedUrl)))
                    onView(withContentDescription("Access token (blank keeps saved token)"))
                        .check(matches(withText("disposable-ha-token")))
                    assertEquals("https://old.invalid", store.read().baseUrl)
                    assertEquals("saved-ha-secret", HaTokenStore(context).read())
                    scenario.onActivity { dialog.get().dismiss() }
                }
            }
        } finally {
            store.write(before)
            tokenPrefs.edit().putString("payload", oldPayload).commit()
        }
    }

    @Test fun firstHaSetupWorksWithAdvancedCollapsed() {
        val store = HaSettingsStore(context)
        val before = store.read()
        val tokenPrefs = context.getSharedPreferences("ha_credentials", Context.MODE_PRIVATE)
        val oldPayload = tokenPrefs.getString("payload", null)
        store.write(HaConnectionSettings())
        tokenPrefs.edit().remove("payload").commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val dialog = AtomicReference<AlertDialog>()
                scenario.onActivity { activity ->
                    dialog.set(HaSettingsDialog(activity, ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource).show())
                }
                onView(withContentDescription("Home Assistant base URL")).perform(replaceText("https://first.invalid"))
                onView(withContentDescription("Access token (blank keeps saved token)")).perform(replaceText("first-test-token"))
                scenario.onActivity {
                    assertFalse((find(dialog.get().window!!.decorView, "Backend") as Spinner).isShown)
                    dialog.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertEquals(BackendMode.HOME_ASSISTANT, store.read().mode)
                    dialog.get().dismiss()
                }
            }
        } finally {
            store.write(before)
            tokenPrefs.edit().putString("payload", oldPayload).commit()
        }
    }

    @Test fun webDavPhoneSubmissionOnlySavesOnAndroidAction() {
        val settings = WebDavSettings(context)
        settings.saveAccount(WebDavAccount("https://old.invalid", "old-user", "saved-dav-secret"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.page_container)).perform(swipeDown())
            onView(withText("WebDAV settings")).perform(click())
            onView(withText("Fill from phone")).perform(click())
            val url = qrUrl()
            val html = request(url)
            assertFalse(html.contains("saved-dav-secret"))
            assertTrue(request(url, "url=${encoded("https://new.invalid")}&username=new-user&password=submitted-dav-secret")
                .startsWith("HTTP/1.1 200"))
            onView(withContentDescription("WebDAV server URL")).check(matches(withText("https://new.invalid")))
            onView(withContentDescription("Username")).check(matches(withText("new-user")))
            onView(withContentDescription("Password (blank keeps saved password)")).check(matches(withText("submitted-dav-secret")))
            assertEquals("saved-dav-secret", settings.account()!!.password)
            onView(withText("Save")).perform(click())
            assertEquals("https://new.invalid", settings.account()!!.baseUrl)
            assertEquals("new-user", settings.account()!!.username)
            assertEquals("submitted-dav-secret", settings.account()!!.password)
        }
    }
}
