package com.dormpanel.app.schedule
import org.junit.Test
import org.junit.Assert.*
import java.time.*
class IcsScheduleExporterTest {
 @Test fun sharedBytesAndReadOnly() {
  val s=ScheduleCommitCodec.decode(javaClass.getResourceAsStream("/sync-v2/complex-root.dpc")!!.readBytes()).state
  val r=IcsScheduleExporter.export(s,LocalDate.parse("2026-10-11"),LocalDate.parse("2026-10-26"),ZoneId.of("Asia/Shanghai"),true,Instant.EPOCH)
  assertArrayEquals(javaClass.getResourceAsStream("/ics-export.ics")!!.readBytes(),r.bytes)
  assertTrue(r.courseCount>0)
  val lines=r.bytes.toString(Charsets.UTF_8).split("\r\n")
  assertTrue(lines.all { it.toByteArray(Charsets.UTF_8).size<=75 })
 }
 @Test fun invalidRangeRejected() {
  try { IcsScheduleExporter.export(ScheduleState(),LocalDate.parse("2026-01-01"),LocalDate.parse("2028-01-01"),ZoneOffset.UTC);fail() } catch(_:IllegalArgumentException) {}
 }
 @Test fun mixedSlotsByteOracle() {
  val slot=CourseSlot("slot",null,"x","x","2026-09-07",listOf(1,3,5),7,1410,90,0,0,true,"中文😀;课,程\\\nX-EVIL:1","教室;A","教师","备注")
  val state=ScheduleState(entries=listOf(TimetableEntry("x","旧安排",1,480,540),TimetableEntry("overlap","重叠",7,1410,1439,"B")),
   dayAdjustments=listOf(DayAdjustment("2026-09-09","weekday",7,"")),
   sharedRecords=listOf(slot,slot.copy(id="copy",replacesSeries=null),slot.copy(id="disabled",replacesSeries=null,enabled=false)).map(SharedCourseSchedule::record))
  for((z,name) in listOf("Asia/Shanghai" to "sh","America/New_York" to "ny")) {
   val r=IcsScheduleExporter.export(state,LocalDate.parse("2026-09-07"),LocalDate.parse("2026-10-12"),ZoneId.of(z),false,Instant.EPOCH)
   assertEquals(14,r.courseCount); assertArrayEquals(javaClass.getResourceAsStream("/ics-mixed-$name.ics")!!.readBytes(),r.bytes)
  }
 }
 @Test fun dstGapUsesActualInstant() {
  val s=ScheduleState(entries=listOf(TimetableEntry("dst","DST",7,150,240)))
  val out=IcsScheduleExporter.export(s,LocalDate.parse("2026-03-08"),LocalDate.parse("2026-03-08"),ZoneId.of("America/New_York"),false,Instant.EPOCH).bytes.toString(Charsets.UTF_8)
  assertTrue(out.contains("DTSTART:20260308T073000Z"));assertTrue(out.contains("DTEND:20260308T080000Z"))
 }
}
