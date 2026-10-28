package com.dormpanel.app.schedule

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.sql.DriverManager

interface V3SqlPort : AutoCloseable {
    fun execute(sql: String, args: List<String> = emptyList()): Int
    fun rows(sql: String, args: List<String> = emptyList()): List<Map<String, String>>
    fun <T> atomic(operation: () -> T): T
}

private class V3AndroidSql(file: File) : V3SqlPort {
    private val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).also { it.enableWriteAheadLogging() }
    override fun execute(sql: String, args: List<String>): Int {
        if (sql.startsWith("PRAGMA")) return db.rawQuery(sql, args.toTypedArray()).use { it.moveToFirst(); 0 }
        if (sql.startsWith("UPDATE") || sql.startsWith("DELETE")) return db.compileStatement(sql).use { s -> args.forEachIndexed { n, v -> s.bindString(n + 1, v) }; s.executeUpdateDelete() }
        db.execSQL(sql, args.toTypedArray()); return 0
    }
    override fun rows(sql: String, args: List<String>): List<Map<String, String>> = db.rawQuery(sql, args.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.columnNames.indices.associate { c.columnNames[it] to c.getString(it) }) } }
    override fun <T> atomic(operation: () -> T): T { db.beginTransaction(); try { val value = operation(); db.setTransactionSuccessful(); return value } finally { db.endTransaction() } }
    override fun close() = db.close()
}

/** JDBC is supplied only by JVM tests. It is neither packaged nor loaded on Android. */
private class V3JvmSql(file: File) : V3SqlPort {
    init { Class.forName("org.sqlite.JDBC") }
    private val db = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    override fun execute(sql: String, args: List<String>): Int = db.prepareStatement(sql).use { s -> args.forEachIndexed { n, v -> s.setString(n + 1, v) }; if (s.execute()) { s.resultSet.close(); 0 } else s.updateCount.coerceAtLeast(0) }
    override fun rows(sql: String, args: List<String>): List<Map<String, String>> = db.prepareStatement(sql).use { s -> args.forEachIndexed { n, v -> s.setString(n + 1, v) }; s.executeQuery().use { r -> buildList { while (r.next()) add((1..r.metaData.columnCount).associate { r.metaData.getColumnName(it) to r.getString(it) }) } } }
    override fun <T> atomic(operation: () -> T): T { db.autoCommit = false; try { val value = operation(); db.commit(); return value } catch (e: Exception) { db.rollback(); throw e } finally { db.autoCommit = true } }
    override fun close() = db.close()
}

data class V3MetaRow(val seq: Long, val key: String, val value: JSONObject)
data class V3NamedRow(val seq:Long,val namespace:String,val key:String,val value:JSONObject)

/** SQLite B-tree pages, direct immutable identities, bounded keyset cursors. Local control and
 * indexes share the SAME transaction. This whole-file lock overlaps Node SQLite owner locks. */
class V3Metadata(directory: File, sqlFactory: ((File) -> V3SqlPort)? = null, requireExisting:Boolean=false) : AutoCloseable {
    private val owner: FileChannel
    private val lease: java.nio.channels.FileLock
    private lateinit var sql: V3SqlPort
    private var depth = 0; private var closed = false
    var budget: V3Budget? = null
    var reads = 0L; private set
    var writes = 0L; private set
    var readBytes = 0L; private set
    var nodeReads = 0L; private set
    var fault: ((String) -> Unit)? = null
    var guard: (() -> Unit)? = null
    private val verifiedSnapshots = linkedSetOf<String>()
    init {
        if(requireExisting)require(directory.isDirectory&&File(directory,"v3-state-v2.sqlite").isFile&&File(directory,"v3-owner.lock").isFile) { "候选私有数据库缺失，停止清理而不重建" }
        require(directory.absolutePath.replace('\\', '/').split('/').none { it == "dormpanel-sync-v2" || it == "dormpanel-sync-v3" }) { "v3 metadata must remain outside protocol directories" }
        val missing = generateSequence(directory) { it.parentFile }.takeWhile { !it.exists() }.toList()
        check(directory.isDirectory || directory.mkdirs()); require(!java.nio.file.Files.isSymbolicLink(directory.toPath()))
        require(listOf("workspace.json", "catalog.json", "graph.json", "identity.json", "schedule.db").none { File(directory, it).exists() }) { "v3 private store refuses existing production/v2 root" }
        require(!File(directory, "v3-engine-v1.json").exists()) { "INCOMPATIBLE_V3_DRAFT_STATE: preserve old isolated journal and use a new private test directory" }
        val lockFile = File(directory, "v3-owner.lock"); require(!java.nio.file.Files.isSymbolicLink(lockFile.toPath()))
        owner = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
        lease = try { requireNotNull(owner.tryLock()) { "v3 writer already locked" } } catch (e: Exception) { owner.close(); throw IllegalStateException("v3 writer already locked", e) }
        try {
            V3Atomic.sync(directory); missing.forEach { V3Atomic.sync(requireNotNull(it.parentFile)) }
            val file = File(directory, "v3-state-v2.sqlite"); require(!java.nio.file.Files.isSymbolicLink(file.toPath()))
            sql = sqlFactory?.invoke(file) ?: if (System.getProperty("java.vm.name") == "Dalvik") V3AndroidSql(file) else V3JvmSql(file)
            if(requireExisting) {
                require(sql.rows("SELECT name FROM sqlite_master WHERE type='table' AND name NOT GLOB 'sqlite_*'").map { it.getValue("name") }.toSet()==setOf("v3_records")) { "未知候选数据库，停止清理" }
                require(get("format","version")?.getInt("value")==2) { "候选数据库格式或归属不明，停止清理" }
            }
            sql.rows("PRAGMA journal_mode=WAL"); sql.execute("PRAGMA synchronous=FULL"); sql.execute("PRAGMA cache_size=-2048"); sql.execute("PRAGMA mmap_size=0"); sql.execute("PRAGMA wal_autocheckpoint=64"); sql.execute("PRAGMA journal_size_limit=1048576")
            sql.execute("CREATE TABLE IF NOT EXISTS v3_records(seq INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ns TEXT NOT NULL, k TEXT NOT NULL, v TEXT NOT NULL, sum TEXT NOT NULL)")
            sql.execute("CREATE UNIQUE INDEX IF NOT EXISTS index_v3_records_ns_k ON v3_records(ns,k)"); sql.execute("CREATE INDEX IF NOT EXISTS index_v3_records_ns_seq ON v3_records(ns,seq)")
            val version = get("format", "version"); require(version == null || version.getInt("value") == 2)
            if (version == null) atomic { put("format", "version", JSONObject().put("value", 2)) }
        } catch (e: Exception) { if (::sql.isInitialized) runCatching { sql.close() }; lease.release(); owner.close(); throw e }
    }
    @Synchronized fun requiresReopen(): Boolean = closed
    private fun checkOpen() { check(!closed) { "v3 storage requires reopen" } }
    @Synchronized fun assertWritable() { checkOpen(); guard?.invoke() }
    @Synchronized fun <T> localOperation(operation: () -> T): T { val previous = budget; budget = V3Budget(maxRequests = 0); try { return operation() } finally { budget = previous } }
    private fun digest(ns: String, key: String, value: String) = SyncV3Codec.hash((ns + "\u0000" + key + "\u0000" + value).toByteArray(Charsets.UTF_8))
    private fun decode(ns: String, row: Map<String, String>, expandSnapshots:Boolean=true): JSONObject {
        val raw = row.getValue("v"); val bytes = raw.toByteArray(Charsets.UTF_8).size.toLong(); budget?.charge(bytes, 0); reads++; readBytes += bytes
        if (ns == "node") nodeReads++
        require(digest(ns, row.getValue("k"), raw) == row.getValue("sum")) { "v3 metadata checksum corrupt" }
        val value = JSONObject(raw); return if (ns == "snapshot"||!expandSnapshots) value else unpack(value, mutableMapOf()) as JSONObject
    }
    private fun remember(hash: String) { if (verifiedSnapshots.size >= 128) verifiedSnapshots.remove(verifiedSnapshots.first()); verifiedSnapshots += hash }
    private fun pack(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { out -> value.namesList().forEach { out.put(it, pack(value.get(it))) } }
        is JSONArray -> JSONArray((0 until value.length()).map { pack(value.get(it)) })
        is String -> if (!value.startsWith(PRIVATE_SNAPSHOT_PREFIX)) value else {
            val bytes = java.util.Base64.getDecoder().decode(value); val hash = SyncV3Codec.hash(bytes)
            val exists = sql.rows("SELECT 1 AS found FROM v3_records WHERE ns='snapshot' AND k=?", listOf(hash)).isNotEmpty()
            if (!exists) write("snapshot", hash, JSONObject().put("bytes", value).toString())
            else if (hash !in verifiedSnapshots) require(SyncV3Codec.hash(java.util.Base64.getDecoder().decode(requireNotNull(get("snapshot", hash)).getString("bytes"))) == hash) { "v3 local snapshot hash" }
            remember(hash); JSONObject().put("\$v3Snapshot", hash).put("kind", "base64")
        }
        else -> value
    }
    private fun unpack(value: Any?, memo: MutableMap<String, String>): Any? = when (value) {
        is JSONObject -> if (value.has("\$v3Snapshot") && value.has("kind")) {
            require(value.getString("kind") == "base64") { "v3 unsupported private snapshot" }; val hash = value.getString("\$v3Snapshot"); SyncV3Codec.hashValue(hash)
            memo[hash] ?: requireNotNull(get("snapshot", hash)) { "v3 missing local snapshot" }.getString("bytes").also { require(SyncV3Codec.hash(java.util.Base64.getDecoder().decode(it)) == hash) { "v3 local snapshot hash" }; memo[hash] = it; remember(hash) }
        } else JSONObject().also { out -> value.namesList().forEach { out.put(it, unpack(value.get(it), memo)) } }
        is JSONArray -> JSONArray((0 until value.length()).map { unpack(value.get(it), memo) })
        else -> value
    }
    @Synchronized fun get(ns: String, key: String): JSONObject? { checkOpen(); return sql.rows("SELECT k,v,sum FROM v3_records WHERE ns=? AND k=?", listOf(ns, key)).firstOrNull()?.let { decode(ns, it) } }
    @Synchronized fun put(ns: String, key: String, value: JSONObject) {
        checkOpen(); check(depth > 0) { "v3 metadata write outside transaction" }; require(ns != "snapshot") { "v3 snapshot bodies are immutable" }
        val raw = (pack(value) as JSONObject).toString(); if (ns == "state" || ns == "workspace") require(raw.toByteArray(Charsets.UTF_8).size <= 48 * 1024 * 1024) { "v3 local control budget" }; write(ns, key, raw)
    }
    private fun write(ns: String, key: String, raw: String) {
        check(depth > 0); val hash = digest(ns, key, raw)
        if (sql.execute("UPDATE v3_records SET v=?,sum=? WHERE ns=? AND k=?", listOf(raw, hash, ns, key)) == 0) sql.execute("INSERT INTO v3_records(ns,k,v,sum) VALUES(?,?,?,?)", listOf(ns, key, raw, hash)); writes++
    }
    @Synchronized fun remove(ns: String, key: String) { checkOpen(); check(depth > 0 && ns != "snapshot") { "v3 no snapshot GC" }; sql.execute("DELETE FROM v3_records WHERE ns=? AND k=?", listOf(ns, key)); writes++ }
    @Synchronized fun page(ns: String, after: Long = 0, limit: Int = 64, through: Long = Long.MAX_VALUE): List<V3MetaRow> {
        checkOpen(); require(ns != "snapshot" && after >= 0 && limit in 1..256)
        return sql.rows("SELECT seq,k,v,sum FROM v3_records WHERE ns=? AND seq>? AND seq<=? ORDER BY seq LIMIT ?", listOf(ns, after.toString(), through.toString(), limit.toString())).map { V3MetaRow(it.getValue("seq").toLong(), it.getValue("k"), decode(ns, it)) }
    }
    @Synchronized fun lastSeq(ns: String): Long { checkOpen(); return sql.rows("SELECT seq FROM v3_records WHERE ns=? ORDER BY seq DESC LIMIT 1", listOf(ns)).firstOrNull()?.getValue("seq")?.toLong() ?: 0 }
    @Synchronized fun count(ns: String): Long { checkOpen(); return sql.rows("SELECT COUNT(*) AS n FROM v3_records WHERE ns=?", listOf(ns)).single().getValue("n").toLong() }
    /** GC must see the stored reference marker, not its expanded RAW body. */
    @Synchronized fun rawRecords(after:Long,limit:Int=128):List<V3NamedRow> {
        checkOpen();require(after>=0&&limit in 1..128)
        return sql.rows("SELECT seq,ns,k,v,sum FROM v3_records WHERE seq>? ORDER BY seq LIMIT ?",listOf(after.toString(),limit.toString())).map {
            V3NamedRow(it.getValue("seq").toLong(),it.getValue("ns"),it.getValue("k"),decode(it.getValue("ns"),it,false))
        }
    }
    @Synchronized fun <T> atomic(operation: () -> T): T {
        checkOpen(); guard?.invoke(); if (depth > 0) return operation()
        var committed = false
        try { val value = sql.atomic { depth++; try { val v = operation(); fault?.invoke("before-commit"); v } finally { depth-- } }; committed = true; fault?.invoke("after-commit"); return value }
        catch (e: Exception) { if (committed || e is java.sql.SQLException || e is android.database.sqlite.SQLiteException) closed = true; throw e }
    }
    @Synchronized override fun close() { try { sql.close() } finally { try { if (lease.isValid) lease.release() } finally { owner.close(); closed = true } } }
    companion object {
        private val PRIVATE_SNAPSHOT_PREFIX by lazy {
            val id = "00000000-0000-0000-0000-000000000001"
            java.util.Base64.getEncoder().encodeToString(SyncV3Codec.encode(V3Object(2, id, id, id, id, 1, "1".repeat(64), state = ScheduleState()))).take(260)
        }
    }
}
