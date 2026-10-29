package com.dormpanel.app.schedule

import java.io.*
import java.time.*

data class CourseSlot(val id: String, val sourceId: String?, val ownerSeries: String, val replacesSeries: String?,
    val week1: String, val weeks: List<Int>, val weekday: Int, val startMinute: Int, val endMinute: Int,
    val periodStart: Int, val periodEnd: Int, val enabled: Boolean, val title: String, val location: String,
    val teacher: String, val note: String)

/** Required projection capability. Each slot is independently merged; absence deletes an added slot. */
object SharedCourseSchedule : SharedTypeAdapter {
    override val typeId = "course.schedule"
    override val schemaVersion = 1
    override val maxPayload = 16384
    fun record(s: CourseSlot): SharedRecord {
        require(SharedRecordCodec.identity(s.id) && SharedRecordCodec.identity(s.ownerSeries) && s.id != s.ownerSeries)
        require(s.sourceId == null || SharedRecordCodec.identity(s.sourceId))
        require(s.replacesSeries == null || s.replacesSeries == s.ownerSeries)
        require(s.week1.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && LocalDate.parse(s.week1).year in 1..9997)
        require(LocalDate.parse(s.week1).toString() == s.week1 && LocalDate.parse(s.week1).dayOfWeek.value == 1)
        require(s.weeks.size <= 104 && s.weeks == s.weeks.distinct().sorted() && s.weeks.all { it in 1..104 })
        require(s.weekday in 1..7 && s.startMinute in 0..1439 && s.endMinute in 0..1439 && s.startMinute != s.endMinute)
        require(s.periodStart == 0 && s.periodEnd == 0 || s.periodStart in 1..30 && s.periodEnd in s.periodStart..30)
        require(s.title.isNotBlank())
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            fun text(v: String) { val bytes=v.toByteArray(Charsets.UTF_8); require(bytes.size <= 4096); out.writeInt(bytes.size); out.write(bytes) }
            text(s.sourceId.orEmpty()); text(s.ownerSeries); text(s.replacesSeries.orEmpty()); text(s.week1)
            out.writeInt(s.weeks.size); s.weeks.forEach(out::writeInt)
            listOf(s.weekday,s.startMinute,s.endMinute,s.periodStart,s.periodEnd,if(s.enabled) 1 else 0).forEach(out::writeInt)
            listOf(s.title,s.location,s.teacher,s.note).forEach(::text)
        } }.toByteArray()
        require(bytes.size <= maxPayload)
        return SharedRecord(typeId,s.id,1,required=true,payload=bytes.toList())
    }
    fun decode(r: SharedRecord): CourseSlot {
        require(r.typeId == typeId && r.schemaVersion == 1 && r.required && r.dependencies.isEmpty() && r.payload.size <= maxPayload)
        val s=DataInputStream(r.payload.toByteArray().inputStream()).use { input ->
            fun text(): String { val n=input.readInt(); require(n in 0..4096 && n <= input.available()); return ByteArray(n).also(input::readFully).toString(Charsets.UTF_8) }
            val source=text().ifEmpty { null }; val owner=text(); val replaces=text().ifEmpty { null }; val week1=text()
            val count=input.readInt(); require(count in 0..104)
            val weeks=List(count) { input.readInt() }
            val day=input.readInt(); val start=input.readInt(); val end=input.readInt(); val a=input.readInt(); val z=input.readInt()
            val flag=input.readInt(); require(flag in 0..1)
            CourseSlot(r.recordId,source,owner,replaces,week1,weeks,day,start,end,a,z,flag==1,text(),text(),text(),text()).also { require(input.available()==0) }
        }
        require(record(s)==r)
        return s
    }
    override fun validate(record: SharedRecord, records: List<SharedRecord>) { decode(record) }
    override fun relatedKeys(record: SharedRecord, state: ScheduleState): Set<String> {
        val slot=decode(record)
        return setOf(if(slot.sourceId==null) "class:${slot.ownerSeries}" else "source:${slot.sourceId}")
    }
    fun localDependents(state: ScheduleState, id: String) = slots(state).filter {it.sourceId==null && it.ownerSeries==id}
    fun requireDeletable(state: ScheduleState, id: String) {
        val dependencies=localDependents(state,id)
        check(dependencies.isEmpty()) {"课程仍被 ${dependencies.size} 条共享安排引用（含停用安排）。请进入课程管理，先明确移除关联规则并成功保存，再删除课程。"}
    }
    fun slots(state: ScheduleState) = state.courseSlots
    override fun validateState(state: ScheduleState) = validateParents(state)
    fun validateParents(state: ScheduleState) {
        val local=state.entries.map { it.id }.toSet()
        val sources=state.sources.map { it.id }.toSet()
        val imported=state.imported.map { it.sourceId to it.seriesId }.toSet()
        val importedIds=state.imported.map { it.id }.toSet()
        val replacements=mutableSetOf<Pair<String?,String>>()
        slots(state).forEach { s ->
            require(s.id !in local && s.id !in importedIds && s.sourceId to s.id !in imported) { "新增时段身份与原课程冲突" }
            require(if(s.sourceId==null) s.ownerSeries in local else s.sourceId in sources && s.sourceId to s.ownerSeries in imported) { "安排 ${s.id} 关联来源/系列 ${s.sourceId}/${s.ownerSeries} 已不存在。来源未变更：请先在课程管理中显式移除受影响共享安排规则并保存，再重试；保留新增安排时不能删除其来源。" }
            if(s.replacesSeries!=null) {
                require(replacements.add(s.sourceId to s.replacesSeries)) { "同一原时段不能被重复替换" }
                if(s.sourceId!=null) require(state.imported.filter { it.sourceId==s.sourceId && it.seriesId==s.replacesSeries }
                    .map { Instant.ofEpochMilli(it.start).atZone(ZoneId.of(it.timezone)).dayOfWeek }.distinct().size==1) { "多星期原系列需先明确例外关联，不能直接替换" }
            }
            if(s.enabled && s.periodStart>0) {
                val profile=requireNotNull(state.sources.find { it.id==s.sourceId }?.resolvedTermKey()?.let { state.profiles[it] }) { "此课程没有可用作息，请使用自定义时间" }
                s.weeks.forEach { week -> requireNotNull(ScheduleProjection.linkedPeriodTimes(profile,LocalDate.parse(s.week1).plusWeeks(week-1L).plusDays(s.weekday-1L),s.periodStart,s.periodEnd)) { "有效周的作息无法解析所选节次" } }
            }
        }
    }
    fun project(state: ScheduleState, monday: LocalDate, zone: ZoneId, base: List<ClassOccurrence>): List<ClassOccurrence> {
        val slots=slots(state)
        val retained=base.filterNot { v -> slots.any { it.sourceId==v.sourceId && it.replacesSeries==v.seriesId } }
        return retained + slots.filter { it.enabled }.mapNotNull { s ->
            val week=Math.floorDiv(monday.toEpochDay()-LocalDate.parse(s.week1).toEpochDay(),7L)+1
            if(week.toInt() !in s.weeks) return@mapNotNull null
            val date=monday.plusDays(s.weekday-1L)
            val profile=s.sourceId?.let { id -> state.sources.find { it.id==id }?.resolvedTermKey()?.let { state.profiles[it] } }
            val timingZone=profile?.timezone?.let(ZoneId::of) ?: s.sourceId?.let { id -> state.imported.find { it.sourceId==id && it.seriesId==s.ownerSeries }?.timezone?.let(ZoneId::of) } ?: zone
            val anchorDay=if(s.replacesSeries==null) s.weekday else if(s.sourceId==null) state.entries.find { it.id==s.ownerSeries }?.day ?: s.weekday
                else state.imported.find { it.sourceId==s.sourceId && it.seriesId==s.ownerSeries }?.let { Instant.ofEpochMilli(it.start).atZone(ZoneId.of(it.timezone)).dayOfWeek.value } ?: s.weekday
            val times=if(s.periodStart>0) profile?.let { ScheduleProjection.linkedPeriodTimes(it,date,s.periodStart,s.periodEnd) } ?: return@mapNotNull null
                else date.atTime(LocalTime.ofSecondOfDay(s.startMinute*60L)).atZone(timingZone).toInstant() to
                    date.plusDays(if(s.endMinute<s.startMinute) 1 else 0).atTime(LocalTime.ofSecondOfDay(s.endMinute*60L)).atZone(timingZone).toInstant()
            val from=times.first.atZone(zone); val to=times.second.atZone(zone)
            ClassOccurrence(TimetableEntry(s.id,s.title,from.dayOfWeek.value,from.hour*60+from.minute,to.hour*60+to.minute,s.location),
                from.toLocalDate(),times.first,times.second,seriesId=s.replacesSeries ?: s.id,sourceId=s.sourceId,
                periodLabel=if(s.periodStart>0) "[${s.periodStart}-${s.periodEnd}节]" else null,
                teacher=s.teacher,note=s.note,linked=s.periodStart>0,academicDate=date,anchorDate=monday.plusDays(anchorDay-1L),colorSeriesId=s.ownerSeries)
        }
    }
}
