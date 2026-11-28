package com.dormpanel.app.home

import com.dormpanel.app.data.Availability
import com.dormpanel.app.data.SwitchIdentity
import com.dormpanel.app.ha.deviceService

internal class DemoDevices {
    private val states = linkedMapOf<String, HomeDeviceState>(
        "climate" to HomeDeviceState.Climate(
            DeviceChoice("cool", listOf("off", "heat", "cool", "heat_cool")), "cooling", 27.0,
            24.0, 20.0, 25.0, "°C", NumericLimits(16.0, 32.0, 0.5),
            true, true, false, false, DeviceChoice("auto", listOf("auto", "low", "high")),
            DeviceChoice("off", listOf("off", "on")), DeviceChoice(null), DeviceChoice("none", listOf("none", "eco")),
        ),
        "fan" to HomeDeviceState.Fan(false, 0, 100.0 / 3, DeviceChoice("normal", listOf("normal", "sleep")), true, true),
        "humidifier" to HomeDeviceState.Humidifier(true, 46.0, 55.0, NumericLimits(30.0, 80.0, 1.0),
            DeviceChoice("normal", listOf("normal", "eco"))),
        "cover" to HomeDeviceState.Cover(30, CoverPhase.OPEN, CoverOperation.entries.toSet()),
    )

    fun entities() = states.map { (domain, device) ->
        val id = "ha:$domain.demo"
        val name = when (domain) {
            "climate" -> "Demo 空调"
            "fan" -> "Demo 风扇"
            "humidifier" -> "Demo 加湿器"
            else -> "Demo 窗帘"
        }
        HomeEntity(id, name, HomeKind.valueOf(domain.uppercase()), "bedroom", null, Availability.AVAILABLE,
            controls = device, target = HomeTarget("demo", SwitchIdentity(id, null, id)))
    }

    fun apply(target: HomeTarget, command: HomeCommand): Boolean {
        val entity = entities().find { it.target == target } ?: return false
        val service = entity.deviceService(command) ?: return false
        val device = states.getValue(service.domain)
        states[service.domain] = when (device) {
            is HomeDeviceState.Climate -> when (command) {
                is HomeCommand.Power -> {
                    val mode = service.data["hvac_mode"] as? String ?: return false
                    device.withMode(mode)
                }
                is HomeCommand.Select -> when (command.option) {
                    DeviceOption.HVAC -> device.withMode(command.value)
                    DeviceOption.FAN -> device.copy(fan = device.fan.copy(current = command.value))
                    DeviceOption.SWING -> device.copy(swing = device.swing.copy(current = command.value))
                    DeviceOption.HORIZONTAL_SWING -> device.copy(horizontalSwing = device.horizontalSwing.copy(current = command.value))
                    DeviceOption.PRESET -> device.copy(preset = device.preset.copy(current = command.value))
                    else -> return false
                }
                is HomeCommand.Temperature -> device.copy(target = command.value)
                is HomeCommand.TemperatureRange -> device.copy(low = command.low, high = command.high)
                else -> return false
            }
            is HomeDeviceState.Fan -> when (command) {
                is HomeCommand.Power -> device.copy(on = command.on, percentage = if (command.on) 33 else 0)
                is HomeCommand.Speed -> device.copy(on = command.percent != 0, percentage = command.percent)
                is HomeCommand.Select -> device.copy(preset = device.preset.copy(current = command.value))
                else -> return false
            }
            is HomeDeviceState.Humidifier -> when (command) {
                is HomeCommand.Power -> device.copy(on = command.on)
                is HomeCommand.Humidity -> device.copy(target = command.percent)
                is HomeCommand.Select -> device.copy(mode = device.mode.copy(current = command.value))
                else -> return false
            }
            is HomeDeviceState.Cover -> when (command) {
                is HomeCommand.Position -> device.atPosition(command.percent)
                is HomeCommand.MoveCover -> when (command.operation) {
                    CoverOperation.OPEN -> device.atPosition(100)
                    CoverOperation.CLOSE -> device.atPosition(0)
                    CoverOperation.STOP -> device.atPosition(device.position ?: return false)
                    CoverOperation.POSITION -> return false
                }
                else -> return false
            }
            is HomeDeviceState.VirtualSwitch -> return false
        }
        return true
    }

    private fun HomeDeviceState.Climate.withMode(mode: String) = copy(hvac = hvac.copy(current = mode),
        action = when (mode) { "off" -> "off"; "heat" -> "heating"; "cool" -> "cooling"; else -> "idle" })

    private fun HomeDeviceState.Cover.atPosition(position: Int) = copy(position = position,
        phase = if (position == 0) CoverPhase.CLOSED else CoverPhase.OPEN)
}
