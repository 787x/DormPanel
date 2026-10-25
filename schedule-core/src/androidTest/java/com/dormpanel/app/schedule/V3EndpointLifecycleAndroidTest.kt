package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** One native owner, unique Room file, no network or daily configuration. */
class V3EndpointLifecycleAndroidTest {
    private class Remote:V3ObjectStore,V3PublicationStore {
        val objects=linkedMapOf<String,ByteArray>();var puts=0
        override fun read(path:String)=objects[path]
        override fun list(scope:String)=objects.keys.filter { it.startsWith(scope) }.map { p->val tail=p.removePrefix(scope);scope+if('/' in tail)tail.substringBefore('/')+"/" else tail }.distinct().sorted()
        override fun prepare(scope:String) {}
        override fun putOnce(path:String,raw:ByteArray){check(path !in objects);objects[path]=raw.copyOf();puts++}
        override fun createForIsolatedTest(path:String,bytes:ByteArray,beforeWriteIntent:()->Unit)=error("no test primitive")
    }
    @Test fun seventyCancelledRoomTasksThenVerifyCommitReopenAndRepeat() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.dormpanel.schedule.core.test")
        val name="pr57-lifecycle-${UUID.randomUUID()}.db";WebDavSettings.overrideNamespace=name;val settings=WebDavSettings(context)
        var db=Room.databaseBuilder(context,ScheduleDatabase::class.java,name).build();var runtime=ActiveScheduleV3(db){};val remote=Remote()
        val account=WebDavAccount("https://old.invalid/account/","old","old-secret");val next=WebDavAccount("https://new.invalid/account/","new","new-secret")
        val folder="https://old.invalid/shared/course/";val target="https://new.invalid/shared/course/"
        settings.saveAccount(account)
        try {
            runtime.prepare(ScheduleState(entries=listOf(TimetableEntry("saved","retained",1,480,540,""))),folder)
            repeat(6){runtime.synchronize(remote,remote,V3Budget())}
            val before=runtime.journal.snapshot().toString();val puts=remote.puts
            repeat(70){
                val j=runtime.endpointStart(next,target,settings.sealEndpoint(next));val token=j.getString("token")
                // Simulate an interrupted bounded validation graph without 70 network walks.
                V3Journal(File(runtime.directory,"endpoint-$token")).use { c->c.meta.atomic { c.meta.put("control","walk",JSONObject().put("root","private inspection")) } }
                assertEquals("CANCELLED",runtime.endpointCancel(token).getString("status"))
            }
            assertEquals(0L,runtime.journal.meta.count("endpointJob"));assertEquals(32L,runtime.journal.meta.count("endpointRetired"));assertEquals(before,runtime.journal.snapshot().toString())
            var j=runtime.endpointStart(next,target,settings.sealEndpoint(next));val token=j.getString("token")
            repeat(16){if(j.optString("status")!="READY")j=runtime.endpointContinue(token,next,V3Budget(),remote)}
            assertEquals("READY",j.getString("status"));val result=runtime.endpointConfirm(token,next,remote)
            assertEquals("COMMITTED",result.getString("status"));runtime.reapEndpoints();assertEquals(0L,runtime.journal.meta.count("endpointJob"));assertEquals(puts,remote.puts)
            runtime.close();db.close();db=Room.databaseBuilder(context,ScheduleDatabase::class.java,name).build();runtime=ActiveScheduleV3(db){}
            assertEquals("COMMITTED",runtime.endpointConfirm(token,next,remote).getString("status"));assertEquals(target,runtime.endpointFolder());assertEquals(next.username,runtime.endpointAccount(settings){error("must use independent account")}.username)
            assertEquals(before,runtime.journal.snapshot().toString());assertEquals(account.baseUrl,settings.account()!!.baseUrl)
        } finally { runtime.close();db.close();WebDavSettings.overrideNamespace=null }
    }
}
