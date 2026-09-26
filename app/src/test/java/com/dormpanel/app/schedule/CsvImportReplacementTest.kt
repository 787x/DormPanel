package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Replacement semantics: same CSV+profile is a no-op; changed profile re-times atomically. */
class CsvImportReplacementTest {
    private val fixtureBytes: ByteArray =
        javaClass.getResourceAsStream("/schedule/hubei_2026-2027-1.csv")!!.use { it.readBytes() }

    private class MemoryStore : ScheduleStore {
        val sources = mutableMapOf<String, ImportSource>()
        val occurrences = mutableMapOf<String, MutableList<ImportedClassOccurrence>>()
        var failNextInsert = false
        override fun load(callback: (Result<ScheduleState>) -> Unit) =
            callback(Result.success(ScheduleState(sources = sources.values.toList(),
                imported = occurrences.values.flatten())))
        override fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
        override fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
        override fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
        override fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
        override fun commitImport(source: ImportSource, occurrences: List<ImportedClassOccurrence>,
            replace: Boolean, callback: (Result<ImportCommit>) -> Unit) {
            val existing = sources[source.id]
            check(if (replace) existing != null else existing == null)
            if (existing?.sha256 == source.sha256) {
                callback(Result.success(ImportCommit(existing, emptyList(), true)))
                return
            }
            if (failNextInsert) {
                failNextInsert = false
                callback(Result.failure(ScheduleImportException("Simulated insert failure")))
                return
            }
            if (replace) this.occurrences.remove(source.id) else check(source.id !in sources)
            sources[source.id] = source
            this.occurrences[source.id] = occurrences.toMutableList()
            callback(Result.success(ImportCommit(source, occurrences, false)))
        }
        override fun deleteImport(id: String, callback: (Result<Unit>) -> Unit) {
            sources.remove(id); occurrences.remove(id); callback(Result.success(Unit))
        }
        override fun close() {}
    }

    private fun preview(profile: TermScheduleProfile, name: String = "t.csv"): ImportPreview =
        resolveHubeiCsv(HubeiCsvTimetableParser.parseBytes(fixtureBytes), profile, name, rawBytes = fixtureBytes)

    @Test fun exactCsvAndProfileReplacementIsNoOp() {
        val profile = BuiltInProfiles.term2026
        val store = MemoryStore()
        val first = preview(profile)
        IcsScheduleImporter().commit(first, store, "A", null, 1L) { it.getOrThrow() }
        val second = preview(profile)
        assertEquals(first.sha256, second.sha256)
        var result: ImportCommit? = null
        IcsScheduleImporter().commit(second, store, "A", store.sources.keys.first(), 2L) { result = it.getOrThrow() }
        assertTrue(result!!.unchanged)
        assertEquals(131, store.occurrences.values.sumOf { it.size })
    }

    @Test fun changedProfileReTimesSourceAtomically() {
        val profile = BuiltInProfiles.term2026
        val store = MemoryStore()
        val first = preview(profile)
        IcsScheduleImporter().commit(first, store, "A", null, 1L) { it.getOrThrow() }
        val id = store.sources.keys.first()
        val originalStart = store.occurrences[id]!!.first().start
        val moved = profile.copy(week1Monday = profile.week1Monday.plusWeeks(1))
        val second = preview(moved)
        assertNotEquals(first.sha256, second.sha256)
        IcsScheduleImporter().commit(second, store, "A", id, 2L) { it.getOrThrow() }
        assertEquals(131, store.occurrences[id]!!.size)
        assertTrue(store.occurrences[id]!!.first().start != originalStart)
    }

    @Test fun failedReplacementPreservesPreviousSource() {
        val profile = BuiltInProfiles.term2026
        val store = MemoryStore()
        val first = preview(profile)
        IcsScheduleImporter().commit(first, store, "A", null, 1L) { it.getOrThrow() }
        val id = store.sources.keys.first()
        val before = store.occurrences[id]!!.map { it.start }
        val moved = profile.copy(week1Monday = profile.week1Monday.plusWeeks(1))
        store.failNextInsert = true
        var failure: Throwable? = null
        IcsScheduleImporter().commit(preview(moved), store, "A", id, 2L) {
            failure = it.exceptionOrNull()
        }
        assertNotNull(failure)
        assertEquals(before, store.occurrences[id]!!.map { it.start })
        assertEquals(131, store.occurrences[id]!!.size)
    }

    @Test fun icsImportBehaviorUnchanged() {
        val ics = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//test//EN
BEGIN:VEVENT
UID:one
DTSTAMP:20260901T000000Z
SUMMARY:Math
DTSTART;TZID=Asia/Shanghai:20260904T080000
DTEND;TZID=Asia/Shanghai:20260904T095000
END:VEVENT
END:VCALENDAR
""".toByteArray()
        val store = MemoryStore()
        val preview = TimetableImporter().parse(ScheduleArtifact("a.ics", ics))
        assertEquals(digest(ics), preview.sha256)
        IcsScheduleImporter().commit(preview, store, "I", null, 1L) { it.getOrThrow() }
        assertEquals(1, store.occurrences.values.sumOf { it.size })
    }
}
