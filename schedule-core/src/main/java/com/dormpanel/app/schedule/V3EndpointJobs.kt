package com.dormpanel.app.schedule

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Private task GC only. Immutable shared cache and RAW snapshots are never deleted.
 * Legacy per-candidate caches stay in place, registered as protected space. New
 * inspections use the shared cache, so cancellation cannot accumulate copies. */
internal class V3EndpointJobs(private val journal:V3Journal) {
    companion object { const val TTL=24*60*60*1000L }
    private val meta get()=journal.meta
    var fault:((String)->Unit)?=null
    fun find(token:String):JSONObject = meta.localOperation {
        ScheduleCommitCodec.uuid(token)
        requireNotNull(meta.get("endpointReceipt",token)?:meta.get("endpointJob",token)?:meta.get("endpointRetired",token)) { "预检已回收或不存在" }
    }
    fun terminal(j:JSONObject,now:Long=System.currentTimeMillis()):String?=when(j.optString("status")) {
        "CLEANING"->j.getString("terminalStatus")
        "COMMITTED","CANCELLED","STALE","EXPIRED"->j.getString("status")
        else->if(j.has("expiresAt")&&now>=j.getLong("expiresAt"))"EXPIRED" else null
    }
    private fun save(j:JSONObject)=meta.localOperation { meta.atomic { meta.put("endpointJob",j.getString("token"),j) } }
    fun directory(token:String):File { ScheduleCommitCodec.uuid(token);return File(journal.directory,"endpoint-$token") }
    fun reap(now:Long=System.currentTimeMillis(),fingerprint:String?=null)=synchronized(journal.networkGate) {
        meta.localOperation {
            for(row in meta.page("endpointJob",0,128)) {
                val j=row.value;check(j.getString("token")==row.key) { "预检归属异常" }
                check(j.optString("status") in listOf("VALIDATING","READY","COMMITTED","CANCELLED","STALE","EXPIRED","CLEANING")) { "未知预检状态，停止清理" }
                if(j.optString("status")=="CLEANING")check(j.optString("terminalStatus") in listOf("COMMITTED","CANCELLED","STALE","EXPIRED")) { "未知清理决定" }
                if(!j.has("expiresAt")&&terminal(j,now)==null){j.put("createdAt",now).put("expiresAt",now+TTL);save(j)} // legacy active tasks get a full grace period
                val end=terminal(j,now)?:if(fingerprint!=null&&j.has("fingerprint")&&j.getString("fingerprint")!=fingerprint)"STALE" else continue
                if(j.optString("status")!="CLEANING") {
                    meta.atomic {
                        if(end=="COMMITTED") {
                            val receipt=JSONObject(j.toString());listOf("endpointAccount","fingerprint","frontier","candidateNodes","folder","endpointKey").forEach(receipt::remove)
                            meta.put("endpointReceipt",row.key,receipt)
                        }
                        val clean=JSONObject().put("token",row.key).put("createdAt",j.optLong("createdAt",now)).put("status","CLEANING").put("terminalStatus",end).put("cleanupPhase","GRAPH")
                        meta.put("endpointJob",row.key,clean)
                    };fault?.invoke("marked")
                }
            }
            // Bounded work per user-triggered call; repeated calls resume without a timer.
            for(row in meta.page("endpointJob",0,128).filter { it.value.optString("status")=="CLEANING" }.take(2)) {
                val j=row.value;if(clean(j))meta.atomic {
                    meta.put("endpointRetired",row.key,JSONObject().put("token",row.key).put("status",j.getString("terminalStatus")).put("createdAt",j.optLong("createdAt")).put("protectedCacheBytes",j.optLong("cacheBytes")))
                    meta.remove("endpointJob",row.key)
                    val count=meta.count("endpointRetired");if(count>32)meta.page("endpointRetired",0,minOf(128,(count-32).toInt())).forEach { meta.remove("endpointRetired",it.key) }
                }
            }
        }
    }
    private fun real(file:File,directory:Boolean) {
        check(!Files.isSymbolicLink(file.toPath()) && if(directory)file.isDirectory else file.isFile) { "候选包含异常文件或链接；停止清理" }
    }
    private val dbFiles=setOf("v3-owner.lock","v3-state-v2.sqlite","v3-state-v2.sqlite-wal","v3-state-v2.sqlite-shm")
    private fun root(dir:File) {
        real(dir,true);val files=requireNotNull(dir.listFiles());check(files.size<=5)
        for(f in files)if(f.name=="sync-v3-index-v2")real(f,true) else {check(f.name in dbFiles) { "未知候选文件，停止清理" };real(f,false)}
    }
    private fun graphKind(ns:String)=ns in setOf("format","node","identity","epoch","frontier","covered","observed","scope","scan","todo","listing","activate","control","error","evidence","failedWalk","proof") ||
        Regex("(?:members:[0-9a-f-]{36}|epochFrontier:[0-9a-f-]{36}(?::covered)?|proof:[0-9a-f]{64}:(?:queue|seen|tips|covered)|(?:stack|visiting):[0-9a-f-]{36})").matches(ns)
    private fun noRaw(value:Any?):Boolean=when(value){is JSONObject->!value.has("\$v3Snapshot")&&value.keys().asSequence().all { noRaw(value.opt(it)) };is JSONArray->(0 until value.length()).all { noRaw(value.opt(it)) };else->true}
    private fun clean(j:JSONObject):Boolean {
        check(j.optString("cleanupPhase") in listOf("GRAPH","CACHE","FILES")) { "未知清理阶段，停止清理" }
        val dir=directory(j.getString("token"));if(!dir.exists()){check(!Files.isSymbolicLink(dir.toPath()));return true};root(dir)
        if(j.optString("cleanupPhase")=="GRAPH") {
            real(File(dir,"v3-state-v2.sqlite"),false);real(File(dir,"v3-owner.lock"),false)
            V3Journal(dir,requireExisting=true).use { candidate ->
                candidate.meta.budget=V3Budget(maxRequests=0)
                val rows=candidate.meta.rawRecords(j.optLong("graphAfter"),128)
                for(r in rows)check(graphKind(r.namespace)&&noRaw(r.value)) { "未知候选图或 RAW 引用，停止清理" }
                j.put("graphAfter",rows.lastOrNull()?.seq?:j.optLong("graphAfter"))
                if(candidate.meta.rawRecords(j.optLong("graphAfter"),1).isEmpty())j.put("cleanupPhase","CACHE")
            };save(j);fault?.invoke("graph");if(j.getString("cleanupPhase")=="GRAPH")return false
        }
        if(j.getString("cleanupPhase")=="CACHE") {
            if(!legacyCache(dir,j))return false
            j.put("cleanupPhase","FILES");save(j);fault?.invoke("files-ready")
        }
        // The durable FILES phase is the ownership proof after a partial unlink.
        root(dir)
        for(name in listOf("v3-state-v2.sqlite-wal","v3-state-v2.sqlite-shm","v3-owner.lock","v3-state-v2.sqlite")) {
            val f=File(dir,name);if(f.exists()){real(f,false);check(f.delete());V3Atomic.sync(dir);fault?.invoke("file")}
        }
        if(File(dir,"sync-v3-index-v2").exists())meta.atomic { meta.put("endpointProtectedCache",j.getString("token"),JSONObject().put("relative",dir.name+"/sync-v3-index-v2").put("bytes",j.optLong("cacheBytes")).put("reason","LEGACY_IMMUTABLE_CACHE_RETAINED")) }
        else {check(dir.delete());V3Atomic.sync(journal.directory)}
        return true
    }
    private fun legacyCache(dir:File,j:JSONObject):Boolean {
        val cacheDir=File(dir,"sync-v3-index-v2");if(!cacheDir.exists())return true
        var stack=j.optJSONArray("cacheStack")?:JSONArray().put(JSONObject().put("relative","").put("after",""))
        val cache=V3IndexedCache(dir,V3Budget(maxRequests=0))
        try { repeat(64) {
            if(stack.length()==0){save(j);return true}
            val frame=stack.getJSONObject(stack.length()-1);val rel=frame.getString("relative");val current=if(rel.isEmpty())cacheDir else File(cacheDir,rel)
            real(current,true);val children=requireNotNull(current.listFiles());check(children.size<=256)
            val next=children.filter { it.name>frame.getString("after") }.minByOrNull { it.name }
            if(next==null) { stack=JSONArray((0 until stack.length()-1).map { stack.getJSONObject(it) });j.put("cacheStack",stack);return@repeat }
            val depth=if(rel.isEmpty())0 else rel.split('/').size
            if(depth<3) {
                check(Regex(if(depth<2)"[0-9a-f]{2}" else "[0-9a-f]{60}").matches(next.name)) { "未知候选缓存目录" };real(next,true)
                frame.put("after",next.name);stack.put(JSONObject().put("relative",if(rel.isEmpty())next.name else "$rel/${next.name}").put("after",""))
            } else {
                real(next,false);check(next.name in setOf("witness.json","object.dpv")||Regex("damaged-[0-9a-f-]{36}\\.dpv").matches(next.name)) { "未知候选缓存文件" }
                val w=JSONObject(V3Atomic.read(File(current,"witness.json"),32768).toString(Charsets.UTF_8));val path=w.getString("path")
                check(cache.blob(path).parentFile==current);val witness=requireNotNull(cache.witness(path));check(witness.getJSONArray("pins").length()<=128)
                if(next.name=="object.dpv")requireNotNull(cache.get(path))
                j.put("cacheBytes",j.optLong("cacheBytes")+next.length())
                frame.put("after",next.name)
            };j.put("cacheStack",stack)
        } } finally { j.put("cacheStack",stack);save(j) };return false
    }
}
