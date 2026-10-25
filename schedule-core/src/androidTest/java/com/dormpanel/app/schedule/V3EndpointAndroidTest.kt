package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.json.JSONObject
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.view.View
import android.view.ViewGroup
import android.widget.Button

/** Isolated Room file, Keystore namespace and read-only loopback DAV; no daily APK/data. */
class V3EndpointAndroidTest {
    @Test fun formalSharedEndpointDialogFitsDevice() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val name="pr57-ui-${UUID.randomUUID()}.db";WebDavSettings.overrideNamespace=name;SharedScheduleSettings.overrideNamespace=name
        val settings=WebDavSettings(context);settings.saveAccount(WebDavAccount("https://isolated.invalid/","synthetic","synthetic"))
        val executor=Executors.newSingleThreadExecutor();val db=Room.databaseBuilder(context,ScheduleDatabase::class.java,name).build();val store=RoomScheduleStore(db,executor,profiles=TermScheduleProfileStore(context))
        val remote=Remote();var source:ScheduleSource?=null;var controller:SharedScheduleController?=null;var dialog:android.app.AlertDialog?=null
        try {
            store.prepareV3("https://isolated.invalid/");repeat(6){store.synchronizeV3(remote,remote,V3Budget())}
            V3HttpsProbeActivity.resumed=java.util.concurrent.CountDownLatch(1)
            val launch=instrumentation.uiAutomation.executeShellCommand("am start -n ${context.packageName}/com.dormpanel.app.schedule.V3HttpsProbeActivity")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(launch).use { it.readBytes() };assertTrue(V3HttpsProbeActivity.resumed.await(15,TimeUnit.SECONDS))
            instrumentation.runOnMainSync { source=ScheduleSource(store) };await<Unit>{source!!.reload(it)}
            instrumentation.runOnMainSync {
                val activity=requireNotNull(V3HttpsProbeActivity.current.get());controller=SharedScheduleController(activity,source!!,settings)
                dialog=SharedScheduleDialog.show(activity,source!!,controller!!,android.os.Build.MODEL=="X08E")
                fun find(view:View):Button? { if(view is Button && view.text.toString().startsWith("安全更换 v3"))return view;if(view is ViewGroup)for(n in 0 until view.childCount)find(view.getChildAt(n))?.let{return it};return null }
                assertTrue(requireNotNull(find(dialog!!.window!!.decorView)).performClick())
            };instrumentation.waitForIdleSync();android.os.SystemClock.sleep(600) // finish Window animation before visual evidence
            val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot());val out=java.io.File(context.getExternalFilesDir(null),"pr57-endpoint-ui.png")
            out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        } finally {
            instrumentation.runOnMainSync { dialog?.dismiss();controller?.close();source?.close();V3HttpsProbeActivity.current.get()?.finish() }
            store.close();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));WebDavSettings.overrideNamespace=null;SharedScheduleSettings.overrideNamespace=null
        }
    }
    @Test fun isolatedNasHttpsCaToPinEndpointMove() {
        val args=InstrumentationRegistry.getArguments();val encoded=args.getString("v3EndpointProbe");assumeTrue("explicit isolated NAS probe required",encoded!=null)
        val p=JSONObject(String(Base64.getDecoder().decode(encoded),Charsets.UTF_8));val config=p.getJSONObject("config");val candidate=p.getJSONObject("candidate")
        val url=config.getString("url");require(Regex("/dormpanel/pr57-endpoint-[0-9a-f-]{36}/$").containsMatchIn(url))
        fun account(c:JSONObject)=WebDavAccount(c.getString("url"),c.getString("username"),c.getString("password"),c.optString("certificatePem").ifBlank { null })
        val original=account(config);val target=account(candidate);val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.dormpanel.schedule.core.test")
        V3HttpsProbeActivity.resumed=java.util.concurrent.CountDownLatch(1)
        val shell=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am start -n ${context.packageName}/com.dormpanel.app.schedule.V3HttpsProbeActivity")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(shell).use { it.readBytes() };assertTrue(V3HttpsProbeActivity.resumed.await(15,TimeUnit.SECONDS))
        val name="pr57-nas-${UUID.randomUUID()}.db";WebDavSettings.overrideNamespace=name;val settings=WebDavSettings(context);settings.saveAccount(original)
        val executor=Executors.newSingleThreadExecutor();val db=Room.databaseBuilder(context,ScheduleDatabase::class.java,name).build();val store=RoomScheduleStore(db,executor,profiles=TermScheduleProfileStore(context))
        try {
            val binding=p.getJSONObject("binding");val raw=requireNotNull(V3WebDavStore(original,url,V3Budget()).read(binding.getString("descriptorPath")))
            assertEquals(binding.getString("descriptorHash"),SyncV3Codec.hash(raw));store.prepareV3(url,raw)
            fun settle(){repeat(32){if(store.synchronizeV3(store.v3Account(settings))=="OBSERVED")return};error("did not settle")};settle()
            await<Unit> { store.put(TimetableEntry("android-${UUID.randomUUID()}","PR57 NAS offline edit",1,480,540,""),it) }
            val before=store.v3Information().toString();var preview=store.previewV3Endpoint(target,target.baseUrl,settings)
            repeat(32){if(preview.optString("status")!="READY")preview=store.continueV3Endpoint(preview.getString("token"),settings)}
            assertEquals("READY",preview.getString("status"));assertEquals("COMMITTED",store.confirmV3Endpoint(preview.getString("token"),settings).getString("status"));assertEquals(before,store.v3Information().toString())
            assertNotNull(store.v3Account(settings).certificatePem);assertNull(settings.account()!!.certificatePem);settle()
            assertTrue(await<ScheduleState>(store::load).entries.any { it.title=="PR57 NAS offline edit" })
        } finally {store.close();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));WebDavSettings.overrideNamespace=null;InstrumentationRegistry.getInstrumentation().runOnMainSync { V3HttpsProbeActivity.current.get()?.finish() }}
    }
    private class Remote:V3ObjectStore,V3PublicationStore {
        val objects=linkedMapOf<String,ByteArray>();var puts=0
        override fun read(path:String)=objects[path]
        override fun list(scope:String)=objects.keys.filter { it.startsWith(scope) }.map { p->val tail=p.removePrefix(scope);scope+if('/' in tail)tail.substringBefore('/')+"/" else tail }.distinct().sorted()
        override fun prepare(scope:String) {}
        override fun putOnce(path:String,raw:ByteArray) { check(path !in objects);objects[path]=raw.copyOf();puts++ }
        override fun createForIsolatedTest(path:String,bytes:ByteArray,beforeWriteIntent:()->Unit)=error("no test primitive")
    }
    private fun <T> await(call:((Result<T>)->Unit)->Unit):T { val f=CompletableFuture<Result<T>>();call { f.complete(it) };return f.get(20,TimeUnit.SECONDS).getOrThrow() }
    @Test fun roomCommitReopenCredentialsPendingAndStalePreview() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.dormpanel.schedule.core.test")
        val name="pr57-${UUID.randomUUID()}.db";WebDavSettings.overrideNamespace=name
        val settings=WebDavSettings(context);settings.saveAccount(WebDavAccount("https://global.invalid/","global","preserve"))
        val global=settings.account();val remote=Remote();val server=MockWebServer();var denied:String?=null
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(r:RecordedRequest):MockResponse {
                val rawPath=r.path!!.substringBefore('?');val prefix="/new/dormpanel-sync-v3/";val path=rawPath.removePrefix(prefix)
                if(!rawPath.startsWith(prefix))return MockResponse().setResponseCode(404)
                if(r.method=="GET")return remote.read(path)?.takeUnless { path==denied }?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                if(r.method=="PROPFIND") {
                    val children=remote.list(path)
                    fun row(p:String)="<d:response><d:href>$prefix$p</d:href><d:propstat><d:prop><d:resourcetype>${if(p.endsWith('/'))"<d:collection/>" else ""}</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                    return MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">"+row(path)+children.joinToString(""){row(it)}+"</d:multistatus>")
                }
                return MockResponse().setResponseCode(405)
            }
        };server.start()
        var executor=Executors.newSingleThreadExecutor();var db=Room.databaseBuilder(context,ScheduleDatabase::class.java,name).build()
        var faultAt:String?=null;var decisionToken:String?=null
        val fault:(String)->Unit={point->
            if(faultAt==point && decisionToken!=null && db.dao().syncRecord(205)?.payload?.toString(Charsets.UTF_8)?.contains("\"endpointDecision\":\"$decisionToken\"")==true){faultAt=null;error("endpoint decision interrupted")}
        }
        var store=RoomScheduleStore(db,executor,profiles=TermScheduleProfileStore(context),v3Fault=fault)
        fun close(){store.close();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS))}
        fun reopen(){close();executor=Executors.newSingleThreadExecutor();db=Room.databaseBuilder(context,ScheduleDatabase::class.java,name).build();store=RoomScheduleStore(db,executor,profiles=TermScheduleProfileStore(context),v3Fault=fault)}
        fun edit(title:String)=await<Unit> { store.put(TimetableEntry("saved",title,1,480,540,""),it) }
        try {
            store.prepareV3("https://old.invalid/");repeat(6){store.synchronizeV3(remote,remote,V3Budget())};edit("offline edit")
            val before=store.v3Information().toString();val puts=remote.puts
            val candidate=WebDavAccount(server.url("/new/").toString(),"new","new secret")
            var p=store.previewV3Endpoint(candidate,candidate.baseUrl,settings);val token=p.getString("token")
            reopen() // Progress and sealed candidate survive; global account unchanged.
            repeat(15){if(p.optString("status")!="READY")p=store.continueV3Endpoint(token,settings)}
            assertEquals("READY",p.getString("status"));assertEquals(before,store.v3Information().toString());assertEquals(global!!.password,settings.account()!!.password);assertEquals(global.baseUrl,settings.account()!!.baseUrl)
            denied=store.v3Information()!!.getString("baselinePath")
            assertThrows(Exception::class.java){store.confirmV3Endpoint(token,settings)};assertEquals(before,store.v3Information().toString());denied=null
            decisionToken=token;faultAt="before-commit";assertThrows(Exception::class.java){store.confirmV3Endpoint(token,settings)};reopen()
            assertEquals(global.baseUrl,store.v3Account(settings).baseUrl);assertEquals(before,store.v3Information().toString())
            faultAt="after-commit";assertThrows(Exception::class.java){store.confirmV3Endpoint(token,settings)};reopen()
            val result=store.confirmV3Endpoint(token,settings);assertEquals("COMMITTED",result.getString("status"))
            assertEquals(result.toString(),store.confirmV3Endpoint(token,settings).toString());assertEquals(candidate.password,store.v3Account(settings).password);assertEquals(candidate.baseUrl,store.v3Account(settings).baseUrl);assertEquals(global!!.password,settings.account()!!.password);assertEquals(global.baseUrl,settings.account()!!.baseUrl)
            assertEquals(before,store.v3Information().toString());assertEquals(puts,remote.puts)
            var next=store.previewV3Endpoint(candidate,candidate.baseUrl,settings)
            repeat(15){if(next.optString("status")!="READY")next=store.continueV3Endpoint(next.getString("token"),settings)}
            edit("after preview");assertThrows(Exception::class.java){store.confirmV3Endpoint(next.getString("token"),settings)}
            assertEquals("after preview",await<ScheduleState>(store::load).entries.single().title)
        } finally {close();server.shutdown();WebDavSettings.overrideNamespace=null}
    }
}
