package com.dormpanel.app.data

enum class Availability { AVAILABLE, UNAVAILABLE, STALE }

/** Forecast timestamps and typed values can map directly from a future weather service. */
data class WeatherForecast(
    val timeEpochMillis: Long, val condition: String, val low: Int?, val high: Int?,
    val forecastTemperature: Int? = null, val isDaytime: Boolean? = null, val rainProbability: Double? = null,
)

data class WeatherAirQuality(val index: Int, val category: String? = null)

data class WeatherState(
    val condition: String, val temperature: Int?, val humidity: Int?,
    val low: Int?, val high: Int?, val forecast: List<WeatherForecast>,
    val availability: Availability = Availability.AVAILABLE,
    val temperatureUnit: String = "°C",
    val name: String? = null,
    val updatedAt: java.time.Instant? = null,
    val forecastType: String = "daily",
    val entityId: String? = null,
    val conditions: com.dormpanel.app.weather.WeatherConditions? = null,
    val airQuality: WeatherAirQuality? = null,
)

data class SensorState(
    val id: String, val name: String, val temperature: Double?, val humidity: Int?,
    val availability: Availability = Availability.AVAILABLE,
    val temperatureUnit: String = "°C",
    val humidityUnit: String = "%",
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
    val brightness: Int? = null, val colorTemperature: Int? = null,
    val availability: Availability = Availability.AVAILABLE,
    val controlValues: LightControlValues? = null,
) {
    val controlBrightness get() = controlValues?.brightness ?: brightness?.takeIf { it in 1..100 }
    val controlTemperature get() = controlValues?.kelvin ?: colorTemperature?.takeIf { it in (capabilities.colorTemperature ?: IntRange.EMPTY) }
}

data class LightControlValues(val brightness: Int? = null, val kelvin: Int? = null)

/** Numeric entry rejects rather than clamps; sliders already enforce their bounds. */
fun parseLightControlNumber(text: String, range: IntRange): Int? = text.trim().toIntOrNull()?.takeIf { it in range }

data class DashboardData(
    val weather: WeatherState,
    val sensors: Map<String, SensorState>,
    val lights: Map<String, LightState>,
    val switches: DashboardSwitches = DashboardSwitches(),
)

/** Single state owner. Implementations deliver immutable snapshots and callbacks on the main thread.
 * Subscribe sends the current snapshot immediately. Commands do not imply optimistic UI state.
 */
interface DashboardDataSource {
    val state: DashboardData
    fun addListener(listener: (DashboardData) -> Unit)
    fun removeListener(listener: (DashboardData) -> Unit)
    fun toggleLight(id: String)
    fun toggleSwitch(sourceId: String, identity: SwitchIdentity): Boolean = false
    fun setLightPower(id: String, on: Boolean) { if (state.lights[id]?.isOn != on) toggleLight(id) }
    /** User brightness commands are 1..100; power is a separate action. */
    fun setBrightness(id: String, percent: Int)
    fun setColorTemperature(id: String, kelvin: Int)
}

class FakeDashboardDataSource : DashboardDataSource {
    private val initialDay = java.time.LocalDate.now()
    private fun demoForecastTime(daysFromToday: Long) =
        initialDay.plusDays(daysFromToday).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    override var state = DashboardData(
        WeatherState("Partly cloudy", 24, 58, 19, 27, listOf(
            WeatherForecast(demoForecastTime(1), "Clear", 20, 26),
            WeatherForecast(demoForecastTime(2), "Cloudy", 19, 25),
            WeatherForecast(demoForecastTime(3), "Light rain", 18, 23),
        ), name = "Demo 天气", airQuality = WeatherAirQuality(39, "优")),
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
        switches = demoSwitches(),
    )
        private set
    private val listeners = linkedSetOf<(DashboardData) -> Unit>()

    override fun addListener(listener: (DashboardData) -> Unit) { listeners += listener; listener(state) }
    override fun removeListener(listener: (DashboardData) -> Unit) { listeners -= listener }
    override fun toggleLight(id: String) = update(id) { it.copy(isOn = !it.isOn) }
    override fun toggleSwitch(sourceId: String, identity: SwitchIdentity): Boolean {
        val switches = state.switches
        val channel = switches.resolve(sourceId, identity) ?: return false
        if (!switches.connected || channel.availability != Availability.AVAILABLE) return false
        state = state.copy(switches = switches.copy(channels = switches.channels +
            (identity.entityId to channel.copy(isOn = channel.isOn != true))))
        listeners.toList().forEach { it(state) }
        return true
    }
    override fun setLightPower(id: String, on: Boolean) = update(id) { it.copy(isOn = on) }
    override fun setBrightness(id: String, percent: Int) = update(id) {
        if (it.capabilities.brightness) it.copy(isOn = true, brightness = percent.coerceIn(1, 100)) else it
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

private fun demoSwitches(): DashboardSwitches {
    val devices = linkedMapOf("desk" to "Desk")
    val desk = SwitchIdentity("switch:desk", "desk", "desk")
    val channels = mutableListOf(DashboardSwitch(desk, "Desk outlet", false, Availability.AVAILABLE))
    for (count in 2..3) {
        val id = "demo-switch-$count"
        devices[id] = "Demo · ${count}键开关"
        val names = if (count == 2) listOf("左键", "右键") else listOf("左键", "中键", "右键")
        names.forEachIndexed { index, name ->
            val entityId = "switch:$id-$index"
            channels += DashboardSwitch(SwitchIdentity(entityId, id, entityId), name, false, Availability.AVAILABLE)
        }
    }
    return DashboardSwitches("demo", true, channels.associateBy { it.identity.entityId }, devices)
}
