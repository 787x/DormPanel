package com.dormpanel.app.schedule

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*

enum class ProductivityKind(val label: String) {
    TODO("Todo"), MEMO("备忘");

    fun records(content: SharedProductivity.Content): List<ProductivityRecord> =
        if (this == TODO) content.lists else content.memos
}

fun productivityChoices(records: List<ProductivityRecord>): Array<String> {
    val counts = records.groupingBy { it.title }.eachCount()
    return records.map { if (counts[it.title] == 1) it.title else "${it.title} · ${it.id.take(8)}" }.toTypedArray()
}

val ProductivityRecord.title: String get() = when (this) {
    is ProductivityRecord.TodoList -> name
    is ProductivityRecord.Task -> text
    is ProductivityRecord.Memo -> title
}

fun Context.productivityMessage(title: String, text: String) =
    AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("关闭", null).show()

/** One editor for Phone and shared X08E cards. Its selected record is presentation state only. */
class ProductivityPanel(
    private val context: Context,
    private val data: AndroidProductivity,
    private val kind: ProductivityKind,
    private val dataset: String?,
    initialRecord: String?,
    private var initialTask: String? = null,
    private var editMemo: Boolean = false,
    private val fixed: Boolean = false,
    private val local: Boolean = false,
    private val sourceAction: (() -> Unit)? = null,
    private val decorate: (AlertDialog) -> Unit = {},
    private val decorateContent: (View) -> Unit = {},
    private val validate: () -> Unit = {}
) : AutoCloseable {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private val column = column()
    val view = ScrollView(context).apply { isFillViewport = true; addView(column) }
    private var selected = initialRecord
    private var opened = true
    private var saving = false
    private var showCompleted = false
    private var pendingLimit = 24
    private var completedLimit = 24
    private var latest: Result<ProductivityView>? = null
    private var facts: SharedSchedulePresentation? = null
    private var uncertain: List<ProductivityEdit>? = null
    private val dialogs = linkedSetOf<AlertDialog>()
    private var subscription: AutoCloseable? = null
    private var syncObservation: AutoCloseable? = null

    fun start() {
        if (subscription != null) return
        render()
        subscription = data.observe(dataset) {
            latest = it
            refreshFacts()
            render()
        }
        if (!local) syncObservation = data.observeSync { refreshFacts() }
    }

    private fun refreshFacts() {
        if (local) return
        data.syncFacts {
            if (opened) {
                facts = it.getOrNull()?.takeIf { fact -> fact.bindingId == dataset }
                render()
            }
        }
    }

    override fun close() {
        opened = false
        subscription?.close()
        syncObservation?.close()
        dialogs.toList().forEach { it.dismiss() }
    }

    private fun column() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    private fun LinearLayout.label(text: String, size: Float = 16f, bold: Boolean = false) {
        addView(TextView(context).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setTextIsSelectable(true)
            setPadding(0, dp(5), 0, dp(5))
        })
    }

    private fun LinearLayout.action(text: String, enabled: Boolean = true, block: () -> Unit) {
        addView(Button(context).apply {
            this.text = text
            isAllCaps = false
            minHeight = dp(48)
            isEnabled = enabled
            setOnClickListener { if (opened && !saving) block() }
        }, if (orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -2, 1f)
        else LinearLayout.LayoutParams(-1, -2))
    }

    private fun LinearLayout.actions(): LinearLayout = LinearLayout(context).also { row ->
        val horizontal = context.resources.configuration.fontScale < 1.35f &&
            context.resources.configuration.screenWidthDp >= 350
        row.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun LinearLayout.section(): LinearLayout = column().also { card ->
        val color = android.util.TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.colorControlHighlight, it, true)
        }.data
        card.background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
        }
        addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun render() {
        repaint()
        if (opened) decorateContent(column)
    }

    private fun repaint() {
        if (!opened) return
        column.removeAllViews()
        sourceAction?.let { column.action("数据来源", block = it) }
        val current = latest?.getOrNull()
        if (current == null) {
            column.label(latest?.exceptionOrNull()?.message?.let { "读取失败 · $it" } ?: "正在读取…")
            column.action("重新读取") { data.load(dataset) { latest = it; render() } }
            return
        }
        val expected = current.version
        column.label(current.status(local, facts))
        if (!local && facts?.recovery == true) column.action("重开活动 Store") {
            confirm("重开活动 Store", "重新读取本机事务及恢复记录。") {
                data.recover { outcome ->
                    if (opened && outcome.isFailure) track(context.productivityMessage("恢复失败", outcome.exceptionOrNull()?.message.orEmpty()))
                    if (opened) data.load(dataset) { outcome ->
                        latest = outcome
                        render()
                        val operations = uncertain
                        val content = outcome.getOrNull()?.content
                        if (operations != null && content != null) {
                            uncertain = null
                            val found = operations.all { it.matches(content) }
                            track(context.productivityMessage("已重新读取", if (found) "本机记录已包含这次修改。"
                                else "当前本机内容无法确认这次修改，请核对后再编辑。"))
                        }
                    }
                }
            }
        }
        current.conflict?.let { conflict ->
            if (expected != null) column.action("查看和处理共享冲突") { conflictDialog(expected, conflict) }
        }
        val writable = expected != null && current.writeBlock == null && !saving
        val records = kind.records(current.content)
        val record = records.firstOrNull { it.id == selected } ?: if (selected == null && !fixed) records.firstOrNull() else null
        if (!fixed) {
            column.action(record?.let { "选择${kind.label} · ${it.title}" } ?: "选择${kind.label}", records.isNotEmpty()) {
                track(AlertDialog.Builder(context).setTitle("选择${kind.label}")
                    .setItems(productivityChoices(records)) { _, i ->
                        selected = records[i].id
                        showCompleted = false
                        pendingLimit = 24
                        completedLimit = 24
                        render()
                    }.setNegativeButton("取消", null).show())
            }
            column.action(if (kind == ProductivityKind.TODO) "新建清单" else "新建备忘", writable) {
                val id = AndroidProductivity.id()
                form(if (kind == ProductivityKind.TODO) "新建清单" else "新建备忘", "名称", "",
                    if (kind == ProductivityKind.MEMO) "" else null, false, requireNotNull(expected),
                    onSaved = { selected = id }) { title, body ->
                    listOf(ProductivityEdit.Create(if (kind == ProductivityKind.TODO) ProductivityRecord.TodoList(id, title)
                    else ProductivityRecord.Memo(id, title, body.orEmpty())))
                }
            }
        }
        if (record == null) {
            column.label(if (selected == null) "暂无${kind.label}" else "所选内容已删除或不可用")
            return
        }
        selected = record.id
        val header = column.section()
        header.label(record.title, 24f, true)
        when (record) {
            is ProductivityRecord.TodoList -> {
                val tasks = current.content.tasks(record.id)
                header.label("剩余 ${tasks.count { !it.completed }} 项")
                header.action("添加任务", writable) {
                    val id = AndroidProductivity.id()
                    val position = (tasks.lastOrNull()?.position ?: -1).let { if (it == Int.MAX_VALUE) it else it + 1 }
                    form("添加任务", "任务正文", "", null, true, requireNotNull(expected)) { text, _ ->
                        listOf(ProductivityEdit.Create(ProductivityRecord.Task(id, record.id, text, false, position)))
                    }
                }
                if (!fixed) {
                    val actions = header.actions()
                    actions.action("重命名清单", writable) {
                        form("重命名清单", "名称", record.name, null, false, requireNotNull(expected)) { text, _ ->
                            SharedProductivity.encode(record.copy(name = text))
                            listOf(ProductivityEdit.RenameList(record.id, text))
                        }
                    }
                    actions.action("删除清单", writable) {
                        confirm("删除清单", "将删除「${record.name}」及其中 ${tasks.size} 项任务。") {
                            save(requireNotNull(expected), listOf(ProductivityEdit.DeleteList(record.id, tasks.map { it.id }.toSet()))) {
                                selected = null
                            }
                        }
                    }
                }
                val pending = tasks.filterNot { it.completed }
                pending.take(pendingLimit).forEach { task(it, expected, writable) }
                if (pending.size > pendingLimit) column.action("显示更多待办") { pendingLimit += 24; render() }
                val completed = tasks.filter { it.completed }
                if (completed.isNotEmpty()) {
                    column.action(if (showCompleted) "收起已完成" else "展开已完成（${completed.size}）") {
                        showCompleted = !showCompleted; render()
                    }
                    if (showCompleted) {
                        completed.take(completedLimit).forEach { task(it, expected, writable) }
                        if (completed.size > completedLimit) column.action("显示更多已完成任务") { completedLimit += 24; render() }
                    }
                }
                initialTask?.let { target ->
                    initialTask = null
                    tasks.firstOrNull { it.id == target }?.let { if (writable) editTask(it, requireNotNull(expected)) }
                }
            }
            is ProductivityRecord.Memo -> {
                header.label(record.body.ifEmpty { "空备忘" }, 18f)
                val edit: () -> Unit = {
                    form("编辑备忘", "标题", record.title, record.body, false, requireNotNull(expected)) { title, body ->
                        val updated = record.copy(title = title, body = body.orEmpty())
                        SharedProductivity.encode(updated)
                        buildList {
                            if (record.title != title) add(ProductivityEdit.RenameMemo(record.id, title))
                            if (record.body != updated.body) add(ProductivityEdit.EditMemo(record.id, updated.body))
                        }
                    }
                }
                header.action("编辑备忘", writable, edit)
                if (editMemo) { editMemo = false; if (writable) edit() }
                if (!fixed) header.action("删除备忘", writable) {
                    confirm("删除备忘", "删除「${record.title}」？") {
                        save(requireNotNull(expected), listOf(ProductivityEdit.DeleteMemo(record.id))) { selected = null }
                    }
                }
            }
            is ProductivityRecord.Task -> Unit
        }
    }

    private fun task(task: ProductivityRecord.Task, expected: ProductivityVersion?, writable: Boolean) {
        column.section().apply {
            tag = task.id
            label(task.text, 17f)
            val actions = actions()
            actions.action(if (task.completed) "取消完成" else "标为完成", writable) {
                save(requireNotNull(expected), listOf(ProductivityEdit.SetTaskCompleted(task.id, !task.completed)))
            }
            actions.action("编辑", writable) { editTask(task, requireNotNull(expected)) }
            actions.action("删除", writable) {
                confirm("删除任务", task.text) {
                    save(requireNotNull(expected), listOf(ProductivityEdit.DeleteTask(task.id)))
                }
            }
        }
    }

    private fun editTask(task: ProductivityRecord.Task, expected: ProductivityVersion) {
        form("编辑任务", "任务正文", task.text, null, true, expected) { text, _ ->
            SharedProductivity.encode(task.copy(text = text))
            listOf(ProductivityEdit.EditTask(task.id, text))
        }
    }

    private fun form(title: String, hint: String, original: String, body: String?, multiline: Boolean,
        expected: ProductivityVersion, onSaved: () -> Unit = {}, edits: (String, String?) -> List<ProductivityEdit>) {
        fun input(label: String, original: String, lines: Boolean) = EditText(context).apply {
            this.hint = label
            inputType = InputType.TYPE_CLASS_TEXT or if (lines) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            minHeight = dp(48)
            if (lines) { minLines = 3; maxLines = 9; gravity = Gravity.TOP }
            setText(original)
        }
        val fields = column()
        val first = input(hint, original, multiline).also(fields::addView)
        val second = body?.let { input("备忘正文", it, true).also(fields::addView) }
        val dialog = AlertDialog.Builder(context).setTitle(title)
            .setView(ScrollView(context).apply { addView(fields) })
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        track(dialog.apply { show() })
        val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        submit.setOnClickListener {
            val candidate = runCatching { edits(first.text.toString(), second?.text?.toString()) }
            val commands = candidate.mapCatching { commands ->
                commands.filterIsInstance<ProductivityEdit.Create>().forEach { SharedProductivity.encode(it.value) }
                commands
            }.getOrElse {
                (second ?: first).error = it.message ?: "文本不符合限制"
                return@setOnClickListener
            }
            submit.isEnabled = false
            dialog.setCancelable(false)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            save(expected, commands, onFailure = {
                submit.isEnabled = it !is ScheduleCommitUnconfirmed
                dialog.setCancelable(true)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                (second ?: first).error = it.message ?: "保存失败"
            }) {
                onSaved()
                dialog.dismiss()
            }
        }
    }

    private fun save(expected: ProductivityVersion, edits: List<ProductivityEdit>,
        onFailure: (Throwable) -> Unit = { context.productivityMessage("未保存", it.message.orEmpty()) },
        success: () -> Unit = {}) {
        if (saving || !opened) return
        if (edits.isEmpty()) { success(); return }
        saving = true
        render()
        data.save(expected, edits, { outcome ->
            if (opened) {
                saving = false
                if (outcome.exceptionOrNull() is ScheduleCommitUnconfirmed) uncertain = edits
                outcome.fold({ success() }, onFailure)
                data.load(dataset) { latest = it; render() }
            }
        }, validate)
    }

    private fun conflictDialog(version: ProductivityVersion, conflict: ProductivityConflict) {
        val text = conflict.rows.joinToString("\n\n") { row ->
            listOf("共同基线" to row.base, "本机" to row.local, "远端" to row.remote).joinToString("\n") { (label, entry) ->
                "$label\n" + when (entry) {
                    is ProductivityRecord.Memo -> entry.title + "\n" + entry.body
                    is ProductivityRecord.Task -> (if (entry.completed) "☑ " else "☐ ") + entry.text
                    else -> entry?.title ?: "无记录"
                }
            }
        }.ifEmpty { "冲突涉及其他课表数据，请到 WebDAV 共享管理中查看完整冲突。" }
        val fields = column()
        fields.label(text)
        fields.action("复制对比内容") {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("共享冲突", text))
        }
        if (conflict.rows.isNotEmpty()) listOf(ScheduleMerge.Choice.LOCAL to "采用本机方案",
            ScheduleMerge.Choice.REMOTE to "采用远端方案").forEach { (choice, label) ->
            fields.action(label) {
                confirm(label, "此决定应用于整组共享冲突，可能包含课表记录。") {
                    data.resolve(version, conflict, choice) { result ->
                        if (opened && result.isFailure) context.productivityMessage("处理失败", result.exceptionOrNull()?.message.orEmpty())
                    }
                }
            }
        }
        track(AlertDialog.Builder(context).setTitle("共享冲突")
            .setView(ScrollView(context).apply { addView(fields) }).setNegativeButton("关闭", null).show())
    }

    private fun confirm(title: String, text: String, action: () -> Unit) {
        track(AlertDialog.Builder(context).setTitle(title).setMessage(text).setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ -> if (opened && !saving) action() }.show())
    }

    private fun track(dialog: AlertDialog) {
        decorate(dialog)
        dialogs += dialog
        dialog.setOnDismissListener { dialogs -= dialog }
    }
}


private fun ProductivityEdit.matches(content: SharedProductivity.Content): Boolean = when (this) {
    is ProductivityEdit.Create -> when (value) {
        is ProductivityRecord.TodoList -> value in content.lists
        is ProductivityRecord.Task -> value in content.tasks
        is ProductivityRecord.Memo -> value in content.memos
    }
    is ProductivityEdit.RenameList -> content.lists.any { it.id == id && it.name == name }
    is ProductivityEdit.EditTask -> content.tasks.any { it.id == id && it.text == text }
    is ProductivityEdit.SetTaskCompleted -> content.tasks.any { it.id == id && it.completed == completed }
    is ProductivityEdit.RenameMemo -> content.memos.any { it.id == id && it.title == title }
    is ProductivityEdit.EditMemo -> content.memos.any { it.id == id && it.body == body }
    is ProductivityEdit.DeleteList -> content.lists.none { it.id == id } && content.tasks.none { it.listId == id }
    is ProductivityEdit.DeleteTask -> content.tasks.none { it.id == id }
    is ProductivityEdit.DeleteMemo -> content.memos.none { it.id == id }
}
