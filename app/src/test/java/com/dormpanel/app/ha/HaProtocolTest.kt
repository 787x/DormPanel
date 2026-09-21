package com.dormpanel.app.ha

import com.dormpanel.app.appearance.*
import com.dormpanel.app.data.Availability
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class HaProtocolTest {
    private class Fixture(val reject: Boolean = false) : AutoCloseable {
        val server = MockWebServer()
        val scheduler = ManualScheduler()
        val http = haHttpClient()
        val received = CopyOnWriteArrayList<JSONObject>()
        lateinit var peer: WebSocket
        val appearance = AppearanceController(object : AppearanceStore {
            override fun read() = AppearanceState()
            override fun write(state: AppearanceState) {}
        })
        val source = HaDashboardDataSource(scheduler, http, appearance)
        var subscription = 0
        @Volatile var holdServices = false
        init {
            enqueueSocket()
            server.start()
        }
        fun enqueueSocket() {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { peer = webSocket; webSocket.send("""{"type":"auth_required"}""") }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = JSONObject(text); received += message
                    if (message.optString("type") == "auth") {
                        webSocket.send(if (reject) """{"type":"auth_invalid","message":"Invalid token"}""" else """{"type":"auth_ok"}""")
                        return
                    }
                    if (holdServices && message.optString("type") == "call_service") return
                    val result: Any = when (message.optString("type")) {
                        "subscribe_events" -> { if (message.optString("event_type") == "state_changed") subscription = message.getInt("id"); JSONObject.NULL }
                        "get_states" -> org.json.JSONArray("""[
                            {"entity_id":"light.a","state":"on","attributes":{"friendly_name":"A","brightness":128,"supported_color_modes":["color_temp"],"min_color_temp_kelvin":2700,"max_color_temp_kelvin":6500}},
                            {"entity_id":"light.b","state":"off","attributes":{"friendly_name":"B","supported_color_modes":["brightness"]}},
                            {"entity_id":"input_select.theme","state":"LIGHT","attributes":{"options":["LIGHT","DARK"]}},
                            {"entity_id":"input_number.opacity","state":"35","attributes":{}},
                            {"entity_id":"weather.home","state":"sunny","attributes":{"temperature":72,"temperature_unit":"°F","supported_features":1}}
                        ]""")
                        "config/entity_registry/list_for_display" -> JSONObject("""{"entities":[]}""")
                        "config/entity_registry/list" -> org.json.JSONArray("""[{"entity_id":"light.b","disabled_by":"user"}]""")
                        "call_service" -> if (message.optString("domain") == "weather") JSONObject("""{"response":{"weather.home":{"forecast":[{"datetime":"2026-09-20T00:00:00Z","temperature":75,"templow":60,"condition":"sunny"}]}}}""") else JSONObject()
                        else -> JSONObject.NULL
                    }
                    webSocket.send(JSONObject().put("id", message.getInt("id")).put("type", "result").put("success", true).put("result", result).toString())
                }
            }))
        }
        fun start(theme: String = "input_select.theme", opacity: String = "input_number.opacity") {
            appearance.themeCommand = source::requestTheme
            appearance.opacityCommand = source::requestOpacity
            source.configure(HaConnectionSettings(BackendMode.HOME_ASSISTANT, server.url("/").toString(), "weather.home", theme, opacity), "test-token")
        }
        fun entity(id: String, value: String?, attributes: JSONObject = JSONObject()) {
            val next = value?.let { JSONObject().put("entity_id", id).put("state", it).put("attributes", attributes) } ?: JSONObject.NULL
            peer.send(JSONObject().put("type", "event").put("id", subscription).put("event", JSONObject().put("event_type", "state_changed")
                .put("data", JSONObject().put("entity_id", id).put("new_state", next))).toString())
            until { if (value == null) id !in source.store.entities else source.store.entities[id]?.let { it.value == value && it.attributes.toString() == attributes.toString() } == true }
        }
        fun services(name: String) = received.filter { it.optString("service") == name }
        fun brightnessCommands() = services("turn_on").mapNotNull { it.optJSONObject("service_data")?.takeIf { data -> data.has("brightness_pct") }?.getInt("brightness_pct") }
        fun awaitAppearanceIdle() { until { source.state.weather.forecast.isNotEmpty() } }
        fun until(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000
            while (!condition() && System.nanoTime() < deadline) { scheduler.advance(); Thread.sleep(5) }
            assertTrue("Condition did not become true; status=${source.status}", condition())
        }
        fun state(value: String) { peer.send(JSONObject().put("type", "event").put("id", subscription).put("event", JSONObject().put("event_type", "state_changed")
            .put("data", JSONObject().put("entity_id", "light.a").put("new_state", JSONObject("""{"entity_id":"light.a","state":"$value","attributes":{"supported_color_modes":["color_temp"],"brightness":255,"min_color_temp_kelvin":2700,"max_color_temp_kelvin":6500}}""")))).toString()) }
        override fun close() { source.stop(); http.dispatcher.cancelAll(); http.connectionPool.evictAll(); server.close(); http.dispatcher.executorService.shutdown() }
    }
    @Test fun authenticatesMapsSnapshotAndForecastAndAppearanceThenCommandsAndReconnect() {
        Fixture().use { f ->
            var catalogChanges = 0
            f.source.catalog.addListener { catalogChanges++ }
            f.start(); f.until { f.source.connected && f.source.state.weather.forecast.isNotEmpty() }
            assertEquals("test-token", f.received.first().getString("access_token"))
            assertEquals(50, f.source.state.lights.getValue("ha:light.a").brightness)
            assertEquals("°F", f.source.state.weather.temperatureUnit)
            assertEquals(ThemeMode.LIGHT, f.appearance.state.themeMode); assertEquals(0.35f, f.appearance.state.cardSurfaceOpacity)
            assertFalse(f.source.state.lights.containsKey("ha:light.b"))
            val candidate = f.source.catalog.candidates.single { it.providerType == "light" }
            assertEquals("ha:light.a", JSONObject(candidate.configurationJson).getString("lightId"))
            f.source.toggleLight("desk")
            f.source.toggleLight("ha:light.a")
            f.until { f.received.any { it.optString("service") == "turn_off" } }
            assertTrue(f.source.state.lights.getValue("ha:light.a").isOn)
            f.state("off"); f.until { !f.source.state.lights.getValue("ha:light.a").isOn }
            f.source.toggleLight("ha:light.a"); f.until { f.received.any { it.optString("service") == "turn_on" } }
            val before = catalogChanges
            f.state("on"); f.until { f.source.state.lights.getValue("ha:light.a").isOn }
            assertEquals(before, catalogChanges)
            repeat(100) { f.source.setBrightness("ha:light.a", it) }
            f.scheduler.advance(180)
            f.until { f.received.any { it.optJSONObject("service_data")?.optInt("brightness_pct") == 99 } }
            assertEquals(1, f.received.count { it.optJSONObject("service_data")?.has("brightness_pct") == true })
            f.source.requestTheme(ThemeMode.DARK); f.source.requestOpacity(0.6f); f.scheduler.advance(180)
            f.until { f.received.any { it.optString("service") == "set_value" } }
            assertEquals(ThemeMode.LIGHT, f.appearance.state.themeMode)
            f.peer.close(1000, "test outage"); f.until { f.source.status.state == HaConnectionState.RECONNECTING }
            assertEquals(Availability.STALE, f.source.state.lights.getValue("ha:light.a").availability)
            assertEquals(100, f.source.state.lights.getValue("ha:light.a").brightness)
            val count = f.received.size; f.source.toggleLight("ha:light.a"); f.source.setBrightness("ha:light.a", 1); f.scheduler.advance(180); assertEquals(count, f.received.size)
            f.enqueueSocket(); f.scheduler.advance(1000); f.until { f.source.connected }
            assertEquals(50, f.source.state.lights.getValue("ha:light.a").brightness)
            assertEquals(Availability.AVAILABLE, f.source.state.lights.getValue("ha:light.a").availability)
            assertEquals(2, f.received.count { it.optString("type") == "get_states" })
        }
    }
    @Test fun brightnessCommandsNeverSendZeroAndPowerCancelsOlderSliderIntent() {
        Fixture().use { f ->
            f.start(); f.until { f.source.connected }; f.awaitAppearanceIdle()
            for ((input, expected) in listOf(0 to 1, -30 to 1, 150 to 100)) {
                val before = f.brightnessCommands().size
                f.source.setBrightness("ha:light.a", input); f.scheduler.advance(180)
                f.until { f.brightnessCommands().size == before + 1 }
                assertEquals(expected, f.brightnessCommands().last())
            }
            val before = f.brightnessCommands().size
            for (value in 100 downTo -10) f.source.setBrightness("ha:light.a", value)
            f.scheduler.advance(180); f.until { f.brightnessCommands().size == before + 1 }
            assertEquals(1, f.brightnessCommands().last())
            f.source.setBrightness("ha:light.a", 80)
            f.source.toggleLight("ha:light.a")
            f.until { f.services("turn_off").isNotEmpty() }
            f.scheduler.advance(180)
            // A subsequent acknowledged command is a barrier for the socket's ordered send queue.
            f.source.requestTheme(ThemeMode.DARK); f.until { f.services("select_option").isNotEmpty() }
            assertEquals(before + 1, f.brightnessCommands().size)
            assertTrue(f.brightnessCommands().all { it in 1..100 })
            assertTrue(f.received.none { it.optString("service") == "toggle" })
            f.entity("light.a", "off", JSONObject("""{"brightness":0,"supported_color_modes":["brightness"]}"""))
            assertEquals(0, f.source.state.lights.getValue("ha:light.a").brightness)
            assertFalse(f.source.state.lights.getValue("ha:light.a").isOn)
            f.source.setBrightness("ha:light.a", 0); f.scheduler.advance(180)
            f.until { f.brightnessCommands().size == before + 2 }
            assertEquals(1, f.brightnessCommands().last())
            assertEquals(0, f.source.state.lights.getValue("ha:light.a").brightness)
        }
    }
    @Test fun validHelpersSendExactOptionsAndWaitForAuthoritativeEvents() {
        Fixture().use { f ->
            f.start(); f.until { f.source.connected }; f.awaitAppearanceIdle()
            f.entity("input_select.theme", "LiGhT", JSONObject("""{"options":["LiGhT","dArK"]}"""))
            assertTrue(f.source.requestTheme(ThemeMode.DARK))
            assertTrue(f.source.requestOpacity(0.6f))
            f.until { f.services("select_option").isNotEmpty() && f.services("set_value").isNotEmpty() }
            assertEquals("dArK", f.services("select_option").last().getJSONObject("service_data").getString("option"))
            assertEquals(60, f.services("set_value").last().getJSONObject("service_data").getInt("value"))
            f.appearance.setTheme(ThemeMode.DARK); f.appearance.setCardOpacity(0.6f)
            assertEquals(AppearanceState(ThemeMode.LIGHT, 0.35f), f.appearance.state)
            f.entity("input_select.theme", "dArK", JSONObject("""{"options":["LiGhT","dArK"]}"""))
            f.entity("input_number.opacity", "60")
            assertEquals(AppearanceState(ThemeMode.DARK, 0.6f), f.appearance.state)
            assertTrue(f.source.requestOpacity(-1f)); assertTrue(f.source.requestOpacity(2f))
            f.until { f.services("set_value").size == 4 }
            assertEquals(listOf(0, 100), f.services("set_value").takeLast(2).map { it.getJSONObject("service_data").getInt("value") })
        }
    }
    @Test fun emptyStaleAndWrongDomainBindingsFallBackLocally() {
        for ((theme, opacity) in listOf("" to "", "input_select.deleted" to "input_number.renamed", "input_number.opacity" to "input_select.theme")) {
            Fixture().use { f ->
                f.start(theme, opacity); f.until { f.source.connected }; f.awaitAppearanceIdle()
                assertFalse(f.source.requestTheme(ThemeMode.LIGHT)); assertFalse(f.source.requestOpacity(0.6f))
                f.appearance.setTheme(ThemeMode.LIGHT); f.appearance.setCardOpacity(0.6f)
                assertEquals(AppearanceState(ThemeMode.LIGHT, 0.6f), f.appearance.state)
                f.entity("light.a", "off")
                assertEquals(AppearanceState(ThemeMode.LIGHT, 0.6f), f.appearance.state)
                assertEquals(theme, f.source.settings.themeEntity); assertEquals(opacity, f.source.settings.opacityEntity)
                assertTrue(f.services("select_option").isEmpty()); assertTrue(f.services("set_value").isEmpty())
            }
        }
    }
    @Test fun unavailableUnknownAndDeletedHelpersDoNotSwallowLocalChanges() {
        for (value in listOf("unavailable", "unknown", null)) {
            Fixture().use { f ->
                f.start(); f.until { f.source.connected }; f.awaitAppearanceIdle()
                f.entity("input_select.theme", value, JSONObject("""{"options":["LIGHT","DARK"]}"""))
                f.entity("input_number.opacity", value)
                assertFalse(f.source.requestTheme(ThemeMode.DARK)); assertFalse(f.source.requestOpacity(0.6f))
                f.appearance.setTheme(ThemeMode.DARK); f.appearance.setCardOpacity(0.6f)
                assertEquals(AppearanceState(ThemeMode.DARK, 0.6f), f.appearance.state)
                f.entity("light.a", "off")
                assertEquals(AppearanceState(ThemeMode.DARK, 0.6f), f.appearance.state)
                assertEquals("input_select.theme", f.source.settings.themeEntity)
                assertEquals("input_number.opacity", f.source.settings.opacityEntity)
            }
        }
    }
    @Test fun missingRequestedThemeOptionUsesLocalFallbackUntilHelperChanges() {
        Fixture().use { f ->
            f.start(); f.until { f.source.connected }; f.awaitAppearanceIdle()
            f.entity("input_select.theme", "LIGHT", JSONObject("""{"options":["LIGHT","automatic"]}"""))
            assertFalse(f.source.requestTheme(ThemeMode.DARK))
            f.appearance.setTheme(ThemeMode.DARK)
            assertEquals(ThemeMode.DARK, f.appearance.state.themeMode)
            f.entity("light.a", "off")
            assertEquals(ThemeMode.DARK, f.appearance.state.themeMode)
            assertTrue(f.services("select_option").isEmpty())
        }
    }
    @Test fun disconnectedAndNonFiniteOpacityRequestsFallBackWithoutSending() {
        Fixture().use { f ->
            f.start(); f.until { f.source.connected }; f.awaitAppearanceIdle()
            for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) assertFalse(f.source.requestOpacity(value))
            assertTrue(f.services("set_value").isEmpty())
            f.peer.close(1000, "offline"); f.until { !f.source.connected }
            assertFalse(f.source.requestTheme(ThemeMode.DARK)); assertFalse(f.source.requestOpacity(0.6f))
            f.appearance.setTheme(ThemeMode.DARK); f.appearance.setCardOpacity(0.6f)
            assertEquals(AppearanceState(ThemeMode.DARK, 0.6f), f.appearance.state)
        }
    }
    @Test fun fullRequestQueueRejectsHelperOwnershipAndAllowsLocalFallback() {
        Fixture().use { f ->
            f.start(); f.until { f.source.connected }; f.awaitAppearanceIdle()
            f.holdServices = true
            repeat(128) { assertTrue(f.source.requestOpacity(0.5f)) }
            assertFalse(f.source.requestTheme(ThemeMode.DARK)); assertFalse(f.source.requestOpacity(0.6f))
            f.appearance.setTheme(ThemeMode.DARK); f.appearance.setCardOpacity(0.6f)
            assertEquals(AppearanceState(ThemeMode.DARK, 0.6f), f.appearance.state)
            f.entity("light.a", "off")
            assertEquals(AppearanceState(ThemeMode.DARK, 0.6f), f.appearance.state)
        }
    }
    @Test fun socketNotSendableDoesNotClaimHelperOwnership() {
        Fixture().use { f ->
            f.start(); f.until { f.source.connected }; f.awaitAppearanceIdle()
            f.source.stop() // Stop transport before a UI status change, exercising stale connected status.
            assertTrue(f.source.connected)
            assertFalse(f.source.requestTheme(ThemeMode.DARK)); assertFalse(f.source.requestOpacity(0.6f))
            f.appearance.setTheme(ThemeMode.DARK); f.appearance.setCardOpacity(0.6f)
            assertEquals(AppearanceState(ThemeMode.DARK, 0.6f), f.appearance.state)
        }
    }
    @Test fun diagnosticsDoNotFollowRedirectsOrCreateState() {
        val server = MockWebServer()
        val other = MockWebServer()
        val scheduler = ManualScheduler()
        val http = haHttpClient()
        server.start(); other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/api/config")))
            var result = ""
            HaRestClient(http, scheduler).test(HaEndpoint.parse(server.url("/").toString()), "diagnostic-token") { result = it }
            val deadline = System.currentTimeMillis() + 5000
            while (result.isEmpty() && System.currentTimeMillis() < deadline) { scheduler.advance(); Thread.sleep(5) }
            assertTrue(result.contains("302"))
            assertEquals(0, other.requestCount)
            assertEquals("Bearer diagnostic-token", server.takeRequest().getHeader("Authorization"))
        } finally { http.dispatcher.cancelAll(); server.close(); other.close(); http.dispatcher.executorService.shutdown() }
    }
    @Test fun settingsChangeClearsOldServerAndNeverSuppliesDemoValues() {
        Fixture().use { f ->
            f.start(); f.until { f.source.connected }
            f.source.configure(HaConnectionSettings(BackendMode.HOME_ASSISTANT, "http://different.invalid"), null)
            assertEquals(HaConnectionState.NOT_CONFIGURED, f.source.status.state)
            assertTrue(f.source.state.lights.isEmpty())
            assertTrue(f.source.catalog.candidates.none { it.providerType == "light" })
            assertEquals(Availability.UNAVAILABLE, f.source.state.weather.availability)
        }
    }
    @Test fun authenticationRejectionStopsAutomaticRetries() {
        Fixture(true).use { f ->
            f.start(); f.until { f.source.status.state == HaConnectionState.AUTH_ERROR }
            f.scheduler.advance(300000)
            assertEquals(1, f.server.requestCount); assertTrue(f.source.state.lights.isEmpty())
            assertFalse(f.received.any { it.optString("type") == "get_states" })
        }
    }
}
