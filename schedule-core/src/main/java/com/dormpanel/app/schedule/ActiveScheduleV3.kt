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
        fun endpointKey(remote:V3WebDavStore,account:WebDavAccount)=digest(("WebDAV\n"+remote.endpointIdentity()+"\n"+account.username+(account.certificatePem?.let { "\n"+WebDavTls.fingerprint(it) } ?: "")).toByteArray(Charsets.UTF_8))
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
    fun prepareVerifiedJoin(preview:UnboundV3JoinPreview,accessKey:String,validateBefore:()->Unit) {
        val target=preview.target; val state=requireNotNull(target.value.state)
        val descriptor=JSONObject().put("datasetId",target.identity.datasetId).put("descriptorPath",V3Paths.path(target.identity)).put("descriptorHash",SyncV3Codec.hash(target.descriptor))
        val mode=JSONObject().put("protocol","v3").put("folder",target.folder).put("intent","JOIN").put("phase","ACTIVE").put("endpointKey",accessKey)
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
    fun prepareExisting(preview:ExistingV3MigrationPreview,accessKey:String?=null,validateBefore:()->Unit) {
        val source=preview.source; val target=preview.target; val state=requireNotNull(target.value.state)
        check(SyncV3Codec.stateBytes(source.merged).contentEquals(SyncV3Codec.stateBytes(state))) { "源与目标 RAW 不等价，不能证明安全加入；保留双方并停止切换" }
        check(target.identity.datasetId!=source.frame.baseline!!.datasetId) { "跨协议数据集身份必须独立" }
        val control=JSONObject().put("protocol","v3").put("folder",target.folder).put("intent","JOIN").put("phase","ACTIVE").put("migrationId",source.transactionId)
        if(accessKey!=null)control.put("endpointKey",accessKey)
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
    fun endpointAccount(settings:WebDavSettings,fallback:()->WebDavAccount):WebDavAccount = binding().optString("endpointAccount").ifBlank { null }?.let(settings::openEndpoint) ?: fallback()
    private fun endpointFingerprint():String = journal.meta.localOperation {
        SyncV3Codec.hash((journal.snapshot().toString()+binding().toString()+journal.meta.lastSeq("node")+":"+journal.meta.lastSeq("observed")+journal.meta.page("frontier",0,65).joinToString { it.key }).toByteArray(Charsets.UTF_8))
    }
    private fun endpointProtection() = journal.meta.localOperation {
        val s=journal.snapshot();check(binding().optString("phase")=="ACTIVE" && s.has("binding") && !s.has("rotation") && (s.optJSONArray("initialization")?.length()?:0)==0) { "初始化、迁移或换代恢复未完成" }
        check(s.optJSONObject("pending")?.optString("phase") in listOf(null,"STAGED","RECEIPTED")) { "存在未回执 PUT；请先恢复原端点并完成只读恢复及回执，新地址 404 不能证明旧 PUT 未发生" }
        check(journal.meta.count("todo")==0L && journal.meta.count("error")==0L && journal.meta.get("control","walk")==null) { "已知历史未验证或异常，请先恢复原端点继续验证" }
    }
    private val endpointJobs by lazy { V3EndpointJobs(journal) }
    fun reapEndpoints() { if(runCatching { endpointProtection() }.isSuccess)endpointJobs.reap(fingerprint=endpointFingerprint()) }
    fun endpointJob(token:String):JSONObject = endpointJobs.find(token).also { j ->
        val end=endpointJobs.terminal(j)?:if(j.has("fingerprint")&&j.getString("fingerprint")!=endpointFingerprint())"STALE" else null
        end?.let { if(j.optString("status")=="CLEANING"||!j.has("binding"))j.put("materials",if(j.optString("status")=="CLEANING")"CLEANING" else "RETIRED");j.put("status",it) }
    }
    fun endpointLatest():JSONObject? {
        reapEndpoints()
        return journal.meta.localOperation { listOf("endpointJob","endpointReceipt","endpointRetired").flatMap { journal.meta.page(it,0,128) }.maxByOrNull { it.value.optLong("createdAt") }?.key?.let(::endpointJob) }
    }
    fun endpointFolder():String = binding().let { check(it.optString("phase")=="ACTIVE") { "活动绑定尚未完成" };it.getString("folder").also { value -> check(value.isNotBlank());WebDavClient().url(value) } }
    fun endpointStart(account:WebDavAccount,folder:String,sealed:String):JSONObject = synchronized(journal.networkGate) {
        endpointProtection();reapEndpoints();val budget=V3Budget();val remote=V3WebDavStore(account,folder,budget)
        val token=UUID.randomUUID().toString()
        val job=journal.meta.localOperation {
            val active=journal.meta.page("endpointJob",0,128).count { endpointJobs.terminal(it.value)==null }
            check(active<8) { "有效预检已达 8 个；请继续或取消已有任务，任务在创建后 24 小时固定到期" }
            check(journal.meta.count("endpointJob")<128) { "候选清理尚未完成，请继续有界清理" }
            JSONObject().put("token",token).put("folder",folder).put("endpointAccount",sealed).put("endpointKey",endpointKey(remote,account))
                .put("binding",journal.snapshot().getJSONObject("binding")).put("fingerprint",endpointFingerprint()).put("status","VALIDATING").put("createdAt",System.currentTimeMillis()).put("expiresAt",System.currentTimeMillis()+V3EndpointJobs.TTL)
                .put("ceiling",journal.meta.lastSeq("node")).put("observedCeiling",journal.meta.lastSeq("observed")).put("after",0).put("observedAfter",0)
        }
        journal.meta.localOperation { journal.meta.atomic { journal.meta.put("endpointJob",token,job) } };job
    }
    private fun endpointDirectory(token:String):File { ScheduleCommitCodec.uuid(token);return File(directory,"endpoint-$token") }
    fun endpointContinue(token:String,account:WebDavAccount,budget:V3Budget=V3Budget(),remoteOverride:V3ObjectStore?=null):JSONObject = synchronized(journal.networkGate) {
        val job=endpointJob(token);if(endpointJobs.terminal(job)!=null)return@synchronized job
        check(job.getString("fingerprint")==endpointFingerprint()) { "端点预览过期：本地修改或已知历史变化，请重新预检" };endpointProtection()
        val transport=V3WebDavStore(account,job.getString("folder"),budget);val remote=remoteOverride?:transport
        check(endpointKey(transport,account)==job.getString("endpointKey")) { "候选访问身份变化" }
        try { V3EndpointInspection.step(endpointDirectory(token),journal,job,remote,budget) }
        catch(e:Exception) { job.put("failure",if(!job.optBoolean("networkAndAuthenticationVerified"))"ACCESS_TLS_OR_AUTH_UNVERIFIED" else if(!job.optBoolean("descriptorExact"))"DESCRIPTOR_IDENTITY" else "HISTORY_UNVERIFIED");throw e }
        finally { journal.meta.localOperation { journal.meta.atomic { journal.meta.put("endpointJob",token,job) } } }
        job
    }
    fun endpointConfirm(token:String,account:WebDavAccount,remoteOverride:V3ObjectStore?=null):JSONObject = synchronized(journal.networkGate) {
        val job=endpointJob(token)
        if(job.optString("status")=="COMMITTED")return@synchronized job
        check(job.optString("status")=="READY" && System.currentTimeMillis()-job.getLong("readyAt")<600000) { "端点预览尚未完成或过期" }
        val nodes=job.getLong("candidateNodes");val tips=job.getJSONArray("frontier").toString()
        val budget=V3Budget();val current=endpointContinue(token,account,budget,remoteOverride)
        if(current.optString("status")!="READY" || current.getLong("candidateNodes")!=nodes || current.getJSONArray("frontier").toString()!=tips) {
            current.put("status","STALE");journal.meta.localOperation { journal.meta.atomic { journal.meta.put("endpointJob",token,current) } };error("候选新历史导致预览过期，请新建预检并重新确认")
        }
        V3EndpointInspection.recheck(endpointDirectory(token),current,remoteOverride?:V3WebDavStore(account,current.getString("folder"),budget))
        journal.localTransaction {
            check(current.getString("fingerprint")==endpointFingerprint()) { "确认期间本地状态变化，预览过期" };endpointProtection()
            val mode=binding().put("folder",current.getString("folder")).put("endpointKey",current.getString("endpointKey"))
                .put("endpointAccount",current.getString("endpointAccount")).put("endpointDecision",token)
            database.dao().checkpoint(ScheduleSyncCheckpoint(MODE,mode.toString().toByteArray(Charsets.UTF_8)))
            current.put("status","COMMITTED");journal.meta.put("endpointJob",token,current)
        }
        current
    }
    fun endpointCancel(token:String):JSONObject = synchronized(journal.networkGate) {
        val job=endpointJobs.find(token);check(job.optString("status")!="COMMITTED") { "切换已提交，不能取消" }
        if(job.has("binding")) { job.put("status","CANCELLED");job.remove("endpointAccount");journal.meta.localOperation { journal.meta.atomic { journal.meta.put("endpointJob",token,job) } } }
        reapEndpoints();endpointJob(token)
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
    fun synchronize(account: WebDavAccount): String = synchronized(journal.networkGate) {
        val b = binding(); val budget = V3Budget(); val remote = V3WebDavStore(account, b.getString("folder"), budget)
        val accessKey=endpointKey(remote,account)
        if(!b.has("endpointKey")) {
            check(!journal.controlSummary().has("binding")) { "旧草案绑定缺少端点身份；保留本地数据与候选，拒绝猜测新帐号身份" }
            journal.localTransaction { val mode=binding().put("endpointKey",accessKey);database.dao().checkpoint(ScheduleSyncCheckpoint(MODE,mode.toString().toByteArray(Charsets.UTF_8))) }
        } else check(b.getString("endpointKey")==accessKey) { "访问端点或帐号身份变化；本地修改保留，请恢复原帐号，不能隐式更换 v3 端点" }
        val proof = if(b.has("migrationId") && b.optString("phase")!="ACTIVE") ScheduleV3MigrationPreview.restore(migrationEvidence()) else null
        val recheck: (() -> Unit)? = proof?.let { p -> {
            val old = WebDavCommitStore(account, p.sourceFolder)
            ScheduleV3Migration.unchanged(p, ScheduleV3Migration.inspect(p.frame, p.hashes, old, p.sourceFolder, p.transactionId))
        } }
        synchronize(remote, remote, budget, recheck)
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
