package com.dormpanel.phone

import com.dormpanel.app.schedule.*
import java.time.*

/** Immutable display slices; no persistence or independent scheduling rules. */
internal object CollectionProjection {
    fun recentWeekLabel(state: ScheduleState?,today: LocalDate,zone: ZoneId,
        days: Pair<TodayCourses.Day,TodayCourses.Day>? = null): String {
        if(state==null) return ""
        val projected=days ?: recent(state,today.atStartOfDay(zone).toInstant(),zone)
        val participants=projected.first.remaining+projected.second.all
        if(participants.isEmpty()) return ""
        val keys=participants.map { item ->
            val id=item.sourceId ?: return "" // Local courses have no verifiable term association.
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
    fun recent(state: ScheduleState, now: Instant, zone: ZoneId): Pair<TodayCourses.Day, TodayCourses.Day> {
        val today = TodayCourses.project(state, now, zone)
        return today to TodayCourses.projectDate(state, now, zone, today.date.plusDays(1))
    }
}
