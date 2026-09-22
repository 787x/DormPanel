package com.dormpanel.app.schedule

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

object ImportLimits {
    const val BYTES = 1024 * 1024
    const val EVENTS = 256
    const val OCCURRENCES = 10000
    const val STORED_OCCURRENCES = 50000
    const val SOURCES = 16
    const val TEXT_CHARACTERS = 4_000_000L
    const val CANDIDATES = 20000
    const val SPAN_DAYS = 3660L
}

class ScheduleImportException(message: String) : IllegalArgumentException(message)
internal fun importCheck(condition: Boolean, message: String) {
    if (!condition) throw ScheduleImportException(message)
}

/** Transport data only. Locator is an opaque, credential-free identifier, never a fetch instruction. */
class ScheduleArtifact(val filename: String, bytes: ByteArray, val mimeType: String? = null,
    val kind: String = "local_document", val locator: String? = null) {
    init { importCheck(bytes.size <= ImportLimits.BYTES, "ICS exceeds the 1 MiB size limit.") }
    private val payload = bytes.copyOf()
    internal fun bytes() = payload.copyOf()
    companion object {
        fun read(filename: String, input: InputStream, mimeType: String? = null,
            kind: String = "local_document", locator: String? = null): ScheduleArtifact {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                importCheck(output.size() + count <= ImportLimits.BYTES, "ICS exceeds the 1 MiB size limit.")
                output.write(buffer, 0, count)
            }
            return ScheduleArtifact(filename, output.toByteArray(), mimeType, kind, locator)
        }
    }
}

@Entity(tableName = "import_sources")
data class ImportSource(@PrimaryKey val id: String, val displayName: String, val filename: String,
    val kind: String, val locator: String?, val calendarName: String?, val sha256: String,
    val importedAt: Long, val timezones: String, val occurrenceCount: Int, val firstStart: Long, val lastEnd: Long)

@Entity(tableName = "imported_timetable_occurrences",
    foreignKeys = [ForeignKey(entity = ImportSource::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId"), Index("start"), Index(value = ["end", "start"])])
data class ImportedClassOccurrence(@PrimaryKey val id: String, val sourceId: String, val seriesId: String,
    val uid: String?, val originalStart: Long, val title: String, val start: Long, val end: Long,
    val timezone: String, val location: String, val description: String, val periodLabel: String?)

internal fun ImportedClassOccurrence.textCharacters(): Long = id.length.toLong() + sourceId.length + seriesId.length +
    (uid?.length ?: 0) + title.length + timezone.length + location.length + description.length + (periodLabel?.length ?: 0)

data class ParsedTimetable(val calendarName: String?, val seriesCount: Int,
    val occurrences: List<ImportedClassOccurrence>, val warnings: List<String>, val hasAlarms: Boolean)

/** Only the parser can construct a preview. No mutable library objects or artifact bytes reach UI. */
class ImportPreview internal constructor(val filename: String, val kind: String, val locator: String?,
    val sha256: String, parsed: ParsedTimetable) {
    val calendarName = parsed.calendarName
    val seriesCount = parsed.seriesCount
    val occurrences: List<ImportedClassOccurrence> = java.util.Collections.unmodifiableList(parsed.occurrences.toList())
    val warnings: List<String> = java.util.Collections.unmodifiableList(parsed.warnings.toList())
    val hasAlarms = parsed.hasAlarms
    val timezones = occurrences.map { it.timezone }.distinct().sorted().joinToString(", ")
    val firstStart = occurrences.minOf { it.start }
    val lastEnd = occurrences.maxOf { it.end }
    internal fun source(id: String, name: String, now: Long) = ImportSource(id, name.trim(), filename,
        kind, locator, calendarName, sha256, now, timezones, occurrences.size, firstStart, lastEnd)
    internal fun forSource(id: String) = occurrences.map {
        it.copy(id = "$id:${it.id}", sourceId = id)
    }
}

interface IcsScheduleParser { fun parse(artifact: ScheduleArtifact): ParsedTimetable }
data class ImportCommit(val source: ImportSource, val occurrences: List<ImportedClassOccurrence>, val unchanged: Boolean)

/** Synchronous core: callers run parse off-main; persistence owns its transaction/executor. */
class IcsScheduleImporter(private val parser: IcsScheduleParser = BiweeklyScheduleParser()) {
    fun parse(artifact: ScheduleArtifact): ImportPreview = ImportPreview(artifact.filename, artifact.kind,
        artifact.locator, digest(artifact.bytes()), parser.parse(artifact))
    fun commit(preview: ImportPreview, store: ScheduleStore, displayName: String, targetId: String?,
        now: Long, callback: (Result<ImportCommit>) -> Unit) {
        if (displayName.isBlank()) { callback(Result.failure(ScheduleImportException("Enter a timetable name."))); return }
        val id = targetId ?: UUID.randomUUID().toString()
        store.commitImport(preview.source(id, displayName, now), preview.forSource(id), targetId != null, callback)
    }
}

internal fun digest(bytes: ByteArray): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex = "0123456789abcdef"
    return CharArray(hash.size * 2) { index ->
        val byte = hash[index / 2].toInt() and 255
        hex[if (index % 2 == 0) byte ushr 4 else byte and 15]
    }.concatToString()
}

/** Shared immutable range index, built once per changed import snapshot, never per card render. */
class ImportedOccurrenceIndex(occurrences: List<ImportedClassOccurrence>) {
    private val sorted = occurrences.sortedBy { it.start }
    private val ends = LongArray(sorted.size)
    init { var max = Long.MIN_VALUE; sorted.forEachIndexed { i, item -> max = maxOf(max, item.end); ends[i] = max } }
    private fun firstAfterEnd(time: Long): Int {
        var low = 0; var high = ends.size
        while (low < high) { val mid = (low + high) ushr 1; if (ends[mid] <= time) low = mid + 1 else high = mid }
        return low
    }
    fun range(start: Long, end: Long): List<ImportedClassOccurrence> = sorted.subList(firstAfterEnd(start), sorted.size).asSequence()
        .takeWhile { it.start < end }.filter { it.end > start }.toList()
    fun upcoming(now: Long, limit: Int): List<ImportedClassOccurrence> = sorted.subList(firstAfterEnd(now), sorted.size).asSequence()
        .filter { it.end > now }.take(limit).toList()
}
