package com.dormpanel.app.schedule.testing

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.time.*
import java.util.concurrent.*

/** Only explicit, random PR65 DAV directory and isolated applicationId. No daily bindings. */
class CourseBatchNetworkTest {
    private fun <T> await(call: ((Result<T>) -> Unit) -> Unit): T {
        val f=CompletableFuture<Result<T>>();call { f.complete(it) };return f.get(30,TimeUnit.SECONDS).getOrThrow()
    }
    @Test fun realThreeEndpointRoundTrip() {
        val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
        require(c.packageName.endsWith(".testbed"))
        val config=JSONObject(File(c.filesDir,"pr65-network.json").readText())
        val url=config.getString("url"); require(Regex("/pr65-(v2|v3)-[0-9a-f-]{36}/$").containsMatchIn(url))
        val phase=config.getString("phase");val proto=config.getString("protocol")
        val account=WebDavAccount(url,config.getString("username"),config.getString("password"))
        val name="pr65-net-${config.getString("run")}-${proto}"
        TermScheduleProfileStore.overrideNamespace=name
        val executor=Executors.newSingleThreadExecutor()
        val store=RoomScheduleStore(Room.databaseBuilder(c,ScheduleDatabase::class.java,"$name.db").build(),executor,profiles=TermScheduleProfileStore(c))
        val remote=WebDavCommitStore(account,url+"dormpanel-sync-v2/")
        fun settle() {
            if(proto=="v3") { repeat(32) { if(store.synchronizeV3(account)=="OBSERVED")return };error("v3 did not settle") }
            else { repeat(2) { assertTrue(AppendOnlyScheduleSync(store,remote).synchronize().conflicts.isEmpty()) } }
        }
        try {
            if(phase=="seed") {
                val zone=ZoneId.systemDefault();val start=LocalDate.of(2026,10,26).atTime(9,0).atZone(zone).toInstant().toEpochMilli()
                val rows=listOf(0,8,14).mapIndexed { n,d -> ImportedClassOccurrence("pr65-$n","pr65-source",if(n==1)"b" else "a",null,start+d*86400000L,"网络课程",start+d*86400000L,start+d*86400000L+3600000L,zone.id,"原教室$n","",null) }
                val source=ImportSource("pr65-source","PR65网络隔离","pr65.ics","ics",null,null,"0".repeat(64),0,zone.id,3,start,rows.last().end)
                await<ImportCommit> { store.commitImport(source,rows,false,it) }
                val edits=listOf("a","b").map { ClassOverride.of(source.id,it,null,ClassPatch(teacher=if(it=="a")"甲" else "乙",note=if(it=="a")"" else "原备注")) }
                await<Unit> { store.putClassOverrides(await(store::load),edits,it) }
                await<Unit> { store.putClassOverride(ClassOverride.of(source.id,"a","2026-10-26",ClassPatch(location="指定周教室",teacher="例外教师")),ClassOverride.key(source.id,"a","2026-10-26"),it) }
                if(proto=="v3")store.prepareV3(url) else {
                    WebDavClient().makeCollection(account,url+"dormpanel-sync-v2/")
                    val root=ScheduleCommit(config.getString("run"),config.getString("run"),java.util.UUID.randomUUID().toString(),emptyList(),ScheduleSnapshotCodec.decode(await<ByteArray>{store.exportSnapshot(config.getString("run"),null,1,it)}).state)
                    store.v2Start(root,false)
                    val raw=ScheduleCommitCodec.encode(root)
                    remote.publishWithIntent(raw,true,store::v2Attempt);store.v2Receipt(raw)
                }
            } else if(proto=="v3" && !store.isV3()) {
                val binding=config.getJSONObject("binding")
                val descriptor=requireNotNull(V3WebDavStore(account,url,V3Budget()).read(binding.getString("descriptorPath")))
                assertEquals(binding.getString("descriptorHash"),SyncV3Codec.hash(descriptor));store.prepareV3(url,descriptor)
            } else if(proto=="v2" && store.v2Frame().baseline==null) {
                val root=ScheduleCommitCodec.decode(requireNotNull(remote.read(config.getString("run"))))
                store.v2Start(root,true)
            }
            settle()
            if(phase=="phone-edit") {
                val state=await<ScheduleState>(store::load)
                val batch=state.classOverrides.filter { it.anchorDate==null }.map { it.copy(title="PR65整门课程",teacher="统一教师") }
                await<Unit> { store.putClassOverrides(state,batch,it) };settle()
            }
            if(phase=="x08e-edit") {
                val old=await<ScheduleState>(store::load).classOverrides.single { it.seriesId=="a" && it.anchorDate==null }
                await<Unit> { store.putClassOverride(old.copy(location="X08E教室"),old.key,it) };settle()
            }
            val state=await<ScheduleState>(store::load)
            if(phase!="seed") {
                assertEquals(2,state.classOverrides.count { it.anchorDate==null && it.title=="PR65整门课程" && it.teacher=="统一教师" })
                assertEquals("指定周教室",state.classOverrides.single { it.anchorDate!=null }.location)
            }
            if(phase=="final") {
                assertEquals("X08E教室",state.classOverrides.single { it.seriesId=="a" && it.anchorDate==null }.location)
                assertEquals("fnOS备注",state.classOverrides.single { it.seriesId=="b" && it.anchorDate==null }.note)
            }
            val report=JSONObject().put("phase",phase).put("protocol",proto).put("rawHash",SyncV3Codec.hash(SyncV3Codec.stateBytes(state.copy(sync=null))))
            if(proto=="v3")report.put("binding",store.v3Information()!!.getJSONObject("binding"))
            File(c.getExternalFilesDir(null),"pr65-network-$proto.json").writeText(report.toString())
        } finally {store.close();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));TermScheduleProfileStore.overrideNamespace=null}
    }
}
