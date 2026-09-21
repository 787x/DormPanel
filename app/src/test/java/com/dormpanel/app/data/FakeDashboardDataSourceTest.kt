package com.dormpanel.app.data

import org.junit.Assert.*
import org.junit.Test

class FakeDashboardDataSourceTest {
    @Test fun `toggle notifies subscribers with immutable authoritative state`() {
        val source = FakeDashboardDataSource()
        val initial = source.state
        val seen = mutableListOf<DashboardData>()
        val listener: (DashboardData) -> Unit = { seen += it }
        source.addListener(listener)
        source.toggleLight("desk")
        assertFalse(source.state.lights.getValue("desk").isOn)
        assertTrue(initial.lights.getValue("desk").isOn)
        assertEquals(source.state, seen.last())
        source.removeListener(listener)
        source.toggleLight("desk")
        assertEquals(2, seen.size)
        assertEquals(initial, source.state)
    }
    @Test fun `brightness and kelvin commands obey bounds and capabilities`() {
        val source = FakeDashboardDataSource()
        source.setBrightness("desk", -50)
        assertEquals(1, source.state.lights.getValue("desk").brightness)
        source.setBrightness("desk", 0)
        assertEquals(1, source.state.lights.getValue("desk").brightness)
        assertTrue(source.state.lights.getValue("desk").isOn)
        source.toggleLight("desk")
        assertFalse(source.state.lights.getValue("desk").isOn)
        source.setBrightness("desk", 1)
        assertTrue(source.state.lights.getValue("desk").isOn)
        source.setBrightness("desk", 150)
        assertEquals(100, source.state.lights.getValue("desk").brightness)
        source.setColorTemperature("desk", 1)
        assertEquals(2700, source.state.lights.getValue("desk").colorTemperature)
        source.setColorTemperature("desk", 10000)
        assertEquals(6500, source.state.lights.getValue("desk").colorTemperature)
        val before = source.state
        source.setColorTemperature("bedside", 6000)
        source.setBrightness("ceiling", 10)
        source.setColorTemperature("ceiling", 6000)
        source.toggleLight("missing")
        assertEquals(before, source.state)
        source.setBrightness("bedside", 15)
        assertEquals(15, source.state.lights.getValue("bedside").brightness)
        assertTrue(source.state.lights.getValue("bedside").isOn)
    }
}
