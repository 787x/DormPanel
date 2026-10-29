package com.dormpanel.app.schedule
import org.junit.Test
import org.junit.Assert.*
import java.time.*
class PeriodPhaseReviewTest {
 @Test fun actualDatesMatchNodeAcrossSparsePhasesAndTimezones() {
  val dir=listOf(java.io.File("../test-vectors/pr70-phases"),java.io.File("test-vectors/pr70-phases")).first {it.isDirectory}
  fun state(name:String)=SyncV3Codec.decode(java.io.File(dir,"$name.dpv").readBytes()).state!!
  val base=state("base");val valid=state("valid");val invalid=state("invalid")
  CourseBatch.validate(base,base,valid.classOverrides)
  assertTrue(runCatching {CourseBatch.validate(invalid,invalid,valid.classOverrides)}.isFailure)
  val edgeStart=ZonedDateTime.parse("2026-09-13T23:30-04:00[America/New_York]").toInstant().toEpochMilli()
  val row=base.imported.first().copy(start=edgeStart,end=edgeStart+3600000,originalStart=edgeStart)
  val edge=base.copy(imported=listOf(row),sources=base.sources.map {it.copy(occurrenceCount=1,firstStart=edgeStart,lastEnd=edgeStart+3600000)})
  CourseBatch.validate(edge,edge,valid.classOverrides)
  val edgeFuture=CourseBatch.applyOverrides(edge,valid.classOverrides)
  val edgeRows=ScheduleProjection.week(edgeFuture,LocalDate.parse("2026-09-07"),ZoneId.of("Asia/Shanghai")).filter {it.sourceId==row.sourceId}
  assertEquals(LocalDate.parse("2026-09-13"),edgeRows.single().academicDate)
  assertEquals(8,edgeRows.single().start.atZone(ZoneId.of("America/New_York")).hour)
  assertEquals(java.io.File(dir,"hash.txt").readText(),SyncV3Codec.businessHash(valid))
  val rows=listOf("2026-09-07","2026-09-21").flatMap {ScheduleProjection.week(valid,LocalDate.parse(it),ZoneId.of("Asia/Shanghai"))}.map {listOf(it.seriesId,it.start.toEpochMilli(),it.end.toEpochMilli(),it.entry.location,it.teacher,it.note,it.sharedRgb)}
  assertEquals(org.json.JSONArray(java.io.File(dir,"projection.json").readText()).toString(),org.json.JSONArray(rows).toString())
 }
}
