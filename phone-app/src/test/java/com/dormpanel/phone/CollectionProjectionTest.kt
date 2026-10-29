package com.dormpanel.phone
import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
class CollectionProjectionTest {
    val zone=ZoneId.of("Asia/Shanghai")
    val date=LocalDate.of(2026,10,26)
    fun entry(id: String,day: Int,start: Int,end: Int)=TimetableEntry(id,id,day,start,end,"A")
    @Test fun remainingTodayAndAllTomorrowWithoutCapacityTruncation() {
        val state=ScheduleState(entries=(0..9).map { entry("a$it",1,480+it*30,510+it*30) }+entry("tomorrow",2,480,540))
        val (today,tomorrow)=CollectionProjection.recent(state,date.atTime(9,0).atZone(zone).toInstant(),zone)
        assertEquals(8,today.remaining.size); assertEquals(1,tomorrow.all.size)
        val (ended,_)=CollectionProjection.recent(state,date.atTime(22,0).atZone(zone).toInstant(),zone)
        assertFalse(ended.all.isEmpty()); assertTrue(ended.remaining.isEmpty())
    }
    @Test fun weekIncludesSevenDaysOverlapsAndShortCustomCourses() {
        val state=ScheduleState(entries=(1..7).map { entry("d$it",it,480,600) }+entry("short",1,507,508))
        val grid=WeekWidgetGrid.create(state,date,zone,{ 0 },date)
        val short=grid.segments.single { it.item.seriesId=="short" }
        assertEquals(507,short.start); assertEquals(508,short.end)
        assertEquals(7,grid.segments.map { it.day }.distinct().size)
        assertTrue(grid.axis.periodLabels.isEmpty())
        assertEquals(.8f,grid.axis.y(508f)-grid.axis.y(507f),.001f)
    }
    @Test fun sundayMidnightCourseAppearsInBothWeeks() {
        val start=date.minusDays(1).atTime(23,30).atZone(zone).toInstant().toEpochMilli()
        val end=date.atTime(1,0).atZone(zone).toInstant().toEpochMilli()
        val state=ScheduleState(imported=listOf(ImportedClassOccurrence("i","s","series",null,start,"夜课",start,end,"Asia/Shanghai","A","",null)))
        val grid=WeekWidgetGrid.create(state,date,zone,{ 0 },date)
        assertEquals(0,grid.segments.first().start)
        assertEquals(60,grid.segments.first().end)
        assertTrue(WeekWidgetGrid.create(state,date.minusWeeks(1),zone,{ 0 },date).segments.any { it.day==6 })
    }
    @Test fun periodDefaultAndExplicitTimeUseSameRealOccurrences() {
        val source=ImportSource("s","课程","demo.csv","csv",null,null,"0".repeat(64),0,"Asia/Shanghai",0,0,0,BuiltInProfiles.TERM_2026_2027_1)
        val state=ScheduleState(entries=listOf(entry("long",1,480,600)),sources=listOf(source),profiles=mapOf(source.termKey!! to BuiltInProfiles.term2026))
        val periods=WeekWidgetGrid.create(state,date,zone,{ 0 },date)
        val time=WeekWidgetGrid.create(state,date,zone,{ 0 },date,false)
        assertFalse(periods.axis.periodLabels.isEmpty()); assertTrue(time.axis.periodLabels.isEmpty())
        assertEquals(periods.segments,time.segments)
        assertEquals(96f,time.axis.y(600f)-time.axis.y(480f),.001f)
        val first=periods.axis.periodLabels.first()
        assertEquals(56f,periods.axis.y(first.end.toSecondOfDay()/60f)-periods.axis.y(first.minute.toFloat()),.001f)
        assertTrue(WeekWidgetGrid.create(state.copy(sources=emptyList()),date,zone,{ 0 },date).axis.periodLabels.isEmpty())
    }
    @Test fun fiveDayRangeExcludesLateWeekendWithoutLosingSevenDayData() {
        val state=ScheduleState(entries=listOf(entry("weekday",1,480,540),entry("weekend",7,1380,1439)))
        val five=WeekWidgetGrid.create(state,date,zone,{ 0 },date,true,5)
        val seven=WeekWidgetGrid.create(state,date,zone,{ 0 },date,true,7)
        assertEquals(five.segments,seven.segments)
        assertEquals(5,five.dayCount); assertEquals(7,seven.dayCount)
        assertTrue(five.axis.height<seven.axis.height)
    }
}
