package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CourseBatchTest {
    private val entry = TimetableEntry("local", "数学", 1, 540, 600)
    private val state = ScheduleState(entries = listOf(entry))
    private fun edit(id: String = "local") = ClassOverride.of(null, id, null, ClassPatch(title = "新名称"))
    @Test fun localNamesNeverLinkIdentity() {
        val groups = CourseBatch.candidates(state.copy(entries = listOf(entry, entry.copy(id = "other"))))
        assertEquals(2, groups.size)
        assertEquals(listOf("local"), groups[0].series)
    }
    @Test fun missingSecondSeriesRejectsWholeBatch() {
        assertTrue(runCatching { CourseBatch.validate(state, state, listOf(edit(), edit("missing"))) }.isFailure)
        assertTrue(state.classOverrides.isEmpty())
    }
    @Test fun concurrentOverrideRejectsStaleCapture() {
        val current = state.copy(classOverrides = listOf(edit().copy(location = "单周以外")))
        assertTrue(runCatching { CourseBatch.validate(current, state, listOf(edit())) }.isFailure)
    }
    @Test fun validBatchPreservesWeeklyExceptions() {
        val weekly = ClassOverride.of(null, "local", "2026-10-26", ClassPatch(location = "例外教室", teacher = ""))
        val captured = state.copy(classOverrides = listOf(weekly))
        CourseBatch.validate(captured, captured, listOf(edit()))
        assertEquals("", captured.classOverrides.single().teacher)
        assertEquals("例外教室", captured.classOverrides.single().location)
    }
    @Test fun invalidTextRejectsBatch() {
        assertTrue(runCatching { CourseBatch.validate(state, state, listOf(edit().copy(title = "x".repeat(161)))) }.isFailure)
    }
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun fixture(extension: String): ScheduleState {
        val bytes=javaClass.getResourceAsStream("/schedule/hubei_2026-2027-1.$extension")!!.readBytes()
        val structure=if(extension=="csv") HubeiCsvTimetableParser.parseBytes(bytes) else HubeiXlsReader.parse(bytes)
        val profile=BuiltInProfiles.term2026
        val rows=CsvTimetableResolver.resolve(structure,profile,"fixture").occurrences
        val source=ImportSource("fixture","湖北大学夹具","test.$extension",extension,null,null,"0".repeat(64),0,zone.id,rows.size,rows.minOf{it.start},rows.maxOf{it.end},profile.termKey)
        return ScheduleState(sources=listOf(source),imported=rows,profiles=mapOf(profile.termKey to profile))
    }
    @Test fun csvAndXlsGroupSameSeriesWithoutInventingWeeksOrIdentity() {
        val csv=fixture("csv");val xls=fixture("xls")
        val groups=CourseBatch.candidates(csv)
        assertEquals(groups,CourseBatch.candidates(xls))
        assertTrue(groups.any{it.series.size>1})
        assertEquals(13,groups.sumOf{it.series.size})
        val group=groups.first{it.series.size>1}
        val occurrences=CourseBatch.occurrences(csv,group,zone,LocalDate.of(2026,10,26))
        assertEquals(csv.imported.filter{it.seriesId in group.series}.map{it.id}.toSet(),occurrences.map{it.imported!!.id}.toSet())
        val edits=group.series.map{ClassOverride.of("fixture",it,null,ClassPatch(title="整门课程",teacher="共同教师"))}
        CourseBatch.validate(csv,csv,edits)
        val future=csv.copy(classOverrides=edits)
        assertTrue(CourseBatch.occurrences(future,group,zone,LocalDate.of(2026,10,26)).all{it.entry.title=="整门课程"&&it.teacher=="共同教师"})
        assertEquals(csv.imported,future.imported)
    }
    @Test fun separateSourcesMixedNamesMultiWeekdayAndSparseDates() {
        val first=fixture("csv");val second=first.copy(sources=first.sources.map{it.copy(id="other")},imported=first.imported.map{it.copy(id="other-"+it.id,sourceId="other")})
        val mixed=first.copy(sources=first.sources+second.sources,imported=first.imported+second.imported,entries=listOf(entry,entry.copy(id="other-local")))
        val groups=CourseBatch.candidates(mixed)
        assertEquals(CourseBatch.candidates(first).size*2+2,groups.size)
        assertEquals(2,groups.count{it.sourceId==null})
        val one=first.imported.first()
        val dates=listOf(0,8,21).mapIndexed{n,day->one.copy(id="sparse-$n",seriesId="sparse",start=one.start+day*86400000L,end=one.end+day*86400000L,originalStart=one.originalStart+day*86400000L)}
        val sparse=first.copy(imported=dates)
        val candidate=CourseBatch.candidates(sparse).single()
        val actual=CourseBatch.occurrences(sparse,candidate,zone,LocalDate.now(zone))
        assertEquals(3,actual.size)
        assertTrue(ClassEditSession(sparse,actual.first(),true,zone).multiWeekday)
        assertEquals(dates.map{Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate()}.toSet(),actual.map{it.date}.toSet())
    }
    @Test fun previewMatchesFinalProjectionAndPreservesNullAndEmpty() {
        val weekly=ClassOverride.of(null,"local","2026-10-26",ClassPatch(title="例外名称",teacher="",location="例外教室"))
        val captured=state.copy(classOverrides=listOf(weekly))
        val edits=listOf(edit().copy(teacher="统一教师",note=""))
        val preview=CourseBatch.exceptionPreview(captured,edits,zone)
        assertTrue(preview.contains("例外名称"));assertTrue(preview.contains("教师：（空）"));assertTrue(preview.contains("例外教室"))
        assertEquals(weekly,captured.classOverrides.single());assertNull(weekly.note)
    }
    @Test fun invalidEffectiveTimeAndRecordBudgetRejectWholeBatch() {
        assertTrue(runCatching{CourseBatch.validate(state,state,listOf(edit().copy(startMinute=700,endMinute=600)))}.isFailure)
        val full=state.copy(classOverrides=(0L..4999L).map{ClassOverride.of(null,"local",LocalDate.of(2000,1,1).plusDays(it).toString(),ClassPatch(note="旧覆盖"))})
        assertTrue(runCatching{CourseBatch.validate(full,full,listOf(edit()))}.isFailure)
    }
    @Test fun replacedOrDeletedImportRejectsOldDraft() {
        val before=fixture("csv");val group=CourseBatch.candidates(before).first()
        val edits=group.series.map{ClassOverride.of("fixture",it,null,ClassPatch(title="新名称"))}
        val after=before.copy(imported=before.imported.filterNot{it.seriesId==group.series.first()})
        assertTrue(runCatching{CourseBatch.validate(after,before,edits)}.isFailure)
        assertTrue(runCatching{CourseBatch.validate(before.copy(sources=emptyList(),imported=emptyList()),before,edits)}.isFailure)
    }

    @Test fun unrelatedLocationEditPreservesExactClearAndInheritanceIntent() {
        val occurrence=CourseBatch.occurrences(state,CourseBatch.candidates(state).single(),zone,LocalDate.of(2026,10,26)).single()
        val session=ClassEditSession(state,occurrence,true,zone)
        val original=ClassPatch(title="数学",note="",teacher=null)
        val result=session.preserveUntouched(original,ClassPatch(),ClassPatch(location="新教室"))
        assertEquals("",result.note);assertNull(result.teacher);assertEquals("数学",result.title);assertEquals("新教室",result.location)
    }

    @Test fun rawInstanceTitleVariationNeverDuplicatesOneSeries() {
        val original=fixture("csv")
        val row=original.imported.first()
        val mixed=original.copy(imported=listOf(row,row.copy(id="variant",title="原始实例别名",start=row.start+7*86400000L,end=row.end+7*86400000L)))
        val groups=CourseBatch.candidates(mixed)
        assertEquals(1,groups.size);assertEquals(listOf(row.seriesId),groups.single().series)
        assertTrue(groups.single().originalTitle.contains("原始名称不一致"))
    }

    @Test fun copiedDayRetainsBothEffectiveDatesForOneInstance() {
        val monday=LocalDate.of(2026,10,26)
        val copied=state.copy(dayAdjustments=listOf(DayAdjustment("2026-10-28","weekday",1)))
        val rows=CourseBatch.occurrences(copied,CourseBatch.candidates(copied).single(),zone,monday)
        assertEquals(setOf(monday,monday.plusDays(2)),rows.map{it.date}.toSet())
    }

}
