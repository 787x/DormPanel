package com.dormpanel.app.apps

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class TemporaryApkUploadServerTest {
    private fun post(url: String, body: ByteArray, type: String): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"; connection.setRequestProperty("Content-Type", type)
        connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        return connection.responseCode.also { connection.disconnect() }
    }

    @Test fun tokenOneShotMalformedRetryAndStop() {
        val directory = Files.createTempDirectory("apk-lan").toFile()
        val received = mutableListOf<String>()
        val server = TemporaryApkUploadServer.startForTest("127.0.0.1", directory, 60_000,
            { input, _ -> val value = input.readBytes().decodeToString(); if (value == "valid") { received += value; true } else false })
        try {
            assertEquals(48, server.token.length)
            assertEquals(404, runCatching { (URL(server.url.replace(server.token, "0".repeat(48))).openConnection() as HttpURLConnection).responseCode }.getOrDefault(404))
            assertEquals(422, post(server.url + "upload", "bad".toByteArray(), "application/vnd.android.package-archive"))
            assertEquals(200, post(server.url + "upload", "valid".toByteArray(), "application/vnd.android.package-archive"))
            assertEquals(listOf("valid"), received)
            assertTrue(runCatching { post(server.url + "upload", "valid".toByteArray(), "application/vnd.android.package-archive") }.getOrDefault(404) != 200)
        } finally { server.close(); directory.deleteRecursively() }
    }

    @Test fun multipartFileStreamsToReceiver() {
        val directory = Files.createTempDirectory("apk-lan").toFile()
        var received = ""
        val server = TemporaryApkUploadServer.startForTest("127.0.0.1", directory, 60_000,
            { input, _ -> received = input.readBytes().decodeToString(); true })
        try {
            val boundary = "AaB03x"
            val body = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"demo.apk\"\r\nContent-Type: application/vnd.android.package-archive\r\n\r\nhello APK\r\n--$boundary--\r\n"
            assertEquals(200, post(server.url + "upload", body.toByteArray(), "multipart/form-data; boundary=$boundary"))
            assertEquals("hello APK", received)
        } finally { server.close(); directory.deleteRecursively() }
    }
}
