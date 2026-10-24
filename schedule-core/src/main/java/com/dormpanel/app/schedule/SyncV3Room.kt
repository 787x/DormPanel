package com.dormpanel.app.schedule

import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import java.io.File
import java.util.Base64
import org.json.JSONObject

@Entity(tableName = "v3_records", indices = [Index(value = ["ns", "k"], unique = true), Index(value = ["ns", "seq"])])
data class V3Record(@PrimaryKey(autoGenerate = true) val seq: Long = 0, val ns: String, val k: String, val v: String, val sum: String)

/** Dedicated opt-in schema; ScheduleDatabase v4 and production factories remain unchanged.
 * PR55 can use this adapter without inventing a second course/receipt transaction boundary. */
@Database(entities = [EventRow::class, TimetableRow::class, ImportSource::class, ImportedClassOccurrence::class,
    DayAdjustment::class, ClassOverride::class, ScheduleSyncCheckpoint::class, V3Record::class], version = 1, exportSchema = false)
abstract class V3RoomDatabase : RoomDatabase() { abstract fun dao(): ScheduleDao }

private class V3RoomSql(private val database: V3RoomDatabase) : V3SqlPort {
    override fun execute(sql: String, args: List<String>): Int {
        val db = database.openHelper.writableDatabase
        if (sql.startsWith("PRAGMA")) return db.query(sql, args.toTypedArray()).use { it.moveToFirst(); 0 }
        if (sql.startsWith("UPDATE") || sql.startsWith("DELETE")) return db.compileStatement(sql).use { s -> args.forEachIndexed { n, v -> s.bindString(n + 1, v) }; s.executeUpdateDelete() }
        db.execSQL(sql, args.toTypedArray()); return 0
    }
    override fun rows(sql: String, args: List<String>): List<Map<String, String>> = database.openHelper.writableDatabase.query(sql, args.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.columnNames.indices.associate { c.columnNames[it] to c.getString(it) }) } }
    override fun <T> atomic(operation: () -> T): T = database.runInTransaction<T> { operation() }
    override fun close() = database.close()
}

class V3RoomAdapter(directory: File, databaseFactory: () -> V3RoomDatabase, writable: () -> Unit) : AutoCloseable {
    init { writable() }
    lateinit var database: V3RoomDatabase; private set
    val journal = V3Journal(directory, sqlFactory = { file ->
        database = databaseFactory(); val configured = requireNotNull(database.openHelper.databaseName)
        check(File(configured).isAbsolute && File(configured).canonicalFile == file.canonicalFile) { "v3 Room and metadata must share one database" }; V3RoomSql(database)
    }, mirrorLocal = { frame ->
        writable(); val state = requireNotNull(SyncV3Codec.decode(Base64.getDecoder().decode(frame.getString("local"))).state)
        val dao = database.dao(); dao.clearOverrides(); dao.clearDays(); dao.clearEvents(); dao.clearEntries(); dao.clearSources()
        state.events.forEach { dao.put(EventRow(it.id, it.title, it.start, it.end, it.note)) }
        state.entries.forEach { dao.put(TimetableRow(it.id, it.title, it.day, it.startMinute, it.endMinute, it.location)) }
        state.sources.forEach(dao::insertSource); dao.insertOccurrences(state.imported)
        state.dayAdjustments.forEach(dao::put); state.classOverrides.forEach(dao::put)
        // Catalog, exact capture and baseline stay in the same V3 control row; v2 checkpoints are untouched.
    })
    init { journal.meta.guard = writable }
    override fun close() = journal.close()
}
