package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class X08eWeekGeometryTest {
    private val week = LocalDate.of(2026, 10, 26)
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun item(start: String, end: String, id: String = "a") = ClassOccurrence(
        TimetableEntry(id, "课程", 1, 480, 540), week,
        week.atTime(LocalTime.parse(start)).atZone(zone).toInstant(), week.atTime(LocalTime.parse(end)).atZone(zone).toInstant())
    private val periods = listOf(PeriodTime(1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
        PeriodTime(2, LocalTime.of(8, 55), LocalTime.of(9, 40)), PeriodTime(3, LocalTime.of(14, 0), LocalTime.of(14, 45)))
    private fun profile(key: String = "term", table: List<PeriodTime> = periods) = TermScheduleProfile(key, zone.id, week,
        listOf(SchedulePhase(week, table)))
    private fun source(id: String, key: String) = ImportSource(id, id, "x.csv", "csv", null, null, "a", 0, zone.id, 1, 0, 1, key)
    private fun state(p: TermScheduleProfile = profile()) = ScheduleState(sources = listOf(source("source", p.termKey)), profiles = mapOf(p.termKey to p))
    private fun linked() = item("08:00", "09:40").copy(sourceId = "source", linked = true, periodLabel = "1-2")

    @Test fun projectedInstantsOverrideStaleEntryAndMidnightIsClippedIntoBothDays() {
        val original = item("23:30", "23:59").copy(end = week.plusDays(1).atTime(0, 30).atZone(zone).toInstant())
        val segments = X08eWeekGeometry.segments(listOf(original), week, zone)
        assertEquals(listOf(0, 1), segments.map { it.day })
        assertEquals(1410f, segments[0].start); assertEquals(1440f, segments[0].end)
        assertEquals(0f, segments[1].start); assertEquals(30f, segments[1].end)
        assertSame(original, segments[1].item)
        assertEquals(0 to 1440, X08eWeekGeometry.range(segments))
    }
    @Test fun genuineOverlapUsesInstantsAndAdjacentShortCoursesRemainSeparate() {
        val a = item("08:00:01", "08:00:15")
        val b = item("08:00:15", "08:00:30", "b")
        val c = item("08:00:20", "08:01:00", "c")
        val groups = X08eWeekGeometry.groups(X08eWeekGeometry.segments(listOf(c, b, a), week, zone))
        assertEquals(listOf(1, 2), groups.map { it.size })
        assertTrue(groups[0][0].end < 481f)
        assertEquals(listOf(b, c), groups[1].map { it.item })
        val chain = listOf(item("08:00", "09:00"), item("08:30", "10:00", "b"), item("09:00", "09:45", "c"))
        val lanes = X08eWeekGeometry.lanes(X08eWeekGeometry.segments(chain, week, zone))
        assertEquals(2, lanes.size)
        assertEquals(listOf(chain[0], chain[2]), lanes[0].map { it.item })
        assertTrue(lanes.all { lane -> lane.zipWithNext().all { (x,y) -> x.item.end <= y.item.start } })
    }
    @Test fun actualTimeHasOneUniformScaleAndRangeIncludesFractionalMinute() {
        val axis = X08eWeekGeometry.time(480, 1260)
        assertEquals(axis.y(600f) - axis.y(540f), axis.y(900f) - axis.y(840f), .001f)
        val segment = X08eWeekGeometry.segments(listOf(item("19:59", "20:00:30")), week, zone)
        assertEquals(1260, X08eWeekGeometry.range(segment).second)
    }
    @Test fun periodAxisCompressesGapsButRetainsCustomTimeAndMultiperiodSpan() {
        val axis = X08eWeekGeometry.periods(state(), listOf(linked()), week, zone, 420, 1440)!!
        assertEquals(72f, axis.y(525f) - axis.y(480f), .001f)
        assertTrue(axis.y(840f) - axis.y(580f) < 40f)
        assertTrue(axis.y(700f) > axis.y(580f)); assertTrue(axis.y(700f) < axis.y(840f))
        assertTrue(axis.y(580f) - axis.y(480f) > 144f)
        assertEquals(1440f, axis.points.last().first)
        assertTrue(axis.points.zipWithNext().all { (a,b) -> a.first < b.first && a.second < b.second })
        listOf(420f,480f,530f,700f,1000f,1440f).forEach { assertEquals(it, axis.minute(axis.y(it)), .001f) }
    }
    @Test fun differingPhasesSourcesMissingProfilesAndTimezoneFallback() {
        val changed = profile().copy(phases = profile().phases + SchedulePhase(week.plusDays(3), periods.map { it.copy(start = it.start.plusMinutes(10), end = it.end.plusMinutes(10)) }))
        assertNull(X08eWeekGeometry.periods(state(changed), listOf(linked()), week, zone, 480, 1200))
        val p2 = profile("other", periods.map { it.copy(start = it.start.plusMinutes(5), end = it.end.plusMinutes(5)) })
        val mixed = state().copy(sources = state().sources + source("other", "other"), profiles = state().profiles + ("other" to p2))
        assertNull(X08eWeekGeometry.periods(mixed, listOf(linked(), linked().copy(sourceId = "other")), week, zone, 480, 1200))
        assertNull(X08eWeekGeometry.periods(state(), listOf(linked().copy(sourceId = "missing")), week, zone, 480, 1200))
        assertNull(X08eWeekGeometry.periods(state(), listOf(linked()), week, ZoneOffset.UTC, 480, 1200))
    }
    @Test fun stalePeriodProvenanceIsNeverPresentedAsCurrentAndColorsUseStableIdentity() {
        val item = linked()
        assertEquals(1..2, X08eWeekGeometry.currentPeriods(item, profile()))
        assertNull(X08eWeekGeometry.currentPeriods(item.copy(start = item.start.plusSeconds(60)), profile()))
        assertNull(X08eWeekGeometry.currentPeriods(item.copy(linked = false), profile()))
        val nextWeek = item.copy(date = week.plusWeeks(1), entry = item.entry.copy(title = "重命名", location = "新教室"), start = item.start.plusSeconds(604800))
        assertEquals(X08eWeekGeometry.colorIndex(item), X08eWeekGeometry.colorIndex(nextWeek))
    }
}
