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

/** Owner of the durable next-boot preference. HA is an optional mirror, never a second store. */
class StartupPolicy(private val store: StartupStore) {
    val startAfterBoot: Boolean get() = store.startAfterBoot()
    var startAfterBootCommand: ((Boolean) -> Unit)? = null
    private val listeners = linkedSetOf<(Boolean) -> Unit>()

    fun addListener(listener: (Boolean) -> Unit) {
        if (!listeners.add(listener)) return
        listener(store.startAfterBoot())
    }
    fun removeListener(listener: (Boolean) -> Unit) { listeners -= listener }

    fun setStartAfterBoot(enabled: Boolean, remote: Boolean = false): Boolean {
        val previous = store.startAfterBoot()
        if (!store.setStartAfterBoot(enabled)) return false
        if (previous != enabled) {
            listeners.toList().forEach { it(enabled) }
            if (!remote) startAfterBootCommand?.invoke(enabled)
        }
        return true
    }
}
