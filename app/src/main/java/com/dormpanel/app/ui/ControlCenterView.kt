package com.dormpanel.app.ui

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.ThemeMode
import kotlin.math.roundToInt

class ControlCenterView(context: Context, private val appearance: AppearanceController,
    private val onGestureClaimed: () -> Unit,
) : LinearLayout(context), PageInteraction {
    private val light = RadioButton(context).apply { id = generateViewId(); text = "Light"; textSize = 20f }
    private val dark = RadioButton(context).apply { id = generateViewId(); text = "Dark"; textSize = 20f }
    private val modes = RadioGroup(context).apply { orientation = HORIZONTAL; addView(light); addView(dark) }
    private val opacityLabel = TextView(context).apply { textSize = 22f }
    private var claimed = false
    private val opacity = ClaimingSeekBar(context) { claimed = true; onGestureClaimed() }.apply {
        max = 100
        contentDescription = "Card surface opacity"
    }
    private val listener: (AppearanceState) -> Unit = {
        modes.check(if (it.themeMode == ThemeMode.LIGHT) light.id else dark.id)
        opacity.progress = (it.cardSurfaceOpacity * 100).roundToInt()
        opacityLabel.text = "Card surface opacity · ${(it.cardSurfaceOpacity * 100).roundToInt()}%"
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        val density = resources.displayMetrics.density
        listOf(light, dark).forEach {
            it.minimumHeight = (48 * density).toInt()
            it.minWidth = (120 * density).toInt()
        }
        setPadding((160 * density).toInt(), (32 * density).toInt(), (160 * density).toInt(), (32 * density).toInt())
        addView(TextView(context).apply { text = "Control Center"; textSize = 36f })
        addView(TextView(context).apply { text = "Appearance"; textSize = 22f; setPadding(0, 24, 0, 8) })
        addView(modes)
        addView(opacityLabel)
        addView(opacity)
        addView(TextView(context).apply { text = "Swipe up to return home"; textSize = 16f; setPadding(0, 24, 0, 0) })
        modes.setOnCheckedChangeListener { _, checkedId -> appearance.setTheme(if (checkedId == light.id) ThemeMode.LIGHT else ThemeMode.DARK) }
        opacity.onUserProgress { appearance.setCardOpacity(it / 100f) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); appearance.addListener(listener) }
    override fun onDetachedFromWindow() { appearance.removeListener(listener); super.onDetachedFromWindow() }

    override fun shouldObservePageSwipe(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val location = IntArray(2)
            opacity.getLocationOnScreen(location)
            claimed = event.rawX >= location[0] && event.rawX < location[0] + opacity.width &&
                event.rawY >= location[1] && event.rawY < location[1] + opacity.height
        }
        val observe = !claimed
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) claimed = false
        return observe
    }
    override fun handleBack() = false
}
