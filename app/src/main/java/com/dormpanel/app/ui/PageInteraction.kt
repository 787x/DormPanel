package com.dormpanel.app.ui

import android.view.MotionEvent

/** Boundary between page content gestures and MainActivity's global swipe observer. */
interface PageInteraction {
    fun shouldObservePageSwipe(event: MotionEvent): Boolean = true
    fun handleBack(): Boolean = false
}
