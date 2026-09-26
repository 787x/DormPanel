package com.dormpanel.app.schedule

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Minimal RFC 4180-style CSV reader for the Hubei personal timetable export.
 * Handles quoted multiline fields; never splits on raw commas inside quotes.
 * Lightweight and Android 9 safe — no spreadsheet dependency.
 */
internal object CsvReader {
    /** Decodes timetable bytes. UTF-8 BOM, then strict UTF-8, then GB18030 fallback. */
    fun decode(bytes: ByteArray): String {
        var data = bytes
        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) {
            data = data.copyOfRange(3, data.size)
            return strictUtf8(data) ?: gb18030(data)
        }
        strictUtf8(data)?.let { return it }
        return gb18030(data)
    }

    private fun strictUtf8(data: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun gb18030(data: ByteArray): String {
        val charset = runCatching { Charset.forName("GB18030") }.getOrNull()
            ?: throw ScheduleImportException("Unable to decode this timetable file.")
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString()
        } catch (_: CharacterCodingException) {
            throw ScheduleImportException("Unable to decode this timetable file as UTF-8 or GB18030.")
        }
    }

    /**
     * Parses one CSV document into rows of columns.
     * Accepts CRLF, LF, or CR row endings and quoted fields containing commas/newlines.
     */
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        val length = text.length
        var fieldWasQuoted = false
        fun endField() {
            row.add(field.toString())
            field.setLength(0)
            fieldWasQuoted = false
        }
        fun endRow() {
            endField()
            rows.add(row)
            row = mutableListOf()
        }
        while (index < length) {
            val ch = text[index]
            when {
                inQuotes -> when {
                    ch == '"' && index + 1 < length && text[index + 1] == '"' -> {
                        field.append('"'); index++
                    }
                    ch == '"' -> inQuotes = false
                    else -> field.append(ch)
                }
                ch == '"' && field.isEmpty() && !fieldWasQuoted -> {
                    inQuotes = true
                    fieldWasQuoted = true
                }
                ch == ',' -> endField()
                ch == '\r' -> {
                    if (index + 1 < length && text[index + 1] == '\n') index++
                    endRow()
                }
                ch == '\n' -> endRow()
                else -> field.append(ch)
            }
            index++
        }
        if (inQuotes) throw ScheduleImportException("Malformed CSV: unterminated quoted field.")
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}

/**
 * Structural model of a Hubei University personal timetable CSV.
 * Contains only timetable facts needed for import — never student name/class/major.
 */
data class CsvCourseSeries(
    val title: String,
    val termKey: String,
    val weekday: Int,
    val weekStart: Int,
    val weekEnd: Int,
    val periodNumbers: List<Int>,
    val location: String,
    val description: String
)

data class HubeiCsvStructure(
    val termKey: String,
    val series: List<CsvCourseSeries>,
    val warnings: List<String>
)

/**
 * Parser for the Hubei University personal timetable CSV export only.
 * Structure parsing is separate from absolute-time resolution; this type never
 * invents dates or period times.
 */
object HubeiCsvTimetableParser {
    const val PARSER_ID = "hubei-personal-csv-v1"

    private val PERIOD_ROW = Regex("第[一二三四五六七八九十百零]+节")
    private val PERIOD_HINT = Regex("\\((\\d{1,2}(?:,\\d{1,2})+)小节\\)")
    private val TERM = Regex("学年学期\\s*[:：]\\s*([0-9]{4}-[0-9]{4}-[0-9])")
    private val COURSE = Regex("^(.+?)\\r?\\n(\\d+(?:-\\d+)?)周\\[(\\d{2}(?:-\\d{2})+)节\\]")
    private val DAY_HEADER = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期天", "星期日")

    /** True when the text carries the Hubei personal-timetable structural signature. */
    fun looksLike(text: String): Boolean {
        val hasHeading = text.contains("学生个人课表") || text.contains("个人课表")
        val hasTerm = TERM.containsMatchIn(text)
        val hasDays = DAY_HEADER.any { text.contains(it) }
        val hasPeriodRow = PERIOD_ROW.containsMatchIn(text)
        val hasCourseCell = Regex("\\d+(?:-\\d+)?周\\[\\d{2}(?:-\\d{2})+节\\]").containsMatchIn(text)
        return hasHeading && hasTerm && hasDays && hasPeriodRow && hasCourseCell
    }

    /**
     * Parses structure from decoded CSV text. Rejects unsupported files with a clear message.
     * Personal header fields are never returned or logged.
     */
    fun parse(text: String): HubeiCsvStructure {
        val rows = CsvReader.parse(text)
        importCheck(rows.isNotEmpty(), "This CSV is empty.")
        val termKey = rows.asSequence().flatten().firstNotNullOfOrNull { TERM.find(it)?.groupValues?.get(1) }
            ?: throw ScheduleImportException("Missing 学年学期 term information in this CSV.")
        val dayColumns = findDayColumns(rows)
        val periodRows = findPeriodRows(rows)
        importCheck(periodRows.isNotEmpty(), "No recognizable period rows in this Hubei University timetable CSV.")
        val series = mutableListOf<CsvCourseSeries>()
        val warnings = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for ((rowIndex, row) in rows.withIndex()) {
            val periods = periodRows[rowIndex] ?: continue
            for ((column, day) in dayColumns) {
                val cell = row.getOrNull(column)?.trim().orEmpty()
                if (cell.isEmpty()) continue
                val parsed = parseCourseCell(cell, termKey, day, periods, warnings) ?: continue
                val key = "${parsed.title}\u0000${parsed.weekday}\u0000${parsed.periodNumbers.joinToString(",")}\u0000${parsed.weekStart}-${parsed.weekEnd}"
                if (seen.add(key)) series += parsed else warnings += "Duplicate course entry ignored: ${parsed.title}."
            }
        }
        // Notes rows carry insufficient weekday/period information and never become occurrences.
        for (row in rows) {
            val first = row.firstOrNull().orEmpty()
            if (first.startsWith("备注")) {
                row.drop(1).forEach { note ->
                    val trimmed = note.trim()
                    if (trimmed.isNotEmpty()) warnings += "Note without weekday/period was not imported: $trimmed"
                }
            }
        }
        importCheck(series.isNotEmpty(), "No courses found in this Hubei University timetable CSV.")
        return HubeiCsvStructure(termKey, series, warnings)
    }

    fun parseBytes(bytes: ByteArray): HubeiCsvStructure = parse(CsvReader.decode(bytes))

    private fun findDayColumns(rows: List<List<String>>): List<Pair<Int, Int>> {
        for (row in rows) {
            val mapping = row.mapIndexedNotNull { index, cell ->
                val value = cell.trim()
                when {
                    value == "星期一" -> index to 1
                    value == "星期二" -> index to 2
                    value == "星期三" -> index to 3
                    value == "星期四" -> index to 4
                    value == "星期五" -> index to 5
                    value == "星期六" -> index to 6
                    value == "星期天" || value == "星期日" -> index to 7
                    else -> null
                }
            }
            if (mapping.size >= 5) return mapping.sortedBy { it.second }
        }
        throw ScheduleImportException("Missing Monday–Sunday timetable header in this Hubei University CSV.")
    }

    /** Row index → periods covered by that row, from labels like 第九十十一节 (09,10,11小节). */
    private fun findPeriodRows(rows: List<List<String>>): Map<Int, List<Int>> {
        val result = mutableMapOf<Int, List<Int>>()
        rows.forEachIndexed { index, row ->
            val label = row.firstOrNull().orEmpty()
            if (!PERIOD_ROW.containsMatchIn(label)) return@forEachIndexed
            val numbers = PERIOD_HINT.find(label)?.groupValues?.get(1)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            if (numbers != null && numbers.isNotEmpty() && numbers.all { it in 1..20 }) {
                result[index] = numbers
            }
        }
        return result
    }

    private fun parseCourseCell(
        cell: String,
        termKey: String,
        weekday: Int,
        rowPeriods: List<Int>,
        warnings: MutableList<String>
    ): CsvCourseSeries? {
        val normalized = cell.replace("\r\n", "\n").replace('\r', '\n')
        val match = COURSE.find(normalized)
        if (match == null) {
            val outline = normalized.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(40)
            warnings += "Course cell without week/period specification was not imported: $outline"
            return null
        }
        val title = match.groupValues[1].trim()
        importCheck(title.isNotBlank(), "Course title is required.")
        val weekText = match.groupValues[2]
        val periodText = match.groupValues[3]
        val weekParts = weekText.split('-')
        val weekStart = weekParts.first().toIntOrNull()
        val weekEnd = weekParts.last().toIntOrNull()
        if (weekStart == null || weekEnd == null || weekStart < 1 || weekEnd < weekStart || weekEnd > 30) {
            warnings += "Unsupported week range '$weekText' for $title was not imported."
            return null
        }
        val periods = periodText.split('-').mapNotNull { it.toIntOrNull() }
        if (periods.isEmpty() || periods != periods.sorted() || periods.distinct().size != periods.size ||
            periods.first() < 1 || periods.last() > 20) {
            warnings += "Unsupported period specification '$periodText' for $title was not imported."
            return null
        }
        // Cell periods are authoritative. The broader row range must not extend the class.
        importCheck(periods.all { it in rowPeriods } || periods.last() <= (rowPeriods.maxOrNull() ?: 20),
            "Period specification '$periodText' is inconsistent with its timetable row.")
        val lines = normalized.substring(match.range.last + 1).lines()
            .map { it.trim() }.filter { it.isNotEmpty() }
        val location = lines.firstOrNull().orEmpty()
        val description = lines.drop(1).joinToString("\n")
        return CsvCourseSeries(title, termKey, weekday, weekStart, weekEnd, periods, location, description)
    }
}
