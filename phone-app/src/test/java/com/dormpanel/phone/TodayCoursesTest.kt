package com.dormpanel.phone

import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TodayCoursesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026,10,28)
    private fun now(hour: Int) = date.atTime(hour,0).atZone(zone).toInstant()
    private val a = TimetableEntry("a","数学",3,480,600,"A101")
    private val b = TimetableEntry("b","英语",3,540,660,"B202")
    @Test fun realEndExcludesFinishedAndPreservesOverlap() {
        val state = ScheduleState(entries=listOf(b,a))
        assertEquals(listOf("a","b"),TodayCourses.project(state,now(9),zone).remaining.map { it.seriesId })
        assertEquals(listOf("b"),TodayCourses.project(state,now(10),zone).remaining.map { it.seriesId })
        assertEquals(now(11),TodayCourses.project(state,now(10),zone).next)
        val ended = TodayCourses.project(state,now(12),zone)
        assertEquals(2,ended.all.size); assertTrue(ended.remaining.isEmpty())
        assertEquals(date.plusDays(1).atStartOfDay(zone).toInstant(),ended.next)
    }
    @Test fun adjustmentsAndOverridesUseEffectiveTimes() {
        val edited = ScheduleState(entries=listOf(a),classOverrides=listOf(ClassOverride.of(null,"a",date.toString(),
            ClassPatch(title="修改课程",timingMode="custom",startMinute=720,endMinute=780))))
        val projected = TodayCourses.project(edited,now(11),zone)
        assertEquals("修改课程",projected.remaining.single().entry.title)
        assertEquals(now(12),projected.remaining.single().start)
        assertTrue(TodayCourses.project(edited.copy(dayAdjustments=listOf(DayAdjustment(date.toString(),"none"))),now(8),zone).all.isEmpty())
    }
    @Test fun midnightAndSystemZoneIntersection() {
        val start = date.minusDays(1).atTime(23,30).atZone(zone).toInstant().toEpochMilli()
        val end = date.atTime(1,0).atZone(zone).toInstant().toEpochMilli()
        val imported = ImportedClassOccurrence("i","source","series",null,start,"夜课",start,end,"Asia/Shanghai","教室","",null)
        val state = ScheduleState(imported=listOf(imported))
        assertEquals(1,TodayCourses.project(state,now(0),zone).remaining.size)
        assertTrue(TodayCourses.project(state,now(1),zone).remaining.isEmpty())
        assertEquals(date.minusDays(1),TodayCourses.project(state,now(0),ZoneId.of("America/Los_Angeles")).date)
    }
    @Test fun tomorrowSwitchIsOptionalAndOnlyAfterTodaysCoursesEnd() {
        val state=ScheduleState(entries=listOf(a,b.copy(day=4)))
        assertEquals(date,TodayCourses.display(state,now(9),zone,true).date)
        assertEquals(date,TodayCourses.display(state,now(12),zone,false).date)
        val next=TodayCourses.display(state,now(12),zone,true)
        assertEquals(date.plusDays(1),next.date); assertEquals("b",next.remaining.single().seriesId)
        assertEquals(date.plusDays(1).atStartOfDay(zone).toInstant(),next.next)
        val afterMidnight=date.plusDays(1).atStartOfDay(zone).toInstant()
        assertEquals(date.plusDays(1),TodayCourses.display(state,afterMidnight,zone,true).date)
        assertEquals(date,TodayCourses.display(ScheduleState(entries=listOf(b.copy(day=4))),now(12),zone,true).date)
    }
    @Test fun timetableTitleUsesSourceNameAndDoesNotDependOnListPosition() {
        val source=ImportSource("s","秋季课程","demo.ics","ics",null,null,"0".repeat(64),0,"Asia/Shanghai",0,0,0)
        assertEquals("秋季课程",TodayCourses.title(ScheduleState(sources=listOf(source))))
        assertEquals("合并课表",TodayCourses.title(ScheduleState(sources=listOf(source,source.copy(id="s2",displayName="其他课程")))))
        assertEquals("我的课表",TodayCourses.title(ScheduleState()))
    }
    @Test fun stableColorSurvivesRenameAndOverride() {
        val item = TodayCourses.project(ScheduleState(entries=listOf(a)),now(8),zone).all.single()
        assertEquals(TodayCourses.colorIndex(item),TodayCourses.colorIndex(item.copy(entry=a.copy(title="新名称"))))
    }
}
