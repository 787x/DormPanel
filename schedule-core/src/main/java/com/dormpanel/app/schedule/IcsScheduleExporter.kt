package com.dormpanel.app.schedule

import java.time.*
import java.time.format.DateTimeFormatter

/** Read-only export of one captured business state. UTC bytes, inclusive local dates. */
object IcsScheduleExporter {
    data class Export(val bytes: ByteArray, val courseCount: Int, val eventCount: Int)
    private data class Row(val identity: String, val start: Instant, val end: Instant, val title: String, val location: String, val description: String)
    fun export(state: ScheduleState, start: LocalDate, end: LocalDate, zone: ZoneId, includeEvents: Boolean = false, now: Instant = Instant.now()): Export {
        require(start.year>=1 && end.year<=9997 && !end.isBefore(start) && java.time.temporal.ChronoUnit.DAYS.between(start,end)<=365) { "日期范围必须为 1–366 天" }
        require(state.invalidRecords==0) { "课表存在无法读取的记录，不能确认完整导出" }
        val began=System.nanoTime()
        fun budget() { require(System.nanoTime()-began<5_000_000_000L) { "导出超时，请缩小范围" } }
        val min=start.atStartOfDay(zone).toInstant(); val max=end.plusDays(1).atStartOfDay(zone).toInstant()
        val rows=linkedMapOf<String,Row>()
        var first=start.minusDays((start.dayOfWeek.value-1).toLong())
        while(!first.isAfter(end)) {
            ScheduleProjection.week(state,first,zone).filter { it.start<max && it.end>min }.forEach { c ->
                val key=org.json.JSONArray().put("course").put(c.sourceId ?: org.json.JSONObject.NULL).put(c.seriesId).put(c.entry.id).put(c.anchorDate.toString()).toString()
                rows[key]=Row(key,c.start,c.end,c.entry.title,c.entry.location,listOf(c.teacher,c.note,c.periodLabel.orEmpty()).filter { it.isNotEmpty() }.joinToString("\n"))
            }
            require(rows.size<=20000) { "导出超过 20000 个事件，请缩小范围" }; budget(); first=first.plusWeeks(1)
        }
        val courses=rows.size
        if(includeEvents) state.events.filter { Instant.ofEpochMilli(it.start)<max && Instant.ofEpochMilli(it.end)>min }.forEach { e ->
            val key=org.json.JSONArray().put("event").put(e.id).toString()
            rows[key]=Row(key,Instant.ofEpochMilli(e.start),Instant.ofEpochMilli(e.end),e.title,"",e.note)
        }
        require(rows.size<=20000) { "导出超过 20000 个事件，请缩小范围" }
        fun text(s:String)=s.replace("\\","\\\\").replace("\r\n","\n").replace("\r","\n").replace("\n","\\n").replace(";","\\;").replace(",","\\,")
        val format=DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        val lines=mutableListOf("BEGIN:VCALENDAR","VERSION:2.0","PRODID:-//DormPanel//Schedule Export 1//ZH","CALSCALE:GREGORIAN")
        rows.values.sortedBy { it.identity }.forEach { r ->
            require(r.start.nano==0 && r.end.nano==0) { "课程或事件包含亚秒时间，标准 ICS 无法无损表达" }
            lines.addAll(listOf("BEGIN:VEVENT","UID:${digest(r.identity.toByteArray(Charsets.UTF_8))}@ics.dormpanel","DTSTAMP:${format.format(now)}","DTSTART:${format.format(r.start)}","DTEND:${format.format(r.end)}","SUMMARY:${text(r.title)}"))
            if(r.location.isNotEmpty()) lines.add("LOCATION:${text(r.location)}")
            if(r.description.isNotEmpty()) lines.add("DESCRIPTION:${text(r.description)}")
            lines.add("END:VEVENT")
        }
        lines.add("END:VCALENDAR")
        val output=StringBuilder()
        lines.forEach { line -> var n=0; var i=0
            while(i<line.length) { val cp=line.codePointAt(i); val s=String(Character.toChars(cp)); val size=s.toByteArray(Charsets.UTF_8).size
                if(n+size>75) { output.append("\r\n "); n=1 }; output.append(s); n+=size; i+=Character.charCount(cp)
            }; output.append("\r\n")
        }
        val bytes=output.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=8*1024*1024) { "导出超过 8 MiB，请缩小范围" }; budget()
        return Export(bytes,courses,rows.size-courses)
    }
}
