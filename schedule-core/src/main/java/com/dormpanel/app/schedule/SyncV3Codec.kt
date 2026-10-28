package com.dormpanel.app.schedule

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Object syntax only. This deliberately does not certify a history or publish anything. */
data class V3Ref(val role: Int, val type: Int, val epochId: String, val epochNumber: Int,
    val objectId: String, val hash: String)
data class V3Object(val type: Int, val datasetId: String, val objectId: String, val writerId: String,
    val epochId: String = SyncV3Codec.ZERO, val epochNumber: Int = 0,
    val descriptorHash: String = SyncV3Codec.ZERO_HASH, val refs: List<V3Ref> = emptyList(),
    val state: ScheduleState? = null, val format: Int = if (state?.sharedRecords != null) 4 else 3)

object SyncV3Codec {
    const val ZERO = "00000000-0000-0000-0000-000000000000"
    const val ZERO_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
    const val MAX_BYTES = ScheduleSnapshotCodec.MAX_BYTES + 32768
    const val DESCRIPTOR = 1; const val GENESIS = 2; const val COMMIT = 3; const val TRANSITION = 4
    const val PARENT = 1; const val INHERITED = 2; const val LATE = 3; const val BASELINE = 4; const val TARGET = 5
    fun hash(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    fun hashValue(s: String) { require(s.matches(Regex("[0-9a-f]{64}"))) }
    private fun unhex(s: String): ByteArray { hashValue(s); return s.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
    private fun canonicalSnapshot(s: ScheduleState): ByteArray = ScheduleCommitCodec.encode(
        ScheduleCommit(ZERO, ZERO, ZERO, emptyList(), s.copy(sharedRecords = null))).let { it.copyOfRange(124, it.size - 32) }
    /** The eight canonical DPSS collections, without snapshot identities or envelope. */
    fun legacyStateBytes(s: ScheduleState): ByteArray = canonicalSnapshot(s).let { it.copyOfRange(109, it.size - 32) }
    fun stateBytes(s: ScheduleState): ByteArray {
        val records = s.sharedRecords ?: return legacyStateBytes(s)
        val legacy = legacyStateBytes(s); val ext = SharedRecordCodec.encode(records)
        return ByteArrayOutputStream().also { b -> DataOutputStream(b).use {
            it.writeInt(0x44505853); it.writeInt(1); it.writeInt(legacy.size); it.write(legacy); it.writeInt(ext.size); it.write(ext)
        } }.toByteArray().also { require(it.size <= ScheduleSnapshotCodec.MAX_BYTES) }
    }
    fun businessHash(s: ScheduleState): String = hash("DPBS".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, 1) +
        stateBytes(s.copy(sources = s.sources.map { it.copy(importedAt = 0) })))
    private fun readState(raw: ByteArray, format: Int = 3): ScheduleState {
        if (format == 4) return DataInputStream(raw.inputStream()).use { input ->
            require(input.readInt() == 0x44505853 && input.readInt() == 1)
            fun block(): ByteArray { val n = input.readInt(); require(n >= 0 && n <= input.available()); return ByteArray(n).also(input::readFully) }
            val legacy = block(); val ext = block(); require(input.available() == 0)
            readState(legacy).copy(sharedRecords = SharedRecordCodec.decode(ext)).also { require(stateBytes(it).contentEquals(raw)) }
        }
        val prefix = canonicalSnapshot(ScheduleState()).copyOfRange(12, 109)
        val payload = prefix + raw
        val b = ByteArrayOutputStream()
        DataOutputStream(b).use { it.writeInt(0x44505353); it.writeInt(1); it.writeInt(payload.size); it.write(payload); it.write(unhex(hash(payload))) }
        val state = ScheduleSnapshotCodec.decode(b.toByteArray()).state
        require(stateBytes(state).contentEquals(raw)) { "Noncanonical business state" }
        return state
    }
    private fun refBytes(r: V3Ref): ByteArray = ByteArrayOutputStream().also { b -> DataOutputStream(b).use {
        it.writeInt(r.role); it.writeInt(r.type); it.write(r.epochId.toByteArray(Charsets.US_ASCII)); it.writeInt(r.epochNumber)
        it.write(r.objectId.toByteArray(Charsets.US_ASCII)); it.write(unhex(r.hash))
    } }.toByteArray()
    private fun refKey(r: V3Ref) = refBytes(r).joinToString("") { "%02x".format(it) }
    private fun validate(o: V3Object) {
        listOf(o.datasetId, o.objectId, o.writerId, o.epochId).forEach(ScheduleCommitCodec::uuid)
        require(o.datasetId != ZERO && o.objectId != ZERO && o.writerId != ZERO)
        require(o.format in 3..4 && (o.state == null || (o.format == 4) == (o.state.sharedRecords != null)))
        hashValue(o.descriptorHash); require(o.type in 1..4 && o.refs.size <= 128)
        require(o.refs.map(::refKey).distinct().size == o.refs.size)
        require(o.refs.map { it.epochId to it.objectId }.distinct().size == o.refs.size)
        o.refs.forEach { r ->
            listOf(r.epochId, r.objectId).forEach(ScheduleCommitCodec::uuid); hashValue(r.hash)
            require(r.epochId != ZERO && r.objectId != ZERO && r.hash != ZERO_HASH && r.epochNumber > 0 && r.type in 2..3 && r.role in 1..5)
            require(r.objectId != o.objectId)
        }
        if (o.type == DESCRIPTOR) {
            require(o.epochId == ZERO && o.epochNumber == 0 && o.descriptorHash == ZERO_HASH && o.refs.isEmpty() && o.state == null)
            return
        }
        require(o.epochId != ZERO && o.epochNumber > 0 && o.descriptorHash != ZERO_HASH)
        fun sameEpoch(r: V3Ref) = r.epochId == o.epochId && r.epochNumber == o.epochNumber
        when (o.type) {
            GENESIS -> {
                require(o.state != null && o.refs.all { it.role == INHERITED && it.epochNumber == o.epochNumber - 1 && it.epochId != o.epochId })
                require(if (o.epochNumber == 1) o.refs.isEmpty() else o.refs.size in 1..64 && o.refs.map { it.epochId }.distinct().size == 1)
            }
            COMMIT -> {
                require(o.state != null && o.refs.all { it.role in listOf(PARENT, LATE, BASELINE) })
                val parents = o.refs.filter { it.role == PARENT }; val late = o.refs.filter { it.role == LATE }; val base = o.refs.filter { it.role == BASELINE }
                require(parents.size in 1..64 && parents.all(::sameEpoch))
                require(late.size <= 62 && late.all { it.epochNumber < o.epochNumber && it.epochId != o.epochId })
                require(if (late.isEmpty()) base.isEmpty() else base.size == 1 && base.single().epochNumber < o.epochNumber && base.single().epochId != o.epochId)
            }
            TRANSITION -> {
                require(o.state == null && o.refs.all { it.role == INHERITED || it.role == TARGET })
                val source = o.refs.filter { it.role == INHERITED }; val target = o.refs.filter { it.role == TARGET }
                require(source.size in 1..64 && source.all(::sameEpoch) && target.size == 1)
                require(o.epochNumber < Int.MAX_VALUE && target.single().let { it.type == GENESIS && it.epochNumber == o.epochNumber + 1 && it.epochId != o.epochId })
            }
        }
    }
    fun encode(o: V3Object): ByteArray {
        validate(o)
        val state = o.state?.let(::stateBytes) ?: byteArrayOf()
        val b = ByteArrayOutputStream()
        DataOutputStream(b).use { out ->
            out.writeInt(0x44505633); out.writeInt(o.format); out.writeInt(o.type)
            listOf(o.datasetId, o.objectId, o.writerId, o.epochId).forEach { out.write(it.toByteArray(Charsets.US_ASCII)) }
            out.writeInt(o.epochNumber); out.write(unhex(o.descriptorHash)); out.writeInt(o.refs.size)
            o.refs.sortedBy(::refKey).forEach { out.write(refBytes(it)) }
            out.writeInt(state.size); out.write(state)
        }
        val body = b.toByteArray(); require(body.size + 32 <= MAX_BYTES)
        return body + unhex(hash(body))
    }
    fun decode(bytes: ByteArray): V3Object {
        require(bytes.size in 232..MAX_BYTES)
        val body = bytes.copyOfRange(0, bytes.size - 32)
        require(MessageDigest.isEqual(unhex(hash(body)), bytes.copyOfRange(bytes.size - 32, bytes.size))) { "v3 checksum" }
        return DataInputStream(body.inputStream()).use { input ->
            require(input.readInt() == 0x44505633); val format = input.readInt(); require(format in 3..4)
            fun id() = ByteArray(36).also(input::readFully).toString(Charsets.US_ASCII).also(ScheduleCommitCodec::uuid)
            fun h() = ByteArray(32).also(input::readFully).joinToString("") { "%02x".format(it) }
            val type = input.readInt(); val dataset = id(); val objectId = id(); val writer = id(); val epoch = id()
            val number = input.readInt(); val descriptor = h(); val count = input.readInt(); require(count in 0..128)
            val refs = List(count) { V3Ref(input.readInt(), input.readInt(), id(), input.readInt(), id(), h()) }
            val n = input.readInt(); require(n in 0..ScheduleSnapshotCodec.MAX_BYTES && n == input.available())
            val raw = ByteArray(n).also(input::readFully)
            V3Object(type, dataset, objectId, writer, epoch, number, descriptor, refs, if (n == 0) null else readState(raw, format), format).also {
                require(encode(it).contentEquals(bytes)) { "Noncanonical v3 object" }
            }
        }
    }
    /** Independent expected identity; callers must not derive expectations from untrusted bytes. */
    fun check(bytes: ByteArray, dataset: String, objectId: String, expectedHash: String): V3Object {
        hashValue(expectedHash); require(hash(bytes) == expectedHash)
        return decode(bytes).also { require(it.datasetId == dataset && it.objectId == objectId) }
    }
    fun checkReference(bytes: ByteArray, dataset: String, descriptorHash: String, ref: V3Ref): V3Object =
        check(bytes, dataset, ref.objectId, ref.hash).also {
            require(it.type == ref.type && it.epochId == ref.epochId && it.epochNumber == ref.epochNumber && it.descriptorHash == descriptorHash)
        }
}
