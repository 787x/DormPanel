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
    @Query("SELECT * FROM schedule_sync_checkpoint ORDER BY id") fun syncRecords(): List<ScheduleSyncCheckpoint>
    @Query("SELECT * FROM schedule_sync_checkpoint WHERE id < 200 ORDER BY id") fun currentSyncRecords(): List<ScheduleSyncCheckpoint>
    @Query("SELECT MAX(id) FROM schedule_sync_checkpoint") fun maxSyncRecordId(): Int?
    @Query("SELECT * FROM schedule_sync_checkpoint WHERE id = 1") fun checkpoint(): ScheduleSyncCheckpoint?
    @Query("SELECT * FROM schedule_sync_checkpoint WHERE id = :id") fun syncRecord(id: Int): ScheduleSyncCheckpoint?
    @Query("SELECT EXISTS(SELECT 1 FROM schedule_sync_checkpoint WHERE id = :id)") fun hasSyncRecord(id: Int): Boolean
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
    private var production: RoomScheduleStore? = null
    private fun database(context:Context) = Room.databaseBuilder(context.applicationContext,ScheduleDatabase::class.java,"schedule.db")
        .addMigrations(ScheduleDatabase.MIGRATION_1_2,ScheduleDatabase.MIGRATION_2_3,ScheduleDatabase.MIGRATION_3_4).build()
    @Synchronized fun create(context: Context, profiles: TermScheduleProfileStore = TermScheduleProfileStore(context)): ScheduleStore {
        overrideFactory?.let { return it(context).also { store -> (store as? RoomScheduleStore)?.attachProfileStore(profiles) } }
        val store = production ?: RoomScheduleStore(
            database(context), profiles = profiles, onCommitted = { ScheduleSyncJobs.schedule(context.applicationContext) }, keepOpen = true,
            reopenDatabase={database(context)}).also { production = it }
        store.attachProfileStore(profiles); return store
    }
}
/** Admission/close share a lock. Accepted writes drain; neither close nor Activity teardown waits. */
class RoomScheduleStore(private var database: ScheduleDatabase,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val closeDatabase: Boolean = true, private var profiles: TermScheduleProfileStore? = null,
    private val afterSnapshotRows: (() -> Unit)? = null, private val onCommitted: (() -> Unit)? = null,
    private val keepOpen: Boolean = false, private val reopenDatabase:(()->ScheduleDatabase)?=null,
    private val v3Fault:((String)->Unit)?=null) : ScheduleStore, ScheduleSyncStore, ScheduleV2Store {
    init { profiles?.sharedStore = this }
    fun attachProfileStore(store: TermScheduleProfileStore) { profiles = store; store.sharedStore = this }
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private var activeV3: ActiveScheduleV3? = null
    private fun v3(): ActiveScheduleV3 = activeV3 ?: ActiveScheduleV3(database, ::mirrorV3).also { it.journal.meta.fault=v3Fault; activeV3 = it }
    private fun mirrorV3(state: ScheduleState) {
        val dao = database.dao()
        dao.clearOverrides(); dao.clearDays(); dao.clearSources(); dao.clearEvents(); dao.clearEntries()
        state.events.forEach { dao.put(EventRow(it.id, it.title, it.start, it.end, it.note)) }
        state.entries.forEach { dao.put(TimetableRow(it.id, it.title, it.day, it.startMinute, it.endMinute, it.location)) }
        state.sources.forEach(dao::insertSource); dao.insertOccurrences(state.imported)
        state.dayAdjustments.forEach(dao::put); state.classOverrides.forEach(dao::put)
        afterSnapshotRows?.invoke()
    }
    @Synchronized private fun <T> submit(callback: (Result<T>) -> Unit, operation: () -> T) {
        if (closed) return
        executor.execute { val result = runCatching(operation); handler.post { if (!closed) callback(result) } }
    }
    private fun <T> write(callback: (Result<T>) -> Unit, operation: () -> T) = submit(callback) {
        if (ActiveScheduleV3.active(database)) {
            var result: Result<T>? = null
            v3().engine.edit { before ->
                if (!v3().localWritesAllowed()) throw ScheduleDatasetWriteProtected()
                check(!database.dao().hasSyncRecord(200) && !database.dao().hasSyncRecord(203)) { "迁移保护禁止编辑" }
                result = Result.success(operation())
                readRows().copy(profiles = before.profiles, customProfileKeys = before.customProfileKeys)
            }
            onCommitted?.invoke(); return@submit requireNotNull(result).getOrThrow()
        }
        database.runInTransaction<T> {
            requireNoDatasetMigration()
            operation()
        }.also { onCommitted?.invoke() }
    }
    private fun readRows() = ScheduleState(database.dao().events().map { CalendarEvent(it.id, it.title, it.start, it.end, it.note) },
        database.dao().entries().map { TimetableEntry(it.id, it.title, it.day, it.startMinute, it.endMinute, it.location) },
        sources = database.dao().sources(), imported = database.dao().imported(),
        dayAdjustments = database.dao().dayAdjustments(), classOverrides = database.dao().classOverrides())
    private fun readState(): ScheduleState {
        if (ActiveScheduleV3.active(database)) return v3().state()
        return database.runInTransaction<ScheduleState> {
            check(database.dao().dayAdjustmentCount() <= 1000 && database.dao().classOverrideCount() <= 5000 &&
                database.dao().classOverrideTextCharacters() <= 2_000_000L) { "Stored override limit exceeded" }
            val state = ScheduleState(database.dao().events().map { CalendarEvent(it.id, it.title, it.start, it.end, it.note) },
                database.dao().entries().map { TimetableEntry(it.id, it.title, it.day, it.startMinute, it.endMinute, it.location) },
                sources = database.dao().sources(), imported = database.dao().imported().also {
                    check(it.size <= ImportLimits.STORED_OCCURRENCES) { "Stored occurrence limit exceeded" }
                }, dayAdjustments = database.dao().dayAdjustments().also { check(it.size <= 1000) },
                classOverrides = database.dao().classOverrides().also { check(it.size <= 5000) })
            val v2 = v2Commit(21) ?: v2Commit(23)
            val saved = v2?.snapshot() ?: database.dao().checkpoint()?.let { ScheduleSnapshotCodec.decode(it.payload) }
            val catalog = database.dao().syncRecord(2)?.let { ScheduleSnapshotCodec.decode(it.payload).state } ?: saved?.state
            if (saved == null) state else {
                val current = state.copy(profiles = catalog!!.profiles, customProfileKeys = catalog.customProfileKeys)
                val pending = v2 != null && (database.dao().hasSyncRecord(23) || !ScheduleMerge.same(saved.state, current))
                current.copy(sync = SnapshotIdentity(saved.datasetId, saved.revision, pending))
            }
        }
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
            requireNoDatasetMigration()
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
        if (ActiveScheduleV3.active(database)) return v3().state().copy(sync = null)
        val state = readState().copy(sync = null)
        return if (database.dao().checkpoint() != null || (v2Commit(21) != null || v2Commit(23) != null)) state else {
            val catalog = requireNotNull(profiles).preferenceCatalog()
            state.copy(profiles = catalog.first, customProfileKeys = catalog.second)
        }
    }
    private fun <T> blocking(operation: () -> T): T {
        check(!closed) { "Schedule store closed" }
        try { return executor.submit<T> {
            if (ActiveScheduleV3.active(database) && activeV3 == null) v3()
            database.runInTransaction<T> { operation() }
        }.get() }
        catch (error: java.util.concurrent.ExecutionException) { throw (error.cause ?: error) }
    }
    private fun <T> workerCall(operation: () -> T): T {
        check(!closed) { "Schedule store closed" }
        try { return executor.submit<T> { operation() }.get() }
        catch (error: java.util.concurrent.ExecutionException) { throw (error.cause ?: error) }
    }
    fun isV3(): Boolean = workerCall { ActiveScheduleV3.active(database) }
    fun businessPaused(): Boolean = workerCall {
        database.dao().hasSyncRecord(200) || database.dao().hasSyncRecord(203) ||
            ActiveScheduleV3.active(database) && !runCatching { v3().localWritesAllowed() }.getOrDefault(false)
    }
    fun businessBlockReason():String? = workerCall {
        when {
            ActiveScheduleV3.active(database) && runCatching { v3().journal.meta.assertWritable() }.isFailure -> "本地事务确认中断，已停止写入；请使用“重开活动 Store”恢复，不要清库或重新绑定。"
            database.dao().hasSyncRecord(200) || database.dao().hasSyncRecord(203) || ActiveScheduleV3.active(database) && !v3().localWritesAllowed() -> ScheduleDatasetWriteProtected().message
            else -> null
        }
    }
    /** Called under the host network gate, after UI admission is paused. Same Store object
     * remains authoritative for every existing Activity and JobService reference. */
    fun reopenV3Store() = workerCall {
        check(ActiveScheduleV3.active(database)); val factory=requireNotNull(reopenDatabase) { "此存储请通过重启进程重新打开；数据与绑定保留" }
        activeV3?.close(); database.close()
        activeV3=null; database=factory(); v3().journal.meta.assertWritable(); v3().state()
        Unit
    }
    fun previewV3Migration(remote: CommitStore, sourceFolder: String): ScheduleV3MigrationPreview {
        v2RequireSyncAllowed()
        val expectedKey = blocking { database.dao().syncRecord(102)?.payload?.toString(Charsets.US_ASCII) }
        check(expectedKey == null || expectedKey == remote.bindingKey) { "源访问身份变化" }
        return ScheduleV3Migration.inspect(v2Frame(), v2Witness(), remote, sourceFolder)
    }
    private fun requireMigrationCapture(preview:ScheduleV3MigrationPreview) {
        requireNoDatasetMigration()
        check(SyncV3Codec.stateBytes(fullState()).contentEquals(SyncV3Codec.stateBytes(preview.frame.local)) && v2Commit(21)==preview.frame.baseline && v2Ids(22)==preview.frame.frontier &&
            !database.dao().hasSyncRecord(23) && v2Plan()==null && v2Hashes(97).isEmpty() && v2Problems().isEmpty()) { "本地状态已变化，迁移预览过期" }
    }
    private fun requireEmptyUnboundJoin():ScheduleState {
        requireNoDatasetMigration()
        check(v2Commit(21)==null && v2Commit(23)==null && decoded(1)==null && decoded(4)==null) { "已有绑定或待发布，请使用迁移目标加入入口" }
        return fullState().also { s ->check(s.events.isEmpty() && s.entries.isEmpty() && s.sources.isEmpty() && s.imported.isEmpty() &&
            s.dayAdjustments.isEmpty() && s.classOverrides.isEmpty() && s.customProfileKeys.isEmpty()) { "本地非空，禁止覆盖加入；本地修改保留" } }
    }
    fun previewUnboundV3Join(account:WebDavAccount,folder:String,descriptor:ByteArray):UnboundV3JoinPreview? {
        val original=workerCall { requireEmptyUnboundJoin() }; val budget=V3Budget(); val remote=V3WebDavStore(account,folder,budget)
        val target=inspectUnboundTarget(remote,budget,descriptor,folder,account.username) ?: return null
        return UnboundV3JoinPreview(original,target)
    }
    private fun inspectUnboundTarget(remote:V3ObjectStore,budget:V3Budget,descriptor:ByteArray,folder:String,user:String):VerifiedV3Snapshot? {
        val key=digest((folder+"\n"+user+"\n"+SyncV3Codec.hash(descriptor)+"\nLOCAL").toByteArray(Charsets.UTF_8))
        val (directory,writer)=workerCall {
            requireEmptyUnboundJoin()
            val prior=database.dao().syncRecord(207)?.payload?.let { org.json.JSONObject(it.toString(Charsets.UTF_8)) }
            val stage=if(prior?.optString("key")==key) prior.getString("stageId") else java.util.UUID.randomUUID().toString().also { id ->
                database.dao().checkpoint(ScheduleSyncCheckpoint(207,org.json.JSONObject().put("key",key).put("stageId",id).toString().toByteArray(Charsets.UTF_8)))
            }
            ScheduleCommitCodec.uuid(stage)
            java.io.File(requireNotNull(database.openHelper.writableDatabase.path)+".v3-inspect-$stage") to v2Writer()
        }
        return V3DatasetInspection.inspect(directory,writer,remote,budget,descriptor,folder)
    }
    fun joinVerifiedV3(preview:UnboundV3JoinPreview,account:WebDavAccount) {
        val budget=V3Budget();val remote=V3WebDavStore(account,preview.target.folder,budget)
        val target=inspectUnboundTarget(remote,budget,preview.target.descriptor,preview.target.folder,account.username)
        check(target!=null && target.snapshot.contentEquals(preview.target.snapshot)) { "目标预检尚未完成或已变化，请重新预览同一目标" }
        workerCall { v3().prepareVerifiedJoin(preview,ActiveScheduleV3.endpointKey(remote,account)) {
            check(SyncV3Codec.stateBytes(requireEmptyUnboundJoin()).contentEquals(SyncV3Codec.stateBytes(preview.original))) { "本地预览后变化，禁止覆盖加入" }
        } }
    }
    private fun inspectExistingTarget(source:ScheduleV3MigrationPreview,remote:V3ObjectStore,budget:V3Budget,descriptor:ByteArray,folder:String):VerifiedV3Snapshot? {
        val key=digest((folder+"\n"+SyncV3Codec.hash(descriptor)+"\n"+source.frame.baseline!!.datasetId+"\n"+source.sourceKey).toByteArray(Charsets.UTF_8))
        val stageId=workerCall {
            requireNoDatasetMigration()
            val prior=database.dao().syncRecord(207)?.payload?.let { org.json.JSONObject(it.toString(Charsets.UTF_8)) }
            if(prior?.optString("key")==key) prior.getString("stageId") else java.util.UUID.randomUUID().toString().also { id ->
                database.dao().checkpoint(ScheduleSyncCheckpoint(207,org.json.JSONObject().put("key",key).put("stageId",id).toString().toByteArray(Charsets.UTF_8)))
            }
        }
        ScheduleCommitCodec.uuid(stageId)
        val directory=workerCall { java.io.File(requireNotNull(database.openHelper.writableDatabase.path)+".v3-inspect-$stageId") }
        return V3DatasetInspection.inspect(directory,source.frame.writerId,remote,budget,descriptor,folder)
    }
    fun previewExistingV3Migration(sourceRemote:CommitStore,sourceFolder:String,account:WebDavAccount,targetFolder:String,descriptor:ByteArray):ExistingV3MigrationPreview? {
        val source=previewV3Migration(sourceRemote,sourceFolder); val budget=V3Budget()
        val target=inspectExistingTarget(source,V3WebDavStore(account,targetFolder,budget),budget,descriptor,targetFolder) ?: return null
        check(SyncV3Codec.stateBytes(source.merged).contentEquals(SyncV3Codec.stateBytes(requireNotNull(target.value.state)))) { "源与目标完整 RAW 不等价，无法证明共同基线；保留两端，不覆盖加入" }
        return ExistingV3MigrationPreview(source,target)
    }
    fun prepareExistingV3Migration(preview:ExistingV3MigrationPreview,sourceRemote:CommitStore,account:WebDavAccount) {
        val source=previewV3Migration(sourceRemote,preview.source.sourceFolder); ScheduleV3Migration.unchanged(preview.source,source)
        val budget=V3Budget(); val remote=V3WebDavStore(account,preview.target.folder,budget)
        val target=inspectExistingTarget(source,remote,budget,preview.target.descriptor,preview.target.folder)
        check(target!=null && target.snapshot.contentEquals(preview.target.snapshot)) { "目标预检尚未完成或预览后发生变化；请选择同一身份继续检查" }
        workerCall { v3().prepareExisting(preview,ActiveScheduleV3.endpointKey(remote,account)) { requireMigrationCapture(preview.source) } }
    }
    internal fun prepareExistingV3Migration(preview:ExistingV3MigrationPreview,sourceRemote:CommitStore,targetRemote:V3ObjectStore,budget:V3Budget) {
        val source=previewV3Migration(sourceRemote,preview.source.sourceFolder); ScheduleV3Migration.unchanged(preview.source,source)
        val target=inspectExistingTarget(source,targetRemote,budget,preview.target.descriptor,preview.target.folder)
        check(target!=null && target.snapshot.contentEquals(preview.target.snapshot)) { "目标预检未完成或已变化" }
        workerCall { v3().prepareExisting(preview) { requireMigrationCapture(preview.source) } }
    }
    fun prepareV3Migration(preview: ScheduleV3MigrationPreview, remote: CommitStore, targetFolder: String) {
        val current = previewV3Migration(remote, preview.sourceFolder)
        ScheduleV3Migration.unchanged(preview, current)
        workerCall {
            requireNoDatasetMigration()
            v3().prepare(preview.merged, targetFolder, migration = preview) {
                requireNoDatasetMigration()
                check(SyncV3Codec.stateBytes(fullState()).contentEquals(SyncV3Codec.stateBytes(preview.frame.local)) && v2Commit(21) == preview.frame.baseline && v2Ids(22) == preview.frame.frontier &&
                    !database.dao().hasSyncRecord(23) && v2Plan() == null && v2Hashes(97).isEmpty() && v2Problems().isEmpty()) { "本地状态已变化，迁移预览过期" }
            }
        }
    }
    fun cancelUnpublishedV3Migration() = workerCall {
        check(ActiveScheduleV3.active(database))
        val runtime = v3(); val mode = runtime.binding()
        check(mode.optString("phase") == "MIGRATING" && !runtime.journal.snapshot().has("binding")) { "目标已有候选或已接受 v3 编辑；禁止简单切回 v2，请保护两端并恢复迁移" }
        val original = ScheduleSnapshotCodec.decode(requireNotNull(database.dao().syncRecord(ActiveScheduleV3.ORIGINAL_LOCAL)).payload).state
        runtime.engine.edit {
            runtime.journal.meta.put("migrationRecovery", mode.getString("migrationId"), mode.put("phase", "CANCELLED"))
            database.dao().deleteSyncRecord(ActiveScheduleV3.MODE)
            original
        }
    }
    fun auditV3MigrationSource(account: WebDavAccount): String {
        val runtime = workerCall { check(ActiveScheduleV3.active(database)); v3() }
        val mode = runtime.binding(); check(mode.has("migrationId")) { "本机没有 v2→v3 迁移记录" }
        val proof = ScheduleV3MigrationPreview.restore(runtime.migrationEvidence())
        val graph = ScheduleCommitGraph(WebDavCommitStore(account, proof.sourceFolder), proof.frame.baseline!!.datasetId).load(proof.hashes.keys.toList())
        check(graph.deferred.isEmpty()) { "旧源存在不可验证路径，不能声明未变化" }
        val hashes = graph.commits.mapValues { digest(ScheduleCommitCodec.encode(it.value)) }
        check(proof.hashes.all { (id, hash) -> hashes[id] == hash }) { "旧源已知提交内容变化；保留两端并停止自动处理" }
        val unchanged = proof.hashes == hashes && proof.tips == graph.tips
        runtime.journal.localTransaction {
            val next = runtime.binding(); next.put("sourceAudit", org.json.JSONObject().put("checkedAt", System.currentTimeMillis()).put("status", if (unchanged) "OBSERVED_UNCHANGED" else "CROSS_PROTOCOL_FORK")
                .put("hashes", org.json.JSONObject(hashes)).put("tips", org.json.JSONArray(graph.tips)))
            database.dao().checkpoint(ScheduleSyncCheckpoint(ActiveScheduleV3.MODE, next.toString().toByteArray(Charsets.UTF_8)))
        }
        return if (unchanged) "本次观察到的 v2 源未变化；不能证明未知 Writer 已停写" else "发现跨协议分叉；v2 新历史仅保留证据，未当作 v3 LATE 整合。请保留两端数据并人工恢复。"
    }
    fun v3Information(): org.json.JSONObject? = workerCall {
        if (!ActiveScheduleV3.active(database)) null else v3().journal.snapshot()
    }
    fun v3Control(): org.json.JSONObject? = workerCall {
        if(!ActiveScheduleV3.active(database)) null else runCatching {
            v3().journal.meta.assertWritable();v3().journal.controlSummary().put("mode",v3().binding().optString("phase","ACTIVE"))
        }.getOrElse { org.json.JSONObject().put("mode","RECOVERY_REQUIRED") }
    }
    fun prepareV3(folder: String, descriptor: ByteArray? = null) = workerCall {
        requireNoDatasetMigration()
        check(v2Commit(21) == null && v2Commit(23) == null && decoded(1) == null && decoded(4) == null) { "已有绑定或待发布；请使用 v2→v3 迁移" }
        val original = fullState()
        if (descriptor != null) check(original.events.isEmpty() && original.entries.isEmpty() && original.sources.isEmpty() && original.imported.isEmpty() &&
            original.classOverrides.isEmpty() && original.dayAdjustments.isEmpty() && original.customProfileKeys.isEmpty()) { "本地非空，禁止覆盖加入；请先保留现有数据" }
        v3().prepare(if (descriptor == null) original else ScheduleState(), folder, descriptor)
    }
    fun synchronizeV3(account: WebDavAccount): String {
        val runtime = workerCall { check(ActiveScheduleV3.active(database)); v3() }
        return runtime.synchronize(account)
    }
    fun v3Account(settings:WebDavSettings):WebDavAccount = workerCall { v3().endpointAccount(settings) { requireNotNull(settings.account()) { "请配置 WebDAV 帐号" } } }
    private fun endpointSummary(job:org.json.JSONObject)=org.json.JSONObject().also { out ->
        listOf("token","status","binding","after","ceiling","descriptorExact","networkAndAuthenticationVerified","knownHistoryComplete","failure","materials","expiresAt","protectedCacheBytes").forEach { if(job.has(it))out.put(it,job.get(it)) }
        out.put("oldEndpointNewChanges","UNPROVEN").put("completeDirectoryView",false).put("otherDevicesReceived","UNKNOWN")
    }
    fun previewV3Endpoint(account:WebDavAccount,folder:String,settings:WebDavSettings):org.json.JSONObject {
        val job=workerCall { requireV3EndpointAllowed();v3().endpointStart(account,folder,settings.sealEndpoint(account)) }
        return continueV3Endpoint(job.getString("token"),settings)
    }
    fun continueV3Endpoint(token:String,settings:WebDavSettings):org.json.JSONObject {
        val runtime=workerCall { requireV3EndpointAllowed();v3() };val job=runtime.endpointJob(token)
        if(job.optString("status") in listOf("COMMITTED","CANCELLED","STALE","EXPIRED"))return endpointSummary(job)
        return endpointSummary(runtime.endpointContinue(token,settings.openEndpoint(job.getString("endpointAccount"))))
    }
    fun confirmV3Endpoint(token:String,settings:WebDavSettings):org.json.JSONObject {
        val runtime=workerCall { requireV3EndpointAllowed();v3() };val job=runtime.endpointJob(token)
        if(job.optString("status")=="COMMITTED")return endpointSummary(job)
        check(job.optString("status")=="READY") { "预检未就绪、已结束或过期" }
        return endpointSummary(runtime.endpointConfirm(token,settings.openEndpoint(job.getString("endpointAccount"))))
    }
    fun cancelV3Endpoint(token:String):org.json.JSONObject = workerCall { endpointSummary(v3().endpointCancel(token)) }
    private fun requireV3EndpointAllowed() { check(ActiveScheduleV3.active(database) && !database.dao().hasSyncRecord(200) && !database.dao().hasSyncRecord(203)) { "需要活动 v3 且无迁移恢复保护" } }
    fun latestV3Endpoint():org.json.JSONObject? = workerCall {
        if(!ActiveScheduleV3.active(database))null else {requireV3EndpointAllowed();v3().endpointLatest()?.let(::endpointSummary)}
    }
    fun v3EndpointFolder():String = workerCall { requireV3EndpointAllowed();v3().endpointFolder() }
    internal fun synchronizeV3(remote: V3ObjectStore, publication: V3PublicationStore, budget: V3Budget, source: CommitStore? = null): String {
        val runtime = workerCall { check(ActiveScheduleV3.active(database)); v3() }
        val proof = if(runtime.binding().has("migrationId")) ScheduleV3MigrationPreview.restore(runtime.migrationEvidence()) else null
        return runtime.synchronize(remote, publication, budget, if (source == null || proof == null) null else ({
            ScheduleV3Migration.unchanged(proof, ScheduleV3Migration.inspect(proof.frame, proof.hashes, source, proof.sourceFolder, proof.transactionId))
        }))
    }
    fun resolveV3(token: String, choice: ScheduleMerge.Choice) = workerCall { check(ActiveScheduleV3.active(database)); v3().engine.resolve(token, choice) }
    override fun syncFrame(): ScheduleSyncFrame = blocking {
        ScheduleSyncFrame(decoded(1), fullState(), decoded(4), decoded(3), decoded(5)?.state)
    }
    internal fun sharedCatalog(): Pair<Map<String, TermScheduleProfile>, Set<String>>? = blocking {
        if (ActiveScheduleV3.active(database)) return@blocking v3().engine.local().let { it.profiles to it.customProfileKeys }
        val catalog = decoded(2) ?: ((v2Commit(21) ?: v2Commit(23))?.snapshot() ?: decoded(1))?.also {
            database.dao().checkpoint(ScheduleSyncCheckpoint(2, catalogBytes(it, it.state)))
        }
        catalog?.state?.let { it.profiles to it.customProfileKeys }
    }
    internal fun changeProfile(profile: TermScheduleProfile?, key: String): Boolean {
        if (isV3()) return workerCall {
            if (!v3().localWritesAllowed()) throw ScheduleDatasetWriteProtected()
            v3().engine.edit { before ->
                val catalog = before.profiles.toMutableMap()
                if (profile != null) catalog[key] = profile else BuiltInProfiles.builtIn(key)?.let { catalog[key] = it } ?: catalog.remove(key)
                check(before.sources.none { it.resolvedTermKey() == key } || key in catalog) { "学期仍有关联来源；请先删除来源" }
                before.copy(profiles = catalog, customProfileKeys = if (profile == null) before.customProfileKeys - key else before.customProfileKeys + key)
            }; onCommitted?.invoke(); true
        }
        return blocking {
        requireNoDatasetMigration()
        val base = (v2Commit(21) ?: v2Commit(23))?.snapshot() ?: decoded(1)
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
    }
    override fun establishBaseline(bytes: ByteArray) = blocking {
        requireNoDatasetMigration()
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
        requireNoDatasetMigration()
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
    override fun integrateRemote(bytes: ByteArray): List<ScheduleMerge.Conflict> = blocking { requireNoDatasetMigration(); integrate(bytes) }
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
        requireNoDatasetMigration()
        val remote = requireNotNull(decoded(3)) { "No saved conflict" }
        val pending = decoded(4)
        val ancestor = if (pending?.revision == remote.revision) requireNotNull(decoded(5)) else requireNotNull(decoded(1))
        val result = ScheduleMerge.merge(ancestor.state, fullState(), remote.state, choice)
        replaceState(result.state, remote)
        database.dao().checkpoint(ScheduleSyncCheckpoint(payload = ScheduleSnapshotCodec.encode(remote)))
        database.dao().deleteSyncRecord(3)
        if (pending?.revision == remote.revision) { database.dao().deleteSyncRecord(4); database.dao().deleteSyncRecord(5) }
    }
    // v2 records have independent meanings; never rewrite v1 checkpoint IDs 1,3..7.
    // 20 writer,21 verified virtual baseline,22 frontier,23 pending,24 capture,
    // 25/26 conflict bases,27 conflict frontier,30..93 conflict branches,29 PUT attempted,
    // 96 verified ID/hash witness,97 own abandoned paths (explicit quarantine).
    private fun v2Commit(id: Int) = database.dao().syncRecord(id)?.payload?.let(ScheduleCommitCodec::decode)
    // 200 active journal, 202 observed paths, 203 inspection pause; >=1000 immutable recovery blobs/manifests.
    private fun datasetMigration() = database.dao().syncRecord(200)?.payload?.let(ScheduleDatasetMigration::decode)
    private fun requireNoDatasetMigration() { if (database.dao().hasSyncRecord(200) || database.dao().hasSyncRecord(203) || ActiveScheduleV3.active(database)) throw ScheduleDatasetWriteProtected() }
    override fun v2DatasetPaused() = blocking { database.dao().hasSyncRecord(200) || database.dao().hasSyncRecord(203) }
    override fun v2DatasetResult() = blocking { database.dao().syncRecord(204)?.payload?.toString(Charsets.UTF_8) }
    override fun v2RequireSyncAllowed() = blocking { requireNoDatasetMigration() }
    override fun v2DatasetInspection(): List<String>? = blocking {
        database.dao().syncRecord(203)?.payload?.let { bytes -> org.json.JSONArray(String(bytes, Charsets.UTF_8)).let { a -> (0 until a.length()).map(a::getString) } }
    }
    override fun v2BeginDatasetInspection(oldFolder: String, newFolder: String, datasetId: String) = blocking {
        check(datasetMigration() == null); ScheduleCommitCodec.uuid(datasetId)
        check(oldFolder != newFolder)
        val config = org.json.JSONArray(listOf(oldFolder, newFolder, datasetId)).toString().toByteArray(Charsets.UTF_8)
        database.dao().syncRecord(203)?.let { check(it.payload.contentEquals(config)) { "已有准备操作，先恢复或取消" }; return@blocking }
        val baseline = requireNotNull(v2Commit(21)); check(ScheduleMerge.same(fullState(), baseline.state)) { "有未发布修改" }
        check(v2Commit(23) == null && v2Plan() == null && !database.dao().hasSyncRecord(29) && v2Hashes(97).isEmpty() && v2Problems().isEmpty()) { "有待恢复同步状态" }
        check(listOf(3, 4, 5, 6, 7).none { database.dao().hasSyncRecord(it) }) { "旧 v1 恢复尚未完成" }
        database.dao().deleteSyncRecord(202); v2Put(203, config)
    }
    override fun v2CancelDatasetInspection() = blocking {
        check(datasetMigration() == null)
        val key = database.dao().syncRecord(102)?.payload?.toString(Charsets.US_ASCII)
        val observations = database.dao().syncRecord(202)?.payload?.let { org.json.JSONObject(String(it, Charsets.UTF_8)).optJSONArray(key.orEmpty()) }
        val ids = (v2Problems() + (observations?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty())).sorted()
        check(ids.size <= 256); v2Put(103, ids.joinToString("\n").toByteArray(Charsets.US_ASCII))
        database.dao().deleteSyncRecord(203)
    }
    override fun v2Migration() = blocking { datasetMigration() }
    override fun v2Witness() = blocking { v2Hashes(96) }
    override fun v2MigrationPaths(key: String, observed: List<String>) = blocking {
        check(database.dao().hasSyncRecord(200) || database.dao().hasSyncRecord(203))
        val all = database.dao().syncRecord(202)?.payload?.let { org.json.JSONObject(String(it, Charsets.UTF_8)) } ?: org.json.JSONObject()
        val previous = all.optJSONArray(key)?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
        val paths = (previous + observed).distinct().sorted(); check(paths.size <= 256); paths.forEach(ScheduleCommitCodec::uuid)
        all.put(key, org.json.JSONArray(paths)); v2Put(202, all.toString().toByteArray(Charsets.UTF_8)); paths
    }
    override fun v2PrepareMigration(journal: ScheduleDatasetMigration) = blocking {
        check(datasetMigration() == null)
        database.dao().syncRecord(203)?.let { check(it.payload.contentEquals(org.json.JSONArray(listOf(journal.oldFolder, journal.newFolder, journal.target.head.datasetId)).toString().toByteArray(Charsets.UTF_8))) }
        val baseline = requireNotNull(v2Commit(21)); val local = fullState()
        check(journal.phase == "prepared" && journal.writerId == v2Writer() && journal.source.head.datasetId == baseline.datasetId)
        check(v2Commit(23) == null && v2Plan() == null && !database.dao().hasSyncRecord(29) && v2Hashes(97).isEmpty() && v2Problems().isEmpty())
        check(listOf(3, 4, 5, 6, 7).none { database.dao().hasSyncRecord(it) }) { "旧 v1 恢复尚未完成" }
        check(ScheduleMerge.same(local, baseline.state) && ScheduleMerge.same(local, journal.source.snapshot.state) && ScheduleMerge.same(local, journal.target.snapshot.state))
        check(journal.source.witness == v2Hashes(96) && v2Ids(22).all { journal.source.witness.containsKey(it) })
        val root = ScheduleCommitCodec.decode(requireNotNull(journal.target.commitBytes))
        check(root.parents.isEmpty() && root.datasetId != baseline.datasetId && journal.target.witness == mapOf(root.commitId to digest(journal.target.commitBytes)))
        check(journal.source.bindingKey == database.dao().syncRecord(102)?.payload?.toString(Charsets.US_ASCII) && journal.target.bindingKey != journal.source.bindingKey)
        var archiveId = maxOf(1000, (database.dao().maxSyncRecordId() ?: 0) + 1)
        fun archive(bytes: ByteArray): org.json.JSONObject {
            val id = archiveId++; v2Put(id, bytes)
            return org.json.JSONObject().put("recordId", id).put("sha256", digest(bytes))
        }
        val records = org.json.JSONObject()
        database.dao().currentSyncRecords().forEach { records.put(it.id.toString(), archive(it.payload)) }
        // Individual immutable blobs avoid base64-copying all checkpoints into one giant JSON value.
        val manifest = org.json.JSONObject().put("format", "DormPanel-dataset-recovery-1").put("id", journal.id)
            .put("writerId", journal.writerId).put("oldFolder", journal.oldFolder).put("newFolder", journal.newFolder)
            .put("sourceDatasetId", journal.source.head.datasetId).put("targetDatasetId", root.datasetId)
            .put("local", archive(ScheduleCommitCodec.encode(baseline.copy(state = local))))
            .put("targetRoot", archive(requireNotNull(journal.target.commitBytes))).put("records", records)
        val body = manifest.toString().toByteArray(Charsets.UTF_8)
        v2Put(archiveId, org.json.JSONObject().put("sha256", digest(body)).put("body", java.util.Base64.getEncoder().encodeToString(body)).toString().toByteArray(Charsets.UTF_8))
        database.dao().deleteSyncRecord(202); v2Put(200, journal.bytes()); database.dao().deleteSyncRecord(203)
    }
    override fun v2CommitMigration(id: String) = blocking {
        val j = requireNotNull(datasetMigration()); check(j.id == id)
        if (j.phase == "committed") return@blocking
        check(j.phase == "prepared" && v2Writer() == j.writerId && v2Hashes(96) == j.source.witness)
        val root = ScheduleCommitCodec.decode(requireNotNull(j.target.commitBytes))
        check(ScheduleMerge.same(fullState(), root.state) && v2Commit(21)?.datasetId == j.source.head.datasetId)
        // Course rows remain in place; only independent dataset checkpoint and catalog identity change.
        v2Put(21, j.target.commitBytes); v2Frontier(22, listOf(root.commitId))
        v2Put(96, "${root.commitId} ${digest(j.target.commitBytes)}".toByteArray(Charsets.US_ASCII))
        v2Put(102, requireNotNull(j.target.bindingKey).toByteArray(Charsets.US_ASCII))
        v2Put(2, catalogBytes(root.snapshot(), root.state)); v2Put(200, j.copy(phase = "committed").bytes())
    }
    override fun v2FinishMigration(id: String, cancel: Boolean) = blocking {
        val j = requireNotNull(datasetMigration()); check(j.id == id)
        check(if (cancel) j.phase == "prepared" else j.phase == "committed") { "切换后禁止反向覆盖；请保护新数据再处理" }
        val summary = org.json.JSONObject().put("id", j.id).put("phase", if (cancel) "cancelled" else "completed-local-verified")
            .put("sourceDatasetId", j.source.head.datasetId).put("targetDatasetId", j.target.head.datasetId).put("writerId", j.writerId)
            .put("origin", "Android 本机实际验证结果").put("checkedAt", java.time.Instant.now().toString())
            .put("oldFolder", j.oldFolder).put("newFolder", j.newFolder).put("sourceCount", j.source.witness.size)
            .put("targetRoot", j.target.head.revision).put("targetSha256", digest(requireNotNull(j.target.commitBytes)))
        v2Put(maxOf(1000, (database.dao().maxSyncRecordId() ?: 0) + 1), summary.toString().toByteArray(Charsets.UTF_8))
        v2Put(204, summary.toString().toByteArray(Charsets.UTF_8))
        database.dao().deleteSyncRecord(200)
    }
    private fun v2Put(id: Int, bytes: ByteArray) = database.dao().checkpoint(ScheduleSyncCheckpoint(id, bytes))
    private fun v2Ids(id: Int) = database.dao().syncRecord(id)?.payload?.toString(Charsets.US_ASCII)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty().also { ids ->
        require(ids.size <= 64 && ids.distinct().size == ids.size); ids.forEach(ScheduleCommitCodec::uuid)
    }
    private fun v2Frontier(id: Int, ids: List<String>) = v2Put(id, ids.sorted().joinToString("\n").toByteArray(Charsets.US_ASCII))
    private fun v2Writer(): String {
        database.dao().syncRecord(20)?.let { return it.payload.toString(Charsets.US_ASCII).also(ScheduleCommitCodec::uuid) }
        return java.util.UUID.randomUUID().toString().also { v2Put(20, it.toByteArray(Charsets.US_ASCII)) }
    }
    private fun v2Plan(): ScheduleV2Plan? = v2Commit(25)?.let { base ->
        val ids = v2Ids(27)
        ScheduleV2Plan(base, requireNotNull(v2Commit(26)), ids.indices.map { requireNotNull(v2Commit(30 + it)).also { c -> check(c.commitId == ids[it]) } })
    }
    override fun v2Frame(): ScheduleV2Frame = blocking {
        ScheduleV2Frame(fullState(), v2Commit(21), v2Ids(22), v2Writer(), database.dao().syncRecord(23)?.payload, database.dao().syncRecord(29) != null, v2Plan(), v2Hashes(97), v2Problems())
    }
    override fun archiveMigrationRecovery(identity: ScheduleSnapshot) = blocking {
        if (database.dao().syncRecord(100) != null) return@blocking
        val bytes = ScheduleSnapshotCodec.encode(identity.copy(state = fullState()))
        val ids = (1..7).filter { database.dao().syncRecord(it) != null }
        ids.forEach { id -> v2Put(110 + id, requireNotNull(database.dao().syncRecord(id)).payload.copyOf()) }
        v2Put(101, ids.joinToString(",").toByteArray(Charsets.US_ASCII))
        v2Put(100, bytes)
    }
    override fun v2CheckTarget(identity: String) = blocking {
        requireNoDatasetMigration()
        require(identity.matches(Regex("[0-9a-f]{64}")))
        val old = database.dao().syncRecord(102)?.payload?.toString(Charsets.US_ASCII)
        if (v2Commit(21) != null || v2Commit(23) != null) check(old == null || old == identity) {
            "服务器、目录或账号身份已改变；保留待确认提交，请恢复原配置"
        }
        if (old != identity) v2Put(102, identity.toByteArray(Charsets.US_ASCII))
    }
    private fun v2Remember(id: String, bytes: ByteArray) {
        val known = v2Hashes(96); val hash = digest(bytes)
        check(known[id] == null || known[id] == hash) { "已确认提交内容改变" }
        val updated = known + (id to hash)
        check(updated.size <= 256) { "已确认历史数量超限；保留本地修改和回执" }
        v2Put(96, updated.toSortedMap().map { "${it.key} ${it.value}" }.joinToString("\n").toByteArray(Charsets.US_ASCII))
    }
    private fun v2Problems(): Set<String> = database.dao().syncRecord(103)?.payload?.toString(Charsets.US_ASCII)?.split('\n')
        ?.filter { it.isNotEmpty() }?.toSet().orEmpty().also { ids -> require(ids.size <= 256); ids.forEach(ScheduleCommitCodec::uuid) }
    override fun v2Unverifiable(ids: Set<String>) = blocking {
        check(!database.dao().hasSyncRecord(200)) { "换库预览已保存，禁止修改原观察证据" }
        require(ids.size <= 256) { "未验证路径数量超限；保留本地修改" }; ids.forEach(ScheduleCommitCodec::uuid)
        if (v2Problems() != ids) v2Put(103, ids.sorted().joinToString("\n").toByteArray(Charsets.US_ASCII))
    }
    override fun v2Migrate(baseline: ScheduleSnapshot) = blocking {
        requireNoDatasetMigration()
        check(v2Commit(21) == null && v2Commit(23) == null && decoded(1) == baseline && decoded(3) == null && decoded(4) == null)
        val c = ScheduleCommit(baseline.datasetId, baseline.revision, v2Writer(), emptyList(), baseline.state)
        v2Put(21, ScheduleCommitCodec.encode(c)); v2Frontier(22, emptyList())
    }
    override fun v2Bind(remote: SharedScheduleRemote) = blocking {
        requireNoDatasetMigration()
        val bytes = requireNotNull(remote.commitBytes)
        val root = ScheduleCommitCodec.decode(bytes)
        check(root.datasetId == remote.head.datasetId && root.commitId == remote.head.revision)
        check(remote.witness[root.commitId] == digest(bytes) && remote.witness.size <= 256)
        remote.witness.forEach { (id, hash) -> ScheduleCommitCodec.uuid(id); require(hash.matches(Regex("[0-9a-f]{64}"))) }
        require(remote.unverifiable.size <= 256); remote.unverifiable.forEach(ScheduleCommitCodec::uuid)
        val target = requireNotNull(remote.bindingKey)
        require(target.matches(Regex("[0-9a-f]{64}")))
        startV2(root, true)
        v2Put(96, remote.witness.toSortedMap().map { "${it.key} ${it.value}" }.joinToString("\n").toByteArray(Charsets.US_ASCII))
        v2Put(103, remote.unverifiable.sorted().joinToString("\n").toByteArray(Charsets.US_ASCII))
        v2Put(102, target.toByteArray(Charsets.US_ASCII))
    }
    override fun v2Start(root: ScheduleCommit, binding: Boolean) = blocking { startV2(root, binding) }
    private fun startV2(root: ScheduleCommit, binding: Boolean) {
        requireNoDatasetMigration()
        check(v2Commit(21) == null && v2Commit(23) == null) { "v2 already bound" }
        val current = fullState()
        if (binding) {
            check((listOf(1, 3, 4, 5, 6, 7, 21, 22, 23, 24, 25, 26, 27, 29, 96, 97, 100, 101) + (30..93) + (111..117))
                .none { database.dao().hasSyncRecord(it) } && current.events.isEmpty() && current.entries.isEmpty() && current.sources.isEmpty() && current.imported.isEmpty() &&
                current.dayAdjustments.isEmpty() && current.classOverrides.isEmpty() && current.customProfileKeys.isEmpty()) { "已有本地数据或旧绑定；拒绝静默覆盖" }
            replaceState(root.state, root.snapshot())
            val bytes = ScheduleCommitCodec.encode(root)
            v2Put(21, bytes); v2Frontier(22, listOf(root.commitId)); v2Remember(root.commitId, bytes)
        } else {
            check(decoded(3) == null && decoded(4) == null) { "旧协议冲突或待确认发布尚未恢复；禁止切换" }
            decoded(1)?.let { check(it.datasetId == root.datasetId) { "旧绑定 dataset 不一致" } }
            check(ScheduleMerge.same(current, root.state))
            v2Put(23, ScheduleCommitCodec.encode(root)); v2Put(24, ScheduleCommitCodec.encode(root.copy(state = current)))
            v2Put(2, catalogBytes(root.snapshot(), current))
        }
    }
    override fun v2Stage(bytes: ByteArray) = blocking {
        requireNoDatasetMigration()
        val c = ScheduleCommitCodec.decode(bytes)
        check(v2Commit(23) == null && v2Commit(21)?.datasetId == c.datasetId && c.parents == v2Ids(22))
        v2Put(23, bytes.copyOf()); v2Put(24, bytes.copyOf())
    }
    override fun v2Attempt() = blocking { requireNoDatasetMigration(); v2Put(29, byteArrayOf(1)) }
    private fun v2Hashes(id: Int) = database.dao().syncRecord(id)?.payload?.toString(Charsets.US_ASCII)?.lines()?.filter { it.isNotEmpty() }?.associate {
        val parts = it.split(' '); check(parts.size == 2); ScheduleCommitCodec.uuid(parts[0]); check(parts[1].matches(Regex("[0-9a-f]{64}"))); parts[0] to parts[1]
    }.orEmpty()
    override fun v2Observe(commits: Map<String, String>) = blocking {
        check(!database.dao().hasSyncRecord(200)) { "换库预览已保存，禁止修改原观察证据" }
        val saved = v2Hashes(96)
        check(saved.all { (id, hash) -> commits[id] == hash }) { "已验证历史缺失或内容改变；未完成同步" }
        check(commits.size <= 256)
        if (saved == commits) return@blocking
        v2Put(96, commits.toSortedMap().map { "${it.key} ${it.value}" }.joinToString("\n").toByteArray(Charsets.US_ASCII))
    }
    override fun v2RenewPending() = blocking {
        requireNoDatasetMigration()
        val old = requireNotNull(v2Commit(23)); val capture = requireNotNull(v2Commit(24))
        val abandoned = v2Hashes(97) + (old.commitId to digest(database.dao().syncRecord(23)!!.payload))
        check(abandoned.size <= 64) { "中断路径数量超限；保留待确认修改" }
        v2Put(97, abandoned.toSortedMap().map { "${it.key} ${it.value}" }.joinToString("\n").toByteArray(Charsets.US_ASCII))
        val id = java.util.UUID.randomUUID().toString()
        database.dao().deleteSyncRecord(29)
        v2Put(23, ScheduleCommitCodec.encode(old.copy(commitId = id)))
        v2Put(24, ScheduleCommitCodec.encode(capture.copy(commitId = id)))
    }
    override fun v2Receipt(bytes: ByteArray) = blocking {
        requireNoDatasetMigration()
        check(database.dao().syncRecord(23)!!.payload.contentEquals(bytes)) { "Pending receipt changed" }
        val c = ScheduleCommitCodec.decode(bytes)
        val replay = ScheduleMerge.merge(requireNotNull(v2Commit(24)).state, fullState(), c.state)
        check(replay.conflicts.isEmpty()) { "上传回执后的本地编辑重放失败；保留 capture" }
        replaceState(replay.state, c.snapshot())
        v2Put(21, bytes.copyOf()); v2Frontier(22, listOf(c.commitId)); v2Remember(c.commitId, bytes)
        database.dao().deleteSyncRecord(23); database.dao().deleteSyncRecord(24); database.dao().deleteSyncRecord(29)
    }
    override fun v2Apply(plan: ScheduleV2Plan): List<ScheduleMerge.Conflict> = blocking {
        requireNoDatasetMigration()
        check(plan.branches.isNotEmpty() && plan.branches.size <= 64)
        val result = plan.merge(fullState())
        if (result.conflicts.isNotEmpty()) {
            v2Put(25, ScheduleCommitCodec.encode(plan.base)); v2Put(26, ScheduleCommitCodec.encode(plan.localBase))
            v2Frontier(27, plan.branches.map { it.commitId })
            plan.branches.sortedBy { it.commitId }.forEachIndexed { i, c -> v2Put(30 + i, ScheduleCommitCodec.encode(c)) }
        } else {
            if (v2Commit(25) != null) {
                listOf(25, 26, 27).forEach { database.dao().deleteSyncRecord(it) }
                (30..93).forEach { database.dao().deleteSyncRecord(it) }
            }
            val baseline = plan.branches.first().copy(state = plan.remoteState())
            val previous = v2Commit(21)
            if (previous != null && ScheduleMerge.same(previous.state, baseline.state) &&
                v2Ids(22) == plan.branches.map { it.commitId }.sorted() && ScheduleMerge.same(fullState(), result.state)) return@blocking emptyList()
            replaceState(result.state, baseline.snapshot())
            v2Put(21, ScheduleCommitCodec.encode(baseline)); v2Frontier(22, plan.branches.map { it.commitId })
        }
        result.conflicts
    }
    override fun v2Resolve(choice: ScheduleMerge.Choice, expectedToken: String?) = blocking {
        requireNoDatasetMigration()
        val plan = requireNotNull(v2Plan())
        check(expectedToken == null || expectedToken == plan.token()) { "冲突版本已经变化；请重新查看后再选择" }
        val result = plan.merge(fullState(), choice)
        val c = ScheduleCommit(plan.base.datasetId, java.util.UUID.randomUUID().toString(), v2Writer(), plan.branches.map { it.commitId }.sorted(), result.state)
        replaceState(result.state, c.snapshot())
        // Decision + exact upload capture are one transaction, including process death recovery.
        v2Put(23, ScheduleCommitCodec.encode(c)); v2Put(24, ScheduleCommitCodec.encode(c))
        listOf(25, 26, 27).forEach { database.dao().deleteSyncRecord(it) }
        (30..93).forEach { database.dao().deleteSyncRecord(it) }
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
            if (ActiveScheduleV3.active(database) || dao.checkpoint() != null || v2Commit(21) != null || v2Commit(23) != null) check(source.resolvedTermKey()?.let { it in fullState().profiles } != false) { "Missing term profile" }
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
        if (keepOpen) return // One process-owned Room/Writer shared by Activities and JobService.
        if (closed) return
        closed = true
        executor.execute { try { activeV3?.close() } finally { if (closeDatabase) database.close() } }; executor.shutdown()
    }
}
