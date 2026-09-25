package com.dormpanel.app.schedule

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class TemporaryLanUploadServerTest {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    private fun start(onPreview: (ImportPreview) -> Unit = {}, onExpired: () -> Unit = {},
        now: () -> Long = System::currentTimeMillis, lifetimeMs: Long = TemporaryLanUploadServer.LIFETIME_MS): TemporaryLanUploadServer {
        val parsed = AtomicReference<ImportPreview>()
        return TemporaryLanUploadServer.start("127.0.0.1",
            { artifact -> runCatching { IcsScheduleImporter().parse(artifact) }.onSuccess(parsed::set).isSuccess },
            { parsed.get()?.let(onPreview) }, onExpired, now, lifetimeMs)
    }
    private fun response(url: String, method: String = "GET", body: okhttp3.RequestBody? = null): Pair<Int, String> =
        client.newCall(Request.Builder().url(url).method(method, body).build()).execute().use { it.code to it.body!!.string() }
    private fun upload(url: String, name: String = "wakeup.ics", bytes: ByteArray = fixture(),
        type: String = "application/octet-stream", extra: Boolean = false): Pair<Int, String> {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, bytes.toRequestBody(type.toMediaType()))
        if (extra) builder.addFormDataPart("other", "other.ics", bytes.toRequestBody(type.toMediaType()))
        return response("${url}upload", "POST", builder.build())
    }
    private fun fixture() = javaClass.getResourceAsStream("/schedule/wakeup.ics")!!.use { it.readBytes() }

    @Test fun tokenRoutesAndLifecycle() {
        val first = start()
        val port = first.port
        try {
            assertEquals(48, first.token.length)
            assertTrue(first.token.matches(Regex("[0-9a-f]{48}")))
            assertEquals(200, response(first.url).first)
            assertTrue(response(first.url).second.contains("DormPanel timetable upload"))
            assertEquals(404, response("http://127.0.0.1:$port/").first)
            assertEquals(404, response(first.url.replace(first.token, "0".repeat(48))).first)
            assertEquals(404, response(first.url + "unrelated").first)
            assertEquals(405, response(first.url, "PUT", "".toRequestBody()).first)
        } finally { first.close(); first.close() }
        assertTrue(runCatching { Socket("127.0.0.1", port).use { } }.isFailure)
        val second = start()
        try { assertTrue(first.token != second.token) } finally { second.close() }
    }

    @Test fun validMultipartUsesExistingImporterAndOnlyProducesPreview() {
        val delivered = AtomicReference<ImportPreview>()
        val latch = CountDownLatch(1)
        val server = start({ delivered.set(it); latch.countDown() })
        try {
            val result = upload(server.url)
            assertEquals(200, result.first)
            assertTrue(result.second.contains("Confirm the timetable"))
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            val preview = delivered.get()
            assertEquals("lan_upload", preview.kind)
            assertEquals("lan_upload", preview.locator)
            assertFalse(preview.locator!!.contains(server.token))
            assertEquals(7, preview.seriesCount)
            assertEquals(175, preview.occurrences.size)
            assertTrue(runCatching { Socket("127.0.0.1", server.port).use { } }.isFailure)
        } finally { server.close() }
    }

    @Test fun invalidRequestsLeaveSessionAvailableForRetry() {
        val delivered = AtomicReference<ImportPreview>()
        val server = start({ delivered.set(it) })
        try {
            assertEquals(400, upload(server.url, "bad.txt").first)
            assertEquals(400, upload(server.url, extra = true).first)
            assertEquals(400, response(server.url + "upload", "POST",
                MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("note", "no file").build()).first)
            assertEquals(422, upload(server.url, bytes = "not an ICS".toByteArray()).first)
            assertEquals(400, response(server.url + "upload", "POST",
                "broken multipart".toRequestBody("multipart/form-data; boundary=foo".toMediaType())).first)
            assertEquals(413, upload(server.url, bytes = ByteArray(ImportLimits.BYTES + 1)).first)
            assertNull(delivered.get())
            assertEquals(200, response(server.url).first)
            assertEquals(200, upload(server.url, type = "application/octet-stream").first)
        } finally { server.close() }
    }

    @Test fun expiryAndAddressPolicy() {
        val time = AtomicLong(1_000L)
        val server = start(now = time::get)
        try {
            time.set(server.expiresAt)
            assertEquals(404, response(server.url).first)
        } finally { server.close() }
        assertEquals("192.168.1.8", TemporaryLanUploadServer.selectAddress(listOf("127.0.0.1", "169.254.1.2", "192.168.1.8")))
        assertNull(TemporaryLanUploadServer.selectAddress(listOf("127.0.0.1", "169.254.1.2", "0.0.0.0")))
    }

    @Test fun displayedUrlIsExactQrPayload() {
        val server = start()
        try {
            val matrix = ReceiveQr.encode(server.url)
            val source = object : LuminanceSource(matrix.width, matrix.height) {
                override fun getRow(y: Int, row: ByteArray?): ByteArray = ByteArray(width) { x ->
                    if (matrix[x, y]) 0 else 255.toByte()
                }
                override fun getMatrix(): ByteArray = ByteArray(width * height) { index ->
                    if (matrix[index % width, index / width]) 0 else 255.toByte()
                }
            }
            assertTrue(QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)),
                mapOf(com.google.zxing.DecodeHintType.PURE_BARCODE to true)).text == server.url)
        } finally { server.close() }
    }

    @Test fun competingUploadIsRejectedWhileFirstBodyIsPending() {
        val server = start()
        try {
            val socket = Socket("127.0.0.1", server.port)
            socket.getOutputStream().write(("POST /${server.token}/upload HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary=x\r\nContent-Length: 100\r\n\r\n").toByteArray())
            socket.getOutputStream().flush()
            Thread.sleep(100)
            assertEquals(409, upload(server.url).first)
            socket.close()
        } finally { server.close() }
    }

    @Test fun timeoutClosesListenerAndStopDropsPendingUpload() {
        val expired = CountDownLatch(1)
        val timed = start(onExpired = { expired.countDown() }, lifetimeMs = 80)
        assertTrue(expired.await(2, TimeUnit.SECONDS))
        assertTrue(runCatching { Socket("127.0.0.1", timed.port).use { } }.isFailure)
        timed.close()

        val delivered = AtomicReference<ImportPreview>()
        val stopped = start({ delivered.set(it) })
        val socket = Socket("127.0.0.1", stopped.port)
        socket.getOutputStream().write(("POST /${stopped.token}/upload HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary=x\r\nContent-Length: 100\r\n\r\n").toByteArray())
        socket.getOutputStream().flush()
        stopped.close()
        socket.close()
        assertNull(delivered.get())
        assertTrue(runCatching { Socket("127.0.0.1", stopped.port).use { } }.isFailure)
    }
}
