package com.dormpanel.app.schedule

import biweekly.ICalendar
import biweekly.component.VEvent
import biweekly.io.text.ICalReader
import biweekly.property.*
import biweekly.util.Frequency
import biweekly.util.ICalDate
import com.github.mangstadt.vinnie.VObjectProperty
import com.github.mangstadt.vinnie.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.util.TimeZone

/** RFC content decoding and recurrence are delegated to biweekly/vinnie, not a line-splitting parser.
 * Admission deliberately supports a bounded timetable subset, and rejects the whole artifact on error. */
class BiweeklyScheduleParser : IcsScheduleParser {
    override fun parse(artifact: ScheduleArtifact): ParsedTimetable {
        try { return parseChecked(artifact) }
        catch (e: ScheduleImportException) { throw e }
        catch (_: Exception) { throw ScheduleImportException("Unable to read this ICS. Check its dates, timezones and recurrence rules.") }
    }

    private fun parseChecked(artifact: ScheduleArtifact): ParsedTimetable {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(artifact.bytes())).toString().removePrefix("\uFEFF")
        preflight(text)
        val calendar = ICalReader(text).use { reader ->
            reader.defaultTimezone = TimeZone.getTimeZone("UTC")
            val value = reader.readNext() ?: throw ScheduleImportException("No VCALENDAR found.")
            // Code 37 is a successfully resolved IANA TZID without a VTIMEZONE definition.
            importCheck(reader.warnings.all { it.code == 37 }, "Malformed ICS property or unresolved timezone. No data was imported.")
            importCheck(reader.readNext() == null, "Import one calendar at a time.")
            value
        }
        importCheck(calendar.events.size in 1..ImportLimits.EVENTS, "ICS must contain 1–256 VEVENTs.")
        importCheck(calendar.method == null || calendar.method.value.equals("PUBLISH", true), "Scheduling messages (METHOD) are not supported.")
        val result = mutableListOf<ImportedClassOccurrence>()
        val seriesKeys = mutableSetOf<String>()
        var candidates = 0
        var textCharacters = 0L
        var alarms = false
        calendar.events.forEachIndexed { index, event ->
            val label = "Event ${index + 1}"
            importCheck(event.recurrenceId == null, "$label: RECURRENCE-ID overrides are not supported yet.")
            importCheck(event.exceptionRules.isEmpty(), "$label: EXRULE is not supported; use EXDATE.")
            importCheck(event.status == null || !event.status.value.equals("CANCELLED", true), "$label: cancelled VEVENTs require a supported cancellation export (EXDATE).")
            val startProperty = event.dateStart ?: throw ScheduleImportException("$label: DTSTART is required.")
            val endProperty = event.dateEnd ?: throw ScheduleImportException("$label: DTEND is required.")
            importCheck(event.duration == null, "$label: DURATION is not supported; use DTEND.")
            val start = startProperty.value
            val end = endProperty.value
            checkDate(calendar, startProperty, start)
            checkDate(calendar, endProperty, end)
            val duration = end.time - start.time
            importCheck(duration in 1..86400000L, "$label: class duration must be positive and at most 24 hours.")
            val title = event.summary?.value?.trim().orEmpty()
            importCheck(title.isNotBlank(), "$label: SUMMARY is required.")
            val assignment = calendar.timezoneInfo.getTimezone(startProperty)
            val timezone = assignment?.timeZone ?: TimeZone.getTimeZone("UTC")
            val zoneId = assignment?.component?.timezoneId?.value ?: assignment?.globalId ?: "UTC"
            val uid = event.uid?.value
            // Same summary never merges events. Ambiguous duplicate masters must not overwrite one another.
            val series = "${uid ?: "event-$index"}\u0000${start.time}"
            importCheck(seriesKeys.add(series), "$label: duplicate UID and DTSTART; remove ambiguous duplicate series.")
            importCheck(event.getProperties(RecurrenceRule::class.java).size <= 1, "$label: multiple RRULEs are unsupported.")
            val rule = event.recurrenceRule?.value
            if (rule != null) {
                importCheck(rule.frequency == Frequency.DAILY || rule.frequency == Frequency.WEEKLY,
                    "$label: only DAILY and WEEKLY recurrence are supported for timetable import.")
                importCheck(rule.count != null || rule.until != null, "$label: unbounded recurrence; export a finite COUNT or UNTIL.")
                importCheck(rule.count == null || rule.until == null, "$label: RRULE cannot contain both COUNT and UNTIL.")
                importCheck((rule.interval ?: 1) in 1..366, "$label: INTERVAL must be 1–366.")
                importCheck(rule.count == null || rule.count in 1..ImportLimits.OCCURRENCES, "$label: COUNT exceeds the occurrence limit.")
                importCheck(rule.bySecond.isEmpty() && rule.byMinute.isEmpty() && rule.byHour.isEmpty() &&
                    rule.byMonthDay.isEmpty() && rule.byYearDay.isEmpty() && rule.byWeekNo.isEmpty() &&
                    rule.byMonth.isEmpty() && rule.bySetPos.isEmpty() && rule.xRules.isEmpty() &&
                    rule.byDay.size <= 7 && rule.byDay.all { it.num == null },
                    "$label: unsupported RRULE filters; only plain BYDAY is supported.")
                // Reject oversized finite schedules, never silently truncate them. Bound iterator work BEFORE iteration.
                val interval = (rule.interval ?: 1).toLong()
                val step = interval * if (rule.frequency == Frequency.WEEKLY) 7 else 1
                val boundDays = rule.until?.let {
                    checkDate(calendar, event.recurrenceRule, it)
                    importCheck(it.hasTime(), "$label: UNTIL must be a UTC date-time.")
                    importCheck(it.time >= start.time, "$label: UNTIL precedes DTSTART.")
                    (it.time - start.time) / 86400000L + 1
                } ?: ((rule.count!!.toLong() + 1) * step * if (rule.frequency == Frequency.DAILY && rule.byDay.isNotEmpty()) 7 else 1)
                importCheck(boundDays <= ImportLimits.SPAN_DAYS, "$label: recurrence exceeds the 10-year safety span.")
                // DAILY + BYDAY with INTERVAL divisible by 7 can never match; avoid iterator search for impossible COUNT.
                if (rule.frequency == Frequency.DAILY && interval % 7 == 0L && rule.byDay.isNotEmpty()) {
                    val day = java.util.Calendar.getInstance(timezone).apply { time = start }.get(java.util.Calendar.DAY_OF_WEEK)
                    importCheck(rule.byDay.any { it.day.calendarConstant == day }, "$label: recurrence cannot produce dates.")
                }
            }
            val included = sortedSetOf(start.time)
            if (rule != null) {
                val iterator = rule.getDateIterator(start, timezone)
                while (iterator.hasNext()) {
                    importCheck(++candidates <= ImportLimits.CANDIDATES, "ICS exceeds the 20,000 recurrence-candidate limit.")
                    val next = iterator.next().time
                    importCheck(next - start.time <= ImportLimits.SPAN_DAYS * 86400000L, "$label: recurrence exceeds the 10-year safety span.")
                    included.add(next)
                    importCheck(included.size <= ImportLimits.OCCURRENCES, "ICS exceeds the 10,000 occurrence limit.")
                }
            }
            event.recurrenceDates.forEach { dates ->
                importCheck(dates.periods.isEmpty(), "$label: RDATE PERIOD is unsupported; use date-times.")
                dates.dates.forEach { checkDate(calendar, dates, it); included.add(it.time) }
            }
            event.exceptionDates.forEach { dates -> dates.values.forEach { checkDate(calendar, dates, it); included.remove(it.time) } }
            val description = event.description?.value.orEmpty().replace("\r\n", "\n")
            val period = description.lineSequence().map { it.trim() }.firstOrNull { PERIOD.matches(it) }
            included.forEach { instant ->
                importCheck(++candidates <= ImportLimits.CANDIDATES && result.size < ImportLimits.OCCURRENCES,
                    "ICS exceeds the recurrence/10,000 occurrence limit.")
                val occurrence = ImportedClassOccurrence(digest("$series\u0000$instant".toByteArray()), "", series, uid, instant, title, instant, Math.addExact(instant, duration),
                    zoneId, event.location?.value.orEmpty().trim(), description, period)
                // Repeated large descriptions must not amplify a small input into an enormous database/snapshot.
                textCharacters += occurrence.textCharacters() + 73 // reserved source UUID in both sourceId and row ID
                importCheck(textCharacters <= ImportLimits.TEXT_CHARACTERS, "Expanded timetable text exceeds the 4-million-character storage limit.")
                result += occurrence
            }
            alarms = alarms || event.alarms.isNotEmpty()
        }
        importCheck(result.isNotEmpty(), "ICS contains no remaining classes after exclusions.")
        val warnings = mutableListOf<String>()
        if (alarms) warnings += "Embedded VALARM reminders are ignored. No notifications or alarms will be scheduled."
        warnings += "Classes are shown in the device timezone; their source timezone and exact instants are preserved."
        return ParsedTimetable(calendar.names.firstOrNull()?.value ?: calendar.getExperimentalProperty("X-WR-CALNAME")?.value,
            calendar.events.size, result.sortedBy { it.start }, warnings, alarms)
    }

    private fun checkDate(calendar: ICalendar, property: ICalProperty, date: ICalDate) {
        importCheck(date.hasTime(), "All-day dates are not supported for timetable classes.")
        importCheck(!calendar.timezoneInfo.isFloating(property), "Floating dates need an explicit TZID or UTC Z suffix.")
        date.rawComponents?.let {
            // The underlying legacy date parser can normalize February 30; refuse normalized invalid input.
            LocalDateTime.of(it.year, it.month, it.date, it.hour, it.minute, it.second)
        }
    }

    /** Streaming library tokenizer guards size/structure before building calendar/timezone objects.
     * In particular, embedded timezone recurrence is rejected before ICalTimeZone can evaluate it. */
    private fun preflight(text: String) {
        val stack = mutableListOf<String>()
        var calendars = 0; var events = 0; var properties = 0; var versions = 0
        var eventProperties = mutableSetOf<String>()
        VObjectReader(java.io.StringReader(text), SyntaxRules.iCalendar()).use { reader -> reader.parse(object : VObjectDataListener {
            override fun onComponentBegin(name: String, context: Context) {
                val allowed = when (stack.lastOrNull()) {
                    null -> name == "VCALENDAR"
                    "VCALENDAR" -> name == "VEVENT" || name == "VTIMEZONE"
                    "VEVENT" -> name == "VALARM"
                    "VTIMEZONE" -> name == "STANDARD" || name == "DAYLIGHT"
                    else -> false
                }
                importCheck(allowed, "Unsupported or malformed ICS component: $name.")
                if (name == "VCALENDAR") importCheck(++calendars == 1, "Import one calendar at a time.")
                if (name == "VEVENT") { importCheck(++events <= ImportLimits.EVENTS, "ICS exceeds 256 events."); eventProperties = mutableSetOf() }
                stack += name
            }
            override fun onComponentEnd(name: String, context: Context) {
                importCheck(context.unfoldedLine.trim().equals("END:$name", true), "Malformed ICS: missing component end.")
                importCheck(stack.lastOrNull() == name, "Mismatched ICS component.")
                stack.removeAt(stack.lastIndex)
            }
            override fun onProperty(property: VObjectProperty, context: Context) {
                val propertyName = property.name.uppercase(java.util.Locale.ROOT)
                importCheck(++properties <= 8192 && property.value.length <= 16384, "ICS contains too many or oversized properties.")
                importCheck(stack.isNotEmpty(), "Property outside VCALENDAR.")
                if (stack.last() == "VEVENT" && propertyName in setOf("DTSTART", "DTEND", "SUMMARY", "UID", "RRULE", "RECURRENCE-ID"))
                    importCheck(eventProperties.add(propertyName), "Duplicate $propertyName in VEVENT.")
                if ("VTIMEZONE" in stack) importCheck(propertyName !in setOf("RRULE", "RDATE", "EXRULE", "EXDATE"),
                    "Recurring VTIMEZONE definitions are not supported; export IANA TZIDs or fixed timezone definitions.")
                if (propertyName == "RRULE") {
                    // The library's recurrence reader otherwise accepts only the first scalar value.
                    val rules = VObjectPropertyValues.parseMultimap(property.value.uppercase(java.util.Locale.ROOT))
                    listOf("FREQ", "COUNT", "UNTIL", "INTERVAL", "WKST").forEach { key ->
                        importCheck(rules[key].orEmpty().size <= 1, "Duplicate $key in RRULE.")
                    }
                    val until = rules["UNTIL"]?.firstOrNull()
                    importCheck(until == null || until.endsWith("Z"), "UNTIL must be a UTC date-time ending in Z.")
                }
            }
            override fun onVersion(value: String, context: Context) { importCheck(value == "2.0" && ++versions == 1, "Exactly one iCalendar VERSION:2.0 is required.") }
            override fun onWarning(warning: Warning, property: VObjectProperty?, thrown: Exception?, context: Context) {
                throw ScheduleImportException("Malformed ICS content near line ${context.lineNumber}.")
            }
        }) }
        importCheck(calendars == 1 && versions == 1 && events > 0 && stack.isEmpty(), "Incomplete VCALENDAR or no VEVENTs.")
    }
    private companion object { val PERIOD = Regex("第\\s*\\d{1,2}\\s*(?:[-–—]\\s*\\d{1,2}\\s*)?节") }
}
