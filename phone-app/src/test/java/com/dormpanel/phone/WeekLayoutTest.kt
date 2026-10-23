package com.dormpanel.phone

import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WeekLayoutTest {
    private val monday = LocalDate.of(2026, 12, 28)
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun occurrence(id: String, start: String, end: String) = ClassOccurrence(
        TimetableEntry(id, id, 1, 0, 1), monday, LocalDateTime.parse(start).atZone(zone).toInstant(),
        LocalDateTime.parse(end).atZone(zone).toInstant())
    @Test fun clipsBothWeekEdgesAndMidnightWithoutChangingIdentity() {
        val before = occurrence("before", "2026-12-27T23:00", "2026-12-28T01:00")
        val after = occurrence("after", "2027-01-03T23:00", "2027-01-04T01:00")
        val middle = occurrence("middle", "2026-12-30T23:30", "2026-12-31T00:30")
        val result = WeekLayout.segments(listOf(after, middle, before), monday, zone)
        assertEquals(listOf(0, 2, 3, 6), result.map { it.day })
        assertEquals(listOf(0, 1410, 0, 1380), result.map { it.start })
        assertEquals(listOf(60, 1440, 30, 1440), result.map { it.end })
        assertSame(before, result.first().item)
    }
    @Test fun overlapAndShortTouchTargetsRemainReachableAndStable() {
        val a = occurrence("a", "2026-12-28T08:00", "2026-12-28T08:01")
        val b = occurrence("b", "2026-12-28T08:10", "2026-12-28T09:00")
        val c = occurrence("c", "2026-12-28T09:00", "2026-12-28T10:30")
        val result = WeekLayout.segments(listOf(c,b,a), monday, zone)
        assertEquals(result, WeekLayout.segments(listOf(a,c,b), monday, zone))
        assertEquals(listOf(1,1,1), WeekLayout.groups(result).map { it.size })
        assertEquals(90, result.last().end-result.last().start)
    }
    @Test fun emptyWeekIsEmpty() { assertTrue(WeekLayout.segments(emptyList(), monday, zone).isEmpty()) }
}
