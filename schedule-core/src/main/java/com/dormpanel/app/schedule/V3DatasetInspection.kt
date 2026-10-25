package com.dormpanel.app.schedule

import java.io.File

data class VerifiedV3Snapshot(val descriptor: ByteArray, val snapshot: ByteArray, val folder: String,val readbackRound:String="") {
    val identity by lazy { SyncV3Codec.decode(descriptor) }
    val value by lazy { SyncV3Codec.decode(snapshot) }
    val hash get() = SyncV3Codec.hash(snapshot)
}
data class ExistingV3MigrationPreview(val source: ScheduleV3MigrationPreview, val target: VerifiedV3Snapshot)
data class UnboundV3JoinPreview(val original:ScheduleState,val target:VerifiedV3Snapshot)

/** Read-only protocol validation, with durable discovery/index progress in a private staging
 * directory. It has no publication port and is never returned by ScheduleStores. */
object V3DatasetInspection {
    fun inspect(directory: File, writerId: String, remote: V3ObjectStore, budget: V3Budget,
        descriptor: ByteArray, folder: String): VerifiedV3Snapshot? {
        val d=SyncV3Codec.decode(descriptor); require(d.type==1)
        V3Journal(directory).use { journal ->
            val engine=V3Engine(journal,writerId)
            if(!journal.snapshot().has("binding")) engine.bind(d,SyncV3Codec.hash(descriptor))
            val b=journal.snapshot().getJSONObject("binding")
            check(b.getString("descriptorHash")==SyncV3Codec.hash(descriptor) && b.getString("datasetId")==d.datasetId) { "预检目标身份变化" }
            journal.meta.budget=budget
            try {
                val cache=V3IndexedCache(directory,budget)
                val discovery=V3Discovery(journal,remote,d.datasetId); discovery.scan(32)
                val history=V3History(journal,cache,remote,V3Paths.path(d),SyncV3Codec.hash(descriptor),budget).open()
                history.activeEpochs().takeLast(8).forEach { epoch -> val scope=V3Paths.epoch(d.datasetId,epoch); discovery.observe(scope,remote.list(scope)) }
                repeat(32) { journal.meta.page("todo",0,1).firstOrNull()?.let { history.verify(it.key) } }
                history.drainActivation()
                if(journal.meta.get("control","initialComplete")==null || journal.meta.page("todo",0,1).isNotEmpty()) return null
                check(journal.meta.page("error",0,1).isEmpty()) { "目标存在未决历史异常" }
                val tips=history.tips(); check(tips.size==1) { "目标存在并发前沿；请由活动 v3 Writer 合并或解决冲突后再加入" }
                val path=tips.single(); val raw=requireNotNull(remote.read(path)) { "目标前沿快照缺失" }
                V3Paths.match(path,raw)
                check(SyncV3Codec.hash(raw)==history.node(path).getString("hash")) { "目标快照身份变化" }
                check(remote.read(V3Paths.path(d))?.contentEquals(descriptor)==true) { "目标 Descriptor 身份变化" }
                return VerifiedV3Snapshot(descriptor.copyOf(),raw,folder,budget.roundId)
            } catch(e:IllegalStateException) {
                if(e.message.orEmpty().contains("budget")) return null
                throw e
            }
        }
    }
}
