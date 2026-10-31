package com.dormpanel.phone

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.ClassOccurrence
import com.dormpanel.app.schedule.TimetableEntry
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Runs against the isolated testbed package. Verifies the platform-level PendingIntent
 * identity (not just token string comparison) and the session check-and-clear storage.
 */
class CourseTrackingSessionTest {
    @Test fun stopPendingIntentIsPerSessionAndStaleActionsCannotStopNewSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val course = ClassOccurrence(
            TimetableEntry("session-course", "测试课程", now.atZone(zone).dayOfWeek.value, 0, 0),
            now.atZone(zone).toLocalDate(), now.minusSeconds(60), now.plusSeconds(3600),
            sourceId = "source", seriesId = "series")
        try {
            CourseLiveTracking.stop(context)
            // Session A for the course instance.
            assertTrue(CourseLiveTracking.start(context, course, now, "session-a"))
            val stopA = trackingStopPendingIntent(context, "session-a")
            // A restart of the very same course is a new session B.
            assertTrue(CourseLiveTracking.start(context, course, now, "session-b"))
            val stopB = trackingStopPendingIntent(context, "session-b")
            assertEquals("session-b", CourseLiveTracking.read(context)?.token)

            // Different session tokens must produce distinct PendingIntent identities;
            // FLAG_UPDATE_CURRENT must not rewrite A into B.
            assertNotSame(stopA, stopB)
            val stillA = PendingIntent.getBroadcast(
                context, 21002,
                Intent(context, CourseNotificationReceiver::class.java)
                    .setAction(STOP_TRACKING_ACTION)
                    .setData(Uri.parse("$TRACKING_STOP_URI/session-a")),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            assertNotNull(stillA)

            // A stale action for A cannot clear or end the current B session.
            assertFalse(CourseLiveTracking.stopIfSession(context, "session-a"))
            assertEquals("session-b", CourseLiveTracking.read(context)?.token)

            // The current session's action still works.
            assertTrue(CourseLiveTracking.stopIfSession(context, "session-b"))
            assertNull(CourseLiveTracking.read(context))

            // Unknown/blank tokens (including legacy actions) are ignored.
            assertTrue(CourseLiveTracking.start(context, course, now, "session-c"))
            assertFalse(CourseLiveTracking.stopIfSession(context, null))
            assertFalse(CourseLiveTracking.stopIfSession(context, ""))
            assertEquals("session-c", CourseLiveTracking.read(context)?.token)
        } finally {
            CourseLiveTracking.stop(context)
            context.getSystemService(NotificationManager::class.java).cancel(21001)
        }
    }

    @Test fun legacySessionWithoutTokenGetsMigratedAndCannotBeStoppedByUnknownAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val now = Instant.now()
        val prefs = context.getSharedPreferences("course_live_tracking", Context.MODE_PRIVATE)
        try {
            CourseLiveTracking.stop(context)
            prefs.edit().putString("source", "source").putString("series", "series")
                .putLong("start", now.minusSeconds(60).toEpochMilli())
                .putLong("end", now.plusSeconds(3600).toEpochMilli())
                .remove("token").commit()
            // Reading a legacy session migrates it to a fresh non-blank token.
            val migrated = CourseLiveTracking.read(context)
            assertNotNull(migrated)
            assertTrue(migrated!!.token.isNotBlank())
            // A legacy action with no token cannot stop it.
            assertFalse(CourseLiveTracking.stopIfSession(context, null))
            assertEquals(migrated.token, CourseLiveTracking.read(context)?.token)
        } finally {
            CourseLiveTracking.stop(context)
        }
    }
}
