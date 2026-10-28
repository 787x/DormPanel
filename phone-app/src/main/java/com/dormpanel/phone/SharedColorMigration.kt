package com.dormpanel.phone

import android.content.Context
import com.dormpanel.app.schedule.*

/** Read-only preview: cancellation and failed Store writes leave PR68 preferences intact. */
internal object SharedColorMigration {
    enum class Decision { KEEP_SHARED, USE_LOCAL, SKIP }
    data class Conflict(val recordId: String, val courses: Set<String>, val shared: SharedRecord, val local: SharedRecord, val courseTitles: Set<String>)
    data class Preview(val expectedHash: String, val records: List<SharedRecord>, val previousRgb: Map<String, Int>, val automaticRgb: Map<String, Int>, val conflicts: List<Conflict>, val automaticVisualChanges: Set<String>)
    fun preview(context: Context, state: ScheduleState): Preview = previewValues(CourseColors.confirmedMigrationValues(context),state)
    fun previewValues(stored: Map<String, Any?>, state: ScheduleState): Preview {
        val records = mutableListOf<SharedRecord>(); val previous = mutableMapOf<String, Int>(); val automatic = mutableMapOf<String, Int>()
        val identity = CourseColorIdentity(state)
        identity.active.sorted().forEach { key ->
            val id = SyncV3Codec.hash(key.toByteArray(Charsets.UTF_8))
            val manual = (stored["m$id"] as? Int)?.takeIf { it in 0..0xffffff }
            val slot = (stored["a$id"] as? Int)?.takeIf { it in CourseColors.palette.indices }
            if (manual != null || slot != null) {
                previous[id] = manual ?: CourseColors.palette[slot!!]
                automatic[id] = SharedCourseColor.automaticColor(id).fold(0) { n, v -> (n shl 8) or (v.toInt() and 255) }
                records += SharedRecord("course.color", id, 1, payload = if (manual == null) listOf(0.toByte()) else listOf(1,manual shr 16,manual shr 8,manual).map(Int::toByte))
            }
        }
        val existing = requireNotNull(state.sharedRecords) { "请先显式升级隔离数据集" }
        val registry = SharedTypes.registry()
        registry.writable(existing)
        val titles=CourseBatch.candidates(state).groupBy { candidate -> identity.key(candidate.sourceId,candidate.series.first())?.let { SyncV3Codec.hash(it.toByteArray(Charsets.UTF_8)) } }
        val conflicts=records.mapNotNull { local -> existing.find { it.key==local.key }?.takeIf { it!=local }?.let { shared ->
            Conflict(local.recordId,state.sharedCourseParents[local.recordId].orEmpty(),shared,local,titles[local.recordId].orEmpty().map { it.originalTitle }.toSet())
        } }
        val merged = records.filter { local -> existing.none { it.key==local.key } }.fold(existing) { rows,r -> registry.replace(rows,r) }
        return Preview(SyncV3Codec.businessHash(state), merged, previous, automatic, conflicts, records.filter { it.payload==listOf(0.toByte()) && previous[it.recordId]!=automatic[it.recordId] }.map { it.recordId }.toSet())
    }
    fun decide(preview: Preview, decisions: Map<String, Decision>): List<SharedRecord> {
        require(decisions.keys == preview.conflicts.map { it.recordId }.toSet()) { "每门差异课程都需要明确决策" }
        return preview.conflicts.fold(preview.records) { rows,c ->
            if(decisions.getValue(c.recordId)==Decision.USE_LOCAL) SharedTypes.registry().replace(rows,c.local) else rows
        }
    }
    fun confirm(store: RoomScheduleStore, preview: Preview, decisions: Map<String, Decision> = emptyMap(), callback: (Result<Unit>) -> Unit) =
        runCatching { decide(preview,decisions) }.fold({ store.putSharedRecords(it, preview.expectedHash, callback) },{ callback(Result.failure(it)) })
}
