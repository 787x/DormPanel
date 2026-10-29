package com.dormpanel.app.schedule.testing

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Explicit, isolated NAS probe. Never opens the product Room or daily binding. */
class Pr71NetworkTest {
 @Test fun isolatedNetwork() {
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  require(c.packageName.endsWith(".testbed"))
  val config=File(c.getExternalFilesDir(null),"pr71-probe.private.json")
  require(config.isFile)
  val p=JSONObject(config.readText());val url=p.getString("url")
  require(Regex("/test/pr71-isolated-[0-9a-f-]{36}/$").containsMatchIn(url))
  val account=WebDavAccount(url,p.getString("username"),p.getString("password"),p.getString("certificatePem"))
  val root=File(c.filesDir,"pr71-room-"+p.getString("name"))
  V3RoomAdapter(root,{Room.databaseBuilder(c,V3RoomDatabase::class.java,File(root,"v3-state-v2.sqlite").absolutePath).setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()},{}).use { adapter ->
   val writerFile=File(root,"writer.txt");if(!writerFile.exists())writerFile.writeText(UUID.randomUUID().toString())
   val engine=V3Engine(adapter.journal,writerFile.readText())
   fun remote(b:V3Budget)=V3WebDavStore(account,url,b,sharedFormat=true)
   if(p.getString("mode")=="seed"){
    val initial=ScheduleState(entries=listOf(TimetableEntry("x","PR71 网络课程",1,480,540,"原教室")),sharedRecords=emptyList())
    if(!adapter.journal.snapshot().has("binding"))engine.initialize(remote(V3Budget()),initial)
    val a=CourseSlot("slot-a",null,"x","x","2026-09-07",listOf(1,3,5),1,480,540,0,0,true,"PR71 网络课程","A","教师","备注")
    val b=a.copy(id="slot-b",replacesSeries=null,weekday=3,location="B")
    engine.edit(SharedCourseColors.change(engine.local().copy(sharedRecords=listOf(SharedCourseSchedule.record(a),SharedCourseSchedule.record(b))),null,"x",0x336699))
   } else if(!adapter.journal.snapshot().has("binding")){
    val binding=p.getJSONObject("binding");engine.bind(SyncV3Codec.decode(remote(V3Budget()).read(binding.getString("descriptorPath"))!!),binding.getString("descriptorHash"))
   }
   if(!p.optBoolean("projectionOnly"))repeat(16){val budget=V3Budget();val r=remote(budget);try{engine.round(r,r,V3IndexedCache(root,budget),budget,32)}catch(e:IllegalStateException){if(!e.message.orEmpty().contains("incomplete"))throw e}}
   if(p.optBoolean("addException")){val old=engine.local();engine.edit(old.copy(classOverrides=old.classOverrides.filterNot{it.anchorDate=="2026-10-05"}+ClassOverride.of(null,"x","2026-10-05",ClassPatch(note="保留指定周例外"))));repeat(8){val b=V3Budget();val r=remote(b);try{engine.round(r,r,V3IndexedCache(root,b),b,32)}catch(e:IllegalStateException){if(!e.message.orEmpty().contains("incomplete"))throw e}}}
   val state=engine.local();assertEquals(0x336699,SharedCourseColors.rgb(state,null,"x"))
   val slots=state.sharedRecords!!.filter{it.typeId=="course.schedule"}.map{SharedCourseSchedule.decode(it)}
   assertEquals(2,slots.size)
   if(p.getString("mode")!="seed"){assertEquals("fnOS 新教室",slots.single{it.id=="slot-a"}.location);assertEquals(listOf(1,5),slots.single{it.id=="slot-a"}.weeks);assertEquals("B",slots.single{it.id=="slot-b"}.location)}
   if(p.has("expectedHash"))assertEquals(p.getString("expectedHash"),SyncV3Codec.businessHash(state))
   if(p.optBoolean("expectException"))assertTrue(state.classOverrides.any{it.anchorDate=="2026-10-05"&&it.note=="保留指定周例外"})
   File(c.getExternalFilesDir(null),"pr71-network-state.dpv").writeBytes(SyncV3Codec.encode(V3Object(2,UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),epochId=UUID.randomUUID().toString(),epochNumber=1,descriptorHash="1".repeat(64),state=state)))
   if(p.has("expectedProjection")){val expected=p.getJSONArray("expectedProjection");for(n in 0 until expected.length()){val w=expected.getJSONObject(n);val actual=ScheduleProjection.week(state,java.time.LocalDate.parse(w.getString("week")),java.time.ZoneId.of("Asia/Shanghai")).sortedWith(compareBy<ClassOccurrence>{it.start}.thenBy{it.entry.id});val rows=w.getJSONArray("rows");assertEquals(rows.length(),actual.size);for(j in actual.indices){val a=actual[j];val e=rows.getJSONObject(j);val fields=mapOf("id" to a.entry.id,"seriesId" to a.seriesId,"sourceId" to a.sourceId,"date" to a.date.toString(),"academicDate" to a.academicDate.toString(),"anchorDate" to a.anchorDate.toString(),"start" to a.start.toEpochMilli(),"end" to a.end.toEpochMilli(),"title" to a.entry.title,"location" to a.entry.location,"teacher" to a.teacher,"note" to a.note,"periodLabel" to a.periodLabel,"sharedRgb" to a.sharedRgb);for((k,v)in fields){if(v==null)assertTrue(k,e.isNull(k))else assertEquals(k,e.get(k).toString(),v.toString())}}}}
   val result=JSONObject().put("hash",SyncV3Codec.businessHash(state)).put("binding",adapter.journal.snapshot().getJSONObject("binding")).put("slots",slots.size)
   File(c.getExternalFilesDir(null),"pr71-result.json").writeText(result.toString())
  }
 }
}
