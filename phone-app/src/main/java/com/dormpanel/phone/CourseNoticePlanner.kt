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
    val nextWake: Instant,
    val occurrences: List<ClassOccurrence>
)
internal object CourseNoticePlanner {
    fun plan(state: ScheduleState, now: Instant, zone: ZoneId, options: CourseNoticeOptions): CourseNoticePlan {
        val clock = object : ScheduleClock {
            override fun instant() = now
            override fun zone() = zone
            override fun locale() = Locale.getDefault()
        }
        val all = ScheduleProjection.occurrences(state, clock)
        val current = all.filter { it.start <= now && it.end > now }
            .minWithOrNull(compareBy<ClassOccurrence> { it.end }.thenBy { it.start }.thenBy { it.entry.id })
        val upcoming = all.firstOrNull { it.start > now }
        val lead = options.leadMinutes.coerceIn(1, 60).toLong() * 60
        val imminent = upcoming != null && !upcoming.start.minusSeconds(lead).isAfter(now)
        val selected = when (options.mode) {
            CourseNoticeMode.OFF -> null
            CourseNoticeMode.ALWAYS -> current ?: upcoming
            CourseNoticeMode.NEAR_CLASS -> current ?: upcoming?.takeIf { imminent }
        }
        // Every overlap boundary matters; local midnight handles new imported schedules.
        val midnight = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        val next = (sequenceOf(midnight) + all.asSequence().flatMap {
            sequenceOf(it.start.minusSeconds(lead), it.start, it.end)
        }).filter { it > now }.minOrNull() ?: now.plusSeconds(1800)
        return CourseNoticePlan(
            selected,
            current != null && selected == current,
            imminent && selected == upcoming,
            next,
            all
        )
    }
}
