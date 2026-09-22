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
@Dao
interface ScheduleDao {
    @Query("SELECT * FROM events ORDER BY start, title, id") fun events(): List<EventRow>
    @Query("SELECT * FROM timetable ORDER BY day, startMinute, title, id") fun entries(): List<TimetableRow>
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
@Database(entities = [EventRow::class, TimetableRow::class, ImportSource::class, ImportedClassOccurrence::class], version = 2, exportSchema = false)
abstract class ScheduleDatabase : RoomDatabase() {
    abstract fun dao(): ScheduleDao
    companion object {
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
    }
}
object ScheduleStores {
    @Volatile var overrideFactory: ((Context) -> ScheduleStore)? = null
    fun create(context: Context): ScheduleStore = overrideFactory?.invoke(context) ?: RoomScheduleStore(
        Room.databaseBuilder(context.applicationContext, ScheduleDatabase::class.java, "schedule.db")
            .addMigrations(ScheduleDatabase.MIGRATION_1_2).build())
}
/** Admission/close share a lock. Accepted writes drain; neither close nor Activity teardown waits. */
class RoomScheduleStore(private val database: ScheduleDatabase,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val closeDatabase: Boolean = true) : ScheduleStore {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Synchronized private fun <T> submit(callback: (Result<T>) -> Unit, operation: () -> T) {
        if (closed) return
        executor.execute { val result = runCatching(operation); handler.post { if (!closed) callback(result) } }
    }
    override fun load(callback: (Result<ScheduleState>) -> Unit) = submit(callback) {
        database.runInTransaction<ScheduleState> {
            ScheduleState(database.dao().events().map { CalendarEvent(it.id, it.title, it.start, it.end, it.note) },
                database.dao().entries().map { TimetableEntry(it.id, it.title, it.day, it.startMinute, it.endMinute, it.location) },
                sources = database.dao().sources(), imported = database.dao().imported().also {
                    check(it.size <= ImportLimits.STORED_OCCURRENCES) { "Stored occurrence limit exceeded" }
                })
        }
    }
    override fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit) = submit(callback) {
        database.dao().put(EventRow(event.id, event.title, event.start, event.end, event.note))
    }
    override fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit) = submit(callback) {
        database.dao().put(TimetableRow(entry.id, entry.title, entry.day, entry.startMinute, entry.endMinute, entry.location))
    }
    override fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit) = submit(callback) { database.dao().deleteEvent(id) }
    override fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit) = submit(callback) { database.dao().deleteEntry(id) }
    override fun commitImport(source: ImportSource, occurrences: List<ImportedClassOccurrence>, replace: Boolean,
        callback: (Result<ImportCommit>) -> Unit) = submit(callback) {
        database.runInTransaction<ImportCommit> {
            val dao = database.dao()
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
    override fun deleteImport(id: String, callback: (Result<Unit>) -> Unit) = submit(callback) {
        database.runInTransaction { database.dao().deleteSource(id) }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        executor.execute { if (closeDatabase) database.close() }; executor.shutdown()
    }
}
