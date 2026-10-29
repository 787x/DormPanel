package com.dormpanel.app.schedule

import com.dormpanel.app.schedule.*
import java.time.*

/** Immutable display slices; no persistence or independent scheduling rules. */
object CourseDayHeaders {
    fun recentWeekLabel(state: ScheduleState?,today: LocalDate,zone: ZoneId,
        days: Pair<CourseDayProjection.Day,CourseDayProjection.Day>? = null): String {
        if(state==null) return ""
        val projected=days ?: recent(state,today.atStartOfDay(zone).toInstant(),zone)
        // Calendar evidence survives today's lessons ending; displayed sources remain first priority.
        val shown=projected.first.remaining+projected.second.all
        val participants=if(shown.isNotEmpty()) shown else projected.first.all+projected.second.all
        val sourceIds: List<String> = if(participants.isNotEmpty()) participants.map { it.sourceId ?: return "" }.distinct() else {
            // Profiles have no term-end date. A preset or a source alone cannot prove an active term.
            // Require concrete source-bound records whose observed date span overlaps this two-day window.
            if(state.entries.isNotEmpty() || state.courseSlots.any { it.enabled && it.sourceId==null }) return ""
            val from=today.atStartOfDay(zone).toInstant().toEpochMilli()
            val until=today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
            state.imported.groupBy { it.sourceId }.filterValues { records ->
                records.minOf { it.start } < until && records.maxOf { it.end } > from
            }.keys.toList()
        }
        if(sourceIds.isEmpty()) return ""
        val keys=sourceIds.map { id ->
            val source=state.sources.singleOrNull { it.id==id } ?: return ""
            source.resolvedTermKey() ?: return ""
        }.distinct()
        val key=keys.singleOrNull() ?: return ""
        val profile=state.profiles[key] ?: return ""
        val tomorrow=today.plusDays(1)
        if(profile.termKey!=key || profile.validate()!=null || profile.timezone!=zone.id || profile.phaseOn(today)==null || profile.phaseOn(tomorrow)==null) return ""
        fun week(date: LocalDate)=java.time.temporal.ChronoUnit.WEEKS.between(profile.week1Monday,date.minusDays((date.dayOfWeek.value-1).toLong()))+1
        val a=week(today); val b=week(tomorrow)
        return if(a<=0 || b<=0) "" else if(a==b) "第${a}周" else "第${a}-${b}周"
    }
    fun recent(state: ScheduleState, now: Instant, zone: ZoneId): Pair<CourseDayProjection.Day, CourseDayProjection.Day> {
        val today = CourseDayProjection.project(state, now, zone)
        return today to CourseDayProjection.projectDate(state, now, zone, today.date.plusDays(1))
    }
}
