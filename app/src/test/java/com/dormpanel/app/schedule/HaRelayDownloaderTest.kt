package com.dormpanel.app.schedule

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HaRelayDownloaderTest {
    private val downloader = HaRelayDownloader(OkHttpClient())
    private val id = "a".repeat(32)
    private val signed = "/api/dormpanel/transfers/$id/${"b".repeat(32)}?authSig=signature"

    @Test fun signedPathCannotLeaveOrigin() {
        val origin = "https://ha.example:8123"
        assertEquals("ha.example", downloader.url(origin, signed).host)
        for (invalid in listOf("https://evil.example$signed", "//evil.example$signed", "http://evil.example$signed",
            "/api/dormpanel/transfers/../other?authSig=x", "/api/dormpanel/transfers/%2f%2fevil?authSig=x",
            "/api/dormpanel/transfers/%2e%2e/%2e%2e/states?authSig=x",
            "/api/dormpanel/transfers/id\\evil?authSig=x", "/api/dormpanel/transfers/id")) {
            assertThrows(IllegalArgumentException::class.java) { downloader.url(origin, invalid) }
        }
    }

    @Test fun verifiesBodyAndCreatesCredentialFreeArtifact() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("BEGIN:VCALENDAR\nEND:VCALENDAR"))
            val bytes = "BEGIN:VCALENDAR\nEND:VCALENDAR".toByteArray()
            val artifact = downloader.download(server.url("/").toString().trimEnd('/'), claim(bytes))
            assertEquals("ha_relay", artifact.kind)
            assertEquals("ha_relay:$id", artifact.locator)
            assertFalse(artifact.locator!!.contains("authSig"))
            assertArrayEquals(bytes, artifact.bytes())
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun rejectsMismatchOversizeAndRedirect() {
        MockWebServer().use { server ->
            val origin = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody("bad"))
            assertThrows(IOException::class.java) { downloader.download(origin, claim("good".toByteArray())) }
            server.enqueue(MockResponse().setBody("x".repeat(ImportLimits.BYTES + 1)))
            assertThrows(IOException::class.java) { downloader.download(origin, claim(ByteArray(1))) }
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://evil.example/file"))
            assertThrows(IOException::class.java) { downloader.download(origin, claim(ByteArray(1))) }
            assertEquals(3, server.requestCount)
        }
    }

    private fun claim(bytes: ByteArray) = JSONObject().put("transfer_id", id).put("filename", "test.ics")
        .put("size", bytes.size).put("sha256", digest(bytes)).put("signed_path", signed)
}
