package com.dormpanel.app.navigation

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test

class TopEdgeSwipeGateTest {
    @Test fun edgeStartOwnsWholeStreamAndNextGestureRecovers() {
        val gate = TopEdgeSwipeGate(32f)
        assertFalse(gate.shouldObserve(MotionEvent.ACTION_DOWN, 0f))
        assertFalse(gate.shouldObserve(MotionEvent.ACTION_MOVE, 400f))
        assertFalse(gate.shouldObserve(MotionEvent.ACTION_UP, 700f))
        assertTrue(gate.shouldObserve(MotionEvent.ACTION_DOWN, 32f))
        assertTrue(gate.shouldObserve(MotionEvent.ACTION_MOVE, 0f))
        assertTrue(gate.shouldObserve(MotionEvent.ACTION_UP, 700f))
    }

    @Test fun cancelClearsReservationAndHorizontalMotionIsAlsoExcluded() {
        val gate = TopEdgeSwipeGate(32f)
        assertFalse(gate.shouldObserve(MotionEvent.ACTION_DOWN, 31.999f))
        assertFalse(gate.shouldObserve(MotionEvent.ACTION_MOVE, 500f))
        assertFalse(gate.shouldObserve(MotionEvent.ACTION_CANCEL, 500f))
        assertTrue(gate.shouldObserve(MotionEvent.ACTION_DOWN, 80f))
        assertTrue(gate.shouldObserve(MotionEvent.ACTION_UP, 80f))
    }
}
