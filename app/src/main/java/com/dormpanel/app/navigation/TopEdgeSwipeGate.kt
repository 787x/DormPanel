package com.dormpanel.app.navigation

import android.view.MotionEvent

/** Excludes global page swipes whose DOWN began in the system's top-edge zone. */
class TopEdgeSwipeGate(private val topEdgePx: Float) {
    private var reservedStream = false

    fun shouldObserve(actionMasked: Int, rawY: Float): Boolean {
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> reservedStream = rawY >= 0f && rawY < topEdgePx
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val observe = !reservedStream
                reservedStream = false
                return observe
            }
        }
        return !reservedStream
    }
}
