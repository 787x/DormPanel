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
import com.dormpanel.app.R
import com.dormpanel.app.dashboard.DashboardStateHolder
import com.dormpanel.app.dashboard.card.DashboardCardRegistry
import com.dormpanel.app.dashboard.layout.LayoutMutationResult
import com.dormpanel.app.dashboard.model.DashboardGridPolicy
import com.dormpanel.app.dashboard.model.PlacedCard
import kotlin.math.roundToInt

class DashboardGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {
    private val definition = DashboardGridPolicy.definition
    private val gapPx = DashboardGridPolicy.GAP_DP.dp
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val entries = linkedMapOf<String, CardEntry>()
    private var committedCards = emptyList<PlacedCard>()
    private var displayedCards = emptyList<PlacedCard>()
    private lateinit var stateHolder: DashboardStateHolder
    private lateinit var registry: DashboardCardRegistry
    private var onEnterEditMode: () -> Unit = {}
    private var onOperationRejected: (String) -> Unit = {}
    private var editMode = false
    private var cellWidth = 0f
    private var cellHeight = 0f
    private var dragSession: DragSession? = null

    fun bind(
        stateHolder: DashboardStateHolder,
        registry: DashboardCardRegistry,
        onEnterEditMode: () -> Unit,
        onOperationRejected: (String) -> Unit,
    ) {
        this.stateHolder = stateHolder
        this.registry = registry
        this.onEnterEditMode = onEnterEditMode
        this.onOperationRejected = onOperationRejected
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
        if (!editing) cancelDrag()
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
            provider.bind(entry.content, card)
            entry.resize.visibility = if (provider.supportedSizes.size > 1) VISIBLE else GONE
            entry.resize.contentDescription = context.getString(R.string.dashboard_resize_card, provider.displayMetadata.name)
            entry.delete.contentDescription = context.getString(R.string.dashboard_delete_card, provider.displayMetadata.name)
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
            setOnLongClickListener {
                onEnterEditMode()
                true
            }
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
        val delete = editButton("×").apply {
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
        val resize = editButton("↘").apply {
            setOnClickListener {
                val current = committedCards.firstOrNull { it.id == card.id } ?: return@setOnClickListener
                val sizes = registry.provider(current.providerType)?.supportedSizes.orEmpty()
                if (sizes.size < 2) return@setOnClickListener
                val nextSize = sizes[(sizes.indexOf(current.size) + 1).mod(sizes.size)]
                reportFailure(stateHolder.resize(current.id, nextSize))
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
        val card = committedCards.firstOrNull { it.id == cardId } ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
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
                        displayedCards = preview.cards
                        requestLayout()
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
                    displayedCards = committedCards
                    requestLayout()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                touchedView.alpha = 1f
                cancelDrag()
                return true
            }
        }
        return false
    }

    private fun cancelDrag() {
        dragSession = null
        displayedCards = committedCards
        entries.values.forEach { it.chrome.alpha = 1f }
        requestLayout()
    }

    private fun reportFailure(result: LayoutMutationResult) {
        if (result is LayoutMutationResult.Failure) {
            displayedCards = committedCards
            requestLayout()
            onOperationRejected(result.reason.name)
        }
    }

    private fun editButton(label: String) = TextView(context).apply {
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
        setColor(context.getColor(R.color.dashboard_card_surface))
        setStroke(
            if (editing) 2.dp else 1.dp,
            context.getColor(if (editing) R.color.panel_accent else R.color.panel_outline),
        )
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
}
