package com.dormpanel.app.schedule

import android.content.Context
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.appearance.*

internal enum class X08eScheduleLayout { CLASSIC, TIME, PERIODS }
internal data class X08eScheduleStyle(val layout: X08eScheduleLayout = X08eScheduleLayout.CLASSIC,
    val colors: Boolean = false, val hierarchy: Boolean = false)

/** Device UI only; deliberately separate from Room, term preferences and snapshots. */
internal class X08eSchedulePreferences(context: Context, namespace: String = "x08e_schedule_display") {
    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    fun read() = X08eScheduleStyle(
        X08eScheduleLayout.entries.firstOrNull { it.name == prefs.getString("layout", null) } ?: X08eScheduleLayout.CLASSIC,
        prefs.getBoolean("colors", false), prefs.getBoolean("hierarchy", false))
    fun write(style: X08eScheduleStyle): Boolean = prefs.edit().putString("layout", style.layout.name)
        .putBoolean("colors", style.colors).putBoolean("hierarchy", style.hierarchy).commit()
}

internal fun Context.scheduleText(zh: String, en: String): String =
    if (resources.configuration.locales[0].language == "zh") zh else en

internal fun Context.scheduleStyleDialog(style: X08eScheduleStyle, appearance: AppearanceController,
    save: (X08eScheduleStyle) -> Boolean): AlertDialog {
    val fields = scheduleColumn().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
    fields.addView(scheduleLabel(scheduleText("布局", "Layout")))
    val layout = Spinner(this).apply {
        contentDescription = scheduleText("课表布局", "Timetable layout"); minimumHeight = dp(48)
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
            listOf(scheduleText("经典列表", "Classic list"), scheduleText("实际时间", "Actual time"), scheduleText("按节次", "Periods")))
        setSelection(style.layout.ordinal)
    }
    val colors = CheckBox(this).apply { text = scheduleText("按课程配色", "Stable course colors"); isChecked = style.colors; minHeight = dp(48) }
    val hierarchy = CheckBox(this).apply { text = scheduleText("优化信息排版", "Information hierarchy"); isChecked = style.hierarchy; minHeight = dp(48) }
    fields.addView(layout); fields.addView(colors); fields.addView(hierarchy)
    fields.addView(scheduleLabel(scheduleText("取消勾选可恢复经典单色和经典排版。仅保存在本机，不参与课表同步。", "Uncheck to restore classic colors and text. Saved on this device only."), 16f))
    val dialog = AlertDialog.Builder(this).setTitle(scheduleText("课表显示设置", "Timetable display"))
        .setView(fields).setNegativeButton(scheduleText("取消", "Cancel"), null).setPositiveButton(scheduleText("保存", "Save"), null).create()
    val theme: (AppearanceState) -> Unit = { state -> dialog.window?.decorView?.let {
        it.setBackgroundColor(PanelPalette.forMode(state.themeMode).surface); applyAppearanceTree(it, state)
    } }
    dialog.setOnDismissListener { appearance.removeListener(theme) }
    dialog.show(); dialog.matchActivityBrightness(); appearance.addListener(theme); theme(appearance.state)
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        if (save(X08eScheduleStyle(X08eScheduleLayout.entries[layout.selectedItemPosition], colors.isChecked, hierarchy.isChecked))) dialog.dismiss()
        else Toast.makeText(this, scheduleText("显示设置保存失败，请重试", "Could not save display settings. Try again."), Toast.LENGTH_LONG).show()
    }
    return dialog
}
