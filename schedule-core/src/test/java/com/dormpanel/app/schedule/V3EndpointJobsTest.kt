package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.nio.file.Files
import java.io.File
import java.util.UUID

class V3EndpointJobsTest {
    private fun id()=UUID.randomUUID().toString()
    private fun job(j:V3Journal,status:String="CANCELLED",expires:Long=Long.MAX_VALUE):String {
        val token=id();j.meta.atomic { j.meta.put("endpointJob",token,JSONObject().put("token",token).put("status",status).put("createdAt",System.currentTimeMillis()).put("expiresAt",expires).put("endpointAccount","encrypted-candidate")) };return token
    }
    private fun graph(j:V3Journal,token:String):File=File(j.directory,"endpoint-$token").also { dir->V3Journal(dir).use { c->c.meta.atomic { c.meta.put("control","walk",JSONObject().put("root","retired proof")) } } }
    @Test fun over64CancelGraphsAndLegacyCacheRetention() {
        V3Journal(Files.createTempDirectory("pr57-jobs-").toFile()).use { j ->
            j.localTransaction { it.put("pending",JSONObject().put("capture","retain")).put("receipt",JSONObject().put("path","retain")) };val before=j.snapshot().toString();val gc=V3EndpointJobs(j)
            repeat(70){val t=job(j);val dir=graph(j,t);gc.reap();assertFalse(File(dir,"v3-state-v2.sqlite").exists());assertFalse(dir.exists())}
            assertEquals(0L,j.meta.count("endpointJob"));assertEquals(32L,j.meta.count("endpointRetired"));assertEquals(before,j.snapshot().toString())
            val t=job(j);val dir=graph(j,t);val objectValue=V3Object(1,id(),id(),id());val raw=SyncV3Codec.encode(objectValue);val path=V3Paths.path(objectValue)
            val cache=V3IndexedCache(dir,V3Budget());cache.put(path,raw);cache.pin(path,"referenced-history")
            repeat(5){gc.reap()};assertEquals("CANCELLED",gc.find(t).getString("status"));assertFalse(File(dir,"v3-state-v2.sqlite").exists());assertArrayEquals(raw,cache.get(path));assertEquals(1L,j.meta.count("endpointProtectedCache"))
        }
    }
    @Test fun cleanupInterruptedThenReopenNeverRecreatesCandidateDatabase() {
        val root=Files.createTempDirectory("pr57-interrupt-").toFile();var j=V3Journal(root);val token=job(j);val dir=graph(j,token)
        var gc=V3EndpointJobs(j);gc.fault={if(it=="file")error("interrupted unlink")};assertThrows(Exception::class.java){gc.reap()}
        assertEquals("CLEANING",j.meta.get("endpointJob",token)!!.getString("status"));j.close();j=V3Journal(root)
        try{gc=V3EndpointJobs(j);gc.reap();assertEquals("CANCELLED",gc.find(token).getString("status"));assertFalse(dir.exists())}finally{j.close()}
    }
    @Test fun activeTasksAndUnknownFilesAreProtectedAndExpiryReleasesCredentials() {
        V3Journal(Files.createTempDirectory("pr57-protect-").toFile()).use { j ->
            val gc=V3EndpointJobs(j);val active=job(j,"READY");val expired=job(j,"VALIDATING",0);val unknown=job(j);val dir=graph(j,unknown);File(dir,"unknown.txt").writeText("preserve")
            assertThrows(Exception::class.java){gc.reap()};assertEquals("READY",gc.find(active).getString("status"));assertEquals("CLEANING",gc.find(unknown).getString("status"));assertTrue(File(dir,"unknown.txt").exists())
            assertFalse(gc.find(unknown).has("endpointAccount"));assertEquals("EXPIRED",gc.find(expired).getString("status"))
            assertTrue(File(dir,"unknown.txt").delete());gc.reap();assertEquals("CANCELLED",gc.find(unknown).getString("status"))
        }
    }
    @Test fun boundFolderRetargetKeepsPathInsteadOfAccountBase() {
        assertEquals("https://new.test:8443/dav/timetable/shared/",V3EndpointForm.retarget("https://old.test/dav/timetable/shared/","https://new.test:8443/account-root/"))
        assertThrows(Exception::class.java){V3EndpointForm.retarget("","https://new.test/")}
    }
    @Test fun legacyActiveGraceCommittedReceiptAndForeignDatabase() {
        V3Journal(Files.createTempDirectory("pr57-legacy-").toFile()).use { j ->
            val gc=V3EndpointJobs(j);val active=job(j,"VALIDATING");val old=j.meta.get("endpointJob",active)!!;old.remove("expiresAt");old.remove("createdAt");j.meta.atomic { j.meta.put("endpointJob",active,old) }
            val committed=job(j,"COMMITTED");gc.reap(1000);assertEquals(1000+V3EndpointJobs.TTL,gc.find(active).getLong("expiresAt"));assertEquals("COMMITTED",gc.find(committed).getString("status"));assertFalse(gc.find(committed).has("endpointAccount"))
            val foreign=job(j);val dir=graph(j,foreign)
            Class.forName("org.sqlite.JDBC");java.sql.DriverManager.getConnection("jdbc:sqlite:${File(dir,"v3-state-v2.sqlite").absolutePath}").use { db -> db.createStatement().use { it.execute("CREATE TABLE unexpected(value TEXT)") } }
            assertThrows(Exception::class.java){gc.reap(1001)};assertTrue(File(dir,"v3-state-v2.sqlite").exists());assertEquals("CLEANING",gc.find(foreign).getString("status"))
        }
    }
    @Test fun storedRawReferenceIsNotHiddenByExpansionDuringGc() {
        V3Journal(Files.createTempDirectory("pr57-raw-ref-").toFile()).use { j ->
            val token=job(j);val dir=graph(j,token)
            val id="00000000-0000-0000-0000-000000000001"
            val raw=java.util.Base64.getEncoder().encodeToString(SyncV3Codec.encode(V3Object(2,id,id,id,id,1,"1".repeat(64),state=ScheduleState())))
            V3Journal(dir).use { c->c.meta.atomic { c.meta.put("control","unexpected",JSONObject().put("body",raw)) } }
            val gc=V3EndpointJobs(j);assertThrows(Exception::class.java){gc.reap()};assertTrue(File(dir,"v3-state-v2.sqlite").exists());assertEquals("CLEANING",gc.find(token).getString("status"))
        }
    }
}
