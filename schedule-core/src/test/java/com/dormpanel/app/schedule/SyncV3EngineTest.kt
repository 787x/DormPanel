package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.nio.file.Files
import java.util.UUID
import java.io.File

class SyncV3EngineTest {
    private fun id() = UUID.randomUUID().toString()
    private fun directory() = Files.createTempDirectory("dp-pr54-").toFile()
    private fun entry(id: String, title: String = id) = TimetableEntry(id = id, title = title, day = 1, startMinute = 480, endMinute = 540, location = "")
    private class Remote : V3ObjectStore, V3PublicationStore {
        val objects = linkedMapOf<String, ByteArray>(); var puts = 0; var reads = 0; var loseResponse = false; var duringPut: (() -> Unit)? = null
        override fun read(path: String): ByteArray? { reads++; return objects[path]?.copyOf() }
        override fun putOnce(path: String, raw: ByteArray) { check(!objects.containsKey(path)); puts++; objects[path] = raw.copyOf(); duringPut?.let { duringPut = null; it() }; if (loseResponse) error("lost response") }
        override fun prepare(scope: String) {}
        override fun list(scope: String): List<String> = objects.keys.filter { it.startsWith(scope) }.map { p -> val tail = p.removePrefix(scope); scope + if ('/' in tail) tail.substringBefore('/') + "/" else tail }.distinct().sorted().also { require(it.size <= 256) }
        override fun createForIsolatedTest(path: String, bytes: ByteArray, beforeWriteIntent: () -> Unit) = error("engine must not invoke test primitive")
    }
    private class Client(val dir: File, val journal: V3Journal, val engine: V3Engine, val remote: Remote) {
        fun round(): String { val budget = V3Budget(); return engine.round(remote, remote, V3IndexedCache(dir, budget), budget, 32) }
        fun settle(rounds: Int = 3): String { var result = ""; repeat(rounds) { try { result = round() } catch (e: IllegalStateException) { if (!e.message.orEmpty().contains("incomplete")) throw e } }; return result }
    }
    private fun client(remote: Remote, binding: JSONObject? = null, registry: SharedTypeRegistry = SharedTypeRegistry(listOf(SharedCourseColor))): Client {
        val dir = directory(); val journal = V3Journal(dir); val engine = V3Engine(journal, id(), registry)
        if (binding != null) engine.bind(SyncV3Codec.decode(requireNotNull(remote.read(binding.getString("descriptorPath")))), binding.getString("descriptorHash"))
        return Client(dir, journal, engine, remote)
    }
    @Test fun independentEditsAndRestartableConflict() {
        val remote = Remote(); val a = client(remote); val binding = a.engine.initialize(remote); a.settle(); val b = client(remote, binding); b.settle()
        a.engine.edit(ScheduleState(entries = listOf(entry("a")))); b.engine.edit(ScheduleState(entries = listOf(entry("b"))))
        a.settle(); b.settle(); a.settle(); assertEquals(setOf("a", "b"), a.engine.local().entries.map { it.id }.toSet())
        a.engine.edit(a.engine.local().copy(entries = a.engine.local().entries.map { if (it.id == "a") entry("a", "left") else it }))
        b.engine.edit(b.engine.local().copy(entries = b.engine.local().entries.map { if (it.id == "a") entry("a", "right") else it }))
        a.settle(); assertEquals("CONFLICT", b.settle(1)); val conflict = b.journal.snapshot().getJSONObject("conflict")
        b.engine.resolve(conflict.getString("id"), ScheduleMerge.Choice.LOCAL); b.settle(); a.settle(); assertEquals("right", a.engine.local().entries.single { it.id == "a" }.title)
    }
    private fun linkedRegistry() = SharedTypeRegistry(listOf(SharedCourseColor,object: SharedTypeAdapter {
        override val typeId="test.note";override val schemaVersion=1;override val maxPayload=32
        override fun validate(record:SharedRecord, records:List<SharedRecord>){require(record.payload.isNotEmpty())}
        override fun relatedKeys(record:SharedRecord,state:ScheduleState)=setOf("class:x")
    }))
    @Test fun colorEditUsesInjectedRegistryAndPreservesRequiredAndOpaqueRecords() {
        val required=SharedRecord("test.note","one",1,required=true,payload=listOf(97.toByte()))
        val opaque=SharedRecord("future.optional","opaque",2,payload=listOf(0,127,-1).map(Int::toByte))
        val initial=SharedCourseColors.change(ScheduleState(entries=listOf(entry("x"),entry("y")),
            events=listOf(CalendarEvent("event","保留事件",1000,2000)),sharedRecords=emptyList()),null,"x",0x123456)
            .let { it.copy(sharedRecords=it.sharedRecords!!+required+opaque) }
        val a=client(Remote(),registry=linkedRegistry())
        a.engine.initialize(a.remote,initial);a.settle();val before=a.engine.local()
        fun preserved() {
            val after=a.engine.local()
            assertEquals(before.copy(sharedRecords=null),after.copy(sharedRecords=null))
            assertEquals(before.sharedRecords!!.filter { it.typeId!="course.color" },after.sharedRecords!!.filter { it.typeId!="course.color" })
            assertArrayEquals(SharedRecordCodec.encode(listOf(required,opaque)),SharedRecordCodec.encode(after.sharedRecords!!.filter { it.typeId!="course.color" }))
        }
        a.engine.setCourseColor(null,"x",0xabcdef)
        assertEquals(0xabcdef,SharedCourseColors.rgb(a.engine.local(),null,"x"));preserved()
        a.engine.setCourseColor(null,"x",null)
        assertEquals("AUTO",SharedCourseColors.display(a.engine.local(),null,"x").status);preserved()
        val current=a.engine.local()
        val futureColor=current.copy(sharedRecords=current.sharedRecords!!.map { if(it.typeId=="course.color")it.copy(schemaVersion=2) else it })
        assertThrows(Exception::class.java) { SharedCourseColors.change(futureColor,null,"x",0xff0000,linkedRegistry()) }
        val unknownDependency=current.copy(sharedRecords=current.sharedRecords!!.map { if(it.typeId=="test.note")it.copy(dependencies=listOf("future.dependency")) else it })
        assertThrows(Exception::class.java) { SharedCourseColors.change(unknownDependency,null,"x",0xff0000,linkedRegistry()) }
        val writer=a.journal.snapshot().getString("writerId")
        val defaultEngine=V3Engine(a.journal,writer)
        assertThrows(Exception::class.java) { defaultEngine.setCourseColor(null,"x",0xff0000) }
        assertEquals(current,defaultEngine.local())
        assertThrows(Exception::class.java) { a.engine.setCourseColor(null,"x",0xff0000,expectedHash="stale") }
        assertEquals(current,a.engine.local())
        a.journal.meta.fault={if(it=="before-commit")error("injected rollback")}
        assertThrows(Exception::class.java) { a.engine.setCourseColor(null,"x",0xff0000) }
        a.journal.meta.fault=null;assertEquals(current,a.engine.local());a.journal.close()
    }
    @Test fun customRegistryRejectsMalformedHistoryAndUnregisteredPeerPreservesIt() {
        val remote=Remote();val seed=client(remote);val binding=seed.engine.initialize(remote,ScheduleState(entries=listOf(entry("x")),sharedRecords=emptyList()));seed.settle()
        val known=client(remote,binding,linkedRegistry());known.settle();val before=known.journal.snapshot()
        val parentRaw=remote.objects.getValue(before.getString("baselinePath"));val parent=SyncV3Codec.decode(parentRaw)
        val note=SharedRecord("test.note","one",1,payload=emptyList())
        val bad=parent.copy(type=3,objectId=id(),refs=listOf(V3Ref(1,parent.type,parent.epochId,parent.epochNumber,parent.objectId,SyncV3Codec.hash(parentRaw))),state=parent.state!!.copy(sharedRecords=listOf(note)))
        remote.objects[V3Paths.path(bad)]=SyncV3Codec.encode(bad)
        var rejected=false;repeat(5){if(!rejected)try{known.round()}catch(e:Exception){rejected=true}};assertTrue(rejected)
        val after=known.journal.snapshot();listOf("local","baseline","pending").forEach{assertEquals(before.opt(it)?.toString(),after.opt(it)?.toString())}
        val old=client(remote,binding);old.settle(5);assertEquals(listOf(note),old.engine.local().sharedRecords)
        val writer=known.journal.snapshot().getString("writerId");known.journal.close();val j=V3Journal(known.dir);val resumed=Client(known.dir,j,V3Engine(j,writer,linkedRegistry()),remote)
        assertThrows(Exception::class.java){resumed.round()};resumed.journal.close();seed.journal.close();old.journal.close()
    }
    @Test fun customAssociationCoversHistoryPlanAndReopenedDecision() {
        val registry=linkedRegistry();val remote=Remote();val a=client(remote,registry=registry)
        val note=SharedRecord("test.note","one",1,payload=listOf(97.toByte()))
        val base=ScheduleState(entries=listOf(entry("x"),entry("y")),sharedRecords=listOf(note))
        val binding=a.engine.initialize(remote,base);a.settle();val raw=remote.objects.getValue(a.journal.snapshot().getString("baselinePath"));val parent=SyncV3Codec.decode(raw)
        val left=base.copy(entries=listOf(entry("y")));val right=base.copy(sharedRecords=listOf(note.copy(payload=listOf(98.toByte()))))
        assertEquals(1,ScheduleMerge.merge(base,left,right,sharedRegistry=registry).conflicts.size)
        assertTrue(ScheduleMerge.merge(base,base.copy(entries=listOf(entry("x"),entry("y","changed"))),right,sharedRegistry=registry).conflicts.isEmpty())
        fun branch(state:ScheduleState)=parent.copy(type=3,objectId=id(),refs=listOf(V3Ref(1,parent.type,parent.epochId,parent.epochNumber,parent.objectId,SyncV3Codec.hash(raw))),state=state)
        val l=branch(left);val r=branch(right);listOf(l,r).forEach{remote.objects[V3Paths.path(it)]=SyncV3Codec.encode(it)}
        val budget=V3Budget();val h=V3History(a.journal,V3IndexedCache(a.dir,budget),remote,binding.getString("descriptorPath"),binding.getString("descriptorHash"),budget,registry).open()
        listOf(l,r).forEach{h.verify(V3Paths.path(it))};assertThrows(Exception::class.java){h.merged(listOf(V3Paths.path(l),V3Paths.path(r)))}
        assertEquals("CONFLICT",a.settle());val writer=a.journal.snapshot().getString("writerId");a.journal.close()
        val j=V3Journal(a.dir);val resumed=Client(a.dir,j,V3Engine(j,writer,registry),remote)
        resumed.engine.resolve(j.snapshot().getJSONObject("conflict").getString("id"),ScheduleMerge.Choice.LOCAL);resumed.settle()
        assertEquals(listOf(note),resumed.engine.local().sharedRecords);assertTrue(resumed.engine.local().entries.any{it.id=="x"});assertEquals("b4496b6ccdaed04e6bd25b6494ec72bd9a22f21018e844dafecfb21f92eac7de",SyncV3Codec.businessHash(resumed.engine.local(),registry));j.close()
    }
    @Test fun secondRegisteredTypeUsesSameWriterAndOlderClientPreservesIt() {
        val adapter=object: SharedTypeAdapter {
            override val typeId="test.note";override val schemaVersion=1;override val maxPayload=32
            override fun validate(record: SharedRecord, records: List<SharedRecord>) { require(record.payload.isNotEmpty()) }
        }
        val remote=Remote();val a=client(remote,registry=SharedTypeRegistry(listOf(SharedCourseColor,adapter)))
        val binding=a.engine.initialize(remote,ScheduleState(entries=listOf(entry("x")),sharedRecords=emptyList()));a.settle()
        val record=SharedRecord("test.note","one",1,payload="opaque".toByteArray().toList())
        a.engine.edit { it.copy(sharedRecords=listOf(record)) };a.settle()
        val b=client(remote,binding);b.settle();b.engine.edit { it.copy(entries=listOf(entry("x","older edit"))) };b.settle();a.settle()
        assertEquals(listOf(record),a.engine.local().sharedRecords)
        val budget=V3Budget();val cache=V3IndexedCache(a.dir,budget)
        val history=V3History(a.journal,cache,remote,binding.getString("descriptorPath"),binding.getString("descriptorHash"),budget).open()
        val path=a.journal.snapshot().getString("baselinePath");history.verify(path);val epoch=history.objectMetadata(path).epochId
        a.engine.rotate(history,remote,epoch,history.epochTips(epoch));a.settle();b.settle()
        assertEquals(listOf(record),b.engine.local().sharedRecords)
        a.journal.close();b.journal.close()
    }
    @Test fun malformedKnownRemotePreservesLocalBaselineAndPending() {
        val remote=Remote();val a=client(remote);a.engine.initialize(remote,ScheduleState(entries=listOf(entry("x")),sharedRecords=emptyList()));a.settle()
        a.engine.setCourseColor(null,"x",0x010203);a.settle();val before=a.journal.snapshot()
        val parentBytes=remote.objects.getValue(before.getString("baselinePath"));val parent=SyncV3Codec.decode(parentBytes)
        val child=parent.copy(type=3,objectId=id(),refs=listOf(V3Ref(1,parent.type,parent.epochId,parent.epochNumber,parent.objectId,SyncV3Codec.hash(parentBytes))))
        val bytes=SyncV3Codec.encode(child);val body=bytes.copyOfRange(0,bytes.size-32);val marker=byteArrayOf(0,0,0,4,1,1,2,3)
        val pos=(0..body.size-marker.size).first { body.copyOfRange(it,it+marker.size).contentEquals(marker) };body[pos+4]=2
        remote.objects[V3Paths.path(child)]=body+java.security.MessageDigest.getInstance("SHA-256").digest(body)
        var refused=false;repeat(6){if(!refused)try{a.round()}catch(e:Exception){refused=true}}
        assertTrue(refused);val after=a.journal.snapshot()
        listOf("local","baseline","pending").forEach { assertEquals(before.opt(it)?.toString(),after.opt(it)?.toString()) };a.journal.close()
    }
    @Test fun sharedUpgradeRejectsIdenticalBytesAfterUnpublishedSourceEdit() {
        val remote=Remote();val source=client(remote);source.engine.initialize(remote,ScheduleState(entries=listOf(entry("x"))));source.settle()
        val capture=remote.objects.getValue(source.journal.snapshot().getString("baselinePath"));val target=client(remote)
        val p=target.engine.previewSharedUpgrade(capture,source.engine)
        source.engine.edit { it.copy(entries=listOf(entry("x","unpublished"))) }
        assertThrows(Exception::class.java){target.engine.confirmSharedUpgrade(p.getString("id"),capture,remote,source.engine)}
        assertThrows(Exception::class.java){target.engine.previewSharedUpgrade(capture,source.engine)}
        assertFalse(target.journal.snapshot().has("binding"));source.settle()
        assertThrows(Exception::class.java){target.engine.confirmSharedUpgrade(p.getString("id"),capture,remote,source.engine)}
        target.journal.close();source.journal.close()
    }
    @Test fun sharedUpgradeCancellationAndReadbackRecoveryPreservesSource() {
        val remote=Remote();val source=client(remote);source.engine.initialize(remote,ScheduleState(entries=listOf(entry("x"))));source.settle()
        val original=source.journal.snapshot().toString();val capture=remote.objects.getValue(source.journal.snapshot().getString("baselinePath"))
        val target=client(remote);val cancelled=target.engine.previewSharedUpgrade(capture,source.engine);target.engine.cancelSharedUpgrade(cancelled.getString("id"))
        assertThrows(Exception::class.java){target.engine.confirmSharedUpgrade(cancelled.getString("id"),capture,remote,source.engine)}
        assertFalse(target.journal.snapshot().has("binding"))
        val preview=target.engine.previewSharedUpgrade(capture,source.engine);remote.loseResponse=true
        assertThrows(Exception::class.java){target.engine.confirmSharedUpgrade(preview.getString("id"),capture,remote,source.engine)}
        target.journal.close();val reopened=V3Journal(target.dir)
        val resumed=Client(target.dir,reopened,V3Engine(reopened,reopened.snapshot().getString("writerId")),remote)
        remote.loseResponse=false;resumed.engine.confirmSharedUpgrade(preview.getString("id"),capture,remote,source.engine);resumed.settle()
        assertEquals(emptyList<SharedRecord>(),resumed.engine.local().sharedRecords);assertEquals(original,source.journal.snapshot().toString())
        assertEquals(preview.getString("targetDatasetId"),resumed.journal.snapshot().getJSONObject("binding").getString("datasetId"))
        resumed.journal.close();source.journal.close()
    }
    @Test fun extendedUnknownSurvivesWriterReceiptAndReopen() {
        val remote = Remote(); val a = client(remote)
        val extra = SharedRecord("test.note", "one", 1, payload = "opaque".toByteArray().toList())
        val initial = ScheduleState(entries = listOf(entry("x")), sharedRecords = listOf(extra))
        val binding = a.engine.initialize(remote, initial); a.settle()
        val b = client(remote, binding); b.settle()
        assertEquals(listOf(extra), b.engine.local().sharedRecords)
        b.engine.edit { it.copy(entries = listOf(entry("x", "renamed"))) }; b.settle(); a.settle()
        assertEquals("renamed", a.engine.local().entries.single().title)
        assertEquals(listOf(extra), a.engine.local().sharedRecords)
        assertEquals(4, SyncV3Codec.decode(remote.read(binding.getString("descriptorPath"))!!).format)
        assertNotEquals(SyncV3Codec.businessHash(initial), SyncV3Codec.businessHash(initial.copy(sharedRecords = emptyList())))
        b.journal.close(); val reopened = V3Journal(b.dir)
        assertEquals(listOf(extra), V3Engine(reopened, reopened.snapshot().getString("writerId")).local().sharedRecords)
        reopened.close(); a.journal.close()
    }
    @Test fun indexedCacheMoreThan4096EntriesAndColdStart() {
        val root = directory(); var cache = V3IndexedCache(root, V3Budget()); var last: V3Object? = null
        repeat(2100) { val o = V3Object(1, id(), id(), id()); cache.put(V3Paths.path(o), SyncV3Codec.encode(o)); last = o }
        cache = V3IndexedCache(root, V3Budget(maxBytes = 2048)); val o = last!!; val p = V3Paths.path(o); assertArrayEquals(SyncV3Codec.encode(o), cache.get(p))
        cache.blob(p).delete(); assertNull(cache.get(p)); assertThrows(Exception::class.java) { cache.put(p, SyncV3Codec.encode(o.copy(writerId = id()))) }
    }
    @Test fun lostResponseRecoveryNeverRepeatsPutAndReceiptPreservesNewEdit() {
        val root = directory(); var journal = V3Journal(root); val remote = Remote(); val o = V3Object(1, id(), id(), id()); val path = V3Paths.path(o)
        var publisher = V3Publisher(journal, remote); publisher.stage(path, SyncV3Codec.encode(o), JSONObject().put("capture", "original")); remote.loseResponse = true
        assertThrows(Exception::class.java) { publisher.resume() }; assertEquals(1, remote.puts)
        journal.close(); journal = V3Journal(root); publisher = V3Publisher(journal, remote); assertEquals("READBACK_CONFIRMED", publisher.resume()); assertEquals(1, remote.puts)
        journal.transaction { it.put("local", "new edit") }; publisher.receipt { _, _ -> }; assertEquals("new edit", journal.snapshot().getString("local"))
        remote.objects.clear(); val second = V3Journal(directory()); val p2 = V3Publisher(second, remote); p2.stage(path, SyncV3Codec.encode(o), JSONObject()); assertThrows(Exception::class.java) { p2.resume() }
        remote.objects.clear(); assertEquals("UNCERTAIN", p2.resume()); assertEquals(2, remote.puts)
    }
    @Test fun publishes1024AndRotatesWithoutGlobalCommitLimit() {
        val remote = Remote(); val a = client(remote); a.engine.initialize(remote); a.settle()
        repeat(1024) { n -> a.engine.edit(ScheduleState(entries = listOf(entry("a", "edit-$n")))); var result = a.round(); if (result == "ROTATED") result = a.round(); assertEquals("iteration $n", "PUBLISHED", result)
            if (n == 31 || n == 1023) {
                val nodes = a.journal.meta.nodeReads; val reads = remote.reads; val metadata = a.journal.meta.reads; val budget = V3Budget(); val cache = V3IndexedCache(a.dir, budget)
                a.engine.round(remote, remote, cache, budget)
                assertTrue(a.journal.meta.nodeReads - nodes <= 16); assertTrue(a.journal.meta.reads - metadata < 4096); assertEquals(0L, cache.contentReads); assertEquals(reads, remote.reads)
                println("V3Performance publications=${n + 1} nodeReads=${a.journal.meta.nodeReads - nodes} metadataReads=${a.journal.meta.reads - metadata} snapshotReads=${cache.contentReads} GETs=${remote.reads - reads}")
            }
        }
        assertTrue(a.journal.meta.count("epoch") >= 6)
        val reads = remote.reads; a.round(); assertTrue(remote.reads - reads < 20); assertEquals("edit-1023", a.engine.local().entries.single().title)
    }
    private fun history(c: Client, binding: JSONObject): V3History {
        val budget = V3Budget(); val h = V3History(c.journal, V3IndexedCache(c.dir, budget), c.remote, binding.getString("descriptorPath"), binding.getString("descriptorHash"), budget).open()
        c.remote.objects.keys.filter { it != binding.getString("descriptorPath") }.forEach(h::verify); return h
    }
    @Test fun concurrentCandidatesAndLateBranchUseHigherGenerationAndDeduplicate() {
        val remote = Remote(); val a = client(remote); val binding = a.engine.initialize(remote); a.settle(); val b = client(remote, binding); b.settle()
        val ha = history(a, binding); val hb = history(b, binding); val old = a.journal.snapshot().getString("baselinePath"); val source = ha.objectMetadata(old).epochId
        a.engine.rotate(ha, remote, source, listOf(old)); b.engine.rotate(hb, remote, source, listOf(old))
        val targets = mutableListOf<V3Ref>()
        listOf(a to "left", b to "right").forEach { (c, key) ->
            val transition = SyncV3Codec.decode(java.util.Base64.getDecoder().decode(c.journal.snapshot().getJSONObject("pending").getString("bytes"))); val target = transition.refs.single { it.role == 5 }
            targets += target
            val o = V3Object(3, binding.getString("datasetId"), id(), id(), target.epochId, 2, binding.getString("descriptorHash"), listOf(target.copy(role = 1)), ScheduleState(entries = listOf(entry(key))))
            remote.putOnce(V3Paths.path(o), SyncV3Codec.encode(o))
        }
        a.settle(8); assertEquals(setOf("left", "right"), a.engine.local().entries.map { it.id }.toSet())
        val bridges = remote.objects.values.map(SyncV3Codec::decode).filter { it.type == 3 && it.refs.any { r -> r.role == 3 } }; assertTrue(bridges.isNotEmpty()); assertTrue(bridges.all { it.refs.filter { r -> r.role == 3 }.all { r -> r.epochNumber < it.epochNumber } })
        val oldObject = SyncV3Codec.decode(remote.objects.getValue(old)); val late = oldObject.copy(type = 3, objectId = id(), refs = listOf(ref(oldObject, SyncV3Codec.hash(remote.objects.getValue(old)), 1)), state = ScheduleState(entries = listOf(entry("late"))))
        remote.putOnce(V3Paths.path(late), SyncV3Codec.encode(late)); a.settle(8); assertEquals(setOf("late", "left", "right"), a.engine.local().entries.map { it.id }.toSet())
        val puts = remote.puts; a.settle(8); assertEquals(puts, remote.puts)
        val twoRaw = remote.objects.getValue(refPath(binding.getString("datasetId"), targets.first())); val two = SyncV3Codec.decode(twoRaw)
        val lateTwo = two.copy(type = 3, objectId = id(), refs = listOf(ref(two, SyncV3Codec.hash(twoRaw), 1)), state = requireNotNull(two.state).copy(entries = two.state.entries + entry("late-two")))
        val child = late.copy(objectId = id(), refs = listOf(ref(late, SyncV3Codec.hash(SyncV3Codec.encode(late)), 1)), state = requireNotNull(late.state).copy(entries = late.state.entries + entry("late-child")))
        remote.putOnce(V3Paths.path(lateTwo), SyncV3Codec.encode(lateTwo)); remote.putOnce(V3Paths.path(child), SyncV3Codec.encode(child)); a.settle(16)
        assertEquals(setOf("late", "late-child", "late-two", "left", "right"), a.engine.local().entries.map { it.id }.toSet()); val finalPuts = remote.puts; a.settle(8); assertEquals(finalPuts, remote.puts)
    }
    @Test fun checkpointExtraEditAndUncommittedCandidateAreDistinct() {
        val remote = Remote(); val a = client(remote); val binding = a.engine.initialize(remote); a.settle(); val h = history(a, binding)
        val old = a.journal.snapshot().getString("baselinePath"); val source = SyncV3Codec.decode(remote.objects.getValue(old)); val inherited = ref(source, SyncV3Codec.hash(remote.objects.getValue(old)), 2)
        val candidate = source.copy(epochId = id(), objectId = id(), epochNumber = 2, refs = listOf(inherited)); remote.putOnce(V3Paths.path(candidate), SyncV3Codec.encode(candidate)); h.verify(V3Paths.path(candidate)); assertFalse(candidate.epochId in h.activeEpochs())
        val edited = candidate.copy(epochId = id(), objectId = id(), state = ScheduleState(entries = listOf(entry("sneaky")))); remote.putOnce(V3Paths.path(edited), SyncV3Codec.encode(edited)); assertThrows(Exception::class.java) { h.verify(V3Paths.path(edited)) }
    }
    @Test fun coldDeepClosureResumesPersistentValidationStack() {
        val remote = Remote(); val descriptor = V3Object(1, id(), id(), id()); val descriptorRaw = SyncV3Codec.encode(descriptor); remote.putOnce(V3Paths.path(descriptor), descriptorRaw)
        var previous: V3Object? = null
        repeat(2) { epoch ->
            val inherited = previous?.let { listOf(ref(it, SyncV3Codec.hash(remote.objects.getValue(V3Paths.path(it))), 2)) }.orEmpty()
            val genesis = V3Object(2, descriptor.datasetId, id(), descriptor.writerId, id(), epoch + 1, SyncV3Codec.hash(descriptorRaw), inherited, ScheduleState())
            remote.putOnce(V3Paths.path(genesis), SyncV3Codec.encode(genesis)); previous = genesis
            repeat(150) {
                val parent = requireNotNull(previous); val next = genesis.copy(type = 3, objectId = id(), refs = listOf(ref(parent, SyncV3Codec.hash(remote.objects.getValue(V3Paths.path(parent))), 1)))
                remote.putOnce(V3Paths.path(next), SyncV3Codec.encode(next)); previous = next
            }
        }
        val root = directory(); val tip = V3Paths.path(requireNotNull(previous)); var rounds = 0; var complete = false
        while (!complete) {
            val journal = V3Journal(root); val budget = V3Budget(); val h = V3History(journal, V3IndexedCache(root, budget), remote, V3Paths.path(descriptor), SyncV3Codec.hash(descriptorRaw), budget).open()
            try { h.verify(tip) } catch (e: Exception) { assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("budget")); assertTrue(journal.meta.get("control", "walk")!!.getInt("depth") > 0) }
            complete = journal.meta.get("node", tip) != null; journal.close(); assertTrue(++rounds <= 8)
        }
        assertTrue(rounds > 1)
    }
    @Test fun candidateIndexRotationAndActiveReceiptBoundariesRecoverExactly() {
        for (boundary in listOf("index-before", "index-after", "genesis", "transition", "activation", "receipt")) {
            val remote = Remote(); val root = directory(); val writer = id(); var journal = V3Journal(root); var engine = V3Engine(journal, writer)
            val binding = engine.initialize(remote)
            fun round() { val b = V3Budget(); engine.round(remote, remote, V3IndexedCache(root, b), b, 32) }
            repeat(3) { try { round() } catch (e: IllegalStateException) { if (!e.message.orEmpty().contains("incomplete")) throw e } }
            engine.edit(ScheduleState(entries = listOf(entry("saved")))); val rotating = !boundary.startsWith("index-"); if (rotating) round()
            val oldEpoch = journal.snapshot().getString("activeEpoch"); var armed = true; var path = ""
            journal.meta.fault = fault@{ point ->
                if (!armed) return@fault
                val p = journal.meta.get("state", "frame")?.optJSONObject("pending") ?: return@fault
                val o = SyncV3Codec.decode(java.util.Base64.getDecoder().decode(p.getString("bytes"))); val indexed = journal.meta.get("node", p.getString("path")) != null; val phase = p.getString("phase")
                val target = o.refs.firstOrNull { it.role == 5 }; val active = target?.let { journal.meta.get("epoch", it.epochId)?.getBoolean("active") } ?: false
                val match = when (boundary) {
                    "index-before" -> o.type == 3 && phase == "READBACK_CONFIRMED" && indexed && point == "before-commit"
                    "index-after" -> o.type == 3 && phase == "READBACK_CONFIRMED" && indexed && point == "after-commit"
                    "genesis" -> o.type == 2 && phase == "READBACK_CONFIRMED" && indexed && point == "after-commit"
                    "transition" -> o.type == 4 && phase == "READBACK_CONFIRMED" && indexed && !active && point == "after-commit"
                    "activation" -> o.type == 4 && phase == "READBACK_CONFIRMED" && active && point == "after-commit"
                    else -> o.type == 4 && phase == "RECEIPTED" && point == "after-commit"
                }
                if (match) { armed = false; path = p.getString("path"); error("injected boundary") }
            }
            assertThrows(Exception::class.java) {
                if (!rotating) round() else { val b = V3Budget(); val h = V3History(journal, V3IndexedCache(root, b), remote, binding.getString("descriptorPath"), binding.getString("descriptorHash"), b).open(); engine.rotate(h, remote, oldEpoch, h.epochTips(oldEpoch)) }
            }
            assertFalse(boundary, armed); val published = remote.puts; journal.close(); journal = V3Journal(root); engine = V3Engine(journal, writer)
            assertEquals("saved", engine.local().entries.single().id)
            if (boundary == "index-before") assertNull(journal.meta.get("node", path)) else assertNotNull(journal.meta.get("node", path))
            if (rotating && boundary != "receipt") assertEquals(oldEpoch, journal.snapshot().getString("activeEpoch"))
            round(); round(); assertEquals(if (boundary == "genesis") published + 1 else published, remote.puts)
            val receipt = journal.snapshot().getJSONObject("receipt"); assertEquals(receipt.getString("hash"), journal.meta.get("node", receipt.getString("path"))!!.getString("hash")); assertEquals("saved", engine.local().entries.single().id)
            if (rotating) assertNotEquals(oldEpoch, journal.snapshot().getString("activeEpoch")); journal.close()
        }
    }
    @Test fun conflictingSourceAtRotationThresholdPublishesDecisionBeforeCheckpoint() {
        val remote = Remote(); val a = client(remote); val binding = a.engine.initialize(remote); a.settle()
        repeat(158) { n -> a.engine.edit(ScheduleState(entries = listOf(entry("x", n.toString())))); assertEquals("PUBLISHED", a.round()) }
        val raw = remote.objects.getValue(a.journal.snapshot().getString("baselinePath")); val base = SyncV3Codec.decode(raw); val b = client(remote, binding); b.settle()
        a.engine.edit(ScheduleState(entries = listOf(entry("x", "left")))); a.round()
        val right = base.copy(objectId = id(), writerId = b.journal.snapshot().getString("writerId"), refs = listOf(ref(base, SyncV3Codec.hash(raw), 1)), state = ScheduleState(entries = listOf(entry("x", "right"))))
        remote.putOnce(V3Paths.path(right), SyncV3Codec.encode(right)); b.engine.edit(requireNotNull(right.state))
        fun advance(): String { repeat(12) { try { return b.round() } catch (e: IllegalStateException) { if (!e.message.orEmpty().contains("budget")) throw e } }; error("proof did not resume") }
        assertEquals("CONFLICT", advance()); b.engine.resolve(b.journal.snapshot().getJSONObject("conflict").getString("id"), ScheduleMerge.Choice.LOCAL)
        assertEquals("PUBLISHED", advance()); val resolved = SyncV3Codec.decode(java.util.Base64.getDecoder().decode(b.journal.snapshot().getJSONObject("pending").getString("bytes")))
        assertEquals(1, resolved.epochNumber); assertEquals(3, resolved.type); assertEquals(2, resolved.refs.count { it.role == 1 })
        b.engine.edit(b.engine.local().copy(entries = b.engine.local().entries + entry("after-decision"))); assertEquals("ROTATED", advance())
        val transition = SyncV3Codec.decode(java.util.Base64.getDecoder().decode(b.journal.snapshot().getJSONObject("pending").getString("bytes"))); val target = transition.refs.single { it.role == 5 }
        val checkpoint = SyncV3Codec.decode(remote.objects.getValue(refPath(binding.getString("datasetId"), target))); assertEquals("right", checkpoint.state!!.entries.single().title)
        assertEquals("PUBLISHED", advance()); assertTrue(b.engine.local().entries.any { it.id == "after-decision" })
    }
    @Test fun receiptConflictSurvivesRestartAndCannotAutoChooseLocal() {
        val remote = Remote(); val a = client(remote); val initial = ScheduleState(entries = listOf(entry("x", "base"))); val binding = a.engine.initialize(remote, initial); a.settle()
        val b = client(remote, binding); b.settle(); b.engine.edit(ScheduleState(entries = listOf(entry("x", "remote")))); b.settle()
        a.engine.edit(initial.copy(entries = initial.entries + entry("z"))); remote.duringPut = { a.engine.edit(ScheduleState(entries = listOf(entry("x", "during"), entry("z")))) }
        assertEquals("CONFLICT", a.round()); val puts = remote.puts; val writer = a.journal.snapshot().getString("writerId"); a.journal.close()
        val j = V3Journal(a.dir); val engine = V3Engine(j, writer)
        fun round(): String { val budget = V3Budget(); return engine.round(remote, remote, V3IndexedCache(a.dir, budget), budget, 8) }
        assertEquals("CONFLICT", round()); assertEquals(puts, remote.puts); val oldId = j.snapshot().getJSONObject("conflict").getString("id")
        engine.edit(engine.local().copy(entries = engine.local().entries + entry("later"))); assertEquals("CONFLICT", round()); assertThrows(Exception::class.java) { engine.resolve(oldId, ScheduleMerge.Choice.LOCAL) }
        engine.resolve(j.snapshot().getJSONObject("conflict").getString("id"), ScheduleMerge.Choice.LOCAL); round(); b.settle()
        assertEquals("during", b.engine.local().entries.single { it.id == "x" }.title); assertTrue(b.engine.local().entries.any { it.id == "z" }); assertTrue(b.engine.local().entries.any { it.id == "later" }); j.close()
    }
}
