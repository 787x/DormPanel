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
    @Test fun recentHeadingShowsSingleOrCrossTeachingWeekWithoutGuessing() {
        val source=ImportSource("s","课程","demo.csv","csv",null,null,"0".repeat(64),0,"Asia/Shanghai",0,0,0,BuiltInProfiles.TERM_2026_2027_1)
        val imported=(0..8).map { n ->
            val start=LocalDate.of(2026,10,26).plusDays(n.toLong()).atTime(8,0).atZone(zone).toInstant().toEpochMilli()
            ImportedClassOccurrence("week$n","s","week$n",null,start,"课程",start,start+3600000,zone.id,"A","",null)
        }
        val state=ScheduleState(sources=listOf(source),imported=imported,profiles=mapOf(source.termKey!! to BuiltInProfiles.term2026))
        assertEquals("第9周",CollectionProjection.recentWeekLabel(state,LocalDate.of(2026,10,29),zone))
        assertEquals("第9-10周",CollectionProjection.recentWeekLabel(state,LocalDate.of(2026,11,1),zone))
        assertEquals("第10周",CollectionProjection.recentWeekLabel(state,LocalDate.of(2026,11,2),zone))
        assertEquals("",CollectionProjection.recentWeekLabel(state,LocalDate.of(2026,11,1),ZoneId.of("UTC")))
        assertEquals("",CollectionProjection.recentWeekLabel(ScheduleState(),date,zone))
    }
    @Test fun relevantSourcesMustAllResolveToTheSameValidTerm() {
        val today=LocalDate.of(2026,10,29)
        val a=ImportSource("a","A","a.csv","csv",null,null,"0".repeat(64),0,zone.id,0,0,0,BuiltInProfiles.TERM_2026_2027_1)
        fun item(id: String,day: LocalDate): ImportedClassOccurrence {
            val start=day.atTime(8,0).atZone(zone).toInstant().toEpochMilli()
            return ImportedClassOccurrence(id,id,id,null,start,"课程",start,start+3600000,zone.id,"教室","",null)
        }
        val base=ScheduleState(sources=listOf(a),imported=listOf(item("a",today)),profiles=mapOf(a.termKey!! to BuiltInProfiles.term2026))
        assertEquals("第9周",CollectionProjection.recentWeekLabel(base,today,zone))
        val b=a.copy(id="b")
        val mixed=base.copy(sources=listOf(a,b),imported=base.imported+item("b",today.plusDays(1)))
        assertEquals("第9周",CollectionProjection.recentWeekLabel(mixed,today,zone))
        assertEquals("",CollectionProjection.recentWeekLabel(mixed.copy(sources=listOf(a,b.copy(termKey=null))),today,zone))
        assertEquals("",CollectionProjection.recentWeekLabel(mixed.copy(sources=listOf(a,b.copy(termKey="missing"))),today,zone))
        val other=BuiltInProfiles.term2026.copy(termKey="2027-2028-1")
        assertEquals("",CollectionProjection.recentWeekLabel(mixed.copy(sources=listOf(a,b.copy(termKey=other.termKey)),profiles=base.profiles+(other.termKey to other)),today,zone))
        assertEquals("",CollectionProjection.recentWeekLabel(base.copy(profiles=mapOf(a.termKey!! to other)),today,zone))
        val invalid=BuiltInProfiles.term2026.copy(week1Monday=today)
        assertEquals("",CollectionProjection.recentWeekLabel(base.copy(profiles=mapOf(a.termKey!! to invalid)),today,zone))
        assertEquals("",CollectionProjection.recentWeekLabel(mixed.copy(sources=listOf(a,b.copy(termKey=other.termKey)),profiles=base.profiles+(other.termKey to other.copy(week1Monday=today))),today,zone))
        val historical=base.copy(sources=listOf(a,b.copy(termKey=null)),imported=base.imported+item("b",today.minusMonths(2)))
        assertEquals("第9周",CollectionProjection.recentWeekLabel(historical,today,zone))
        assertEquals("",CollectionProjection.recentWeekLabel(base.copy(entries=listOf(entry("local",4,480,540))),today,zone))
    }
    @Test fun endedAndEmptyDaysKeepOnlyEvidenceBackedCurrentTeachingWeek() {
        val today=LocalDate.of(2026,10,29)
        val source=ImportSource("a","A","a.csv","csv",null,null,"0".repeat(64),0,zone.id,0,0,0,BuiltInProfiles.TERM_2026_2027_1)
        fun occurrence(id: String,owner: String,date: LocalDate): ImportedClassOccurrence {
            val start=date.atTime(8,0).atZone(zone).toInstant().toEpochMilli()
            return ImportedClassOccurrence(id,owner,id,null,start,"课程",start,start+3600000,zone.id,"A","",null)
        }
        val base=ScheduleState(sources=listOf(source),profiles=mapOf(source.termKey!! to BuiltInProfiles.term2026))
        val ended=base.copy(imported=listOf(occurrence("ended","a",today)))
        val days=CollectionProjection.recent(ended,today.atTime(20,0).atZone(zone).toInstant(),zone)
        assertTrue(days.first.remaining.isEmpty()); assertTrue(days.second.all.isEmpty())
        assertEquals("第9周",CollectionProjection.recentWeekLabel(ended,today,zone,days))
        val empty=base.copy(imported=listOf(occurrence("before","a",today.minusDays(3)),occurrence("after","a",today.plusDays(4))))
        val emptyDays=CollectionProjection.recent(empty,today.atTime(12,0).atZone(zone).toInstant(),zone)
        assertTrue(emptyDays.first.all.isEmpty()); assertTrue(emptyDays.second.all.isEmpty())
        assertEquals("第9周",CollectionProjection.recentWeekLabel(empty,today,zone,emptyDays))
        val sunday=LocalDate.of(2026,11,1)
        assertEquals("第9-10周",CollectionProjection.recentWeekLabel(empty,sunday,zone))
        assertEquals("",CollectionProjection.recentWeekLabel(base,today,zone)) // No records: preset is not evidence.
        val b=source.copy(id="b")
        val same=empty.copy(sources=listOf(source,b),imported=empty.imported+listOf(occurrence("b1","b",today.minusDays(2)),occurrence("b2","b",today.plusDays(5))))
        assertEquals("第9周",CollectionProjection.recentWeekLabel(same,today,zone))
        assertEquals("",CollectionProjection.recentWeekLabel(same.copy(sources=listOf(source,b.copy(termKey=null))),today,zone))
        val other=BuiltInProfiles.term2026.copy(termKey="other")
        assertEquals("",CollectionProjection.recentWeekLabel(same.copy(sources=listOf(source,b.copy(termKey="other")),profiles=base.profiles+("other" to other)),today,zone))
        val history=empty.copy(sources=listOf(source,b.copy(termKey=null)),imported=empty.imported+occurrence("old","b",today.minusMonths(2)))
        assertEquals("第9周",CollectionProjection.recentWeekLabel(history,today,zone))
        assertEquals("",CollectionProjection.recentWeekLabel(base.copy(imported=listOf(occurrence("old","a",today.minusMonths(2)))),today,zone))
        assertEquals("",CollectionProjection.recentWeekLabel(empty,today,ZoneId.of("UTC")))
        assertEquals("",CollectionProjection.recentWeekLabel(empty.copy(profiles=mapOf(source.termKey!! to BuiltInProfiles.term2026.copy(week1Monday=LocalDate.of(2026,11,2)))),today,zone))
        assertEquals("",CollectionProjection.recentWeekLabel(empty.copy(profiles=mapOf(source.termKey!! to BuiltInProfiles.term2026.copy(week1Monday=today))),today,zone))
    }
}
