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
import com.dormpanel.app.lan.SettingsPortal
import com.dormpanel.app.schedule.WebDavBinding
import com.dormpanel.app.schedule.WebDavMode
import com.dormpanel.app.weather.*
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



    @Test fun firstHaSetupWorksWithAdvancedCollapsed() {
        val store = HaSettingsStore(context)
        val before = store.read()
        val tokenPrefs = context.getSharedPreferences("ha_credentials", Context.MODE_PRIVATE)
        val oldPayload = tokenPrefs.getString("payload", null)
        val oldBinding = tokenPrefs.getString("endpoint", null)
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
            tokenPrefs.edit().putString("payload", oldPayload).putString("endpoint", oldBinding).commit()
        }
    }


    @Test fun controlCenterExposesUnifiedEntry() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.page_container)).perform(swipeDown())
            onView(withText("扫码配置")).check(matches(isDisplayed()))
        }
    }

    @Test fun threeSectionsStageIndependentlyAndKeepExistingSecrets() {
        val haStore = HaSettingsStore(context)
        val savedHa = haStore.read()
        val tokens = HaTokenStore(context)
        val tokenPrefs = context.getSharedPreferences("ha_credentials", Context.MODE_PRIVATE)
        val savedPayload = tokenPrefs.getString("payload", null)
        val savedBinding = tokenPrefs.getString("endpoint", null)
        val dav = WebDavSettings(context)
        val weatherPrefs = context.getSharedPreferences("weather_service", Context.MODE_PRIVATE)
        require(weatherPrefs.all.isEmpty()) { "Use the isolated X08E testbed with no weather credentials" }
        haStore.write(HaConnectionSettings(baseUrl = "http://old-ha.invalid", themeEntity = "input_select.keep"))
        tokens.write("saved-ha-fixture")
        dav.saveAccount(WebDavAccount("https://dav.invalid/old/", "fixture", "saved-dav-fixture"))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var portal: SettingsPortal
                lateinit var backend: DashboardBackend
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
                    backend = model.dataSource
                    backend.weatherServices.configure("mock.qweatherapi.com", QWeatherAuth.ApiKey("saved-api-fixture"))
                    backend.weatherServices.settings.generateKey()
                    portal = SettingsPortal(activity, backend, dav, model.webDav, localAddress = { "127.0.0.1" })
                    portal.show()
                }
                val url = qrUrl()
                val html = request(url)
                for (label in listOf("DormPanel 配置门户", "Home Assistant", "WebDAV", "和风天气", "天气地点", "BEGIN PUBLIC KEY")) {
                    assertTrue("Missing portal label $label", html.contains(label))
                }
                assertFalse(html.contains("saved-ha-fixture") || html.contains("saved-dav-fixture") || html.contains("saved-api-fixture"))
                assertFalse(html.contains("PRIVATE KEY"))
                lateinit var pendingId: String
                scenario.onActivity {
                    pendingId = backend.weatherServices.settings.pendingKey!!.spkiSha256
                    assertFalse(html.contains(backend.weatherServices.settings.pendingKey!!.encode()))
                }
                val ha = "_action=submit&_revision=0&ha_url=${encoded("ws://OLD-HA.invalid:80/api/websocket")}&ha_token=&ha_mode=DEMO&ha_weather=weather.home"
                val changed = ha.replace(encoded("ws://OLD-HA.invalid:80/api/websocket"), encoded("http://new-ha.invalid:8123"))
                val rejected = request(url, changed)
                assertTrue(rejected.startsWith("HTTP/1.1 422"))
                assertTrue(rejected.contains("ha_token") && rejected.contains("服务器地址已改变"))
                assertTrue(request(url, "$ha&unexpected=x").startsWith("HTTP/1.1 422"))
                assertTrue(request(url, ha).startsWith("HTTP/1.1 200"))
                onView(withText("待保存配置")).check(matches(isDisplayed()))
                assertEquals("http://old-ha.invalid", haStore.read().baseUrl)
                onView(withText("取消")).perform(click())
                assertEquals("http://old-ha.invalid", haStore.read().baseUrl)
                assertTrue(runCatching { request(url, ha) }.isFailure)

                scenario.onActivity { portal.show() }
                assertTrue(request(qrUrl(), ha).startsWith("HTTP/1.1 200"))
                onView(withText("确认应用")).perform(click())
                onView(withText("Home Assistant · 已保存")).check(matches(isDisplayed()))
                onView(withText("关闭")).perform(click())
                assertEquals("http://old-ha.invalid", haStore.read().baseUrl)
                assertEquals("weather.home", haStore.read().weatherEntity)
                assertEquals("input_select.keep", haStore.read().themeEntity)
                assertEquals("saved-ha-fixture", tokens.read())
                assertEquals("https://dav.invalid/old/", dav.account()!!.baseUrl)

                scenario.onActivity { portal.show() }
                val webdav = "_action=submit&_revision=0&dav_url=${encoded("https://dav.invalid/new/")}&dav_username=fixture&dav_password="
                assertTrue(request(qrUrl(), webdav).startsWith("HTTP/1.1 200"))
                assertEquals("https://dav.invalid/old/", dav.account()!!.baseUrl)
                onView(withText("确认应用")).perform(click())
                onView(withText("WebDAV · 已保存")).check(matches(isDisplayed()))
                onView(withText("关闭")).perform(click())
                assertEquals("https://dav.invalid/new/", dav.account()!!.baseUrl)
                assertEquals("saved-dav-fixture", dav.account()!!.password)

                scenario.onActivity { portal.show() }
                val place = """[{"id":"","name":"扫码地点","zone":"Asia/Shanghai","lat":"39.92","lon":"116.41","locationId":"","city":""}]"""
                val auth = "_action=submit&_revision=0&qw_host=mock.qweatherapi.com&qw_auth=jwt&qw_kid=testkid&qw_iss=testissuer&qw_sub=testproject&qw_key=$pendingId&qw_api="
                assertTrue(request(qrUrl(), "$auth&places=${encoded(place)}").startsWith("HTTP/1.1 200"))
                scenario.onActivity {
                    assertTrue(backend.weatherServices.settings.config?.auth is QWeatherAuth.ApiKey)
                    assertFalse(backend.weatherRepository.places.places.any { it.name == "扫码地点" })
                }
                onView(withText("确认应用")).perform(click())
                onView(withText(org.hamcrest.Matchers.containsString("添加 扫码地点 · 已保存"))).check(matches(isDisplayed()))
                onView(withText("关闭")).perform(click())
                scenario.onActivity {
                    assertTrue(backend.weatherServices.settings.config?.auth is QWeatherAuth.Jwt)
                    assertNull(backend.weatherServices.settings.pendingKey)
                    val places = backend.weatherRepository.places
                    places.delete(places.places.single { it.name == "扫码地点" }.id)
                    backend.weatherServices.clearCredentials()
                    portal.close()
                }
                assertEquals("saved-ha-fixture", tokens.read())
                assertEquals("saved-dav-fixture", dav.account()!!.password)
            }
        } finally {
            haStore.write(savedHa)
            tokenPrefs.edit().putString("payload", savedPayload).putString("endpoint", savedBinding).commit()
        }
    }

    @Test fun firstWeatherSetupContinuesToSearchAndFavoriteInTheSameSession() {
        val namespace = "portal-" + java.util.UUID.randomUUID()
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("$namespace-$name", mode)
            override fun getNoBackupFilesDir() = java.io.File(context.cacheDir, namespace)
        }
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        val mockBase = server.url("/")
        val settings = QWeatherSettings(isolated, namespace)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var portal: SettingsPortal
                lateinit var weather: WeatherServices
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
                    val backend = model.dataSource
                    weather = WeatherServices(isolated, MainHaScheduler(), backend.ha, { "" }, { null },
                        settings = settings, apiFactory = { http, clock ->
                            QWeatherApi(http, clock) { _, endpoint -> mockBase.newBuilder().addPathSegments(endpoint.path).build() }
                        }, publicLocationUrl = mockBase.newBuilder().addPathSegment("directory").build())
                    portal = SettingsPortal(activity, backend, model.webDav.settings, model.webDav,
                        localAddress = { "127.0.0.1" }, weather = weather)
                    portal.show()
                }
                try {
                    val url = qrUrl()
                    val search = "_action=search&query=${encoded("北京")}"
                    assertTrue(request(url, search).contains("请先在和风分区保存凭据"))
                    assertEquals(0, server.requestCount)
                    val generation = "_action=generate_key&_revision=0"
                    assertTrue(request(url, generation).contains("pending"))
                    assertNull(settings.pendingKey)
                    onView(withText("生成和风待注册密钥？")).check(matches(isDisplayed()))
                    assertTrue(request(url, generation).startsWith("HTTP/1.1 422"))
                    onView(withText("确认生成")).perform(click())
                    val pending = settings.pendingKey!!
                    val resumed = request(url, "_action=status")
                    assertTrue(resumed.contains(pending.qWeatherPemSha256) && resumed.contains("BEGIN PUBLIC KEY"))
                    assertFalse(resumed.contains(pending.encode()))
                    assertTrue(request(url, generation).startsWith("HTTP/1.1 422"))
                    assertTrue(request(url, "_action=generate_key&_revision=1").startsWith("HTTP/1.1 422"))
                    assertEquals(pending.spkiSha256, QWeatherSettings(isolated, namespace).pendingKey!!.spkiSha256)

                    val configure = "_action=save_weather&_revision=1&qw_host=fixture.qweatherapi.com&qw_auth=jwt&qw_kid=testKid&qw_iss=testIssuer&qw_sub=testProject&qw_key=${pending.spkiSha256}&qw_api="
                    assertTrue(request(url, configure).contains("pending"))
                    assertNull(settings.config)
                    onView(withText("保存和风天气凭据？")).check(matches(isDisplayed()))
                    assertTrue(request(url, "_action=submit&_revision=1&mode=SAVER&budget=100").startsWith("HTTP/1.1 422"))
                    server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"temperature":{"value":22.5,"unit":"°C"}}"""))
                    onView(withText("保存并测试")).perform(click())
                    var status = ""
                    val deadline = android.os.SystemClock.uptimeMillis() + 6000
                    do {
                        status = request(url, "_action=status")
                        if (status.contains("认证成功")) break
                        Thread.sleep(80)
                    } while (android.os.SystemClock.uptimeMillis() < deadline)
                    assertTrue(status.contains("认证成功") && status.contains("已可用"))
                    assertEquals(pending.spkiSha256, settings.activeKey!!.spkiSha256)
                    assertNull(settings.pendingKey)
                    val testRequest = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
                    assertTrue(testRequest.getHeader("Authorization")!!.startsWith("Bearer "))
                    assertFalse(status.contains(testRequest.getHeader("Authorization")!!))

                    server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"code":"200","location":[{"name":"北京","adm1":"北京","id":"101010100","lat":"39.92","lon":"116.41","tz":"Asia/Shanghai"}]}"""))
                    assertTrue(request(url, search).contains("101010100"))
                    server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503))
                    assertTrue(request(url, search).contains("天气服务请求失败"))
                    server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401))
                    assertTrue(request(url, search).contains("认证失败"))
                    assertTrue(request(url, search).contains("认证已拒绝"))
                    scenario.onActivity { assertEquals(4, weather.budget.stats().requests) }

                    assertTrue(request(url, "_action=generate_key&_revision=2").contains("pending"))
                    onView(withText("取消")).perform(click())
                    assertTrue(request(url, "_action=status").contains("已在 X08E 取消"))
                    assertEquals(pending.spkiSha256, settings.activeKey!!.spkiSha256)
                    assertNull(settings.pendingKey)
                    assertTrue(request(url, "_action=generate_key&_revision=2").startsWith("HTTP/1.1 422"))

                    server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("China-City-List fixture\nLocation_ID,Name\n101010100,Beijing\n"))
                    val place = """[{"id":"","name":"首次设置收藏","zone":"Asia/Shanghai","lat":"","lon":"","locationId":"","city":"101010100"}]"""
                    assertTrue(request(url, "_action=submit&_revision=3&places=${encoded(place)}").startsWith("HTTP/1.1 200"))
                    onView(withText("确认应用")).perform(click())
                    onView(withText("收藏 首次设置收藏 · 已保存")).check(matches(isDisplayed()))
                    scenario.onActivity {
                        assertEquals(listOf(WeatherSourceRef.QWeatherCity("101010100")), weather.places.places.single().sources)
                    }
                    assertTrue(runCatching { request(url, "_action=status") }.isFailure)
                } finally {
                    scenario.onActivity { portal.close(); weather.close() }
                }
            }
        } finally {
            settings.clear()
            listOf("weather_service", "weather_budget", "weather_places", "weather_refresh")
                .forEach { context.deleteSharedPreferences("$namespace-$it") }
            server.close()
        }
    }

    @Test fun webDavOriginChangeStillRequiresBindingConfirmation() {
        val dav = WebDavSettings(context)
        dav.saveAccount(WebDavAccount("https://old.invalid", "fixture", "old-fixture"))
        val binding = WebDavBinding("fixture", WebDavMode.FILE, "test.ics")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var portal: SettingsPortal
            fun open() = scenario.onActivity {
                dav.saveBindings(listOf(binding))
                val model = ViewModelProvider(it)[DashboardViewModel::class.java]
                portal = SettingsPortal(it, model.dataSource, dav, model.webDav, localAddress = { "127.0.0.1" })
                portal.show()
            }
            val payload = "_action=submit&_revision=0&dav_url=${encoded("https://new.invalid")}&dav_username=fixture&dav_password=new-fixture"
            open()
            assertTrue(request(qrUrl(), payload).startsWith("HTTP/1.1 200"))
            onView(withText("确认应用")).perform(click())
            onView(withText("WebDAV 服务器已改变")).check(matches(isDisplayed()))
            assertEquals("https://old.invalid", dav.account()?.baseUrl)
            assertEquals(listOf(binding), dav.bindings())
            onView(withText("保留当前账号")).perform(click())
            onView(withText("WebDAV · 未保存 · 已取消账号更换")).check(matches(isDisplayed()))
            onView(withText("关闭")).perform(click())
            assertEquals(listOf(binding), dav.bindings())
            open()
            assertTrue(request(qrUrl(), payload).startsWith("HTTP/1.1 200"))
            onView(withText("确认应用")).perform(click())
            onView(withText("移除导入绑定并保存")).perform(click())
            onView(withText("WebDAV · 已保存")).check(matches(isDisplayed()))
            onView(withText("关闭")).perform(click())
            assertEquals("https://new.invalid", dav.account()?.baseUrl)
            assertTrue(dav.bindings().isEmpty())
            scenario.onActivity { portal.show() }
            val url = qrUrl()
            onView(withText("关闭扫码会话")).perform(click())
            assertTrue(runCatching { request(url, payload) }.isFailure)
            scenario.onActivity { portal.show() }
            val cancelled = qrUrl()
            assertTrue(request(cancelled, "_action=cancel").contains("已关闭扫码会话"))
            assertTrue(runCatching { request(cancelled, payload) }.isFailure)
        }
    }
}
