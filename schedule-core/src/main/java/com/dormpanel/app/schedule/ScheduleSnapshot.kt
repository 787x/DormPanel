package com.dormpanel.app.schedule

import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.*
import java.util.UUID

/** Portable v1 protocol. Positional fields are mandatory; null has an explicit tag.
 * No DB files, preferences, credentials or network instructions enter this format. */
data class ScheduleSnapshot(val datasetId: String, val revision: String, val parentRevision: String?,
    val createdAt: Long, val state: ScheduleState, val generation: Long = if (parentRevision == null) 1 else 2)

object ScheduleSnapshotCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAGIC = 0x44505353
    private fun uuid(value: String) = require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) { "Invalid dataset/revision identity" }
    fun validate(s: ScheduleSnapshot) {
        uuid(s.datasetId); uuid(s.revision); s.parentRevision?.let { uuid(it); require(it != s.revision) }
        require(s.createdAt > 0 && s.state.invalidRecords == 0 && s.generation > 0 && (s.generation == 1L) == (s.parentRevision == null)) { "Invalid snapshot metadata" }
        require(s.state.sharedRecords == null) { "Extended state requires DPV3/4; legacy export refused" }
        val v = s.state
        require(v.sync == null) { "Snapshot must contain schedule data, not local binding state" }
        fun <T> unique(rows: List<T>, max: Int, key: (T) -> String) {
            require(rows.size <= max && rows.map(key).all { it.isNotBlank() } && rows.map(key).distinct().size == rows.size) { "Snapshot limit or duplicate identity" }
        }
        unique(v.events, 10000) { it.id }; require(v.events.all { it.valid() })
        unique(v.entries, 10000) { it.id }; require(v.entries.all { it.valid() })
        unique(v.sources, ImportLimits.SOURCES) { it.id }
        unique(v.imported, ImportLimits.STORED_OCCURRENCES) { it.id }
        unique(v.dayAdjustments, 1000) { it.date }; require(v.dayAdjustments.all { it.valid() })
        unique(v.classOverrides, 5000) { it.key }
        require(v.classOverrides.sumOf { it.textCharacters().toLong() } <= 2_000_000L)
        require(v.imported.sumOf { it.textCharacters() } <= ImportLimits.TEXT_CHARACTERS)
        require(v.profiles.size <= 128 && v.profiles.all { (key, p) -> key == p.termKey && p.validate() == null && p.phases.size <= 128 && p.phases.all { it.periods.size <= 30 } })
        require(v.customProfileKeys.all { it in v.profiles }) { "Invalid custom profile catalog" }
        val sources = v.sources.associateBy { it.id }
        v.sources.forEach { source ->
            val rows = v.imported.filter { it.sourceId == source.id }
            require(source.id.length <= 160 && source.sha256.matches(Regex("[0-9a-f]{64}")) && rows.isNotEmpty() &&
                source.occurrenceCount == rows.size && source.firstStart == rows.minOf { it.start } && source.lastEnd == rows.maxOf { it.end }) { "Invalid source metadata" }
            require(source.resolvedTermKey()?.let { it in v.profiles } != false) { "Missing term profile" }
            // Existing opaque locators are metadata only, but must never carry URL credentials.
            require(source.locator?.let { !it.contains(Regex("(?i)https?://[^/]*@")) } != false) { "Credential-bearing locator" }
        }
        v.imported.forEach { row ->
            require(row.sourceId in sources && row.seriesId.length in 1..200 && row.title.isNotBlank() && row.end > row.start &&
                row.start >= -62135510400000L && row.end <= 253402214400000L)
            require(runCatching { ZoneId.of(row.timezone) }.isSuccess) { "Unknown course timezone" }
        }
        val local = v.entries.map { it.id }.toSet()
        v.classOverrides.forEach { edit ->
            require(edit.valid() && edit.key == ClassOverride.key(edit.sourceId, edit.seriesId, edit.anchorDate) &&
                if (edit.sourceId == null) edit.seriesId in local else edit.sourceId in sources) { "Invalid edit identity" }
        }
    }
    fun encode(s: ScheduleSnapshot): ByteArray {
        validate(s)
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(object : FilterOutputStream(buffer) {
            override fun write(b: Int) { require(buffer.size() < MAX_BYTES - 44); super.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { require(buffer.size().toLong() + len <= MAX_BYTES - 44); out.write(b, off, len) }
        })
        with(out) {
            text(s.datasetId); text(s.revision); nullable(s.parentRevision); writeLong(s.createdAt); writeLong(s.generation)
            val v = s.state
            rows(v.events) { text(it.id); text(it.title); writeLong(it.start); writeLong(it.end); text(it.note) }
            rows(v.entries) { text(it.id); text(it.title); writeInt(it.day); writeInt(it.startMinute); writeInt(it.endMinute); text(it.location) }
            rows(v.sources) { text(it.id); text(it.displayName); text(it.filename); text(it.kind); nullable(it.locator); nullable(it.calendarName); text(it.sha256); writeLong(it.importedAt); text(it.timezones); writeInt(it.occurrenceCount); writeLong(it.firstStart); writeLong(it.lastEnd); nullable(it.termKey) }
            rows(v.imported) { text(it.id); text(it.sourceId); text(it.seriesId); nullable(it.uid); writeLong(it.originalStart); text(it.title); writeLong(it.start); writeLong(it.end); text(it.timezone); text(it.location); text(it.description); nullable(it.periodLabel) }
            rows(v.dayAdjustments) { text(it.date); text(it.mode); number(it.weekday); text(it.label) }
            rows(v.classOverrides) { text(it.key); nullable(it.sourceId); text(it.seriesId); nullable(it.anchorDate); nullable(it.title); number(it.weekday); nullable(it.date); number(it.periodStart); number(it.periodEnd); nullable(it.timingMode); number(it.startMinute); number(it.endMinute); nullable(it.location); nullable(it.teacher); nullable(it.note) }
            rows(v.profiles.values.toList()) { p ->
                text(p.termKey); text(p.timezone); text(p.week1Monday.toString())
                rows(p.phases) { phase -> text(phase.effectiveFrom.toString()); rows(phase.periods) { writeInt(it.periodNumber); text(it.start.toString()); text(it.end.toString()) } }
            }
            rows(v.customProfileKeys.sorted()) { text(it) }
        }
        val payload = buffer.toByteArray()
        return ByteArrayOutputStream().also { b -> DataOutputStream(b).use { it.writeInt(MAGIC); it.writeInt(SCHEMA_VERSION); it.writeInt(payload.size); it.write(payload); it.write(MessageDigest.getInstance("SHA-256").digest(payload)) } }.toByteArray()
    }
    fun decode(bytes: ByteArray): ScheduleSnapshot {
        require(bytes.size in 44..MAX_BYTES) { "Snapshot size limit" }
        val input = DataInputStream(bytes.inputStream())
        require(input.readInt() == MAGIC && input.readInt() == SCHEMA_VERSION) { "Unknown snapshot schema" }
        val length = input.readInt(); require(length == bytes.size - 44) { "Incomplete snapshot" }
        val payload = ByteArray(length); input.readFully(payload)
        val hash = ByteArray(32); input.readFully(hash)
        require(MessageDigest.isEqual(hash, MessageDigest.getInstance("SHA-256").digest(payload))) { "Snapshot checksum failed" }
        return DataInputStream(payload.inputStream()).run {
            val dataset = text(); val revision = text(); val parent = nullable(); val at = readLong(); val generation = readLong()
            val events = rows(10000) { CalendarEvent(text(), text(), readLong(), readLong(), text()) }
            val entries = rows(10000) { TimetableEntry(text(), text(), readInt(), readInt(), readInt(), text()) }
            val sources = rows(16) { ImportSource(text(), text(), text(), text(), nullable(), nullable(), text(), readLong(), text(), readInt(), readLong(), readLong(), nullable()) }
            val imported = rows(50000) { ImportedClassOccurrence(text(), text(), text(), nullable(), readLong(), text(), readLong(), readLong(), text(), text(), text(), nullable()) }
            val days = rows(1000) { DayAdjustment(text(), text(), number(), text()) }
            val edits = rows(5000) { ClassOverride(text(), nullable(), text(), nullable(), nullable(), number(), nullable(), number(), number(), nullable(), number(), number(), nullable(), nullable(), nullable()) }
            val profiles = rows(128) { TermScheduleProfile(text(), text(), LocalDate.parse(text()), rows(128) { SchedulePhase(LocalDate.parse(text()), rows(30) { PeriodTime(readInt(), LocalTime.parse(text()), LocalTime.parse(text())) }) }) }
            val custom = rows(128) { text() }; require(custom.distinct().size == custom.size)
            require(available() == 0 && profiles.map { it.termKey }.distinct().size == profiles.size) { "Unexpected snapshot fields" }
            ScheduleSnapshot(dataset, revision, parent, at, ScheduleState(events, entries, sources = sources, imported = imported, dayAdjustments = days, classOverrides = edits, profiles = profiles.associateBy { it.termKey }, customProfileKeys = custom.toSet()), generation).also(::validate)
        }
    }
    private fun DataOutputStream.text(s: String) { val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(s)); val b = ByteArray(encoded.remaining()); encoded.get(b); require(b.size <= 1_000_000); writeInt(b.size); write(b) }
    private fun DataInputStream.text(): String {
        val n = readInt(); require(n in 0..minOf(1_000_000, available())); val b = ByteArray(n); readFully(b)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
    }
    private fun DataOutputStream.nullable(s: String?) { writeByte(if (s == null) 0 else 1); if (s != null) text(s) }
    private fun DataInputStream.tag(): Boolean = readUnsignedByte().also { require(it in 0..1) } == 1
    private fun DataInputStream.nullable(): String? = if (tag()) text() else null
    private fun DataOutputStream.number(n: Int?) { writeByte(if (n == null) 0 else 1); if (n != null) writeInt(n) }
    private fun DataInputStream.number(): Int? = if (tag()) readInt() else null
    private fun <T> DataOutputStream.rows(rows: List<T>, write: DataOutputStream.(T) -> Unit) { writeInt(rows.size); rows.forEach { write(it) } }
    private fun <T> DataInputStream.rows(max: Int, read: DataInputStream.() -> T): List<T> { val n = readInt(); require(n in 0..max); return List(n) { read.invoke(this) } }
}
