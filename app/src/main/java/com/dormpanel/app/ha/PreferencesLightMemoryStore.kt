package com.dormpanel.app.ha

import android.content.Context
import com.dormpanel.app.data.LightControlValues
import java.security.MessageDigest

/** Contains only non-secret integers. Hash endpoint + entity to avoid key collisions. */
class PreferencesLightMemoryStore(context: Context) : LightMemoryStore {
    private val prefs = context.getSharedPreferences("light_control_memory", Context.MODE_PRIVATE)
    private fun key(endpoint: String, id: String) = MessageDigest.getInstance("SHA-256")
        .digest("$endpoint\n$id".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    override fun read(endpoint: String, id: String): LightControlValues {
        val key = key(endpoint, id)
        return LightControlValues(prefs.getInt("$key.b", 0).takeIf { it in 1..100 }, prefs.getInt("$key.k", 0).takeIf { it > 0 })
    }
    override fun write(endpoint: String, id: String, value: LightControlValues) {
        val key = key(endpoint, id)
        prefs.edit().putInt("$key.b", value.brightness ?: 0).putInt("$key.k", value.kelvin ?: 0).apply()
    }
}
