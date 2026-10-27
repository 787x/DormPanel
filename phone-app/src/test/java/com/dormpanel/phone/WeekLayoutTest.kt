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
    @Test fun visibleRangeIgnoresHiddenWeekendButKeepsWeekdayMidnightPieces() {
        val weekday = occurrence("weekday","2026-12-28T08:00","2026-12-28T17:00")
        val late = occurrence("weekend","2027-01-02T22:00","2027-01-03T00:30")
        val segments = WeekLayout.segments(listOf(weekday,late),monday,zone)
        assertEquals(480 to 1080,WeekLayout.visibleRange(segments,5))
        assertEquals(0 to 1440,WeekLayout.visibleRange(segments,7))
        assertEquals(480 to 1080,WeekLayout.visibleRange(emptyList(),5))
        val mondayPiece = occurrence("fromSunday","2026-12-27T23:30","2026-12-28T00:30")
        val friday = occurrence("friday","2027-01-01T23:00","2027-01-02T01:00")
        assertEquals(0 to 1440,WeekLayout.visibleRange(WeekLayout.segments(listOf(mondayPiece,friday),monday,zone),5))
        assertSame(late,segments.first { it.day==5 }.item)
    }
    @Test fun touchPaddingDoesNotCreateOverlapAndBodyWins() {
        val items = listOf(occurrence("a","2026-12-28T08:00","2026-12-28T08:45"),
            occurrence("b","2026-12-28T08:55","2026-12-28T09:40"))
        val groups = WeekLayout.groups(WeekLayout.segments(items,monday,zone))
        val a = WeekHitArea(groups[0],0,56); val b = WeekHitArea(groups[1],64,120)
        assertEquals(listOf(a),WeekTouch.hit(listOf(a,b),55f,24))
        assertEquals(listOf(b),WeekTouch.hit(listOf(a,b),65f,24))
        assertEquals(listOf(a,b),WeekTouch.hit(listOf(a,b),60f,24))
        assertEquals(listOf(a),WeekTouch.hit(listOf(a),60f,24))
        assertTrue(WeekTouch.hit(listOf(a,b),200f,24).isEmpty())
        val tiny = WeekHitArea(groups[0],130,131)
        assertEquals(listOf(tiny),WeekTouch.hit(listOf(tiny),150f,24))
    }
    @Test fun overlapUsesExactInstantsAndKeepsConnectedOriginals() {
        val a = occurrence("a","2026-12-28T08:00","2026-12-28T09:00")
        val b = occurrence("b","2026-12-28T08:30","2026-12-28T09:30")
        val c = occurrence("c","2026-12-28T09:20","2026-12-28T10:00")
        assertEquals(listOf(a,b,c),WeekLayout.groups(WeekLayout.segments(listOf(c,a,b),monday,zone)).single().map { it.item })
        val secondsA = occurrence("secondsA","2026-12-28T08:00:00","2026-12-28T08:00:20")
        val secondsB = occurrence("secondsB","2026-12-28T08:00:30","2026-12-28T08:01:00")
        assertEquals(listOf(1,1),WeekLayout.groups(WeekLayout.segments(listOf(secondsA,secondsB),monday,zone)).map { it.size })
    }
}
