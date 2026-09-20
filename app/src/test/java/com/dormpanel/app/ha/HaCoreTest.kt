package com.dormpanel.app.ha

import com.dormpanel.app.data.Availability
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class HaCoreTest {
    @Test fun endpoints() {
        assertEquals("http://home.local:8123", HaEndpoint.parse(" home.local:8123/api/ ").base)
        assertEquals("ws://home.local:8123/api/websocket", HaEndpoint.parse("http://home.local:8123/").websocket)
        assertEquals("wss://ha.example/prefix/api/websocket", HaEndpoint.parse("https://ha.example/prefix/api/websocket").websocket)
        assertEquals("https://ha.example/api/", HaEndpoint.parse("wss://ha.example/api/websocket").api)
        listOf("https://user:password@ha.test", "https://ha.test/?token=secret", "ftp://ha.test").forEach { assertTrue(runCatching { HaEndpoint.parse(it) }.isFailure) }
    }
    @Test fun authenticatedTokenEncryptionAndFailures() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = TokenCipher { key }
        val first = cipher.encrypt("secret-token")
        assertFalse(first.contains("secret-token")); assertEquals("secret-token", cipher.decrypt(first))
        assertNotEquals(first, cipher.encrypt("secret-token"))
        assertNull(cipher.decrypt(first.dropLast(3) + "AAA")); assertNull(cipher.decrypt("broken"))
        assertNull(TokenCipher { error("invalidated key") }.decrypt(first))
    }
    private fun entity(id: String, value: String, attributes: String) = JSONObject("""{"entity_id":"$id","state":"$value","attributes":$attributes}""")
    @Test fun lightAndIncrementalStateMapping() {
        val store = HaEntityStore()
        store.snapshot(JSONArray().put(entity("light.desk", "on", """{"supported_color_modes":["color_temp"],"brightness":128,"color_temp_kelvin":3200,"min_color_temp_kelvin":2700,"max_color_temp_kelvin":6500}""")))
        val state = store.normalized(true, "").lights.getValue("ha:light.desk")
        assertEquals(50, state.brightness); assertTrue(state.capabilities.brightness)
        assertEquals(2700..6500, state.capabilities.colorTemperature); assertEquals(3200, state.colorTemperature)
        assertEquals(state.copy(availability = Availability.STALE), store.normalized(false, "").lights.getValue(state.id))
        store.event(JSONObject().put("entity_id", "light.desk").put("new_state", entity("light.desk", "off", """{"supported_color_modes":["onoff"]}""")))
        assertFalse(store.normalized(true, "").lights.getValue(state.id).isOn)
        assertFalse(store.normalized(true, "").lights.getValue(state.id).capabilities.brightness)
        store.event(JSONObject().put("entity_id", "light.desk").put("new_state", JSONObject.NULL))
        assertTrue(store.normalized(true, "").lights.isEmpty())
    }
    @Test fun sensorsPairOnlyUnambiguousDeviceMetadataAndPreserveUnits() {
        val store = HaEntityStore()
        store.snapshot(JSONArray().put(entity("sensor.a", "72.5", """{"device_class":"temperature","unit_of_measurement":"°F"}"""))
            .put(entity("sensor.b", "45", """{"device_class":"humidity","unit_of_measurement":"%"}"""))
            .put(entity("sensor.temperature_name_only", "20", "{}")))
        assertEquals(2, store.sensorGroups().size)
        store.registry(JSONArray("""[{"entity_id":"sensor.a","device_id":"device"},{"entity_id":"sensor.b","device_id":"device"}]"""))
        val paired = store.normalized(true, "").sensors.values.single()
        assertEquals("ha:device:device", paired.id); assertEquals("°F", paired.temperatureUnit); assertEquals(45, paired.humidity)
        store.entities["sensor.c"] = HaEntity.parse(entity("sensor.c", "unavailable", """{"device_class":"temperature"}"""))
        store.registry(JSONArray("""[{"entity_id":"sensor.a","device_id":"device"},{"entity_id":"sensor.b","device_id":"device"},{"entity_id":"sensor.c","device_id":"device"}]"""))
        assertEquals(3, store.sensorGroups().size)
        assertEquals(Availability.UNAVAILABLE, store.normalized(true, "").sensors.getValue("ha:sensor.c").availability)
        store.entities["sensor.a"] = HaEntity.parse(entity("sensor.a", "unknown", """{"device_class":"temperature"}"""))
        assertNull(store.normalized(true, "").sensors.getValue("ha:sensor.a").temperature)
    }
    @Test fun registryUnknownFieldsDisabledAndCompactDisplay() {
        val store = HaEntityStore()
        store.snapshot(JSONArray().put(entity("light.a", "off", "{}")))
        store.registry(JSONObject("""{"entities":[{"ei":"light.a","di":"device","en":"Desk","future":42}]}"""))
        assertEquals("Desk", store.normalized(true, "").lights.values.single().name)
        store.registry(JSONArray("""[{"entity_id":"light.a","disabled_by":"user","unexpected":{}}]"""))
        assertTrue(store.normalized(true, "").lights.isEmpty())
    }
    @Test fun forecastResponse() {
        val result = JSONObject("""{"response":{"weather.home":{"forecast":[{"datetime":"2026-09-20T00:00:00+00:00","condition":"sunny","temperature":29,"templow":20},{"datetime":"bad","temperature":30}]}}}""")
        val forecast = HaEntityStore.forecast(result, "weather.home")
        assertEquals(1, forecast.size); assertEquals(29, forecast.single().high); assertEquals(20, forecast.single().low)
    }
    @Test fun coalescingFlushesTailAndKeepsDevicesIndependent() {
        val scheduler = ManualScheduler()
        val sent = mutableListOf<Triple<String, String, Int>>()
        val commands = LatestCommands(scheduler) { entity, property, value -> sent += Triple(entity, property, value) }
        repeat(100) { commands.put("a", "brightness_pct", it) }
        commands.put("b", "brightness_pct", 42); commands.put("a", "color_temp_kelvin", 3000)
        assertTrue(sent.isEmpty()); scheduler.advance(180)
        assertEquals(3, sent.size); assertTrue(Triple("a", "brightness_pct", 99) in sent)
        commands.put("a", "brightness_pct", 100); scheduler.advance(180)
        assertEquals(100, sent.last().third)
        commands.put("a", "brightness_pct", 2); commands.clear(); scheduler.advance(180); assertEquals(4, sent.size)
    }
    @Test fun explicitSwitchCancelsOlderSliderIntentOnlyForThatDevice() {
        val scheduler = ManualScheduler()
        val sent = mutableListOf<String>()
        val commands = LatestCommands(scheduler) { entity, _, _ -> sent += entity }
        commands.put("a", "brightness_pct", 80); commands.put("b", "brightness_pct", 40)
        commands.cancel("a"); scheduler.advance(180)
        assertEquals(listOf("b"), sent)
    }
    @Test fun backoffIsBounded() { assertEquals(listOf(1000L, 2000L, 5000L, 10000L, 30000L, 30000L), (0..5).map(::reconnectDelay)) }
}

class ManualScheduler : HaScheduler {
    private data class Entry(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
    private val entries = mutableListOf<Entry>()
    private var now = 0L
    override fun execute(action: () -> Unit) { synchronized(entries) { entries += Entry(now, action) } }
    override fun after(delayMillis: Long, action: () -> Unit): () -> Unit {
        val entry = Entry(now + delayMillis, action); synchronized(entries) { entries += entry }
        return { entry.cancelled = true }
    }
    fun advance(millis: Long = 0) {
        now += millis
        while (true) {
            val entry = synchronized(entries) { entries.firstOrNull { it.at <= now }?.also { entries.remove(it) } } ?: break
            if (!entry.cancelled) entry.action()
        }
    }
}
