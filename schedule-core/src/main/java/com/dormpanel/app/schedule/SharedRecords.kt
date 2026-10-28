package com.dormpanel.app.schedule

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** DPXR/1 candidate block; not a DPSS/1 state or an active Writer input. */
data class SharedRecord(val typeId: String, val recordId: String, val schemaVersion: Int,
    val required: Boolean = false, val dependencies: List<String> = emptyList(), val payload: List<Byte>) {
    val key: String get() = "$typeId/$recordId"
}

object SharedRecordCodec {
    private fun name(s: String) = s.length <= 96 && s.matches(Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+"))
    fun identity(s: String) = s.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}"))
    fun validate(r: SharedRecord) {
        require(name(r.typeId) && identity(r.recordId) && r.schemaVersion > 0)
        require(r.dependencies.size <= 16 && r.dependencies.all(::name) && r.dependencies.distinct().size == r.dependencies.size)
        require(r.payload.size <= 65536)
    }
    private fun DataOutputStream.text(s: String) { val b = s.toByteArray(Charsets.US_ASCII); writeInt(b.size); write(b) }
    fun encode(records: List<SharedRecord>): ByteArray {
        require(records.size <= 4096 && records.map { it.key }.distinct().size == records.size)
        // Bound before allocation, including per-record framing.
        records.forEach(::validate)
        require(records.sumOf { 24L + it.typeId.length + it.recordId.length + it.dependencies.sumOf { d -> 4 + d.length } + it.payload.size } + 12 <= 1048576)
        return ByteArrayOutputStream().also { b -> DataOutputStream(b).use { out ->
            out.writeInt(0x44505852); out.writeInt(1); out.writeInt(records.size)
            records.sortedWith(compareBy<SharedRecord> { it.typeId }.thenBy { it.recordId }).forEach { r ->
                out.text(r.typeId); out.text(r.recordId); out.writeInt(r.schemaVersion); out.writeInt(if (r.required) 1 else 0)
                out.writeInt(r.dependencies.size); r.dependencies.sorted().forEach { out.text(it) }
                out.writeInt(r.payload.size); out.write(r.payload.toByteArray())
            }
        } }.toByteArray().also { require(it.size <= 1048576) }
    }
    fun decode(bytes: ByteArray): List<SharedRecord> {
        require(bytes.size in 12..1048576)
        return DataInputStream(bytes.inputStream()).use { input ->
            fun count(max: Int): Int = input.readInt().also { require(it in 0..max) }
            fun take(n: Int): ByteArray { require(n <= input.available()); return ByteArray(n).also(input::readFully) }
            fun text(): String = take(count(200)).also { require(it.all { b -> b >= 0 }) }.toString(Charsets.US_ASCII)
            require(input.readInt() == 0x44505852 && input.readInt() == 1)
            val records = List(count(4096)) {
                val type = text(); val id = text(); val version = input.readInt(); val flag = count(1)
                val dependencies = List(count(16)) { text() }; val payload = take(count(65536)).toList()
                SharedRecord(type, id, version, flag == 1, dependencies, payload)
            }
            require(input.available() == 0 && encode(records).contentEquals(bytes))
            records
        }
    }
    fun businessHash(records: List<SharedRecord>): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(records)).joinToString("") { "%02x".format(it) }
}

interface SharedTypeAdapter {
    val typeId: String
    val schemaVersion: Int
    val maxPayload: Int
    val maxRecords: Int get() = 4096
    fun validate(record: SharedRecord, records: List<SharedRecord>)
    fun relatedKeys(record: SharedRecord, state: ScheduleState): Set<String> = emptySet()
    fun validateState(state: ScheduleState) {}
}

class SharedTypeRegistry(adapters: List<SharedTypeAdapter>) {
    private val types = adapters.associateBy { it.typeId }
    init { require(types.size == adapters.size); adapters.forEach { require(it.schemaVersion > 0 && it.maxPayload in 0..65536 && it.maxRecords in 0..4096) } }
    fun relatedKeys(r: SharedRecord, state: ScheduleState): Set<String> = if (supported(r)) types.getValue(r.typeId).relatedKeys(r,state) else emptySet()
    fun supported(r: SharedRecord) = types[r.typeId]?.schemaVersion == r.schemaVersion
    fun validate(records: List<SharedRecord>) {
        SharedRecordCodec.encode(records)
        types.values.forEach { type -> require(records.count { it.typeId == type.typeId } <= type.maxRecords) }
        records.filter(::supported).forEach { r -> types.getValue(r.typeId).let {
            require(r.payload.size <= it.maxPayload); it.validate(r, records)
        } }
    }
    fun validateState(state: ScheduleState) {
        validate(state.sharedRecords.orEmpty())
        types.values.forEach { it.validateState(state) }
    }
    fun writable(records: List<SharedRecord>) {
        validate(records)
        records.forEach { r ->
            require(!r.required || supported(r)) { "Unsupported required capability" }
            require(r.dependencies.all { type -> type in types && records.filter { it.typeId == type }.all(::supported) }) { "Unknown dependency" }
        }
    }
    fun replace(records: List<SharedRecord>, record: SharedRecord): List<SharedRecord> {
        writable(records); require(records.filter { it.key==record.key }.all(::supported)) { "Unsupported existing schema" }; require(supported(record)) { "Unregistered business write" }
        return (records.filter { it.key != record.key } + record).also(::writable).let { SharedRecordCodec.decode(SharedRecordCodec.encode(it)) }
    }
    fun remove(records: List<SharedRecord>, typeId: String, recordId: String): List<SharedRecord> {
        writable(records)
        val record = records.find { it.typeId == typeId && it.recordId == recordId }
        require(record == null || supported(record))
        return records.filter { it !== record }.also(::writable)
    }
}

data class SharedRecordConflict(val key: String, val base: SharedRecord?, val local: SharedRecord?, val remote: SharedRecord?)
data class SharedRecordMerge(val records: List<SharedRecord>, val conflicts: List<SharedRecordConflict>)
object SharedRecords {
    fun merge(base: List<SharedRecord>, local: List<SharedRecord>, remote: List<SharedRecord>): SharedRecordMerge {
        listOf(base, local, remote).forEach(SharedRecordCodec::encode)
        fun index(rows: List<SharedRecord>) = SharedRecordCodec.decode(SharedRecordCodec.encode(rows)).associateBy { it.key }
        val b = index(base); val l = index(local); val r = index(remote)
        val records = mutableListOf<SharedRecord>(); val conflicts = mutableListOf<SharedRecordConflict>()
        (b.keys + l.keys + r.keys).sorted().forEach { key ->
            val bv = b[key]; val lv = l[key]; val rv = r[key]
            when { lv == rv -> lv?.let(records::add)
                lv == bv -> rv?.let(records::add)
                rv == bv -> lv?.let(records::add)
                else -> conflicts.add(SharedRecordConflict(key, bv, lv, rv)) }
        }
        SharedRecordCodec.encode(records)
        return SharedRecordMerge(records, conflicts)
    }
}

object SharedCourseColor : SharedTypeAdapter {
    override val typeId = "course.color"
    override val schemaVersion = 1
    override val maxPayload = 4
    override fun validate(record: SharedRecord, records: List<SharedRecord>) {
        require(record.recordId.matches(Regex("[0-9a-f]{64}")))
        require(record.payload == listOf(0.toByte()) || record.payload.size == 4 && record.payload[0] == 1.toByte())
    }
    override fun relatedKeys(record: SharedRecord, state: ScheduleState): Set<String> = state.sharedCourseParents[record.recordId].orEmpty()
    fun automaticColor(recordId: String): List<Byte> {
        require(SharedRecordCodec.identity(recordId))
        return MessageDigest.getInstance("SHA-256").digest(recordId.toByteArray(Charsets.US_ASCII)).take(3)
    }
}

