package com.dormpanel.app.airplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory

internal fun containsAddress(local: ByteArray, prefixLength: Int, peer: ByteArray): Boolean {
    if (local.size != peer.size || prefixLength !in 1..(local.size * 8)) return false
    val fullBytes = prefixLength / 8
    for (index in 0 until fullBytes) if (local[index] != peer[index]) return false
    val remaining = prefixLength % 8
    if (remaining == 0) return true
    val mask = (0xff shl (8 - remaining)) and 0xff
    return (local[fullBytes].toInt() and mask) == (peer[fullBytes].toInt() and mask)
}

internal object AirPlayMetadata {
    fun track(bytes: ByteArray): AirPlayTrack? {
        if (bytes.size !in 8..65_536) return null
        var title = ""
        var artist = ""
        var album = ""
        var elements = 0
        fun parse(start: Int, end: Int, depth: Int): Boolean {
            if (depth > 4) return false
            var offset = start
            while (offset < end) {
                if (end - offset < 8 || ++elements > 256) return false
                val tag = String(bytes, offset, 4, Charsets.US_ASCII)
                var length = 0
                for (index in offset + 4 until offset + 8) {
                    length = (length shl 8) or (bytes[index].toInt() and 0xff)
                }
                val data = offset + 8
                if (length < 0 || length > end - data) return false
                when (tag) {
                    "mlit", "mlcl", "cmst" -> if (!parse(data, data + length, depth + 1)) return false
                    "minm", "asar", "asal" -> {
                        val value = String(bytes, data, minOf(length, 1024), Charsets.UTF_8)
                            .filterNot(Char::isISOControl).trim().take(256)
                        when (tag) {
                            "minm" -> title = value
                            "asar" -> artist = value
                            else -> album = value
                        }
                    }
                }
                offset = data + length
            }
            return true
        }
        return if (parse(0, bytes.size, 0)) AirPlayTrack(title, artist, album) else null
    }

    fun progress(start: Long, current: Long, end: Long): AirPlayProgress? {
        val duration = (end - start) and 0xffffffffL
        val position = (current - start) and 0xffffffffL
        if (duration < 45L || position > duration) return null
        return AirPlayProgress(position * 1_000 / 44_100, duration * 1_000 / 44_100)
    }

    fun artwork(bytes: ByteArray): Bitmap? {
        if (bytes.size !in 8..1_048_576) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 ||
            bounds.outMimeType !in setOf("image/jpeg", "image/png")) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 384) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
