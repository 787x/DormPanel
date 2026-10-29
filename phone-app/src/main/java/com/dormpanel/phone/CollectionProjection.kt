package com.dormpanel.phone

import com.dormpanel.app.schedule.*
import java.time.*

/** Immutable display slices; no persistence or independent scheduling rules. */
internal object CollectionProjection {
    fun recentWeekLabel(state: ScheduleState?,today: LocalDate,zone: ZoneId): String {
        if(state==null) return ""
        val tomorrow=today.plusDays(1)
        val profile=state.sources.mapNotNull { it.resolvedTermKey() }.distinct().mapNotNull { state.profiles[it] }
            .filter { it.validate()==null && it.timezone==zone.id && it.phaseOn(today)!=null && it.phaseOn(tomorrow)!=null }.singleOrNull() ?: return ""
        fun week(date: LocalDate)=java.time.temporal.ChronoUnit.WEEKS.between(profile.week1Monday,date.minusDays((date.dayOfWeek.value-1).toLong()))+1
        val a=week(today); val b=week(tomorrow)
        return if(a<=0 || b<=0) "" else if(a==b) "第${a}周" else "第${a}-${b}周"
    }
    fun recent(state: ScheduleState, now: Instant, zone: ZoneId): Pair<TodayCourses.Day, TodayCourses.Day> {
        val today = TodayCourses.project(state, now, zone)
        return today to TodayCourses.projectDate(state, now, zone, today.date.plusDays(1))
    }
}
