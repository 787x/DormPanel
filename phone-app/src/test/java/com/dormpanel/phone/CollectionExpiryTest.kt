package com.dormpanel.phone
import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
class CollectionExpiryTest {
    val zone=ZoneId.of("Asia/Shanghai")
    val date=LocalDate.of(2026,10,29)
    fun at(hour: Int,minute: Int)=date.atTime(hour,minute).atZone(zone).toInstant()
    @Test fun recentExpiresExactlyAtProjectedCourseBoundaryAndRefreshDropsEndedCourse() {
        val state=ScheduleState(entries=listOf(TimetableEntry("a","A",4,480,590),TimetableEntry("b","B",4,610,720),TimetableEntry("t","Tomorrow",5,480,540)))
        val (a,b)=CollectionProjection.recent(state,at(9,0),zone)
        val snapshot=CollectionWidgets.Snapshot(emptyList(),null,basis=CollectionBasis(date,zone),capturedAt=at(9,0),validUntil=minOf(a.next,b.next))
        assertEquals(listOf("a","b"),a.remaining.map { it.entry.id })
        assertTrue(snapshot.current(at(9,40),zone)); assertFalse(snapshot.current(at(9,50),zone))
        val next=CollectionProjection.recent(state,at(9,50),zone)
        assertEquals(listOf("b"),next.first.remaining.map { it.entry.id }); assertEquals(b.all,next.second.all)
        assertFalse(snapshot.current(at(8,59),zone))
    }
    @Test fun weekExpiresAtNaturalMidnightAcrossWeekAndZoneChanges() {
        val sunday=LocalDate.of(2026,11,1)
        val start=sunday.atTime(9,0).atZone(zone).toInstant()
        val snapshot=CollectionWidgets.Snapshot(emptyList(),null,basis=CollectionBasis(sunday,zone),capturedAt=start,validUntil=sunday.plusDays(1).atStartOfDay(zone).toInstant())
        assertTrue(snapshot.current(start.plusSeconds(3600),zone))
        assertFalse(snapshot.current(sunday.plusDays(1).atStartOfDay(zone).toInstant(),zone))
        assertFalse(snapshot.current(start,ZoneId.of("UTC")))
    }
    @Test fun midnightBudgetUsesNaturalDayAcrossDst() {
        val dst=ZoneId.of("America/New_York"); val day=LocalDate.of(2026,11,1)
        val start=day.atStartOfDay(dst).toInstant(); val end=day.plusDays(1).atStartOfDay(dst).toInstant()
        assertEquals(25,Duration.between(start,end).toHours())
        val snapshot=CollectionWidgets.Snapshot(emptyList(),null,basis=CollectionBasis(day,dst),capturedAt=start,validUntil=end)
        assertTrue(snapshot.current(start.plusSeconds(24*3600),dst)); assertFalse(snapshot.current(end,dst))
    }
}
