package com.dormpanel.phone

import com.dormpanel.app.schedule.*

/** Display identity only. Length framing prevents delimiter and Unicode ambiguity. */
internal class CourseColorIdentity(state: ScheduleState) {
    companion object {
        fun encode(vararg parts: String) = parts.joinToString("") { "${it.length}:$it" }
    }
    private val ambiguousSeries = state.imported.groupBy { it.sourceId to it.seriesId }
        .filterValues { rows -> rows.map { it.title }.distinct().size > 1 }.keys
    private val keys = buildMap<Pair<String?, String>, String> {
        CourseBatch.candidates(state).forEach { candidate ->
            val ambiguous = candidate.sourceId to candidate.series.first() in ambiguousSeries
            val key = when {
                candidate.sourceId == null -> encode("local", candidate.series.single())
                ambiguous -> encode("ambiguous", candidate.sourceId!!, candidate.series.single())
                else -> encode("import", candidate.sourceId!!, candidate.originalTitle)
            }
            candidate.series.forEach { put(candidate.sourceId to it, key) }
        }
    }
    val active: Set<String> = keys.values.toSet()
    fun key(source: String?, series: String): String? = keys[source to series]
}

internal object CourseColorAllocation {
    fun allocate(keys: Set<String>, previous: Map<String, Int>, size: Int): Map<String, Int> {
        val result = previous.filter { it.key in keys && it.value in 0 until size }.toMutableMap()
        val counts = IntArray(size)
        result.values.forEach { counts[it]++ }
        keys.sorted().forEach { key ->
            if (key !in result) {
                val start = Math.floorMod(key.hashCode(), size)
                val choice = (0 until size).map { (start + it) % size }.minBy { counts[it] }
                result[key] = choice; counts[choice]++
            }
        }
        return result
    }
}
