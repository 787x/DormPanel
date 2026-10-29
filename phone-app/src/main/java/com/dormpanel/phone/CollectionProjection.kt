package com.dormpanel.phone

import com.dormpanel.app.schedule.*
import java.time.*

/** Immutable display slices; no persistence or independent scheduling rules. */
internal object CollectionProjection {
    fun recentWeekLabel(state: ScheduleState?,today: LocalDate,zone: ZoneId,
        days: Pair<TodayCourses.Day,TodayCourses.Day>? = null): String {
        fun shared(day: TodayCourses.Day) = CourseDayProjection.Day(day.date, day.all, day.remaining, day.next)
        return CourseDayHeaders.recentWeekLabel(state, today, zone, days?.let { shared(it.first) to shared(it.second) })
    }
    fun recent(state: ScheduleState, now: Instant, zone: ZoneId): Pair<TodayCourses.Day, TodayCourses.Day> {
        val today = TodayCourses.project(state, now, zone)
        return today to TodayCourses.projectDate(state, now, zone, today.date.plusDays(1))
    }
}
