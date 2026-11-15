package com.dormpanel.app.weather

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
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
            cache().write("q", "current", WeatherCacheRecord(JSONObject().put("sample", 7), moment))
            cache().read("q", "current") { record ->
                assertNotNull(record)
                assertEquals(moment, record!!.fetchedAt)
                assertEquals(7, record.wire.getInt("sample"))
            }
            cache().clear(setOf("q"))
            cache().read("q", "current") { assertNull(it) }
            WeatherPlaceStore(isolated).delete(place.id)
            assertNull(WeatherPlaceStore(isolated).get(place.id))
        } finally {
            directory.deleteRecursively()
            context.deleteSharedPreferences("$namespace-weather_places")
        }
    }
}
