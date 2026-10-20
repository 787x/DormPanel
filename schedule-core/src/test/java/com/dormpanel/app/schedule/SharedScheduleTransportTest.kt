package com.dormpanel.app.schedule

import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class SharedScheduleTransportTest {
    private class Dav : Dispatcher() {
        val files = ConcurrentHashMap<String, ByteArray>()
        var ignoreConditions = false
        var ignoreMatch = false
        var rejectSnapshot = false
        var conflictHead = false
        var unauthorized = false
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (unauthorized) return MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic")
            if (request.getHeader("Authorization") != "Basic dXNlcjpzZWNyZXQ=") return MockResponse().setResponseCode(401)
            val path = request.path!!
            val old = files[path]
            val etag = old?.let { "\"${digest(it)}\"" }
            if (request.method == "GET") return if (old == null) MockResponse().setResponseCode(404)
                else MockResponse().setBody(okio.Buffer().write(old)).addHeader("ETag", etag!!)
            if (request.method != "PUT") return MockResponse().setResponseCode(405)
            if (rejectSnapshot && path.contains("snapshot-")) return MockResponse().setResponseCode(500)
            if (conflictHead && path.endsWith("dormpanel-head-v1")) return MockResponse().setResponseCode(412)
            if (!ignoreConditions && (request.getHeader("If-None-Match") == "*" && old != null ||
                !ignoreMatch && request.getHeader("If-Match")?.let { it != etag } == true)) return MockResponse().setResponseCode(412)
            files[path] = request.body.readByteArray()
            return MockResponse().setResponseCode(if (old == null) 201 else 204)
        }
    }
    @Test fun publishBindUpdateHistoryAndAuthentication() {
        MockWebServer().use { server ->
            val dav = Dav(); server.dispatcher = dav
            val account = WebDavAccount(server.url("/dav/").toString(), "user", "secret")
            val dir = server.url("/dav/share/").toString(); val transport = SharedScheduleTransport()
            val first = SnapshotFixture.snapshot(); val b1 = ScheduleSnapshotCodec.encode(first)
            val e1 = transport.publish(account, dir, b1, null, null)
            val received = transport.inspect(account, dir)
            assertEquals(first, received.snapshot); assertArrayEquals(b1, received.bytes)
            val next = SnapshotFixture.snapshot(parent = first.revision)
            val e2 = transport.publish(account, dir, ScheduleSnapshotCodec.encode(next), first.revision, e1)
            assertNotEquals(e1, e2); assertEquals(next, transport.inspect(account, dir).snapshot)
            assertTrue(dav.files.keys.any { it.contains(first.revision) })
            assertThrows(Exception::class.java) { transport.publish(account, dir, b1, null, null) }
            assertThrows(Exception::class.java) { transport.publish(account, dir, ScheduleSnapshotCodec.encode(next), first.revision, e1) }
        }
    }
    @Test fun ignoredPreconditionsNeverActivateSnapshot() {
        listOf(false, true).forEach { onlyMatch -> MockWebServer().use { server ->
            val dav = Dav().apply { ignoreConditions = !onlyMatch; ignoreMatch = onlyMatch }; server.dispatcher = dav
            val account = WebDavAccount(server.url("/dav/").toString(), "user", "secret")
            assertThrows(Exception::class.java) { SharedScheduleTransport().publish(account, account.baseUrl, ScheduleSnapshotCodec.encode(SnapshotFixture.snapshot()), null, null) }
            assertFalse(dav.files.keys.any { it.contains("snapshot-") || it.endsWith("dormpanel-head-v1") })
        } }
    }
    @Test fun failedUploadAndPointerConflictRetainPublishedVersion() {
        MockWebServer().use { server ->
            val dav = Dav(); server.dispatcher = dav
            val a = WebDavAccount(server.url("/dav/").toString(), "user", "secret"); val t = SharedScheduleTransport()
            val first = SnapshotFixture.snapshot(); val etag = t.publish(a, a.baseUrl, ScheduleSnapshotCodec.encode(first), null, null)
            val next = SnapshotFixture.snapshot(parent = first.revision); val bytes = ScheduleSnapshotCodec.encode(next)
            dav.rejectSnapshot = true
            assertThrows(Exception::class.java) { t.publish(a, a.baseUrl, bytes, first.revision, etag) }
            assertEquals(first, t.inspect(a, a.baseUrl).snapshot)
            dav.rejectSnapshot = false; dav.conflictHead = true
            assertThrows(Exception::class.java) { t.publish(a, a.baseUrl, bytes, first.revision, etag) }
            assertEquals(first, t.inspect(a, a.baseUrl).snapshot)
        }
    }
    @Test fun damagedPayloadAndWrongPasswordCannotReturnUsableSnapshot() {
        MockWebServer().use { server ->
            val dav = Dav(); server.dispatcher = dav
            val a = WebDavAccount(server.url("/dav/").toString(), "user", "secret"); val t = SharedScheduleTransport()
            val first = SnapshotFixture.snapshot(); t.publish(a, a.baseUrl, ScheduleSnapshotCodec.encode(first), null, null)
            dav.files["/dav/snapshot-${first.revision}.dps"] = byteArrayOf(1, 2)
            assertThrows(Exception::class.java) { t.inspect(a, a.baseUrl) }
            dav.unauthorized = true
            val error = assertThrows(WebDavException::class.java) { t.inspect(a, a.baseUrl) }
            assertTrue(error.reason.contains("Authentication")); assertFalse(error.reason.contains("secret"))
        }
    }
    @Test fun originRedirectAndStrongEtagBoundaries() {
        MockWebServer().use { server -> MockWebServer().use { other ->
            val a = WebDavAccount(server.url("/dav/").toString(), "user", "secret")
            assertThrows(Exception::class.java) { SharedScheduleTransport().folder(a, other.url("/share/").toString()) }
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", other.url("/dav/")))
            assertThrows(WebDavException::class.java) { SharedScheduleTransport().head(a, a.baseUrl) }
            assertEquals(0, other.requestCount)
            val client = WebDavClient()
            listOf(null, "W/\"weak\"", "*", "bad", "\"x\"\r\nInjected: value").forEach { assertThrows(WebDavException::class.java) { client.requireStrongEtag(it) } }
        } }
    }
    @Test fun truncatedResponseAndConditional409AreExplicitFailures() {
        MockWebServer().use { server ->
            val a = WebDavAccount(server.url("/dav/").toString(), "user", "secret")
            server.enqueue(MockResponse().setBody("a".repeat(1000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            assertThrows(WebDavException::class.java) { WebDavClient().readResource(a, a.baseUrl, 2000) }
            server.enqueue(MockResponse().setResponseCode(409))
            assertEquals(409, WebDavClient().conditionalPut(a, a.baseUrl, byteArrayOf(1), null))
            server.takeRequest()
            assertEquals("*", server.takeRequest().getHeader("If-None-Match"))
        }
    }
    @Test fun interruptedUploadFailsWithoutAnUnconditionalRetry() {
        MockWebServer().use { server ->
            val account = WebDavAccount(server.url("/dav/").toString(), "user", "secret")
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY))
            val http = okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false)
                .callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
            assertThrows(WebDavException::class.java) { WebDavClient(http).conditionalPut(account, server.url("/dav/snapshot-test.dps").toString(), ByteArray(64 * 1024), null) }
            assertEquals(1, server.requestCount)
            assertEquals("*", server.takeRequest().getHeader("If-None-Match"))
        }
    }
}
