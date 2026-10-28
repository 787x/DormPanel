package com.dormpanel.phone

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import com.dormpanel.app.schedule.testing.V3UiFixture
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.*

class CourseMixedResetTest {
    private val c get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun <T> await(call:((Result<T>)->Unit)->Unit):T {val f=CompletableFuture<Result<T>>();call{f.complete(it)};return f.get(20,TimeUnit.SECONDS).getOrThrow()}
    private class Client(val c:android.content.Context,val name:String="pr65-final-${UUID.randomUUID()}.db") {
        var executor=Executors.newSingleThreadExecutor()
        fun database()=Room.databaseBuilder(c,ScheduleDatabase::class.java,name).build()
        var db=database();var fault:String?=null
        var store=RoomScheduleStore(db,executor,profiles=TermScheduleProfileStore(c),v3Fault={if(it==fault)error("injected $it")})
        fun close(){store.close();executor.shutdown();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS))}
        fun reopen(){close();fault=null;executor=Executors.newSingleThreadExecutor();db=database();store=RoomScheduleStore(db,executor,profiles=TermScheduleProfileStore(c))}
    }
    @Test fun v2MixedResetIsAtomicAndSurvivesPublishAndColdReader()=verify(false)
    @Test fun v3UnconfirmedMixedResetRecoversAndPropagatesWithoutResurrection()=verify(true)
    private fun verify(v3:Boolean) {
        require(c.packageName.endsWith(".testbed"))
        V3UiFixture(c).use {remote->
            val a=Client(c);val b=Client(c)
            val dav=WebDavCommitStore(remote.account,remote.account.baseUrl+"dormpanel-sync-v2/")
            fun settle(client:Client) {if(v3){repeat(32){if(client.store.synchronizeV3(remote.account)=="OBSERVED")return};error("did not settle")}else assertTrue(AppendOnlyScheduleSync(client.store,dav).synchronize().conflicts.isEmpty())}
            fun load(client:Client)=await<ScheduleState>(client.store::load)
            try {
                listOf("a","b","c").forEachIndexed{n,id->await<Unit>{a.store.put(TimetableEntry(id,"原课程$id",n+1,540,600),it)}}
                val oldA=ClassOverride.of(null,"a",null,ClassPatch(title="旧名称"))
                val oldB=ClassOverride.of(null,"b",null,ClassPatch(teacher="旧教师"))
                val weekly=ClassOverride.of(null,"a","2026-10-26",ClassPatch(location="指定周教室"))
                listOf(oldA,oldB,weekly).forEach{edit->await<Unit>{a.store.putClassOverride(edit,edit.key,it)}}
                if(v3) {
                    a.store.prepareV3(remote.account.baseUrl);settle(a)
                    val binding=a.store.v3Information()!!.getJSONObject("binding")
                    val descriptor=V3WebDavStore(remote.account,remote.account.baseUrl,V3Budget()).read(binding.getString("descriptorPath"))!!
                    b.store.prepareV3(remote.account.baseUrl,descriptor);settle(b)
                } else {
                    WebDavClient().makeCollection(remote.account,remote.account.baseUrl+"dormpanel-sync-v2/")
                    val state=ScheduleSnapshotCodec.decode(await<ByteArray>{a.store.exportSnapshot(UUID.randomUUID().toString(),null,1,it)}).state
                    val root=ScheduleCommit(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),emptyList(),state)
                    a.store.v2Start(root,false);val raw=ScheduleCommitCodec.encode(root);dav.publishWithIntent(raw,true,a.store::v2Attempt);a.store.v2Receipt(raw)
                    b.store.v2Start(root,true);settle(b)
                }
                val captured=load(a)
                val batch=listOf(oldA.copy(title=null),oldB.copy(teacher="新教师"),ClassOverride.of(null,"c",null,ClassPatch(note="")))
                if(v3) a.fault="before-commit" else a.executor.submit{a.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_c BEFORE INSERT ON class_overrides WHEN NEW.seriesId='c' BEGIN SELECT RAISE(ABORT,'injected'); END")}.get()
                val failure=runCatching{await<Unit>{a.store.putClassOverrides(captured,batch,it)}}.exceptionOrNull()
                assertNotNull(failure);assertFalse(failure is ScheduleCommitUnconfirmed)
                a.fault=null;assertEquals(captured.classOverrides,load(a).classOverrides)
                if(!v3)a.executor.submit{a.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_c")}.get()
                if(v3) {
                    val invalid=runCatching{await<Unit>{a.store.putClassOverrides(captured,listOf(oldA.copy(title="")),it)}}.exceptionOrNull()
                    assertNotNull(invalid);assertFalse(invalid is ScheduleCommitUnconfirmed)
                    a.fault="after-commit"
                    val uncertain=runCatching{await<Unit>{a.store.putClassOverrides(captured,batch,it)}}.exceptionOrNull()
                    assertTrue(uncertain is ScheduleCommitUnconfirmed);assertNotNull(uncertain!!.cause);assertTrue(a.store.businessPaused())
                    assertTrue(runCatching{await<Unit>{a.store.putClassOverrides(captured,batch,it)}}.isFailure)
                } else await<Unit>{a.store.putClassOverrides(captured,batch,it)}
                a.reopen();assertFalse(load(a).editsByKey.containsKey(oldA.key));settle(a);settle(b)
                b.reopen();settle(b)
                val received=load(b)
                assertFalse(received.editsByKey.containsKey(oldA.key));assertEquals(weekly,received.editsByKey[weekly.key])
                assertEquals("新教师",received.editsByKey[oldB.key]!!.teacher)
                assertEquals("",received.classOverrides.single{it.seriesId=="c"}.note)
                assertTrue(ScheduleMerge.same(load(a),received))
            }finally{a.close();b.close()}
        }
    }
}
