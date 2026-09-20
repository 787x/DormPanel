package com.dormpanel.app.ui

import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.SeekBar
import com.dormpanel.app.R

/** Owns the whole stream, including before a horizontal slider movement crosses swipe slop. */
class ClaimingSeekBar(context: Context, private val claim: () -> Unit) : androidx.appcompat.widget.AppCompatSeekBar(context) {
    init {
        setTag(R.id.tag_claims_page_gesture, true)
        // SeekBar's theme maxHeight can defeat minimumHeight; reserve a full touch target explicitly.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * resources.displayMetrics.density).toInt())
    }
    // Native SeekBar owns touch handling and accessibility; this override only claims navigation.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            claim()
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }
    override fun performClick(): Boolean = super.performClick()
}

fun SeekBar.onUserProgress(change: (Int) -> Unit) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) change(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    })
}
