package com.dormpanel.app.lan

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TemporarySettingsEntryServerTest {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    private fun post(url: String, body: String, origin: String? = null) = client.newCall(Request.Builder().url(url)
        .apply { origin?.let { header("Origin", it) } }
        .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()).execute()

    private fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute()
    private fun closed(server: TemporarySettingsEntryServer) =
        runCatching { Socket("127.0.0.1", server.port).use {} }.isFailure

    @Test fun capabilityAndOriginChecksAndOneShotDelivery() {
        val latch = CountDownLatch(1)
        val count = AtomicInteger()
        var submitted = emptyMap<String, String>()
        TemporarySettingsEntryServer.start("127.0.0.1", { nonce, _ ->
            """<!doctype html><script nonce="$nonce">void(0)</script>"""
        }, { SettingsEntryReply(200, "pending", terminal = true) }, {
            submitted = it
            count.incrementAndGet()
            latch.countDown()
        }).use { server ->
            assertTrue(server.token.matches(Regex("[0-9a-f]{48}")))
            get(server.url).use {
                assertEquals(200, it.code)
                assertEquals("no-store", it.header("Cache-Control"))
                assertEquals("nosniff", it.header("X-Content-Type-Options"))
                assertEquals("no-referrer", it.header("Referrer-Policy"))
                assertEquals("DENY", it.header("X-Frame-Options"))
                assertTrue(it.header("Content-Security-Policy")!!.contains("script-src 'nonce-${server.token}'"))
            }
            post(server.url.replace(server.token, "z".repeat(48)), "_action=submit").use { assertEquals(404, it.code) }
            post(server.url, "_action=submit", "https://other.invalid").use { assertEquals(403, it.code) }
            assertEquals(0, count.get())
            post(server.url, "_action=submit&ha_token=&dav_password=fake-test-secret", "http://127.0.0.1:${server.port}").use {
                assertEquals(200, it.code)
                assertFalse(it.body!!.string().contains("fake-test-secret"))
            }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals("", submitted["ha_token"])
            assertEquals("fake-test-secret", submitted["dav_password"])
            assertTrue(closed(server))
            assertTrue(runCatching { post(server.url, "_action=submit").close() }.isFailure)
            assertEquals(1, count.get())
        }
    }

    @Test fun malformedOversizedAndRejectedRequestsDoNotConsumeSession() {
        TemporarySettingsEntryServer.start("127.0.0.1", { _, _ -> "<!doctype html>" },
            { SettingsEntryReply(422, "invalid") }, { fail("Rejected submission delivered") }).use { server ->
            for (body in listOf("key=%ZZ", "key=%C3%28", "key=x&key=y", "key=x%00y", "key=x%0Ay", "key=x%09y", "1key=y")) {
                post(server.url, body).use { assertEquals(400, it.code) }
            }
            post(server.url, "x".repeat(17 * 1024)).use { assertEquals(413, it.code) }
            Socket("127.0.0.1", server.port).use { socket ->
                socket.getOutputStream().write(("POST /${server.token}/ HTTP/1.1\r\nHost: local\r\n" +
                    "X-Long: ${"a".repeat(8192)}\r\n\r\n").toByteArray())
                assertTrue(socket.getInputStream().readBytes().decodeToString().startsWith("HTTP/1.1 413"))
            }
            post(server.url, "unknown=x").use { assertEquals(422, it.code) }
            get(server.url).use { assertEquals(200, it.code) }
            server.close()
            assertTrue(closed(server))
        }
    }

    @Test fun concurrentSubmissionIsRejectedAndExpiryClosesActiveSockets() {
        val entered = CountDownLatch(1)
        val expired = CountDownLatch(1)
        val requestDone = CountDownLatch(1)
        TemporarySettingsEntryServer.start("127.0.0.1", { _, _ -> "<!doctype html>" }, {
            entered.countDown()
            CountDownLatch(1).await(10, TimeUnit.SECONDS)
            SettingsEntryReply(200, "pending", terminal = true)
        }, { fail("Expired session delivered data") }, { expired.countDown() }, lifetimeMs = 1500).use { server ->
            Thread {
                try { runCatching { post(server.url, "_action=submit").close() } }
                finally { requestDone.countDown() }
            }.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            post(server.url, "_action=submit").use { assertEquals(409, it.code) }
            assertTrue(expired.await(3, TimeUnit.SECONDS))
            assertTrue(requestDone.await(2, TimeUnit.SECONDS))
            assertTrue(closed(server))
        }
    }
}
