package com.dormpanel.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AirPlayMetadataTest {
    private fun field(tag: String, bytes: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + bytes.size).put(tag.toByteArray(Charsets.US_ASCII))
            .putInt(bytes.size).put(bytes).array()

    @Test fun nestedMetadataKeepsOnlyProvidedFields() {
        val data = field("mlit", field("minm", " 曲名 ".toByteArray()) +
            field("asar", "歌手\u0000".toByteArray()) + field("xxxx", byteArrayOf(5)))
        assertEquals(AirPlayTrack(title = "曲名", artist = "歌手"), AirPlayMetadata.track(data))
    }

    @Test fun rejectsTruncatedOversizedAndDeepMetadata() {
        assertNull(AirPlayMetadata.track(field("minm", byteArrayOf(2)).dropLast(1).toByteArray()))
        assertNull(AirPlayMetadata.track(ByteArray(65_537)))
        var item = field("minm", "title".toByteArray())
        repeat(6) { item = field("mlit", item) }
        assertNull(AirPlayMetadata.track(item))
        assertNull(AirPlayMetadata.track(field("mlit", ByteArray(257 * 8))))
    }

    @Test fun handles32BitRtpWrapAndRejectsInconsistentProgress() {
        val start = 0xffffa000L
        val current = (start + 44_100L) and 0xffffffffL
        val end = (start + 88_200L) and 0xffffffffL
        assertEquals(AirPlayProgress(1_000, 2_000), AirPlayMetadata.progress(start, current, end))
        assertNull(AirPlayMetadata.progress(10, 50, 40))
        assertNull(AirPlayMetadata.progress(10, 10, 11))
    }

    @Test fun validatesDnsSdLabelBudget() {
        assertEquals("DormPanel 客厅", AirPlayController.validName(" DormPanel 客厅 "))
        assertNull(AirPlayController.validName("测".repeat(17)))
        assertNull(AirPlayController.validName("bad\u0000name"))
        assertNull(AirPlayController.validName("📣"))
    }

    @Test fun localPeerCheckHandlesPartialPrefixesAndDifferentFamilies() {
        val address = byteArrayOf(10, 0, 4, 32)
        assertTrue(containsAddress(address, 27, byteArrayOf(10, 0, 4, 63)))
        assertFalse(containsAddress(address, 27, byteArrayOf(10, 0, 4, 64)))
        assertFalse(containsAddress(address, 24, ByteArray(16)))
        assertFalse(containsAddress(address, 0, byteArrayOf(8, 8, 8, 8)))
    }
}
