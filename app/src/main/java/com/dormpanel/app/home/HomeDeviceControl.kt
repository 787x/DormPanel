package com.dormpanel.app.home

import com.dormpanel.app.data.SwitchIdentity
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

data class HomeTarget(val sourceId: String, val identity: SwitchIdentity)

internal val DEVICE_CONTROL_KINDS = setOf(HomeKind.CLIMATE, HomeKind.FAN, HomeKind.HUMIDIFIER, HomeKind.COVER, HomeKind.INPUT_BOOLEAN)

data class NumericLimits(val min: Double, val max: Double, val step: Double? = null) {
    operator fun contains(value: Double): Boolean = value in min..max &&
        (step == null || ((value - min) / step).let { abs(it - round(it)) < 0.00001 })
}

data class DeviceChoice(val current: String?, val supported: List<String> = emptyList())

private enum class TemperatureTarget { SINGLE, RANGE }

sealed interface HomeDeviceState {
    data class Climate(
        val hvac: DeviceChoice, val action: String?, val current: Double?, val target: Double?,
        val low: Double?, val high: Double?, val unit: String, val limits: NumericLimits?,
        val singleTarget: Boolean, val rangeTarget: Boolean, val turnOn: Boolean, val turnOff: Boolean,
        val fan: DeviceChoice, val swing: DeviceChoice, val horizontalSwing: DeviceChoice, val preset: DeviceChoice,
    ) : HomeDeviceState {
        val active get() = hvac.current != null && hvac.current != "off"
        val recoveryMode get() = hvac.supported.filter { it != "off" }.singleOrNull()
        private val targetForm = when (hvac.current) {
            "heat_cool" -> TemperatureTarget.RANGE.takeIf { rangeTarget }
            "heat", "cool", "dry" -> TemperatureTarget.SINGLE.takeIf { singleTarget }
            "auto" -> autoTargetForm()
            else -> null
        }
        val showsRange get() = hvac.current == "heat_cool" || targetForm == TemperatureTarget.RANGE
        val canUseRange get() = targetForm == TemperatureTarget.RANGE && limits != null
        val canUseTarget get() = targetForm == TemperatureTarget.SINGLE && limits != null

        private fun autoTargetForm(): TemperatureTarget? {
            if (singleTarget != rangeTarget) return if (singleTarget) TemperatureTarget.SINGLE else TemperatureTarget.RANGE
            if (!singleTarget) return null
            val bounds = limits?.let { it.min..it.max } ?: return null
            // With both features, prefer a complete range as before. HA can round readbacks,
            // so apply the step check to outgoing edits only.
            return when {
                low != null && high != null && low in bounds && high in bounds && low <= high -> TemperatureTarget.RANGE
                target != null && target in bounds -> TemperatureTarget.SINGLE
                else -> null
            }
        }
    }
    data class Fan(
        val on: Boolean?, val percentage: Int?, val percentageStep: Double?, val preset: DeviceChoice,
        val turnOn: Boolean, val turnOff: Boolean,
    ) : HomeDeviceState {
        // HA exposes fractional steps but its ordered-speed helper reports integer
        // percentages using floor division (e.g. 33, 66, 100 for three speeds).
        val speeds: List<Int> = percentageStep?.let { step ->
            (1..floor(100 / step + 0.00001).toInt()).mapNotNull { index ->
                val percent = index * step
                floor(percent + 0.00001).toInt().takeIf { percent < 99.5 }
            } + 100
        } ?: emptyList()
    }
    data class Humidifier(
        val on: Boolean?, val current: Double?, val target: Double?, val limits: NumericLimits?, val mode: DeviceChoice,
    ) : HomeDeviceState
    data class Cover(val position: Int?, val phase: CoverPhase?, val operations: Set<CoverOperation>) : HomeDeviceState
    data class VirtualSwitch(val on: Boolean?) : HomeDeviceState
}

enum class CoverPhase { OPEN, CLOSED, OPENING, CLOSING }
enum class CoverOperation { OPEN, CLOSE, POSITION, STOP }
enum class DeviceOption { HVAC, FAN, SWING, HORIZONTAL_SWING, PRESET, HUMIDIFIER_MODE }
enum class DeviceRequestState {
    SENDING, ACCEPTED, DENIED, FAILED;

    val busy get() = this == SENDING || this == ACCEPTED
}

sealed interface HomeCommand {
    data class Power(val on: Boolean) : HomeCommand
    data class Select(val option: DeviceOption, val value: String) : HomeCommand
    data class Temperature(val value: Double) : HomeCommand
    data class TemperatureRange(val low: Double, val high: Double) : HomeCommand
    data class Speed(val percent: Int) : HomeCommand
    data class Humidity(val percent: Double) : HomeCommand
    data class MoveCover(val operation: CoverOperation) : HomeCommand
    data class Position(val percent: Int) : HomeCommand
}

fun HomeDeviceState.primaryCommand(): HomeCommand? = when (this) {
    is HomeDeviceState.Climate -> when {
        active && (turnOff || "off" in hvac.supported) -> HomeCommand.Power(false)
        hvac.current == "off" && (turnOn || recoveryMode != null) -> HomeCommand.Power(true)
        else -> null
    }
    is HomeDeviceState.Fan -> when {
        on == true && turnOff -> HomeCommand.Power(false)
        on == false && turnOn -> HomeCommand.Power(true)
        else -> null
    }
    is HomeDeviceState.Humidifier -> on?.let { HomeCommand.Power(!it) }
    is HomeDeviceState.VirtualSwitch -> on?.let { HomeCommand.Power(!it) }
    is HomeDeviceState.Cover -> when (phase) {
        CoverPhase.OPENING, CoverPhase.CLOSING -> CoverOperation.STOP
        CoverPhase.OPEN -> CoverOperation.CLOSE
        CoverPhase.CLOSED -> CoverOperation.OPEN
        null -> null
    }?.takeIf { it in operations }?.let(HomeCommand::MoveCover)
}
