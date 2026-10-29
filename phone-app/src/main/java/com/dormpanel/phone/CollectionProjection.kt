package com.dormpanel.phone

import com.dormpanel.app.schedule.*
import java.time.*

/** Immutable display slices; no persistence or independent scheduling rules. */
internal object CollectionProjection {
    fun recent(state: ScheduleState, now: Instant, zone: ZoneId): Pair<TodayCourses.Day, TodayCourses.Day> {
        val today = TodayCourses.project(state, now, zone)
        return today to TodayCourses.projectDate(state, now, zone, today.date.plusDays(1))
    }
}
