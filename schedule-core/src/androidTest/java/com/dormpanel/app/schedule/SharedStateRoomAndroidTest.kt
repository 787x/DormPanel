package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.Base64

class SharedStateRoomAndroidTest {
    @Test fun isolatedRoomWebDavInteropAndRollback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = File(context.filesDir,"pr69-probe.private.json")
        org.junit.Assume.assumeTrue("explicit isolated probe required",config.isFile)
        val c = JSONObject(config.readText()); val url=c.getString("url")
        require(Regex("/pr69-isolated-[0-9a-f-]{36}/$").containsMatchIn(url))
        val account=WebDavAccount(url,c.getString("username"),c.getString("password"),c.getString("certificatePem"))
        val writeRgb=c.optInt("writeRGB",0x654321)
        val root=File(context.filesDir,"pr69-room-${UUID.randomUUID()}")
        fun open()=V3RoomAdapter(root,{
            Room.databaseBuilder(context,V3RoomDatabase::class.java,File(root,"v3-state-v2.sqlite").absolutePath)
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        },{})
        var adapter=open();val writer=UUID.randomUUID().toString();var engine=V3Engine(adapter.journal,writer)
        fun remote(b:V3Budget)=V3WebDavStore(account,url,b,sharedFormat=true)
        fun settle(){repeat(4){val b=V3Budget();val r=remote(b);try{engine.round(r,r,V3IndexedCache(root,b),b,32)}catch(e:IllegalStateException){if(!e.message.orEmpty().contains("incomplete"))throw e}}}
        val binding=c.getJSONObject("binding");engine.bind(SyncV3Codec.decode(remote(V3Budget()).read(binding.getString("descriptorPath"))!!),binding.getString("descriptorHash"));settle()
        assertEquals(c.optInt("androidRGB",0xabcdef),SharedCourseColors.rgb(engine.local(),null,"x"))
        val before=engine.local();adapter.journal.meta.fault={if(it=="before-commit")error("injected rollback")}
        assertThrows(Exception::class.java){engine.setCourseColor(null,"x",0x654321)}
        adapter.journal.meta.fault=null;assertEquals(before.sharedRecords,engine.local().sharedRecords)
        engine.setCourseColor(null,"x",writeRgb);settle();adapter.close()
        adapter=open();engine=V3Engine(adapter.journal,writer)
        assertEquals(writeRgb,SharedCourseColors.rgb(engine.local(),null,"x"))
        assertEquals("x",adapter.database.dao().entries().single().id)
        assertEquals("opaque",engine.local().sharedRecords!!.first{it.typeId=="test.note"}.payload.toByteArray().toString(Charsets.UTF_8))
        File(context.filesDir,"pr69-result.json").writeText(JSONObject().put("hash",SyncV3Codec.businessHash(engine.local())).put("rgb",writeRgb).toString())
        adapter.close()
    }
}
