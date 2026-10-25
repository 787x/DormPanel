package com.dormpanel.app.schedule

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only protocol task, never an engine/Writer. Its private persisted graph
 * is separate from the live frontier; every required source object is GET checked. */
internal object V3EndpointInspection {
    fun step(directory: File, source: V3Journal, job: JSONObject, remote: V3ObjectStore, budget: V3Budget): JSONObject {
        val binding=job.getJSONObject("binding")
        job.put("status","VALIDATING").put("knownHistoryComplete",false).put("descriptorExact",false).put("networkAndAuthenticationVerified",false);job.remove("failure")
        val descriptor=requireNotNull(remote.read(binding.getString("descriptorPath"))) { "候选 Descriptor 缺失" }
        job.put("networkAndAuthenticationVerified",true)
        check(SyncV3Codec.hash(descriptor)==binding.getString("descriptorHash") && V3Paths.match(binding.getString("descriptorPath"),descriptor).datasetId==binding.getString("datasetId")) { "候选 Descriptor 完整 RAW 身份变化" }
        job.put("descriptorExact",true).put("networkAndAuthenticationVerified",true).put("status","VALIDATING")
        V3Journal(directory).use { candidate ->
            val meta=candidate.meta;meta.budget=budget
            val cache=V3IndexedCache(source.directory,budget,requireRemoteReadback=true)
            try {
                val rows=source.meta.localOperation { source.meta.page("node",job.optLong("after"),16,job.getLong("ceiling")) }
                for(row in rows) {
                    val raw=requireNotNull(remote.read(row.key)) { "新端点已知对象缺失" }
                    check(SyncV3Codec.hash(raw)==row.value.getString("hash")) { "新端点同路径哈希变化" }
                    cache.put(row.key,raw);meta.atomic { meta.put("todo",row.key,JSONObject().put("path",row.key)) }
                    job.put("after",row.seq)
                    source.meta.localOperation { source.meta.atomic { source.meta.put("endpointJob",job.getString("token"),job) } }
                }
                val observed=source.meta.localOperation { source.meta.page("observed",job.optLong("observedAfter"),16,job.getLong("observedCeiling")) }
                for(row in observed) {
                    meta.atomic { meta.put("todo",row.key,JSONObject().put("path",row.key)) };job.put("observedAfter",row.seq)
                }
                val history=V3History(candidate,cache,remote,binding.getString("descriptorPath"),binding.getString("descriptorHash"),budget).open()
                V3Discovery(candidate,remote,binding.getString("datasetId")).scan(32)
                repeat(16) { meta.page("todo",0,1).firstOrNull()?.let { history.verify(it.key) } }
                history.drainActivation()
                val allKnown=source.meta.localOperation { source.meta.page("node",job.optLong("after"),1,job.getLong("ceiling")).isEmpty() && source.meta.page("observed",job.optLong("observedAfter"),1,job.getLong("observedCeiling")).isEmpty() }
                if(allKnown && meta.count("scan")==0L && meta.count("todo")==0L && meta.count("error")==0L && meta.get("control","walk")==null) {
                    job.put("status","READY").put("knownHistoryComplete",true).put("readyAt",System.currentTimeMillis())
                }
                job.put("candidateNodes",meta.lastSeq("node")).put("frontier",JSONArray(history.tips()))
            } catch(e:IllegalStateException) { if(!e.message.orEmpty().contains("budget"))throw e }
        }
        return job
    }
    fun recheck(directory:File,job:JSONObject,remote:V3ObjectStore) {
        V3Journal(directory).use { candidate ->
            val binding=job.getJSONObject("binding");val tips=job.getJSONArray("frontier").strings();check(tips.isNotEmpty()) { "候选没有已验证前沿" }
            val epochs=linkedSetOf<String>()
            for(path in tips) {
                val node=requireNotNull(candidate.meta.get("node",path));val raw=requireNotNull(remote.read(path)) { "候选前沿缺失" }
                check(SyncV3Codec.hash(raw)==node.getString("hash")) { "候选前沿身份变化" };epochs.add(node.getString("epochId"))
            }
            for(epoch in epochs) for(path in remote.list(V3Paths.epoch(binding.getString("datasetId"),epoch)))check(candidate.meta.get("node",path)!=null) { "候选新增未知历史，请继续验证" }
        }
    }
}
