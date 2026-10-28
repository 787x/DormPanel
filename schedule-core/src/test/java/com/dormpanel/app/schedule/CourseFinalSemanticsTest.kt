package com.dormpanel.app.schedule

import org.junit.Test
import org.junit.Assert.*
import java.time.*

class CourseFinalSemanticsTest {
    private val entries=listOf(TimetableEntry("a","原课程",1,540,600,"原教室"),TimetableEntry("b","另一课程",2,660,720),TimetableEntry("c","新增课程",3,780,840))
    private val a=ClassOverride.of(null,"a",null,ClassPatch(title="旧名称"))
    private val b=ClassOverride.of(null,"b",null,ClassPatch(teacher="旧教师"))
    private val week=ClassOverride.of(null,"a","2026-10-26",ClassPatch(location="指定周教室"))
    private val base=ScheduleState(entries=entries,classOverrides=listOf(a,b,week))
    @Test fun emptyNewRecordIsNotStoredButExplicitClearSurvives() {
        assertTrue(ClassPatch().isEmpty());assertFalse(ClassPatch(note="").isEmpty())
        val state=CourseBatch.applyOverrides(base,listOf(ClassOverride.of(null,"c",null,ClassPatch())))
        assertEquals(base,state)
        val clear=ClassOverride.of(null,"c",null,ClassPatch(note=""))
        assertEquals("",CourseBatch.applyOverrides(base,listOf(clear)).editsByKey[clear.key]!!.note)
    }
    @Test fun mixedResetUpdateCreatePreservesWeeklyIdentityAndProjectionFlags() {
        val batch=listOf(a.copy(title=null),b.copy(teacher="新教师"),ClassOverride.of(null,"c",null,ClassPatch(note="")))
        CourseBatch.validate(base,base,batch)
        val state=CourseBatch.applyOverrides(base,batch)
        assertFalse(state.editsByKey.containsKey(a.key));assertEquals(week,state.editsByKey[week.key])
        val rows=CourseBatch.occurrences(state,CourseCandidate(null,"",listOf("a")),ZoneId.systemDefault(),LocalDate.of(2026,10,26))
        assertEquals("原课程",rows.single().entry.title);assertFalse(rows.single().allWeeksEdited);assertTrue(rows.single().thisWeekEdited)
    }
    @Test fun resetMergesAsDeletionAndConcurrentEditRequiresConflictDecision() {
        val local=CourseBatch.applyOverrides(base,listOf(a.copy(title=null)))
        val sameRemote=ScheduleMerge.merge(base,local,base)
        assertTrue(sameRemote.conflicts.isEmpty());assertFalse(sameRemote.state.editsByKey.containsKey(a.key))
        val changed=base.copy(classOverrides=listOf(a.copy(title="远端修改"),b,week))
        assertTrue(ScheduleMerge.merge(base,local,changed).conflicts.isNotEmpty())
        assertFalse(ScheduleMerge.merge(base,local,changed,ScheduleMerge.Choice.LOCAL).state.editsByKey.containsKey(a.key))
    }
}
