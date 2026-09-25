package com.dormpanel.app.apps

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class ApkFileSinkTest {
    @Test fun streamsAndHashesWithoutTrustingAnAdvertisedLength() {
        val directory = Files.createTempDirectory("apk-sink").toFile()
        try {
            val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
            val file = directory.resolve("candidate.apk")
            val (size, hash) = ApkFileSink.write(bytes.inputStream(), file, bytes.size.toLong())
            assertEquals(bytes.size.toLong(), size)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }, hash)
            assertArrayEquals(bytes, file.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun actualBytesOverLimitDeletePartialFile() {
        val directory = Files.createTempDirectory("apk-sink").toFile()
        try {
            val file = directory.resolve("partial.apk")
            try { ApkFileSink.write(ByteArray(65).inputStream(), file, 64); fail("Oversize accepted") }
            catch (_: ApkRejected) { assertFalse(file.exists()) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedReadDeletesPartialFile() {
        val directory = Files.createTempDirectory("apk-sink").toFile()
        try {
            val file = directory.resolve("partial.apk")
            val input = object : java.io.InputStream() { var count = 0
                override fun read(): Int { if (++count > 4) throw java.io.IOException("Cancelled"); return 1 }
            }
            try { ApkFileSink.write(input, file, 64); fail("Read failure ignored") }
            catch (_: java.io.IOException) { assertFalse(file.exists()) }
        } finally { directory.deleteRecursively() }
    }
}
