package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Every file is uniquely named. Never opens the production schedule.db or real WebDAV. */
class ActiveScheduleV3AndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun id() = UUID.randomUUID().toString()
    private class Remote : V3ObjectStore, V3PublicationStore {
        val objects = linkedMapOf<String, ByteArray>(); var puts = 0; var lose = false
        override fun prepare(scope: String) {}
        override fun read(path: String) = objects[path]?.copyOf()
        override fun putOnce(path: String, raw: ByteArray) { check(path !in objects); puts++; objects[path] = raw.copyOf(); if (lose) { lose = false; error("lost response") } }
        override fun list(scope: String) = objects.keys.filter { it.startsWith(scope) }.map { p -> val tail = p.removePrefix(scope); scope + if ('/' in tail) tail.substringBefore('/') + "/" else tail }.distinct().sorted()
        override fun createForIsolatedTest(path: String, bytes: ByteArray, beforeWriteIntent: () -> Unit) = error("test primitive")
    }
    private inner class Client(val name: String = "pr55-active-${id()}.db") {
        var executor = Executors.newSingleThreadExecutor()
        var db = Room.databaseBuilder(context, ScheduleDatabase::class.java, name).build()
        var store = RoomScheduleStore(db, executor, profiles = TermScheduleProfileStore(context))
        fun close() { store.close(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)) }
        fun reopen() { close(); executor = Executors.newSingleThreadExecutor(); db = Room.databaseBuilder(context, ScheduleDatabase::class.java, name).build(); store = RoomScheduleStore(db, executor, profiles = TermScheduleProfileStore(context)) }
        fun round(r: Remote) = store.synchronizeV3(r, r, V3Budget())
        fun settle(r: Remote) { repeat(8) { round(r) } }
    }
    private fun <T> await(operation: ((Result<T>) -> Unit) -> Unit): T {
        val result = CompletableFuture<Result<T>>(); operation { result.complete(it) }; return result.get(10, TimeUnit.SECONDS).getOrThrow()
    }
    private fun entry(title: String) = TimetableEntry("stable", title, 1, 480, 540, "")
    @Test fun changedAccessIdentityStopsBeforeNetworkAndRetainsLocalEdits() {
        val a=Client();val r=Remote()
        try {
            await<Unit> { a.store.put(entry("retained"),it) };a.store.prepareV3("https://never-resolve.invalid/");a.settle(r)
            val account=WebDavAccount("https://never-resolve.invalid/","original","test")
            val key=ActiveScheduleV3.endpointKey(V3WebDavStore(account,account.baseUrl,V3Budget()),account)
            val mode=org.json.JSONObject(a.db.dao().syncRecord(205)!!.payload.toString(Charsets.UTF_8)).put("endpointKey",key)
            a.db.dao().checkpoint(ScheduleSyncCheckpoint(205,mode.toString().toByteArray(Charsets.UTF_8)))
            a.reopen()
            val error=assertThrows(Exception::class.java) { a.store.synchronizeV3(WebDavAccount(account.baseUrl,"changed","test")) }
            assertTrue(generateSequence(error as Throwable?) { it.cause }.any { it.message.orEmpty().contains("帐号身份变化") })
            assertEquals("retained",await<ScheduleState>(a.store::load).entries.single().title)
            assertEquals(key,org.json.JSONObject(a.db.dao().syncRecord(205)!!.payload.toString(Charsets.UTF_8)).getString("endpointKey"))
        } finally { a.close() }
    }
    @Test fun productionStoreEditsJoinReceiptsAndReopenUseSameRoomFile() {
        val a = Client(); val b = Client(); val r = Remote()
        try {
            await<Unit> { a.store.put(entry("base"), it) }
            a.store.prepareV3("https://isolated.test/"); assertTrue(a.store.isV3())
            assertThrows(Exception::class.java) { a.store.v2RequireSyncAllowed() }
            a.settle(r)
            val binding = a.store.v3Information()!!.getJSONObject("binding")
            b.store.prepareV3("https://isolated.test/", r.objects.getValue(binding.getString("descriptorPath")))
            b.settle(r); assertEquals("base", await<ScheduleState>(b.store::load).entries.single().title)
            await<Unit> { b.store.put(entry("from B"), it) }; b.settle(r); a.settle(r)
            assertEquals("from B", a.db.dao().entries().single().title)
            assertEquals("from B", await<ScheduleState>(a.store::load).entries.single().title)
            val old = a.store; a.reopen(); assertTrue(a.store.isV3())
            assertEquals(binding.getString("datasetId"), a.store.v3Information()!!.getJSONObject("binding").getString("datasetId"))
            assertThrows(Exception::class.java) { old.v2RequireSyncAllowed() }
            await<Unit> { a.store.put(entry("offline after restart"), it) }
            assertEquals("offline after restart", a.db.dao().entries().single().title)
            assertNotNull(a.db.dao().syncRecord(ActiveScheduleV3.ORIGINAL_LOCAL))
            assertEquals("base", ScheduleSnapshotCodec.decode(a.db.dao().syncRecord(ActiveScheduleV3.ORIGINAL_LOCAL)!!.payload).state.entries.single().title)
        } finally { a.close(); b.close() }
    }
    @Test fun attemptedPublicationReopensSameCandidateAndNonemptyJoinCannotOverwrite() {
        val a = Client(); val b = Client(); val r = Remote()
        try {
            await<Unit> { a.store.put(entry("saved"), it) }; a.store.prepareV3("https://isolated.test/")
            r.lose = true; assertThrows(Exception::class.java) { a.round(r) }
            val pending = a.store.v3Information()!!.getJSONObject("pending").getString("path")
            val target = a.store.v3Information()!!.getJSONObject("binding").getString("datasetId")
            a.reopen(); a.settle(r)
            assertEquals(target, a.store.v3Information()!!.getJSONObject("binding").getString("datasetId"))
            assertTrue(r.objects.containsKey(pending)); assertEquals(2, r.puts)
            await<Unit> { b.store.put(entry("retained"), it) }
            val descriptor = a.store.v3Information()!!.getJSONObject("binding").getString("descriptorPath")
            assertThrows(Exception::class.java) { b.store.prepareV3("https://isolated.test/", r.objects.getValue(descriptor)) }
            assertFalse(b.store.isV3()); assertEquals("retained", await<ScheduleState>(b.store::load).entries.single().title)
        } finally { a.close(); b.close() }
    }
    @Test fun migrationRetainsAllRawAndUnpublishedLocalAcrossLostResponseAndRestart() {
        val a = Client(); val target = Remote()
        val raw = InstrumentationRegistry.getInstrumentation().context.assets.open("pr55-complex.dpv").use { it.readBytes() }
        val original = SyncV3Codec.decode(raw).state!!
        val root = ScheduleCommit(id(), id(), id(), emptyList(), original)
        val sourceObjects = linkedMapOf(root.commitId to ScheduleCommitCodec.encode(root))
        val source = object : CommitStore {
            override val bindingKey = "1".repeat(64)
            override fun list() = sourceObjects.keys.toList()
            override fun read(commitId: String) = sourceObjects[commitId]
            override fun publish(bytes: ByteArray, allowCreate: Boolean) = error("migration must not publish v2")
        }
        try {
            a.store.v2Start(root, true)
            await<Unit> { a.store.put(entry("unpublished local"), it) }
            val before = await<ScheduleState>(a.store::load).copy(sync = null)
            val catalog = a.db.dao().syncRecord(2)!!.payload.copyOf()
            val preview = a.store.previewV3Migration(source, "https://isolated.test/dormpanel-sync-v2/")
            a.store.prepareV3Migration(preview, source, "https://isolated.test/")
            assertTrue("UI mode reads must not decode full migration snapshots",a.db.dao().syncRecord(ActiveScheduleV3.MODE)!!.payload.size<4096)
            assertTrue(a.store.businessPaused())
            assertThrows(Exception::class.java) { await<Unit> { a.store.put(entry("forbidden"), it) } }
            target.lose = true
            assertThrows(Exception::class.java) { a.store.synchronizeV3(target, target, V3Budget(), source) }
            val binding = a.store.v3Information()!!.getJSONObject("binding").getString("datasetId")
            a.reopen()
            repeat(8) { a.store.synchronizeV3(target, target, V3Budget(), source) }
            assertFalse(a.store.businessPaused()); assertEquals(2, target.puts)
            assertEquals(binding, a.store.v3Information()!!.getJSONObject("binding").getString("datasetId"))
            assertArrayEquals(SyncV3Codec.stateBytes(before), SyncV3Codec.stateBytes(await<ScheduleState>(a.store::load).copy(sync = null)))
            assertArrayEquals(catalog, a.db.dao().syncRecord(2)!!.payload)
            assertArrayEquals(ScheduleCommitCodec.encode(root), a.db.dao().syncRecord(21)!!.payload)
            assertEquals(1, sourceObjects.size)
        } finally { a.close() }
    }
    @Test fun secondV2DeviceJoinsEquivalentMigratedTargetWithoutAnotherGenesis() {
        val a=Client(); val b=Client(); val target=Remote()
        val state=SyncV3Codec.decode(InstrumentationRegistry.getInstrumentation().context.assets.open("pr55-complex.dpv").use { it.readBytes() }).state!!
        val root=ScheduleCommit(id(),id(),id(),emptyList(),state); val sourceRaw=ScheduleCommitCodec.encode(root)
        val source=object:CommitStore {
            override val bindingKey="2".repeat(64)
            override fun list()=listOf(root.commitId)
            override fun read(commitId:String)=if(commitId==root.commitId) sourceRaw else null
            override fun publish(bytes:ByteArray,allowCreate:Boolean)=error("must not publish v2")
        }
        try {
            a.store.v2Start(root,true); b.store.v2Start(root,true)
            val first=a.store.previewV3Migration(source,"https://isolated.test/dormpanel-sync-v2/")
            a.store.prepareV3Migration(first,source,"https://isolated.test/")
            repeat(8) { a.store.synchronizeV3(target,target,V3Budget(),source) }
            val descriptor=target.objects.getValue(a.store.v3Control()!!.getJSONObject("binding").getString("descriptorPath"))
            val directory=java.io.File(context.filesDir,"pr55-readonly-${id()}"); var verified:VerifiedV3Snapshot?=null
            repeat(8) { if(verified==null) verified=V3DatasetInspection.inspect(directory,b.store.v2Frame().writerId,target,V3Budget(),descriptor,"https://isolated.test/") }
            assertNotNull(verified); assertEquals(2,target.puts); assertFalse(b.store.isV3())
            val second=ExistingV3MigrationPreview(b.store.previewV3Migration(source,"https://isolated.test/dormpanel-sync-v2/"),verified!!)
            repeat(8) { if(!b.store.isV3()) try { b.store.prepareExistingV3Migration(second,source,target,V3Budget()) } catch(e:IllegalStateException) { if(!e.message.orEmpty().contains("预检未完成")) throw e } }
            assertTrue(b.store.isV3()); assertFalse(b.store.businessPaused())
            b.settle(target); assertEquals(2,target.puts)
            assertEquals(a.store.v3Control()!!.getJSONObject("binding").getString("datasetId"),b.store.v3Control()!!.getJSONObject("binding").getString("datasetId"))
            assertThrows(Exception::class.java) { b.store.v2RequireSyncAllowed() }
            await<Unit> { b.store.put(entry("second migrated device"),it) }; b.settle(target); a.settle(target)
            assertEquals("second migrated device",await<ScheduleState>(a.store::load).entries.single { it.id=="stable" }.title)
            assertEquals(1,target.objects.values.map(SyncV3Codec::decode).count { it.type==2 && it.epochNumber==1 })
            assertArrayEquals(sourceRaw,b.db.dao().syncRecord(21)!!.payload)
        } finally { a.close();b.close() }
    }
    @Test fun missingActiveFrameNeverRebuildsEmptyDatasetOrClearsRoomCourses() {
        val first=Client(); val target=Remote(); var reopened:Client?=null
        try {
            await<Unit> { first.store.put(entry("durable"),it) }; first.store.prepareV3("https://isolated.test/"); first.settle(target)
            first.close()
            val db=Room.databaseBuilder(context,ScheduleDatabase::class.java,first.name).build()
            db.openHelper.writableDatabase.execSQL("DELETE FROM v3_records WHERE ns='state' AND k='frame'")
            db.close()
            val next=Client(first.name); reopened=next
            val puts=target.puts
            assertThrows(Exception::class.java) { next.round(target) }
            assertTrue(next.store.businessPaused()); assertEquals(puts,target.puts)
            assertEquals("durable",next.db.dao().entries().single().title)
            assertThrows(Exception::class.java) { await<ScheduleState>(next.store::load) }
            assertEquals("durable",next.db.dao().entries().single().title)
        } finally { reopened?.close(); first.close() }
    }
    @Test fun lostLocalCommitReceiptReopensSameStoreAndSameCreationCandidate() {
        val name="pr55-owner-recovery-${id()}.db"; val executor=Executors.newSingleThreadExecutor(); var database=Room.databaseBuilder(context,ScheduleDatabase::class.java,name).build()
        var inject=false; var expected:String?=null
        val store=RoomScheduleStore(database,executor,profiles=TermScheduleProfileStore(context),reopenDatabase={
            Room.databaseBuilder(context,ScheduleDatabase::class.java,name).build().also { database=it }
        },v3Fault={ phase ->
            if(inject && phase=="after-commit") {
                val raw=database.openHelper.writableDatabase.query("SELECT v FROM v3_records WHERE ns='state' AND k='frame'").use { c->check(c.moveToFirst());c.getString(0) }
                val frame=org.json.JSONObject(raw)
                if(frame.has("binding")) { expected=frame.getJSONObject("binding").getString("datasetId");inject=false;error("injected commit receipt loss") }
            }
        })
        val target=Remote()
        try {
            await<Unit> { store.put(entry("before"),it) };store.prepareV3("https://isolated.test/");inject=true
            assertThrows(Exception::class.java) { store.synchronizeV3(target,target,V3Budget()) }
            assertTrue(store.businessPaused());assertEquals(0,target.puts);assertNotNull(expected)
            store.reopenV3Store();assertFalse(store.businessPaused())
            assertEquals(expected,store.v3Control()!!.getJSONObject("binding").getString("datasetId"))
            await<Unit> { store.put(entry("after recovery"),it) }
            repeat(8) { store.synchronizeV3(target,target,V3Budget()) }
            assertEquals("after recovery",await<ScheduleState>(store::load).entries.single().title)
            assertEquals(setOf(expected),target.objects.values.map { SyncV3Codec.decode(it).datasetId }.toSet())
            assertEquals(1,target.objects.values.map(SyncV3Codec::decode).count { it.type==2 && it.epochNumber==1 })
        } finally { store.close();assertTrue(executor.awaitTermination(15,TimeUnit.SECONDS)) }
    }
}
