package com.dormpanel.phone

import android.content.Context
import android.graphics.Color
import com.dormpanel.app.schedule.*

/** A read-only resolver per loaded state; preferences never contain course content. */
internal object CourseColors {
    private const val MAX_IDENTITIES = 60000 // RAW permits 10000 local entries plus 50000 imported instances.
    // Slots remain unchanged; saturated accents retain separation after theme tinting.
    val palette = intArrayOf(0x0040ff,0xbfff00,0xff0040,0x4000ff,0xffbf00,0x00ffff,
        0xff4000,0x59acff,0x802d2d,0x2d806b,0x806000,0x00ff80,0xff00ff,0x00ff00,0x008000,0xbf43bf)
    private const val MANUAL_WRITE = "manual_write"
    // commit(false) may already have changed SharedPreferences' in-process map. Keep the
    // previously confirmed value visible until an explicit manual save commits successfully.
    private val uncertain = java.util.WeakHashMap<android.content.SharedPreferences, MutableMap<String, Int?>>()
    fun background(rgb: Int, dark: Boolean): Int {
        fun channel(c: Int) = if (dark) (c * .33 + 14).toInt() else (c * .32 + 255 * .68).toInt()
        return (0xff shl 24) or (channel(rgb ushr 16 and 255) shl 16) or (channel(rgb ushr 8 and 255) shl 8) or channel(rgb and 255)
    }
    fun ink(dark: Boolean) = if (dark) Color.rgb(245,245,245) else Color.rgb(28,35,42)
    private fun prefs(context: Context) = context.getSharedPreferences("course_colors_v1",Context.MODE_PRIVATE)
    private fun digest(key: String) = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    @Synchronized fun confirmedMigrationValues(context: Context): Map<String, Any?> {
        val pref = prefs(context); val stored = pref.all.toMutableMap()
        uncertain[pref]?.forEach { (key,value) -> if (value == null) stored.remove(key) else stored[key] = value }
        return stored
    }
    class Snapshot(val identity: CourseColorIdentity, private val automatic: Map<String, Int>, private val manual: Map<String, Int>, private val shared: Map<String, Int> = emptyMap()) {
        fun key(candidate: CourseCandidate) = identity.key(candidate.sourceId,candidate.series.first())
        fun manual(key: String) = manual[key]
        fun rgb(key: String, override: Int? = manual[key]) = override ?: shared[key] ?: palette[automatic[key] ?: 0]
        fun rgb(item: ClassOccurrence) = identity.key(item.sourceId,item.colorSeriesId)?.let { rgb(it) } ?: palette[0]
    }
    @Synchronized fun snapshot(context: Context, state: ScheduleState): Snapshot {
        val identity = CourseColorIdentity(state)
        require(identity.active.size <= MAX_IDENTITIES) { "课程颜色超出本地预算" }
        if (state.sharedRecords != null) {
            val values = CourseBatch.candidates(state).associate { candidate ->
                identity.key(candidate.sourceId, candidate.series.first())!! to
                    SharedCourseColors.display(state, candidate.sourceId, candidate.series.first())
            }
            return Snapshot(identity, emptyMap(), values.filterValues { it.status == "MANUAL" }.mapValues { it.value.rgb },
                values.mapValues { (key, _) -> SharedCourseColor.automaticColor(digest(key)).fold(0) { n,v -> (n shl 8) or (v.toInt() and 255) } })
        }
        val pref = prefs(context)
        val stored = pref.all.toMutableMap()
        uncertain[pref]?.forEach { (key,value) -> if (value == null) stored.remove(key) else stored[key] = value }
        val auto = identity.active.mapNotNull { key -> (stored["a"+digest(key)] as? Int)?.let { key to it } }.toMap()
        val assigned = CourseColorAllocation.allocate(identity.active,auto,palette.size)
        val manual = identity.active.mapNotNull { key -> (stored["m"+digest(key)] as? Int)?.takeIf { it in 0..0xffffff }?.let { key to it } }.toMap()
        // Keep recently unseen identities for 30 days: a concurrent older Widget read must
        // not erase a newly saved color. Only digests and integers are stored, capped below.
        val day = System.currentTimeMillis() / 86400000L
        val activeHashes = identity.active.map(::digest).toSet()
        val retained = stored.keys.filter { it.startsWith("t") && it.length == 65 }
            .map { it.substring(1) }.filter { hash ->
                val seen = stored["t"+hash] as? Long ?: 0L
                hash !in activeHashes && seen in (day-30)..day
            }.sortedByDescending { stored["t"+it] as? Long ?: 0L }.take((maxOf(10000,activeHashes.size)-activeHashes.size).coerceAtLeast(0))
        val desired = mutableMapOf<String,Any>()
        (stored[MANUAL_WRITE] as? String)?.let { desired[MANUAL_WRITE] = it }
        retained.forEach { hash -> listOf("a","m","t").forEach { prefix -> stored[prefix+hash]?.let { desired[prefix+hash] = it } } }
        assigned.forEach { (key,value) -> desired["a"+digest(key)] = value; desired["t"+digest(key)] = day }
        manual.forEach { (key,value) -> desired["m"+digest(key)] = value }
        if (stored == desired) return Snapshot(identity,assigned,manual)
        // Automatic assignments are a display cache. A disk failure must not hide readable
        // courses; this snapshot still uses the retained slots and deterministic allocation.
        runCatching {
            val edit = pref.edit().clear()
            desired.forEach { (key,value) -> when(value) { is Int -> edit.putInt(key,value); is Long -> edit.putLong(key,value); is String -> edit.putString(key,value) } }
            check(edit.commit()) { "课程颜色配置保存失败" }
        }.onFailure { android.util.Log.w("CourseColors", "自动颜色缓存未持久化，继续使用当前显示分配", it) }
        return Snapshot(identity,assigned,manual)
    }
    @Synchronized fun save(context: Context, key: String, rgb: Int?) {
        val pref = prefs(context)
        val storedKey = "m"+digest(key)
        val pending = uncertain.getOrPut(pref) { mutableMapOf() }
        if (!pending.containsKey(storedKey)) pending[storedKey] =
            (pref.all[storedKey] as? Int)?.takeIf { it in 0..0xffffff }
        runCatching {
            val edit = pref.edit()
            if (rgb == null) edit.remove(storedKey) else edit.putInt(storedKey,rgb and 0xffffff)
            // A fresh marker forces a real write even if a failed prior commit left the
            // requested RGB in memory. A matching in-memory value is never a save receipt.
            edit.putString(MANUAL_WRITE, java.util.UUID.randomUUID().toString())
            check(edit.commit()) { "颜色保存失败，草稿保留" }
        }.getOrElse { throw IllegalStateException("颜色保存失败，草稿保留",it) }
        pending.remove(storedKey)
        if (pending.isEmpty()) uncertain.remove(pref)
        TodayWidgetUpdates.request(context)
    }
}
