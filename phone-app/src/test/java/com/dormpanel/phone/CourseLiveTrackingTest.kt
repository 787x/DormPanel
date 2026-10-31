package com.dormpanel.phone

import com.dormpanel.app.schedule.ClassOccurrence
import com.dormpanel.app.schedule.TimetableEntry
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class CourseLiveTrackingTest {
    private val entry = TimetableEntry("id", "数学", 3, 480, 600)
    private val start = Instant.parse("2026-10-28T00:00:00Z")
    private val end = start.plusSeconds(7200)
    private val classItem = ClassOccurrence(entry, LocalDate.of(2026,10,28),start,end,sourceId="source",seriesId="series")
    private val tracked = TrackedCourse("source","series",start.toEpochMilli(),end.toEpochMilli())

    @Test fun sessionMustBeActive() {
        assertFalse(tracked.active(start.minusSeconds(1)))
        assertTrue(tracked.active(start))
        assertTrue(tracked.active(end.minusMillis(1)))
        assertFalse(tracked.active(end))
    }
    @Test fun titleChangesDoNotBreakTrackingButTimeChangesDo() {
        assertTrue(tracked.matches(classItem))
        assertTrue(tracked.matches(classItem.copy(entry=entry.copy(title="新课程名"))))
        assertFalse(tracked.matches(classItem.copy(end=end.plusSeconds(60))))
        assertFalse(tracked.matches(classItem.copy(sourceId="other")))
    }

    @Test fun stopIsBoundToTheSessionThatCreatedIt() {
        val first = tracked.copy(token = "session-a")
        val second = tracked.copy(token = "session-b")
        // Restarting the very same course instance is still a new session.
        assertTrue(first.stopAllowed("session-a"))
        assertFalse(first.stopAllowed("session-b"))
        assertFalse(second.stopAllowed("session-a"))
        assertTrue(second.stopAllowed("session-b"))
    }

    @Test fun blankOrMissingTokenCannotStopAnySession() {
        val legacy = tracked.copy(token = "")
        assertFalse(legacy.stopAllowed(null))
        assertFalse(legacy.stopAllowed(""))
        assertFalse(tracked.stopAllowed(null))
        assertFalse(tracked.stopAllowed(""))
    }
}
