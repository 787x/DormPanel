package com.dormpanel.app.schedule

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Entity(tableName = "events")
data class EventRow(@PrimaryKey val id: String, val title: String, val start: Long, val end: Long, val note: String)
@Entity(tableName = "timetable")
data class TimetableRow(@PrimaryKey val id: String, val title: String, val day: Int, val startMinute: Int, val endMinute: Int, val location: String)
@Entity(tableName = "schedule_sync_checkpoint")
data class ScheduleSyncCheckpoint(@PrimaryKey val id: Int = 1, val payload: ByteArray)
@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedule_sync_checkpoint WHERE id = 1") fun checkpoint(): ScheduleSyncCheckpoint?
    @Query("SELECT * FROM schedule_sync_checkpoint WHERE id = :id") fun syncRecord(id: Int): ScheduleSyncCheckpoint?
    @Query("DELETE FROM schedule_sync_checkpoint WHERE id = :id") fun deleteSyncRecord(id: Int)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun checkpoint(row: ScheduleSyncCheckpoint)
    @Query("DELETE FROM events") fun clearEvents()
    @Query("DELETE FROM timetable") fun clearEntries()
    @Query("DELETE FROM class_overrides") fun clearOverrides()
    @Query("DELETE FROM day_adjustments") fun clearDays()
    @Query("DELETE FROM import_sources") fun clearSources()

    @Query("SELECT * FROM events ORDER BY start, title, id") fun events(): List<EventRow>
    @Query("SELECT * FROM timetable ORDER BY day, startMinute, title, id") fun entries(): List<TimetableRow>
    @Query("SELECT * FROM day_adjustments LIMIT 1001") fun dayAdjustments(): List<DayAdjustment>
    @Query("SELECT * FROM class_overrides LIMIT 5001") fun classOverrides(): List<ClassOverride>
    @Query("SELECT COUNT(*) FROM day_adjustments") fun dayAdjustmentCount(): Int
    @Query("SELECT COUNT(*) FROM class_overrides") fun classOverrideCount(): Int
    @Query("""SELECT COALESCE(SUM(length(key) + length(COALESCE(sourceId,'')) + length(seriesId) +
        length(COALESCE(anchorDate,'')) + length(COALESCE(title,'')) + length(COALESCE(date,'')) +
        length(COALESCE(timingMode,'')) + length(COALESCE(location,'')) +
        length(COALESCE(teacher,'')) + length(COALESCE(note,''))), 0) FROM class_overrides""")
    fun classOverrideTextCharacters(): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: DayAdjustment)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: ClassOverride)
    @Query("DELETE FROM day_adjustments WHERE date = :date") fun deleteDayAdjustment(date: String)
    @Query("DELETE FROM class_overrides WHERE key = :key") fun deleteClassOverride(key: String)
    @Query("DELETE FROM class_overrides WHERE sourceId = :sourceId") fun deleteSourceOverrides(sourceId: String)
    @Query("DELETE FROM class_overrides WHERE sourceId IS NULL AND seriesId = :seriesId") fun deleteLocalOverrides(seriesId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: EventRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: TimetableRow)
    @Query("DELETE FROM events WHERE id = :id") fun deleteEvent(id: String)
    @Query("DELETE FROM timetable WHERE id = :id") fun deleteEntry(id: String)
    @Query("SELECT * FROM import_sources ORDER BY displayName, id") fun sources(): List<ImportSource>
    @Query("SELECT * FROM imported_timetable_occurrences ORDER BY start, id LIMIT 50001") fun imported(): List<ImportedClassOccurrence>
    @Query("SELECT * FROM import_sources WHERE id = :id") fun source(id: String): ImportSource?
    @Query("SELECT COUNT(*) FROM imported_timetable_occurrences WHERE sourceId != :id") fun otherCount(id: String): Int
    @Query("""SELECT COALESCE(SUM(length(id) + length(sourceId) + length(seriesId) + length(COALESCE(uid, '')) +
        length(title) + length(timezone) + length(location) + length(description) + length(COALESCE(periodLabel, ''))), 0)
        FROM imported_timetable_occurrences WHERE sourceId != :id""") fun otherTextCharacters(id: String): Long
    @Query("SELECT COUNT(*) FROM import_sources") fun sourceCount(): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertSource(row: ImportSource)
    @Update fun updateSource(row: ImportSource)
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertOccurrences(rows: List<ImportedClassOccurrence>)
    @Query("DELETE FROM imported_timetable_occurrences WHERE sourceId = :id") fun deleteOccurrences(id: String)
    @Query("DELETE FROM import_sources WHERE id = :id") fun deleteSource(id: String)
}
@Database(entities = [EventRow::class, TimetableRow::class, ImportSource::class, ImportedClassOccurrence::class,
    DayAdjustment::class, ClassOverride::class, ScheduleSyncCheckpoint::class], version = 4, exportSchema = false)
abstract class ScheduleDatabase : RoomDatabase() {
    abstract fun dao(): ScheduleDao
    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS schedule_sync_checkpoint (id INTEGER NOT NULL PRIMARY KEY, payload BLOB NOT NULL)")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS import_sources (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL,
                    filename TEXT NOT NULL, kind TEXT NOT NULL, locator TEXT, calendarName TEXT, sha256 TEXT NOT NULL,
                    importedAt INTEGER NOT NULL, timezones TEXT NOT NULL, occurrenceCount INTEGER NOT NULL,
                    firstStart INTEGER NOT NULL, lastEnd INTEGER NOT NULL)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS imported_timetable_occurrences (id TEXT NOT NULL PRIMARY KEY,
                    sourceId TEXT NOT NULL, seriesId TEXT NOT NULL, uid TEXT, originalStart INTEGER NOT NULL,
                    title TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, timezone TEXT NOT NULL,
                    location TEXT NOT NULL, description TEXT NOT NULL, periodLabel TEXT,
                    FOREIGN KEY(sourceId) REFERENCES import_sources(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_imported_timetable_occurrences_sourceId ON imported_timetable_occurrences(sourceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_imported_timetable_occurrences_start ON imported_timetable_occurrences(start)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_imported_timetable_occurrences_end_start ON imported_timetable_occurrences(end, start)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE import_sources ADD COLUMN termKey TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS day_adjustments (date TEXT NOT NULL PRIMARY KEY, mode TEXT NOT NULL, weekday INTEGER, label TEXT NOT NULL)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS class_overrides (key TEXT NOT NULL PRIMARY KEY,
                    sourceId TEXT, seriesId TEXT NOT NULL, anchorDate TEXT, title TEXT, weekday INTEGER,
                    date TEXT, periodStart INTEGER, periodEnd INTEGER, timingMode TEXT,
                    startMinute INTEGER, endMinute INTEGER, location TEXT, teacher TEXT, note TEXT)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_class_overrides_sourceId ON class_overrides(sourceId)")
            }
        }
    }
}
object ScheduleStores {
    @Volatile var overrideFactory: ((Context) -> ScheduleStore)? = null
    fun create(context: Context, profiles: TermScheduleProfileStore = TermScheduleProfileStore(context)): ScheduleStore = (overrideFactory?.invoke(context) ?: RoomScheduleStore(
        Room.databaseBuilder(context.applicationContext, ScheduleDatabase::class.java, "schedule.db")
            .addMigrations(ScheduleDatabase.MIGRATION_1_2, ScheduleDatabase.MIGRATION_2_3, ScheduleDatabase.MIGRATION_3_4).build(), profiles = profiles, onCommitted = { ScheduleSyncJobs.schedule(context.applicationContext) })).also { (it as? RoomScheduleStore)?.attachProfileStore(profiles) }
}
/** Admission/close share a lock. Accepted writes drain; neither close nor Activity teardown waits. */
class RoomScheduleStore(private val database: ScheduleDatabase,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val closeDatabase: Boolean = true, private var profiles: TermScheduleProfileStore? = null,
    private val afterSnapshotRows: (() -> Unit)? = null, private val onCommitted: (() -> Unit)? = null) : ScheduleStore, ScheduleSyncStore {
    init { profiles?.sharedStore = this }
    fun attachProfileStore(store: TermScheduleProfileStore) { profiles = store; store.sharedStore = this }
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Synchronized private fun <T> submit(callback: (Result<T>) -> Unit, operation: () -> T) {
        if (closed) return
        executor.execute { val result = runCatching(operation); handler.post { if (!closed) callback(result) } }
    }
    private fun <T> write(callback: (Result<T>) -> Unit, operation: () -> T) = submit(callback) {
        database.runInTransaction<T> {
            operation()
        }.also { onCommitted?.invoke() }
    }
    private fun readState(): ScheduleState =
        database.runInTransaction<ScheduleState> {
            check(database.dao().dayAdjustmentCount() <= 1000 && database.dao().classOverrideCount() <= 5000 &&
                database.dao().classOverrideTextCharacters() <= 2_000_000L) { "Stored override limit exceeded" }
            val state = ScheduleState(database.dao().events().map { CalendarEvent(it.id, it.title, it.start, it.end, it.note) },
                database.dao().entries().map { TimetableEntry(it.id, it.title, it.day, it.startMinute, it.endMinute, it.location) },
                sources = database.dao().sources(), imported = database.dao().imported().also {
                    check(it.size <= ImportLimits.STORED_OCCURRENCES) { "Stored occurrence limit exceeded" }
                }, dayAdjustments = database.dao().dayAdjustments().also { check(it.size <= 1000) },
                classOverrides = database.dao().classOverrides().also { check(it.size <= 5000) })
            val saved = database.dao().checkpoint()?.let { ScheduleSnapshotCodec.decode(it.payload) }
            val catalog = database.dao().syncRecord(2)?.let { ScheduleSnapshotCodec.decode(it.payload).state } ?: saved?.state
            if (saved == null) state else state.copy(profiles = catalog!!.profiles, customProfileKeys = catalog.customProfileKeys,
                sync = SnapshotIdentity(saved.datasetId, saved.revision))
        }
    override fun load(callback: (Result<ScheduleState>) -> Unit) = submit(callback) { readState() }
    override fun exportSnapshot(datasetId: String, parent: String?, generation: Long, callback: (Result<ByteArray>) -> Unit) = submit(callback) {
        database.runInTransaction<ByteArray> {
            val state = readState()
            val catalog = if (state.sync != null) state.profiles to state.customProfileKeys else requireNotNull(profiles) { "Term profile store required for complete snapshot export" }.preferenceCatalog()
            ScheduleSnapshotCodec.encode(ScheduleSnapshot(datasetId, java.util.UUID.randomUUID().toString(), parent,
                System.currentTimeMillis(), state.copy(profiles = catalog.first, customProfileKeys = catalog.second, sync = null), generation))
        }
    }
    override fun applySnapshot(bytes: ByteArray, callback: (Result<Unit>) -> Unit) = submit(callback) {
        val stable = bytes.copyOf()
        val snapshot = ScheduleSnapshotCodec.decode(stable)
        var conflicted = false
        database.runInTransaction {
            val dao = database.dao()
            val old = dao.checkpoint()?.let { ScheduleSnapshotCodec.decode(it.payload) }
            if (old != null) {
                conflicted = integrate(stable).isNotEmpty()
                return@runInTransaction
            }
            run {
                check(profiles != null) { "Term profile store required for safe first binding" }
                val current = readState()
                check(current.events.isEmpty() && current.entries.isEmpty() && current.sources.isEmpty() && current.imported.isEmpty() &&
                    current.dayAdjustments.isEmpty() && current.classOverrides.isEmpty() && profiles?.hasCustomProfiles() != true) {
                    "PR31 cannot bind a non-empty local timetable or custom term configuration"
                }
            }
            dao.clearOverrides(); dao.clearDays(); dao.clearSources(); dao.clearEvents(); dao.clearEntries()
            val v = snapshot.state
            v.events.forEach { dao.put(EventRow(it.id, it.title, it.start, it.end, it.note)) }
            v.entries.forEach { dao.put(TimetableRow(it.id, it.title, it.day, it.startMinute, it.endMinute, it.location)) }
            v.sources.forEach(dao::insertSource); dao.insertOccurrences(v.imported)
            v.dayAdjustments.forEach(dao::put); v.classOverrides.forEach(dao::put)
            afterSnapshotRows?.invoke()
            dao.checkpoint(ScheduleSyncCheckpoint(payload = stable))
            dao.checkpoint(ScheduleSyncCheckpoint(2, catalogBytes(snapshot, snapshot.state)))
        }
        check(!conflicted) { "存在冲突；本地修改与远端快照已保存" }
    }
    // IDs in the existing v4 checkpoint table: 1 confirmed baseline, 2 local catalog,
    // 3 unresolved remote, 4 uncertain publication, 5 exact local capture for that publication,
    // 6 traversal head and 7 proven history cursor (resumable bounded history verification).
    // No schema change or destructive migration: PR31's id=1 becomes the baseline in place.
    private fun catalogBytes(snapshot: ScheduleSnapshot, state: ScheduleState) = ScheduleSnapshotCodec.encode(
        snapshot.copy(state = ScheduleState(profiles = state.profiles, customProfileKeys = state.customProfileKeys)))
    private fun decoded(id: Int) = database.dao().syncRecord(id)?.let { ScheduleSnapshotCodec.decode(it.payload) }
    private fun fullState(): ScheduleState {
        val state = readState().copy(sync = null)
        return if (database.dao().checkpoint() != null) state else {
            val catalog = requireNotNull(profiles).preferenceCatalog()
            state.copy(profiles = catalog.first, customProfileKeys = catalog.second)
        }
    }
    private fun <T> blocking(operation: () -> T): T {
        check(!closed) { "Schedule store closed" }
        try { return executor.submit<T> { database.runInTransaction<T> { operation() } }.get() }
        catch (error: java.util.concurrent.ExecutionException) { throw (error.cause ?: error) }
    }
    override fun syncFrame(): ScheduleSyncFrame = blocking {
        ScheduleSyncFrame(decoded(1), fullState(), decoded(4), decoded(3), decoded(5)?.state)
    }
    internal fun sharedCatalog(): Pair<Map<String, TermScheduleProfile>, Set<String>>? = blocking {
        val catalog = decoded(2) ?: decoded(1)?.also {
            database.dao().checkpoint(ScheduleSyncCheckpoint(2, catalogBytes(it, it.state)))
        }
        catalog?.state?.let { it.profiles to it.customProfileKeys }
    }
    internal fun changeProfile(profile: TermScheduleProfile?, key: String): Boolean = blocking {
        val base = decoded(1)
        if (base == null) {
            // Serialize preference edits with the first Room baseline/catalog establishment.
            requireNotNull(profiles).changePreference(profile, key)
            return@blocking true
        }
        val state = fullState()
        val catalog = state.profiles.toMutableMap()
        if (profile != null) catalog[key] = profile else {
            BuiltInProfiles.builtIn(key)?.let { catalog[key] = it } ?: catalog.remove(key)
            check(state.sources.none { it.resolvedTermKey() == key } || key in catalog) {
                "学期仍有关联来源；请先删除来源，再删除学期"
            }
        }
        val custom = if (profile == null) state.customProfileKeys - key else state.customProfileKeys + key
        val bytes = catalogBytes(base, state.copy(profiles = catalog, customProfileKeys = custom))
        database.dao().checkpoint(ScheduleSyncCheckpoint(2, bytes))
        true
    }.also { if (it) onCommitted?.invoke() }
    override fun establishBaseline(bytes: ByteArray) = blocking {
        val base = ScheduleSnapshotCodec.decode(bytes)
        val existing = decoded(1)
        check(existing == null || existing == base) { "Baseline already established" }
        if (existing == null) {
            val current = fullState()
            database.dao().checkpoint(ScheduleSyncCheckpoint(2, catalogBytes(base, current)))
            database.dao().checkpoint(ScheduleSyncCheckpoint(payload = bytes.copyOf()))
        }
    }
    override fun stagePublication(bytes: ByteArray, captured: ScheduleState) = blocking {
        val candidate = ScheduleSnapshotCodec.decode(bytes)
        val base = decoded(1)
        check(candidate.parentRevision == base?.revision && (base == null || candidate.datasetId == base.datasetId))
        check(decoded(4) == null) { "Pending publication must be reconciled first" }
        database.dao().checkpoint(ScheduleSyncCheckpoint(4, bytes.copyOf()))
        database.dao().checkpoint(ScheduleSyncCheckpoint(5, ScheduleSnapshotCodec.encode(candidate.copy(state = captured.copy(sync = null)))))
    }
    private fun replaceState(state: ScheduleState, baseline: ScheduleSnapshot) {
        val dao = database.dao()
        // Encode/validate before touching rows, including all cross-record references and limits.
        ScheduleSnapshotCodec.validate(baseline.copy(state = state.copy(sync = null)))
        dao.clearOverrides(); dao.clearDays(); dao.clearSources(); dao.clearEvents(); dao.clearEntries()
        state.events.forEach { dao.put(EventRow(it.id, it.title, it.start, it.end, it.note)) }
        state.entries.forEach { dao.put(TimetableRow(it.id, it.title, it.day, it.startMinute, it.endMinute, it.location)) }
        state.sources.forEach(dao::insertSource); dao.insertOccurrences(state.imported)
        state.dayAdjustments.forEach(dao::put); state.classOverrides.forEach(dao::put)
        afterSnapshotRows?.invoke()
        dao.checkpoint(ScheduleSyncCheckpoint(2, catalogBytes(baseline, state)))
    }
    private fun integrate(bytes: ByteArray): List<ScheduleMerge.Conflict> {
        val remote = ScheduleSnapshotCodec.decode(bytes)
        val base = decoded(1)
        val observed = decoded(3)
        if (observed != null) {
            check(remote.datasetId == observed.datasetId && remote.generation >= observed.generation) { "Remote is older than saved conflict" }
            if (remote.generation == observed.generation) check(remote == observed) { "Remote conflict revision changed" }
        }
        val pending = decoded(4)
        val receipt = pending?.revision == remote.revision
        if (base != null) {
            check(base.datasetId == remote.datasetId) { "远端 dataset 已变化；拒绝替换" }
            if (base.revision == remote.revision) {
                check(database.dao().checkpoint()!!.payload.contentEquals(bytes)) { "Revision content changed" }
                return emptyList()
            }
            check(remote.generation > base.generation) { "Remote generation is stale or conflicts with the cached revision" }
        } else check(receipt) { "Missing verified common baseline" }
        if (receipt) check(database.dao().syncRecord(4)!!.payload.contentEquals(bytes)) { "Pending revision content changed" }
        val ancestor = if (receipt) requireNotNull(decoded(5)).state else requireNotNull(base).state
        val result = ScheduleMerge.merge(ancestor, fullState(), remote.state)
        if (result.conflicts.isNotEmpty()) {
            database.dao().checkpoint(ScheduleSyncCheckpoint(3, bytes.copyOf()))
            return result.conflicts
        }
        replaceState(result.state, remote)
        database.dao().checkpoint(ScheduleSyncCheckpoint(payload = bytes.copyOf()))
        database.dao().deleteSyncRecord(3)
        if (receipt) { database.dao().deleteSyncRecord(4); database.dao().deleteSyncRecord(5) }
        return emptyList()
    }
    override fun integrateRemote(bytes: ByteArray): List<ScheduleMerge.Conflict> = blocking { integrate(bytes) }
    /** Called only after a verified history walk proves the candidate is absent from the head chain. */
    override fun discardUncommittedPublication() = blocking {
        database.dao().deleteSyncRecord(4); database.dao().deleteSyncRecord(5)
    }
    override fun historyCursor(head: ByteArray): ByteArray? = blocking {
        if (database.dao().syncRecord(6)?.payload?.contentEquals(head) == true) database.dao().syncRecord(7)?.payload else null
    }
    override fun saveHistoryCursor(head: ByteArray, cursor: ByteArray) = blocking {
        val top = ScheduleSnapshotCodec.decode(head); val next = ScheduleSnapshotCodec.decode(cursor)
        check(top.datasetId == next.datasetId && top.generation >= next.generation)
        database.dao().checkpoint(ScheduleSyncCheckpoint(6, head.copyOf()))
        database.dao().checkpoint(ScheduleSyncCheckpoint(7, cursor.copyOf()))
    }
    override fun resolveConflict(choice: ScheduleMerge.Choice) = blocking {
        val remote = requireNotNull(decoded(3)) { "No saved conflict" }
        val pending = decoded(4)
        val ancestor = if (pending?.revision == remote.revision) requireNotNull(decoded(5)) else requireNotNull(decoded(1))
        val result = ScheduleMerge.merge(ancestor.state, fullState(), remote.state, choice)
        replaceState(result.state, remote)
        database.dao().checkpoint(ScheduleSyncCheckpoint(payload = ScheduleSnapshotCodec.encode(remote)))
        database.dao().deleteSyncRecord(3)
        if (pending?.revision == remote.revision) { database.dao().deleteSyncRecord(4); database.dao().deleteSyncRecord(5) }
    }
    override fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit) = write(callback) {
        database.dao().put(EventRow(event.id, event.title, event.start, event.end, event.note))
    }
    override fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit) = write(callback) {
        database.dao().put(TimetableRow(entry.id, entry.title, entry.day, entry.startMinute, entry.endMinute, entry.location))
    }
    override fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit) = write(callback) { database.dao().deleteEvent(id) }
    override fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit) = write(callback) {
        database.runInTransaction { database.dao().deleteEntry(id); database.dao().deleteLocalOverrides(id) }
    }
    override fun putDayAdjustment(item: DayAdjustment?, date: String, callback: (Result<Unit>) -> Unit) = write(callback) {
        if (item == null) database.dao().deleteDayAdjustment(date) else database.dao().put(item)
    }
    override fun putClassOverride(item: ClassOverride?, key: String, callback: (Result<Unit>) -> Unit) = write(callback) {
        if (item != null) check(if (item.sourceId != null) database.dao().source(item.sourceId) != null
            else database.dao().entries().any { it.id == item.seriesId }) { "Course was deleted; reopen the timetable" }
        if (item == null) database.dao().deleteClassOverride(key) else database.dao().put(item)
    }
    override fun commitImport(source: ImportSource, occurrences: List<ImportedClassOccurrence>, replace: Boolean,
        callback: (Result<ImportCommit>) -> Unit) = write(callback) {
        database.runInTransaction<ImportCommit> {
            val dao = database.dao()
            if (dao.checkpoint() != null) check(source.resolvedTermKey()?.let { it in fullState().profiles } != false) { "Missing term profile" }
            val existing = dao.source(source.id)
            check(if (replace) existing != null else existing == null) { "Import source changed; reopen the preview" }
            if (existing?.sha256 == source.sha256) ImportCommit(existing, emptyList(), true)
            else {
                importCheck(occurrences.size in 1..ImportLimits.OCCURRENCES && occurrences.size == source.occurrenceCount &&
                    occurrences.all { it.sourceId == source.id && it.end > it.start }, "Invalid imported occurrences.")
                importCheck(dao.otherCount(source.id) + occurrences.size <= ImportLimits.STORED_OCCURRENCES, "Stored schedules exceed 50,000 classes. Delete an old source first.")
                importCheck(dao.otherTextCharacters(source.id) + occurrences.sumOf { it.textCharacters() } <= ImportLimits.TEXT_CHARACTERS,
                    "Stored timetable text exceeds 4 million characters. Delete an old source first.")
                importCheck(replace || dao.sourceCount() < ImportLimits.SOURCES, "At most 16 timetable sources are supported.")
                if (replace) { dao.deleteOccurrences(source.id); dao.updateSource(source) } else dao.insertSource(source)
                dao.insertOccurrences(occurrences)
                ImportCommit(source, occurrences, false)
            }
        }
    }
    override fun deleteImport(id: String, callback: (Result<Unit>) -> Unit) = write(callback) {
        database.runInTransaction { database.dao().deleteSourceOverrides(id); database.dao().deleteSource(id) }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        executor.execute { if (closeDatabase) database.close() }; executor.shutdown()
    }
}
