package com.dormpanel.app.schedule.testing

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.*

class CourseBatchProtectionTest {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val c get()=i.targetContext
    private fun <T> await(call:((Result<T>)->Unit)->Unit):T {
        val f=CompletableFuture<Result<T>>();call{f.complete(it)};return f.get(20,TimeUnit.SECONDS).getOrThrow()
    }
    @Test fun transactionRechecksMigrationAndV3InterruptedCommit() {
        require(c.packageName.endsWith(".testbed"))
        V3UiFixture(c).use { remote ->
            val name="pr65-protection-${UUID.randomUUID()}.db"
            var fault:String?=null
            var executor=Executors.newSingleThreadExecutor()
            fun database()=Room.databaseBuilder(c,ScheduleDatabase::class.java,name).build()
            var db=database()
            var store=RoomScheduleStore(db,executor,profiles=TermScheduleProfileStore(c),v3Fault={ if(it==fault)error("PR65 injected $it") })
            fun load()=await<ScheduleState>(store::load)
            val entries=listOf(TimetableEntry("a","课程",1,540,600,"A"),TimetableEntry("b","课程",2,660,720,"B"))
            try {
                entries.forEach { entry->await<Unit>{store.put(entry,it)} }
                val original=load();val edits=entries.map { ClassOverride.of(null,it.id,null,ClassPatch(title="批量新名称")) }
                executor.submit{db.dao().checkpoint(ScheduleSyncCheckpoint(200,byteArrayOf(1)))}.get()
                assertTrue(runCatching{await<Unit>{store.putClassOverrides(original,edits,it)}}.isFailure)
                assertTrue(load().classOverrides.isEmpty())
                executor.submit{db.dao().deleteSyncRecord(200)}.get()
                store.prepareV3(remote.account.baseUrl);repeat(16){store.synchronizeV3(remote.account)}
                val captured=load();fault="before-commit"
                assertTrue(runCatching{await<Unit>{store.putClassOverrides(captured,edits,it)}}.isFailure)
                fault=null;assertTrue(load().classOverrides.isEmpty())
                fault="after-commit"
                assertTrue(runCatching{await<Unit>{store.putClassOverrides(captured,edits,it)}}.isFailure)
                fault=null
                assertTrue(store.businessPaused())
                store.close();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS))
                executor=Executors.newSingleThreadExecutor();db=database()
                store=RoomScheduleStore(db,executor,profiles=TermScheduleProfileStore(c))
                val recovered=load();assertEquals(2,recovered.classOverrides.size)
                assertTrue(recovered.classOverrides.all{it.title=="批量新名称"})
                assertFalse(store.businessPaused())
                repeat(16){store.synchronizeV3(remote.account)}
                assertEquals(2,load().classOverrides.size)
            } finally {fault=null;store.close();executor.shutdown();executor.awaitTermination(20,TimeUnit.SECONDS)}
        }
    }
    @Test fun importReplacementAndDeletedSourceInvalidateOpenBatch() {
        require(c.packageName.endsWith(".testbed"))
        val name="pr65-replace-${UUID.randomUUID()}"
        val executor=Executors.newSingleThreadExecutor()
        val store=RoomScheduleStore(Room.databaseBuilder(c,ScheduleDatabase::class.java,"$name.db").build(),executor)
        try {
            val start=1792976400000L
            val rows=listOf("a","b").mapIndexed{n,id->ImportedClassOccurrence(id,"source",id,null,start+n*86400000L,"课程",start+n*86400000L,start+n*86400000L+3600000,"Asia/Shanghai","教室","",null)}
            val source=ImportSource("source","隔离替换","test.ics","ics",null,null,"0".repeat(64),0,"Asia/Shanghai",2,start,rows.last().end)
            await<ImportCommit>{store.commitImport(source,rows,false,it)}
            val captured=await<ScheduleState>(store::load)
            val batch=rows.map{ClassOverride.of("source",it.seriesId,null,ClassPatch(title="草稿"))}
            await<ImportCommit>{store.commitImport(source.copy(sha256="1".repeat(64),occurrenceCount=1,lastEnd=rows.first().end),rows.take(1),true,it)}
            assertTrue(runCatching{await<Unit>{store.putClassOverrides(captured,batch,it)}}.isFailure)
            val current=await<ScheduleState>(store::load)
            assertTrue(runCatching{await<Unit>{store.putClassOverrides(current,batch,it)}}.isFailure)
            assertTrue(await<ScheduleState>(store::load).classOverrides.isEmpty())
            await<Unit>{store.deleteImport("source",it)}
            assertTrue(runCatching{await<Unit>{store.putClassOverrides(current,batch,it)}}.isFailure)
            assertTrue(await<ScheduleState>(store::load).classOverrides.isEmpty())
        } finally {store.close();executor.shutdown();executor.awaitTermination(20,TimeUnit.SECONDS)}
    }
    @Test fun localReadFailureAndSnapshotApplicationRefuseBatchBeforeStore() {
        var fail=false;var writes=0;var snapshotCallback:((Result<Unit>)->Unit)?=null
        val state=ScheduleState(entries=listOf(TimetableEntry("a","课程",1,540,600)))
        val store=object:ScheduleStore {
            override fun load(callback:(Result<ScheduleState>)->Unit)=callback(if(fail)Result.failure(IllegalStateException("injected read")) else Result.success(state))
            override fun putClassOverrides(expected:ScheduleState,items:List<ClassOverride>,callback:(Result<Unit>)->Unit){writes++;callback(Result.success(Unit))}
            override fun applySnapshot(bytes:ByteArray,callback:(Result<Unit>)->Unit){snapshotCallback=callback}
            override fun put(event:CalendarEvent,callback:(Result<Unit>)->Unit)=error("unused")
            override fun put(entry:TimetableEntry,callback:(Result<Unit>)->Unit)=error("unused")
            override fun deleteEvent(id:String,callback:(Result<Unit>)->Unit)=error("unused")
            override fun deleteEntry(id:String,callback:(Result<Unit>)->Unit)=error("unused")
            override fun close()=Unit
        }
        i.runOnMainSync {
            val source=ScheduleSource(store);val edits=listOf(ClassOverride.of(null,"a",null,ClassPatch(title="新名称")))
            fail=true;source.reload();assertFalse(source.editable)
            source.saveClassOverrides(state,edits){assertTrue(it.isFailure)};assertEquals(0,writes)
            fail=false;source.reload();assertTrue(source.editable)
            source.applySnapshot(byteArrayOf(1)){}
            assertTrue(source.applyingSnapshot)
            source.saveClassOverrides(state,edits){assertTrue(it.isFailure)};assertEquals(0,writes)
            snapshotCallback!!(Result.success(Unit));assertTrue(source.editable);source.close()
        }
    }
}
