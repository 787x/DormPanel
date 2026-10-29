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
        val day = CourseDayProjection.projectDate(state, now, zone, date)
        return Day(day.date, day.all, day.remaining, day.next)
    }
    fun title(state: ScheduleState?) = state?.sources?.singleOrNull()?.displayName?.takeIf { it.isNotBlank() }
        ?: if (state != null && state.sources.size > 1) "合并课表" else "我的课表"
}
