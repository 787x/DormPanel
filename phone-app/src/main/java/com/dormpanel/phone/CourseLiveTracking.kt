package com.dormpanel.phone

import android.content.Context
import com.dormpanel.app.schedule.ClassOccurrence
import com.dormpanel.app.schedule.ScheduleClock
import com.dormpanel.app.schedule.ScheduleProjection
import com.dormpanel.app.schedule.ScheduleState
import java.time.Instant
import java.util.UUID

/** Intent identity for the tracking-stop action: one PendingIntent per tracking session.
 *  The token is also carried as an extra for validation; it contains no course content. */
internal const val EXTRA_TRACKING_SESSION = "com.dormpanel.phone.extra.TRACKING_SESSION"
internal const val TRACKING_STOP_URI = "dormpanel://course-tracking"

/** 当前正在进行的课程（作为开始跟踪的候选）。纯投影，不修改任何状态。 */
internal fun currentInProgressCourse(state: ScheduleState, clock: ScheduleClock, now: Instant): ClassOccurrence? =
    ScheduleProjection.occurrences(state, clock)
        .filter { it.start <= now && it.end > now }
        .minWithOrNull(compareBy<ClassOccurrence> { it.end }.thenBy { it.start }.thenBy { it.entry.id })

/** Explicit, per-class session. No background service, and never auto-started from a timetable.
 *  [token] identifies one user-started session (a re-start of the same course gets a new token);
 *  [sourceId]/[seriesId]/[start]/[end] remain the course-instance identity. */
internal data class TrackedCourse(
    val sourceId: String?,
    val seriesId: String,
    val start: Long,
    val end: Long,
    val token: String = ""
) {
    fun matches(value: ClassOccurrence): Boolean =
        value.sourceId == sourceId && value.seriesId == seriesId &&
            value.start.toEpochMilli() == start && value.end.toEpochMilli() == end
    fun active(now: Instant): Boolean = now.toEpochMilli() in start until end

    /** A stop request is only valid for the session that created its notification. */
    fun stopAllowed(requestToken: String?): Boolean =
        token.isNotBlank() && !requestToken.isNullOrBlank() && token == requestToken
}

internal object CourseLiveTracking {
    private const val NAME = "course_live_tracking"
    private val lock = Any()
    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Starts a new session. The token is persisted with the session so it survives process
     *  rebuild; calling [start] again always creates a new token. */
    fun start(
        context: Context,
        course: ClassOccurrence,
        now: Instant,
        token: String = UUID.randomUUID().toString()
    ): Boolean {
        val session = TrackedCourse(course.sourceId, course.seriesId,
            course.start.toEpochMilli(), course.end.toEpochMilli(), token)
        if (!session.active(now) || token.isBlank()) return false
        synchronized(lock) {
            prefs(context).edit().putString("source", session.sourceId)
                .putString("series", session.seriesId)
                .putString("token", session.token)
                .putLong("start", session.start).putLong("end", session.end).commit()
        }
        return true
    }

    /** Unconditional app-internal stop. Notification actions use [stopIfSession]. */
    fun stop(context: Context) = synchronized(lock) { clearLocked(context) }

    /** Check-and-clear is one synchronized step, so a newly started session can never be
     *  cleared by a late stop request for the previous session. */
    fun stopIfSession(context: Context, requestToken: String?): Boolean = synchronized(lock) {
        val session = readLocked(context) ?: return@synchronized false
        if (!session.stopAllowed(requestToken)) return@synchronized false
        clearLocked(context)
        true
    }

    fun read(context: Context): TrackedCourse? = synchronized(lock) { readLocked(context) }

    fun hasActive(context: Context, now: Instant): Boolean = synchronized(lock) {
        val session = readLocked(context) ?: return@synchronized false
        if (!session.active(now)) { clearLocked(context); return@synchronized false }
        true
    }

    fun match(context: Context, occurrences: List<ClassOccurrence>, now: Instant): ClassOccurrence? =
        synchronized(lock) {
            val session = readLocked(context) ?: return@synchronized null
            if (!session.active(now)) { clearLocked(context); return@synchronized null }
            occurrences.firstOrNull(session::matches).also { if (it == null) clearLocked(context) }
        }

    private fun clearLocked(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun readLocked(context: Context): TrackedCourse? {
        val p = prefs(context)
        val series = p.getString("series", null) ?: return null
        val start = p.getLong("start", 0)
        val end = p.getLong("end", 0)
        if (start <= 0 || end <= start) return null
        // Legacy sessions (from before session tokens existed) get a fresh token on read.
        // An old notification's stop intent has no token, so it can never match this and
        // the next published notification carries the migrated token.
        var token = p.getString("token", null)
        if (token.isNullOrBlank()) {
            token = UUID.randomUUID().toString()
            p.edit().putString("token", token).apply()
        }
        return TrackedCourse(p.getString("source", null), series, start, end, token)
    }
}
