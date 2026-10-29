package com.dormpanel.app.schedule
import org.junit.Test
import org.junit.Assert.*
import java.time.*
class InheritedMoveReviewTest {
 @Test fun inheritedWeekdayMoveMustResolveTargetPeriods() {
  val dir=listOf(java.io.File("../test-vectors/pr70-inherited-move"),java.io.File("test-vectors/pr70-inherited-move")).first {it.isDirectory}
  fun state(name:String)=SyncV3Codec.decode(java.io.File(dir,"$name.dpv").readBytes()).state!!
  val base=state("base");val valid=state("valid");val invalid=state("invalid")
  val edits=valid.classOverrides
  CourseBatch.validate(base,base,edits)
  assertTrue(runCatching {CourseBatch.validate(invalid,invalid,edits)}.isFailure)
  val zone=ZoneId.of("Asia/Shanghai")
  val rows=ScheduleProjection.week(valid,LocalDate.parse("2026-09-07"),zone).map {listOf(it.seriesId,it.start.toEpochMilli(),it.end.toEpochMilli(),it.entry.location,it.teacher,it.note,it.sharedRgb)}
  assertEquals(org.json.JSONArray(java.io.File(dir,"projection.json").readText()).toString(),org.json.JSONArray(rows).toString())
  assertEquals(java.io.File(dir,"hash.txt").readText(),SyncV3Codec.businessHash(valid))
 }
}
