package com.dormpanel.phone

import com.dormpanel.app.schedule.*
import org.junit.Test
import org.junit.Assert.*

class SharedColorMigrationTest {
    private val state = ScheduleState(entries=listOf(TimetableEntry("x","课程",1,480,540)),sharedRecords=emptyList())
    @Test fun manualAndAutomaticPreviewLeavesOriginalSettingsIntact() {
        val id=SharedCourseColors.identity(state,null,"x")
        val manual=mapOf<String,Any?>("m$id" to 0x123456,"a$id" to 2)
        val copy=manual.toMap();val p=SharedColorMigration.previewValues(manual,state)
        assertEquals(copy,manual);assertEquals(0x123456,p.previousRgb[id]);assertEquals(listOf(1,0x12,0x34,0x56).map(Int::toByte),p.records.single().payload)
        val automatic=mapOf<String,Any?>("a$id" to 2);val q=SharedColorMigration.previewValues(automatic,state)
        assertEquals(CourseColors.palette[2],q.previousRgb[id]);assertEquals(listOf(0.toByte()),q.records.single().payload)
        assertTrue(state.sharedRecords!!.isEmpty());assertEquals(mapOf<String,Any?>("a$id" to 2),automatic)
    }
    @Test fun incompatiblePreviewFailurePreservesPreferencesAndState() {
        val id=SharedCourseColors.identity(state,null,"x");val values=mapOf<String,Any?>("m$id" to 0x123456)
        val blocked=state.copy(sharedRecords=listOf(SharedRecord("future.type","one",1,required=true,payload=emptyList())))
        assertTrue(runCatching{SharedColorMigration.previewValues(values,blocked)}.isFailure)
        assertEquals(0x123456,values["m$id"]);assertTrue(blocked.sharedRecords!!.single().required)
    }
}
