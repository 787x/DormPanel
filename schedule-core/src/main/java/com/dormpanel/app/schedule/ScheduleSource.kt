package com.dormpanel.app.schedule

import java.time.*
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

interface ScheduleClock {
    fun instant(): Instant
    fun zone(): ZoneId
    fun locale(): Locale
    fun today(): LocalDate = instant().atZone(zone()).toLocalDate()
}
object DeviceScheduleClock : ScheduleClock {
    override fun instant(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
    override fun locale(): Locale = Locale.getDefault()
}
data class CalendarEvent(val id: String, val title: String, val start: Long, val end: Long, val note: String = "") {
    fun valid() = id.isNotBlank() && title.isNotBlank() && end > start &&
        start >= -62135510400000L && end <= 253402214400000L
}
data class TimetableEntry(val id: String, val title: String, val day: Int, val startMinute: Int,
    val endMinute: Int, val location: String = "") {
    fun valid() = id.isNotBlank() && title.isNotBlank() && day in 1..7 &&
        startMinute in 0..1439 && endMinute in 1..1439 && endMinute > startMinute
}
data class ScheduleState(val events: List<CalendarEvent> = emptyList(), val entries: List<TimetableEntry> = emptyList(),
    val invalidRecords: Int = 0, val sources: List<ImportSource> = emptyList(),
    val imported: List<ImportedClassOccurrence> = emptyList(),
    val dayAdjustments: List<DayAdjustment> = emptyList(), val classOverrides: List<ClassOverride> = emptyList(),
    val profiles: Map<String, TermScheduleProfile> = emptyMap(), val sync: SnapshotIdentity? = null,
    val customProfileKeys: Set<String> = emptySet(), val sharedRecords: List<SharedRecord>? = null) {
    val courseSlots by lazy { sharedRecords.orEmpty().filter { it.typeId=="course.schedule" && it.schemaVersion==1 }.map(SharedCourseSchedule::decode) }
    val sharedCourseParents by lazy { SharedCourseColors.parents(this) }
    val importedIndex by lazy { ImportedOccurrenceIndex(imported) }
    val daysByDate by lazy { dayAdjustments.associateBy { it.date } }
    val editsByKey by lazy { classOverrides.associateBy { it.key } }
}
data class SnapshotIdentity(val datasetId: String, val revision: String, val pending: Boolean = false)
data class ClassBatchSaveReceipt(val refreshed: Boolean) {
    val message get() = if (refreshed) "已保存到本机" else "已保存到本机，但重新读取失败；修改已经提交。恢复读取前暂不可编辑或同步。"
}
class ScheduleCommitUnconfirmed(cause: Throwable) : IllegalStateException(
    "保存结果尚待确认，请完成同步恢复检查；暂时不要重复提交。请从共享课表使用原有“重开活动 Store”恢复入口，重新读取后确认实际数据。", cause)
class ScheduleLocalReadFailed : IllegalStateException("本机课表读取失败，暂不可编辑；原有数据保留，请重新读取成功后再操作")
interface ScheduleStore {
    fun exportSnapshot(datasetId: String, parent: String?, generation: Long, callback: (Result<ByteArray>) -> Unit) { callback(Result.failure(UnsupportedOperationException())) }
    fun applySnapshot(bytes: ByteArray, callback: (Result<Unit>) -> Unit) { callback(Result.failure(UnsupportedOperationException())) }

    fun load(callback: (Result<ScheduleState>) -> Unit)
    /** Background presentation read; implementations may include their persisted profile catalog. */
    fun loadPresentation(callback: (Result<ScheduleState>) -> Unit) = load(callback)
    fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit)
    fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit)
    fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit)
    fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit)
    fun putDayAdjustment(item: DayAdjustment?, date: String, callback: (Result<Unit>) -> Unit) {
        callback(Result.failure(UnsupportedOperationException("Day adjustments unavailable")))
    }
    fun putClassOverride(item: ClassOverride?, key: String, callback: (Result<Unit>) -> Unit) {
        callback(Result.failure(UnsupportedOperationException("Class overrides unavailable")))
    }
    fun putClassOverrides(expected: ScheduleState, items: List<ClassOverride>, callback: (Result<Unit>) -> Unit) {
        callback(Result.failure(UnsupportedOperationException("Atomic class edits unavailable")))
    }
    fun commitImport(source: ImportSource, occurrences: List<ImportedClassOccurrence>, replace: Boolean,
        callback: (Result<ImportCommit>) -> Unit) { callback(Result.failure(UnsupportedOperationException("Imports unavailable"))) }
    fun deleteImport(id: String, callback: (Result<Unit>) -> Unit) { callback(Result.failure(UnsupportedOperationException("Imports unavailable"))) }
    fun close()
}

/** Main-thread confined. Global identities belong to this boundary, never to dashboard cards.
 * Invalid disk rows are withheld with a visible warning, and remain intact for future recovery. */
class ScheduleSource(private val store: ScheduleStore, val clock: ScheduleClock = DeviceScheduleClock,
    private val profileResolver: (String) -> TermScheduleProfile? = { BuiltInProfiles.builtIn(it) }) {
    var applyingSnapshot = false; private set
    var retryingLocalRead = false; private set
    /** A readable cached snapshot does not admit writes after a failed read. Conflicts alone remain editable. */
    val readOnly get() = !closed && (error || ((store as? RoomScheduleStore)?.businessPaused() ?: ((store as? ScheduleV2Store)?.v2DatasetPaused() == true)))
    val editBlockReason get() = if (error) ScheduleLocalReadFailed().message else
        if (readOnly) (store as? RoomScheduleStore)?.businessBlockReason() ?: ScheduleDatasetWriteProtected().message else null
    val editable get() = !closed && ready && !applyingSnapshot && !readOnly
    val syncStore get() = store as? RoomScheduleStore
    var localCommitted: (() -> Unit)? = null
    internal fun markLocalPending() {
        state.sync?.let { state = state.copy(sync = it.copy(pending = true)) }
    }
    internal fun pauseForSync() { applyingSnapshot = true; refresh() }
    fun reload(callback: (Result<Unit>) -> Unit = {}) {
        if (closed) { callback(Result.failure(IllegalStateException("Schedule source closed"))); return }
        applyingSnapshot = true
        store.load { result ->
            result.onSuccess { state = ordered(if (it.sync == null) it.copy(profiles = effectiveProfiles(it.sources)) else it); ready = true; error = false }.onFailure { error = true }
            applyingSnapshot = false; refresh(); callback(result.map { })
        }
    }
    /** Error-only UI admission; no view callback or remote action is captured here. */
    fun retryLocalRead(): Boolean {
        if (closed || !error || applyingSnapshot || retryingLocalRead) return false
        retryingLocalRead = true
        reload {
            retryingLocalRead = false
            // reload already notified views with applyingSnapshot=false, under the local-only marker.
            // Do not emit a second normal-ready event which could start remote subscribers.
        }
        if (retryingLocalRead) refresh() // Async read: render progress after reload has gated edits.
        return true
    }
    fun exportSnapshot(datasetId: String, parent: String?, generation: Long, callback: (Result<ByteArray>) -> Unit) = store.exportSnapshot(datasetId, parent, generation, callback)
    fun applySnapshot(bytes: ByteArray, callback: (Result<Unit>) -> Unit) {
        if (error) { callback(Result.failure(ScheduleLocalReadFailed())); return }
        if (closed || !ready || applyingSnapshot) { callback(Result.failure(IllegalStateException("Timetable is busy"))); return }
        applyingSnapshot = true; refresh()
        store.applySnapshot(bytes) { result ->
            store.load { loaded ->
                loaded.onSuccess { state = ordered(it); ready = true; error = false }.onFailure { error = true }
                applyingSnapshot = false; refresh()
                callback(if (loaded.isFailure) Result.failure(loaded.exceptionOrNull()!!) else result)
            }
        }
    }
    var state = ScheduleState(); private set
    var ready = false; private set
    var error = false; private set
    val loadState get() = when { error -> SharedLocalRead.FAILED; !ready -> SharedLocalRead.LOADING; else -> SharedLocalRead.LOADED }
    private var closed = false
    private val listeners = linkedSetOf<() -> Unit>()
    init {
        store.load { result -> if (!closed) {
            result.onSuccess { loaded ->
                state = ordered(loaded.copy(events = loaded.events.filter { it.valid() }, entries = loaded.entries.filter { it.valid() },
                    dayAdjustments = loaded.dayAdjustments.filter { it.valid() }, classOverrides = loaded.classOverrides.filter { it.valid() },
                    profiles = if (loaded.sync != null) loaded.profiles else effectiveProfiles(loaded.sources),
                    invalidRecords = loaded.invalidRecords + loaded.events.count { !it.valid() } + loaded.entries.count { !it.valid() } +
                        loaded.dayAdjustments.count { !it.valid() } + loaded.classOverrides.count { !it.valid() }))
                ready = true; error = false
            }.onFailure { error = true }
            refresh()
        } }
    }
    fun subscribe(listener: () -> Unit) { if (!closed) listeners.add(listener) }
    fun unsubscribe(listener: () -> Unit) { listeners.remove(listener) }
    fun refresh() { if (!closed) listeners.toList().forEach { it() } }
    private fun effectiveProfiles(sources: List<ImportSource>) = sources.mapNotNull { item ->
        item.resolvedTermKey()?.let { term -> profileResolver(term)?.let { term to it } }
    }.toMap()
    /** Call after a profile-store mutation; imported base rows retain their original instants. */
    fun refreshProfiles() {
        if (closed || !ready || readOnly || applyingSnapshot) return
        if (state.sync != null) {
            val catalog = syncStore?.sharedCatalog() ?: return
            if (state.profiles != catalog.first || state.customProfileKeys != catalog.second) {
                state = state.copy(profiles = catalog.first, customProfileKeys = catalog.second); refresh()
            }
            localCommitted?.invoke()
        } else {
            val profiles = effectiveProfiles(state.sources)
            if (profiles != state.profiles) { state = state.copy(profiles = profiles); refresh() }
            localCommitted?.invoke()
        }
    }
    private fun ordered(value: ScheduleState) = value.copy(
        events = value.events.sortedWith(compareBy<CalendarEvent> { it.start }.thenBy { it.title }.thenBy { it.id }),
        entries = value.entries.sortedWith(compareBy<TimetableEntry> { it.day }.thenBy { it.startMinute }.thenBy { it.title }.thenBy { it.id }))
    private val completion: (Result<Unit>) -> Unit = { if (!closed) {
        if (it.exceptionOrNull() is ScheduleDatasetWriteProtected) {
            // The guard may become durable after the optimistic UI check but before the queued write.
            // Reload the accepted rows; an expected refusal is not a broken database.
            reload()
        } else if (it.isFailure) { error = true; refresh() } else localCommitted?.invoke()
    } }
    fun saveEvent(title: String, start: Long, end: Long, note: String = "", id: String? = null): Boolean {
        val event = CalendarEvent(id ?: UUID.randomUUID().toString(), title.trim(), start, end, note.trim())
        if (closed || !ready || readOnly || applyingSnapshot || !event.valid() || (id != null && state.events.none { it.id == id })) return false
        state = ordered(state.copy(events = state.events.filterNot { it.id == event.id } + event))
        store.put(event, completion); refresh(); return true
    }
    fun saveEntry(title: String, day: Int, start: Int, end: Int, location: String = "", id: String? = null): Boolean {
        val entry = TimetableEntry(id ?: UUID.randomUUID().toString(), title.trim(), day, start, end, location.trim())
        if (closed || !ready || readOnly || applyingSnapshot || !entry.valid() || (id != null && state.entries.none { it.id == id })) return false
        state = ordered(state.copy(entries = state.entries.filterNot { it.id == entry.id } + entry))
        store.put(entry, completion); refresh(); return true
    }
    fun deleteEvent(id: String) {
        if (closed || !ready || readOnly || applyingSnapshot || state.events.none { it.id == id }) return
        state = state.copy(events = state.events.filterNot { it.id == id }); store.deleteEvent(id, completion); refresh()
    }
    fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit = {}) {
        if (!editable || state.entries.none {it.id==id}) {callback(Result.failure(IllegalStateException("课程不可删除，请重新读取")));return}
        // Never optimistically remove a parent: a dependent may arrive before Writer execution.
        applyingSnapshot=true;refresh()
        store.deleteEntry(id) { result ->
            applyingSnapshot=false
            if(result.exceptionOrNull() is ScheduleCommitUnconfirmed) {refresh();callback(result)}
            else reload { loaded ->
                if(result.isSuccess) localCommitted?.invoke()
                callback(if(result.isFailure) result else if(loaded.isFailure) Result.failure(IllegalStateException("已删除到本机，但重新读取失败；请恢复读取，勿重复删除",loaded.exceptionOrNull())) else Result.success(Unit))
            }
        }
    }
    fun saveDayAdjustment(date: LocalDate, mode: String, weekday: Int? = null, label: String = ""): Boolean {
        val item = if (mode == "normal") null else DayAdjustment(date.toString(), mode, weekday, label.trim())
        if (closed || !ready || readOnly || applyingSnapshot || item != null && !item.valid() ||
            item != null && state.dayAdjustments.none { it.date == item.date } && state.dayAdjustments.size >= 1000) return false
        state = state.copy(dayAdjustments = state.dayAdjustments.filterNot { it.date == date.toString() } + listOfNotNull(item))
        store.putDayAdjustment(item, date.toString(), completion); refresh(); return true
    }
    fun saveClassOverride(sourceId: String?, seriesId: String, anchorDate: LocalDate?, patch: ClassPatch?): Boolean {
        val anchor = anchorDate?.toString()
        val key = ClassOverride.key(sourceId, seriesId, anchor)
        val item = patch?.let { ClassOverride.of(sourceId, seriesId, anchor, it) }
        val existingCharacters = state.classOverrides.filterNot { it.key == key }.sumOf { it.textCharacters().toLong() }
        if (closed || !ready || readOnly || applyingSnapshot || item != null && !item.valid() ||
            anchorDate != null && patch?.date != null &&
            runCatching { LocalDate.parse(patch.date).minusDays((LocalDate.parse(patch.date).dayOfWeek.value - 1).toLong()) }.getOrNull() !=
                anchorDate.minusDays((anchorDate.dayOfWeek.value - 1).toLong()) ||
            item != null && state.classOverrides.none { it.key == key } && state.classOverrides.size >= 5000 ||
            item != null && existingCharacters + item.textCharacters() > 2_000_000L) return false
        state = state.copy(classOverrides = state.classOverrides.filterNot { it.key == key } + listOfNotNull(item))
        store.putClassOverride(item, key, completion); refresh(); return true
    }
    fun saveSharedRecords(records: List<SharedRecord>, expectedHash: String, callback: (Result<ClassBatchSaveReceipt>) -> Unit) {
        if (!editable || syncStore==null) { callback(Result.failure(IllegalStateException(editBlockReason ?: "课表不可写"))); return }
        syncStore!!.putSharedRecords(records,expectedHash) { result ->
            if(result.isSuccess) reload { loaded -> localCommitted?.invoke();callback(Result.success(ClassBatchSaveReceipt(loaded.isSuccess))) }
            else callback(Result.failure(requireNotNull(result.exceptionOrNull())))
        }
    }
    fun saveCourseWithColor(expected: ScheduleState, items: List<ClassOverride>, candidate: CourseCandidate,
        rgb: Int?, records: List<SharedRecord>?, callback: (Result<ClassBatchSaveReceipt>) -> Unit) = saveCourseEdit(expected,items,candidate,CourseColorEdit.Set(rgb),records,callback)
    fun saveCourseEdit(expected: ScheduleState, items: List<ClassOverride>, candidate: CourseCandidate,
        color: CourseColorEdit, records: List<SharedRecord>?, callback: (Result<ClassBatchSaveReceipt>) -> Unit) {
        if (!editable || syncStore == null) { callback(Result.failure(IllegalStateException(editBlockReason ?: "课表不可写"))); return }
        syncStore!!.saveCourseEdit(expected, items, candidate, color, records) { result ->
            if (result.isSuccess) reload { loaded ->
                localCommitted?.invoke()
                callback(Result.success(ClassBatchSaveReceipt(loaded.isSuccess)))
            } else callback(Result.failure(requireNotNull(result.exceptionOrNull())))
        }
    }
    fun saveClassOverrides(expected: ScheduleState, items: List<ClassOverride>, callback: (Result<ClassBatchSaveReceipt>) -> Unit) {
        if (!editable) { callback(Result.failure(IllegalStateException(editBlockReason ?: "课表忙碌，请稍后重试"))); return }
        store.putClassOverrides(expected, items) { result ->
            if (result.isSuccess) reload { loaded ->
                if (loaded.isSuccess) localCommitted?.invoke()
                callback(Result.success(ClassBatchSaveReceipt(loaded.isSuccess)))
            } else callback(Result.failure(requireNotNull(result.exceptionOrNull())))
        }
    }
    fun import(preview: ImportPreview, name: String, targetId: String?, callback: (Result<ImportCommit>) -> Unit) {
        if (error) { callback(Result.failure(ScheduleLocalReadFailed())); return }
        if (readOnly) { callback(Result.failure(ScheduleDatasetWriteProtected())); return }
        if (closed || !ready || readOnly || applyingSnapshot) return
        val guarded=if(state.sharedRecords!=null && syncStore!=null) {
            val expectedHash=SyncV3Codec.businessHash(state)
            object:ScheduleStore by store {
                override fun commitImport(source:ImportSource, occurrences:List<ImportedClassOccurrence>, replace:Boolean, callback:(Result<ImportCommit>)->Unit) = syncStore!!.commitImportExpected(source,occurrences,replace,expectedHash,callback)
            }
        } else store
        IcsScheduleImporter().commit(preview, guarded, name, targetId, clock.instant().toEpochMilli()) { result ->
            if (!closed) {
                result.onSuccess { committed -> if (!committed.unchanged) {
                    val sources = state.sources.filterNot { it.id == committed.source.id } + committed.source
                    state = state.copy(sources = sources,
                        imported = state.imported.filterNot { it.sourceId == committed.source.id } + committed.occurrences,
                        profiles = if (state.sync != null) state.profiles else effectiveProfiles(sources))
                    refresh()
                } }
                if (result.isSuccess) localCommitted?.invoke()
                callback(result)
            }
        }
    }
    fun deleteImport(id: String, callback: (Result<Unit>) -> Unit) {
        if (error) { callback(Result.failure(ScheduleLocalReadFailed())); return }
        if (readOnly) { callback(Result.failure(ScheduleDatasetWriteProtected())); return }
        if (closed || !ready || readOnly || applyingSnapshot) return
        val guarded=if(state.sharedRecords!=null && syncStore!=null) {
            val expectedHash=SyncV3Codec.businessHash(state)
            object:ScheduleStore by store {override fun deleteImport(id:String,callback:(Result<Unit>)->Unit)=syncStore!!.deleteImportExpected(id,expectedHash,callback)}
        } else store
        guarded.deleteImport(id) { result -> if (!closed) {
            result.onSuccess {
                val sources = state.sources.filterNot { it.id == id }
                state = state.copy(sources = sources, imported = state.imported.filterNot { it.sourceId == id },
                    classOverrides = state.classOverrides.filterNot { it.sourceId == id }, profiles = if (state.sync != null) state.profiles else effectiveProfiles(sources))
                refresh()
            }
            if (result.isSuccess) localCommitted?.invoke()
            callback(result)
        } }
    }
    fun close() { if (!closed) { closed = true; listeners.clear(); store.close() } }
}

enum class ScheduleMode { CALENDAR, TIMETABLE }
class ScheduleSession(clock: ScheduleClock, private val modeStore: ScheduleModeStore? = null) {
    var mode = modeStore?.read() ?: ScheduleMode.CALENDAR
        set(value) {
            if (field == value) return
            field = value
            modeStore?.write(value)
        }
    var selectedDate: LocalDate = clock.today()
    var month: YearMonth = YearMonth.from(selectedDate)
    var weekStart: LocalDate = selectedDate.minusDays((selectedDate.dayOfWeek.value - 1).toLong())
    fun today(clock: ScheduleClock) { selectedDate = clock.today(); month = YearMonth.from(selectedDate); weekStart = selectedDate.minusDays((selectedDate.dayOfWeek.value - 1).toLong()) }
    fun moveMonth(amount: Long) { month = month.plusMonths(amount) }
    fun select(date: LocalDate) { selectedDate = date; month = YearMonth.from(date) }
    fun openTimetableDate(date: LocalDate) {
        select(date)
        weekStart = date.minusDays((date.dayOfWeek.value - 1).toLong())
        mode = ScheduleMode.TIMETABLE
    }
    // Rollover retains explicit selection; Today is the deterministic way to follow the new day.
}
/** date is display-local. academicDate resolves profile phases; anchorDate is the stable pre-move academic identity. */
data class ClassOccurrence(val entry: TimetableEntry, val date: LocalDate, val start: Instant, val end: Instant,
    val imported: ImportedClassOccurrence? = null, val anchorDate: LocalDate = date,
    val seriesId: String = imported?.seriesId ?: entry.id, val sourceId: String? = imported?.sourceId,
    val periodLabel: String? = imported?.periodLabel, val teacher: String = "", val note: String = "",
    val allWeeksEdited: Boolean = false, val thisWeekEdited: Boolean = false,
    val copiedFrom: LocalDate? = null, val linked: Boolean = false, val academicDate: LocalDate = date, val colorSeriesId: String = seriesId, val sharedRgb: Int? = null)
enum class ClassStatus { CURRENT, NEXT_TODAY, NO_MORE_TODAY }
data class TimetableCardContent(val status: ClassStatus, val occurrences: List<ClassOccurrence>)
object ScheduleProjection {
    fun weekStart(date: LocalDate, locale: Locale): LocalDate = date.minusDays((date.dayOfWeek.value - weekDays(locale).first().value + 7L) % 7)
    private fun importedClass(state: ScheduleState, item: ImportedClassOccurrence, zone: ZoneId): ClassOccurrence {
        val start = Instant.ofEpochMilli(item.start).atZone(zone)
        val end = Instant.ofEpochMilli(item.end).atZone(zone)
        // CSV source timezone preserves its academic date even if the active profile timezone changes.
        val academic = if (state.sources.firstOrNull { it.id == item.sourceId }?.resolvedTermKey() != null)
            start.toInstant().atZone(ZoneId.of(item.timezone)).toLocalDate() else start.toLocalDate()
        return ClassOccurrence(TimetableEntry(item.id, item.title, start.dayOfWeek.value,
            start.hour * 60 + start.minute, end.hour * 60 + end.minute, item.location), start.toLocalDate(),
            start.toInstant(), end.toInstant(), item, anchorDate = academic, academicDate = academic)
    }
    private fun monday(date: LocalDate) = date.minusDays((date.dayOfWeek.value - 1).toLong())
    fun profile(state: ScheduleState, occurrence: ClassOccurrence): TermScheduleProfile? =
        state.sources.firstOrNull { it.id == occurrence.sourceId }?.resolvedTermKey()?.let { state.profiles[it] }
    fun periods(label: String?): Pair<Int, Int>? {
        val numbers = Regex("\\d+").findAll(label.orEmpty()).mapNotNull { it.value.toIntOrNull() }.toList()
        return if (numbers.isNotEmpty() && numbers.size <= 30 && numbers.first() in 1..30 && numbers.last() in 1..30)
            numbers.first() to numbers.last() else null
    }
    fun linkedPeriodTimes(profile: TermScheduleProfile, date: LocalDate, first: Int, last: Int): Pair<Instant, Instant>? {
        val phase = profile.phaseOn(date) ?: return null
        val start = phase.period(first)?.start ?: return null
        val end = phase.period(last)?.end ?: return null
        val zone = ZoneId.of(profile.timezone)
        val from = date.atTime(start).atZone(zone).toInstant()
        val to = date.atTime(end).atZone(zone).toInstant()
        return if (to > from) from to to else null
    }
    private fun patch(state: ScheduleState, value: ClassOccurrence, edit: ClassOverride, zone: ZoneId): ClassOccurrence? {
        val p = edit.patch()
        val targetDate = p.date?.let { LocalDate.parse(it) } ?: p.weekday?.let { monday(value.academicDate).plusDays((it - 1).toLong()) } ?: value.academicDate
        if (p.date != null && monday(targetDate) != monday(value.anchorDate)) return null
        val original = periods(value.periodLabel)
        val first = p.periodStart ?: original?.first
        val last = p.periodEnd ?: original?.second
        val linked = p.timingMode == "linked" || p.timingMode == null && value.linked
        val profile = profile(state, value)
        if (p.timingMode == "linked" && (profile == null || first == null || last == null)) return null
        val linkedTimes = if (linked && profile != null && first != null && last != null)
            linkedPeriodTimes(profile, targetDate, first, last) ?: return null else null
        val bounds = if (linkedTimes != null)
            linkedTimes.first.atZone(zone).toLocalTime().toSecondOfDay() / 60 to
                (linkedTimes.second.atZone(zone).toLocalTime().toSecondOfDay() / 60)
        else (p.startMinute ?: value.entry.startMinute) to (p.endMinute ?: value.entry.endMinute)
        val overnight = value.end.atZone(zone).toLocalDate() > value.date &&
            p.startMinute == null && p.endMinute == null && p.periodStart == null && p.periodEnd == null && p.timingMode == null
        if (linkedTimes == null && bounds.second <= bounds.first && !overnight) return null
        val displayTarget = value.date.plusDays(targetDate.toEpochDay() - value.academicDate.toEpochDay())
        val start = linkedTimes?.first ?: displayTarget.atTime(LocalTime.ofSecondOfDay(bounds.first * 60L)).atZone(zone).toInstant()
        val end = linkedTimes?.second ?: displayTarget.plusDays(if (overnight) 1 else 0)
            .atTime(LocalTime.ofSecondOfDay(bounds.second * 60L)).atZone(zone).toInstant()
        val displayDate = start.atZone(zone).toLocalDate()
        return value.copy(entry = value.entry.copy(title = p.title ?: value.entry.title, day = displayDate.dayOfWeek.value,
            startMinute = bounds.first, endMinute = bounds.second, location = p.location ?: value.entry.location),
            date = displayDate, academicDate = targetDate, start = start, end = end,
            periodLabel = if (p.periodStart != null || p.periodEnd != null)
                "[${(first!!..last!!).joinToString("-") { "%02d".format(it) }}节]" else value.periodLabel,
            teacher = p.teacher ?: value.teacher, note = p.note ?: value.note,
            allWeeksEdited = value.allWeeksEdited || edit.anchorDate == null,
            thisWeekEdited = value.thisWeekEdited || edit.anchorDate != null, linked = linked)
    }
    private fun onAcademicDate(value: ClassOccurrence, date: LocalDate, zone: ZoneId, anchor: LocalDate): ClassOccurrence {
        if (date == value.academicDate) return value.copy(anchorDate = anchor)
        val displayDate = value.date.plusDays(date.toEpochDay() - value.academicDate.toEpochDay())
        val start = displayDate.atTime(LocalTime.ofSecondOfDay(value.entry.startMinute * 60L)).atZone(zone).toInstant()
        val endDate = displayDate.plusDays(if (value.end.atZone(zone).toLocalDate() > value.date) 1 else 0)
        val end = endDate.atTime(LocalTime.ofSecondOfDay(value.entry.endMinute * 60L)).atZone(zone).toInstant()
        return value.copy(date = displayDate, academicDate = date, anchorDate = anchor, start = start, end = end,
            entry = value.entry.copy(day = displayDate.dayOfWeek.value))
    }
    /** Base → all-weeks → target day composition → this-week. Source-day adjustments are never copied. */
    internal fun academicWeek(state: ScheduleState, first: LocalDate, zone: ZoneId): List<ClassOccurrence> {
        val monday = monday(first)
        val base = (0L..6L).flatMap { offset ->
            val date = monday.plusDays(offset)
            state.entries.filter { it.day == date.dayOfWeek.value }.map { entry ->
                val start = date.atTime(LocalTime.ofSecondOfDay(entry.startMinute * 60L)).atZone(zone).toInstant()
                val end = date.atTime(LocalTime.ofSecondOfDay(entry.endMinute * 60L)).atZone(zone).toInstant()
                ClassOccurrence(entry, date, start, end)
            }
        } + state.importedIndex.range(monday.minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli(),
            monday.plusDays(9).atStartOfDay(zone).toInstant().toEpochMilli()).map { importedClass(state, it, zone) }
            .filter { it.academicDate >= monday && it.academicDate < monday.plusDays(7) }
        val templates = SharedCourseSchedule.project(state, monday, zone, base).mapNotNull { raw ->
            val value = raw.copy(linked = profile(state, raw) != null && periods(raw.periodLabel) != null)
            val edit = state.editsByKey[ClassOverride.key(value.sourceId, value.seriesId, null)]
            val withEdit = if (edit == null) value else patch(state, value, edit, zone) ?: value
            withEdit
        }
        val composed = (0L..6L).flatMap { offset ->
            val target = monday.plusDays(offset)
            val day = state.daysByDate[target.toString()]
            when (day?.mode) {
                "none" -> emptyList()
                "weekday" -> {
                    val sourceDate = monday.plusDays((day.weekday!! - 1).toLong())
                    templates.filter { it.academicDate == sourceDate }.mapNotNull { template ->
                        var copied = onAcademicDate(template, target, zone, target).copy(copiedFrom = sourceDate)
                        val range = periods(copied.periodLabel)
                        val term = profile(state, copied)
                        if (copied.linked && range != null && term != null) {
                            copied = patch(state, copied, ClassOverride.of(copied.sourceId, copied.seriesId, null,
                                ClassPatch(periodStart = range.first, periodEnd = range.second, timingMode = "linked")), zone)
                                ?.copy(allWeeksEdited = template.allWeeksEdited, thisWeekEdited = template.thisWeekEdited)
                                ?: return@mapNotNull null
                        }
                        copied
                    }
                }
                else -> templates.filter { it.academicDate == target }
            }
        }
        return composed.mapNotNull { value ->
            val edit = state.editsByKey[ClassOverride.key(value.sourceId, value.seriesId, value.anchorDate.toString())]
            val edited = if (edit == null) value else patch(state, value, edit, zone) ?: value
            edited.takeUnless { state.daysByDate[it.date.toString()]?.mode == "none" }?.let { final ->
                if (state.sharedRecords == null) final else final.copy(sharedRgb = SharedCourseColors.rgb(state,final.sourceId,final.colorSeriesId))
            }
        }.sortedWith(compareBy<ClassOccurrence> { it.start }.thenBy { it.entry.title }.thenBy { it.entry.id })
    }
    fun week(state: ScheduleState, first: LocalDate, zone: ZoneId): List<ClassOccurrence> {
        val start = first.atStartOfDay(zone).toInstant()
        val end = first.plusDays(7).atStartOfDay(zone).toInstant()
        // An adjacent academic week can resolve into this display week, including across Sunday/Monday.
        return (-1L..1L).flatMap { academicWeek(state, first.plusWeeks(it), zone) }
            .filter { it.start < end && it.end > start }.sortedBy { it.start }
    }
    fun weekDays(locale: Locale): List<DayOfWeek> {
        val first = WeekFields.of(locale).firstDayOfWeek
        return (0L..6L).map { first.plus(it) }
    }
    fun monthCells(month: YearMonth, locale: Locale): List<LocalDate> {
        val first = month.atDay(1)
        val offset = (first.dayOfWeek.value - weekDays(locale).first().value + 7) % 7
        return (0L..41L).map { first.minusDays(offset.toLong()).plusDays(it) }
    }
    fun eventsOn(state: ScheduleState, date: LocalDate, zone: ZoneId): List<CalendarEvent> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return state.events.filter { it.start < end && it.end > start }
    }
    fun upcomingEvents(state: ScheduleState, clock: ScheduleClock, limit: Int): List<CalendarEvent> =
        state.events.filter { it.end > clock.instant().toEpochMilli() }.take(limit)
    fun occurrences(state: ScheduleState, clock: ScheduleClock): List<ClassOccurrence> {
        val today = clock.today()
        val futureWeeks = state.importedIndex.upcoming(clock.instant().toEpochMilli(), 16).map {
            monday(importedClass(state, it, clock.zone()).academicDate)
        }
        val scheduleWeeks=java.util.TreeSet<LocalDate>()
        SharedCourseSchedule.slots(state).filter { it.enabled }.forEach { slot ->
            val anchor=LocalDate.parse(slot.week1)
            slot.weeks.asSequence().map { anchor.plusWeeks(it-1L) }.filter { it >= monday(today) }.take(16).forEach { date ->
                scheduleWeeks.add(date);if(scheduleWeeks.size>16) scheduleWeeks.pollLast()
            }
        }
        val weeks = ((-1L..2L).map { monday(today).plusWeeks(it) } + futureWeeks + scheduleWeeks).distinct()
        return weeks.flatMap { academicWeek(state, it, clock.zone()) }
            .filter { it.end > clock.instant() && it.end > it.start }
            .sortedWith(compareBy<ClassOccurrence> { it.start }.thenBy { it.entry.title }.thenBy { it.entry.id })
    }
    fun cardLimit(columns: Int, rows: Int) = if (rows == 1) 1 else if (columns >= 3) 4 else 2
    fun timetableCard(state: ScheduleState, clock: ScheduleClock, columns: Int, rows: Int): TimetableCardContent {
        val all = occurrences(state, clock)
        val today = all.filter { it.date == clock.today() || (it.start <= clock.instant() && it.end > clock.instant()) }
        val status = when {
            today.any { it.start <= clock.instant() } -> ClassStatus.CURRENT
            today.isNotEmpty() -> ClassStatus.NEXT_TODAY
            else -> ClassStatus.NO_MORE_TODAY
        }
        return TimetableCardContent(status, (if (rows > 1 && today.isEmpty()) all else today).take(cardLimit(columns, rows)))
    }
    /** A single visible-view wakeup at the next actual presentation boundary, including DST midnight. */
    fun nextBoundary(state: ScheduleState, clock: ScheduleClock): Instant {
        val now = clock.instant()
        return (listOf(clock.today().plusDays(1).atStartOfDay(clock.zone()).toInstant()) +
            state.events.flatMap { listOf(Instant.ofEpochMilli(it.start), Instant.ofEpochMilli(it.end)) } +
            occurrences(state, clock).flatMap { listOf(it.start, it.end) }).filter { it > now }.minOrNull()!!
    }
}
