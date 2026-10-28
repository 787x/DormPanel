package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class SharedCourseScheduleTest {
    private val slot = CourseSlot("slot-1", null, "x", null, "2026-09-07", listOf(1,3,5), 2, 480, 540, 0, 0, true, "Math", "A", "T", "N")
    @Test fun strictCodecAndCapability() {
        val r = SharedCourseSchedule.record(slot)
        assertEquals(slot, SharedCourseSchedule.decode(r))
        val vector=listOf(java.io.File("../test-vectors/course-schedule/payload.hex"),java.io.File("test-vectors/course-schedule/payload.hex")).first { it.isFile }
        assertEquals(vector.readText().trim(),r.payload.joinToString("") { "%02x".format(it) })
        assertTrue(runCatching { SharedTypeRegistry(listOf(SharedCourseColor)).writable(listOf(r)) }.isFailure)
        assertTrue(runCatching { SharedCourseSchedule.record(slot.copy(weeks=listOf(3,1))) }.isFailure)
        assertTrue(runCatching { SharedCourseSchedule.record(slot.copy(id="x")) }.isFailure)
        assertTrue(runCatching { SharedCourseSchedule.decode(r.copy(payload=r.payload + 0.toByte())) }.isFailure)
        assertTrue(SharedRecords.merge(listOf(r),emptyList(),listOf(SharedCourseSchedule.record(slot.copy(location="B")))).conflicts.isNotEmpty())
    }
    @Test fun atomicCandidateStaleGuardAndLegacyProtection() {
        val state=ScheduleState(entries=listOf(TimetableEntry("x","Math",1,480,540)),sharedRecords=emptyList())
        val candidate=CourseCandidate(null,"Math",listOf("x"))
        val edit=ClassOverride.of(null,"x",null,ClassPatch(title="New"))
        val next=CourseBatch.withColor(state,state,listOf(edit),candidate,0x123456,listOf(SharedCourseSchedule.record(slot)))
        assertEquals("New",next.classOverrides.single().title)
        assertEquals(0x123456,SharedCourseColors.rgb(next,null,"x"))
        assertEquals(1,SharedCourseSchedule.slots(next).size)
        assertTrue(state.sharedRecords!!.isEmpty());assertTrue(state.classOverrides.isEmpty())
        assertTrue(runCatching { CourseBatch.withColor(next,state,listOf(edit),candidate,null) }.isFailure)
        assertTrue(runCatching { CourseBatch.withColor(state.copy(sharedRecords=null),state,emptyList(),candidate,null) }.isFailure)
        val orphan=SharedCourseSchedule.record(slot.copy(ownerSeries="missing"))
        assertTrue(runCatching { CourseBatch.withColor(state,state,emptyList(),candidate,null,listOf(orphan)) }.isFailure)
    }
    @Test fun replacementKeepsOriginalExceptionAnchorAndCopiesAreIndependent() {
        val replace=slot.copy(replacesSeries="x")
        val copy=slot.copy(id="copy",weekday=3)
        val weekly=ClassOverride.of(null,"x","2026-09-07",ClassPatch(location="weekly"))
        val state=ScheduleState(entries=listOf(TimetableEntry("x","Math",1,480,540)),classOverrides=listOf(weekly),
            sharedRecords=listOf(SharedCourseSchedule.record(replace),SharedCourseSchedule.record(copy)))
        val rows=ScheduleProjection.week(state,LocalDate.parse("2026-09-07"),ZoneId.of("Asia/Shanghai"))
        assertEquals(2,rows.size)
        assertEquals("weekly",rows.single { it.seriesId=="x" }.entry.location)
        assertEquals("A",rows.single { it.seriesId=="copy" }.entry.location)
        assertEquals(LocalDate.parse("2026-09-07"),rows.single { it.seriesId=="x" }.anchorDate)
        val disabled=state.copy(sharedRecords=listOf(SharedCourseSchedule.record(replace.copy(enabled=false))))
        assertTrue(ScheduleProjection.week(disabled,LocalDate.parse("2026-09-07"),ZoneId.of("Asia/Shanghai")).isEmpty())
        val overnight=state.copy(sharedRecords=listOf(SharedCourseSchedule.record(replace.copy(startMinute=1380,endMinute=60))))
        val night=ScheduleProjection.week(overnight,LocalDate.parse("2026-09-07"),ZoneId.of("Asia/Shanghai")).single()
        assertEquals(7200,Duration.between(night.start,night.end).seconds)
    }
    @Test fun projectionAndIndependentSlots() {
        val state = ScheduleState(entries=listOf(TimetableEntry("x","Math",1,480,540)), sharedRecords=listOf(SharedCourseSchedule.record(slot)))
        val vector=listOf(java.io.File("../test-vectors/course-schedule/business-hash.txt"),java.io.File("test-vectors/course-schedule/business-hash.txt")).first { it.isFile }
        assertEquals(vector.readText().trim(),SyncV3Codec.businessHash(state))
        val zone=ZoneId.of("Asia/Shanghai")
        assertEquals(2, ScheduleProjection.week(state,LocalDate.parse("2026-09-07"),zone).size)
        assertEquals(1, ScheduleProjection.week(state,LocalDate.parse("2026-09-14"),zone).size)
        val other=SharedCourseSchedule.record(slot.copy(id="slot-2",weekday=3))
        assertTrue(SharedRecords.merge(emptyList(),state.sharedRecords!!,listOf(other)).conflicts.isEmpty())
    }
}
