package com.dormpanel.app.icloud

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
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
import com.dormpanel.app.schedule.scheduleButton
import com.dormpanel.app.schedule.scheduleColumn
import com.dormpanel.app.schedule.scheduleLabel

internal class CloudCalendarDialogs(private val context: Context, private val appearance: AppearanceController) {
    private val open = linkedSetOf<AlertDialog>()
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
            confirm("断开 iCloud？", "将清除此设备上的 iCloud 凭据、选择及事件缓存。", service::disconnect)
        }
        listOf(refresh, selection, cadence, login, clear, disconnect).forEach { content.addView(it) }
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
        }
        update()
        val dialog = show(AlertDialog.Builder(context).setTitle("iCloud 日历").setView(scroll(content))
            .setNegativeButton("关闭", null), onDismiss = { service.removeListener(update) })
        if (dialog.isShowing) service.addListener(update)
    }

    fun choose(calendars: List<CloudCalendar>, selected: Set<String>?, card: Boolean = false, save: (Set<String>?) -> Unit) {
        val content = column()
        val radio = if (card) RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            addView(choice("继承全局日历选择"))
            addView(choice("为此卡片选择日历"))
            check(getChildAt(if (selected == null) 0 else 1).id)
            content.addView(this)
        } else null
        val choices = calendars.map { calendar ->
            calendar.id to CheckBox(context).apply {
                text = "${calendar.name} · ${calendar.access.label()}"
                minHeight = context.dp(48)
                isChecked = calendar.id in selected.orEmpty()
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

    fun details(event: CloudCalendarEvent, time: String) {
        val content = column()
        val calendar = event.calendar
        val remote = event.event
        content.addView(context.scheduleLabel(time, 18f))
        content.addView(context.scheduleLabel("iCloud · ${calendar.name} · ${calendar.access.label()}", 18f).apply {
            calendar.color?.let { background = stripe(it, context.dp(3)) }
        })
        if (remote.location.isNotBlank()) content.addView(context.scheduleLabel(remote.location, 18f))
        if (remote.description.isNotBlank()) content.addView(context.scheduleLabel(remote.description, 18f))
        show(AlertDialog.Builder(context).setTitle(remote.title.ifBlank { "无标题日程" }).setView(scroll(content))
            .setNegativeButton("关闭", null))
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
                service.connect(account, secret)
                dialog.dismiss()
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
        if (secure) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        open.add(dialog)
        dialog.matchActivityBrightness()
        appearance.addListener(paint)
        paint(appearance.state)
        listOf(-1, -2, -3).forEach { dialog.getButton(it)?.minHeight = context.dp(48) }
        return dialog
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
