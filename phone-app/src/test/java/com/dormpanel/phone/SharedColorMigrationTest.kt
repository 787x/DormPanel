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
    @Test fun conflictNeedsDecisionAndFutureDisplayIsSafe() {
        val shared=SharedCourseColors.change(state,null,"x",0x0000ff)
        val id=SharedCourseColors.identity(state,null,"x")
        val preview=SharedColorMigration.previewValues(mapOf("m$id" to 0xff0000),shared)
        assertEquals(shared.sharedRecords,preview.records)
        assertEquals(1,preview.conflicts.size)
        assertTrue(runCatching { SharedColorMigration.decide(preview,emptyMap()) }.isFailure)
        assertEquals(shared.sharedRecords,SharedColorMigration.decide(preview,mapOf(id to SharedColorMigration.Decision.KEEP_SHARED)))
        assertEquals(0xff0000,SharedCourseColors.rgb(shared.copy(sharedRecords=SharedColorMigration.decide(preview,mapOf(id to SharedColorMigration.Decision.USE_LOCAL))),null,"x"))
        val future=shared.copy(sharedRecords=shared.sharedRecords!!.map { it.copy(schemaVersion=2,payload=emptyList()) })
        assertEquals("UNSUPPORTED",SharedCourseColors.display(future,null,"x").status)
        SharedCourseColors.rgb(future,null,"x")
    }
    @Test fun automaticModeAndPerCourseChoicesAreNotRgbEquality() {
        val two=state.copy(entries=state.entries+TimetableEntry("y","另一门",2,600,660))
        val shared=SharedCourseColors.change(SharedCourseColors.change(two,null,"x",0x123456),null,"y",0xabcdef)
        val x=SharedCourseColors.identity(two,null,"x");val y=SharedCourseColors.identity(two,null,"y")
        val same=SharedColorMigration.previewValues(mapOf("m$x" to 0x123456),shared)
        assertTrue(same.conflicts.isEmpty());assertEquals(shared.sharedRecords,same.records)
        val p=SharedColorMigration.previewValues(mapOf("a$x" to 2,"m$y" to 0xff0000),shared)
        assertEquals(2,p.conflicts.size);assertTrue(p.conflicts.all { it.courseTitles.isNotEmpty() })
        assertTrue(runCatching { SharedColorMigration.decide(p,mapOf(x to SharedColorMigration.Decision.SKIP)) }.isFailure)
        val chosen=shared.copy(sharedRecords=SharedColorMigration.decide(p,mapOf(x to SharedColorMigration.Decision.USE_LOCAL,y to SharedColorMigration.Decision.KEEP_SHARED)))
        assertEquals("AUTO",SharedCourseColors.display(chosen,null,"x").status)
        assertEquals(0xabcdef,SharedCourseColors.rgb(chosen,null,"y"))
    }
    @Test fun incompatiblePreviewFailurePreservesPreferencesAndState() {
        val id=SharedCourseColors.identity(state,null,"x");val values=mapOf<String,Any?>("m$id" to 0x123456)
        val blocked=state.copy(sharedRecords=listOf(SharedRecord("future.type","one",1,required=true,payload=emptyList())))
        assertTrue(runCatching{SharedColorMigration.previewValues(values,blocked)}.isFailure)
        assertEquals(0x123456,values["m$id"]);assertTrue(blocked.sharedRecords!!.single().required)
    }
}
