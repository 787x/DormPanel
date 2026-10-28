package com.dormpanel.phone

import com.dormpanel.app.schedule.*
import java.time.*

/** Read-only display-day intersection, including courses crossing midnight. */
internal object TodayCourses {
    data class Day(val date: LocalDate, val all: List<ClassOccurrence>, val remaining: List<ClassOccurrence>, val next: Instant)
    fun project(state: ScheduleState, now: Instant, zone: ZoneId): Day {
        val date = now.atZone(zone).toLocalDate()
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
        val all = ScheduleProjection.week(state, monday, zone).filter { it.start < end && it.end > start }
            .sortedWith(compareBy<ClassOccurrence> { it.start }.thenBy { it.sourceId.orEmpty() }.thenBy { it.seriesId }.thenBy { it.anchorDate }.thenBy { it.entry.id })
        val remaining = all.filter { it.end > now }
        val next = (remaining.flatMap { listOf(it.start, it.end) } + end).filter { it > now }.minOrNull()!!
        return Day(date, all, remaining, next)
    }
    fun colorIndex(item: ClassOccurrence) = Math.floorMod((item.sourceId.orEmpty()+"|"+item.seriesId).hashCode(), 6)
}
