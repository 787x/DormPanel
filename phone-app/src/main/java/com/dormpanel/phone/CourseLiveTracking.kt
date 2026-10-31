package com.dormpanel.phone

import android.content.Context
import com.dormpanel.app.schedule.ClassOccurrence
import com.dormpanel.app.schedule.ScheduleClock
import com.dormpanel.app.schedule.ScheduleProjection
import com.dormpanel.app.schedule.ScheduleState
import java.time.Instant

/** 当前正在进行的课程（作为开始跟踪的候选）。纯投影，不修改任何状态。 */
internal fun currentInProgressCourse(state: ScheduleState, clock: ScheduleClock, now: Instant): ClassOccurrence? =
    ScheduleProjection.occurrences(state, clock)
        .filter { it.start <= now && it.end > now }
        .minWithOrNull(compareBy<ClassOccurrence> { it.end }.thenBy { it.start }.thenBy { it.entry.id })

/** Explicit, per-class session. No background service, and never auto-started from a timetable. */
internal data class TrackedCourse(val sourceId: String?, val seriesId: String, val start: Long, val end: Long) {
    fun matches(value: ClassOccurrence): Boolean =
        value.sourceId == sourceId && value.seriesId == seriesId &&
            value.start.toEpochMilli() == start && value.end.toEpochMilli() == end
    fun active(now: Instant): Boolean = now.toEpochMilli() in start until end
}
internal object CourseLiveTracking {
    private const val NAME = "course_live_tracking"
    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun start(context: Context, course: ClassOccurrence, now: Instant): Boolean {
        val session = TrackedCourse(course.sourceId, course.seriesId,
            course.start.toEpochMilli(), course.end.toEpochMilli())
        if (!session.active(now)) return false
        prefs(context).edit().putString("source", session.sourceId)
            .putString("series", session.seriesId)
            .putLong("start", session.start).putLong("end", session.end).apply()
        return true
    }

    fun stop(context: Context) { prefs(context).edit().clear().apply() }

    fun read(context: Context): TrackedCourse? {
        val p = prefs(context)
        val id = p.getString("series", null) ?: return null
        return TrackedCourse(p.getString("source", null), id, p.getLong("start", 0), p.getLong("end", 0))
    }

    fun hasActive(context: Context, now: Instant): Boolean {
        val session = read(context) ?: return false
        if (!session.active(now)) { stop(context); return false }
        return true
    }

    fun match(context: Context, occurrences: List<ClassOccurrence>, now: Instant): ClassOccurrence? {
        val session = read(context) ?: return null
        if (!session.active(now)) { stop(context); return null }
        return occurrences.firstOrNull(session::matches).also { if (it == null) stop(context) }
    }
}
