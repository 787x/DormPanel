package com.dormpanel.app.ha

import com.dormpanel.app.data.*
import org.junit.Assert.*
import org.junit.Test

class LightControlMemoryTest {
    private val light = LightState("ha:light.a", "A", true, LightCapabilities(true, 2700..6500), 65, 4000)
    @Test fun rawOffStateRemainsUnknownWhileMemoryAndFreshStateWinInOrder() {
        val memory = LightControlMemory()
        memory.observe(light)
        val off = light.copy(isOn = false, brightness = null, colorTemperature = null)
        memory.observe(off)
        val projected = memory.project(off)
        assertNull(projected.brightness); assertNull(projected.colorTemperature)
        assertEquals(65, projected.controlBrightness); assertEquals(4000, projected.controlTemperature)
        memory.remember(light.id, kelvin = 5000)
        assertEquals(5000, memory.project(off).controlTemperature)
        memory.observe(light.copy(brightness = 80, colorTemperature = 4200))
        assertEquals(LightControlValues(80, 4200), memory.get(light.id))
        assertNull(memory.project(off.copy(id = "ha:light.b")).controlBrightness)
        assertNull(memory.project(off.copy(id = "ha:light.b")).controlTemperature)
    }
    @Test fun persistenceIsIsolatedByNormalizedEndpointAndEntity() {
        val values = mutableMapOf<Pair<String, String>, LightControlValues>()
        val store = object : LightMemoryStore {
            override fun read(endpoint: String, id: String) = values[endpoint to id] ?: LightControlValues()
            override fun write(endpoint: String, id: String, value: LightControlValues) { values[endpoint to id] = value }
        }
        val first = LightControlMemory(store)
        first.scope("http://one:8123/"); first.observe(light)
        val restarted = LightControlMemory(store)
        restarted.scope("http://one:8123")
        assertEquals(65, restarted.get(light.id).brightness)
        restarted.scope("http://two:8123")
        assertNull(restarted.get(light.id).brightness)
        restarted.scope("http://one:8123")
        assertEquals(4000, restarted.get(light.id).kelvin)
        assertNull(restarted.get("ha:light.b").kelvin)
    }
    @Test fun numericValidationAndFakeOffIntent() {
        listOf("", "0", "101", "65.5", "abc", "999999999999").forEach { assertNull(parseLightControlNumber(it, 1..100)) }
        assertEquals(65, parseLightControlNumber("65", 1..100))
        assertNull(parseLightControlNumber("2699", 2700..6500))
        assertEquals(6500, parseLightControlNumber("6500", 2700..6500))
        val fake = FakeDashboardDataSource()
        fake.setLightPower("desk", false); fake.setColorTemperature("desk", 5100)
        assertFalse(fake.state.lights.getValue("desk").isOn)
        fake.setLightPower("desk", true)
        assertEquals(5100, fake.state.lights.getValue("desk").controlTemperature)
        fake.setLightPower("desk", false); fake.setBrightness("desk", 37)
        assertTrue(fake.state.lights.getValue("desk").isOn)
        assertEquals(37, fake.state.lights.getValue("desk").controlBrightness)
        assertEquals(5100, fake.state.lights.getValue("desk").controlTemperature)
    }
}
