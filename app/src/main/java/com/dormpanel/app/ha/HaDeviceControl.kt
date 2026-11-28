package com.dormpanel.app.ha

import com.dormpanel.app.home.*
import com.dormpanel.app.home.HomeDeviceState.*
import com.dormpanel.app.data.Availability
import org.json.JSONObject

private val hvacModes = setOf("off", "heat", "cool", "heat_cool", "auto", "dry", "fan_only")
private fun JSONObject.string(key: String) = (opt(key) as? String)?.takeIf { it.isNotBlank() }
private fun JSONObject.numeric(key: String) = (opt(key) as? Number)?.toDouble()?.takeIf(Double::isFinite)
private fun JSONObject.percent(key: String) = numeric(key)?.takeIf { it in 0.0..100.0 }
private fun JSONObject.intPercent(key: String) = percent(key)?.takeIf { it == it.toInt().toDouble() }?.toInt()
private fun JSONObject.choice(key: String, list: String, supported: Boolean): DeviceChoice {
    val array = optJSONArray(list)
    val choices = if (supported && array != null) (0 until array.length()).mapNotNull {
        (array.opt(it) as? String)?.takeIf(String::isNotBlank)
    }.distinct() else emptyList()
    return DeviceChoice(string(key), choices)
}

private fun JSONObject.limits(minKey: String, maxKey: String, stepKey: String, bounds: ClosedFloatingPointRange<Double>): NumericLimits? {
    val min = numeric(minKey)?.takeIf { it in bounds } ?: return null
    val max = numeric(maxKey)?.takeIf { it in bounds && it >= min } ?: return null
    val step = numeric(stepKey)?.takeIf { it > 0 && it >= 0.00001 }
    if (!isNull(stepKey) && step == null) return null
    return NumericLimits(min, max, step)
}

internal fun HaEntity.deviceState(systemTemperatureUnit: String): HomeDeviceState? {
    val a = attributes
    val features = a.numeric("supported_features")?.takeIf { it in 0.0..Int.MAX_VALUE.toDouble() && it == it.toInt().toDouble() }?.toInt() ?: 0
    fun supports(flag: Int) = features and flag != 0
    val power = when (value) { "on" -> true; "off" -> false; else -> null }
    return when (id.substringBefore('.')) {
        "climate" -> Climate(
            DeviceChoice(value.takeIf { it in hvacModes }, a.choice("hvac_mode", "hvac_modes", true).supported.filter { it in hvacModes }),
            a.string("hvac_action")?.takeUnless { it in setOf("unknown", "unavailable") },
            a.numeric("current_temperature"), a.numeric("temperature"),
            a.numeric("target_temp_low"), a.numeric("target_temp_high"),
            a.string("temperature_unit")?.takeIf { it in setOf("°C", "°F", "K") } ?: systemTemperatureUnit,
            a.limits("min_temp", "max_temp", "target_temp_step", -459.67..1000.0),
            supports(1), supports(2), supports(256), supports(128),
            a.choice("fan_mode", "fan_modes", supports(8)),
            a.choice("swing_mode", "swing_modes", supports(32)),
            a.choice("swing_horizontal_mode", "swing_horizontal_modes", supports(512)),
            a.choice("preset_mode", "preset_modes", supports(16)),
        )
        "fan" -> Fan(power, a.intPercent("percentage"),
            a.numeric("percentage_step")?.takeIf { supports(1) && it in 1.0..100.0 },
            a.choice("preset_mode", "preset_modes", supports(8)), supports(32), supports(16))
        "humidifier" -> Humidifier(power, a.percent("current_humidity"), a.percent("humidity"),
            a.limits("min_humidity", "max_humidity", "target_humidity_step", 0.0..100.0),
            a.choice("mode", "available_modes", supports(1)))
        "cover" -> Cover(a.intPercent("current_position"),
            CoverPhase.entries.find { it.name.equals(value, ignoreCase = true) },
            buildSet {
                if (supports(1)) add(CoverOperation.OPEN)
                if (supports(2)) add(CoverOperation.CLOSE)
                if (supports(4)) add(CoverOperation.POSITION)
                if (supports(8)) add(CoverOperation.STOP)
            })
        "input_boolean" -> VirtualSwitch(power)
        else -> null
    }
}

internal fun HomeDeviceState.hasKnownState() = when (this) {
    is Climate -> hvac.current != null
    is Fan -> on != null
    is Humidifier -> on != null
    is Cover -> phase != null
    is VirtualSwitch -> on != null
}

private val entityIdPattern = Regex("[a-z0-9_]+\\.[a-z0-9_]+")

internal data class DeviceService(val domain: String, val service: String, val entityId: String, val data: Map<String, Any>)

internal fun HomeEntity.deviceService(command: HomeCommand): DeviceService? {
    val domain = kind.name.lowercase()
    val target = id.removePrefix("ha:")
    if (availability != Availability.AVAILABLE || !actionable || !id.startsWith("ha:") ||
        !target.startsWith("$domain.") || !target.matches(entityIdPattern)) return null
    val device = controls?.takeIf { it.hasKnownState() } ?: return null
    fun call(service: String, vararg data: Pair<String, Any>) = DeviceService(domain, service, target, data.toMap())
    fun power(on: Boolean) = call(if (on) "turn_on" else "turn_off")
    fun option(service: String, key: String, choice: DeviceChoice, value: String) =
        if (value in choice.supported) call(service, key to value) else null
    return when (command) {
        is HomeCommand.Power -> when (device) {
            is Climate -> when {
                command.on && device.hvac.current == "off" && device.turnOn -> power(true)
                command.on && device.hvac.current == "off" -> device.recoveryMode?.let { call("set_hvac_mode", "hvac_mode" to it) }
                !command.on && device.active && device.turnOff -> power(false)
                !command.on && device.active && "off" in device.hvac.supported -> call("set_hvac_mode", "hvac_mode" to "off")
                else -> null
            }
            is Fan -> power(command.on).takeIf { if (command.on) device.turnOn else device.turnOff }
            is Humidifier, is VirtualSwitch -> power(command.on)
            is Cover -> null
        }
        is HomeCommand.Select -> when (command.option) {
            DeviceOption.HVAC -> (device as? Climate)?.let { option("set_hvac_mode", "hvac_mode", it.hvac, command.value) }
            DeviceOption.FAN -> (device as? Climate)?.let { option("set_fan_mode", "fan_mode", it.fan, command.value) }
            DeviceOption.SWING -> (device as? Climate)?.let { option("set_swing_mode", "swing_mode", it.swing, command.value) }
            DeviceOption.HORIZONTAL_SWING -> (device as? Climate)?.let { option("set_swing_horizontal_mode", "swing_horizontal_mode", it.horizontalSwing, command.value) }
            DeviceOption.PRESET -> when (device) {
                is Climate -> option("set_preset_mode", "preset_mode", device.preset, command.value)
                is Fan -> option("set_preset_mode", "preset_mode", device.preset, command.value)
                else -> null
            }
            DeviceOption.HUMIDIFIER_MODE -> (device as? Humidifier)?.let { option("set_mode", "mode", it.mode, command.value) }
        }
        is HomeCommand.Temperature -> (device as? Climate)?.takeIf {
            it.canUseTarget && command.value in checkNotNull(it.limits)
        }?.let { call("set_temperature", "temperature" to command.value) }
        is HomeCommand.TemperatureRange -> (device as? Climate)?.takeIf {
            it.canUseRange && command.low <= command.high &&
                command.low in checkNotNull(it.limits) && command.high in it.limits
        }?.let { call("set_temperature", "target_temp_low" to command.low, "target_temp_high" to command.high) }
        is HomeCommand.Speed -> (device as? Fan)?.takeIf {
            command.percent in it.speeds || command.percent == 0 && it.speeds.isNotEmpty()
        }?.let { call("set_percentage", "percentage" to command.percent) }
        is HomeCommand.Humidity -> (device as? Humidifier)?.limits?.takeIf {
            command.percent in it
        }?.let { call("set_humidity", "humidity" to command.percent) }
        is HomeCommand.MoveCover -> (device as? Cover)?.takeIf { command.operation in it.operations }?.let {
            when (command.operation) {
                CoverOperation.OPEN -> call("open_cover")
                CoverOperation.CLOSE -> call("close_cover")
                CoverOperation.STOP -> call("stop_cover")
                CoverOperation.POSITION -> null
            }
        }
        is HomeCommand.Position -> (device as? Cover)?.takeIf {
            CoverOperation.POSITION in it.operations && command.percent in 0..100
        }?.let { call("set_cover_position", "position" to command.percent) }
    }
}
