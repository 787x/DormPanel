package com.dormpanel.app.schedule

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.room.*
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
}
/** Schema v1. Future versions must supply explicit preserving migrations; never destructive fallback. */
@Database(entities = [EventRow::class, TimetableRow::class], version = 1, exportSchema = false)
abstract class ScheduleDatabase : RoomDatabase() { abstract fun dao(): ScheduleDao }
object ScheduleStores {
    @Volatile var overrideFactory: ((Context) -> ScheduleStore)? = null
    fun create(context: Context): ScheduleStore = overrideFactory?.invoke(context) ?: RoomScheduleStore(
        Room.databaseBuilder(context.applicationContext, ScheduleDatabase::class.java, "schedule.db").build())
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
                database.dao().entries().map { TimetableEntry(it.id, it.title, it.day, it.startMinute, it.endMinute, it.location) })
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
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        executor.execute { if (closeDatabase) database.close() }; executor.shutdown()
    }
}
