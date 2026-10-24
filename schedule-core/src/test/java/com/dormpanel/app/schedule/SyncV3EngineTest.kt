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
        val objects = linkedMapOf<String, ByteArray>(); var puts = 0; var reads = 0; var loseResponse = false
        override fun read(path: String): ByteArray? { reads++; return objects[path]?.copyOf() }
        override fun putOnce(path: String, raw: ByteArray) { check(!objects.containsKey(path)); puts++; objects[path] = raw.copyOf(); if (loseResponse) error("lost response") }
        override fun prepare(scope: String) {}
        override fun list(scope: String): List<String> = objects.keys.filter { it.startsWith(scope) }.map { p -> val tail = p.removePrefix(scope); scope + if ('/' in tail) tail.substringBefore('/') + "/" else tail }.distinct().sorted().also { require(it.size <= 256) }
        override fun createForIsolatedTest(path: String, bytes: ByteArray, beforeWriteIntent: () -> Unit) = error("engine must not invoke test primitive")
    }
    private class Client(val dir: File, val journal: V3Journal, val engine: V3Engine, val remote: Remote) {
        fun round(): String { val budget = V3Budget(); return engine.round(remote, remote, V3IndexedCache(dir, budget), budget, 32) }
        fun settle(rounds: Int = 3): String { var result = ""; repeat(rounds) { try { result = round() } catch (e: IllegalStateException) { if (!e.message.orEmpty().contains("incomplete")) throw e } }; return result }
    }
    private fun client(remote: Remote, binding: JSONObject? = null): Client {
        val dir = directory(); val journal = V3Journal(dir); val engine = V3Engine(journal, id())
        if (binding != null) engine.bind(SyncV3Codec.decode(requireNotNull(remote.read(binding.getString("descriptorPath")))), binding.getString("descriptorHash"))
        return Client(dir, journal, engine, remote)
    }
    @Test fun independentEditsAndRestartableConflict() {
        val remote = Remote(); val a = client(remote); val binding = a.engine.initialize(remote); a.settle(); val b = client(remote, binding); b.settle()
        a.engine.edit(ScheduleState(entries = listOf(entry("a")))); b.engine.edit(ScheduleState(entries = listOf(entry("b"))))
        a.settle(); b.settle(); a.settle(); assertEquals(setOf("a", "b"), a.engine.local().entries.map { it.id }.toSet())
        a.engine.edit(a.engine.local().copy(entries = a.engine.local().entries.map { if (it.id == "a") entry("a", "left") else it }))
        b.engine.edit(b.engine.local().copy(entries = b.engine.local().entries.map { if (it.id == "a") entry("a", "right") else it }))
        a.settle(); assertEquals("CONFLICT", b.settle(1)); val reopened = V3Journal(b.dir); val conflict = reopened.snapshot().getJSONObject("conflict")
        b.engine.resolve(conflict.getString("id"), ScheduleMerge.Choice.LOCAL); b.settle(); a.settle(); assertEquals("right", a.engine.local().entries.single { it.id == "a" }.title)
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
        journal = V3Journal(root); publisher = V3Publisher(journal, remote); assertEquals("READBACK_CONFIRMED", publisher.resume()); assertEquals(1, remote.puts)
        journal.transaction { it.put("local", "new edit") }; publisher.receipt { _, _ -> }; assertEquals("new edit", journal.snapshot().getString("local"))
        remote.objects.clear(); val second = V3Journal(directory()); val p2 = V3Publisher(second, remote); p2.stage(path, SyncV3Codec.encode(o), JSONObject()); assertThrows(Exception::class.java) { p2.resume() }
        remote.objects.clear(); assertEquals("UNCERTAIN", p2.resume()); assertEquals(2, remote.puts)
    }
    @Test fun publishes1024AndRotatesWithoutGlobalCommitLimit() {
        val remote = Remote(); val a = client(remote); a.engine.initialize(remote); a.settle()
        repeat(1024) { n -> a.engine.edit(ScheduleState(entries = listOf(entry("a", "edit-$n")))); var result = a.round(); if (result == "ROTATED") result = a.round(); assertEquals("iteration $n", "PUBLISHED", result) }
        assertTrue(a.journal.snapshot().getJSONObject("history").getJSONObject("roots").length() >= 6)
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
        listOf(a to "left", b to "right").forEach { (c, key) ->
            val transition = SyncV3Codec.decode(java.util.Base64.getDecoder().decode(c.journal.snapshot().getJSONObject("pending").getString("bytes"))); val target = transition.refs.single { it.role == 5 }
            val o = V3Object(3, binding.getString("datasetId"), id(), id(), target.epochId, 2, binding.getString("descriptorHash"), listOf(target.copy(role = 1)), ScheduleState(entries = listOf(entry(key))))
            remote.putOnce(V3Paths.path(o), SyncV3Codec.encode(o))
        }
        a.settle(8); assertEquals(setOf("left", "right"), a.engine.local().entries.map { it.id }.toSet())
        val bridges = remote.objects.values.map(SyncV3Codec::decode).filter { it.type == 3 && it.refs.any { r -> r.role == 3 } }; assertTrue(bridges.isNotEmpty()); assertTrue(bridges.all { it.refs.filter { r -> r.role == 3 }.all { r -> r.epochNumber < it.epochNumber } })
        val oldObject = SyncV3Codec.decode(remote.objects.getValue(old)); val late = oldObject.copy(type = 3, objectId = id(), refs = listOf(ref(oldObject, SyncV3Codec.hash(remote.objects.getValue(old)), 1)), state = ScheduleState(entries = listOf(entry("late"))))
        remote.putOnce(V3Paths.path(late), SyncV3Codec.encode(late)); a.settle(8); assertEquals(setOf("late", "left", "right"), a.engine.local().entries.map { it.id }.toSet())
        val puts = remote.puts; a.settle(8); assertEquals(puts, remote.puts)
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
            try { h.verify(tip) } catch (e: Exception) { assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("budget")); assertTrue(journal.snapshot().getJSONObject("history").getJSONObject("walk").getJSONArray("stack").length() > 0) }
            complete = journal.snapshot().getJSONObject("history").getJSONObject("nodes").has(tip); assertTrue(++rounds <= 8)
        }
        assertTrue(rounds > 1)
    }
}
