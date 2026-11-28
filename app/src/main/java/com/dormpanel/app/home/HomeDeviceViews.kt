package com.dormpanel.app.home

import android.content.Context
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.R
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.applyAppearanceTree
import com.dormpanel.app.dashboard.catalog.resolve
import com.dormpanel.app.data.Availability
import com.dormpanel.app.home.HomeDeviceState.*
import com.dormpanel.app.ui.hidePanelSystemBars
import java.text.NumberFormat

internal fun HomeEntity.deviceValue(context: Context, compact: Boolean = false): String {
    fun power(on: Boolean?) = context.getString(when (on) {
        true -> R.string.home_on
        false -> R.string.home_off
        null -> R.string.home_no_value
    })
    fun measure(value: Double?, unit: String) = value?.let { "${formatNumber(it)}$unit" }
    fun readings(current: String?, target: String?) = listOfNotNull(
        current?.let { context.getString(R.string.device_current, it) },
        target?.let { context.getString(R.string.device_target, it) },
    ).joinToString(" · ").ifBlank { null }
    val state = when (val d = controls) {
        is Climate -> listOfNotNull(
            d.hvac.current?.let { context.modeLabel(it) },
            d.action?.takeUnless { compact || it == d.hvac.current }?.let { context.modeLabel(it) },
            readings(measure(d.current, d.unit), if (d.showsRange) {
                val low = measure(d.low, d.unit)
                val high = measure(d.high, d.unit)
                if (low != null && high != null) "$low–$high" else null
            } else measure(d.target, d.unit)),
        )
        is Fan -> listOfNotNull(power(d.on), d.percentage?.let { "$it%" },
            d.preset.current?.takeUnless { compact }?.let { context.modeLabel(it) })
        is Humidifier -> listOfNotNull(power(d.on), readings(measure(d.current, "%"), measure(d.target, "%")))
        is Cover -> listOfNotNull(d.phase?.name?.lowercase()?.let { context.modeLabel(it) }, d.position?.let { "$it%" })
        is VirtualSwitch -> listOf(power(d.on))
        null -> emptyList()
    }.joinToString(" · ").ifBlank { context.getString(R.string.home_no_value) }
    return when (availability) {
        Availability.AVAILABLE -> state
        Availability.STALE -> context.getString(R.string.home_value_stale, state)
        Availability.UNAVAILABLE -> context.getString(R.string.home_unavailable)
    }
}

internal fun DeviceRequestState.label() = when (this) {
    DeviceRequestState.SENDING -> R.string.device_sending
    DeviceRequestState.ACCEPTED -> R.string.device_accepted
    DeviceRequestState.DENIED -> R.string.device_denied
    DeviceRequestState.FAILED -> R.string.device_failed
}

internal val HomeEntity.deviceBusy get() = request?.busy == true

internal fun HomeEntity.canSend(command: HomeCommand) = availability == Availability.AVAILABLE &&
    (!deviceBusy || command == HomeCommand.MoveCover(CoverOperation.STOP))

internal fun HomeCommand.actionLabel(): Int? = when (this) {
    is HomeCommand.Power -> if (on) R.string.light_turn_on else R.string.light_turn_off
    is HomeCommand.MoveCover -> when (operation) {
        CoverOperation.OPEN -> R.string.device_open
        CoverOperation.CLOSE -> R.string.device_close
        CoverOperation.STOP -> R.string.device_stop
        CoverOperation.POSITION -> null
    }
    else -> null
}

internal fun sendDeviceCommand(context: Context, source: HomeControlSource, target: HomeTarget, command: HomeCommand): Boolean {
    if (source.command(target, command)) return true
    Toast.makeText(context, R.string.device_not_sent, Toast.LENGTH_SHORT).show()
    return false
}

private fun formatNumber(value: Double) = NumberFormat.getNumberInstance().apply {
    maximumFractionDigits = 5
    isGroupingUsed = false
}.format(value)

private fun Context.modeLabel(mode: String) = when (mode) {
    "on" -> getString(R.string.home_on)
    "off" -> getString(R.string.home_off)
    "cool" -> getString(R.string.device_cool)
    "heat" -> getString(R.string.device_heat)
    "heat_cool" -> getString(R.string.device_heat_cool)
    "dry" -> getString(R.string.device_dry)
    "fan_only" -> getString(R.string.device_fan_only)
    "auto" -> getString(R.string.device_auto)
    "cooling" -> getString(R.string.device_cooling)
    "heating" -> getString(R.string.device_heating)
    "idle" -> getString(R.string.device_idle)
    "open" -> getString(R.string.device_opened)
    "closed" -> getString(R.string.device_closed)
    "opening" -> getString(R.string.device_opening)
    "closing" -> getString(R.string.device_closing)
    else -> mode
}

internal class HomeDeviceDetails(
    private val context: Context,
    private val source: HomeControlSource,
    private val appearance: AppearanceController,
    private val target: HomeTarget,
    private val kind: HomeKind,
    private val onDismiss: () -> Unit,
) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(12))
    }
    private val summary = TextView(context).apply { textSize = 19f }
    private val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val dialog = AlertDialog.Builder(context)
        .setView(ScrollView(context).apply { addView(body); isFillViewport = true })
        .setNegativeButton(android.R.string.cancel, null).create()
    private var editor: AlertDialog? = null
    private var displayed: HomeEntity? = null
    private val listener: (HomeControlState) -> Unit = { state -> bind(target.resolve(state.entities, kind)) }

    init {
        body.addView(summary)
        body.addView(controls)
        dialog.setOnDismissListener {
            editor?.dismiss()
            source.removeHomeListener(listener)
            onDismiss()
        }
    }

    fun show() {
        dialog.show()
        dialog.window?.setLayout((context.resources.displayMetrics.widthPixels * .66).toInt(),
            (context.resources.displayMetrics.heightPixels * .82).toInt())
        dialog.window?.hidePanelSystemBars()
        source.addHomeListener(listener)
    }

    fun dismiss() = dialog.dismiss()

    private fun bind(entity: HomeEntity?) {
        if (entity != null && entity == displayed) return
        displayed = entity
        dialog.setTitle(entity?.name ?: context.getString(R.string.home_details))
        summary.text = entity?.let {
            listOfNotNull(it.deviceValue(context), it.request?.let { request -> context.getString(request.label()) }).joinToString("\n")
        } ?: context.getString(R.string.device_missing)
        controls.removeAllViews()
        if (entity?.availability != Availability.AVAILABLE) {
            editor?.dismiss()
            applyAppearanceTree(body, appearance.state)
            return
        }
        fun action(command: HomeCommand) {
            val title = command.actionLabel() ?: return
            addButton(context.getString(title), entity.canSend(command)) { sendDeviceCommand(context, source, target, command) }
        }
        fun choice(label: Int, options: DeviceChoice, setting: DeviceOption) {
            if (options.supported.isEmpty()) return
            choices(label, options.current?.let(context::modeLabel), options.supported.map(context::modeLabel), !entity.deviceBusy) { index ->
                HomeCommand.Select(setting, options.supported[index])
            }
        }
        val primary = entity.controls?.primaryCommand()
        if (primary != null && entity.controls !is Cover) action(primary)
        when (val d = entity.controls) {
            is Climate -> {
                choice(R.string.device_hvac, d.hvac, DeviceOption.HVAC)
                val limits = d.limits
                if (d.canUseTarget && limits != null) numberButton(R.string.device_temperature, limits, !entity.deviceBusy,
                    listOf(R.string.device_temperature to d.target)) { HomeCommand.Temperature(it[0]) }
                if (d.canUseRange && limits != null) numberButton(R.string.device_temperature_range, limits, !entity.deviceBusy,
                    listOf(R.string.device_low to d.low, R.string.device_high to d.high)) {
                    if (it[0] <= it[1]) HomeCommand.TemperatureRange(it[0], it[1]) else null
                }
                choice(R.string.device_fan_mode, d.fan, DeviceOption.FAN)
                choice(R.string.device_swing, d.swing, DeviceOption.SWING)
                choice(R.string.device_horizontal_swing, d.horizontalSwing, DeviceOption.HORIZONTAL_SWING)
                choice(R.string.device_preset, d.preset, DeviceOption.PRESET)
            }
            is Fan -> if (d.speeds.isNotEmpty() || d.preset.supported.isNotEmpty()) {
                val speeds = listOf(0) + d.speeds
                if (d.speeds.isNotEmpty()) choices(R.string.device_speed, d.percentage?.let { "$it%" },
                    speeds.map { "$it%" }, !entity.deviceBusy) { HomeCommand.Speed(speeds[it]) }
                choice(R.string.device_preset, d.preset, DeviceOption.PRESET)
            }
            is Humidifier -> {
                d.limits?.let { numberButton(R.string.device_humidity, it, !entity.deviceBusy,
                    listOf(R.string.device_humidity to d.target)) { values -> HomeCommand.Humidity(values[0]) } }
                choice(R.string.device_mode, d.mode, DeviceOption.HUMIDIFIER_MODE)
            }
            is Cover -> {
                listOf(CoverOperation.OPEN, CoverOperation.CLOSE, CoverOperation.STOP).filter { it in d.operations }
                    .forEach { action(HomeCommand.MoveCover(it)) }
                if (CoverOperation.POSITION in d.operations) numberButton(R.string.device_position, NumericLimits(0.0, 100.0, 1.0),
                    !entity.deviceBusy, listOf(R.string.device_position to d.position?.toDouble())) { HomeCommand.Position(it[0].toInt()) }
            }
            else -> Unit
        }
        applyAppearanceTree(body, appearance.state)
    }

    private fun addButton(label: String, enabled: Boolean, action: () -> Unit) {
        controls.addView(Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 17f
            minHeight = dp(52)
            isEnabled = enabled
            setOnClickListener { action() }
        })
    }

    private fun choices(label: Int, current: String?, options: List<String>, enabled: Boolean, command: (Int) -> HomeCommand) {
        val title = context.getString(label)
        addButton(listOfNotNull(title, current).joinToString(" · "), enabled) {
            editor = AlertDialog.Builder(context).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), options.indexOf(current)) { choice, index ->
                    if (sendDeviceCommand(context, source, target, command(index))) choice.dismiss()
                }.setNegativeButton(android.R.string.cancel, null).show().also { it.window?.hidePanelSystemBars() }
        }
    }

    private fun numberButton(label: Int, limits: NumericLimits, enabled: Boolean,
        inputs: List<Pair<Int, Double?>>, command: (List<Double>) -> HomeCommand?,
    ) {
        addButton(context.getString(label), enabled) {
            val fields = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(4), dp(20), dp(8))
            }
            val range = "${formatNumber(limits.min)}–${formatNumber(limits.max)}"
            fields.addView(TextView(context).apply {
                text = limits.step?.let { context.getString(R.string.device_range_step, range, formatNumber(it)) } ?: range
            })
            val edits = inputs.map { (hint, value) ->
                fields.addView(TextView(context).apply { setText(hint) })
                EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                    contentDescription = context.getString(hint)
                    minHeight = dp(52)
                    setText(value?.let(::formatNumber).orEmpty())
                    fields.addView(this)
                }
            }
            applyAppearanceTree(fields, appearance.state)
            val numberDialog = AlertDialog.Builder(context).setTitle(label).setView(fields)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.device_apply, null).show()
            editor = numberDialog
            numberDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = edits.map { edit -> edit.text.toString().trim().replace(',', '.').toDoubleOrNull() }
                val request = if (values.all { it != null && it in limits }) command(values.filterNotNull()) else null
                if (request == null) edits.last().error = context.getString(R.string.device_invalid_value)
                else if (sendDeviceCommand(context, source, target, request)) numberDialog.dismiss()
            }
        }
    }
}
