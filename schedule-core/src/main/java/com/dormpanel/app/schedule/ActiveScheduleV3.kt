package com.dormpanel.app.schedule

import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID

/** Private metadata tables share the existing Room connection and its transaction.
 * No second course database is opened. v2 checkpoint rows stay intact for recovery. */
internal class ActiveScheduleV3(private val database: ScheduleDatabase,
    mirror: (ScheduleState) -> Unit) : AutoCloseable {
    companion object {
        const val MODE = 205
        const val ORIGINAL_LOCAL = 206
        fun active(database: ScheduleDatabase) = database.dao().hasSyncRecord(MODE)
    }
    val directory = File(requireNotNull(database.openHelper.writableDatabase.path) + ".sync-v3")
    val journal = V3Journal(directory, { V3RoomSql(database, false) }, { frame ->
        mirror(requireNotNull(SyncV3Codec.decode(Base64.getDecoder().decode(frame.getString("local"))).state))
    })
    init {
        try { if(active(database)) {
            val frame=journal.controlSummary()
            check(journal.snapshot().has("local")) { "活动 v3 本地事务缺失；保留 Room 课程和证据，拒绝重建空数据集" }
            check(binding().optString("phase")!="ACTIVE" || frame.has("binding") && frame.has("baselinePath")) { "活动 v3 绑定或基线缺失；拒绝生成新 Genesis" }
        } } catch(error:Exception) { journal.close(); throw error }
    }
    private val writer = try { database.runInTransaction<String> {
        database.dao().syncRecord(20)?.payload?.toString(Charsets.US_ASCII)?.also(ScheduleCommitCodec::uuid) ?: run {
            check(!active(database)) { "活动 v3 Writer 身份缺失；拒绝生成新 Writer" }
            UUID.randomUUID().toString().also {
            database.dao().checkpoint(ScheduleSyncCheckpoint(20, it.toByteArray(Charsets.US_ASCII)))
            }
        }
    } } catch(error:Exception) { journal.close();throw error }
    val engine = try { V3Engine(journal, writer) } catch(error:Exception) { journal.close();throw error }
    fun binding() = JSONObject(requireNotNull(database.dao().syncRecord(MODE)).payload.toString(Charsets.UTF_8))
    fun migrationEvidence(): JSONObject = journal.meta.localOperation {
        requireNotNull(journal.meta.get("hostMigration", binding().getString("migrationId"))) { "迁移证明缺失；保留源与目标并停止切换" }
    }
    fun localWritesAllowed(): Boolean = binding().let { mode ->
        runCatching { journal.meta.assertWritable() }.isSuccess && mode.optString("phase") !in listOf("MIGRATING","JOINING")
    }
    private fun envelope(state:ScheduleState):String {
        val id="00000000-0000-0000-0000-000000000001"
        return Base64.getEncoder().encodeToString(SyncV3Codec.encode(V3Object(2,id,id,id,id,1,"1".repeat(64),state=state.copy(sync=null))))
    }
    fun prepareVerifiedJoin(preview:UnboundV3JoinPreview,validateBefore:()->Unit) {
        val target=preview.target; val state=requireNotNull(target.value.state)
        val descriptor=JSONObject().put("datasetId",target.identity.datasetId).put("descriptorPath",V3Paths.path(target.identity)).put("descriptorHash",SyncV3Codec.hash(target.descriptor))
        val mode=JSONObject().put("protocol","v3").put("folder",target.folder).put("intent","JOIN").put("phase","ACTIVE")
        journal.localTransaction { frame ->
            check(!active(database) && !frame.has("binding"));validateBefore()
            val original=ScheduleSnapshotCodec.encode(ScheduleSnapshot(UUID.randomUUID().toString(),UUID.randomUUID().toString(),null,System.currentTimeMillis(),preview.original.copy(sync=null),1))
            database.dao().checkpoint(ScheduleSyncCheckpoint(ORIGINAL_LOCAL,original))
            database.dao().checkpoint(ScheduleSyncCheckpoint(MODE,mode.toString().toByteArray(Charsets.UTF_8)))
            val raw=envelope(state)
            frame.put("writerId",writer).put("binding",descriptor).put("local",raw).put("baseline",raw).put("baselinePath",V3Paths.path(target.value))
                .put("baselineHash",target.hash).put("activeEpoch",target.value.epochId).put("revision",0).put("unbasedLocal",false)
                .put("joinReadback",JSONObject().put("path",V3Paths.path(target.value)).put("hash",target.hash).put("endpoint",target.folder).put("roundId",target.readbackRound))
        }
    }
    fun prepareExisting(preview:ExistingV3MigrationPreview,validateBefore:()->Unit) {
        val source=preview.source; val target=preview.target; val state=requireNotNull(target.value.state)
        check(SyncV3Codec.stateBytes(source.merged).contentEquals(SyncV3Codec.stateBytes(state))) { "源与目标 RAW 不等价，不能证明安全加入；保留双方并停止切换" }
        check(target.identity.datasetId!=source.frame.baseline!!.datasetId) { "跨协议数据集身份必须独立" }
        val control=JSONObject().put("protocol","v3").put("folder",target.folder).put("intent","JOIN").put("phase","ACTIVE").put("migrationId",source.transactionId)
        val descriptor=JSONObject().put("datasetId",target.identity.datasetId).put("descriptorPath",V3Paths.path(target.identity)).put("descriptorHash",SyncV3Codec.hash(target.descriptor))
        val proof=source.evidence().put("targetKind","EXISTING").put("targetDescriptor",descriptor).put("targetBaselinePath",V3Paths.path(target.value))
            .put("targetBaselineHash",target.hash).put("exactReadbackConfirmed",true).put("rawEquivalent",true).put("sourceWriterPaused",true)
        journal.localTransaction { frame ->
            check(!active(database) && !frame.has("binding")); validateBefore()
            val original=ScheduleSnapshotCodec.encode(source.frame.baseline.snapshot().copy(state=source.frame.local.copy(sync=null)))
            database.dao().checkpoint(ScheduleSyncCheckpoint(ORIGINAL_LOCAL,original))
            journal.meta.put("hostMigration",source.transactionId,proof)
            database.dao().checkpoint(ScheduleSyncCheckpoint(MODE,control.toString().toByteArray(Charsets.UTF_8)))
            val raw=envelope(state)
            frame.put("writerId",writer).put("binding",descriptor).put("local",raw).put("baseline",raw)
                .put("baselinePath",V3Paths.path(target.value)).put("baselineHash",target.hash).put("activeEpoch",target.value.epochId)
                .put("revision",0).put("unbasedLocal",false).put("joinReadback",JSONObject().put("path",V3Paths.path(target.value)).put("hash",target.hash))
        }
    }
    fun state(): ScheduleState {
        val s = journal.snapshot()
        check(s.has("local")) { "v3 本地事务缺失；保留原数据库并停止写入" }
        val b = s.optJSONObject("binding")
        return engine.local().copy(sync = b?.let { SnapshotIdentity(it.getString("datasetId"), s.optString("baselinePath", "待验证"),
            !s.has("baseline") || !ScheduleMerge.same(engine.local(), requireNotNull(SyncV3Codec.decode(Base64.getDecoder().decode(s.getString("baseline"))).state))) })
    }
    fun prepare(seed: ScheduleState, folder: String, descriptor: ByteArray? = null,
        migration: ScheduleV3MigrationPreview? = null, validateBefore: () -> Unit = {}) {
        check(!active(database)) { "已有活动协议；禁止重复切换" }
        val mode = JSONObject().put("protocol", "v3").put("folder", folder).put("intent", if (descriptor == null) "CREATE" else "JOIN").put("phase","CREATING")
        if (migration != null) mode.put("migrationId", migration.transactionId).put("phase", "MIGRATING")
        if (descriptor != null) {
            val d = SyncV3Codec.decode(descriptor); require(d.type == 1)
            mode.put("descriptor", Base64.getEncoder().encodeToString(descriptor)).put("descriptorHash", SyncV3Codec.hash(descriptor))
            mode.put("phase","JOINING")
        }
        val encoded = envelope(seed)
        journal.localTransaction { frame ->
            check(!active(database))
            validateBefore()
            if(migration!=null) journal.meta.put("hostMigration",migration.transactionId,migration.evidence())
            val raw = ScheduleSnapshotCodec.encode(ScheduleSnapshot(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null,
                System.currentTimeMillis(), (migration?.frame?.local ?: seed).copy(sync = null), 1))
            database.dao().checkpoint(ScheduleSyncCheckpoint(ORIGINAL_LOCAL, raw))
            database.dao().checkpoint(ScheduleSyncCheckpoint(MODE, mode.toString().toByteArray(Charsets.UTF_8)))
            frame.put("writerId", writer).put("local", encoded).put("revision", 0)
        }
    }
    fun synchronize(account: WebDavAccount): String {
        val b = binding(); val budget = V3Budget(); val remote = V3WebDavStore(account, b.getString("folder"), budget)
        val proof = if(b.has("migrationId") && b.optString("phase")!="ACTIVE") ScheduleV3MigrationPreview.restore(migrationEvidence()) else null
        val recheck: (() -> Unit)? = proof?.let { p -> {
            val old = WebDavCommitStore(account, p.sourceFolder)
            ScheduleV3Migration.unchanged(p, ScheduleV3Migration.inspect(p.frame, p.hashes, old, p.sourceFolder, p.transactionId))
        } }
        return synchronize(remote, remote, budget, recheck)
    }
    fun synchronize(remote: V3ObjectStore, publication: V3PublicationStore, budget: V3Budget, recheck: (() -> Unit)? = null): String {
        val b = binding()
        val migrating = b.has("migrationId") && b.optString("phase") != "ACTIVE"
        if (migrating) requireNotNull(recheck) { "迁移恢复必须重新验证源历史" }.invoke()
        if (!journal.snapshot().has("binding")) {
            if (b.getString("intent") == "CREATE") engine.initialize(publication)
            else {
                val raw = Base64.getDecoder().decode(b.getString("descriptor")); val descriptor = SyncV3Codec.decode(raw)
                check(remote.read(V3Paths.path(descriptor))?.contentEquals(raw) == true) { "v3 Descriptor 身份变化；原数据已保留" }
                engine.bind(descriptor, b.getString("descriptorHash"))
            }
        }
        if (migrating) {
            if ((journal.snapshot().optJSONArray("initialization")?.length() ?: 0) > 0) engine.initialize(publication)
            val s = journal.snapshot(); val p = migrationEvidence()
            check(s.optJSONArray("initialization")?.length() == 0 && s.has("baselinePath") && s.has("receipt")) { "Genesis 尚未回读确认；继续恢复原候选" }
            val genesisState = requireNotNull(SyncV3Codec.decode(Base64.getDecoder().decode(s.getString("baseline"))).state)
            check(SyncV3Codec.hash(SyncV3Codec.stateBytes(genesisState)) == p.getString("rawHash")) { "目标 Genesis 与完整源 RAW 快照不等价" }
            recheck!!.invoke()
            journal.localTransaction {
                val mode = binding(); val evidence = migrationEvidence()
                evidence.put("targetDescriptor", s.getJSONObject("binding")).put("targetGenesisPath", s.getString("baselinePath"))
                    .put("targetGenesisHash", s.getString("baselineHash")).put("exactReadbackConfirmed", true).put("businessEquivalent", true)
                mode.put("phase", "ACTIVE")
                journal.meta.put("hostMigration",mode.getString("migrationId"),evidence)
                database.dao().checkpoint(ScheduleSyncCheckpoint(MODE, mode.toString().toByteArray(Charsets.UTF_8)))
            }
        }
        return try {
            val result=engine.round(remote, publication, V3IndexedCache(directory, budget), budget, 32)
            if(binding().optString("phase") in listOf("JOINING","CREATING") && journal.controlSummary().has("baselinePath")) journal.localTransaction {
                val mode=binding().put("phase","ACTIVE"); database.dao().checkpoint(ScheduleSyncCheckpoint(MODE,mode.toString().toByteArray(Charsets.UTF_8)))
            }
            result
        }
        catch (e: IllegalStateException) {
            when {
                e.message.orEmpty().contains("budget") -> "BUDGET"
                e.message.orEmpty().contains("initial discovery/validation incomplete") -> "DISCOVERING"
                else -> throw e
            }
        }
    }
    override fun close() = journal.close()
}

internal fun v3StatusMessage(status: String) = when (status) {
    "PUBLISHED" -> "v3 对象已由指定远端回读确认；其他设备接收情况未知"
    "OBSERVED" -> "本轮观察到的 v3 DAG 已合并；旧 Epoch 巡检未完成"
    "CONFLICT" -> "v3 存在未决冲突；双方内容已保存"
    "BUDGET" -> "v3 本轮预算已用完；进度已保存，等待继续"
    "DISCOVERING" -> "v3 历史发现与验证尚未完成；进度已保存"
    "ROTATED" -> "v3 已换代；旧 Epoch 巡检未完成"
    else -> "v3 本地已保存；同步状态待检查"
}
