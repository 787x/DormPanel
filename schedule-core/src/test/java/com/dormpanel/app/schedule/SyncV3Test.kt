package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Properties
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer

class SyncV3Test {
    private val root = File("src/test/resources/sync-v3")
    private fun raw(name: String) = File(root, "$name.dpv").readBytes()
    private fun cacheDir() = File(Files.createTempDirectory("dp-v3-").toFile(), "sync-v3-cache-v1")
    @Test fun independentVectorsAndIdentity() {
        val p = Properties().apply { File(root, "manifest.properties").inputStream().use(::load) }
        root.listFiles()!!.filter { it.extension == "dpv" }.forEach { f ->
            val b = f.readBytes()
            if (f.name.startsWith("bad-")) assertThrows(Exception::class.java) { SyncV3Codec.decode(b) }
            else {
                val o = SyncV3Codec.decode(b); val n = f.nameWithoutExtension
                assertArrayEquals(b, SyncV3Codec.encode(o)); assertEquals(p.getProperty("$n.hash"), SyncV3Codec.hash(b))
                assertEquals(p.getProperty("$n.size").toInt(), b.size)
                o.state?.let { assertEquals(p.getProperty("$n.business"), SyncV3Codec.businessHash(it)) }
                SyncV3Codec.check(b, o.datasetId, o.objectId, SyncV3Codec.hash(b))
                assertThrows(Exception::class.java) { SyncV3Codec.check(b, o.writerId, o.objectId, SyncV3Codec.hash(b)) }
            }
        }
    }
    @Test fun businessHashMatchesMergeSemantics() {
        val o = SyncV3Codec.decode(raw("commit-complex")); val s = o.state!!
        val shifted = s.copy(sources = s.sources.map { it.copy(importedAt = it.importedAt + 30) })
        assertTrue(ScheduleMerge.same(s, shifted)); assertEquals(SyncV3Codec.businessHash(s), SyncV3Codec.businessHash(shifted))
        assertFalse(SyncV3Codec.encode(o).contentEquals(SyncV3Codec.encode(o.copy(state = shifted))))
        val edited = s.copy(events = s.events.map { it.copy(note = it.note + "x") })
        assertNotEquals(SyncV3Codec.businessHash(s), SyncV3Codec.businessHash(edited))
        val ref = o.refs.single(); val genesis = raw("genesis-empty")
        SyncV3Codec.checkReference(genesis, o.datasetId, o.descriptorHash, ref)
        assertThrows(Exception::class.java) { SyncV3Codec.checkReference(genesis, o.datasetId, o.descriptorHash, ref.copy(epochNumber = 2)) }
        assertThrows(Exception::class.java) { SyncV3Codec.checkReference(genesis, o.datasetId, SyncV3Codec.ZERO_HASH, ref) }
    }
    @Test fun crashRebuildHitMissingReadbackCollisionAndEvidence() {
        val dir = cacheDir(); val b = raw("commit-complex"); val o = SyncV3Codec.decode(b); val path = V3Paths.path(o)
        val crashed = V3ObjectCache(dir, V3Budget()) { error("crash") }
        assertThrows(Exception::class.java) { crashed.put(path, b) }
        val cache = V3ObjectCache(dir, V3Budget()); assertEquals(1, cache.index().size)
        var reads = 0
        val remote = object : V3ObjectStore {
            override fun list(scope: String) = emptyList<String>()
            override fun read(path: String): ByteArray { reads++; return b }
            override fun createForIsolatedTest(path: String, bytes: ByteArray, beforeWriteIntent: () -> Unit) = error("unused")
        }
        assertArrayEquals(b, cache.load(path, remote)); assertEquals(0, reads)
        File(dir, "${SyncV3Codec.hash(b)}.dpv").delete()
        val restarted = V3ObjectCache(dir, V3Budget()); assertTrue(restarted.index().isEmpty())
        assertThrows(Exception::class.java) { restarted.put(path, SyncV3Codec.encode(o.copy(writerId = o.datasetId))) }
        assertArrayEquals(b, restarted.load(path, remote)); assertEquals(1, reads)
        val round = V3Observation("endpoint", "round")
        assertEquals(V3Evidence(true, false, false, false), round.evidence(path, true))
        round.observe(path); assertEquals(V3Evidence(true, true, false, false), round.evidence(path, true))
        round.recheck(path, b, SyncV3Codec.hash(b)); assertFalse(round.evidence(path, true).closureVerifiedThisRound)
        assertFalse(V3Observation("endpoint", "next").evidence(path, true).observedThisRound)
        val localClosure = V3Observation("endpoint", "local-only"); localClosure.recordVerifiedClosure(setOf(path))
        assertEquals(V3Evidence(true, false, false, true), localClosure.evidence(path, true))
        File(dir, ".partial.tmp").writeText("incomplete"); restarted.recover()
        assertThrows(Exception::class.java) { V3ObjectCache(dir, V3Budget(maxBytes = 100)) }
        File(dir, "${SyncV3Codec.hash(b)}.dpv").writeText("broken")
        assertThrows(Exception::class.java) { restarted.get(path) }
    }
    @Test fun orphanRebuildAndBudgetAndPaths() {
        val dir = cacheDir(); dir.mkdirs(); val b = raw("genesis-empty")
        File(dir, "${SyncV3Codec.hash(b)}.dpv").writeBytes(b)
        assertEquals(1, V3ObjectCache(dir, V3Budget()).index().size)
        assertThrows(Exception::class.java) { V3Budget(maxRequests = 0).charge() }
        assertThrows(Exception::class.java) { V3Budget(maxBytes = 1).charge(2, 0) }
        assertThrows(Exception::class.java) { V3Budget(maxRequests = 513) }
        var now = 0L; val budget = V3Budget(maxMillis = 10, clock = { now }); now = 10
        assertThrows(Exception::class.java) { budget.charge() }
        listOf("../x", "/dataset-x/", "dormpanel-sync-v2/").forEach { assertThrows(Exception::class.java) { V3Paths.scope(it) } }
        assertThrows(Exception::class.java) { V3Paths.file("../a.dpv") }
    }
    @Test fun webdavCreateReadbackIntentAndOccupied() = MockWebServer().use { server ->
        val b = raw("genesis-empty"); val path = V3Paths.path(SyncV3Codec.decode(b))
        server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setResponseCode(201)); server.enqueue(MockResponse().setBody(Buffer().write(b)))
        val account = WebDavAccount(server.url("/isolated/").toString(), "u", "p")
        val store = V3WebDavStore(account, account.baseUrl, V3Budget()); var intent = false
        store.createForIsolatedTest(path, b) { intent = true }; assertTrue(intent)
        val requests = List(3) { server.takeRequest() }; assertEquals(listOf("GET", "PUT", "GET"), requests.map { it.method })
        assertTrue(requests.all { it.path == "/isolated/dormpanel-sync-v3/$path" && it.getHeader("If-Match") == null && it.getHeader("If-None-Match") == null })
        server.enqueue(MockResponse().setBody(Buffer().write(SyncV3Codec.encode(SyncV3Codec.decode(b).copy(writerId = SyncV3Codec.decode(b).datasetId)))))
        assertThrows(Exception::class.java) { store.createForIsolatedTest(path, b) { fail() } }
        Unit
    }
    @Test fun interruptedPutNeverReplayedAndReadbackFailureNotSuccess() = MockWebServer().use { server ->
        val b = raw("genesis-empty"); val path = V3Paths.path(SyncV3Codec.decode(b)); val account = WebDavAccount(server.url("/isolated/").toString(), "u", "p")
        server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertThrows(Exception::class.java) { V3WebDavStore(account, account.baseUrl, V3Budget()).createForIsolatedTest(path, b) {} }
        assertEquals(2, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setResponseCode(201)); server.enqueue(MockResponse().setBody("broken"))
        assertThrows(Exception::class.java) { V3WebDavStore(account, account.baseUrl, V3Budget()).createForIsolatedTest(path, b) {} }
        Unit
    }
    @Test fun strictListingRejectsBadPropsDuplicatesPathsAndBudget() = MockWebServer().use { server ->
        val o = SyncV3Codec.decode(raw("genesis-empty")); val scope = V3Paths.epoch(o.datasetId, o.epochId)
        val account = WebDavAccount(server.url("/isolated/").toString(), "u", "p")
        val path = "/isolated/dormpanel-sync-v3/" + V3Paths.path(o)
        fun row(href: String, status: String = "200 OK") = "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 $status</d:status></d:propstat></d:response>"
        fun response(rows: String) = MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">$rows</d:multistatus>")
        val store = V3WebDavStore(account, account.baseUrl, V3Budget())
        server.enqueue(response(row(path))); assertEquals(listOf(V3Paths.path(o)), store.list(scope))
        listOf(row(path) + row(path), row("/outside/x"), row(path + "/x"), row(path.replace("genesis-", "./genesis-")), row(path, "403 Forbidden"),
            row(path).replace("</d:response>", "<d:propstat><d:prop/><d:status>HTTP/1.1 403 Forbidden</d:status></d:propstat></d:response>")).forEach {
            server.enqueue(response(it)); assertThrows(Exception::class.java) { store.list(scope) }
        }
        val before = server.requestCount
        assertThrows(Exception::class.java) { V3WebDavStore(account, account.baseUrl, V3Budget(maxBytes = 100)).list(scope) }
        assertEquals(before, server.requestCount)
    }
}
