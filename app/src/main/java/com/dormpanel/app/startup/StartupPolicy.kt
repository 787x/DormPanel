package com.dormpanel.app.startup

import android.content.Context

interface StartupStore {
    fun startAfterBoot(): Boolean
    fun setStartAfterBoot(enabled: Boolean): Boolean
}

class PreferencesStartupStore(context: Context) : StartupStore {
    private val preferences = context.applicationContext.getSharedPreferences("startup_policy", Context.MODE_PRIVATE)

    override fun startAfterBoot(): Boolean = preferences.getBoolean("start_after_boot", false)

    // The UI reports success only after the small preference write is durable.
    override fun setStartAfterBoot(enabled: Boolean): Boolean =
        preferences.edit().putBoolean("start_after_boot", enabled).commit()
}

class StartupPolicy(private val store: StartupStore) {
    val startAfterBoot: Boolean get() = store.startAfterBoot()

    fun setStartAfterBoot(enabled: Boolean): Boolean = store.setStartAfterBoot(enabled)
}
