package com.dormpanel.app.schedule

import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

class SharedStateWebDavTest {
    @Test fun isolatedKotlinNodeRoundTrip() {
        val path = System.getenv("DORMPANEL_SHARED_PROBE"); assumeTrue(path != null)
        val configFile = File(path!!); val c = JSONObject(configFile.readText())
        val url = c.getString("url"); require(Regex("/pr69-isolated-[0-9a-f-]{36}/$").containsMatchIn(url))
        val account = WebDavAccount(url, c.getString("username"), c.getString("password"), c.getString("certificatePem"))
        val root = File(c.getString("kotlinRoot")); val j = V3Journal(root)
        val writer = j.snapshot().optString("writerId").ifEmpty { UUID.randomUUID().toString() }; val engine = V3Engine(j, writer)
        fun remote(b: V3Budget) = V3WebDavStore(account, url, b, sharedFormat = true)
        fun settle() { repeat(4) { val b = V3Budget(); val r = remote(b); try { engine.round(r,r,V3IndexedCache(root,b),b,32) } catch (e: IllegalStateException) { if (!e.message.orEmpty().contains("incomplete")) throw e } } }
        if (c.getString("phase") == "seed") {
            val initial = ScheduleState(entries = listOf(TimetableEntry("x", "课程", 1,480,540)), sharedRecords = listOf(
                SharedRecord("test.note","one",1,payload="opaque".toByteArray().toList())))
            val binding = if (j.snapshot().has("binding")) j.snapshot().getJSONObject("binding") else engine.initialize(remote(V3Budget()), initial); c.put("binding",binding); configFile.writeText(c.toString()); settle()
            engine.setCourseColor(null,"x",0x123456); settle()
            c.put("binding",binding); configFile.writeText(c.toString())
        } else {
            settle(); assertEquals(0xabcdef,SharedCourseColors.rgb(engine.local(),null,"x"))
            assertEquals("opaque",engine.local().sharedRecords!!.first { it.typeId=="test.note" }.payload.toByteArray().toString(Charsets.UTF_8))
            c.put("kotlinHash",SyncV3Codec.businessHash(engine.local()));configFile.writeText(c.toString())
        }
        j.close()
    }
}
