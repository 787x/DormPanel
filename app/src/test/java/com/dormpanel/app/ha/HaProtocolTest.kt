package com.dormpanel.app.ha

import com.dormpanel.app.appearance.*
import com.dormpanel.app.data.Availability
import com.dormpanel.app.weather.HaWeatherProvider
import com.dormpanel.app.weather.WeatherBlock
import com.dormpanel.app.weather.WeatherQuery
import com.dormpanel.app.weather.WeatherResult
import com.dormpanel.app.weather.WeatherSourceRef
import com.dormpanel.app.weather.WeatherState
import java.time.Instant
import java.time.ZoneOffset
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class HaProtocolTest {
    private class Fixture(val reject: Boolean = false, val modernWeather: Boolean = false,
        val nativeControl: Boolean = false, val failFirstProbe: Boolean = false,
        val switchFixture: Boolean = false) : AutoCloseable {
        val server = MockWebServer()
        val scheduler = ManualScheduler()
        val http = haHttpClient()
        val received = CopyOnWriteArrayList<JSONObject>()
        lateinit var peer: WebSocket
        val appearance = AppearanceController(object : AppearanceStore {
            override fun read() = AppearanceState()
            override fun write(state: AppearanceState) {}
        })
        var dashboards = 0
        var homes = 0
        val projector = object : HaProjector {
            override fun dashboard(store: HaEntityStore, connected: Boolean, weatherId: String): com.dormpanel.app.data.DashboardData {
                dashboards++
                return DefaultHaProjector.dashboard(store, connected, weatherId)
            }
            override fun home(store: HaEntityStore, connected: Boolean, lights: Map<String, com.dormpanel.app.data.LightState>): com.dormpanel.app.home.HomeControlState {
                homes++
                return DefaultHaProjector.home(store, connected, lights)
            }
        }
        val source = HaDashboardDataSource(scheduler, http, appearance, projector)
        var subscription = 0
        @Volatile var holdServices = false
        @Volatile var switchEntryId = "outlet-entry"
        @Volatile var failFullRegistry = false
        @Volatile var fullRegistryError = "unavailable"
        @Volatile var snapshotSwitch = "off"
        @Volatile var scriptRequiresInput = false
        @Volatile var areaName = "Bedroom"
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
                    if (message.optString("type") == "weather/subscribe_forecast") {
                        val id = message.getInt("id")
                        webSocket.send(JSONObject().put("id", id).put("type", "result").put("success", modernWeather)
                            .put("error", if (modernWeather) null else JSONObject().put("code", "unknown_command")).toString())
                        if (modernWeather) {
                            val type = message.getString("forecast_type")
                            val temperature = when (message.getString("entity_id") to type) {
                                "weather.home" to "daily" -> 75
                                "weather.other" to "hourly" -> 12
                                else -> 7
                            }
                            val entries = org.json.JSONArray().put(JSONObject().put("temperature", temperature)
                                .put("datetime", "2026-09-20T00:00:00Z").put("condition", "sunny"))
                            webSocket.send(JSONObject().put("id", id).put("type", "event")
                                .put("event", JSONObject().put("type", type).put("forecast", entries)).toString())
                        }
                        return
                    }
                    if (holdServices && message.optString("type") == "call_service") return
                    if (failFullRegistry && message.optString("type") == "config/entity_registry/list") {
                        webSocket.send(JSONObject().put("id", message.getInt("id")).put("type", "result")
                            .put("success", false).put("error", JSONObject().put("code", fullRegistryError)).toString())
                        return
                    }
                    if (message.optString("type") == "dormpanel/control_protocol") {
                        val failure = failFirstProbe && received.count { it.optString("type") == "dormpanel/control_protocol" } == 1
                        webSocket.send(JSONObject().put("id", message.getInt("id")).put("type", "result")
                            .put("success", nativeControl && !failure).apply {
                                if (nativeControl && !failure) put("result", JSONObject().put("version", 1))
                                else put("error", JSONObject().put("code", if (failure) "not_loaded" else "unknown_command"))
                            }.toString())
                        return
                    }
                    val result: Any = when (message.optString("type")) {
                        "subscribe_events" -> { if (message.optString("event_type") == "state_changed") subscription = message.getInt("id"); JSONObject.NULL }
                        "get_states" -> org.json.JSONArray("""[
                            {"entity_id":"light.a","state":"on","attributes":{"friendly_name":"A","brightness":128,"supported_color_modes":["color_temp"],"min_color_temp_kelvin":2700,"max_color_temp_kelvin":6500}},
                            {"entity_id":"light.b","state":"off","attributes":{"friendly_name":"B","supported_color_modes":["brightness"]}},
                            {"entity_id":"input_select.theme","state":"LIGHT","attributes":{"options":["LIGHT","DARK"]}},
                            {"entity_id":"input_number.opacity","state":"35","attributes":{}},
                            {"entity_id":"input_number.display","state":"32","attributes":{"min":1,"max":100}},
                            {"entity_id":"input_number.system","state":"40","attributes":{"min":1,"max":100}},
                            {"entity_id":"input_number.volume","state":"45","attributes":{"min":0,"max":100}},
                            {"entity_id":"input_boolean.blackout","state":"off","attributes":{}},
                            {"entity_id":"input_boolean.automatic","state":"on","attributes":{}},
                            {"entity_id":"input_boolean.auto_off","state":"off","attributes":{}},
                            {"entity_id":"input_boolean.follow","state":"on","attributes":{}},
                            {"entity_id":"input_boolean.awake","state":"on","attributes":{}},
                            {"entity_id":"input_boolean.boot","state":"off","attributes":{}},
                            {"entity_id":"weather.home","state":"sunny","attributes":{"temperature":72,"temperature_unit":"°F","supported_features":1}}
                        ]""").apply { if (switchFixture) put(JSONObject().put("entity_id", "switch.panel").put("state", snapshotSwitch)) }
                        "config/entity_registry/list_for_display" -> JSONObject(if (switchFixture)
                            """{"entities":[{"ei":"switch.panel","di":"wall"}]}""" else """{"entities":[]}""")
                        "config/entity_registry/list" -> org.json.JSONArray("""[{"entity_id":"light.b","disabled_by":"user"},{"entity_id":"light.a","device_id":"lamp","area_id":"bedroom"}]""")
                            .apply { if (switchFixture) put(JSONObject().put("entity_id", "switch.panel").put("device_id", "wall").put("id", switchEntryId)) }
                        "config/device_registry/list" -> org.json.JSONArray("""[{"id":"lamp","name_by_user":"Lamp","area_id":"study"}]""")
                            .apply { if (switchFixture) put(JSONObject().put("id", "wall").put("name", "Wall switch")) }
                        "config/area_registry/list" -> org.json.JSONArray().put(JSONObject().put("area_id", "study").put("name", "Study"))
                            .put(JSONObject().put("area_id", "bedroom").put("name", areaName))
                        "get_services" -> JSONObject("""{"script":{"simple":{"fields":{"input":{"required":$scriptRequiresInput}}},"required":{"fields":{"input":{"required":true}}}}}""")
                        "call_service" -> if (message.optString("domain") == "weather") JSONObject("""{"response":{"weather.home":{"forecast":[{"datetime":"2026-09-20T00:00:00Z","temperature":75,"templow":60,"condition":"sunny"}]}}}""") else JSONObject()
                        else -> JSONObject.NULL
                    }
                    webSocket.send(JSONObject().put("id", message.getInt("id")).put("type", "result").put("success", true).put("result", result).toString())
                }
            }))
        }
        fun start(theme: String = "input_select.theme", opacity: String = "input_number.opacity",
            display: String = "", volume: String = "", system: String = "", blackout: String = "",
            systemAutomatic: String = "", followSystem: String = "", keepAwake: String = "", startAfterBoot: String = "") {
            appearance.themeCommand = source::requestTheme
            appearance.opacityCommand = source::requestOpacity
            source.configure(HaConnectionSettings(BackendMode.HOME_ASSISTANT, server.url("/").toString(), "weather.home", theme, opacity, display, volume, system, blackout,
                systemAutomatic, followSystem, keepAwake, startAfterBoot), "test-token")
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

    @Test fun compactFallbackSuspendsPersistentCardsUntilFullRegistryReturns() {
        Fixture(switchFixture = true).use { f ->
            f.start()
            f.until { f.source.connected }
            val sourceId = f.source.state.switches.sourceId
            val identity = f.source.state.switches.channels.getValue("ha:switch.panel").identity
            f.failFullRegistry = true
            f.peer.close(1000, "reconnect with compact registry only")
            f.until { !f.source.connected }
            f.enqueueSocket(); f.scheduler.advance(1000); f.until { f.source.connected }
            assertTrue(f.source.state.switches.channels.isEmpty())
            assertFalse(f.source.candidates.any { it.providerType == "switch" || it.providerType == "switch_group" })
            assertFalse(f.source.toggleSwitch(sourceId, identity))
            assertTrue(f.source.activateEntity(identity.entityId))
            f.until { f.services("turn_on").size == 1 }
            f.entity("switch.panel", "on")

            f.failFullRegistry = false
            val subscription = f.received.last { it.optString("event_type") == "entity_registry_updated" }.getInt("id")
            f.peer.send(JSONObject().put("id", subscription).put("type", "event").put("event",
                JSONObject().put("event_type", "entity_registry_updated").put("data", JSONObject())).toString())
            f.until { f.source.state.switches.resolve(sourceId, identity) != null }
            assertTrue(f.source.toggleSwitch(sourceId, identity))
            f.until { f.services("turn_off").size == 1 }
        }
    }

    @Test fun fullRegistryRecoversOnTheSameSocketWithoutRegistryEvents() {
        Fixture(switchFixture = true).use { f ->
            f.failFullRegistry = true
            f.start()
            f.until { f.source.connected }
            val sourceId = f.source.state.switches.sourceId
            val identity = com.dormpanel.app.data.SwitchIdentity("ha:switch.panel", "wall", "outlet-entry")
            assertFalse(f.source.toggleSwitch(sourceId, identity))
            assertTrue(f.source.state.switches.channels.isEmpty())
            assertTrue(f.services("turn_on").isEmpty())
            f.failFullRegistry = false
            f.scheduler.advance(5000)
            f.until { f.source.state.switches.resolve(sourceId, identity) != null }
            assertEquals(1, f.server.requestCount)
            assertEquals(1, f.received.count { it.optString("type") == "config/entity_registry/list_for_display" })
            assertTrue(f.source.toggleSwitch(sourceId, identity))
            f.until { f.services("turn_on").size == 1 }
        }
    }

    @Test fun registryRetryBacksOffAndStopsOnPermissionFailureOrBackendChange() {
        Fixture(switchFixture = true).use { f ->
            f.failFullRegistry = true
            f.start()
            f.until { f.source.connected }
            fun requests() = f.received.count { it.optString("type") == "config/entity_registry/list" }
            f.scheduler.advance(5000)
            f.until { requests() == 2 }
            // Drain the WebSocket failure response before moving the fake clock again.
            repeat(8) { Thread.sleep(10); f.scheduler.advance() }
            f.scheduler.advance(20_000)
            assertEquals(2, requests())
            f.fullRegistryError = "unauthorized"
            f.scheduler.advance(10_000)
            f.until { requests() == 3 }
            repeat(8) { Thread.sleep(10); f.scheduler.advance() }
            f.scheduler.advance(3_600_000)
            assertEquals(3, requests())
            assertTrue(f.source.state.switches.channels.isEmpty())

            f.peer.close(1000, "reconnect")
            f.until { !f.source.connected }
            f.fullRegistryError = "unavailable"
            f.enqueueSocket(); f.scheduler.advance(1000); f.until { f.source.connected }
            assertEquals(4, requests())
            f.source.configure(HaConnectionSettings(), null)
            f.scheduler.advance(3_600_000)
            assertEquals(4, requests())
            assertFalse(f.source.connected)
        }
    }
    @Test fun switchCommandsShareActualStateAndRejectUnverifiedBindings() {
        Fixture(switchFixture = true).use { f ->
            f.start()
            f.until { f.source.connected }
            val id = "ha:switch.panel"
            val sourceId = f.source.state.switches.sourceId
            val identity = f.source.state.switches.channels.getValue(id).identity
            val firstCard = mutableListOf<Boolean?>()
            val secondCard = mutableListOf<Boolean?>()
            f.source.addListener { firstCard += it.switches.resolve(sourceId, identity)?.isOn }
            f.source.addListener { secondCard += it.switches.resolve(sourceId, identity)?.isOn }
            assertTrue(f.source.toggleSwitch(sourceId, identity))
            assertFalse(f.source.activateEntity(id))
            f.until { f.services("turn_on").size == 1 }
            val command = f.services("turn_on").single()
            assertEquals("switch", command.getString("domain"))
            assertEquals("switch.panel", command.getJSONObject("target").getString("entity_id"))
            assertEquals(false, firstCard.last())
            assertEquals(false, secondCard.last())
            f.entity("switch.panel", "on")
            assertEquals(true, firstCard.last())
            assertEquals(firstCard, secondCard)
            assertTrue(f.source.homeState.entities.single { it.id == id }.isOn)
            assertTrue(f.source.activateEntity(id))
            f.until { f.services("turn_off").size == 1 }
            f.entity("switch.panel", "unavailable")
            assertFalse(f.source.toggleSwitch(sourceId, identity))
            f.entity("switch.panel", "on")
            f.peer.close(1000, "offline")
            f.until { !f.source.connected }
            assertEquals(Availability.STALE, f.source.state.switches.resolve(sourceId, identity)?.availability)
            assertFalse(f.source.toggleSwitch(sourceId, identity))
            f.enqueueSocket(); f.scheduler.advance(1000); f.until { f.source.connected }
            assertEquals(false, firstCard.last())
            assertFalse(f.source.toggleSwitch("another-source", identity))
            f.entity("switch.panel", null)
            assertNull(firstCard.last())
            assertFalse(f.source.toggleSwitch(sourceId, identity))
            f.peer.close(1000, "replace registry entry")
            f.until { !f.source.connected }
            f.switchEntryId = "replacement"
            f.enqueueSocket(); f.scheduler.advance(1000); f.until { f.source.connected }
            assertNull(firstCard.last())
            assertFalse(f.source.toggleSwitch(sourceId, identity))
            assertEquals(1, f.services("turn_on").size)
            assertEquals(1, f.services("turn_off").size)
        }
    }
    @Test fun nativeHandshakeExcludesHelperSnapshotsEventsAndWrites() {
        listOf(false, true).forEach { native ->
            Fixture(nativeControl = native).use { f ->
                val displays = mutableListOf<Int>()
                f.source.remoteBrightness = { displays += it }
                f.start(display = "input_number.display")
                f.until { if (native) f.source.nativeControlSupported == true else displays.isNotEmpty() }
                assertEquals(native, f.source.nativeControlSupported)
                assertEquals(if (native) ThemeMode.DARK else ThemeMode.LIGHT, f.appearance.state.themeMode)
                assertEquals(if (native) emptyList<Int>() else listOf(32), displays)
                f.entity("input_number.display", "80")
                assertEquals(if (native) emptyList<Int>() else listOf(32, 80), displays)
                f.source.requestDisplayBrightness(70)
                f.scheduler.advance(180)
                if (native) {
                    f.appearance.setTheme(ThemeMode.LIGHT)
                    assertEquals(ThemeMode.LIGHT, f.appearance.state.themeMode)
                    assertTrue(f.services("set_value").isEmpty())
                } else {
                    f.until { f.services("set_value").size == 1 }
                }
            }
        }
    }
    @Test fun temporaryProbeFailureEnablesLiveHelpersUntilNativeSessionTakesOver() {
        Fixture(nativeControl = true, failFirstProbe = true).use { f ->
            val displays = mutableListOf<Int>()
            f.source.remoteBrightness = { displays += it }
            f.start(display = "input_number.display")
            f.awaitAppearanceIdle()
            assertTrue(displays.isEmpty()) // Preserve local state during a transient fault.
            f.entity("input_number.display", "80")
            assertEquals(listOf(80), displays)
            f.scheduler.advance(1000)
            f.until { f.source.nativeControlSupported == true }
            f.entity("input_number.display", "81")
            assertEquals(listOf(80, 81), displays) // The probe alone does not take over.
            f.source.requestDisplayBrightness(67)
            f.source.enableLegacyControls(false) // Valid subscription, before first native report.
            f.scheduler.advance(180)
            f.entity("input_number.display", "82")
            assertEquals(listOf(80, 81), displays)
            assertTrue(f.services("set_value").isEmpty())
        }
    }
    @Test fun projectionsAreDemandDrivenAndTopologyReusesOneDashboardSnapshot() {
        Fixture().use { f ->
            f.start(); f.until { f.source.connected }; f.awaitAppearanceIdle()
            assertEquals(0, f.homes)
            var before = f.dashboards
            f.entity("switch.outlet", "off", JSONObject().put("friendly_name", "New outlet"))
            assertEquals(before + 1, f.dashboards)
            assertEquals(0, f.homes)
            f.entity("input_number.opacity", "60")
            assertEquals(.6f, f.appearance.state.cardSurfaceOpacity)
            assertEquals(0, f.homes)
            assertFalse(f.source.homeState.entities.single { it.id == "ha:switch.outlet" }.isOn)
            f.entity("switch.outlet", "on")
            assertTrue(f.source.homeState.entities.single { it.id == "ha:switch.outlet" }.isOn)
            val emitted = mutableListOf<com.dormpanel.app.home.HomeControlState>()
            val listener: (com.dormpanel.app.home.HomeControlState) -> Unit = { emitted += it }
            f.source.addHomeListener(listener)
            val homeBefore = f.homes
            before = f.dashboards
            f.entity("input_number.opacity", "70")
            assertEquals(before + 1, f.dashboards)
            assertEquals(homeBefore, f.homes)
            f.entity("switch.outlet", "off")
            assertEquals(homeBefore + 1, f.homes)
            assertFalse(emitted.last().entities.single { it.id == "ha:switch.outlet" }.isOn)
            assertEquals(2, emitted.size)
            f.source.removeHomeListener(listener)
            val detached = f.homes
            f.entity("switch.outlet", "on")
            assertEquals(detached, f.homes)
            f.peer.close(1000, "offline"); f.until { !f.source.connected }
            assertEquals(detached, f.homes)
            assertFalse(f.source.homeState.connected)
            assertTrue(f.source.homeState.entities.all { it.availability == Availability.STALE })
            f.enqueueSocket(); f.scheduler.advance(1000); f.until { f.source.connected }; f.awaitAppearanceIdle()
            assertTrue(f.source.homeState.connected)
            assertEquals(2, f.server.requestCount) // The same client opens one replacement socket.
            assertEquals(Availability.AVAILABLE, f.source.state.lights.getValue("ha:light.a").availability)
            assertEquals(Availability.AVAILABLE, f.source.homeState.entities.single { it.id == "ha:light.a" }.availability)
            assertTrue(f.source.state.weather.forecast.isNotEmpty())
            val commandsBefore = f.services("turn_off").size
            f.source.setLightPower("ha:light.a", false)
            f.until { f.services("turn_off").size == commandsBefore + 1 }
        }
    }

    @Test fun deviceHelpersApplyOnlyValidBoundUpdatesAndCoalesceLocalCommands() {
        Fixture().use { f ->
            val displays = mutableListOf<Int>()
            val volumes = mutableListOf<Int>()
            f.source.remoteBrightness = { displays += it }
            f.source.remoteVolume = { volumes += it }
            f.start(display = "input_number.display", volume = "input_number.volume")
            f.until { f.source.connected && displays.isNotEmpty() && volumes.isNotEmpty() }
            assertEquals(32, displays.last())
            assertEquals(45, volumes.last())
            f.entity("light.a", "off")
            assertEquals(listOf(32), displays)
            assertEquals(listOf(45), volumes)
            f.entity("input_number.display", "unavailable")
            f.entity("input_number.volume", "101")
            assertEquals(listOf(32), displays)
            assertEquals(listOf(45), volumes)
            f.entity("input_number.display", "80", JSONObject("""{"min":1,"max":100}"""))
            f.entity("input_number.volume", "0", JSONObject("""{"min":0,"max":100}"""))
            assertEquals(80, displays.last())
            assertEquals(0, volumes.last())
            repeat(50) { f.source.requestDisplayBrightness(it.coerceAtLeast(1)); f.source.requestMediaVolume(it) }
            f.scheduler.advance(180)
            f.until { f.services("set_value").count { it.optJSONObject("target")?.optString("entity_id") in listOf("input_number.display", "input_number.volume") } >= 2 }
            val sent = f.services("set_value").filter { it.optJSONObject("target")?.optString("entity_id") in listOf("input_number.display", "input_number.volume") }
            assertEquals(2, sent.size)
            assertEquals(setOf(49), sent.map { it.getJSONObject("service_data").getInt("value") }.toSet())
        }
    }

    @Test fun systemAndBlackoutHelpersUseBoundedAndDeterministicCommands() {
        Fixture().use { f ->
            val systemValues = mutableListOf<Int>()
            val blackoutValues = mutableListOf<Boolean>()
            f.source.remoteSystemBrightness = { systemValues += it }
            f.source.remoteBlackout = { blackoutValues += it }
            f.start(system = "input_number.system", blackout = "input_boolean.blackout")
            f.until { f.source.connected && blackoutValues.isNotEmpty() && systemValues.isNotEmpty() }
            assertEquals(listOf(40), systemValues)
            assertEquals(listOf(false), blackoutValues)
            f.entity("input_number.system", "60", JSONObject("""{"min":1,"max":100}"""))
            assertEquals(listOf(40, 60), systemValues)
            f.entity("input_number.system", "unavailable")
            f.entity("input_number.system", "101")
            assertEquals(listOf(40, 60), systemValues)
            f.entity("input_number.system", "40", JSONObject("""{"min":1,"max":100}"""))
            repeat(50) { f.source.requestSystemBrightness(it.coerceAtLeast(1)) }
            f.scheduler.advance(180)
            f.until { f.services("set_value").any { it.optJSONObject("target")?.optString("entity_id") == "input_number.system" } }
            val writes = f.services("set_value").filter { it.optJSONObject("target")?.optString("entity_id") == "input_number.system" }
            assertEquals(1, writes.size)
            assertEquals(49, writes.single().getJSONObject("service_data").getInt("value"))
            f.entity("input_boolean.blackout", "on")
            assertEquals(true, blackoutValues.last())
            f.source.requestBlackout(true)
            f.until { f.services("turn_on").any { it.optJSONObject("target")?.optString("entity_id") == "input_boolean.blackout" } }
            f.source.requestBlackout(false)
            f.until { f.services("turn_off").any { it.optJSONObject("target")?.optString("entity_id") == "input_boolean.blackout" } }
            f.entity("input_boolean.blackout", "unavailable")
            assertEquals(true, blackoutValues.last())
        }
    }

    @Test fun controlCenterBooleanHelpersApplySnapshotsEventsAndSendLocalCommands() {
        Fixture().use { f ->
            val automatic = mutableListOf<Boolean>()
            val follow = mutableListOf<Boolean>()
            val awake = mutableListOf<Boolean>()
            val boot = mutableListOf<Boolean>()
            f.source.remoteSystemAutomatic = { automatic += it }
            f.source.remoteFollowSystem = { follow += it }
            f.source.remoteKeepAwake = { awake += it }
            f.source.remoteStartAfterBoot = { boot += it }
            f.start(systemAutomatic = "input_boolean.automatic", followSystem = "input_boolean.follow",
                keepAwake = "input_boolean.awake", startAfterBoot = "input_boolean.boot")
            f.until { f.source.connected && automatic.isNotEmpty() && follow.isNotEmpty() && awake.isNotEmpty() && boot.isNotEmpty() }
            assertEquals(listOf(true), automatic)
            assertEquals(listOf(true), follow)
            assertEquals(listOf(true), awake)
            assertEquals(listOf(false), boot)
            f.entity("input_boolean.automatic", "off")
            f.entity("input_boolean.follow", "off")
            f.entity("input_boolean.awake", "off")
            f.entity("input_boolean.boot", "on")
            assertEquals(listOf(true, false), automatic)
            assertEquals(listOf(true, false), follow)
            assertEquals(listOf(true, false), awake)
            assertEquals(listOf(false, true), boot)
            f.entity("light.a", "off")
            assertEquals(listOf(true, false), automatic)
            assertEquals(2, follow.size)
            assertEquals(2, awake.size)
            assertEquals(2, boot.size)
            listOf(true, false).forEach { enabled ->
                f.source.requestSystemAutomatic(enabled)
                f.source.requestFollowSystem(enabled)
                f.source.requestKeepAwake(enabled)
                f.source.requestStartAfterBoot(enabled)
            }
            f.until {
                f.services("turn_on").count { it.optJSONObject("target")?.optString("entity_id")?.startsWith("input_boolean.") == true } >= 4 &&
                    f.services("turn_off").count { it.optJSONObject("target")?.optString("entity_id")?.startsWith("input_boolean.") == true } >= 4
            }
            fun servicesFor(entity: String, name: String) = f.services(name).filter { it.optJSONObject("target")?.optString("entity_id") == entity }
            assertEquals(1, servicesFor("input_boolean.automatic", "turn_on").size)
            assertEquals(1, servicesFor("input_boolean.automatic", "turn_off").size)
            assertEquals(1, servicesFor("input_boolean.follow", "turn_on").size)
            assertEquals(1, servicesFor("input_boolean.follow", "turn_off").size)
            assertEquals(1, servicesFor("input_boolean.awake", "turn_on").size)
            assertEquals(1, servicesFor("input_boolean.awake", "turn_off").size)
            assertEquals(1, servicesFor("input_boolean.boot", "turn_on").size)
            assertEquals(1, servicesFor("input_boolean.boot", "turn_off").size)
            // Remote application never re-emits a helper command.
            val sentBefore = f.services("turn_on").size + f.services("turn_off").size
            f.entity("input_boolean.automatic", "on")
            f.entity("input_boolean.follow", "on")
            f.entity("input_boolean.awake", "on")
            f.entity("input_boolean.boot", "off")
            assertEquals(sentBefore, f.services("turn_on").size + f.services("turn_off").size)
            assertEquals(listOf(true, false, true), automatic)
            assertEquals(listOf(true, false, true), follow)
            assertEquals(listOf(true, false, true), awake)
            assertEquals(listOf(false, true, false), boot)
        }
    }

    @Test fun snapshotAppliesAutomaticOffBeforeSystemBrightnessSoManualValueIsAccepted() {
        Fixture().use { f ->
            val order = mutableListOf<String>()
            // Mirrors DeviceControlController: system brightness is only accepted when Automatic is off.
            var automatic = true
            var acceptedSystemBrightness: Int? = null
            var followSystem = true
            var acceptedDisplayBrightness: Int? = null
            f.source.remoteSystemAutomatic = { enabled ->
                order += "auto:$enabled"
                automatic = enabled
            }
            f.source.remoteSystemBrightness = { percent ->
                order += "system:$percent"
                if (!automatic) acceptedSystemBrightness = percent
            }
            f.source.remoteFollowSystem = { enabled ->
                order += "follow:$enabled"
                followSystem = enabled
            }
            f.source.acceptBrightnessSnapshot = { !followSystem }
            f.source.remoteBrightness = { percent ->
                order += "display:$percent"
                if (!followSystem) acceptedDisplayBrightness = percent
            }
            // Snapshot: Automatic OFF, System brightness 40, Follow System OFF, DormPanel brightness 32.
            f.start(system = "input_number.system", systemAutomatic = "input_boolean.auto_off",
                followSystem = "input_boolean.follow", display = "input_number.display")
            // follow snapshot is "on" in the shared fixture; flip the dependency inputs via live helpers
            // only after asserting the automatic/system pair from the synchronized snapshot.
            f.until { f.source.connected && order.any { it.startsWith("auto:") } && order.any { it.startsWith("system:") } }
            val autoIndex = order.indexOfFirst { it.startsWith("auto:") }
            val systemIndex = order.indexOfFirst { it.startsWith("system:") }
            assertTrue("Automatic must be applied before System brightness: $order", autoIndex in 0 until systemIndex)
            assertEquals(false, automatic)
            assertEquals(40, acceptedSystemBrightness)
            // Follow System helper "on" keeps display brightness rejected while following.
            assertEquals(true, followSystem)
            assertNull(acceptedDisplayBrightness)
            // Live events stay event-driven and independent; order still matters only on full snapshots.
            val before = order.size
            f.entity("input_number.system", "55", JSONObject("""{"min":1,"max":100}"""))
            assertEquals("system:55", order[before])
            assertEquals(55, acceptedSystemBrightness)
        }
    }

    @Test fun controlCenterBooleanHelpersIgnoreInvalidAndUnconfiguredBindings() {
        Fixture().use { f ->
            val automatic = mutableListOf<Boolean>()
            val follow = mutableListOf<Boolean>()
            val awake = mutableListOf<Boolean>()
            val boot = mutableListOf<Boolean>()
            f.source.remoteSystemAutomatic = { automatic += it }
            f.source.remoteFollowSystem = { follow += it }
            f.source.remoteKeepAwake = { awake += it }
            f.source.remoteStartAfterBoot = { boot += it }
            f.start(systemAutomatic = "switch.not_a_boolean", followSystem = "input_boolean.missing",
                keepAwake = "", startAfterBoot = "input_boolean.boot")
            f.until { f.source.connected && boot.isNotEmpty() }
            assertTrue(automatic.isEmpty())
            assertTrue(follow.isEmpty())
            assertTrue(awake.isEmpty())
            assertEquals(listOf(false), boot)
            f.entity("switch.not_a_boolean", "on")
            f.entity("input_boolean.automatic", "off")
            f.entity("input_boolean.awake", "on")
            assertTrue(automatic.isEmpty())
            assertTrue(follow.isEmpty())
            assertTrue(awake.isEmpty())
            assertEquals(listOf(false), boot)
            f.source.requestSystemAutomatic(true)
            f.source.requestFollowSystem(true)
            f.source.requestKeepAwake(true)
            f.source.requestStartAfterBoot(true)
            f.until { f.services("turn_on").any { it.optJSONObject("target")?.optString("entity_id") == "input_boolean.boot" } }
            assertTrue(f.services("turn_on").none { it.optJSONObject("target")?.optString("entity_id") in listOf("switch.not_a_boolean", "input_boolean.missing", "input_boolean.automatic", "input_boolean.awake", "input_boolean.follow") })
            assertTrue(f.services("turn_off").isEmpty())
            f.entity("input_boolean.boot", "unavailable")
            f.entity("input_boolean.boot", "unknown")
            assertEquals(listOf(false), boot)
            f.entity("input_boolean.boot", "on")
            assertEquals(listOf(false, true), boot)
        }
    }

    @Test fun directHomeReadsUseRawLightsEvenBeforeInitializationPublishesDashboard() {
        Fixture().use { f ->
            f.source.store.snapshot(org.json.JSONArray("""[{"entity_id":"light.fresh","state":"on","attributes":{"brightness":255,"supported_color_modes":["brightness"]}}]"""))
            assertTrue(f.source.state.lights.isEmpty())
            val home = f.source.homeState
            assertEquals(100, home.entities.single().light!!.brightness)
            assertTrue(home.entities.single().isOn)
            assertFalse(home.connected)
        }
    }

    @Test fun observedRegistryAndServiceUpdatesReuseSnapshotsAndPublishFreshMetadata() {
        Fixture().use { f ->
            f.start(); f.until { f.source.connected }; f.awaitAppearanceIdle()
            f.entity("script.simple", "off")
            var latest = com.dormpanel.app.home.HomeControlState()
            f.source.addHomeListener { latest = it }
            assertTrue(latest.entities.single { it.id == "ha:script.simple" }.actionable)
            for (eventType in listOf("entity_registry_updated", "device_registry_updated", "area_registry_updated", "service_registered", "service_removed")) {
                f.areaName = "Updated bedroom"
                f.scriptRequiresInput = !f.scriptRequiresInput
                val before = f.dashboards
                val homeBefore = f.homes
                val subscription = f.received.single { it.optString("event_type") == eventType }.getInt("id")
                f.peer.send(JSONObject().put("id", subscription).put("type", "event").put("event",
                    JSONObject().put("event_type", eventType).put("data", JSONObject().put("domain", "script"))).toString())
                f.until { f.dashboards > before }
                assertEquals(before + 1, f.dashboards)
                assertEquals(homeBefore + 1, f.homes)
                if (eventType == "area_registry_updated") assertTrue(latest.areas.any { it.name == "Updated bedroom" })
                if (eventType.startsWith("service_")) {
                    assertEquals(!f.scriptRequiresInput, latest.entities.single { it.id == "ha:script.simple" }.actionable)
                }
            }
        }
    }

    @Test fun homeCommandsRegistryRefreshAndOfflineSafety() {
        Fixture().use { f ->
            f.start(); f.until { f.source.connected }; f.awaitAppearanceIdle()
            assertEquals(1, f.received.count { it.optString("type") == "config/device_registry/list" })
            assertEquals(1, f.received.count { it.optString("type") == "config/area_registry/list" })
            assertEquals("bedroom", f.source.homeState.entities.single { it.id == "ha:light.a" }.areaId)
            assertEquals("Lamp", f.source.homeState.devices.single().name)
            f.entity("switch.outlet", "off")
            assertTrue(f.source.activateEntity("ha:switch.outlet"))
            f.until { f.services("turn_on").any { it.optString("domain") == "switch" } }
            assertFalse(f.source.homeState.entities.single { it.id == "ha:switch.outlet" }.isOn)
            f.entity("switch.outlet", "on")
            assertTrue(f.source.activateEntity("ha:switch.outlet"))
            f.until { f.services("turn_off").any { it.optString("domain") == "switch" } }
            f.entity("scene.night", "2026-09-21")
            assertTrue(f.source.activateEntity("ha:scene.night"))
            f.until { f.services("turn_on").any { it.optString("domain") == "scene" } }
            f.entity("script.simple", "off")
            f.entity("script.simple", "on")
            assertTrue(f.source.activateEntity("ha:script.simple"))
            f.until { f.services("turn_on").any { it.optString("domain") == "script" } }
            f.entity("script.required", "off")
            assertFalse(f.source.activateEntity("ha:script.required"))
            assertEquals(1, f.received.count { it.optString("type") == "config/device_registry/list" })
            assertEquals(1, f.received.count { it.optString("type") == "config/area_registry/list" })
            f.areaName = "Renamed bedroom"
            for (kind in listOf("entity", "device", "area")) {
                val before = f.received.count { it.optString("type") == "config/${kind}_registry/list" }
                val subscription = f.received.single { it.optString("event_type") == "${kind}_registry_updated" }.getInt("id")
                f.peer.send(JSONObject().put("id", subscription).put("type", "event").put("event", JSONObject().put("event_type", "${kind}_registry_updated").put("data", JSONObject())).toString())
                f.until { f.received.count { it.optString("type") == "config/${kind}_registry/list" } == before + 1 }
            }
            f.until { f.source.homeState.areas.any { it.name == "Renamed bedroom" } }
            assertEquals(2, f.received.count { it.optString("type") == "config/device_registry/list" })
            assertEquals(2, f.received.count { it.optString("type") == "config/area_registry/list" })
            assertTrue(f.received.none { it.optString("service") == "toggle" })
            f.peer.close(1000, "offline"); f.until { !f.source.connected }
            assertEquals("on", f.source.homeState.entities.single { it.id == "ha:switch.outlet" }.value)
            assertEquals(Availability.STALE, f.source.homeState.entities.single { it.id == "ha:switch.outlet" }.availability)
            listOf("ha:switch.outlet", "ha:scene.night", "ha:script.simple").forEach { assertFalse(f.source.activateEntity(it)) }
        }
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
            assertEquals(2, f.received.count { it.optJSONObject("service_data")?.has("brightness_pct") == true })
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
    @Test fun offMemoryAndPendingKelvinUseOneCoherentOnCommand() {
        Fixture().use { f ->
            f.start(); f.until { f.source.connected }; f.awaitAppearanceIdle()
            val id = "ha:light.a"
            fun attributes(brightness: Int? = null, kelvin: Int? = null) = JSONObject()
                .put("supported_color_modes", org.json.JSONArray().put("color_temp"))
                .put("min_color_temp_kelvin", 2700).put("max_color_temp_kelvin", 6500)
                .apply { brightness?.let { put("brightness", it) }; kelvin?.let { put("color_temp_kelvin", it) } }
            f.entity("light.a", "on", attributes(166, 4000))
            assertEquals(65, f.source.state.lights.getValue(id).controlBrightness)
            f.entity("light.a", "off", attributes())
            val off = f.source.state.lights.getValue(id)
            assertNull(off.brightness); assertNull(off.colorTemperature)
            assertEquals(65, off.controlBrightness); assertEquals(4000, off.controlTemperature)
            var before = f.services("turn_on").size
            f.source.setColorTemperature(id, 5100); f.scheduler.advance(180)
            // Ordered service barrier verifies that no delayed light request was emitted.
            f.source.requestTheme(ThemeMode.DARK); f.until { f.services("select_option").isNotEmpty() }
            assertEquals(before, f.services("turn_on").size)
            assertFalse(f.source.state.lights.getValue(id).isOn)
            assertEquals(5100, f.source.homeState.entities.single { it.id == id }.light!!.controlTemperature)
            f.source.setLightPower(id, true)
            f.until { f.services("turn_on").size == before + 1 }
            var payload = f.services("turn_on").last().getJSONObject("service_data")
            assertEquals(65, payload.getInt("brightness_pct")); assertEquals(5100, payload.getInt("color_temp_kelvin"))
            before = f.services("turn_on").size
            f.source.setBrightness(id, 37); f.scheduler.advance(180)
            f.until { f.services("turn_on").size == before + 1 }
            payload = f.services("turn_on").last().getJSONObject("service_data")
            assertEquals(37, payload.getInt("brightness_pct")); assertEquals(5100, payload.getInt("color_temp_kelvin"))
            // A real resulting event, not a successful service response, establishes actual power.
            f.entity("light.a", "on", attributes(94, 5050))
            assertEquals(5050, f.source.state.lights.getValue(id).controlTemperature)
            before = f.services("turn_on").size
            f.source.setBrightness(id, 80); f.source.setColorTemperature(id, 4300)
            f.source.setLightPower(id, false); f.scheduler.advance(180)
            f.until { f.services("turn_off").isNotEmpty() }
            f.source.requestTheme(ThemeMode.LIGHT); f.until { f.services("select_option").size == 2 }
            assertEquals(before, f.services("turn_on").size)
            assertEquals(4300, f.source.state.lights.getValue(id).controlTemperature)
        }
    }

    @Test fun freshEventsUpdateMemoryWithoutRewritingUnsentSliderInput() {
        Fixture().use { f ->
            f.start(); f.until { f.source.connected }; f.awaitAppearanceIdle()
            val id = "ha:light.a"
            f.source.setBrightness(id, 76)
            f.source.setColorTemperature(id, 4000)
            // A previous request can finish while the next coalesced command is waiting.
            f.entity("light.a", "on", JSONObject("""{"brightness":128,"color_temp_kelvin":5100,"supported_color_modes":["color_temp"],"min_color_temp_kelvin":2700,"max_color_temp_kelvin":6500}"""))
            assertEquals(50, f.source.state.lights.getValue(id).controlBrightness)
            assertEquals(5100, f.source.state.lights.getValue(id).controlTemperature)
            f.scheduler.advance(180)
            f.until { f.services("turn_on").isNotEmpty() }
            val payload = f.services("turn_on").last().getJSONObject("service_data")
            assertEquals(76, payload.getInt("brightness_pct"))
            assertEquals(4000, payload.getInt("color_temp_kelvin"))
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
    @Test fun boundWeatherDistinguishesOfflineFromMissingEntityAndRecovers() = Fixture().use { f ->
        f.start()
        f.until { f.source.connected }
        val source = WeatherSourceRef.HomeAssistant("weather.home", "server")
        val provider = HaWeatherProvider(f.source, { "server" }, { "account" })
        val results = mutableListOf<WeatherResult>()
        val stop = provider.plan(source, ZoneOffset.UTC, WeatherQuery(WeatherBlock.CURRENT), Instant.EPOCH)
            .start { results += it }
        fun failure() = (results.last() as? WeatherResult.Failed)?.failure
        try {
            assertTrue(results.last() is WeatherResult.Data)
            f.peer.close(1000, "offline")
            f.until { failure()?.offline == true }
            assertEquals(WeatherState.ERROR, failure()?.state)
            f.enqueueSocket()
            f.scheduler.advance(1000)
            f.until { f.source.connected && results.last() is WeatherResult.Data }

            f.entity(source.entityId, "unavailable")
            assertEquals(WeatherState.ERROR, failure()?.state)
            f.entity(source.entityId, null)
            assertEquals(WeatherState.UNSUPPORTED, failure()?.state)
            assertEquals(false, failure()?.offline)
            f.entity(source.entityId, "sunny")
            assertTrue(results.last() is WeatherResult.Data)
        } finally {
            stop()
        }
    }

    @Test fun multipleWeatherFeedsShareSubscriptionsAndReconnectWithoutInterceptingStateEvents() {
        Fixture(modernWeather = true).use { f ->
            f.start()
            f.until { f.source.connected }
            f.awaitAppearanceIdle()
            val home = mutableListOf<Int>()
            val hourly = mutableListOf<Int>()
            val twice = mutableListOf<Int>()
            fun observe(entity: String, type: String, entries: MutableList<Int>) =
                f.source.forecasts.observe(entity, type) { value ->
                    value.forecast?.optJSONObject(0)?.let { entries += it.getInt("temperature") }
                }
            val consumers = List(5) { observe("weather.home", "daily", home) }
            val stopHourly = observe("weather.other", "hourly", hourly)
            val stopTwice = observe("weather.other", "twice_daily", twice)
            f.until { hourly.isNotEmpty() && twice.isNotEmpty() }
            assertEquals(List(5) { 75 }, home)
            assertEquals(listOf(12), hourly)
            assertEquals(listOf(7), twice)
            fun subscriptions() = f.received.count { it.optString("type") == "weather/subscribe_forecast" }
            assertEquals(3, subscriptions())
            f.state("off")
            f.until { f.source.store.entities["light.a"]?.value == "off" }
            stopTwice()
            f.until { f.received.any { it.optString("type") == "unsubscribe_events" } }
            val twiceId = f.received.single { it.optString("forecast_type") == "twice_daily" }.getInt("id")
            assertEquals(twiceId, f.received.last { it.optString("type") == "unsubscribe_events" }.getInt("subscription"))
            assertTrue(f.services("get_forecasts").isEmpty())
            f.peer.close(1000, "offline")
            f.until { !f.source.connected }
            f.enqueueSocket()
            f.scheduler.advance(1000)
            f.until { f.source.connected && hourly.size == 2 && home.size == 10 }
            assertEquals(5, subscriptions())
            assertEquals(listOf(7), twice)
            consumers.forEach { it() }
            stopHourly()
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
