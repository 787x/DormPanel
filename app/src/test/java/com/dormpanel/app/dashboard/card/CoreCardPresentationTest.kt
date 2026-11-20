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
        assertEquals(listOf("clock", WEATHER_CARD, COMPACT_WEATHER_CARD, CLASSIC_WEATHER_CARD, "sensor", "light", SWITCH_CARD, SWITCH_GROUP_CARD),
            registry.providers.map { it.typeKey })
        val compact = registry.provider(COMPACT_WEATHER_CARD)!!
        assertEquals(CardSize(2, 3), compact.defaultSize)
        assertEquals(listOf(CardSize(2, 3)), compact.sizePolicy.legalSizes(CardSize(8, 6)))
        assertFalse(compact.sizePolicy.hasAlternative(compact.defaultSize, CardSize(8, 6)))
        val classic = registry.provider("weather_classic")!!
        assertEquals(CardSize(4, 3), classic.defaultSize)
        for (rows in 1..3) for (columns in 2..4) assertTrue(classic.sizePolicy.allows(CardSize(columns, rows)))
        registry.providers.filter { it != compact }.forEach {
            assertTrue(it.sizePolicy.allows(it.defaultSize))
            assertTrue(it.sizePolicy.allows(CardSize(2, 1)))
            assertTrue(it.sizePolicy.allows(CardSize(2, 2)))
            assertTrue(it.sizePolicy.allows(CardSize(3, 3)))
            assertEquals(it.typeKey == SWITCH_CARD, it.sizePolicy.allows(CardSize(1, 1)))
        }
        assertTrue(registry.provider("light")!!.sizePolicy.allows(CardSize(4, 3)))
        assertEquals(CardSize(1, 1), registry.provider(SWITCH_CARD)!!.defaultSize)
        val grouped = registry.provider(SWITCH_GROUP_CARD)!!
        assertEquals(CardSize(2, 1), grouped.defaultSize)
        assertTrue(grouped.sizePolicy.allows(CardSize(1, 2)))
        assertEquals(CardSize(1, 2), grouped.sizePolicy.snap(CardSize(1, 1), CardSize(1, 3)))
    }
    @Test fun `clock layout uses both width and height`() {
        assertFalse(clockPresentation(CardSize(2, 1)).date)
        assertTrue(clockPresentation(CardSize(2, 2)).date)
        assertFalse(clockPresentation(CardSize(2, 3)).calendarDetail)
        assertTrue(clockPresentation(CardSize(4, 3)).calendarDetail)
        assertTrue(clockPresentation(CardSize(4, 3)).timeSp >= 100)
        assertEquals(clockPresentation(CardSize(4, 3)).timeSp, clockPresentation(CardSize(3, 3)).timeSp)
        assertTrue(clockPresentation(CardSize(4, 3)).timeSp > clockPresentation(CardSize(4, 1)).timeSp)
    }
    @Test fun `weather and climate change structure independently`() {
        assertEquals(0, weatherPresentation(CardSize(4, 1)).forecastColumns)
        assertEquals(1, weatherPresentation(CardSize(2, 2)).forecastColumns)
        assertTrue(weatherPresentation(CardSize(2, 3)).stackedCurrent)
        assertEquals(2, weatherPresentation(CardSize(2, 3)).forecastColumns)
        assertEquals(3, weatherPresentation(CardSize(2, 3), compactForecast = true).forecastColumns)
        assertEquals(0, classicWeatherPresentation(CardSize(2, 3)).forecastColumns)
        assertEquals(3, weatherPresentation(CardSize(4, 3)).forecastColumns)
        assertEquals(3, weatherPresentation(CardSize(3, 3)).forecastColumns)
        assertFalse(sensorPresentation(CardSize(2, 2)).sideBySide)
        assertTrue(sensorPresentation(CardSize(3, 2)).sideBySide)
        assertTrue(sensorPresentation(CardSize(3, 3)).status)
        assertTrue(sensorPresentation(CardSize(3, 3)).valueSp >= 60)
    }
    @Test fun `light controls require both sufficient area and device capability`() {
        val full = com.dormpanel.app.data.LightCapabilities(true, 2700..6500)
        assertFalse(lightPresentation(CardSize(2, 1), full).inlineBrightness)
        assertTrue(lightPresentation(CardSize(2, 3), full).inlineTemperature)
        assertTrue(lightPresentation(CardSize(2, 2), full).inlineBrightness)
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
