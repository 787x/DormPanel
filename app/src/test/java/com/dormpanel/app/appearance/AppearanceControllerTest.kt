package com.dormpanel.app.appearance

import org.junit.Assert.*
import org.junit.Test

class AppearanceControllerTest {
    private class MemoryStore(var saved: AppearanceState = AppearanceState()) : AppearanceStore {
        var writes = 0
        override fun read() = saved
        override fun write(state: AppearanceState) { saved = state; writes++ }
    }
    @Test fun `theme and opacity persist and restore with synchronous notification`() {
        val store = MemoryStore()
        val controller = AppearanceController(store)
        val seen = mutableListOf<AppearanceState>()
        val listener: (AppearanceState) -> Unit = { seen += it }
        controller.addListener(listener)
        controller.setTheme(ThemeMode.LIGHT)
        controller.setCardOpacity(0.25f)
        assertEquals(3, seen.size)
        assertEquals(controller.state, seen.last())
        assertEquals(controller.state, AppearanceController(store).state)
        controller.setCardOpacity(0.25f)
        assertEquals(2, store.writes)
        controller.removeListener(listener)
        controller.setTheme(ThemeMode.DARK)
        assertEquals(3, seen.size)
    }
    @Test fun `opacity commands and restored values are bounded including non finite values`() {
        val store = MemoryStore(AppearanceState(cardSurfaceOpacity = -2f))
        val controller = AppearanceController(store)
        assertEquals(0f, controller.state.cardSurfaceOpacity)
        controller.setCardOpacity(3f)
        assertEquals(1f, controller.state.cardSurfaceOpacity)
        controller.setCardOpacity(-1f)
        assertEquals(0f, controller.state.cardSurfaceOpacity)
        controller.setCardOpacity(Float.NaN)
        assertEquals(1f, controller.state.cardSurfaceOpacity)
        controller.setCardOpacity(Float.POSITIVE_INFINITY)
        assertEquals(1f, controller.state.cardSurfaceOpacity)
    }
}
