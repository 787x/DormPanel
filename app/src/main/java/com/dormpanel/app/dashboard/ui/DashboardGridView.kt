package com.dormpanel.app.dashboard.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import com.dormpanel.app.R
import com.dormpanel.app.dashboard.DashboardStateHolder
import com.dormpanel.app.dashboard.card.CardInteractionScope
import com.dormpanel.app.dashboard.card.DashboardCardRegistry
import com.dormpanel.app.dashboard.layout.LayoutMutationResult
import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.model.DashboardGridPolicy
import com.dormpanel.app.dashboard.model.PlacedCard
import kotlin.math.roundToInt
import com.dormpanel.app.appearance.AppearanceAware
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.appearance.applyAppearanceTree

class DashboardGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs), AppearanceAware {
    private var appearance = AppearanceState()
    override fun applyAppearance(state: AppearanceState) {
        appearance = state
        entries.values.forEach {
            it.container.background = cardBackground(editMode)
            applyAppearanceTree(it.content, state)
        }
    }
    private val definition = DashboardGridPolicy.definition
    private val gapPx = DashboardGridPolicy.GAP_DP.dp
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val entries = linkedMapOf<String, CardEntry>()
    private var committedCards = emptyList<PlacedCard>()
    private var displayedCards = emptyList<PlacedCard>()
    private lateinit var stateHolder: DashboardStateHolder
    private lateinit var registry: DashboardCardRegistry
    private var onEnterEditMode: () -> Unit = {}
    private var onGestureClaimed: () -> Unit = {}
    private var onOperationRejected: (String) -> Unit = {}
    private var editMode = false
    private var cellWidth = 0f
    private var cellHeight = 0f
    private var dragSession: DragSession? = null
    private var resizeSession: ActiveResizeSession? = null

    fun bind(
        stateHolder: DashboardStateHolder,
        registry: DashboardCardRegistry,
        onEnterEditMode: () -> Unit,
        onGestureClaimed: () -> Unit,
        onOperationRejected: (String) -> Unit,
    ) {
        this.stateHolder = stateHolder
        this.registry = registry
        this.onEnterEditMode = onEnterEditMode
        this.onGestureClaimed = onGestureClaimed
        this.onOperationRejected = onOperationRejected
        isClickable = true
        isLongClickable = true
        setOnLongClickListener {
            this.onGestureClaimed()
            this.onEnterEditMode()
            true
        }
    }

    fun submitCards(cards: List<PlacedCard>) {
        committedCards = cards.toList()
        displayedCards = committedCards
        synchronizeChildren()
        requestLayout()
    }

    fun setEditMode(editing: Boolean) {
        editMode = editing
        entries.values.forEach { entry ->
            entry.chrome.visibility = if (editing) VISIBLE else GONE
            entry.container.background = cardBackground(editing)
        }
        if (!editing) cancelInteractions() else applyDisplayedCards(committedCards)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        cellWidth = (width - gapPx * (definition.columns - 1)).toFloat() / definition.columns
        cellHeight = (height - gapPx * (definition.rows - 1)).toFloat() / definition.rows

        displayedCards.forEach { card ->
            val child = entries[card.id]?.container ?: return@forEach
            val childWidth = (cellWidth * card.size.columnSpan + gapPx * (card.size.columnSpan - 1)).roundToInt()
            val childHeight = (cellHeight * card.size.rowSpan + gapPx * (card.size.rowSpan - 1)).roundToInt()
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        displayedCards.forEach { card ->
            val child = entries[card.id]?.container ?: return@forEach
            val childLeft = (card.column * (cellWidth + gapPx)).roundToInt()
            val childTop = (card.row * (cellHeight + gapPx)).roundToInt()
            child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + child.measuredHeight)
        }
    }

    private fun synchronizeChildren() {
        val currentIds = committedCards.mapTo(mutableSetOf()) { it.id }
        entries.keys.filterNot { it in currentIds }.forEach { id ->
            removeView(entries.remove(id)?.container)
        }

        committedCards.forEach { card ->
            val provider = registry.provider(card.providerType) ?: return@forEach
            val entry = entries[card.id] ?: createEntry(card).also {
                entries[card.id] = it
                addView(it.container)
            }
            provider.bind(entry.content, card, cardInteractions())
            applyAppearanceTree(entry.content, appearance)
            val maximumSize = CardSize(
                definition.columns - card.column,
                definition.rows - card.row,
            )
            entry.resize.visibility = if (
                registry.hasAlternativeSize(card.providerType, card.size, maximumSize)
            ) VISIBLE else GONE
            entry.resize.contentDescription = context.getString(R.string.dashboard_resize_card, provider.displayMetadata.name(context))
            entry.delete.contentDescription = context.getString(R.string.dashboard_delete_card, provider.displayMetadata.name(context))
            entry.chrome.visibility = if (editMode) VISIBLE else GONE
            entry.container.background = cardBackground(editMode)
        }
    }

    private fun createEntry(card: PlacedCard): CardEntry {
        val provider = checkNotNull(registry.provider(card.providerType))
        val container = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            clipToOutline = true
            background = cardBackground(editMode)
        }
        val content = provider.createView(context)
        container.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val chrome = FrameLayout(context).apply {
            visibility = if (editMode) VISIBLE else GONE
            isClickable = true
            contentDescription = context.getString(R.string.dashboard_drag_card)
            setOnTouchListener { view, event ->
                val isClick = event.actionMasked == MotionEvent.ACTION_UP && dragSession?.dragging != true
                val handled = handleDragTouch(card.id, view, event)
                if (isClick) view.performClick()
                handled
            }
        }
        val grip = TextView(context).apply {
            text = context.getString(R.string.dashboard_drag_hint)
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(context.getColor(R.color.panel_primary_text))
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(150, 8, 10, 14))
        }
        chrome.addView(
            grip,
            FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, 36.dp).apply {
                gravity = Gravity.CENTER
                leftMargin = 12.dp
                rightMargin = 12.dp
            },
        )
        val delete = editButton("×", TextView(context)).apply {
            setOnClickListener {
                val result = stateHolder.delete(card.id)
                reportFailure(result)
            }
        }
        chrome.addView(
            delete,
            FrameLayout.LayoutParams(44.dp, 44.dp).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 8.dp
                marginEnd = 8.dp
            },
        )
        val resize = editButton("◢", ResizeHandleView(context)).apply {
            setOnTouchListener { view, event ->
                val handled = handleResizeTouch(card.id, view, event)
                if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                handled
            }
        }
        chrome.addView(
            resize,
            FrameLayout.LayoutParams(48.dp, 48.dp).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = 8.dp
                marginEnd = 8.dp
            },
        )
        container.addView(chrome, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        return CardEntry(container, content, chrome, delete, resize)
    }

    private fun handleDragTouch(cardId: String, touchedView: View, event: MotionEvent): Boolean {
        if (resizeSession != null) return false
        val card = committedCards.firstOrNull { it.id == cardId } ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onGestureClaimed()
                dragSession = DragSession(
                    cardId = cardId,
                    startRawX = event.rawX,
                    startRawY = event.rawY,
                    startColumn = card.column,
                    startRow = card.row,
                    targetColumn = card.column,
                    targetRow = card.row,
                )
                touchedView.alpha = 0.82f
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val session = dragSession?.takeIf { it.cardId == cardId } ?: return false
                val deltaX = event.rawX - session.startRawX
                val deltaY = event.rawY - session.startRawY
                if (!session.dragging && kotlin.math.abs(deltaX) < touchSlop && kotlin.math.abs(deltaY) < touchSlop) {
                    return true
                }
                session.dragging = true
                val maxColumn = definition.columns - card.size.columnSpan
                val maxRow = definition.rows - card.size.rowSpan
                val targetColumn = (session.startColumn + (deltaX / (cellWidth + gapPx)).roundToInt())
                    .coerceIn(0, maxColumn)
                val targetRow = (session.startRow + (deltaY / (cellHeight + gapPx)).roundToInt())
                    .coerceIn(0, maxRow)
                if (targetColumn == session.targetColumn && targetRow == session.targetRow) return true
                session.targetColumn = targetColumn
                session.targetRow = targetRow
                when (val preview = stateHolder.previewMove(
                    committedCards,
                    cardId,
                    targetColumn,
                    targetRow,
                )) {
                    is LayoutMutationResult.Success -> {
                        applyDisplayedCards(preview.cards)
                    }
                    is LayoutMutationResult.Failure -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val session = dragSession?.takeIf { it.cardId == cardId }
                touchedView.alpha = 1f
                dragSession = null
                if (session != null && session.dragging) {
                    reportFailure(stateHolder.move(cardId, session.targetColumn, session.targetRow))
                } else {
                    applyDisplayedCards(committedCards)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                touchedView.alpha = 1f
                cancelMove()
                return true
            }
        }
        return false
    }

    private fun handleResizeTouch(cardId: String, touchedView: View, event: MotionEvent): Boolean {
        val card = committedCards.firstOrNull { it.id == cardId } ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val provider = registry.provider(card.providerType) ?: return false
                onGestureClaimed()
                cancelMove()
                resizeSession = ActiveResizeSession(
                    cardId = cardId,
                    initialSize = card.size,
                    lastValidSize = card.size,
                    gesture = ResizeGestureSession(
                        pointerOriginX = event.rawX,
                        pointerOriginY = event.rawY,
                        initialSize = card.size,
                        maximumSize = CardSize(
                            definition.columns - card.column,
                            definition.rows - card.row,
                        ),
                        sizePolicy = provider.sizePolicy,
                    ),
                )
                requestDisallowInterceptTouchEvent(true)
                touchedView.alpha = 0.72f
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val session = resizeSession?.takeIf { it.cardId == cardId } ?: return false
                val snappedSize = session.gesture.update(
                    pointerX = event.rawX,
                    pointerY = event.rawY,
                    columnStepPx = cellWidth + gapPx,
                    rowStepPx = cellHeight + gapPx,
                ) ?: return true
                when (val preview = stateHolder.previewResize(committedCards, cardId, snappedSize)) {
                    is LayoutMutationResult.Success -> {
                        session.lastValidSize = snappedSize
                        applyDisplayedCards(preview.cards)
                    }
                    is LayoutMutationResult.Failure -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val session = resizeSession?.takeIf { it.cardId == cardId }
                resizeSession = null
                requestDisallowInterceptTouchEvent(false)
                touchedView.alpha = 1f
                if (session != null && session.lastValidSize != session.initialSize) {
                    reportFailure(stateHolder.resize(cardId, session.lastValidSize))
                } else {
                    applyDisplayedCards(committedCards)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                touchedView.alpha = 1f
                cancelResize()
                return true
            }
        }
        return false
    }

    private fun cancelMove() {
        dragSession = null
        entries.values.forEach { it.chrome.alpha = 1f }
        applyDisplayedCards(committedCards)
    }

    private fun cancelResize() {
        resizeSession = null
        requestDisallowInterceptTouchEvent(false)
        entries.values.forEach { it.resize.alpha = 1f }
        applyDisplayedCards(committedCards)
    }

    private fun cancelInteractions() {
        cancelMove()
        cancelResize()
    }

    private fun applyDisplayedCards(cards: List<PlacedCard>) {
        displayedCards = cards.toList()
        displayedCards.forEach { card ->
            val entry = entries[card.id] ?: return@forEach
            registry.provider(card.providerType)?.bind(entry.content, card, cardInteractions())
        }
        requestLayout()
    }

    private fun reportFailure(result: LayoutMutationResult) {
        if (result is LayoutMutationResult.Failure) {
            applyDisplayedCards(committedCards)
            onOperationRejected(result.reason.name)
        }
    }

    private fun cardInteractions() = CardInteractionScope(
        enabled = !editMode,
        onGestureClaimed = onGestureClaimed,
    )

    private fun <T : TextView> editButton(label: String, view: T): T = view.apply {
        text = label
        textSize = 24f
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.panel_primary_text))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(225, 27, 36, 48))
            setStroke(1.dp, context.getColor(R.color.panel_accent))
        }
        isClickable = true
        isFocusable = true
    }

    private fun cardBackground(editing: Boolean) = GradientDrawable().apply {
        cornerRadius = 18.dp.toFloat()
        val palette = PanelPalette.forMode(appearance.themeMode)
        setColor(Color.argb((appearance.cardSurfaceOpacity * 255).roundToInt(),
            Color.red(palette.surface), Color.green(palette.surface), Color.blue(palette.surface)))
        if (editing) setStroke(2.dp, palette.accent)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    private data class CardEntry(
        val container: FrameLayout,
        val content: View,
        val chrome: FrameLayout,
        val delete: TextView,
        val resize: TextView,
    )

    private data class DragSession(
        val cardId: String,
        val startRawX: Float,
        val startRawY: Float,
        val startColumn: Int,
        val startRow: Int,
        var targetColumn: Int,
        var targetRow: Int,
        var dragging: Boolean = false,
    )

    private data class ActiveResizeSession(
        val cardId: String,
        val initialSize: CardSize,
        var lastValidSize: CardSize,
        val gesture: ResizeGestureSession,
    )

    private class ResizeHandleView(context: Context) : AppCompatTextView(context) {
        override fun performClick(): Boolean = super.performClick()
    }
}
