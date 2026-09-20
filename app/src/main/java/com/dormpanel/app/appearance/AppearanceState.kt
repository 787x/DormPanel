package com.dormpanel.app.appearance

enum class ThemeMode { LIGHT, DARK }

data class AppearanceState(val themeMode: ThemeMode = ThemeMode.DARK, val cardSurfaceOpacity: Float = 1f)

interface AppearanceStore {
    fun read(): AppearanceState
    fun write(state: AppearanceState)
}

/** Local or future external appearance commands must pass through this single observable owner. */
class AppearanceController(private val store: AppearanceStore) {
    var state = normalize(store.read())
        private set
    private val listeners = linkedSetOf<(AppearanceState) -> Unit>()

    fun addListener(listener: (AppearanceState) -> Unit) { listeners += listener; listener(state) }
    fun removeListener(listener: (AppearanceState) -> Unit) { listeners -= listener }
    fun setTheme(mode: ThemeMode) = update(state.copy(themeMode = mode))
    fun setCardOpacity(opacity: Float) = update(state.copy(cardSurfaceOpacity = opacity))
    fun update(value: AppearanceState) {
        val next = normalize(value)
        if (next == state) return
        state = next
        store.write(next)
        listeners.toList().forEach { it(next) }
    }

    private fun normalize(value: AppearanceState) = value.copy(
        cardSurfaceOpacity = if (value.cardSurfaceOpacity.isFinite()) value.cardSurfaceOpacity.coerceIn(0f, 1f) else 1f,
    )
}
