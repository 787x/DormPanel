package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test

class SharedRecordsTest {
    private val color = SharedRecord("course.color", "local-1", 1, payload = listOf(1, 1, 2, 3).map(Int::toByte))
    private val extra = SharedRecord("test.note", "one", 1, payload = "opaque".toByteArray().toList())
    @Test fun canonicalAndUnknownPreservation() {
        val bytes = SharedRecordCodec.encode(listOf(extra, color))
        assertArrayEquals(bytes, SharedRecordCodec.encode(listOf(color, extra)))
        val registry = SharedTypeRegistry(listOf(SharedCourseColor))
        val next = registry.replace(SharedRecordCodec.decode(bytes), color.copy(payload = listOf(1,4,5,6).map(Int::toByte)))
        assertEquals(extra, next.find { it.typeId == extra.typeId })
        assertTrue(runCatching { registry.replace(listOf(extra.copy(required = true)), color) }.isFailure)
        assertTrue(runCatching { registry.replace(listOf(extra.copy(dependencies = listOf("future.type"))), color) }.isFailure)
        assertTrue(runCatching { SharedRecordCodec.encode(listOf(extra,extra)) }.isFailure)
        assertTrue(runCatching { SharedRecordCodec.decode(bytes + byteArrayOf(0)) }.isFailure)
    }
    @Test fun crossLanguageVector() {
        val rows = listOf(extra, color)
        assertEquals("4450585200000001000000020000000c636f757273652e636f6c6f72000000076c6f63616c2d31000000010000000000000000000000040101020300000009746573742e6e6f7465000000036f6e65000000010000000000000000000000066f7061717565", SharedRecordCodec.encode(rows).joinToString("") { "%02x".format(it) })
        assertEquals("426234c80e57aade8b5af6d5df87cc997c31c09cb7209790fef3906cd97aa055", SharedRecordCodec.businessHash(rows))
        assertEquals("59e27c", SharedCourseColor.automaticColor("local-1").joinToString("") { "%02x".format(it) })
    }
    @Test fun conservativeMerge() {
        val changed = color.copy(payload = listOf(0.toByte()))
        assertEquals(2, SharedRecords.merge(listOf(color), listOf(changed), listOf(color, extra)).records.size)
        assertEquals(1, SharedRecords.merge(listOf(color), emptyList(), listOf(changed)).conflicts.size)
        assertTrue(SharedRecords.merge(listOf(color), emptyList(), listOf(color)).records.isEmpty())
    }
}

// Fixed DPXR/1 vector shared with fnOS, deliberately independent of active DPBS/1.

