package com.dormpanel.app.schedule

import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WebDavCommitStoreTest {
    private fun commit() = ScheduleCommitCodec.encode(ScheduleCommit(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), emptyList(), ScheduleState()))
    @Test fun ordinaryPutReadbackAndRetryNeverUseConditionalHeaders() = MockWebServer().use { server ->
        val bytes = commit()
        server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setBody(Buffer().write(bytes))); server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val account = WebDavAccount(server.url("/v2/").toString(), "u", "p")
        val store = WebDavCommitStore(account, account.baseUrl)
        store.publish(bytes); store.publish(bytes, false)
        val requests = List(4) { server.takeRequest() }
        assertEquals(listOf("GET", "PUT", "GET", "GET"), requests.map { it.method })
        assertTrue(requests.all { it.getHeader("If-Match") == null && it.getHeader("If-None-Match") == null })
    }
    @Test fun differentExistingFileAndUncertain404NeverOverwrite() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("broken")); server.enqueue(MockResponse().setResponseCode(404))
        val account = WebDavAccount(server.url("/v2/").toString(), "u", "p")
        val store = WebDavCommitStore(account, account.baseUrl)
        assertThrows(CommitPathOccupied::class.java) { store.publish(commit()) }
        assertThrows(CommitPathOccupied::class.java) { store.publish(commit(), false) }
        assertEquals(2, server.requestCount)
    }
    @Test fun partialUploadReadbackCannotSucceed() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setResponseCode(201)); server.enqueue(MockResponse().setBody("partial"))
        val account = WebDavAccount(server.url("/v2/").toString(), "u", "p")
        assertThrows(IllegalStateException::class.java) { WebDavCommitStore(account, account.baseUrl).publish(commit()) }; Unit
    }
    @Test fun interruptedPutIsNotAutomaticallyReplayedByHttpClient() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val account = WebDavAccount(server.url("/v2/").toString(), "u", "p")
        assertThrows(WebDavException::class.java) { WebDavCommitStore(account, account.baseUrl).publish(commit()) }
        assertEquals(2, server.requestCount)
    }
    @Test fun failedPreflightReadDoesNotMarkPutAttempted() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(503))
        val account = WebDavAccount(server.url("/v2/").toString(), "u", "p")
        var attempted = false
        assertThrows(WebDavException::class.java) { WebDavCommitStore(account, account.baseUrl).publishWithIntent(commit(), true) { attempted = true } }
        assertFalse(attempted); assertEquals(1, server.requestCount)
    }
    @Test fun immutablePutRedirectNeverReplaysTheWrite() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "/v2/redirect.dpc"))
        val account = WebDavAccount(server.url("/v2/").toString(), "u", "p")
        assertThrows(WebDavException::class.java) { WebDavCommitStore(account, account.baseUrl).publish(commit()) }
        assertEquals(2, server.requestCount)
    }
    @Test fun duplicateAndEscapingListingRefuse() {
        val client = WebDavClient()
        val response = "<d:response><d:href>/v2/commit-11111111-1111-4111-8111-111111111111.dpc</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        val xml = "<d:multistatus xmlns:d=\"DAV:\">$response$response</d:multistatus>".toByteArray()
        assertThrows(WebDavException::class.java) { client.parseListing(xml, client.url("https://example.org/v2/"), true) }
    }
    @Test fun diagnosticConcurrentWritersAndDelayedListing() = MockWebServer().use { server ->
        val files = ConcurrentHashMap<String, ByteArray>(); var listings = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse = when(r.method) {
                "MKCOL" -> MockResponse().setResponseCode(201)
                "GET" -> files[r.path]?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                "PUT" -> { assertNull(r.getHeader("If-Match")); assertNull(r.getHeader("If-None-Match")); files[r.path!!] = r.body.readByteArray(); MockResponse().setResponseCode(201) }
                "PROPFIND" -> {
                    assertEquals("1", r.getHeader("Depth")); listings++
                    val rows = if (listings == 1) "" else files.keys.sorted().joinToString("") {
                        "<d:response><d:href>$it</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                    }
                    MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">$rows</d:multistatus>")
                }
                else -> MockResponse().setResponseCode(405)
            }
        }
        val account = WebDavAccount(server.url("/test/").toString(), "u", "p")
        assertTrue(AppendOnlyWebDavDiagnostic.run(account, account.baseUrl).contains("第 2 次"))
        assertEquals(2, files.size)
    }
}
