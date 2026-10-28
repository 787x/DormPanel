package com.dormpanel.phone

import android.content.Context
import com.dormpanel.app.schedule.*

/** Read-only preview: cancellation and failed Store writes leave PR68 preferences intact. */
internal object SharedColorMigration {
    data class Preview(val expectedHash: String, val records: List<SharedRecord>, val previousRgb: Map<String, Int>, val automaticRgb: Map<String, Int>)
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
        val merged = records.fold(existing) { rows, r -> registry.replace(rows,r) }
        return Preview(SyncV3Codec.businessHash(state), merged, previous, automatic)
    }
    fun confirm(store: RoomScheduleStore, preview: Preview, callback: (Result<Unit>) -> Unit) =
        store.putSharedRecords(preview.records, preview.expectedHash, callback)
}
