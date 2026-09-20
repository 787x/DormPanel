package com.dormpanel.app.dashboard.card

import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.data.LightCapabilities

/** Shared typographic hierarchy (sp), not a shared card layout. */
object DashboardTypography {
    const val MINOR = 16f
    const val SECONDARY = 22f
    const val TITLE = 24f
    const val VALUE = 40f
    const val HERO = 64f
    const val CLOCK = 104f
}

data class ClockPresentation(val timeSp: Float, val date: Boolean, val calendarDetail: Boolean, val centered: Boolean)
fun clockPresentation(size: CardSize) = ClockPresentation(
    timeSp = when {
        size.rowSpan == 1 -> if (size.columnSpan >= 3) 48f else 36f
        size.columnSpan >= 4 && size.rowSpan >= 3 -> DashboardTypography.CLOCK
        size.columnSpan >= 3 && size.rowSpan >= 2 -> 80f
        else -> 52f
    },
    date = size.rowSpan >= 2,
    calendarDetail = size.rowSpan >= 3 && size.columnSpan >= 3,
    centered = size.columnSpan <= 2,
)

data class WeatherPresentation(val horizontal: Boolean, val temperatureSp: Float, val summary: Boolean, val forecastColumns: Int)
fun weatherPresentation(size: CardSize) = WeatherPresentation(
    horizontal = size.rowSpan == 1 || size.columnSpan >= 3,
    temperatureSp = if (size.rowSpan == 1) 36f else if (size.columnSpan >= 3) 72f else 48f,
    summary = size.rowSpan >= 2,
    forecastColumns = if (size.rowSpan >= 3 && size.columnSpan >= 3) size.columnSpan - 1 else 0,
)

data class SensorPresentation(val sideBySide: Boolean, val valueSp: Float, val valueLabels: Boolean, val status: Boolean)
fun sensorPresentation(size: CardSize) = SensorPresentation(
    sideBySide = size.rowSpan == 1 || size.columnSpan >= 3,
    valueSp = when { size.rowSpan == 1 -> 28f; size.columnSpan >= 3 -> 60f; else -> 40f },
    valueLabels = size.rowSpan >= 2,
    status = size.rowSpan >= 3,
)

data class LightPresentation(val inlineBrightness: Boolean, val inlineTemperature: Boolean, val prominentSummary: Boolean, val horizontalHeader: Boolean)
fun lightPresentation(size: CardSize, capabilities: LightCapabilities) = LightPresentation(
    inlineBrightness = size.columnSpan >= 3 && size.rowSpan >= 2 && capabilities.brightness,
    inlineTemperature = size.columnSpan >= 3 && size.rowSpan >= 3 && capabilities.colorTemperature != null,
    prominentSummary = size.rowSpan >= 2,
    horizontalHeader = size.rowSpan == 1 || size.columnSpan >= 3,
)

fun millisUntilNextMinute(now: Long): Long = 60_000L - Math.floorMod(now, 60_000L)
