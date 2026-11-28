package com.dormpanel.app.icloud

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class CloudEventDraft(
    val title: String, val location: String, val description: String,
    val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean, val zone: ZoneId,
) {
    fun validate() {
        require(title.isNotBlank()) { "请输入日程标题" }
        require(title.length <= 512 && location.length <= 512 && description.length <= 4096) { "日程文字过长" }
        require(listOf(title, location, description).none { text -> text.any { it.isISOControl() && it !in "\r\n\t" } }) {
            "日程文字包含不支持的控制字符"
        }
        require(start.year in 1..9999 && end.year in 1..9999) { "日期超出范围" }
    }

    fun resolve(value: LocalDateTime, original: Instant? = null): Instant {
        if (original != null && original.atZone(zone).toLocalDateTime() == value) return original
        val offsets = zone.rules.getValidOffsets(value)
        require(offsets.isNotEmpty()) { "所选时间因夏令时调整而不存在，请选择其他时间" }
        return value.toInstant(offsets.first())
    }
}

/** Retain the original content lines, including unknown properties, alarms and timezone definitions. */
internal class EditableCalendarObject private constructor(
    private val lines: List<String>,
    private val fields: Map<String, Int>,
    private val eventEnd: Int,
    val uid: String,
    val draft: CloudEventDraft,
    private val startInstant: Instant,
    private val endInstant: Instant,
) {
    fun edit(next: CloudEventDraft, now: Instant): String {
        next.validate()
        require(next.zone == draft.zone) { "请使用打开编辑器时的时区" }
        val datesChanged = next.allDay != draft.allDay
        val changeStart = datesChanged || next.start != draft.start
        val changeEnd = datesChanged || next.end != draft.end
        val start = if (next.allDay) null else next.resolve(next.start, startInstant.takeUnless { datesChanged })
        val end = if (next.allDay) null else next.resolve(next.end, endInstant.takeUnless { datesChanged })
        require(if (start == null) next.end.toLocalDate() > next.start.toLocalDate() else end!! > start) {
            "结束必须晚于开始"
        }
        val replacements = linkedMapOf<String, String?>()
        // RFC 5545 requires both endpoints to use local time or both to use UTC.
        // When changing the original local range, pin both resolved instants;
        // the untouched endpoint keeps its exact instant, including an overlap offset.
        val pinRange = !draft.allDay && !next.allDay && (changeStart || changeEnd) &&
            listOfNotNull(fields["DTSTART"], fields["DTEND"]).any { !lines[it].endsWith("Z", true) }
        if (changeStart || pinRange) replacements["DTSTART"] = dateLine("DTSTART", next.start, start)
        if (changeEnd || pinRange || (changeStart && "DTEND" !in fields)) replacements["DTEND"] = dateLine("DTEND", next.end, end)
        if ("DTEND" in replacements && "DURATION" in fields) replacements["DURATION"] = null
        if (next.title != draft.title) replacements["SUMMARY"] = "SUMMARY:${escapeText(next.title)}"
        if (next.location != draft.location) replacements["LOCATION"] = "LOCATION:${escapeText(next.location)}"
        if (next.description != draft.description) replacements["DESCRIPTION"] = "DESCRIPTION:${escapeText(next.description)}"
        if (replacements.isEmpty()) return serialize(lines)
        require("LOCATION" !in replacements || lines.none { it.startsWith("X-APPLE-STRUCTURED-LOCATION", true) }) {
            "原始地点含地图数据，无法安全修改"
        }
        // A text property with another representation may contain meaningful alternate content.
        for (name in replacements.keys.intersect(TEXT_FIELDS)) {
            require(fields[name]?.let { lines[it].substringBefore(':').equals(name, true) } != false) {
                "原始文字属性含附加参数，无法安全修改"
            }
        }
        val sequence = fields["SEQUENCE"]?.let { lines[it].substringAfter(':').toInt() } ?: 0
        require(sequence in 0 until Int.MAX_VALUE) { "日程序列号不可用" }
        replacements["DTSTAMP"] = "DTSTAMP:${utc.format(now)}"
        replacements["LAST-MODIFIED"] = "LAST-MODIFIED:${utc.format(now)}"
        replacements["SEQUENCE"] = "SEQUENCE:${sequence + 1}"
        val byIndex = replacements.filterKeys(fields::containsKey).mapKeys { fields.getValue(it.key) }
        return serialize(buildList {
            lines.forEachIndexed { i, line ->
                if (i == eventEnd) addAll(replacements.filterKeys { it !in fields }.values.filterNotNull())
                if (i in byIndex) byIndex[i]?.let(::add) else add(line)
            }
        })
    }

    fun matches(other: EditableCalendarObject, preserveProperties: Boolean): Boolean =
        uid == other.uid && draft == other.draft &&
            (draft.allDay || startInstant == other.startInstant && endInstant == other.endInstant) &&
            (!preserveProperties || protectedContent() == other.protectedContent())

    private fun protectedContent(): List<String> {
        val managed = fields.filter { (name, index) ->
            name in setOf("DTSTAMP", "LAST-MODIFIED", "SEQUENCE", "DTSTART", "DTEND", "DURATION") ||
                name in TEXT_FIELDS && lines[index].substringBefore(':').equals(name, true)
        }.values.toSet()
        // A DAV server may reorder top-level properties or normalize physical folding.
        // Preserve nested components as complete units.
        val parts = mutableListOf<String>()
        var nested: MutableList<String>? = null
        lines.forEachIndexed { index, line ->
            if (index in managed) return@forEachIndexed
            val key = line.substringBefore(':').uppercase(Locale.ROOT)
            val componentName = line.substringAfter(':').uppercase(Locale.ROOT)
            if (nested == null && key == "BEGIN" && componentName in setOf("VALARM", "VTIMEZONE")) nested = mutableListOf()
            val component = nested
            if (component == null) {
                if (key !in setOf("PRODID", "CALSCALE")) parts.add(line)
            } else {
                component.add(line)
                if (key == "END" && componentName in setOf("VALARM", "VTIMEZONE")) {
                    parts.add(component.joinToString("\n"))
                    nested = null
                }
            }
        }
        return parts.sorted()
    }

    companion object {
        private val TEXT_FIELDS = setOf("SUMMARY", "LOCATION", "DESCRIPTION")
        private val UNIQUE_FIELDS = TEXT_FIELDS + setOf("UID", "DTSTART", "DTEND", "DURATION", "DTSTAMP", "LAST-MODIFIED", "SEQUENCE")
        private val UNSAFE = setOf("ATTENDEE", "ORGANIZER", "METHOD", "RRULE", "RDATE", "EXRULE", "EXDATE",
            "RECURRENCE-ID", "REQUEST-STATUS")

        fun parse(ics: String, uid: String, zone: ZoneId): EditableCalendarObject {
            require(ics.length in 1..MAX_WRITE_ICS_BYTES) { "日程资源过大或为空" }
            val lines = unfold(ics)
            val stack = mutableListOf<String>()
            val fields = linkedMapOf<String, Int>()
            var eventCount = 0
            var eventEnd = -1
            var calendarCount = 0
            lines.forEachIndexed { index, line ->
                val delimiter = propertyDelimiter(line)
                require(delimiter > 0) { "日程属性格式无法安全处理" }
                val key = line.substring(0, delimiter).substringBefore(';').uppercase(Locale.ROOT)
                require(key.matches(PROPERTY_NAME)) { "日程属性名称无法安全处理" }
                val value = line.substring(delimiter + 1)
                require(key !in UNSAFE || (key in setOf("RRULE", "RDATE") && stack.lastOrNull() in setOf("STANDARD", "DAYLIGHT"))) {
                    "重复日程、邀请或带扩展位置的日程为只读"
                }
                when (key) {
                    "BEGIN" -> {
                        require(delimiter == 5)
                        val component = value.uppercase(Locale.ROOT)
                        val allowed = when (stack.lastOrNull()) {
                            null -> component == "VCALENDAR" && ++calendarCount == 1
                            "VCALENDAR" -> component == "VTIMEZONE" || component == "VEVENT" && ++eventCount == 1
                            "VEVENT" -> component == "VALARM"
                            "VTIMEZONE" -> component in setOf("STANDARD", "DAYLIGHT")
                            else -> false
                        }
                        require(allowed) { "日程含多个事件或暂不支持的组件" }
                        stack.add(component)
                    }
                    "END" -> {
                        require(delimiter == 3 && stack.isNotEmpty() && stack.removeAt(stack.lastIndex).equals(value, true))
                        if (value.equals("VEVENT", true)) eventEnd = index
                    }
                    else -> {
                        require(stack.isNotEmpty())
                        if (stack.last() == "VEVENT" && key in UNIQUE_FIELDS) require(fields.put(key, index) == null)
                        if (stack.last() == "VALARM" && key == "ACTION") require(value.uppercase(Locale.ROOT) in setOf("DISPLAY", "AUDIO")) {
                            "含邮件或其他高级提醒的日程为只读"
                        }
                    }
                }
            }
            require(stack.isEmpty() && eventCount == 1 && eventEnd > 0)
            val event = readEditableEvent(ics, zone)
            require(event.uid == uid) { "服务器日程的 UID 与原记录不一致" }
            val start = event.start(zone)
            val end = event.end(zone)
            return EditableCalendarObject(lines, fields, eventEnd, uid, CloudEventDraft(
                event.title, event.location, event.description,
                event.firstDay?.atStartOfDay() ?: start.atZone(zone).toLocalDateTime(),
                event.endDay?.atStartOfDay() ?: end.atZone(zone).toLocalDateTime(), event.firstDay != null, zone,
            ), start, end)
        }

        fun create(uid: String, draft: CloudEventDraft, now: Instant): String {
            draft.validate()
            val start = if (draft.allDay) null else draft.resolve(draft.start)
            val end = if (draft.allDay) null else draft.resolve(draft.end)
            require(if (start == null) draft.end.toLocalDate() > draft.start.toLocalDate() else end!! > start) {
                "结束必须晚于开始"
            }
            return serialize(listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//DormPanel//Calendar//EN",
                "BEGIN:VEVENT", "UID:${escapeText(uid)}", "DTSTAMP:${utc.format(now)}", "SEQUENCE:0",
                dateLine("DTSTART", draft.start, start), dateLine("DTEND", draft.end, end),
                "SUMMARY:${escapeText(draft.title)}", "LOCATION:${escapeText(draft.location)}",
                "DESCRIPTION:${escapeText(draft.description)}", "END:VEVENT", "END:VCALENDAR"))
        }
    }
}

internal const val MAX_WRITE_ICS_BYTES = 262144
private val PROPERTY_NAME = Regex("[A-Z0-9-]+")
private val utc = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

private fun propertyDelimiter(line: String): Int {
    var quoted = false
    for (index in line.indices) {
        if (line[index] == '"') quoted = !quoted
        if (line[index] == ':' && !quoted) return index
    }
    return -1
}

private fun unfold(ics: String): List<String> {
    val lines = mutableListOf<StringBuilder>()
    val physical = ics.removePrefix("\uFEFF").replace("\r\n", "\n").removeSuffix("\n").split('\n')
    for (line in physical) {
        require(line.isNotEmpty() && line.none { it.isISOControl() && it != '\t' })
        if (line[0] == ' ' || line[0] == '\t') {
            require(lines.isNotEmpty())
            lines.last().append(line, 1, line.length)
        } else lines.add(StringBuilder(line))
    }
    return lines.map(StringBuilder::toString)
}

private fun serialize(lines: List<String>): String {
    val result = buildString {
        for (line in lines) {
            var bytes = 0
            var offset = 0
            while (offset < line.length) {
                val point = line.codePointAt(offset)
                val width = when (point) {
                    in 0..127 -> 1
                    in 128..2047 -> 2
                    in 2048..65535 -> 3
                    else -> 4
                }
                if (bytes + width > 75) {
                    append("\r\n ")
                    bytes = 1
                }
                appendCodePoint(point)
                bytes += width
                offset += Character.charCount(point)
            }
            append("\r\n")
        }
    }
    require(result.toByteArray(Charsets.UTF_8).size <= MAX_WRITE_ICS_BYTES) { "日程资源过大" }
    return result
}

private fun dateLine(name: String, local: LocalDateTime, instant: Instant?) =
    if (instant == null) "$name;VALUE=DATE:${DateTimeFormatter.BASIC_ISO_DATE.format(local.toLocalDate())}"
    else "$name:${utc.format(instant)}"

private fun escapeText(value: String) = value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
    .replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n")
