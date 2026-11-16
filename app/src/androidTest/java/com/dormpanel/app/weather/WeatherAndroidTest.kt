package com.dormpanel.app.weather

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.lifecycle.ViewModelProvider
import com.dormpanel.app.dashboard.DashboardViewModel
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.IsolatedDashboardRule
import com.dormpanel.app.MainActivity
import com.dormpanel.app.R
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

class WeatherAndroidTest {
    @get:Rule val dashboard = IsolatedDashboardRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun weatherEntryIsReachableFromControlCenter() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.page_container)).perform(swipeDown())
            onView(withText("天气服务与地点")).perform(click())
            onView(withText("搜索城市或区县")).check(matches(isDisplayed()))
            onView(withText("和风服务配置")).perform(click())
            onView(withText("测试已保存凭据")).check(matches(isDisplayed()))
        }
    }

    @Test fun weatherFormsOpenTheSystemImeAndKeepActionsReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun imeShown(): Boolean = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("dumpsys input_method")
        ).bufferedReader().use { it.readText().contains("mInputShown=true") }
        fun awaitIme(shown: Boolean) {
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (imeShown() != shown && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(120)
            }
            assertEquals("System IME visibility", shown, imeShown())
        }
        fun edit(description: String, change: (android.widget.EditText) -> Unit) {
            onView(withContentDescription(description)).perform(object : androidx.test.espresso.ViewAction {
                override fun getDescription() = "Edit through the focused text field"
                override fun getConstraints() = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.EditText::class.java)
                override fun perform(controller: androidx.test.espresso.UiController, view: android.view.View) {
                    val field = view as android.widget.EditText
                    assertTrue(field.hasFocus())
                    change(field)
                }
            })
        }
        fun input(description: String, value: String) {
            onView(withContentDescription(description)).perform(scrollTo(), click())
            awaitIme(true)
            onView(withContentDescription(description)).check(matches(isDisplayed()))
            // Injecting hardware key events switches the X08E's Sogou IME to physical-keyboard mode.
            edit(description) {
                it.selectAll()
                assertTrue(it.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!.commitText(value, 1))
                assertEquals(value, it.text.toString())
            }
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.page_container)).perform(swipeDown())
            onView(withText("天气服务与地点")).perform(click())
            onView(withText("和风服务配置")).perform(click())
            input("专属 API Host", "abc.qweatherapi.com")
            edit("专属 API Host") {
                assertTrue(it.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!.deleteSurroundingText(1, 0))
                assertEquals("abc.qweatherapi.co", it.text.toString())
            }
            onView(withContentDescription("Ed25519 PKCS#8 私钥")).perform(scrollTo(), click())
            awaitIme(true)
            lateinit var clipboard: android.content.ClipboardManager
            var previousClip: android.content.ClipData? = null
            scenario.onActivity { activity ->
                clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
                previousClip = clipboard.primaryClip
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("test", "fixture"))
            }
            try {
                edit("Ed25519 PKCS#8 私钥") {
                    assertTrue(it.onTextContextMenuItem(android.R.id.paste))
                    assertEquals("fixture", it.text.toString())
                }
            } finally {
                scenario.onActivity { previousClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() }
            }
            onView(withText("保存凭据")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("关闭")).perform(click())
            awaitIme(false)

            onView(withText("搜索城市或区县")).perform(click())
            input("城市、区县或行政区域", "Beijing")
            onView(withText("搜索")).check(matches(isDisplayed()))
            onView(withText("关闭")).perform(click())
            awaitIme(false)

            onView(withText("添加地点")).perform(click())
            input("地点名称", "IME place")
            input("地点时区（例如 Asia/Shanghai）", "Asia/Shanghai")
            input("纬度 −90 至 90", "39.92")
            input("经度 −180 至 180", "116.41")
            onView(withText("保存地点")).perform(scrollTo(), click())
            awaitIme(false)
            scenario.onActivity { activity ->
                val store = ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource.weatherRepository.places
                val place = store.places.single { it.name == "IME place" }
                assertEquals(ZoneId.of("Asia/Shanghai"), place.zone)
                assertEquals(WeatherCoordinates("39.92".toBigDecimal(), "116.41".toBigDecimal()),
                    (place.sources.single() as WeatherSourceRef.QWeather).coordinates)
                store.delete(place.id)
            }

            onView(withText("和风服务配置")).perform(click())
            onView(withText("刷新周期设置")).perform(scrollTo(), click())
            onView(withContentDescription("和风刷新模式")).perform(click())
            onView(withText("自定义")).inRoot(isPlatformPopup()).perform(click())
            input("实时天气（10–180 分钟）", "25")
            onView(withText("保存刷新周期")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("关闭")).perform(click())
            awaitIme(false)
        }
    }

    @Test fun controlCenterRefreshModeAndManualButtonsAreTouchable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val id = UUID.randomUUID().toString()
            val title = "刷新测试地点"
            lateinit var original: WeatherRefreshPolicy
            scenario.onActivity { activity ->
                val services = ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource.weatherServices
                check(services.settings.config == null) { "Run this UI case in an isolated testbed without credentials" }
                original = services.refreshPolicy
                services.places.save(WeatherPlace(id, title, ZoneId.of("Asia/Shanghai"), listOf(
                    WeatherSourceRef.QWeather(WeatherCoordinates("40.12".toBigDecimal(), "116.1".toBigDecimal())),
                )))
            }
            try {
                onView(withId(R.id.page_container)).perform(swipeDown())
                onView(withText("天气服务与地点")).perform(click())
                onView(withText("和风服务配置")).perform(click())
                onView(withText("刷新周期设置")).perform(scrollTo(), click())
                onView(withContentDescription("和风刷新模式")).perform(click())
                onView(withText("节省请求")).inRoot(isPlatformPopup()).perform(click())
                onView(withText("保存刷新周期")).perform(click())
                onView(withText("刷新周期已保存并生效")).check(matches(isDisplayed()))
                scenario.onActivity { activity ->
                    val services = ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource.weatherServices
                    assertEquals(WeatherRefreshMode.SAVER, services.refreshPolicy.mode)
                }
                onView(withText("关闭")).perform(click())
                onView(withText("关闭")).perform(click())
                onView(withText("$title · Asia/Shanghai")).perform(scrollTo(), click())
                onView(withText("检查天气数据")).perform(click())
                onView(withText("刷新核心天气")).perform(click())
                onView(withText("刷新选中数据")).perform(click())
                onView(withText("关闭")).perform(click())
            } finally {
                scenario.onActivity { activity ->
                    val services = ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource.weatherServices
                    services.places.delete(id)
                    services.setRefreshPolicy(original)
                }
            }
        }
    }

    @Test fun inspectorShowsNetworkChangesAndConfirmsOfflineCacheClear() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val id = UUID.randomUUID().toString()
            val title = "离线状态测试地点"
            scenario.onActivity { activity ->
                val services = ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource.weatherServices
                check(services.settings.config == null) { "Use the isolated testbed without QWeather credentials" }
                services.places.save(WeatherPlace(id, title, ZoneId.of("Asia/Shanghai"), listOf(
                    WeatherSourceRef.QWeather(WeatherCoordinates("40.123".toBigDecimal(), "116.12".toBigDecimal())),
                )))
            }
            try {
                onView(withId(R.id.page_container)).perform(swipeDown())
                onView(withText("天气服务与地点")).perform(click())
                onView(withText("$title · Asia/Shanghai")).perform(scrollTo(), click())
                onView(withText("检查天气数据")).perform(click())
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource.weatherRepository.onNetworkLost()
                }
                onView(withText("设备离线，联网后可更新和风天气")).check(matches(isDisplayed()))
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource.weatherRepository.onNetworkAvailable()
                }
                onView(withText("网络已连接")).check(matches(isDisplayed()))
                onView(withText("清除此地点离线缓存")).perform(scrollTo(), click())
                onView(withText("当前会话的天气仍可查看。其他地点共用的缓存会保留。")).check(matches(isDisplayed()))
                onView(withText("清除离线缓存")).perform(click())
                onView(withText("网络已连接")).check(matches(isDisplayed()))
            } finally {
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource.weatherRepository.places.delete(id)
                }
            }
        }
    }

    @Test fun androidKeystoreAndEd25519RoundTripAndCorruption() {
        val namespace = UUID.randomUUID().toString()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$namespace-$name", mode)
        }
        val prefs = isolated.getSharedPreferences("weather_service", Context.MODE_PRIVATE)
        val settings = QWeatherSettings(isolated, namespace)
        try {
            // RFC 8032 test vector 1. This key belongs exclusively to the public test vector.
            val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
            val public = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
            fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val pem = Base64.getEncoder().encodeToString(hex("302e020100300506032b657004220420$seed"))
            settings.save("test.qweatherapi.com", QWeatherAuth.Jwt("kid", "issuer", "subject", pem))
            assertFalse(prefs.getString("payload", "")!!.contains(pem))
            val initial = settings.activeKey!!.spkiSha256
            val export = settings.activeKey!!.publicPem.lineSequence().elementAt(1)
            assertArrayEquals(hex("302a300506032b6570032100$public"), Base64.getDecoder().decode(export))
            settings.generateKey()
            val restored = QWeatherSettings(isolated, namespace)
            assertEquals(initial, restored.activeKey!!.spkiSha256)
            val pending = restored.pendingKey!!
            assertNotEquals(initial, pending.spkiSha256)
            assertFalse(prefs.getString("pending_key", "")!!.contains(pending.encode()))
            restored.save("test.qweatherapi.com", QWeatherAuth.Jwt("newkid", "issuer", "subject", pending))
            val activated = QWeatherSettings(isolated, namespace)
            assertNull(activated.pendingKey)
            assertEquals(pending.spkiSha256, activated.activeKey!!.spkiSha256)
            settings.save("test.qweatherapi.com", QWeatherAuth.Jwt("kid", "issuer", "subject", pem))

            val saved = QWeatherSettings(isolated, namespace).config!!
            val parts = saved.auth.header(Instant.parse("2026-11-15T00:00:00Z")).second.removePrefix("Bearer ").split(".")
            val verifier = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
            verifier.initVerify(EdDSAPublicKey(X509EncodedKeySpec(hex("302a300506032b6570032100$public"))))
            verifier.update("${parts[0]}.${parts[1]}".toByteArray())
            assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])))
            prefs.edit().putString("payload", "corrupt").commit()
            val damaged = QWeatherSettings(isolated, namespace)
            assertNull(damaged.config)
            assertTrue(damaged.problem.contains("重新配置"))
            damaged.clear()
            assertFalse(QWeatherSettings(isolated, namespace).hasPayload)
            settings.save("test.qweatherapi.com", QWeatherAuth.ApiKey("test-vector-key"))
            assertEquals("test-vector-key", QWeatherSettings(isolated, namespace).config!!.auth.header(Instant.EPOCH).second)
        } finally {
            settings.clear()
            context.deleteSharedPreferences("$namespace-weather_service")
        }
    }

    @Test fun qWeatherWorksInDemoModeThroughRealHttpAndMonthlyBudget() {
        val namespace = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, namespace)
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$namespace-$name", mode)
            override fun getNoBackupFilesDir() = directory
        }
        val settings = QWeatherSettings(isolated, namespace)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val client = okhttp3.OkHttpClient()
        val server = okhttp3.mockwebserver.MockWebServer()
        val responses = java.util.concurrent.LinkedBlockingQueue<String>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                if (request.getHeader("X-QW-Api-Key") != "mock-key") return okhttp3.mockwebserver.MockResponse().setResponseCode(401)
                val body = when {
                    request.path!!.startsWith("/geo/v2/city/lookup") -> """{"code":"200","location":[
                        {"name":"重名区","adm1":"甲地","adm2":"甲区","country":"测试","lat":"39.923456","lon":"116.417654","id":"101010100","tz":"Asia/Shanghai"},
                        {"name":"重名区","adm1":"乙地","adm2":"乙区","country":"测试","lat":"32.01","lon":"120.01","id":"101010101","tz":"Asia/Tokyo"}]}"""
                    request.path!!.startsWith("/weather/v1/current/39.92/116.42") -> """{"temperature":{"value":22.5,"unit":"°C"},
                        "metadata":{"attributions":["mock source"]},"condition":{"code":"100","text":"测试晴"}}"""
                    else -> return okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                }
                return okhttp3.mockwebserver.MockResponse().setBody(body)
            }
        }
        server.start()
        val baseUrl = server.url("/")
        val scheduler = com.dormpanel.app.ha.MainHaScheduler()
        val appearance = com.dormpanel.app.appearance.AppearanceController(
            com.dormpanel.app.appearance.PreferencesAppearanceStore(isolated))
        val ha = com.dormpanel.app.ha.HaDashboardDataSource(scheduler, client, appearance)
        val services = WeatherServices(isolated, scheduler, ha, { "" }, { null }, settings = settings,
            apiFactory = { http, clock ->
                QWeatherApi(http, clock) { _, endpoint ->
                    baseUrl.newBuilder().addPathSegments(endpoint.path).apply {
                        endpoint.parameters.forEach { (name, value) -> addQueryParameter(name, value) }
                    }.build()
                }
            })
        fun next() = checkNotNull(responses.poll(8, java.util.concurrent.TimeUnit.SECONDS))
        try {
            instrumentation.runOnMainSync {
                services.configure("mock.qweatherapi.com", QWeatherAuth.ApiKey("mock-key"))
                services.budget.setLimit(2)
                services.search("重名区") { cities, error ->
                    if (error != null) { responses.add(error); return@search }
                    responses.add(cities.joinToString(",") { it.area })
                    val city = cities.first()
                    val place = services.select(city, city.zone!!)
                    services.repository.observe(place.id, place.sources.single(), WeatherQuery(WeatherBlock.CURRENT)) {
                        if (it.state == WeatherState.FRESH) {
                            val current = it.data as WeatherData.Current
                            responses.add("${current.conditions.temperature}:${it.attribution.statements.single()}")
                        }
                    }
                }
            }
            assertEquals("甲区 / 甲地 / 测试,乙区 / 乙地 / 测试", next())
            assertEquals("22.5:mock source", next())
            assertEquals(2, server.requestCount)
            val geo = server.takeRequest()
            assertEquals("重名区", geo.requestUrl!!.queryParameter("location"))
            assertNull(geo.requestUrl!!.queryParameter("key"))
            instrumentation.runOnMainSync {
                services.test { responses.add(it) }
            }
            assertTrue(next().contains("月请求上限"))
            assertEquals(2, services.budget.stats().requests)
            assertEquals(2, server.requestCount)
            assertEquals(WeatherState.UNCONFIGURED, services.budget.acquire("wrong", true)?.state)
        } finally {
            instrumentation.runOnMainSync { services.close(); ha.stop() }
            settings.clear()
            server.close()
            client.dispatcher.executorService.shutdown()
            directory.deleteRecursively()
            listOf("weather_service", "weather_places", "weather_budget", "appearance").forEach {
                context.deleteSharedPreferences("$namespace-$it")
            }
        }
    }

    @Test fun publicDirectoryFavoriteUsesOnlyPublicIdAndResolvesAfterReopening() {
        val namespace = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, namespace)
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$namespace-$name", mode)
            override fun getNoBackupFilesDir() = directory
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val settings = QWeatherSettings(isolated, namespace)
        val client = okhttp3.OkHttpClient()
        val server = okhttp3.mockwebserver.MockWebServer()
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                val path = request.requestUrl!!.encodedPath
                calls += path
                val body = when (path) {
                    "/locations.csv" -> {
                        check(request.getHeader("X-QW-Api-Key") == null && request.getHeader("Authorization") == null)
                        "China-City-List vTest,,,,\nLocation_ID,Location_Name\n101010100,Public location"
                    }
                    "/geo/v2/city/lookup" -> {
                        check(request.getHeader("X-QW-Api-Key") == "mock-key")
                        """{"code":"200","location":[{"name":"Private Geo label","id":"101010100",
                            "lat":"39.923456","lon":"116.417654","tz":"Asia/Shanghai","adm1":"Private area"}]}"""
                    }
                    "/weather/v1/current/39.92/116.42" -> """{"temperature":{"unit":"°C","value":22}}"""
                    "/weather/v1/hourly/39.92/116.42" -> """{"hours":[]}"""
                    else -> return okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                }
                return okhttp3.mockwebserver.MockResponse().setBody(body)
            }
        }
        server.start()
        val base = server.url("/")
        val scheduler = com.dormpanel.app.ha.MainHaScheduler()
        val ha = com.dormpanel.app.ha.HaDashboardDataSource(scheduler, client,
            com.dormpanel.app.appearance.AppearanceController(com.dormpanel.app.appearance.PreferencesAppearanceStore(isolated)))
        fun newServices() = WeatherServices(isolated, scheduler, ha, { "" }, { null }, settings = settings,
            apiFactory = { http, clock ->
                QWeatherApi(http, clock) { _, endpoint -> base.newBuilder().addPathSegments(endpoint.path).apply {
                    endpoint.parameters.forEach { (name, value) -> addQueryParameter(name, value) }
                }.build() }
            }, publicLocationUrl = base.newBuilder().addPathSegment("locations.csv").build())
        lateinit var services: WeatherServices
        val saved = java.util.concurrent.LinkedBlockingQueue<WeatherPlace>()
        val weather = java.util.concurrent.LinkedBlockingQueue<WeatherSnapshot>()
        val policy = WeatherRefreshPolicy(WeatherRefreshMode.CUSTOM, mapOf(RefreshInterval.CURRENT to 25))
        try {
            instrumentation.runOnMainSync {
                services = newServices()
                assertEquals(30_000, services.budget.stats().limit)
                services.configure("mock.qweatherapi.com", QWeatherAuth.ApiKey("mock-key"))
                services.setRefreshPolicy(policy)
                services.search("test city") { results, _ ->
                    val result = results.single()
                    val preview = services.select(result, result.zone!!)
                    services.favorite(preview.id, result.locationId, "My saved label", ZoneId.of("Asia/Tokyo")) { place, _ ->
                        check(place?.id == preview.id && !services.places.isTemporary(preview.id))
                        saved.add(place)
                    }
                }
            }
            val original = checkNotNull(saved.poll(8, java.util.concurrent.TimeUnit.SECONDS))
            val raw = isolated.getSharedPreferences("weather_places", Context.MODE_PRIVATE).getString("places", "")!!
            assertFalse(raw.contains("Private Geo label") || raw.contains("Private area") || raw.contains("39.923456") || raw.contains("Asia/Shanghai"))
            instrumentation.runOnMainSync {
                services.close()
                services = newServices()
                assertEquals(policy.minutes(WeatherBlock.CURRENT), services.refreshPolicy.minutes(WeatherBlock.CURRENT))
                assertEquals(WeatherRefreshMode.CUSTOM, services.refreshPolicy.mode)
                val restored = services.places.get(original.id)!!
                assertEquals(original, restored)
                restored.sources.forEach { source ->
                    for (block in listOf(WeatherBlock.CURRENT, WeatherBlock.HOURLY)) {
                        services.repository.observe(restored.id, source, WeatherQuery(block)) {
                            if (it.persistence == WeatherPersistence.SAVED) weather.add(it)
                        }
                    }
                }
            }
            val first = checkNotNull(weather.poll(8, java.util.concurrent.TimeUnit.SECONDS))
            val second = checkNotNull(weather.poll(8, java.util.concurrent.TimeUnit.SECONDS))
            val current = listOf(first, second).first { it.query.block == WeatherBlock.CURRENT }
            assertEquals(22.0, (current.data as WeatherData.Current).conditions.temperature!!, 0.0)
            assertEquals(2, calls.count { it == "/geo/v2/city/lookup" })
            assertEquals(1, calls.count { it == "/locations.csv" })
            val before = services.budget.stats().requests
            services.budget.setLimit(6)
            assertEquals(before, services.budget.stats().requests)
            assertEquals(6 - before, services.budget.stats().remaining)
        } finally {
            instrumentation.runOnMainSync { services.close(); ha.stop() }
            settings.clear()
            server.close()
            client.dispatcher.executorService.shutdown()
            directory.deleteRecursively()
            listOf("weather_service", "weather_places", "weather_budget", "weather_refresh", "appearance").forEach {
                context.deleteSharedPreferences("$namespace-$it")
            }
        }
    }

    @Test fun placeAndAtomicWeatherCacheSurviveStoreRecreation() {
        val namespace = UUID.randomUUID().toString()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$namespace-$name", mode)
        }
        val place = WeatherPlace("test", "Test place", ZoneId.of("America/New_York"), listOf(
            WeatherSourceRef.QWeather(WeatherCoordinates("39.123456789".toBigDecimal(), "-75.012345678".toBigDecimal())),
            WeatherSourceRef.HomeAssistant("weather.test", "test-server"),
        ))
        val directory = File(context.cacheDir, namespace)
        fun cache() = DiskWeatherCache(directory, java.util.concurrent.Executor { it.run() }, { it() })
        try {
            WeatherPlaceStore(isolated).save(place)
            assertEquals(place, WeatherPlaceStore(isolated).get(place.id))
            val moment = Instant.parse("2026-11-15T14:10:00Z")
            cache().write("q", "current", WeatherCacheRecord(JSONObject().put("sample", 7), moment)) { assertTrue(it) }
            cache().read("q", "current") { record ->
                assertNotNull(record)
                assertEquals(moment, record!!.fetchedAt)
                assertEquals(7, record.wire.getInt("sample"))
            }
            val obstruction = File(directory, "file-instead-of-directory").apply { writeText("fixture") }
            val broken = DiskWeatherCache(obstruction, java.util.concurrent.Executor { it.run() }, { it() })
            broken.write("q", "current", WeatherCacheRecord(JSONObject().put("sample", 8), moment)) { assertFalse(it) }
            assertTrue(obstruction.delete())
            broken.write("q", "current", WeatherCacheRecord(JSONObject().put("sample", 8), moment)) { assertTrue(it) }
            broken.read("q", "current") { assertEquals(8, it?.wire?.getInt("sample")) }
            cache().clear(setOf("q")) { assertTrue(it) }
            cache().read("q", "current") { assertNull(it) }
            WeatherPlaceStore(isolated).delete(place.id)
            assertNull(WeatherPlaceStore(isolated).get(place.id))
        } finally {
            directory.deleteRecursively()
            context.deleteSharedPreferences("$namespace-weather_places")
        }
    }
}
