package com.dormpanel.app.productivity

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.R
import com.dormpanel.app.appearance.*
import com.dormpanel.app.dashboard.card.*
import com.dormpanel.app.dashboard.catalog.*
import com.dormpanel.app.dashboard.model.*
import java.util.Locale

class ProductivityCatalog(context: Context) : CardCatalog {
    override val candidates = listOf("todo" to R.string.productivity_todo, "memo" to R.string.productivity_memo,
        "timer" to R.string.productivity_timer).map { (key, label) ->
        CardAddCandidate(key, CardCategory.PRODUCTIVITY, key, context.getString(label), context.getString(R.string.productivity_local), "{}")
    }
    override fun addListener(listener: (List<CardAddCandidate>) -> Unit) = listener(candidates)
    override fun removeListener(listener: (List<CardAddCandidate>) -> Unit) = Unit
}
class ProductivityCardProvider(override val typeKey: String, private val source: ProductivitySource,
    private val appearance: AppearanceController) : DashboardCardProvider {
    override val displayMetadata = CardDisplayMetadata(typeKey, "", when (typeKey) {
        "todo" -> R.string.productivity_todo; "memo" -> R.string.productivity_memo; else -> R.string.productivity_timer
    }, R.string.productivity_local)
    override val sizePolicy = ExplicitCardSizePolicy(listOf(CardSize(2, 1), CardSize(2, 2), CardSize(3, 2)))
    override val defaultSize = CardSize(2, 2)
    override fun createView(context: Context): View = ProductivityCardView(context, appearance, source, typeKey)
    override fun bind(view: View, card: PlacedCard, interactions: CardInteractionScope) = (view as ProductivityCardView).bind(card, interactions)
}

@SuppressLint("ViewConstructor")
class ProductivityCardView(context: Context, appearance: AppearanceController, private val source: ProductivitySource,
    private val kind: String) : DashboardCardView(context, appearance) {
    private val heading = label(20f)
    private val body = label(22f)
    private val detail = label(16f, true)
    private val controls = row()
    private val start = Button(context).apply { textSize = 16f }
    private val reset = Button(context).apply { textSize = 16f }
    private var subscribedOwner: String? = null
    private var visible = false
    private val dialogs = mutableListOf<AlertDialog>()
    private var updateDialog: (() -> Unit)? = null
    private val listener: () -> Unit = { refresh(); updateDialog?.invoke() }
    private val ticker = CountdownDisplayTicker({ task, delay -> postDelayed(task, delay) }, { removeCallbacks(it) }) { refresh(); updateDialog?.invoke() }
    init {
        addView(heading); addView(body); addView(detail); addView(controls)
        controls.addView(start, LayoutParams(0, 48.dp, 1f)); controls.addView(reset, LayoutParams(0, 48.dp, 1f))
        start.setOnClickListener { if (interactions.enabled) {
            interactions.claimGesture()
            if (source.state(card.id).timer.status == TimerStatus.RUNNING) source.pauseTimer(card.id) else source.startTimer(card.id)
        } }
        reset.setText(R.string.productivity_reset)
        reset.setOnClickListener { if (interactions.enabled) { interactions.claimGesture(); source.resetTimer(card.id) } }
    }
    private fun subscribe() {
        if (!isBound || !isAttachedToWindow || subscribedOwner == card.id) return
        subscribedOwner?.let { source.unsubscribe(it, listener) }
        subscribedOwner = card.id; source.subscribe(card.id, listener)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); subscribe(); refresh() }
    override fun onDetachedFromWindow() {
        ticker.stop(); subscribedOwner?.let { source.unsubscribe(it, listener) }; subscribedOwner = null
        dialogs.toList().forEach { it.dismiss() }; updateDialog = null
        super.onDetachedFromWindow()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); visible = isVisible; if (isBound) refresh() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (isBound) refresh() }
    override fun render() {
        subscribe(); ticker.stop()
        val state = source.state(card.id)
        val large = card.size.rowSpan > 1
        heading.setText(when (kind) { "todo" -> R.string.productivity_todo; "memo" -> R.string.productivity_memo; else -> R.string.productivity_timer })
        body.textSize = if (kind == "timer") 32f else 20f
        body.maxLines = if (!large) 1 else if (card.size.columnSpan >= 3) 5 else 3
        detail.show(kind != "memo")
        when (kind) {
            "todo" -> {
                val incomplete = state.todos.filterNot { it.completed }
                body.text = incomplete.take(body.maxLines).joinToString("\n") { "• ${it.text}" }.ifEmpty { context.getString(R.string.productivity_todo_empty) }
                detail.text = resources.getQuantityString(R.plurals.productivity_remaining, incomplete.size, incomplete.size)
            }
            "memo" -> body.text = state.memo.ifEmpty { context.getString(R.string.productivity_memo_empty) }
            else -> {
                body.text = formatTime(state.timer.remaining)
                detail.text = if (large) context.getString(R.string.productivity_timer_detail, context.getString(statusLabel(state.timer.status)), formatTime(state.timer.duration)) else context.getString(statusLabel(state.timer.status))
                ticker.update(visible && isAttachedToWindow && windowVisibility == VISIBLE, state.timer.status, state.timer.remaining)
            }
        }
        if (!source.ready || source.error) { detail.show(true); detail.setText(if (source.error) R.string.productivity_error else R.string.productivity_loading) }
        controls.show(kind == "timer" && large)
        start.setText(if (state.timer.status == TimerStatus.RUNNING) R.string.productivity_pause else if (state.timer.status == TimerStatus.PAUSED) R.string.productivity_resume else R.string.productivity_start)
        listOf(start, reset).forEach { it.isEnabled = interactions.enabled && source.ready; interactions.claimFromDown(it) }
        if (!interactions.enabled) dialogs.toList().forEach { it.dismiss() }
        applyAppearanceTree(controls, appearance.state)
        describe(heading.text, body.text, detail.text)
    }
    override fun primaryAction() {
        if (!interactions.enabled || !source.ready) return
        when (kind) { "todo" -> todoDialog(); "memo" -> memoDialog(); else -> timerDialog() }
    }
    override fun applyAppearance(state: AppearanceState) { super.applyAppearance(state); applyAppearanceTree(controls, state) }
    private fun input(text: String = "", multiline: Boolean = false) = EditText(context).apply {
        textSize = 22f; minHeight = 48.dp; setText(text)
        inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
        if (multiline) { minLines = 5; maxLines = 8; gravity = android.view.Gravity.TOP }
    }
    private fun button(label: Int, action: () -> Unit) = Button(context).apply {
        setText(label); textSize = 18f; minHeight = 48.dp; setOnClickListener { if (interactions.enabled) action() }
    }
    private fun show(title: Int, content: View, save: (() -> Unit)? = null): AlertDialog {
        val wrapper = column().apply { setPadding(24.dp, 12.dp, 24.dp, 12.dp); addView(content) }
        val builder = AlertDialog.Builder(context).setTitle(title).setView(wrapper).setNegativeButton(android.R.string.cancel, null)
        if (save != null) builder.setPositiveButton(R.string.productivity_save) { _, _ -> if (interactions.enabled) save() }
        val dialog = builder.create()
        dialogs += dialog
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show()
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE).forEach { which ->
            dialog.getButton(which)?.apply { textSize = 18f; minHeight = 48.dp }
        }
        dialog.window?.setLayout(minOf(760.dp, resources.displayMetrics.widthPixels - 48.dp), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.matchActivityBrightness()
        dialog.window?.decorView?.let { it.setBackgroundColor(PanelPalette.forMode(appearance.state.themeMode).surface); applyAppearanceTree(it, appearance.state) }
        return dialog
    }
    private fun memoDialog() {
        val edit = input(source.state(card.id).memo, true)
        val content = column().apply { addView(edit); addView(button(R.string.productivity_clear) { edit.setText("") }) }
        show(R.string.productivity_memo, content) { source.saveMemo(card.id, edit.text.toString()) }
    }
    private fun todoDialog() {
        val content = column(); val edit = input(); edit.setHint(R.string.productivity_task_hint)
        val tasks = column()
        content.addView(edit); content.addView(button(R.string.productivity_add) { source.addTodo(card.id, edit.text.toString()); edit.setText("") })
        content.addView(ScrollView(context).apply { addView(tasks) }, LayoutParams(LayoutParams.MATCH_PARENT, 260.dp))
        val update: () -> Unit = {
            tasks.removeAllViews()
            source.state(card.id).todos.sortedBy { it.completed }.forEach { task ->
                val line = row()
                line.addView(CheckBox(context).apply {
                    text = task.text; textSize = 20f; minHeight = 48.dp; isChecked = task.completed
                    setOnCheckedChangeListener { _, _ -> if (interactions.enabled) source.toggleTodo(card.id, task.id) }
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                line.addView(button(R.string.productivity_edit) {
                    val taskEdit = input(task.text)
                    show(R.string.productivity_edit, taskEdit) { source.editTodo(card.id, task.id, taskEdit.text.toString()) }
                })
                line.addView(button(R.string.productivity_delete) { source.deleteTodo(card.id, task.id) })
                tasks.addView(line)
            }
            applyAppearanceTree(tasks, appearance.state)
        }
        updateDialog = update; update()
        show(R.string.productivity_todo, content).setOnDismissListener { dialog -> dialogs.remove(dialog); updateDialog = null }
    }
    private fun timerDialog() {
        val content = column()
        content.addView(TextView(context).apply { textSize = 18f; setText(R.string.productivity_timer_hint) })
        val summary = TextView(context).apply { textSize = 24f; minHeight = 48.dp }
        content.addView(summary)
        val presets = row()
        listOf(60L, 300L, 1500L).forEach { seconds ->
            presets.addView(Button(context).apply { text = formatTime(seconds * 1000); textSize = 18f; minHeight = 48.dp
                setOnClickListener { if (interactions.enabled) source.configureTimer(card.id, seconds * 1000) }
            }, LayoutParams(0, 48.dp, 1f))
        }
        content.addView(presets)
        val seconds = input().apply { inputType = InputType.TYPE_CLASS_NUMBER; setHint(R.string.productivity_seconds) }
        content.addView(seconds)
        content.addView(button(R.string.productivity_set_duration) {
            val value = seconds.text.toString().toLongOrNull()
            if (value != null && value in 1..86400) source.configureTimer(card.id, value * 1000)
            else seconds.error = context.getString(R.string.productivity_seconds)
        })
        val toggle = button(R.string.productivity_start) {
            if (source.state(card.id).timer.status == TimerStatus.RUNNING) source.pauseTimer(card.id) else source.startTimer(card.id)
        }
        val update: () -> Unit = { summary.text = context.getString(R.string.productivity_timer_detail, formatTime(source.state(card.id).timer.remaining), context.getString(statusLabel(source.state(card.id).timer.status))); toggle.setText(if (source.state(card.id).timer.status == TimerStatus.RUNNING) R.string.productivity_pause else if (source.state(card.id).timer.status == TimerStatus.PAUSED) R.string.productivity_resume else R.string.productivity_start) }
        updateDialog = update; update()
        content.addView(toggle); content.addView(button(R.string.productivity_reset) { source.resetTimer(card.id) })
        show(R.string.productivity_timer, content).setOnDismissListener { dialog -> dialogs.remove(dialog); updateDialog = null }
    }
    private fun statusLabel(status: TimerStatus) = when (status) {
        TimerStatus.READY -> R.string.productivity_ready; TimerStatus.RUNNING -> R.string.productivity_running
        TimerStatus.PAUSED -> R.string.productivity_paused; TimerStatus.FINISHED -> R.string.productivity_finished
    }
    private fun formatTime(millis: Long): String {
        val seconds = (millis + 999) / 1000
        return if (seconds >= 3600) String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
    }
}
