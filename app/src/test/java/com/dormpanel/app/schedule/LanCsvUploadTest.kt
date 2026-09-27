package com.dormpanel.app.schedule

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** LAN upload must accept .ics and .csv while keeping token/bounds/lifetime behavior. */
class LanCsvUploadTest {
    private val client = OkHttpClient()
    private val fixtureCsv: ByteArray =
        javaClass.getResourceAsStream("/schedule/hubei_2026-2027-1.csv")!!.use { it.readBytes() }
    private val fixtureIcs: ByteArray =
        javaClass.getResourceAsStream("/schedule/wakeup.ics")!!.use { it.readBytes() }

    private fun start(validate: (ScheduleArtifact) -> Boolean, onAccepted: () -> Unit = {}): TemporaryLanUploadServer =
        TemporaryLanUploadServer.start("127.0.0.1", validate, onAccepted)

    private fun upload(url: String, name: String, bytes: ByteArray): Pair<Int, String> {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = Request.Builder().url(url + "upload").post(body).build()
        client.newCall(request).execute().use { response ->
            return response.code to response.body?.string().orEmpty()
        }
    }

    @Test fun validCsvIsAcceptedAndHandedToPreview() {
        val delivered = AtomicReference<ImportPreview>()
        val latch = CountDownLatch(1)
        val server = start(
            validate = { artifact ->
                runCatching {
                    when (val outcome = TimetableImporter(profiles = null).parseOutcome(artifact)) {
                        is TimetableImporter.ParseOutcome.Ready -> { delivered.set(outcome.preview); true }
                        is TimetableImporter.ParseOutcome.NeedsProfile -> true
                    }
                }.getOrDefault(false)
            },
            onAccepted = { latch.countDown() })
        try {
            val (code, body) = upload(server.url, "hubei.csv", fixtureCsv)
            assertEquals(200, code)
            assertTrue(body.contains("Confirm the timetable"))
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals(131, delivered.get().occurrences.size)
        } finally { server.close() }
    }

    @Test fun validIcsStillAccepted() {
        val latch = CountDownLatch(1)
        val server = start(validate = { artifact ->
            runCatching { TimetableImporter().parse(artifact) }.isSuccess
        }, onAccepted = { latch.countDown() })
        try {
            val (code, _) = upload(server.url, "wakeup.ics", fixtureIcs)
            assertEquals(200, code)
            assertTrue(latch.await(3, TimeUnit.SECONDS))
        } finally { server.close() }
    }

    @Test fun malformedCsvRejectedSafely() {
        val server = start(validate = { artifact ->
            runCatching { TimetableImporter(profiles = null).parseOutcome(artifact); true }.getOrDefault(false)
        })
        try {
            val (code, body) = upload(server.url, "bad.csv", "not,a,timetable".toByteArray())
            assertEquals(422, code)
            assertTrue(body.contains("could not be imported", true))
        } finally { server.close() }
    }

    @Test fun txtExtensionStillRejected() {
        val server = start(validate = { true })
        try {
            val (code, _) = upload(server.url, "notes.txt", fixtureCsv)
            assertEquals(400, code)
        } finally { server.close() }
    }

    @Test fun structurallyValidCsvNeedingProfileIsNotInvalidUpload() {
        val text = CsvReader.decode(fixtureCsv).replace("2026-2027-1", "2099-2100-1")
        val bytes = text.toByteArray(Charsets.UTF_8)
        var acceptedProfileNeeded = false
        val latch = CountDownLatch(1)
        val server = start(validate = { artifact ->
            when (val outcome = TimetableImporter(profiles = null).parseOutcome(artifact)) {
                is TimetableImporter.ParseOutcome.Ready -> true
                is TimetableImporter.ParseOutcome.NeedsProfile -> {
                    acceptedProfileNeeded = true
                    true
                }
            }
        }, onAccepted = { latch.countDown() })
        try {
            val (code, _) = upload(server.url, "future.csv", bytes)
            assertEquals(200, code)
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertTrue(acceptedProfileNeeded)
        } finally { server.close() }
    }
}
