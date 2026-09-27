package com.dormpanel.app.lan

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class TemporarySettingsEntryServerTest {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    private fun post(url: String, values: String) = client.newCall(Request.Builder().url(url)
        .post(values.toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()).execute()
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    @Test fun haUrlVariantsPassThroughWithoutEndpointInterpretation() {
        for (baseUrl in listOf("homeassistant.local:8123", "ws://homeassistant.local:8123/api/websocket",
            "wss://example.invalid/api/websocket")) {
            val accepted = AtomicReference<Map<String, String>>()
            val latch = CountDownLatch(1)
            val server = TemporarySettingsEntryServer.start("127.0.0.1", SettingsEntryForm.HA, emptyMap(),
                { accepted.set(it); latch.countDown() })
            try {
                post(server.url, "url=${encode(baseUrl)}&token=disposable").use { assertEquals(200, it.code) }
                assertTrue(latch.await(2, TimeUnit.SECONDS))
                assertEquals(baseUrl, accepted.get()["url"])
                assertEquals("disposable", accepted.get()["token"])
            } finally { server.close() }
        }
    }

    @Test fun blankSecretIsTransferredForAndroidSaveSemantics() {
        val accepted = AtomicReference<Map<String, String>>()
        val latch = CountDownLatch(1)
        val server = TemporarySettingsEntryServer.start("127.0.0.1", SettingsEntryForm.HA, emptyMap(),
            { accepted.set(it); latch.countDown() })
        try {
            post(server.url, "url=homeassistant.local%3A8123&token=").use { assertEquals(200, it.code) }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals("", accepted.get()["token"])
        } finally { server.close() }
    }

    @Test fun capabilityFormHeadersAndNoSecretEcho() {
        val received = AtomicReference<Map<String, String>>()
        val latch = CountDownLatch(1)
        val server = TemporarySettingsEntryServer.start("127.0.0.1", SettingsEntryForm.HA,
            mapOf("url" to "https://old.invalid/?a=\"<&", "token" to "saved-secret"),
            { received.set(it); latch.countDown() })
        try {
            assertEquals(48, server.token.length)
            assertTrue(server.token.matches(Regex("[0-9a-f]{48}")))
            client.newCall(Request.Builder().url(server.url).build()).execute().use {
                assertEquals(200, it.code)
                val html = it.body!!.string()
                assertTrue(html.contains("Home Assistant base URL"))
                assertTrue(html.contains("&quot;&lt;&amp;"))
                assertFalse(html.contains("saved-secret"))
                assertTrue(html.contains("trusted local network"))
                assertEquals("no-store", it.header("Cache-Control"))
                assertEquals("nosniff", it.header("X-Content-Type-Options"))
                assertEquals("no-referrer", it.header("Referrer-Policy"))
                assertTrue(it.header("Content-Security-Policy")!!.contains("form-action 'self'"))
                assertEquals("DENY", it.header("X-Frame-Options"))
            }
            val wrong = server.url.replace(server.token, "0".repeat(48))
            post(wrong, "url=https%3A%2F%2Fnew.invalid&token=attacker").use { assertEquals(404, it.code) }
            assertNull(received.get())
            client.newCall(Request.Builder().url(server.url).put("".toRequestBody()).build()).execute().use {
                assertEquals(405, it.code)
            }
            post(server.url, "url=${encode("https://new.invalid")}&token=${encode("new-secret")}").use {
                assertEquals(200, it.code)
                assertFalse(it.body!!.string().contains("new-secret"))
            }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(mapOf("url" to "https://new.invalid", "token" to "new-secret"), received.get())
            assertTrue(runCatching { Socket("127.0.0.1", server.port).use { } }.isFailure)
        } finally { server.close() }
    }

    @Test fun webDavFormIsOneShotAndDoesNotPrefillPassword() {
        val count = AtomicInteger()
        val received = AtomicReference<Map<String, String>>()
        val server = TemporarySettingsEntryServer.start("127.0.0.1", SettingsEntryForm.WEBDAV,
            mapOf("url" to "https://dav.invalid", "username" to "a<&\"", "password" to "old-password"),
            { count.incrementAndGet(); received.set(it) })
        try {
            client.newCall(Request.Builder().url(server.url).build()).execute().use {
                val html = it.body!!.string()
                assertTrue(html.contains("a&lt;&amp;&quot;"))
                assertFalse(html.contains("old-password"))
                assertTrue(html.contains("type=\"password\""))
            }
            post(server.url, "url=https%3A%2F%2Fdav.invalid&username=new&password=changed").use { assertEquals(200, it.code) }
            assertEquals(1, count.get())
            assertEquals("changed", received.get()["password"])
            assertTrue(runCatching { post(server.url, "url=https%3A%2F%2Fevil.invalid&username=x&password=x").close() }.isFailure)
            assertEquals(1, count.get())
        } finally { server.close() }
    }

    @Test fun malformedAndOversizedRequestsCanRetryThenClose() {
        val count = AtomicInteger()
        val server = TemporarySettingsEntryServer.start("127.0.0.1", SettingsEntryForm.HA, emptyMap(), { count.incrementAndGet() })
        try {
            for (body in listOf("url=%ZZ&token=x", "url=%C3%28&token=x", "url=https%3A%2F%2Fa.invalid&token=x&token=y",
                "url=+++&token=x", "url=x%00y&token=x",
                "url=x%0Dy&token=x", "url=x%0Ay&token=x", "token=x", "url=${"x".repeat(2049)}&token=x")) {
                post(server.url, body).use { assertEquals(400, it.code) }
            }
            post(server.url, "x".repeat(17 * 1024)).use { assertEquals(413, it.code) }
            Socket("127.0.0.1", server.port).use { socket ->
                socket.getOutputStream().write(("POST /${server.token}/ HTTP/1.1\r\nHost: local\r\n" +
                    "X-Long: ${"a".repeat(8192)}\r\n\r\n").toByteArray())
                assertTrue(socket.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 413"))
            }
            assertEquals(0, count.get())
            server.close()
            assertTrue(runCatching { Socket("127.0.0.1", server.port).use { } }.isFailure)
        } finally { server.close() }
    }

    @Test fun expiryClosesSession() {
        val expired = CountDownLatch(1)
        val clock = AtomicLong(1000)
        val server = TemporarySettingsEntryServer.start("127.0.0.1", SettingsEntryForm.HA, emptyMap(),
            { fail("Expired form delivered data") }, { expired.countDown() }, clock::get, 80)
        assertTrue(expired.await(2, TimeUnit.SECONDS))
        assertTrue(runCatching { Socket("127.0.0.1", server.port).use { } }.isFailure)
        server.close()
    }
}
