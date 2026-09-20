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
        assertEquals(CardDensity.COMPACT, cardDensity(CardSize(4, 1)))
        assertEquals(CardDensity.STANDARD, cardDensity(CardSize(2, 3)))
        assertEquals(CardDensity.EXPANDED, cardDensity(CardSize(3, 3)))
    }
    @Test fun `clock schedules next minute boundary rather than each second`() {
        assertEquals(60000L, millisUntilNextMinute(120000L))
        assertEquals(1L, millisUntilNextMinute(179999L))
        assertEquals(30000L, millisUntilNextMinute(150000L))
    }
}
