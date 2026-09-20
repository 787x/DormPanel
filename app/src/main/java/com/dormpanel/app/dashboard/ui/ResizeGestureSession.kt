package com.dormpanel.app.dashboard.ui

import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.model.CardSizePolicy
import kotlin.math.roundToInt

/** Maps one pointer stream to distinct, provider-approved logical sizes. */
class ResizeGestureSession(
    private val pointerOriginX: Float,
    private val pointerOriginY: Float,
    private val initialSize: CardSize,
    private val maximumSize: CardSize,
    private val sizePolicy: CardSizePolicy,
) {
    private var lastSnappedSize = initialSize

    fun update(
        pointerX: Float,
        pointerY: Float,
        columnStepPx: Float,
        rowStepPx: Float,
    ): CardSize? {
        require(columnStepPx > 0f && rowStepPx > 0f) { "Grid steps must be positive" }
        val requestedColumns = (
            initialSize.columnSpan + ((pointerX - pointerOriginX) / columnStepPx).roundToInt()
            ).coerceIn(1, maximumSize.columnSpan)
        val requestedRows = (
            initialSize.rowSpan + ((pointerY - pointerOriginY) / rowStepPx).roundToInt()
            ).coerceIn(1, maximumSize.rowSpan)
        val snapped = sizePolicy.snap(
            CardSize(requestedColumns, requestedRows),
            maximumSize,
        ) ?: return null
        if (snapped == lastSnappedSize) return null
        lastSnappedSize = snapped
        return snapped
    }
}
