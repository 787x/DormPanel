package com.dormpanel.app.appearance

import android.content.Context

class PreferencesAppearanceStore(context: Context) : AppearanceStore {
    private val preferences = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    override fun read(): AppearanceState = try {
        AppearanceState(
            ThemeMode.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: ThemeMode.DARK,
            preferences.getFloat("card_opacity", 1f),
        )
    } catch (_: ClassCastException) { AppearanceState() }

    override fun write(state: AppearanceState) {
        preferences.edit().putString("theme", state.themeMode.name)
            .putFloat("card_opacity", state.cardSurfaceOpacity).apply()
    }
}
