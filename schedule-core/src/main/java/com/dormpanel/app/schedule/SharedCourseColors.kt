package com.dormpanel.app.schedule

/** Same logical grouping as PR68; display overrides never change this identity. */
object SharedCourseColors {
    fun parents(state: ScheduleState): Map<String, Set<String>> {
        fun frame(vararg parts: String) = parts.joinToString("") { "${it.length}:$it" }
        val result = mutableMapOf<String, MutableSet<String>>()
        fun add(key: String, parent: String) { result.getOrPut(SyncV3Codec.hash(key.toByteArray(Charsets.UTF_8))) { mutableSetOf() }.add(parent) }
        state.entries.forEach { add(frame("local",it.id), "class:${it.id}") }
        state.imported.groupBy { it.sourceId to it.seriesId }.forEach { (key,rows) ->
            val identity = if (rows.map { it.title }.distinct().size > 1) frame("ambiguous",key.first,key.second) else frame("import",key.first,rows.first().title)
            add(identity,"source:${key.first}")
        }
        return result
    }
    fun legacyKey(state: ScheduleState, sourceId: String?, seriesId: String): String {
        fun frame(vararg parts: String) = parts.joinToString("") { "${it.length}:$it" }
        if (sourceId == null) { require(state.entries.any { it.id == seriesId }); return frame("local", seriesId) }
        val rows = state.imported.filter { it.sourceId == sourceId && it.seriesId == seriesId }
        require(rows.isNotEmpty())
        return if (rows.map { it.title }.distinct().size > 1) frame("ambiguous", sourceId, seriesId)
            else frame("import", sourceId, rows.first().title)
    }
    fun identity(state: ScheduleState, sourceId: String?, seriesId: String) = SyncV3Codec.hash(legacyKey(state, sourceId, seriesId).toByteArray(Charsets.UTF_8))
    fun change(state: ScheduleState, sourceId: String?, seriesId: String, rgb: Int?): ScheduleState {
        require(rgb == null || rgb in 0..0xffffff)
        val id = identity(state, sourceId, seriesId)
        val payload = if (rgb == null) listOf(0.toByte()) else listOf(1, rgb shr 16, rgb shr 8, rgb).map(Int::toByte)
        val record = SharedRecord("course.color", id, 1, payload = payload)
        val rows = requireNotNull(state.sharedRecords) { "Explicit shared format upgrade required" }
        return state.copy(sharedRecords = SharedTypes.registry().replace(rows, record))
    }
    fun rgb(state: ScheduleState, sourceId: String?, seriesId: String): Int {
        val id = identity(state, sourceId, seriesId)
        val row = state.sharedRecords?.find { it.typeId == "course.color" && it.recordId == id }
        val bytes = if (row == null || row.payload == listOf(0.toByte())) SharedCourseColor.automaticColor(id) else {
            require(row.schemaVersion == 1); SharedCourseColor.validate(row, state.sharedRecords.orEmpty()); row.payload.drop(1)
        }
        return bytes.fold(0) { n, v -> (n shl 8) or (v.toInt() and 255) }
    }
}
