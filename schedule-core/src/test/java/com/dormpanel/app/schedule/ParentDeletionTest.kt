package com.dormpanel.app.schedule
import org.junit.Test
import org.junit.Assert.*
class ParentDeletionTest {
    class Fake(var saved:ScheduleState):ScheduleStore {
        var pending:((Result<Unit>)->Unit)?=null
        override fun load(callback:(Result<ScheduleState>)->Unit)=callback(Result.success(saved))
        override fun put(event:CalendarEvent,callback:(Result<Unit>)->Unit) {}
        override fun put(entry:TimetableEntry,callback:(Result<Unit>)->Unit) {}
        override fun deleteEvent(id:String,callback:(Result<Unit>)->Unit) {}
        override fun deleteEntry(id:String,callback:(Result<Unit>)->Unit) {pending=callback}
        override fun close() {}
        fun finish(id:String) {val result=runCatching {SharedCourseSchedule.requireDeletable(saved,id);saved=saved.copy(entries=saved.entries.filterNot {it.id==id})};pending!!(result)}
    }
    @Test fun nodeMultiSeriesTransactionVector() {
        val dir=listOf(java.io.File("../test-vectors/pr70-multi"),java.io.File("test-vectors/pr70-multi")).first {it.isDirectory}
        val state=SyncV3Codec.decode(java.io.File(dir,"state.dpv").readBytes()).state!!
        assertEquals(java.io.File(dir,"hash.txt").readText(),SyncV3Codec.businessHash(state))
        val rows=listOf("2026-09-07","2026-10-12").flatMap {week->ScheduleProjection.week(state,java.time.LocalDate.parse(week),java.time.ZoneId.of("Asia/Shanghai"))}.map {listOf(it.seriesId,it.start.toEpochMilli(),it.end.toEpochMilli(),it.entry.location,it.teacher,it.note,it.sharedRgb)}
        assertEquals(org.json.JSONArray(java.io.File(dir,"projection.json").readText()).toString(),org.json.JSONArray(rows).toString())
    }
    @Test fun pendingFailureAndRacingReferenceNeverOptimisticallyDeleteParent() {
        val initial=ScheduleState(entries=listOf(TimetableEntry("x","Math",1,480,540)),sharedRecords=emptyList())
        val store=Fake(initial);val source=ScheduleSource(store);var result:Result<Unit>?=null
        source.deleteEntry("x") {result=it};assertEquals(initial.entries,source.state.entries)
        val slot=CourseSlot("slot",null,"x",null,"2026-09-07",listOf(1),1,480,540,0,0,false,"Math","","","")
        store.saved=initial.copy(sharedRecords=listOf(SharedCourseSchedule.record(slot)));store.finish("x")
        assertTrue(result!!.isFailure);assertFalse(source.error);assertEquals(store.saved,source.state);assertEquals(1,source.state.entries.size)
        for(enabled in listOf(true,false)) assertTrue(runCatching {SharedCourseSchedule.requireDeletable(store.saved.copy(sharedRecords=listOf(SharedCourseSchedule.record(slot.copy(enabled=enabled)))),"x")}.isFailure)
        store.saved=initial;source.reload();source.deleteEntry("x") {result=it};store.finish("x");assertTrue(result!!.isSuccess);assertTrue(source.state.entries.isEmpty())
        store.saved=initial.copy(sharedRecords=null);source.reload();source.deleteEntry("x") {result=it};store.finish("x");assertTrue(result!!.isSuccess);assertTrue(source.state.entries.isEmpty())
    }
}
