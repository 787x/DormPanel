package com.dormpanel.app.dashboard.card

import com.dormpanel.app.appearance.*
import com.dormpanel.app.data.FakeDashboardDataSource
import com.dormpanel.app.dashboard.model.CardSize
import org.junit.Assert.*
import org.junit.Test

class CoreCardPresentationTest {
    @Test fun `production providers have valid defaults and reachable densities`() {
        val appearance = AppearanceController(object : AppearanceStore {
            override fun read() = AppearanceState()
            override fun write(state: AppearanceState) = Unit
        })
        val registry = coreCardRegistry(FakeDashboardDataSource(), appearance)
        assertEquals(listOf("clock", "weather", "sensor", "light"), registry.providers.map { it.typeKey })
        registry.providers.forEach {
            assertTrue(it.sizePolicy.allows(it.defaultSize))
            assertTrue(it.sizePolicy.allows(CardSize(2, 1)))
            assertTrue(it.sizePolicy.allows(CardSize(2, 2)))
            assertTrue(it.sizePolicy.allows(CardSize(3, 3)))
            assertFalse(it.sizePolicy.allows(CardSize(1, 1)))
        }
        assertTrue(registry.provider("light")!!.sizePolicy.allows(CardSize(4, 3)))
    }
    @Test fun `clock layout uses both width and height`() {
        assertFalse(clockPresentation(CardSize(2, 1)).date)
        assertTrue(clockPresentation(CardSize(2, 2)).date)
        assertFalse(clockPresentation(CardSize(2, 3)).calendarDetail)
        assertTrue(clockPresentation(CardSize(4, 3)).calendarDetail)
        assertTrue(clockPresentation(CardSize(4, 3)).timeSp >= 100)
        assertTrue(clockPresentation(CardSize(4, 3)).timeSp > clockPresentation(CardSize(4, 1)).timeSp)
    }
    @Test fun `weather and climate change structure independently`() {
        assertEquals(0, weatherPresentation(CardSize(4, 1)).forecastColumns)
        assertFalse(weatherPresentation(CardSize(2, 2)).horizontal)
        assertEquals(3, weatherPresentation(CardSize(4, 3)).forecastColumns)
        assertEquals(2, weatherPresentation(CardSize(3, 3)).forecastColumns)
        assertFalse(sensorPresentation(CardSize(2, 2)).sideBySide)
        assertTrue(sensorPresentation(CardSize(3, 2)).sideBySide)
        assertTrue(sensorPresentation(CardSize(3, 3)).status)
        assertTrue(sensorPresentation(CardSize(3, 3)).valueSp >= 60)
    }
    @Test fun `light controls require both sufficient area and device capability`() {
        val full = com.dormpanel.app.data.LightCapabilities(true, 2700..6500)
        assertFalse(lightPresentation(CardSize(2, 1), full).inlineBrightness)
        assertFalse(lightPresentation(CardSize(2, 3), full).inlineTemperature)
        assertTrue(lightPresentation(CardSize(3, 2), full).inlineBrightness)
        assertFalse(lightPresentation(CardSize(3, 2), full).inlineTemperature)
        assertTrue(lightPresentation(CardSize(3, 3), full).inlineTemperature)
        assertTrue(lightPresentation(CardSize(4, 3), full).inlineTemperature)
        assertFalse(lightPresentation(CardSize(4, 3), com.dormpanel.app.data.LightCapabilities()).inlineBrightness)
        assertFalse(lightPresentation(CardSize(4, 3), full.copy(colorTemperature = null)).inlineTemperature)
    }
    @Test fun `clock schedules next minute boundary rather than each second`() {
        assertEquals(60000L, millisUntilNextMinute(120000L))
        assertEquals(1L, millisUntilNextMinute(179999L))
        assertEquals(30000L, millisUntilNextMinute(150000L))
    }
}
