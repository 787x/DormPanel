package com.dormpanel.app.dashboard.catalog

import com.dormpanel.app.data.*
import org.junit.Assert.*
import org.junit.Test

class CardCatalogTest {
    private val labels = CatalogLabels("Clock", "Local", "Weather", "Sample", "Climate", "Switch", "Dimmer", "Warmth")
    @Test fun `catalog exposes static information and actual individual source devices`() {
        val catalog = SourceCardCatalog(FakeDashboardDataSource(), labels)
        assertEquals(2, catalog.candidates.count { it.category == CardCategory.INFORMATION })
        assertEquals(6, catalog.candidates.count { it.category == CardCategory.HOME })
        assertEquals("{\"lightId\":\"bedside\"}", catalog.candidates.first { it.displayName == "Bedside light" }.configurationJson)
        assertEquals("{\"lightId\":\"ceiling\"}", catalog.candidates.first { it.displayName == "Ceiling light" }.configurationJson)
        assertEquals("{\"sensorId\":\"desk\"}", catalog.candidates.first { it.displayName == "Desk climate" }.configurationJson)
        assertEquals(8, catalog.candidates.map { it.candidateId }.distinct().size)
    }
    @Test fun `discovery updates catalog but ordinary state changes do not rebuild picker`() {
        val source = MutableSource()
        val catalog = SourceCardCatalog(source, labels)
        val seen = mutableListOf<List<CardAddCandidate>>()
        val listener: (List<CardAddCandidate>) -> Unit = { seen += it }
        catalog.addListener(listener)
        source.publish(source.state.copy(lights = source.state.lights.mapValues { (_, light) -> light.copy(isOn = !light.isOn) }))
        assertEquals(1, seen.size)
        val unusualId = "new\"\\\n"
        source.publish(source.state.copy(sensors = mapOf(unusualId to SensorState(unusualId, "New sensor", 20.0, 40))))
        assertEquals(2, seen.size)
        assertTrue(seen.last().any { it.displayName == "New sensor" })
        assertFalse(seen.last().any { it.displayName == "Room climate" })
        assertEquals("{\"sensorId\":\"new\\\"\\\\\\u000a\"}", seen.last().first { it.displayName == "New sensor" }.configurationJson)
        catalog.removeListener(listener)
        source.publish(source.state.copy(sensors = emptyMap()))
        assertEquals(2, seen.size)
        assertTrue(source.listeners.isEmpty())
    }
    private class MutableSource : DashboardDataSource {
        override var state = FakeDashboardDataSource().state
        val listeners = mutableSetOf<(DashboardData) -> Unit>()
        fun publish(next: DashboardData) { state = next; listeners.toList().forEach { it(next) } }
        override fun addListener(listener: (DashboardData) -> Unit) { listeners += listener; listener(state) }
        override fun removeListener(listener: (DashboardData) -> Unit) { listeners -= listener }
        override fun toggleLight(id: String) = Unit
        override fun setBrightness(id: String, percent: Int) = Unit
        override fun setColorTemperature(id: String, kelvin: Int) = Unit
    }
}
