package com.dormpanel.app.data

enum class Availability { AVAILABLE, UNAVAILABLE, STALE }

/** Forecast timestamps and typed values can map directly from a future weather service. */
data class WeatherForecast(val timeEpochMillis: Long, val condition: String, val low: Int, val high: Int)

data class WeatherState(
    val condition: String, val temperature: Int, val humidity: Int,
    val low: Int, val high: Int, val forecast: List<WeatherForecast>,
    val availability: Availability = Availability.AVAILABLE,
)

data class SensorState(
    val id: String, val name: String, val temperature: Double?, val humidity: Int?,
    val availability: Availability = Availability.AVAILABLE,
)

data class LightCapabilities(
    val brightness: Boolean = false,
    val colorTemperature: IntRange? = null,
) {
    init { require(colorTemperature == null || (colorTemperature.first > 0 && !colorTemperature.isEmpty())) }
}

data class LightState(
    val id: String, val name: String, val isOn: Boolean,
    val capabilities: LightCapabilities = LightCapabilities(),
    val brightness: Int = 100, val colorTemperature: Int = 4000,
    val availability: Availability = Availability.AVAILABLE,
)

data class DashboardData(
    val weather: WeatherState,
    val sensors: Map<String, SensorState>,
    val lights: Map<String, LightState>,
)

/** Single state owner. Implementations deliver immutable snapshots and callbacks on the main thread.
 * Subscribe sends the current snapshot immediately. Commands do not imply optimistic UI state.
 */
interface DashboardDataSource {
    val state: DashboardData
    fun addListener(listener: (DashboardData) -> Unit)
    fun removeListener(listener: (DashboardData) -> Unit)
    fun toggleLight(id: String)
    fun setBrightness(id: String, percent: Int)
    fun setColorTemperature(id: String, kelvin: Int)
}

class FakeDashboardDataSource : DashboardDataSource {
    override var state = DashboardData(
        WeatherState("Partly cloudy", 24, 58, 19, 27, listOf(
            WeatherForecast(1790035200000L, "Clear", 20, 26),
            WeatherForecast(1790121600000L, "Cloudy", 19, 25),
            WeatherForecast(1790208000000L, "Light rain", 18, 23),
        )),
        listOf(
            SensorState("room", "Room climate", 23.6, 54),
            SensorState("desk", "Desk climate", 24.1, 51, Availability.STALE),
            SensorState("balcony", "Balcony climate", null, null, Availability.UNAVAILABLE),
        ).associateBy { it.id },
        listOf(
            LightState("desk", "Desk light", true, LightCapabilities(true, 2700..6500), 65, 4000),
            LightState("bedside", "Bedside light", false, LightCapabilities(true), 40),
            LightState("ceiling", "Ceiling light", false),
        ).associateBy { it.id },
    )
        private set
    private val listeners = linkedSetOf<(DashboardData) -> Unit>()

    override fun addListener(listener: (DashboardData) -> Unit) { listeners += listener; listener(state) }
    override fun removeListener(listener: (DashboardData) -> Unit) { listeners -= listener }
    override fun toggleLight(id: String) = update(id) { it.copy(isOn = !it.isOn) }
    override fun setBrightness(id: String, percent: Int) = update(id) {
        if (it.capabilities.brightness) it.copy(brightness = percent.coerceIn(0, 100)) else it
    }
    override fun setColorTemperature(id: String, kelvin: Int) = update(id) {
        it.capabilities.colorTemperature?.let { range -> it.copy(colorTemperature = kelvin.coerceIn(range)) } ?: it
    }

    private fun update(id: String, transform: (LightState) -> LightState) {
        val previous = state.lights[id] ?: return
        if (previous.availability != Availability.AVAILABLE) return
        val next = transform(previous)
        if (next == previous) return
        state = state.copy(lights = state.lights + (id to next))
        listeners.toList().forEach { it(state) }
    }
}
