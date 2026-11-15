package com.dormpanel.app.weather

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** HA supplies a timestamp and, for twice-daily forecasts, a day/night flag. */
internal fun WeatherPeriod.haForecastEnd(block: WeatherBlock, zone: ZoneId): Instant? = try {
    if (block == WeatherBlock.DAILY) {
        val day = date ?: start?.atZone(zone)?.toLocalDate() ?: localTime?.toLocalDate()
        day?.plusDays(1)?.atStartOfDay(zone)?.toInstant()
    } else {
        val beginning = start ?: localTime?.let { local ->
            zone.rules.getValidOffsets(local).singleOrNull()?.let(local::toInstant)
        }
        when {
            beginning == null -> null
            block == WeatherBlock.TWICE_DAILY && isDaytime == null -> null
            end != null -> end.takeIf { it > beginning }
            block == WeatherBlock.HOURLY -> beginning.plusSeconds(3600)
            block == WeatherBlock.TWICE_DAILY -> {
                val local = beginning.atZone(zone)
                ZonedDateTime.ofLocal(local.toLocalDateTime().plusHours(12), zone, local.offset).toInstant()
            }
            else -> null
        }
    }
} catch (_: DateTimeException) {
    null
}
