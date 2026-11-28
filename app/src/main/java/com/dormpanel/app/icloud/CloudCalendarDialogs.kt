package com.dormpanel.app.icloud

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.appearance.applyAppearanceTree
import com.dormpanel.app.appearance.matchActivityBrightness
import com.dormpanel.app.schedule.dp
import com.dormpanel.app.schedule.scheduleDate
import com.dormpanel.app.schedule.scheduleTime
import com.dormpanel.app.schedule.scheduleButton
import com.dormpanel.app.schedule.scheduleColumn
import com.dormpanel.app.schedule.scheduleLabel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

internal class CloudCalendarDialogs(private val context: Context, private val appearance: AppearanceController) {
    private val open = linkedSetOf<Dialog>()
    fun close() = open.toList().forEach { it.dismiss() }

    fun settings(service: CloudCalendarService, range: CloudRange) {
        val content = column()
        val status = context.scheduleLabel("", 18f)
        content.addView(status)
        val refresh = context.scheduleButton("刷新日历", service::refresh)
        val selection = context.scheduleButton("显示的日历") {
            choose(service.calendars, service.selected) { service.setSelection(it.orEmpty()) }
        }
        val cadence = context.scheduleButton("") {
            val current = refreshChoices.indexOf(service.refreshMinutes)
            show(AlertDialog.Builder(context).setTitle("自动刷新周期")
                .setSingleChoiceItems(refreshChoices.map { "$it 分钟" }.toTypedArray(), current) { dialog, index ->
                    service.setRefresh(refreshChoices[index])
                    dialog.dismiss()
                }.setNegativeButton("取消", null))
        }
        val login = context.scheduleButton("") { signIn(service) }
        val clear = context.scheduleButton("清除本机 iCloud 缓存") {
            confirm("清除本机 iCloud 缓存？", "连接和显示选择会保留，联网后重新读取日历。", service::clearCache)
        }
        val disconnect = context.scheduleButton("断开并清除凭据与缓存") {
            val pending = service.hasPendingWrites
            val warning = if (pending) "\n\n原账户的待写记录会保留在本机，断开后只能查看或放弃，无法继续提交。" else ""
            confirm("断开 iCloud？", "将清除此设备上的 iCloud 凭据、选择及事件缓存。$warning") { service.disconnect(pending) }
        }
        val pending = context.scheduleButton("日程写入记录") { writes(service) }
        listOf(refresh, selection, cadence, login, clear, disconnect, pending).forEach { content.addView(it) }
        val update: () -> Unit = {
            status.text = service.connectionLabel(range)
            cadence.text = "自动刷新 · 每 ${service.refreshMinutes} 分钟"
            login.text = if (service.connected) "重新验证或更换账号" else "连接 iCloud"
            login.isEnabled = !service.isBusy
            listOf(refresh, selection, clear, disconnect).forEach {
                it.visibility = if (service.connected) View.VISIBLE else View.GONE
                it.isEnabled = !service.isBusy
            }
            cadence.isEnabled = !service.isBusy
            pending.visibility = if (service.writes.isNotEmpty()) View.VISIBLE else View.GONE
        }
        update()
        val dialog = show(AlertDialog.Builder(context).setTitle("iCloud 日历").setView(scroll(content))
            .setNegativeButton("关闭", null), onDismiss = { service.removeListener(update) })
        if (dialog.isShowing) service.addListener(update)
    }

    fun choose(
        calendars: List<CloudCalendar>, selected: Set<String>?, card: Boolean = false,
        inherited: Set<String> = emptySet(), save: (Set<String>?) -> Unit,
    ) {
        val content = column()
        val radio = if (card) RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            addView(choice("继承全局日历选择"))
            addView(choice("为此卡片选择日历"))
            check(getChildAt(if (selected == null) 0 else 1).id)
            content.addView(this)
        } else null
        val initial = selected ?: inherited
        if (initial.any { id -> calendars.none { it.id == id } }) {
            content.addView(context.scheduleLabel("部分已选日历无法确认，请核对后重新选择。", 16f))
        }
        val choices = calendars.map { calendar ->
            calendar.id to CheckBox(context).apply {
                text = "${calendar.name} · ${calendar.access.label()}"
                minHeight = context.dp(48)
                isChecked = calendar.id in initial
                calendar.color?.let { color -> background = stripe(color, context.dp(3)) }
                content.addView(this)
            }
        }
        if (choices.isEmpty()) content.addView(context.scheduleLabel("没有已发现的 iCloud 日历", 16f))
        fun inherits() = radio != null && radio.checkedRadioButtonId == radio.getChildAt(0).id
        fun enabled() = choices.forEach { (_, check) -> check.isEnabled = !inherits() }
        radio?.setOnCheckedChangeListener { _, _ -> enabled() }
        enabled()
        show(AlertDialog.Builder(context).setTitle(if (card) "卡片显示的日历" else "显示的 iCloud 日历")
            .setView(scroll(content)).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                val ids = choices.filter { it.second.isChecked }.mapTo(linkedSetOf()) { it.first }
                save(if (inherits()) null else ids)
            })
    }

    fun create(service: CloudCalendarService, date: LocalDate, zone: ZoneId, localAllowed: Boolean, local: () -> Unit) {
        val targets = service.createCalendars
        val account = service.accountId
        val names = buildList {
            if (localAllowed) add("本机日历")
            targets.forEach { add("iCloud · ${it.name}") }
        }
        if (names.isEmpty()) return
        if (targets.isEmpty()) { local(); return }
        show(AlertDialog.Builder(context).setTitle("选择保存日历")
            .setItems(names.toTypedArray()) { _, index ->
                if (localAllowed && index == 0) local() else {
                    val calendar = targets[index - if (localAllowed) 1 else 0]
                    val initial = CloudEventDraft("", "", "", date.atTime(9, 0), date.atTime(10, 0), false, zone)
                    val intent = java.util.UUID.randomUUID()
                    editor("新建 iCloud 日程", calendar.name, initial) { value, saved -> service.saveCreate(intent, account, calendar, value, saved) }
                }
            }.setNegativeButton("取消", null))
    }

    fun details(service: CloudCalendarService, event: CloudCalendarEvent, time: String, zone: ZoneId) {
        val content = column()
        val calendar = event.calendar
        val remote = event.event
        content.addView(context.scheduleLabel(time, 18f))
        content.addView(context.scheduleLabel("iCloud · ${calendar.name} · ${calendar.access.label()}", 18f).apply {
            calendar.color?.let { background = stripe(it, context.dp(3)) }
        })
        if (remote.location.isNotBlank()) content.addView(context.scheduleLabel(remote.location, 18f))
        if (remote.description.isNotBlank()) content.addView(context.scheduleLabel(remote.description, 18f))
        val status = context.scheduleLabel("", 16f)
        val eligibility = context.scheduleLabel("", 16f)
        content.addView(status)
        content.addView(eligibility)
        val edit = context.scheduleButton("编辑 iCloud 日程") {}
        val delete = context.scheduleButton("删除此 iCloud 日程") {}
        val records = context.scheduleButton("查看写入记录") { writes(service, event.ref) }
        val reload = context.scheduleButton("重新读取服务器版本") {}
        listOf(edit, delete, records, reload).forEach { content.addView(it); it.visibility = View.GONE }
        var base: CloudEditBase? = null
        var available = false
        var reason = if (event.ref == null) "此缓存缺少可验证的写回身份，请刷新日历" else "正在核对服务器日程和权限"
        val update: () -> Unit = {
            val pending = service.writeFor(event.ref)
            status.text = pending?.label(service.isOffline) ?: "iCloud 服务器日程"
            val blocked = pending != null && pending.state != CalendarWriteState.CONFIRMED
            val sameAccount = event.ref?.account == service.accountId
            eligibility.text = when {
                !sameAccount && event.ref != null -> "原账户已断开或切换"
                blocked -> "此日程有待处理的写入"
                service.isOffline && base == null -> "网络不可用，联网后可核对权限"
                base != null && (base?.canEdit == true || base?.canDelete == true) ->
                    "权限 · ${if (base?.canEdit == true) "可编辑" else "不可编辑"} · ${if (base?.canDelete == true) "可删除" else "不可删除"}"
                else -> reason
            }
            edit.visibility = if (sameAccount && !blocked && base?.canEdit == true && !service.isBusy) View.VISIBLE else View.GONE
            delete.visibility = if (sameAccount && !blocked && base?.canDelete == true && !service.isBusy) View.VISIBLE else View.GONE
            records.visibility = if (pending != null) View.VISIBLE else View.GONE
            reload.visibility = if (event.ref != null && sameAccount && !blocked && !available && !service.isOffline) View.VISIBLE else View.GONE
        }
        val dialog = show(AlertDialog.Builder(context).setTitle(remote.title.ifBlank { "无标题日程" }).setView(scroll(content))
            .setNegativeButton("关闭", null), onDismiss = { service.removeListener(update) })
        fun inspect(allowNewer: Boolean) {
            val ref = event.ref ?: return
            available = true
            reason = "正在核对服务器日程和权限"
            update()
            service.inspect(ref, zone, allowNewer) checked@ { checked, error ->
                if (!dialog.isShowing) return@checked
                base = checked
                available = checked != null || ref.recurrenceId != null
                reason = error ?: "服务器未授予编辑或删除权限，或权限未知"
                if (allowNewer && checked?.isNewer == true) {
                    dialog.dismiss()
                    reviewedVersion(service, checked, null)
                } else update()
            }
        }
        edit.setOnClickListener { base?.let { checked -> dialog.dismiss(); edit(service, checked) } }
        delete.setOnClickListener { base?.let { checked -> confirmDelete(service, checked) { dialog.dismiss() } } }
        reload.setOnClickListener { inspect(true) }
        service.addListener(update)
        update()
        if (event.ref != null && service.writeFor(event.ref)?.state.let { it == null || it == CalendarWriteState.CONFIRMED })
            inspect(false)
    }

    fun writes(service: CloudCalendarService, target: CloudObjectRef? = null) {
        val content = column()
        val update: () -> Unit = {
            content.removeAllViews()
            val entries = service.writes.filter { target == null || it.sameTarget(target) }
            if (entries.isEmpty()) content.addView(context.scheduleLabel("没有日程写入记录", 18f))
            entries.forEach { entry ->
                val detached = entry.ref.account != service.accountId
                val name = entry.draft.title.ifBlank { "无标题日程" }
                val kind = when (entry.kind) {
                    CalendarWriteKind.CREATE -> "新建"
                    CalendarWriteKind.UPDATE -> "修改"
                    CalendarWriteKind.DELETE -> "删除"
                }
                content.addView(context.scheduleButton(
                    "$kind · ${entry.calendarName} · $name\n${entry.label(service.isOffline)}${if (detached) " · 原账户未连接" else ""}"
                ) { writeDetails(service, entry.id) }.apply { maxLines = 3 })
            }
            applyAppearanceTree(content, appearance.state)
        }
        val dialog = show(AlertDialog.Builder(context).setTitle("iCloud 日程写入记录").setView(scroll(content))
            .setNegativeButton("关闭", null), onDismiss = { service.removeListener(update) })
        if (dialog.isShowing) service.addListener(update)
        update()
    }

    private fun writeDetails(service: CloudCalendarService, id: String) {
        val content = column()
        val state = context.scheduleLabel("", 18f)
        val error = context.scheduleLabel("", 16f)
        val summary = context.scheduleLabel("", 18f).apply { setTextIsSelectable(true) }
        val retry = context.scheduleButton("重试或核对写入") { service.retryWrite(id) }
        val reload = context.scheduleButton("重新读取服务器版本") {}
        val discard = context.scheduleButton("放弃本机待写修改") {}
        listOf(state, summary, retry, reload, discard, error).forEach(content::addView)
        val update: () -> Unit = {
            val entry = service.writes.firstOrNull { it.id == id }
            val detached = entry?.ref?.account != service.accountId
            val active = entry != null && entry.state !in setOf(CalendarWriteState.CONFIRMED, CalendarWriteState.WRITING)
            state.text = (entry?.label(service.isOffline) ?: "本机待写记录已移除") + if (entry != null && detached) " · 原账户未连接" else ""
            summary.text = entry?.let { "${it.calendarName}\n${describe(it.draft)}" }.orEmpty()
            retry.visibility = if (active && !detached && entry?.state != CalendarWriteState.CONFLICT) View.VISIBLE else View.GONE
            retry.isEnabled = (!service.isOffline || entry?.cachePending == true) && !service.isBusy &&
                (entry?.retryAt ?: 0) <= System.currentTimeMillis()
            reload.visibility = if (active && !detached && entry?.kind != CalendarWriteKind.CREATE &&
                entry?.state == CalendarWriteState.CONFLICT) View.VISIBLE else View.GONE
            reload.isEnabled = !service.isOffline && !service.isBusy
            discard.visibility = if (active && entry?.cachePending != true) View.VISIBLE else View.GONE
        }
        val dialog = show(AlertDialog.Builder(context).setTitle("本机保存的 iCloud 日程").setView(scroll(content))
            .setNegativeButton("关闭", null), onDismiss = { service.removeListener(update) })
        retry.setOnClickListener { error.text = ""; service.retryWrite(id) }
        reload.setOnClickListener {
            val entry = service.writes.firstOrNull { it.id == id } ?: return@setOnClickListener
            error.text = "正在读取服务器版本"
            service.inspect(entry.ref, entry.draft.zone, allowNewer = true) { base, message ->
                if (dialog.isShowing) {
                    error.text = message.orEmpty()
                    if (base != null) reviewedVersion(service, base, entry)
                }
            }
        }
        discard.setOnClickListener {
            confirm("放弃本机待写修改？", "这会移除本机保存的待写记录。已在 iCloud 生效的操作仍然有效。") {
                service.discardWrite(id) { message -> if (message == null) dialog.dismiss() else error.text = message }
            }
        }
        service.addListener(update)
        update()
    }

    private fun reviewedVersion(service: CloudCalendarService, base: CloudEditBase, previous: CalendarWrite?) {
        val content = column()
        content.addView(context.scheduleLabel("iCloud · ${base.calendarName}\n${describe(base.event.draft)}", 18f))
        if (previous != null) content.addView(context.scheduleLabel("本机草稿\n${describe(previous.draft)}", 18f))
        val edit = base.canEdit && previous?.kind != CalendarWriteKind.DELETE
        val delete = base.canDelete && previous?.kind != CalendarWriteKind.UPDATE
        if (!edit && !delete) content.addView(context.scheduleLabel("服务器未授予此操作权限", 16f))
        show(AlertDialog.Builder(context).setTitle("服务器当前版本").setView(scroll(content)).setNegativeButton("关闭", null)
            .apply {
                if (edit) setPositiveButton(if (previous == null) "编辑此版本" else "用本机草稿重新编辑") { _, _ ->
                    edit(service, base, previous)
                }
                if (delete) setNeutralButton("删除此版本") { _, _ -> confirmDelete(service, base, previous?.id) }
            })
    }

    private fun edit(service: CloudCalendarService, base: CloudEditBase, previous: CalendarWrite? = null) {
        editor("编辑 iCloud 日程", base.calendarName, previous?.draft ?: base.event.draft) { draft, saved ->
            service.saveEdit(base, draft, replacing = previous?.id, done = saved)
        }
    }

    private fun confirmDelete(service: CloudCalendarService, base: CloudEditBase, replacing: String? = null, saved: () -> Unit = {}) {
        val dialog = show(AlertDialog.Builder(context).setTitle("删除此 iCloud 日程？")
            .setMessage("${base.calendarName} · ${base.event.draft.title.ifBlank { "无标题日程" }}\n将删除这一条日程。")
            .setNegativeButton("取消", null).setPositiveButton("删除此日程", null))
        val action = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        action.setOnClickListener {
            action.isEnabled = false
            service.saveEdit(base, base.event.draft, delete = true, replacing = replacing) { error ->
                if (error == null) { dialog.dismiss(); saved() }
                else if (dialog.isShowing) { dialog.setMessage(error); action.isEnabled = true }
            }
        }
    }

    private fun describe(draft: CloudEventDraft): String = buildString {
        append(draft.title.ifBlank { "无标题日程" })
        if (draft.allDay) append("\n全天 · ${draft.start.toLocalDate()} 至 ${draft.end.toLocalDate()}（结束日不包含）")
        else append("\n${draft.start} 至 ${draft.end} · ${draft.zone.id}")
        if (draft.location.isNotEmpty()) append("\n${draft.location}")
        if (draft.description.isNotEmpty()) append("\n${draft.description}")
    }

    private fun editor(title: String, calendar: String, initial: CloudEventDraft,
        save: (CloudEventDraft, (String?) -> Unit) -> Unit,
    ) {
        val fields = column().apply { isFocusableInTouchMode = true }
        var start = initial.start
        var end = initial.end
        val target = context.scheduleLabel("保存到 iCloud · $calendar", 18f)
        fields.addView(target)
        fun input(label: String, value: String, multiline: Boolean = false) = EditText(context).apply {
            hint = label
            contentDescription = label
            inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            minHeight = context.dp(48)
            maxLines = if (multiline) 3 else 1
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setText(value)
            fields.addView(this)
        }
        val summary = input("日程标题", initial.title)
        val allDay = CheckBox(context).apply {
            text = "全天"
            minHeight = context.dp(48)
            isChecked = initial.allDay
            fields.addView(this)
        }
        val zone = context.scheduleLabel("", 16f)
        fields.addView(zone)
        fun dateTimeRow(label: () -> String, get: () -> LocalDateTime, set: (LocalDateTime) -> Unit): () -> Unit {
            val row = LinearLayout(context)
            val date = context.scheduleButton("") {}
            val time = context.scheduleButton("") {}
            val update: () -> Unit = {
                date.text = "${label()} · ${scheduleDate(get().toLocalDate())}"
                time.text = context.scheduleTime(get().hour * 60 + get().minute)
                time.contentDescription = "${label()}时间 · ${time.text}"
                time.visibility = if (allDay.isChecked) View.GONE else View.VISIBLE
            }
            date.setOnClickListener {
                val value = get()
                track(DatePickerDialog(context, { _, year, month, day ->
                    set(LocalDate.of(year, month + 1, day).atTime(get().toLocalTime()))
                    update()
                }, value.year, value.monthValue - 1, value.dayOfMonth))
            }
            time.setOnClickListener {
                val value = get()
                track(TimePickerDialog(context, { _, hour, minute ->
                    set(get().withHour(hour).withMinute(minute))
                    update()
                }, value.hour, value.minute, android.text.format.DateFormat.is24HourFormat(context)))
            }
            row.addView(date, LinearLayout.LayoutParams(0, context.dp(52), 2f))
            row.addView(time, LinearLayout.LayoutParams(0, context.dp(52), 1f))
            fields.addView(row)
            return update
        }
        val updateStart = dateTimeRow({ "开始" }, { start }, { start = it })
        val updateEnd = dateTimeRow({ if (allDay.isChecked) "结束日（不包含）" else "结束" }, { end }, { end = it })
        fun updateTimes() {
            zone.text = if (allDay.isChecked) "全天日程按日期保存" else "编辑时区 · ${initial.zone.id}"
            updateStart()
            updateEnd()
        }
        updateTimes()
        var timed = if (initial.allDay) null else start to end
        var dates = if (initial.allDay) start to end else null
        allDay.setOnCheckedChangeListener { _, checked ->
            val current = if (checked) {
                timed = start to end
                val day = start.toLocalDate()
                val exclusive = if (end.toLocalTime() == java.time.LocalTime.MIDNIGHT) end.toLocalDate() else end.toLocalDate().plusDays(1)
                dates ?: (day.atStartOfDay() to maxOf(day.plusDays(1), exclusive).atStartOfDay())
            } else {
                dates = start to end
                val day = start.toLocalDate()
                timed ?: (day.atTime(9, 0) to maxOf(day, end.toLocalDate().minusDays(1)).atTime(10, 0))
            }
            start = current.first
            end = current.second
            updateTimes()
        }
        val location = input("地点（可选）", initial.location)
        val description = input("描述（可选）", initial.description, true)
        val error = context.scheduleLabel("", 16f).apply { visibility = View.GONE }
        fields.addView(error)
        val paintHints: (AppearanceState) -> Unit = { state ->
            val color = PanelPalette.forMode(state.themeMode).secondary
            listOf(summary, location, description).forEach { it.setHintTextColor(color) }
        }
        val dialog = show(AlertDialog.Builder(context).setTitle(title).setView(scroll(fields))
            .setNegativeButton("取消", null).setPositiveButton("保存到本机待写", null),
            onDismiss = { appearance.removeListener(paintHints) })
        appearance.addListener(paintHints)
        dialog.window?.setLayout(minOf(context.dp(780), context.resources.displayMetrics.widthPixels - context.dp(48)),
            ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        fields.requestFocus()
        val action = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        action.setOnClickListener {
            action.isEnabled = false
            val all = allDay.isChecked
            val value = CloudEventDraft(summary.text.toString(), location.text.toString(), description.text.toString(),
                if (all) start.toLocalDate().atStartOfDay() else start,
                if (all) end.toLocalDate().atStartOfDay() else end, all, initial.zone)
            save(value) { message ->
                if (message == null) dialog.dismiss()
                else if (dialog.isShowing) {
                    error.text = message
                    error.visibility = View.VISIBLE
                    action.isEnabled = true
                }
            }
        }
    }

    private fun signIn(service: CloudCalendarService) {
        val content = column()
        fun input(hint: String, type: Int) = EditText(context).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or type
            setSingleLine(true)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
            minHeight = context.dp(48)
            content.addView(this)
        }
        val email = input("Apple Account 邮箱", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = input("专用 App 密码", InputType.TYPE_TEXT_VARIATION_PASSWORD)
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        val error = context.scheduleLabel("", 16f)
        content.addView(error)
        val dialog = show(AlertDialog.Builder(context).setTitle("连接 iCloud 日历").setView(scroll(content))
            .setNegativeButton("取消", null).setPositiveButton("连接", null), secure = true,
            onDismiss = { password.text.clear() })
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val account = email.text.toString().trim()
            val secret = password.text.toString().trim()
            if (account.length !in 3..254 || account.count { it == '@' } != 1 ||
                account.any { it.isWhitespace() || it.isISOControl() || it == ':' } ||
                secret.length !in 1..128 || secret.any { it.isWhitespace() || it.isISOControl() }) {
                error.text = "请输入 Apple Account 邮箱和专用 App 密码"
            } else {
                if (service.hasPendingWrites) {
                    confirm("重新验证或更换账号？", "如果更换账户，当前账户的待写记录会保留在本机供查看或放弃，无法继续提交。") {
                        service.connect(account, secret, keepPending = true)
                        dialog.dismiss()
                    }
                } else {
                    service.connect(account, secret)
                    dialog.dismiss()
                }
            }
        }
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        show(AlertDialog.Builder(context).setTitle(title).setMessage(message).setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ -> action() })
    }

    private fun column() = context.scheduleColumn().apply { setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(8)) }
    private fun scroll(view: View) = ScrollView(context).apply { addView(view) }
    private fun choice(label: String) = RadioButton(context).apply {
        id = View.generateViewId()
        text = label
        minHeight = context.dp(48)
    }

    private fun show(builder: AlertDialog.Builder, secure: Boolean = false, onDismiss: () -> Unit = {}): AlertDialog {
        val dialog = builder.create()
        if (secure) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        track(dialog, onDismiss)
        if (secure) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        listOf(-1, -2, -3).forEach { dialog.getButton(it)?.minHeight = context.dp(48) }
        return dialog
    }

    private fun track(dialog: Dialog, onDismiss: () -> Unit = {}) {
        val paint: (AppearanceState) -> Unit = { state ->
            dialog.window?.decorView?.let {
                it.setBackgroundColor(PanelPalette.forMode(state.themeMode).surface)
                applyAppearanceTree(it, state)
            }
        }
        dialog.setOnDismissListener {
            open.remove(dialog)
            appearance.removeListener(paint)
            onDismiss()
        }
        dialog.show()
        open.add(dialog)
        dialog.matchActivityBrightness()
        appearance.addListener(paint)
        paint(appearance.state)
    }
}

private fun CalendarAccess.label() = when (this) {
    CalendarAccess.READ_ONLY -> "账户只读"
    CalendarAccess.WRITABLE -> "账户可写"
    CalendarAccess.UNKNOWN -> "权限未知"
}

internal fun stripe(color: Int, width: Int) = LayerDrawable(arrayOf(ColorDrawable(color))).apply {
    setLayerWidth(0, width)
    setLayerGravity(0, android.view.Gravity.START or android.view.Gravity.FILL_VERTICAL)
}
