package com.dormpanel.phone

import android.content.Context
import android.graphics.Color
import com.dormpanel.app.schedule.*

/** A read-only resolver per loaded state; preferences never contain course content. */
internal object CourseColors {
    private const val MAX_IDENTITIES = 60000 // RAW permits 10000 local entries plus 50000 imported instances.
    val palette = intArrayOf(0x4789ff,0x719447,0xc06488,0x9670bf,0xb58b39,0x399d91,
        0xd4764c,0x568eac,0xae6870,0x7a83bc,0x9b9146,0x4c9b72,0xb575ae,0x877560,0x5b96bb,0xb67b47)
    fun background(rgb: Int, dark: Boolean): Int {
        fun channel(c: Int) = if (dark) (c * .30 + 14).toInt() else (c * .23 + 255 * .77).toInt()
        return Color.rgb(channel(Color.red(rgb)),channel(Color.green(rgb)),channel(Color.blue(rgb)))
    }
    fun ink(dark: Boolean) = if (dark) Color.rgb(245,245,245) else Color.rgb(28,35,42)
    private fun prefs(context: Context) = context.getSharedPreferences("course_colors_v1",Context.MODE_PRIVATE)
    private fun digest(key: String) = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    class Snapshot(val identity: CourseColorIdentity, private val automatic: Map<String, Int>, private val manual: Map<String, Int>) {
        fun key(candidate: CourseCandidate) = identity.key(candidate.sourceId,candidate.series.first())
        fun manual(key: String) = manual[key]
        fun rgb(key: String, override: Int? = manual[key]) = override ?: palette[automatic[key] ?: 0]
        fun rgb(item: ClassOccurrence) = identity.key(item.sourceId,item.seriesId)?.let { rgb(it) } ?: palette[0]
    }
    @Synchronized fun snapshot(context: Context, state: ScheduleState): Snapshot {
        val identity = CourseColorIdentity(state)
        require(identity.active.size <= MAX_IDENTITIES) { "课程颜色超出本地预算" }
        val pref = prefs(context)
        val stored = pref.all
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
        retained.forEach { hash -> listOf("a","m","t").forEach { prefix -> stored[prefix+hash]?.let { desired[prefix+hash] = it } } }
        assigned.forEach { (key,value) -> desired["a"+digest(key)] = value; desired["t"+digest(key)] = day }
        manual.forEach { (key,value) -> desired["m"+digest(key)] = value }
        if (stored == desired) return Snapshot(identity,assigned,manual)
        // Automatic assignments are a display cache. A disk failure must not hide readable
        // courses; this snapshot still uses the retained slots and deterministic allocation.
        runCatching {
            val edit = pref.edit().clear()
            desired.forEach { (key,value) -> when(value) { is Int -> edit.putInt(key,value); is Long -> edit.putLong(key,value) } }
            check(edit.commit()) { "课程颜色配置保存失败" }
        }.onFailure { android.util.Log.w("CourseColors", "自动颜色缓存未持久化，继续使用当前显示分配", it) }
        return Snapshot(identity,assigned,manual)
    }
    @Synchronized fun save(context: Context, key: String, rgb: Int?) {
        runCatching {
            val edit = prefs(context).edit()
            if (rgb == null) edit.remove("m"+digest(key)) else edit.putInt("m"+digest(key),rgb and 0xffffff)
            check(edit.commit()) { "颜色保存失败，草稿保留" }
        }.getOrElse { throw IllegalStateException("颜色保存失败，草稿保留",it) }
        TodayWidgetUpdates.request(context)
    }
}
