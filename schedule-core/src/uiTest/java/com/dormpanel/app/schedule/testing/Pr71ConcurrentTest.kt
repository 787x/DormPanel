package com.dormpanel.app.schedule.testing
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
class Pr71ConcurrentTest {
 @Test fun isolatedPhoneBranch(){
  val c=InstrumentationRegistry.getInstrumentation().targetContext;require(c.packageName.endsWith(".testbed"))
  val p=JSONObject(File(c.getExternalFilesDir(null),"pr71-concurrent.private.json").readText());val url=p.getString("url");require(Regex("/test/pr71-isolated-[0-9a-f-]{36}/$").containsMatchIn(url))
  val account=WebDavAccount(url,p.getString("username"),p.getString("password"),p.getString("certificatePem"));val name=p.getString("case");require(name.matches(Regex("[a-z-]+")))
  val root=File(c.filesDir,"pr71-concurrent-"+p.getJSONObject("binding").getString("datasetId"))
  V3RoomAdapter(root,{Room.databaseBuilder(c,V3RoomDatabase::class.java,File(root,"v3-state-v2.sqlite").absolutePath).setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()},{}).use {adapter->
   val writer=File(root,"writer.txt");if(!writer.exists())writer.writeText(UUID.randomUUID().toString());val engine=V3Engine(adapter.journal,writer.readText())
   fun remote(b:V3Budget)=V3WebDavStore(account,url,b,sharedFormat=true)
   fun rounds(){repeat(6){val b=V3Budget();val r=remote(b);try{engine.round(r,r,V3IndexedCache(root,b),b,32)}catch(e:IllegalStateException){if(!e.message.orEmpty().contains("incomplete"))throw e}}}
   if(p.getString("mode")=="prepare"){
    val binding=p.getJSONObject("binding");engine.bind(SyncV3Codec.decode(remote(V3Budget()).read(binding.getString("descriptorPath"))!!),binding.getString("descriptorHash"));rounds();val state=engine.local()
    when(name){
     "different-courses"->engine.edit(state.copy(classOverrides=state.classOverrides+ClassOverride.of(null,"y",null,ClassPatch(teacher="Phone"))))
     "teacher-color"->engine.setCourseColor(null,"x",0x123456)
     "color-conflict"->engine.setCourseColor(null,"x",0x222222)
     "slot-delete-edit","source-reference"->{val slot=SharedCourseSchedule.slots(state).single();engine.edit(state.copy(sharedRecords=state.sharedRecords!!.filterNot{it.typeId=="course.schedule"}+SharedCourseSchedule.record(slot.copy(location="Phone"))))}
     else->error("unsupported case")
    }
   }else{
    rounds();val conflict=adapter.journal.snapshot().optJSONObject("conflict");assertEquals(p.getBoolean("expectConflict"),conflict!=null)
    if(conflict!=null){engine.resolve(conflict.getString("id"),ScheduleMerge.Choice.LOCAL);rounds()}
   }
   File(c.getExternalFilesDir(null),"pr71-concurrent-result.json").writeText(JSONObject().put("hash",SyncV3Codec.businessHash(engine.local())).put("case",name).toString())
  }
 }
}
