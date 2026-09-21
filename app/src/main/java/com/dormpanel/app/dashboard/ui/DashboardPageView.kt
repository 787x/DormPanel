package com.dormpanel.app.dashboard.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.dashboard.catalog.CardCatalog
import androidx.constraintlayout.widget.ConstraintLayout
import com.dormpanel.app.R
import com.dormpanel.app.dashboard.DashboardStateHolder
import com.dormpanel.app.dashboard.DashboardUiState
import com.dormpanel.app.dashboard.card.DashboardCardRegistry
import com.dormpanel.app.dashboard.layout.LayoutMutationResult
import com.dormpanel.app.ui.PageInteraction

class DashboardPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ConstraintLayout(context, attrs), PageInteraction {
    private lateinit var stateHolder: DashboardStateHolder
    private lateinit var catalog: CardCatalog
    private lateinit var appearance: AppearanceController
    private var backend: com.dormpanel.app.ha.DashboardBackend? = null
    private val haListener: (com.dormpanel.app.ha.HaStatus) -> Unit = { status ->
        findViewById<TextView>(R.id.ha_connection_hint).apply {
            visibility = if (backend?.settings?.mode == com.dormpanel.app.ha.BackendMode.HOME_ASSISTANT && status.state != com.dormpanel.app.ha.HaConnectionState.CONNECTED) View.VISIBLE else View.GONE
            text = context.getString(R.string.ha_status, status.state, "")
        }
    }
    private var picker: CardPickerDialog? = null
    private lateinit var gridView: DashboardGridView
    private lateinit var editToolbar: View
    private lateinit var addButton: Button
    private lateinit var doneButton: Button
    private lateinit var editButton: Button
    private lateinit var message: TextView
    private var navigationHints = emptyList<View>()
    private var onGestureClaimed: () -> Unit = {}
    private var editing = false
    private var bound = false
    private var gestureClaimed = false
    private val stateListener: (DashboardUiState) -> Unit = ::render

    override fun onFinishInflate() {
        super.onFinishInflate()
        gridView = findViewById(R.id.dashboard_grid)
        editToolbar = findViewById(R.id.dashboard_edit_toolbar)
        addButton = findViewById(R.id.dashboard_add)
        doneButton = findViewById(R.id.dashboard_done)
        editButton = findViewById(R.id.dashboard_edit)
        message = findViewById(R.id.dashboard_message)
        navigationHints = listOf(
            findViewById(R.id.apps_hint),
            findViewById(R.id.calendar_hint),
            findViewById(R.id.home_control_hint),
            findViewById(R.id.control_center_hint),
        )
    }

    fun bind(
        stateHolder: DashboardStateHolder,
        registry: DashboardCardRegistry,
        onGestureClaimed: () -> Unit,
        catalog: CardCatalog,
        appearance: AppearanceController,
        backend: com.dormpanel.app.ha.DashboardBackend? = null,
    ) {
        this.backend = backend
        this.stateHolder = stateHolder
        this.catalog = catalog
        this.appearance = appearance
        this.onGestureClaimed = {
            gestureClaimed = true
            onGestureClaimed()
        }
        gridView.bind(
            stateHolder = stateHolder,
            registry = registry,
            onEnterEditMode = ::enterEditMode,
            onGestureClaimed = this.onGestureClaimed,
            onOperationRejected = {
                Toast.makeText(context, R.string.dashboard_operation_rejected, Toast.LENGTH_SHORT).show()
            },
        )
        addButton.setOnClickListener { showAddCardDialog() }
        doneButton.setOnClickListener { leaveEditMode() }
        editButton.setOnClickListener {
            this.onGestureClaimed()
            enterEditMode()
        }
        bound = true
        if (isAttachedToWindow) { stateHolder.addListener(stateListener); backend?.ha?.addStatusListener(haListener) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (bound) { stateHolder.addListener(stateListener); backend?.ha?.addStatusListener(haListener) }
    }

    override fun onDetachedFromWindow() {
        backend?.ha?.removeStatusListener(haListener)
        picker?.dismiss(); picker = null
        if (bound) stateHolder.removeListener(stateListener)
        super.onDetachedFromWindow()
    }

    override fun shouldObservePageSwipe(event: MotionEvent): Boolean {
        if (editing) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureClaimed = descendantClaimsGesture(event.rawX, event.rawY)
        }
        val shouldObserve = !gestureClaimed
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            gestureClaimed = false
        }
        return shouldObserve
    }

    override fun handleBack(): Boolean {
        if (!editing) return false
        leaveEditMode()
        return true
    }

    private fun render(state: DashboardUiState) {
        gridView.submitCards(state.cards)
        message.visibility = if (!state.loaded || state.storageError) VISIBLE else GONE
        message.setText(
            when {
                !state.loaded -> R.string.dashboard_loading
                state.storageError -> R.string.dashboard_storage_error
                else -> R.string.dashboard_loading
            },
        )
    }

    private fun enterEditMode() {
        if (editing) return
        editing = true
        editToolbar.visibility = VISIBLE
        editButton.visibility = GONE
        navigationHints.forEach { it.visibility = GONE }
        gridView.setEditMode(true)
    }

    private fun leaveEditMode() {
        if (!editing) return
        editing = false
        editToolbar.visibility = GONE
        editButton.visibility = VISIBLE
        navigationHints.forEach { it.visibility = VISIBLE }
        gridView.setEditMode(false)
        gestureClaimed = false
    }

    private fun showAddCardDialog() {
        picker?.dismiss()
        picker = CardPickerDialog(context, catalog, appearance) { candidate ->
            val result = stateHolder.add(candidate)
            if (result is LayoutMutationResult.Failure) {
                Toast.makeText(context, R.string.dashboard_no_space, Toast.LENGTH_SHORT).show()
                false
            } else true
        }.also { it.show() }
    }

    /** Future sliders/scrolling controls opt in by setting tag_claims_page_gesture=true. */
    private fun descendantClaimsGesture(rawX: Float, rawY: Float): Boolean {
        fun contains(view: View): Boolean {
            if (view.visibility != VISIBLE) return false
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return rawX >= location[0] && rawX < location[0] + view.width &&
                rawY >= location[1] && rawY < location[1] + view.height
        }

        fun search(view: View): Boolean {
            if (!contains(view)) return false
            if (view.getTag(R.id.tag_claims_page_gesture) == true) return true
            if (view is ViewGroup) {
                for (index in view.childCount - 1 downTo 0) {
                    if (search(view.getChildAt(index))) return true
                }
            }
            return false
        }
        return search(this)
    }
}
