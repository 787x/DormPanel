package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test

class SharedRecordsTest {
    private val color = SharedRecord("course.color", "0".repeat(64), 1, payload = listOf(1, 1, 2, 3).map(Int::toByte))
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
        assertEquals("4450585200000001000000020000000c636f757273652e636f6c6f720000004030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030000000010000000000000000000000040101020300000009746573742e6e6f7465000000036f6e65000000010000000000000000000000066f7061717565", SharedRecordCodec.encode(rows).joinToString("") { "%02x".format(it) })
        assertEquals("d6520d9c73e644af7e5526c5fd189a31df33a8b21faea827e99b284b4ad9bff6", SharedRecordCodec.businessHash(rows))
        assertEquals("59e27c", SharedCourseColor.automaticColor("local-1").joinToString("") { "%02x".format(it) })
    }
    @Test fun courseIdentityRenameAndRestoreAuto() {
        val state = ScheduleState(entries=listOf(TimetableEntry("x","before",1,480,540)), sharedRecords=emptyList())
        val colored=SharedCourseColors.change(state,null,"x",0x123456)
        assertEquals(0x123456,SharedCourseColors.rgb(colored.copy(entries=listOf(state.entries.single().copy(title="after"))),null,"x"))
        val automatic=SharedCourseColors.change(colored,null,"x",null)
        assertEquals(SharedCourseColors.rgb(state,null,"x"),SharedCourseColors.rgb(automatic,null,"x"))
        assertTrue(ScheduleMerge.merge(colored,colored.copy(entries=emptyList()),SharedCourseColors.change(colored,null,"x",0xabcdef)).conflicts.isNotEmpty())
        assertTrue(runCatching { ScheduleSnapshotCodec.encode(ScheduleSnapshot(SyncV3Codec.ZERO,SyncV3Codec.ZERO,null,1,colored,1)) }.isFailure)
    }
    @Test fun completeObjectAndBusinessHashVector() {
        val dir = listOf(java.io.File("../test-vectors/sync-v3-shared"),java.io.File("test-vectors/sync-v3-shared")).first { it.isDirectory }
        val raw=java.io.File(dir,"genesis.dpv").readBytes(); val manifest=org.json.JSONObject(java.io.File(dir,"manifest.json").readText())
        val objectValue=SyncV3Codec.decode(raw);assertEquals(4,objectValue.format)
        assertArrayEquals(raw,SyncV3Codec.encode(objectValue));assertEquals(manifest.getString("objectHash"),SyncV3Codec.hash(raw))
        assertEquals(manifest.getString("businessHash"),SyncV3Codec.businessHash(objectValue.state!!))
        // PR68's object reader requires version 3 and cannot interpret this object.
        assertNotEquals(3,java.nio.ByteBuffer.wrap(raw,4,4).int)
    }
    @Test fun malformedKnownColorWithValidChecksumRejectedAndFutureOptionalPreserved() {
        val dir=listOf(java.io.File("../test-vectors/sync-v3-shared"),java.io.File("test-vectors/sync-v3-shared")).first{it.isDirectory}
        val bytes=java.io.File(dir,"genesis.dpv").readBytes();val body=bytes.copyOfRange(0,bytes.size-32)
        val marker=byteArrayOf(0,0,0,4,1,1,2,3);val pos=(0..body.size-marker.size).first{body.copyOfRange(it,it+marker.size).contentEquals(marker)}
        body[pos+4]=2;val raw=body+java.security.MessageDigest.getInstance("SHA-256").digest(body)
        assertTrue(runCatching{SyncV3Codec.decode(raw)}.isFailure)
        val state=ScheduleState(entries=listOf(TimetableEntry("x","x",1,480,540)),sharedRecords=emptyList())
        val future=SharedRecord("course.color",SharedCourseColors.identity(state,null,"x"),2,payload=emptyList())
        val extended=state.copy(sharedRecords=listOf(future));assertEquals("UNSUPPORTED",SharedCourseColors.display(extended,null,"x").status)
        assertTrue(runCatching{SharedCourseColors.change(extended,null,"x",null)}.isFailure)
        SharedTypes.registry().validate(listOf(future));assertTrue(runCatching{SharedTypes.registry().writable(listOf(future.copy(required=true)))}.isFailure)
    }
    @Test fun conservativeMerge() {
        val changed = color.copy(payload = listOf(0.toByte()))
        assertEquals(2, SharedRecords.merge(listOf(color), listOf(changed), listOf(color, extra)).records.size)
        assertEquals(1, SharedRecords.merge(listOf(color), emptyList(), listOf(changed)).conflicts.size)
        assertTrue(SharedRecords.merge(listOf(color), emptyList(), listOf(color)).records.isEmpty())
    }
}

// Fixed DPXR/1 vector shared with fnOS, deliberately independent of active DPBS/1.

