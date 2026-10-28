package com.dormpanel.app.schedule

/** Record-level three-way merge. Missing records are deletions, never implicit inheritance. */
object ScheduleMerge {
    private data class Source(val source: ImportSource, val occurrences: List<ImportedClassOccurrence>) {
        // Import time is incidental metadata. Identical replacements are the same operation;
        // content, identities, term binding and sparse edits are never selected by timestamp.
        override fun equals(other: Any?) = other is Source && source.copy(importedAt = 0) == other.source.copy(importedAt = 0) && occurrences == other.occurrences
        override fun hashCode() = 31 * source.copy(importedAt = 0).hashCode() + occurrences.hashCode()
    }
    private data class Profile(val value: TermScheduleProfile, val custom: Boolean)
    data class Conflict(val key: String, val description: String, val keys: Set<String> = setOf(key))
    data class Result(val state: ScheduleState, val conflicts: List<Conflict>)
    enum class Choice { LOCAL, REMOTE }

    private fun records(s: ScheduleState): Map<String, Any> = buildMap {
        s.sharedRecords?.forEach { put("shared:${it.key}", it.copy(dependencies = it.dependencies.sorted())) }
        s.events.forEach { put("event:${it.id}", it) }
        s.entries.forEach { put("class:${it.id}", it) }
        val imported = s.imported.groupBy { it.sourceId }
        s.sources.forEach { put("source:${it.id}", Source(it, imported[it.id].orEmpty().sortedBy { row -> row.id })) }
        s.classOverrides.forEach { put("edit:${it.key}", it) }
        s.dayAdjustments.forEach { put("date:${it.date}", it) }
        s.profiles.forEach { (key, p) ->
            val normalized = p.copy(phases = p.phases.sortedBy { it.effectiveFrom }.map { phase -> phase.copy(periods = phase.periods.sortedBy { it.periodNumber }) })
            put("term:$key", Profile(normalized, key in s.customProfileKeys))
        }
    }
    fun same(a: ScheduleState, b: ScheduleState) = records(a) == records(b)

    fun merge(base: ScheduleState, local: ScheduleState, remote: ScheduleState, choice: Choice? = null,
        forceConflicts: Set<String> = emptySet(), maxConflicts: Int = Int.MAX_VALUE, sharedRegistry: SharedTypeRegistry = SharedTypes.registry()): Result {
        val b = records(base); val l = records(local); val r = records(remote)
        val keys = (b.keys + l.keys + r.keys).sorted()
        val parents = keys.associateWith { it }.toMutableMap()
        fun root(key: String): String { var v = key; while (parents.getValue(v) != v) v = parents.getValue(v); return v }
        fun join(a: String, c: String) { if (a in parents && c in parents) parents[root(c)] = root(a) }
        val states = listOf(base, local, remote)
        states.forEach { state -> state.sharedRecords.orEmpty().forEach { record ->
            sharedRegistry.relatedKeys(record,state).forEach { parent ->
                if (l[parent] != b[parent] || r[parent] != b[parent]) join(parent,"shared:${record.key}")
            }
        } }
        // Deletion of a parent and an edit of a child is one explicit decision, avoiding orphan edits.
        states.flatMap { it.classOverrides }.distinctBy { it.key }.forEach { edit ->
            val parent = edit.sourceId?.let { "source:$it" } ?: "class:${edit.seriesId}"
            val key = "edit:${edit.key}"
            if (b[parent] != null && (l[parent] == null || r[parent] == null)) join(parent, key)
        }
        // Term changes and concurrent edits to dependent courses are deliberately conservative.
        // The whole related component is chosen together; independent terms/courses still merge.
        states.flatMap { it.sources }.distinctBy { it.id to it.resolvedTermKey() }.forEach { source ->
            source.resolvedTermKey()?.let { term ->
                val p = "term:$term"; val src = "source:${source.id}"
                if (l[p] != b[p] || r[p] != b[p]) {
                    join(p, src)
                    states.flatMap { it.classOverrides }.filter { it.sourceId == source.id }.forEach { join(p, "edit:${it.key}") }
                }
            }
        }
        // Date composition changes can invalidate a this-week edit's anchor or destination.
        // Weekly series edits remain independent; their identities survive day composition.
        val editsByDate = states.flatMap { it.classOverrides }.flatMap { edit ->
            listOfNotNull(edit.anchorDate, edit.date).distinct().map { it to edit.key }
        }.groupBy({ it.first }, { it.second })
        states.flatMap { it.dayAdjustments }.map { it.date }.distinct().forEach { date ->
            fun composition(map: Map<String, Any>) = (map["date:$date"] as? DayAdjustment)?.copy(label = "")
            if (composition(l) != composition(b) || composition(r) != composition(b)) {
                editsByDate[date].orEmpty().distinct().forEach { join("date:$date", "edit:$it") }
            }
        }
        val conflicts = mutableListOf<Conflict>()
        val merged = mutableMapOf<String, Any>()
        keys.groupBy(::root).values.forEach { group ->
            fun take(map: Map<String, Any>) = group.associateWith { map[it] }
            val bv = take(b); val lv = take(l); val rv = take(r)
            val forced = group.any { it in forceConflicts }
            val selected = when {
                !forced && lv == rv -> rv // adopt confirmed metadata for semantically identical content
                !forced && lv == bv -> rv
                !forced && rv == bv -> lv
                else -> {
                    check(conflicts.size < maxConflicts) { "冲突条目超过本轮处理上限；本地修改和远端版本已保留" }
                    val descriptions = group.map { key ->
                        val value = l[key] ?: r[key] ?: b[key]
                        when (value) {
                            is TimetableEntry -> "课程 ${value.title}"
                            is CalendarEvent -> "事件 ${value.title}"
                            is Source -> "来源 ${value.source.displayName}"
                            is Profile -> "学期 ${value.value.termKey}"
                            is ClassOverride -> {
                                val title = states.asSequence().flatMap { it.imported.asSequence() }
                                    .firstOrNull { it.sourceId == value.sourceId && it.seriesId == value.seriesId }?.title
                                    ?: states.flatMap { it.entries }.firstOrNull { it.id == value.seriesId }?.title ?: value.seriesId
                                "课程编辑 $title · ${value.anchorDate ?: "全部周次"}"
                            }
                            else -> key
                        }
                    }.distinct().joinToString("、")
                    fun time(minute: Int?) = minute?.let { "%02d:%02d".format(java.util.Locale.ROOT, it / 60, it % 60) } ?: "继承"
                    fun summary(map: Map<String, Any>) = group.filter { forced || l[it] != r[it] }.joinToString("；") { key ->
                        when (val value = map[key]) {
                            null -> "已删除 / 已重置"
                            is TimetableEntry -> "${value.title} 星期${value.day} ${time(value.startMinute)}–${time(value.endMinute)} ${value.location}"
                            is CalendarEvent -> "${value.title} ${java.time.Instant.ofEpochMilli(value.start).atZone(java.time.ZoneId.systemDefault())}–${java.time.Instant.ofEpochMilli(value.end).atZone(java.time.ZoneId.systemDefault())} ${value.note}"
                            is ClassOverride -> {
                                fun text(v: String?) = when (v) { null -> "继承"; "" -> "显式清空"; else -> v }
                                "标题=${text(value.title)} 地点=${text(value.location)} 教师=${text(value.teacher)} 日期=${value.date ?: value.weekday ?: "继承"} 节次=${value.periodStart ?: "继承"}-${value.periodEnd ?: "继承"} 模式=${text(value.timingMode)} 时间=${time(value.startMinute)}–${time(value.endMinute)} 备注=${text(value.note)}"
                            }
                            is Profile -> "${value.value.termKey} ${value.value.timezone} 第一周=${value.value.week1Monday} 自定义=${value.custom}\n${TermProfileForm.phasesToText(value.value)}"
                            is Source -> "${value.source.displayName} ${value.source.occurrenceCount}节 ${value.source.sha256.take(8)}"
                            else -> value.toString()
                        }.take(1000)
                    }.take(2000)
                    conflicts += Conflict(group.sorted().first(), descriptions.take(1000) + "\n本地：" + summary(l) + "\n远端：" + summary(r), group.toSet())
                    if (choice == Choice.REMOTE) rv else lv
                }
            }
            selected.forEach { (key, value) -> if (value != null) merged[key] = value }
        }
        val values = merged.toSortedMap().values
        val sources = values.filterIsInstance<Source>()
        val profiles = values.filterIsInstance<Profile>()
        return Result(ScheduleState(events = values.filterIsInstance<CalendarEvent>(), entries = values.filterIsInstance<TimetableEntry>(),
            sources = sources.map { it.source }, imported = sources.flatMap { it.occurrences },
            dayAdjustments = values.filterIsInstance<DayAdjustment>(), classOverrides = values.filterIsInstance<ClassOverride>(),
            sharedRecords = if (states.any { it.sharedRecords != null }) values.filterIsInstance<SharedRecord>() else null,
            profiles = profiles.associate { it.value.termKey to it.value }, customProfileKeys = profiles.filter { it.custom }.map { it.value.termKey }.toSet()), conflicts)
    }
}

data class ScheduleSyncFrame(val baseline: ScheduleSnapshot?, val local: ScheduleState,
    val pending: ScheduleSnapshot?, val conflict: ScheduleSnapshot?, val captured: ScheduleState? = null) {
    val dirty get() = baseline?.let { !ScheduleMerge.same(it.state, local) } ?: false
}

/** Blocking API used only by the sync worker; every method serializes with local Room writes. */
interface ScheduleSyncStore {
    fun syncFrame(): ScheduleSyncFrame
    fun archiveMigrationRecovery(identity: ScheduleSnapshot) {}
    fun establishBaseline(bytes: ByteArray)
    fun stagePublication(bytes: ByteArray, captured: ScheduleState)
    fun integrateRemote(bytes: ByteArray): List<ScheduleMerge.Conflict>
    fun resolveConflict(choice: ScheduleMerge.Choice)
    fun discardUncommittedPublication()
    fun historyCursor(head: ByteArray): ByteArray?
    fun saveHistoryCursor(head: ByteArray, cursor: ByteArray)
}
