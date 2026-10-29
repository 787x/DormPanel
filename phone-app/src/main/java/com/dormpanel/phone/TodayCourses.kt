package com.dormpanel.phone

import com.dormpanel.app.schedule.*
import java.time.*

/** Read-only display-day intersection, including courses crossing midnight. */
internal object TodayCourses {
    data class Day(val date: LocalDate, val all: List<ClassOccurrence>, val remaining: List<ClassOccurrence>, val next: Instant)
    fun project(state: ScheduleState, now: Instant, zone: ZoneId): Day = projectDate(state,now,zone,now.atZone(zone).toLocalDate())
    fun display(state: ScheduleState, now: Instant, zone: ZoneId, showTomorrow: Boolean): Day {
        val today=project(state,now,zone)
        if (!showTomorrow || today.all.isEmpty() || today.remaining.isNotEmpty()) return today
        val tomorrow=projectDate(state,now,zone,today.date.plusDays(1))
        return tomorrow.copy(next=minOf(tomorrow.next,today.date.plusDays(1).atStartOfDay(zone).toInstant()))
    }
    fun projectDate(state: ScheduleState, now: Instant, zone: ZoneId, date: LocalDate): Day {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
        val all = ScheduleProjection.week(state, monday, zone).filter { it.start < end && it.end > start }
            .sortedWith(compareBy<ClassOccurrence> { it.start }.thenBy { it.sourceId.orEmpty() }.thenBy { it.seriesId }.thenBy { it.anchorDate }.thenBy { it.entry.id })
        val remaining = all.filter { it.end > now }
        val next = (remaining.flatMap { listOf(it.start, it.end) } + end).filter { it > now }.minOrNull()!!
        return Day(date, all, remaining, next)
    }
    fun title(state: ScheduleState?) = state?.sources?.singleOrNull()?.displayName?.takeIf { it.isNotBlank() }
        ?: if (state != null && state.sources.size > 1) "合并课表" else "我的课表"
}
