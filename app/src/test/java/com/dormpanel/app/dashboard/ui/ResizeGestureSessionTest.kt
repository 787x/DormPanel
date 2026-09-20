package com.dormpanel.app.dashboard.ui

import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.model.ExplicitCardSizePolicy
import com.dormpanel.app.dashboard.model.RangeCardSizePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResizeGestureSessionTest {
    @Test
    fun `horizontal and vertical pointer movement maps to snapped logical spans`() {
        val session = ResizeGestureSession(
            pointerOriginX = 500f,
            pointerOriginY = 300f,
            initialSize = CardSize(3, 2),
            maximumSize = CardSize(6, 5),
            sizePolicy = RangeCardSizePolicy(CardSize(2, 1), CardSize(4, 3)),
        )

        assertEquals(CardSize(4, 3), session.update(560f, 360f, 100f, 100f))
        assertEquals(CardSize(2, 1), session.update(390f, 190f, 100f, 100f))
    }

    @Test
    fun `movement within the same snapped size emits no repeated mutation`() {
        val session = ResizeGestureSession(
            pointerOriginX = 0f,
            pointerOriginY = 0f,
            initialSize = CardSize(2, 1),
            maximumSize = CardSize(6, 3),
            sizePolicy = ExplicitCardSizePolicy(listOf(CardSize(2, 1), CardSize(4, 1))),
        )

        assertNull(session.update(80f, 0f, 100f, 100f))
        assertEquals(CardSize(4, 1), session.update(200f, 0f, 100f, 100f))
        assertNull(session.update(240f, 10f, 100f, 100f))
    }
}
