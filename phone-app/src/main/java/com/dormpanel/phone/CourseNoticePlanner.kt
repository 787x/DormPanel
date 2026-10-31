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
/**
 * Tracked session wins over the ordinary selection for as long as it is a valid,
 * in-progress occurrence. It never auto-starts from the timetable.
 */
internal data class CourseNoticePlan(
    val course: ClassOccurrence?,
    val inProgress: Boolean,
    val imminent: Boolean,
    val nextWake: Instant,
    val tracked: ClassOccurrence? = null
) {
    val tracking: Boolean get() = tracked != null
}
internal object CourseNoticePlanner {
    private class Shown(
        val course: ClassOccurrence?,
        val inProgress: Boolean,
        val imminent: Boolean,
        val tracked: ClassOccurrence?
    )

    /**
     * @param trackedSession stable identity of an explicit, user-started session, if any.
     * When it resolves to an in-progress occurrence in the snapshot, that course is shown
     * (and promoted) even if an ordinary selection would pick a different overlapping class.
     */
    fun plan(
        state: ScheduleState,
        now: Instant,
        zone: ZoneId,
        options: CourseNoticeOptions,
        trackedSession: TrackedCourse? = null
    ): CourseNoticePlan {
        val clock = object : ScheduleClock {
            override fun instant() = now
            override fun zone() = zone
            override fun locale() = Locale.getDefault()
        }
        val all = ScheduleProjection.occurrences(state, clock)
        val lead = options.leadMinutes.coerceIn(1, 60).toLong() * 60

        fun display(at: Instant): Shown {
            val happening = all.filter { it.start <= at && it.end > at }
            val current = happening.minWithOrNull(compareBy<ClassOccurrence> { it.end }.thenBy { it.start }
                .thenBy { it.sourceId ?: "" }.thenBy { it.entry.id })
            // A tracked session is only visible while the course notifications mode is ON:
            // with OFF we must never publish a tracking notification in the background.
            val tracked = if (options.mode == CourseNoticeMode.OFF) null else trackedSession?.let { session ->
                if (!session.active(at)) null else happening.firstOrNull(session::matches)
            }
            val upcoming = all.firstOrNull { it.start > at }
            val imminent = upcoming != null && !upcoming.start.minusSeconds(lead).isAfter(at)
            // Tracking has priority and is always considered in-progress for its own course.
            val selected = tracked ?: when (options.mode) {
                CourseNoticeMode.OFF -> null
                CourseNoticeMode.ALWAYS -> current ?: upcoming
                CourseNoticeMode.NEAR_CLASS -> current ?: upcoming?.takeIf { imminent }
            }
            val inProgress = tracked != null || (current != null && selected == current)
            return Shown(selected, inProgress, tracked == null && imminent && selected == upcoming, tracked)
        }
        val shown = display(now)
        // Reproject at the local date boundary (including DST). Within this projection,
        // wake only when the displayed course/phase actually changes.
        val midnight = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        val baseNext = if (options.mode == CourseNoticeMode.OFF) midnight else
            all.asSequence().flatMap {
                sequenceOf(it.start.minusSeconds(lead), it.start, it.end)
            }.filter { it > now && it < midnight }.distinct().sorted()
                .firstOrNull { display(it).course != shown.course || display(it).inProgress != shown.inProgress ||
                    display(it).imminent != shown.imminent } ?: midnight

        // A tracked in-progress class must never be refreshed later than its own end.
        val trackedEnd = shown.tracked?.end?.takeIf { it > now && it < baseNext }
        return CourseNoticePlan(shown.course, shown.inProgress, shown.imminent,
            trackedEnd ?: baseNext, shown.tracked)
    }
}