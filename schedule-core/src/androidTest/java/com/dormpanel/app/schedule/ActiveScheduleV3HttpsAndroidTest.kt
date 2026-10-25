package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ActiveScheduleV3HttpsAndroidTest {
    private fun <T> await(operation: ((Result<T>) -> Unit) -> Unit): T {
        val future=CompletableFuture<Result<T>>(); operation { future.complete(it) }; return future.get(20,TimeUnit.SECONDS).getOrThrow()
    }
    @Test fun productionStoreIsolatedHttpsInteroperability() {
        val args=InstrumentationRegistry.getArguments(); val encoded=args.getString("v3Probe")
        assumeTrue("explicit PR55 isolated probe required",encoded!=null)
        val probe=JSONObject(String(Base64.getDecoder().decode(encoded),Charsets.UTF_8)); val config=probe.getJSONObject("config"); val binding=probe.getJSONObject("binding")
        val url=config.getString("url"); require(Regex("/pr55-runtime-[0-9a-f-]{36}/$").containsMatchIn(url))
        val account=WebDavAccount(url,config.getString("username"),config.getString("password"),config.optString("certificatePem").takeIf { it.isNotBlank() })
        val diagnosticClient=account.certificatePem?.let { WebDavTls.client(okhttp3.OkHttpClient(),it) } ?: okhttp3.OkHttpClient()
        val device=args.getString("v3Device","test"); require(device in listOf("phone","x08e")); val mode=args.getString("v3Mode","publish")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.dormpanel.schedule.core.test")
        V3HttpsProbeActivity.resumed=java.util.concurrent.CountDownLatch(1)
        val launch=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${context.packageName}/com.dormpanel.app.schedule.V3HttpsProbeActivity")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(launch).use { it.readBytes() }
        assertTrue(V3HttpsProbeActivity.resumed.await(15,TimeUnit.SECONDS))
        if (mode=="diagnose") {
            // No Authorization header in this diagnostic; normal TLS validation stays enabled.
            diagnosticClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { assertTrue(it.code in 200..499) }
            return
        }
        val name="pr55-https-endpoint-v1-${binding.getString("datasetId")}-$device.db"
        WebDavSettings.overrideNamespace=name
        val settings=WebDavSettings(context)
        if(mode=="publish")settings.saveAccount(account)
        else {
            val saved=requireNotNull(settings.account())
            assertEquals(account.baseUrl,saved.baseUrl);assertEquals(account.username,saved.username)
            assertEquals(account.certificatePem?.let(WebDavTls::fingerprint),saved.certificatePem?.let(WebDavTls::fingerprint))
        }
        val executor=Executors.newSingleThreadExecutor(); val db=Room.databaseBuilder(context,ScheduleDatabase::class.java,name).build()
        val store=RoomScheduleStore(db,executor,profiles=TermScheduleProfileStore(context))
        try {
            if (mode=="receive") {
                // Distinguish a cold device DNS failure from persisted Store recovery.
                diagnosticClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().close()
            }
            if (!store.isV3()) {
                assertEquals("publish",mode)
                val descriptor=V3WebDavStore(account,url,V3Budget()).read(binding.getString("descriptorPath"))!!
                assertEquals(binding.getString("descriptorHash"),SyncV3Codec.hash(descriptor)); store.prepareV3(url,descriptor)
            }
            fun settle() { repeat(32) { if(store.synchronizeV3(account)=="OBSERVED") return }; error("bounded rounds did not settle") }
            if(mode=="offline-edit") {
                await<Unit> { store.put(TimetableEntry("node","Phone conflict decision",1,480,540,""),it) }
                return
            }
            if(mode=="resolve") {
                var conflict=false
                repeat(32) { if(!conflict) conflict=store.synchronizeV3(account)=="CONFLICT" }
                assertTrue(conflict); val token=store.v3Information()!!.getJSONObject("conflict").getString("id")
                store.resolveV3(token,ScheduleMerge.Choice.LOCAL); settle()
                assertEquals("Phone conflict decision",await<ScheduleState>(store::load).entries.single { it.id=="node" }.title)
                return
            }
            settle(); assertTrue(await<ScheduleState>(store::load).entries.any { it.id=="node" })
            if(mode=="publish") {
                await<Unit> { store.put(TimetableEntry("android-$device","Android $device",4,720,780,""),it) }; settle()
            } else {
                val state=await<ScheduleState>(store::load)
                assertTrue(listOf("node","android-phone","android-x08e").all { id->state.entries.any { it.id==id } })
                assertTrue(state.events.any { it.id=="node-final" })
                config.optString("expectedRawHash").takeIf { it.isNotBlank() }?.let { expected ->
                    assertEquals(expected,SyncV3Codec.hash(SyncV3Codec.stateBytes(state)))
                }
            }
            assertEquals(binding.getString("datasetId"),store.v3Information()!!.getJSONObject("binding").getString("datasetId"))
        } finally {
            store.close(); assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS))
            WebDavSettings.overrideNamespace=null
            InstrumentationRegistry.getInstrumentation().runOnMainSync { V3HttpsProbeActivity.current.get()?.finish() }
        }
    }
}
