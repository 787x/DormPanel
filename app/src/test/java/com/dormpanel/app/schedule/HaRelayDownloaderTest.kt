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

    @Test fun xlsDownloadUsesBinaryImporterAndPreservesPreviewBoundary() {
        val bytes = javaClass.getResourceAsStream("/schedule/hubei_2026-2027-1.xls")!!.readBytes()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val artifact = downloader.download(server.url("/").toString(),
                claim(bytes).put("kind", "schedule_xls").put("filename", "plan.xls"))
            assertEquals("application/vnd.ms-excel", artifact.mimeType)
            assertArrayEquals(bytes, artifact.bytes())
            val preview = (TimetableImporter().parseOutcome(artifact) as TimetableImporter.ParseOutcome.Ready).preview
            assertEquals(13, preview.seriesCount)
            assertEquals(131, preview.occurrences.size)
        }
    }

    @Test fun xlsRejectsCrossKindFilenameLengthAndHash() {
        MockWebServer().use { server ->
            val bytes = "invalid XLS".toByteArray()
            for (payload in listOf(
                claim(bytes).put("kind", "schedule_xls"),
                claim(bytes).put("kind", "schedule_csv").put("filename", "plan.xls"),
                claim(bytes).put("kind", "schedule_xls").put("filename", "../plan.xls"),
                claim(bytes).put("kind", "schedule_xls").put("filename", "plan.xls").put("size", bytes.size + 1),
                claim(bytes).put("kind", "schedule_xls").put("filename", "plan.xls").put("sha256", "0".repeat(64)))) {
                server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
                assertThrows(HaRelayIntegrityException::class.java) { downloader.download(server.url("/").toString(), payload) }
            }
        }
    }

    private fun claim(bytes: ByteArray) = JSONObject().put("transfer_id", id).put("filename", "test.ics")
        .put("size", bytes.size).put("sha256", digest(bytes)).put("signed_path", signed)
}
