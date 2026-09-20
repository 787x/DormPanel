package com.dormpanel.app.appearance

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.TextView
import com.dormpanel.app.R

data class PanelPalette(val background: Int, val surface: Int, val text: Int, val secondary: Int, val accent: Int) {
    companion object {
        fun forMode(mode: ThemeMode) = if (mode == ThemeMode.DARK) {
            PanelPalette(Color.BLACK, 0xFF202020.toInt(), 0xFFF2F2F2.toInt(), 0xFFB5B5B5.toInt(), 0xFF91BEFF.toInt())
        } else {
            PanelPalette(0xFFF1F2F0.toInt(), Color.WHITE, 0xFF202723.toInt(), 0xFF56625B.toInt(), 0xFF205FAD.toInt())
        }
    }
}

interface AppearanceAware { fun applyAppearance(state: AppearanceState) }

/** Updates existing views without rebinding layout or replacing the Activity. */
fun applyAppearanceTree(view: View, state: AppearanceState) {
    if (view is AppearanceAware) { view.applyAppearance(state); return }
    val palette = PanelPalette.forMode(state.themeMode)
    if (view is TextView) view.setTextColor(palette.text)
    if (view is Button) view.backgroundTintList = ColorStateList.valueOf(palette.surface)
    if (view is CompoundButton) view.buttonTintList = ColorStateList.valueOf(palette.accent)
    if (view is SeekBar) {
        view.progressTintList = ColorStateList.valueOf(palette.accent)
        view.thumbTintList = ColorStateList.valueOf(palette.accent)
    }
    if (view.id in intArrayOf(R.id.ha_connection_hint, R.id.apps_hint, R.id.calendar_hint, R.id.home_control_hint, R.id.control_center_hint, R.id.dashboard_edit)) {
        if (view is TextView) view.setTextColor(palette.secondary)
        view.setBackgroundColor(Color.TRANSPARENT)
    }
    if (view.id in intArrayOf(R.id.return_hint, R.id.dashboard_edit_toolbar, R.id.dashboard_message)) {
        view.background = GradientDrawable().apply { cornerRadius = 24f; setColor(palette.surface) }
    }
    if (view is ViewGroup) for (index in 0 until view.childCount) applyAppearanceTree(view.getChildAt(index), state)
}
