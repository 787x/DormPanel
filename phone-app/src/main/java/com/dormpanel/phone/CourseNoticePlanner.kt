package com.dormpanel.phone

import com.dormpanel.app.schedule.*
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Read-only notification policy. Never mutates the shared schedule. */
internal enum class CourseNoticeMode { OFF, ALWAYS, NEAR_CLASS }
internal data class CourseNoticeOptions(
    val mode: CourseNoticeMode = CourseNoticeMode.OFF,
    val leadMinutes: Int = 15,
    val hideOnLockscreen: Boolean = true
)
internal data class CourseNoticePlan(
    val course: ClassOccurrence?,
    val inProgress: Boolean,
    val imminent: Boolean,
    val nextWake: Instant
)
internal object CourseNoticePlanner {
    fun plan(state: ScheduleState, now: Instant, zone: ZoneId, options: CourseNoticeOptions): CourseNoticePlan {
        val clock = object : ScheduleClock {
            override fun instant() = now
            override fun zone() = zone
            override fun locale() = Locale.getDefault()
        }
        val all = ScheduleProjection.occurrences(state, clock)
        val lead = options.leadMinutes.coerceIn(1, 60).toLong() * 60
        fun display(at: Instant): Triple<ClassOccurrence?, Boolean, Boolean> {
            val current = all.filter { it.start <= at && it.end > at }
                .minWithOrNull(compareBy<ClassOccurrence> { it.end }.thenBy { it.start }
                    .thenBy { it.sourceId ?: "" }.thenBy { it.entry.id })
            val upcoming = all.firstOrNull { it.start > at }
            val imminent = upcoming != null && !upcoming.start.minusSeconds(lead).isAfter(at)
            val selected = when (options.mode) {
                CourseNoticeMode.OFF -> null
                CourseNoticeMode.ALWAYS -> current ?: upcoming
                CourseNoticeMode.NEAR_CLASS -> current ?: upcoming?.takeIf { imminent }
            }
            return Triple(selected, current != null && selected == current,
                imminent && selected == upcoming)
        }
        val shown = display(now)
        // Reproject at the local date boundary (including DST). Within this projection,
        // wake only when the selected course or its displayed phase actually changes.
        val midnight = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        val next = if (options.mode == CourseNoticeMode.OFF) midnight else
            all.asSequence().flatMap {
                sequenceOf(it.start.minusSeconds(lead), it.start, it.end)
            }.filter { it > now && it < midnight }.distinct().sorted()
                .firstOrNull { display(it) != shown } ?: midnight
        return CourseNoticePlan(shown.first, shown.second, shown.third, next)
    }
}
