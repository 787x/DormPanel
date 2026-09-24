package com.dormpanel.app.ha

import com.dormpanel.app.data.*
import com.dormpanel.app.home.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import java.time.OffsetDateTime

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "")
internal fun JSONObject.number(key: String): Double? = optDouble(key, Double.NaN).takeIf { it.isFinite() }
data class HaEntity(val id: String, val value: String, val attributes: JSONObject) {
    val usable get() = value != "unknown" && value != "unavailable"
    companion object { fun parse(json: JSONObject) = HaEntity(json.getString("entity_id"), json.optString("state"), json.optJSONObject("attributes") ?: JSONObject()) }
}
data class HaMetadata(val id: String, val device: String, val name: String, val area: String, val disabled: Boolean, val category: String = "")
data class SensorGroup(val id: String, val name: String, val temperature: String?, val humidity: String?)

/** The only raw entity store. Registry topology is cached separately from live values. */
class HaEntityStore {
    val entities = linkedMapOf<String, HaEntity>()
    val metadata = linkedMapOf<String, HaMetadata>()
    private val scriptInputs = mutableMapOf<String, Boolean>()
    fun scriptServices(result: JSONObject?) {
        scriptInputs.clear()
        val scripts = result?.optJSONObject("script") ?: return
        scripts.keys().forEach { name ->
            val fields = scripts.optJSONObject(name)?.optJSONObject("fields")
            scriptInputs["script.$name"] = fields?.keys()?.asSequence()?.any { fields.optJSONObject(it)?.optBoolean("required") == true } == true
        }
    }
    val areas = linkedMapOf<String, HomeArea>()
    val devices = linkedMapOf<String, HomeDevice>()
    fun areaRegistry(array: JSONArray) {
        areas.clear()
        array.objects().forEach { val id = it.text("area_id"); if (id.isNotEmpty()) areas[id] = HomeArea(id, it.text("name").ifEmpty { id }) }
    }
    fun deviceRegistry(array: JSONArray) {
        devices.clear()
        array.objects().forEach {
            val id = it.text("id")
            if (id.isNotEmpty()) devices[id] = HomeDevice(id, it.text("name_by_user").ifBlank { it.text("name").ifBlank { id } }, it.text("area_id").ifEmpty { null })
        }
    }
    fun home(connected: Boolean, lights: Map<String, LightState>): HomeControlState {
        val supported = HomeKind.entries.associateBy { it.name.lowercase() }
        val values = entities.values.mapNotNull { e ->
            val kind = supported[e.id.substringBefore('.')] ?: return@mapNotNull null
            val meta = metadata[e.id]
            if (!enabled(e) || meta?.category in setOf("diagnostic", "config")) return@mapNotNull null
            val device = devices[meta?.device]
            val area = (meta?.area?.takeIf { it.isNotEmpty() } ?: device?.areaId)?.takeIf { it in areas }
            val availability = if (!connected) Availability.STALE else if (!e.usable) Availability.UNAVAILABLE else Availability.AVAILABLE
            val fields = e.attributes.optJSONObject("fields")
            val needsInput = fields?.keys()?.asSequence()?.any { fields.optJSONObject(it)?.optBoolean("required") == true } == true
            HomeEntity("ha:${e.id}", name(e), kind, area, device?.id, availability,
                e.value, e.attributes.text("unit_of_measurement"), e.value == "on", lights["ha:${e.id}"], !needsInput && (kind != HomeKind.SCRIPT || scriptInputs[e.id] == false))
        }.sortedWith(compareBy({ it.name }, { it.id }))
        return HomeControlState(areas.values.sortedWith(compareBy({ it.name }, { it.id })),
            devices.values.sortedWith(compareBy({ it.name }, { it.id })), values, connected)
    }
    private val discoveryIds = mutableMapOf<String, List<String>>()
    private var groupedSensors: List<SensorGroup>? = null
    private fun invalidateDiscovery() { discoveryIds.clear(); groupedSensors = null }
    private fun topology(entity: HaEntity?) = entity?.let { listOf(it.id, it.attributes.text("friendly_name"), it.attributes.text("device_class")) }
    var registryKnown = false
    var forecast = emptyList<WeatherForecast>()
    fun snapshot(array: JSONArray) { invalidateDiscovery(); entities.clear(); array.objects().map(HaEntity::parse).forEach { entities[it.id] = it } }
    fun event(data: JSONObject) {
        val id = data.getString("entity_id")
        val next = data.optJSONObject("new_state")?.let(HaEntity::parse)
        if (topology(entities[id]) != topology(next)) invalidateDiscovery()
        if (next == null) entities.remove(id) else entities[id] = next
    }
    fun registry(result: Any?) {
        val array = when (result) { is JSONObject -> result.optJSONArray("entities"); is JSONArray -> result; else -> null } ?: return
        invalidateDiscovery(); metadata.clear(); registryKnown = true
        array.objects().forEach {
            val id = it.text("entity_id").ifEmpty { it.text("ei") }
            if (id.isNotEmpty()) metadata[id] = HaMetadata(id, it.text("device_id").ifEmpty { it.text("di") },
                it.text("name").ifEmpty { it.text("en") }, it.text("area_id").ifEmpty { it.text("ai") }, !it.isNull("disabled_by"), it.text("entity_category"))
        }
    }
    fun enabled(entity: HaEntity) = metadata[entity.id]?.disabled != true
    fun name(entity: HaEntity) = metadata[entity.id]?.name?.takeIf { it.isNotBlank() }
        ?: entity.attributes.text("friendly_name").ifEmpty { entity.id }
    fun discovered(domain: String): List<HaEntity> = discoveryIds.getOrPut(domain) {
        entities.values.filter { it.id.startsWith("$domain.") && enabled(it) }
            .sortedWith(compareBy({ metadata[it.id]?.area ?: "" }, { name(it) }, { it.id })).map { it.id }
    }.mapNotNull { entities[it] }
    fun sensorGroups(): List<SensorGroup> {
        groupedSensors?.let { return it }
        val sensors = discovered("sensor").filter { it.attributes.text("device_class") in listOf("temperature", "humidity") }
        val groups = sensors.groupBy { metadata[it.id]?.device?.takeIf(String::isNotEmpty) ?: "entity:${it.id}" }
        return groups.flatMap { (device, values) ->
            val temperatures = values.filter { it.attributes.text("device_class") == "temperature" }
            val humidities = values.filter { it.attributes.text("device_class") == "humidity" }
            if (!device.startsWith("entity:") && temperatures.size == 1 && humidities.size == 1)
                listOf(SensorGroup("ha:device:$device", name(temperatures.single()), temperatures.single().id, humidities.single().id))
            else values.map { SensorGroup("ha:${it.id}", name(it), it.id.takeIf { _ -> it in temperatures }, it.id.takeIf { _ -> it in humidities }) }
        }.sortedBy { it.id }.also { groupedSensors = it }
    }
    /** Direct Home reads also work while a reconnect snapshot is still awaiting registries. */
    fun lightStates(connected: Boolean): Map<String, LightState> {
        fun availability(e: HaEntity) = if (!e.usable) Availability.UNAVAILABLE else if (connected) Availability.AVAILABLE else Availability.STALE
        return discovered("light").associate { e ->
            val a = e.attributes
            val modes = a.optJSONArray("supported_color_modes")?.let { list -> (0 until list.length()).map { list.optString(it) } }.orEmpty()
            val min = a.optInt("min_color_temp_kelvin"); val max = a.optInt("max_color_temp_kelvin")
            val range = if ("color_temp" in modes && min > 0 && max >= min) min..max else null
            val id = "ha:${e.id}"
            id to LightState(id, name(e), e.value == "on", LightCapabilities(modes.any { it in setOf("brightness", "color_temp", "hs", "xy", "rgb", "rgbw", "rgbww", "white") }, range),
                a.number("brightness")?.takeIf { it in 0.0..255.0 }?.let { (it * 100 / 255).roundToInt() },
                a.number("color_temp_kelvin")?.takeIf { it > 0 }?.toInt(), availability(e))
        }
    }
    fun normalized(connected: Boolean, weatherId: String): DashboardData {
        fun availability(e: HaEntity) = if (!e.usable) Availability.UNAVAILABLE else if (connected) Availability.AVAILABLE else Availability.STALE
        val lights = lightStates(connected)
        val sensors = sensorGroups().associate { group ->
            val t = entities[group.temperature]; val h = entities[group.humidity]
            val temp = t?.takeIf { it.usable }?.value?.toDoubleOrNull()?.takeIf { it.isFinite() }
            val humidity = h?.takeIf { it.usable }?.value?.toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToInt()
            val status = if (temp == null && humidity == null) Availability.UNAVAILABLE else if (!connected) Availability.STALE else Availability.AVAILABLE
            group.id to SensorState(group.id, group.name, temp, humidity, status, t?.attributes?.text("unit_of_measurement").orEmpty(), h?.attributes?.text("unit_of_measurement").orEmpty())
        }
        val weather = entities[weatherId]?.takeIf { enabled(it) }
        val a = weather?.attributes ?: JSONObject()
        val temperature = a.number("temperature")?.roundToInt() ?: 0
        return DashboardData(WeatherState(weather?.value.orEmpty(), temperature, a.optInt("humidity"), forecast.firstOrNull()?.low ?: temperature,
            forecast.firstOrNull()?.high ?: temperature, forecast,
            if (weather == null || a.number("temperature") == null) Availability.UNAVAILABLE else availability(weather), a.text("temperature_unit")), sensors, lights)
    }
    companion object {
        fun forecast(result: JSONObject?, entity: String): List<WeatherForecast> = result?.optJSONObject("response")?.optJSONObject(entity)
            ?.optJSONArray("forecast")?.objects().orEmpty().mapNotNull { item -> runCatching {
                val high = item.number("temperature") ?: return@mapNotNull null
                WeatherForecast(OffsetDateTime.parse(item.getString("datetime")).toInstant().toEpochMilli(), item.text("condition"),
                    (item.number("templow") ?: high).roundToInt(), high.roundToInt())
            }.getOrNull() }
    }
}
