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
import com.dormpanel.app.dashboard.ConfigurationSaveState
import com.dormpanel.app.dashboard.card.*
import com.dormpanel.app.dashboard.catalog.CardAddCandidate
import com.dormpanel.app.dashboard.layout.LayoutFailureReason
import com.dormpanel.app.weather.WeatherSettingsDialog
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
    private var switchPicker: SwitchConfigurationDialog? = null
    private data class SwitchSave(
        val picker: SwitchConfigurationDialog,
        val cardId: String,
        val configuration: String,
        val addPicker: CardPickerDialog?,
    )
    private var switchSave: SwitchSave? = null
    private var onOpenWeather: (WeatherCardConfiguration) -> Unit = {}
    private var weatherPicker: WeatherSourcePicker? = null
    private data class WeatherSave(
        val picker: WeatherSourcePicker,
        val action: WeatherCardNavigation,
        val configuration: String,
    )
    private var weatherSave: WeatherSave? = null
    private var weatherDetail: WeatherDetailDialog? = null
    private var weatherSettings: WeatherSettingsDialog? = null
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
    private var renderedCards: List<com.dormpanel.app.dashboard.model.PlacedCard>? = null

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
        renderedCards = null
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
            onWeatherAction = ::openWeather,
            onConfigureSwitch = ::configureSwitch,
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
        switchPicker?.dismiss(); switchPicker = null
        switchSave = null
        weatherSettings?.dismiss(); weatherSettings = null
        weatherSave = null
        weatherPicker?.dismiss(); weatherPicker = null
        weatherDetail?.dismiss(); weatherDetail = null
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
        if (renderedCards != state.cards) {
            renderedCards = state.cards
            gridView.submitCards(state.cards)
        }
        val storageError = state.storageError || ConfigurationSaveState.FAILED in state.configurationSaves.values
        message.visibility = if (!state.loaded || storageError) VISIBLE else GONE
        message.setText(
            when {
                !state.loaded -> R.string.dashboard_loading
                storageError -> R.string.dashboard_storage_error
                else -> R.string.dashboard_loading
            },
        )
        resolveWeatherSave(state)
        resolveSwitchSave(state)
    }

    private fun resolveSwitchSave(state: DashboardUiState) {
        val pending = switchSave ?: return
        if (switchPicker !== pending.picker || !pending.picker.isShowing ||
            (pending.addPicker != null && (picker !== pending.addPicker || !pending.addPicker.isShowing))) {
            switchSave = null
            return
        }
        val card = state.cards.firstOrNull { it.id == pending.cardId }
        val status = when {
            card?.configurationJson != pending.configuration -> R.string.switch_save_superseded
            state.configurationSaves[card.id] == ConfigurationSaveState.SAVING -> return
            state.configurationSaves[card.id] == ConfigurationSaveState.FAILED -> R.string.switch_save_failed
            else -> null
        }
        switchSave = null
        if (status == null) {
            pending.picker.dismiss()
            pending.addPicker?.dismiss()
        } else pending.picker.showSaveError(status)
    }

    private fun resolveWeatherSave(state: DashboardUiState) {
        val pending = weatherSave ?: return
        val card = state.cards.firstOrNull { it.id == pending.action.cardId }
        if (card?.configurationJson != pending.configuration) {
            weatherSave = null
            pending.picker.showRejected()
            return
        }
        when (state.configurationSaves[card.id]) {
            ConfigurationSaveState.SAVING -> Unit
            ConfigurationSaveState.FAILED -> {
                weatherSave = null
                pending.picker.showSaveState(inProgress = false, failed = true)
            }
            null -> {
                weatherSave = null
                val updated = WeatherCardConfiguration.decode(pending.configuration) as? WeatherCardConfiguration.Bound
                pending.picker.dismiss()
                openWeather(pending.action.copy(placeId = updated?.placeId, source = updated?.source))
            }
        }
    }

    private fun enterEditMode() {
        if (editing) return
        weatherDetail?.dismiss(); weatherDetail = null
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
            if (candidate.providerType.isWeatherCard() && backend != null) {
                selectWeather(WeatherCardConfiguration.Default) { weather, config ->
                    if (addCard(candidate.copy(configurationJson = config))) {
                        weather.dismiss()
                        picker?.dismiss()
                    } else weather.showRejected()
                }
                false
            } else if (candidate.providerType.isSwitchCard()) {
                configureNewSwitch(candidate, checkNotNull(picker))
                false
            } else addCard(candidate)
        }.also { it.show() }
    }

    private fun configureNewSwitch(candidate: CardAddCandidate, addPicker: CardPickerDialog) {
        val cardId = java.util.UUID.randomUUID().toString()
        var submitted = false
        showSwitchConfiguration(candidate.providerType, candidate.configurationJson) { dialog, config ->
            saveSwitch(SwitchSave(dialog, cardId, config, addPicker)) {
                if (submitted) stateHolder.updateConfiguration(cardId, config)
                else stateHolder.add(candidate.copy(configurationJson = config), cardId).also {
                    submitted = it is LayoutMutationResult.Success
                }
            }
        }
    }

    private fun configureSwitch(cardId: String) {
        if (!editing) return
        val card = stateHolder.state.cards.firstOrNull { it.id == cardId && it.providerType.isSwitchCard() } ?: return
        showSwitchConfiguration(card.providerType, card.configurationJson) { dialog, config ->
            saveSwitch(SwitchSave(dialog, cardId, config, null)) { stateHolder.updateConfiguration(cardId, config) }
        }
    }

    private fun saveSwitch(pending: SwitchSave, submit: () -> LayoutMutationResult) {
        pending.picker.showSaving()
        switchSave = pending
        val result = submit()
        if (switchSave !== pending) return
        if (result is LayoutMutationResult.Failure) {
            switchSave = null
            pending.picker.showSaveError(if (result.reason == LayoutFailureReason.NO_SPACE) R.string.dashboard_no_space
                else R.string.switch_save_superseded)
        } else resolveSwitchSave(stateHolder.state)
    }

    private fun showSwitchConfiguration(type: String, json: String, confirm: (SwitchConfigurationDialog, String) -> Unit) {
        val backend = backend ?: return
        switchPicker?.dismiss()
        switchSave = null
        switchPicker = SwitchConfigurationDialog(context, type, SwitchCardConfiguration.decode(json),
            { backend.state.switches }, confirm).also { it.show() }
    }

    private fun addCard(candidate: CardAddCandidate): Boolean {
        val result = stateHolder.add(candidate)
        if (result !is LayoutMutationResult.Failure) return true
        val message = if (result.reason == LayoutFailureReason.NO_SPACE) R.string.dashboard_no_space
            else R.string.dashboard_operation_rejected
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        return false
    }

    internal fun setWeatherNavigation(open: (WeatherCardConfiguration) -> Unit) { onOpenWeather = open }

    private fun openWeather(action: WeatherCardNavigation) {
        if (editing || !isAttachedToWindow) return
        val backend = backend ?: return
        val card = stateHolder.state.cards.firstOrNull { it.id == action.cardId && it.providerType.isWeatherCard() } ?: return
        val configuration = WeatherCardConfiguration.decode(card.configurationJson)
        val binding = configuration as? WeatherCardConfiguration.Bound
        if (action.placeId != binding?.placeId || action.source != binding?.source) return
        weatherDetail?.dismiss()
        if (action.destination == WeatherCardNavigation.Destination.WEATHER_CENTER) {
            onOpenWeather(configuration)
            return
        }
        val more = { openWeather(action.copy(destination = WeatherCardNavigation.Destination.WEATHER_CENTER)) }
        weatherDetail = WeatherDetailDialog(context, appearance, backend, backend.weatherRepository, configuration,
            changeBinding = { editWeather(action, configuration, card.configurationJson) },
            more = more,
            editDisplay = binding?.takeIf { it.canSave(backend.weatherRepository.places) }?.let {
                { editWeather(action, configuration, card.configurationJson, editDisplay = true) }
            },
        ).also { dialog ->
            dialog.setOnDismissListener { if (weatherDetail === dialog) weatherDetail = null }
            dialog.show()
        }
    }

    private fun editWeather(
        action: WeatherCardNavigation,
        initial: WeatherCardConfiguration,
        initialJson: String,
        editDisplay: Boolean = false,
    ) {
        val detail = weatherDetail
        selectWeather(initial, editDisplay, onDismissed = {
            val latest = stateHolder.state.cards.firstOrNull { it.id == action.cardId }
            if (latest?.configurationJson != initialJson && weatherDetail === detail) detail?.dismiss()
        }) { dialog, config ->
            dialog.showSaveState(inProgress = true)
            weatherSave = WeatherSave(dialog, action, config)
            if (stateHolder.updateConfiguration(action.cardId, config) is LayoutMutationResult.Failure) {
                weatherSave = null
                dialog.showRejected()
            } else resolveWeatherSave(stateHolder.state)
        }
    }

    private fun selectWeather(
        initial: WeatherCardConfiguration,
        editDisplay: Boolean = false,
        onDismissed: () -> Unit = {},
        confirm: (WeatherSourcePicker, String) -> Unit,
    ) {
        val backend = backend ?: return
        weatherPicker?.dismiss()
        weatherPicker = WeatherSourcePicker(context, appearance, backend.weatherRepository,
            haName = { source -> backend.weatherServices.haSources().firstOrNull { it.first == source }?.second },
            initial = initial,
            openSettings = {
                weatherSettings?.dismiss()
                weatherSettings = WeatherSettingsDialog(context, backend.weatherServices).also { it.show() }
            },
            editDisplay = editDisplay,
            confirm = { dialog, configuration ->
                when (configuration) {
                    WeatherCardConfiguration.Default -> confirm(dialog, "{}")
                    is WeatherCardConfiguration.Bound -> confirm(dialog, configuration.encode())
                    WeatherCardConfiguration.Invalid -> dialog.showRejected()
                }
            }).also { dialog ->
                dialog.setOnDismissListener {
                    if (weatherPicker === dialog) weatherPicker = null
                    if (weatherSave?.picker === dialog) weatherSave = null
                    onDismissed()
                }
                dialog.show()
            }
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
