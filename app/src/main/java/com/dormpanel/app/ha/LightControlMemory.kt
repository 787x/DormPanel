package com.dormpanel.app.ha

import com.dormpanel.app.data.*

interface LightMemoryStore {
    fun read(endpoint: String, id: String): LightControlValues
    fun write(endpoint: String, id: String, value: LightControlValues)
}

/** Main-thread control intent, separate from raw HA entities. Observe only fresh ingress,
 * never re-observe an old projection while rendering or publishing user intent. */
class LightControlMemory(private val persistence: LightMemoryStore? = null) {
    private var endpoint = ""
    private val values = mutableMapOf<String, LightControlValues>()
    fun scope(base: String) {
        val normalized = if (base.isBlank()) "" else runCatching { HaEndpoint.parse(base).base }.getOrDefault(base)
        if (endpoint != normalized) { endpoint = normalized; values.clear() }
    }
    fun get(id: String): LightControlValues = values.getOrPut(id) { persistence?.read(endpoint, id) ?: LightControlValues() }
    fun remember(id: String, brightness: Int? = null, kelvin: Int? = null) {
        val old = get(id)
        val next = LightControlValues(brightness ?: old.brightness, kelvin ?: old.kelvin)
        if (old == next) return
        values[id] = next
        persistence?.write(endpoint, id, next)
    }
    fun observe(light: LightState) {
        if (light.availability != Availability.AVAILABLE) return
        remember(light.id, light.brightness?.takeIf { it in 1..100 },
            light.colorTemperature?.takeIf { it in (light.capabilities.colorTemperature ?: IntRange.EMPTY) })
    }
    fun project(light: LightState): LightState {
        val remembered = get(light.id)
        return light.copy(controlValues = LightControlValues(
            remembered.brightness?.takeIf { light.capabilities.brightness && it in 1..100 },
            remembered.kelvin?.takeIf { it in (light.capabilities.colorTemperature ?: IntRange.EMPTY) },
        ))
    }
}
