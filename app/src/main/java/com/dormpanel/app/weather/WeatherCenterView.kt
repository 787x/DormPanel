package com.dormpanel.app.weather

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.appearance.AppearanceAware
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.dashboard.card.*
import com.dormpanel.app.data.DashboardData
import com.dormpanel.app.ha.BackendMode
import com.dormpanel.app.ha.DashboardBackend
import com.dormpanel.app.ui.PageInteraction
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal class WeatherCenterView(
    context: Context,
    private val backend: DashboardBackend,
    appearance: AppearanceController,
    initial: WeatherCenterSelection,
    private val back: () -> Unit,
    private val repository: WeatherRepository = backend.weatherRepository,
    private val now: () -> Instant = Instant::now,
    openPortal: (() -> Unit)? = null,
    private val closePortal: () -> Unit = {},
) : LinearLayout(context), PageInteraction, AppearanceAware {
    private enum class Section(val title: String) {
        FORECAST("天气预报"), RAIN("分钟降雨"), AIR("空气质量"), INDICES("生活指数"), SKY("日月天文"), HISTORY("历史天气"),
    }

    private val places = repository.places
    private val settings = WeatherSettingsDialog(context, backend.weatherServices, ::openPlace, openPortal)
    private val ui = WeatherCenterContent(context, PanelPalette.forMode(appearance.state.themeMode))
    private val sidebar = ui.column()
    private val cityRows = ui.column()
    private val cityScroll = ScrollView(context).apply {
        tag = "weather_city_list"
        addView(cityRows)
        cityRows.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) schedulePreviews()
        }
        setOnScrollChangeListener { _, _, _, _, _ -> schedulePreviews() }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedulePreviews() }
    }
    private data class CityRow(val place: WeatherPlace, val item: LinearLayout, val summary: TextView, val icon: WeatherIconView)
    private val cities = linkedMapOf<String, CityRow>()
    private val previews = linkedMapOf<Pair<String, WeatherSourceRef>, WeatherRepository.Subscription>()
    private val updatePreviews = Runnable { bindPreviews(add = true) }
    private val trimPreviews = Runnable { bindPreviews(add = false) }
    private val detail = ui.column()
    private val scroll = ScrollView(context).apply { addView(detail); isFillViewport = true }
    private val stops = linkedMapOf<WeatherQuery, WeatherRepository.Subscription>()
    private val snapshots = mutableMapOf<WeatherQuery, WeatherSnapshot>()
    private val sections = mutableMapOf<WeatherBlock, LinearLayout>()
    private val popups = linkedSetOf<AlertDialog>()
    private var alertPopup: AlertDialog? = null
    private var generation = 0
    private var stopPlaces: (() -> Unit)? = null
    private var listeningLegacy = false
    private var section = Section.FORECAST
    private var range = 24
    private var hourlyRequestRange = 24
    private var hourlyMetric = HourlyMetric.TEMPERATURE
    private val rangeButtons = linkedMapOf<Int, Button>()
    private var historyDate: LocalDate? = null
    private var requestedHistory: LocalDate? = null
    private var shownPlace: WeatherPlace? = initial.selectedPlace(places)
    private var active = true
    private lateinit var header: LinearLayout
    private lateinit var hero: LinearLayout
    private lateinit var refreshFeedback: TextView
    private val current get() = snapshots.values.firstOrNull { it.query.block == WeatherBlock.CURRENT }
    private val localBindingUnavailable get() = selection.isUnavailable(places)
    private val haBindingUnavailable get() = selection.source is WeatherSourceRef.HomeAssistant &&
        current?.state == WeatherState.UNSUPPORTED
    private val bindingUnavailable get() = localBindingUnavailable || haBindingUnavailable
    private val daily get() = snapshots.values.firstOrNull { it.query.block == WeatherBlock.DAILY }
    private val zone get() = shownPlace?.zone ?: ZoneId.systemDefault()
    private val legacyListener: (DashboardData) -> Unit = { renderLegacy(it) }

    var selection = initial.resolve(places)
        private set

    init {
        orientation = HORIZONTAL
        buildSidebar()
        addView(sidebar, LayoutParams(0, -1, 1f))
        addView(scroll, LayoutParams(0, -1, 3.1f))
        detail.setPadding(ui.dp(16), ui.dp(12), ui.dp(20), ui.dp(20))
        applyAppearance(appearance.state)
    }

    override fun shouldObservePageSwipe(event: MotionEvent) = false

    override fun handleBack(): Boolean {
        back()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopPlaces = places.observe { select(selection) }
        buildPage()
    }

    override fun onDetachedFromWindow() {
        closePortal()
        stopPlaces?.invoke()
        stopPlaces = null
        settings.dismiss()
        popups.toList().forEach(AlertDialog::dismiss)
        release()
        super.onDetachedFromWindow()
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (active) {
            subscribe()
            schedulePreviews()
        } else {
            requestedHistory = null
            release(clear = false)
        }
    }

    override fun applyAppearance(state: AppearanceState) {
        ui.palette = PanelPalette.forMode(state.themeMode)
        setBackgroundColor(ui.palette.background)
        if (isAttachedToWindow) {
            buildSidebar()
            buildPage()
        }
    }

    private fun buildSidebar() = with(ui) {
        sidebar.removeAllViews()
        sidebar.setPadding(dp(12), dp(12), dp(8), dp(12))
        sidebar.addView(row(button("‹ 返回", back), label("天气中心", 23f, bold = true)))
        sidebar.addView(button("搜索城市或区县", settings::search))
        sidebar.addView(button("添加地点") { settings.edit(null) })
        sidebar.addView(cityScroll, LayoutParams(-1, 0, 1f))
        sidebar.addView(button("天气服务与地点", settings::show))
    }

    private fun openPlace(place: WeatherPlace) {
        settings.dismiss()
        select(WeatherCenterSelection(place.id))
    }

    private fun select(next: WeatherCenterSelection) {
        val resolved = next.resolve(places)
        val place = resolved.selectedPlace(places)
        if (resolved == selection && shownPlace?.zone == place?.zone) {
            shownPlace = place
            renderSidebar()
            renderHeader()
            return
        }
        alertPopup?.dismiss()
        release(includePreviews = resolved.isUnavailable(places))
        selection = resolved
        shownPlace = place
        if (section != Section.SKY || resolved.source == null || resolved.source is WeatherSourceRef.HomeAssistant)
            section = Section.FORECAST
        range = 24
        hourlyRequestRange = 24
        hourlyMetric = HourlyMetric.TEMPERATURE
        historyDate = null
        requestedHistory = null
        buildPage()
        scroll.scrollTo(0, 0)
    }

    private fun renderSidebar() = with(ui) {
        cityRows.removeAllViews()
        cities.clear()
        places.orderedPlaces.forEach { place ->
            val selected = !bindingUnavailable && place.id == selection.placeId
            val reading = when {
                bindingUnavailable -> null
                selected -> current
                else -> repository.cachedCurrent(place.id, place.sources.first())
            }
            val summary = label("", 15f, subdued = true)
            val glyph = icon(sky(null), 34)
            val caption = column().apply {
                addView(label((if (place.favorite) "★ " else "") + place.name, 20f, bold = selected))
                addView(summary)
            }
            val item = row(glyph).apply {
                addView(caption, LayoutParams(0, -2, 1f))
                minimumHeight = dp(68)
                isFocusable = true
                background = surface(if (selected) palette.accent else palette.surface, selected)
                setOnClickListener {
                    val source = selection.source?.takeIf { !bindingUnavailable && it in place.sources } ?: place.sources.first()
                    select(WeatherCenterSelection(place.id, source))
                }
            }
            val city = CityRow(place, item, summary, glyph)
            cities[place.id] = city
            renderCity(city, reading)
            cityRows.addView(item, LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        cityRows.addView(button(if (selection.legacy) "✓ 默认天气" else "默认天气") {
            select(WeatherCenterSelection(legacy = true))
        })
        schedulePreviews()
    }

    private fun renderCity(city: CityRow, reading: WeatherSnapshot?) {
        val conditions = reading.conditions()
        val sky = sky(conditions?.code, conditions?.description)
        city.icon.symbol = sky.symbol
        val status = reading?.let {
            if (it.state == WeatherState.UNLOADED && it.network != WeatherNetworkStatus.OFFLINE) "等待刷新"
            else weatherStatus(it)
        }
        val summary = if (conditions != null) {
            listOfNotNull(conditions.temperature.value("°C"), sky.description,
                status?.takeUnless { it == "最新" }).joinToString(" · ")
        } else if (bindingUnavailable) "选择地点" else status ?: if (places.isTemporary(city.place.id)) "临时预览" else "待加载"
        city.summary.text = summary
        val selectionLabel = if (!bindingUnavailable && city.place.id == selection.placeId) "已选中 · " else ""
        city.item.contentDescription = "$selectionLabel${city.place.name} · $summary"
    }

    private fun schedulePreviews() {
        cityScroll.removeCallbacks(trimPreviews)
        cityScroll.removeCallbacks(updatePreviews)
        if (bindingUnavailable) {
            val closing = previews.values.toList()
            previews.clear()
            repository.closeSubscriptions(closing)
            return
        }
        cityScroll.postOnAnimation(trimPreviews)
        cityScroll.postDelayed(updatePreviews, 120)
    }

    private fun bindPreviews(add: Boolean) {
        if (!active || !isAttachedToWindow || bindingUnavailable) return
        val top = cityScroll.scrollY
        val bottom = top + cityScroll.height
        val visible = cities.values.filter { city ->
            city.place.id != selection.placeId && !places.isTemporary(city.place.id) &&
                city.item.bottom > top && city.item.top < bottom
        }.associateBy { it.place.id to it.place.sources.first() }
        repository.closeSubscriptions((previews.keys - visible.keys).mapNotNull(previews::remove))
        if (!add) return
        (visible.keys - previews.keys).forEach { key ->
            val (id, source) = key
            previews[key] = repository.observeCurrentPreview(id, source) { snapshot ->
                if (!bindingUnavailable && id != selection.placeId) cities[id]?.takeIf { it.place.sources.first() == source }
                    ?.let { renderCity(it, snapshot) }
            }
        }
    }

    private fun buildPage(): Unit = with(ui) {
        detail.removeAllViews()
        sections.clear()
        rangeButtons.clear()
        shownPlace = selection.selectedPlace(places)
        header = column()
        detail.addView(header)
        hero = card()
        detail.addView(hero)
        refreshFeedback = label("", 15f, subdued = true).apply { visibility = GONE }
        renderSidebar()
        renderHeader()
        when {
            selection.legacy -> renderLegacy(backend.state)
            localBindingUnavailable -> renderUnavailable()
            shownPlace == null -> hero.addView(label("搜索城市或添加经纬度地点，即可查看天气。", 21f))
            else -> {
                if (selection.source !is WeatherSourceRef.HomeAssistant) {
                    addBlock(WeatherBlock.ALERTS)
                    val alert = sections.getValue(WeatherBlock.ALERTS)
                    detail.removeView(alert)
                    detail.addView(alert, 1)
                }
                renderHero()
                val tabs = row()
                Section.entries.filter { section ->
                    selection.source !is WeatherSourceRef.HomeAssistant || section == Section.FORECAST
                }.forEach { item ->
                    tabs.addView(button(item.title, selected = section == item) {
                        if (section != item) {
                            section = item
                            requestedHistory = null
                            buildPage()
                            scroll.scrollTo(0, 0)
                        }
                    }, LayoutParams(0, -2, 1f).apply { marginEnd = dp(4); bottomMargin = dp(6) })
                }
                detail.addView(tabs)
                if (section == Section.FORECAST) detail.addView(row().apply {
                    listOf(24, 48, 72, 240).forEach { hours ->
                        val control = button(if (hours == 240) "最多 240 小时" else "$hours 小时", selected = range == hours) {
                            selectRange(hours)
                        }
                        rangeButtons[hours] = control
                        addView(control, LayoutParams(0, -2, 1f).apply { marginEnd = dp(4); bottomMargin = dp(6) })
                    }
                })
                if (section == Section.HISTORY) detail.addView(button(historyDate?.format(centerDate) ?: "选择历史日期") {
                    val dates = (1L..10).map { LocalDate.now(zone).minusDays(it) }
                    show(ui.dialogBuilder().setTitle("选择历史日期")
                        .setItems(dates.map { it.format(centerDate) }.toTypedArray()) { _, index ->
                            historyDate = dates[index]
                            requestedHistory = null
                            buildPage()
                        })
                })
                val visible = when (section) {
                    Section.FORECAST -> listOf(WeatherBlock.HOURLY, WeatherBlock.DAILY) +
                        listOf(WeatherBlock.TWICE_DAILY).filter { selection.source is WeatherSourceRef.HomeAssistant }
                    Section.SKY -> listOf(WeatherBlock.SUN, WeatherBlock.MOON)
                    else -> queries().map { it.block }.filterNot { it in core }
                }
                visible.forEach(::addBlock)
                if (section == Section.SKY) renderBlock(WeatherBlock.DAILY, daily)
            }
        }
        subscribe()
    }

    private fun selectRange(hours: Int) {
        if (range == hours) return
        val previous = snapshots.values.firstOrNull { it.query.block == WeatherBlock.HOURLY }
        range = hours
        val ha = selection.source is WeatherSourceRef.HomeAssistant
        if (!ha && (hours > hourlyRequestRange || previous.entries().isEmpty())) hourlyRequestRange = hours
        rangeButtons.forEach { (value, button) -> ui.markSelected(button, value == hours) }
        renderBlock(WeatherBlock.HOURLY, previous)
        subscribe()
    }

    private fun renderUnavailable() = with(ui) {
        hero.removeAllViews()
        hero.addView(label("原地点或来源已失效", 22f, bold = true))
        hero.addView(label("请从左侧重新选择地点。桌面卡片可长按后用「切换天气」重新绑定。", 17f))
    }

    private fun renderHeader() = with(ui) {
        if (!::header.isInitialized) return
        header.removeAllViews()
        val source = selection.source
        val place = shownPlace
        val title = (place ?: selection.placeId?.let(places::get))?.name
            ?: if (selection.legacy) "默认天气" else "选择天气地点"
        val titleView = label(title, 31f, bold = true)
        val actions = row()
        actions.addView(titleView, LayoutParams(0, -2, 1f))
        if (place != null && source != null) {
            actions.addView(button("地点管理") { settings.showPlace(place.id) }, LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            if (source !is WeatherSourceRef.HomeAssistant) {
                actions.addView(button("刷新可见天气", action = ::refresh), LayoutParams(-2, -2))
            }
        }
        header.addView(actions)
        if (source != null && place != null) {
            header.addView(row().apply {
                addView(button(weatherSourceName(source)) { chooseSource(place) }, LayoutParams(-2, -2))
                addView(label(place.zone.id, 16f, subdued = true), LayoutParams(0, -2, 1f))
                if (source !is WeatherSourceRef.HomeAssistant) {
                    addView(button("和风天气官网") { header.openWeatherLink(QWEATHER_WEBSITE) }, LayoutParams(-2, -2))
                }
            })
        }
        if (::refreshFeedback.isInitialized) {
            (refreshFeedback.parent as? LinearLayout)?.removeView(refreshFeedback)
            header.addView(refreshFeedback)
        }
    }

    private fun chooseSource(place: WeatherPlace) {
        val labels = place.sources.map { source ->
            val snapshot = repository.cachedCurrent(place.id, source)
            val status = snapshot?.let(::weatherStatus) ?: when (repository.capabilities(place.id, source).blocks[WeatherBlock.CURRENT]) {
                WeatherState.UNCONFIGURED -> "未配置"
                WeatherState.UNAUTHORIZED -> "认证或权限失败"
                WeatherState.UNSUPPORTED -> "不支持"
                WeatherState.BUDGET -> "请求受限"
                WeatherState.ERROR -> "暂不可用"
                else -> "选择后查看"
            }
            listOfNotNull(weatherSourceName(source), status,
                snapshot?.fetchedAt?.atZone(place.zone)?.format(weatherUpdateFormat)).joinToString(" · ")
        }
        show(ui.dialogBuilder().setTitle("选择查看来源").setSingleChoiceItems(
            labels.toTypedArray(), place.sources.indexOf(selection.source),
        ) { dialog, index ->
            dialog.dismiss()
            select(WeatherCenterSelection(place.id, place.sources[index]))
        })
    }

    private fun addBlock(block: WeatherBlock) {
        sections[block] = ui.card().apply { tag = block }.also(detail::addView)
        renderBlock(block, snapshots.values.firstOrNull { it.query.block == block })
    }

    private val core get() = setOf(WeatherBlock.CURRENT, WeatherBlock.DAILY, WeatherBlock.ALERTS)

    private fun queries(): List<WeatherQuery> = buildList {
        val ha = selection.source is WeatherSourceRef.HomeAssistant
        add(WeatherQuery(WeatherBlock.CURRENT))
        add(WeatherQuery(WeatherBlock.DAILY, days = if (ha) 7 else 10))
        if (!ha) add(WeatherQuery(WeatherBlock.ALERTS))
        when (section) {
            Section.FORECAST -> {
                add(WeatherQuery(WeatherBlock.HOURLY, hours = if (ha) 24 else hourlyRequestRange))
                if (ha) add(WeatherQuery(WeatherBlock.TWICE_DAILY))
            }
            Section.RAIN -> add(WeatherQuery(WeatherBlock.MINUTELY))
            Section.AIR -> listOf(WeatherBlock.AIR_CURRENT, WeatherBlock.AIR_HOURLY, WeatherBlock.AIR_DAILY)
                .forEach { add(WeatherQuery(it)) }
            Section.INDICES -> add(WeatherQuery(WeatherBlock.INDICES, indexTypes = setOf(1, 2, 3, 4, 5, 7)))
            Section.SKY -> astronomyBlocks.filter(::needsAstronomy).forEach { add(WeatherQuery(it)) }
            Section.HISTORY -> historyDate?.let { add(WeatherQuery(WeatherBlock.HISTORY, date = it)) }
        }
    }

    private fun subscribe() {
        removeCallbacks(astronomyMidnight)
        if (!active || !isAttachedToWindow) return
        if (selection.legacy) {
            if (!listeningLegacy) {
                listeningLegacy = true
                backend.addListener(legacyListener)
            }
            return
        }
        val place = shownPlace ?: return
        val source = selection.source ?: return
        val token = generation
        val desired = queries()
        if (section == Section.SKY) {
            val instant = now()
            val midnight = instant.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
            postDelayed(astronomyMidnight, Duration.between(instant, midnight).toMillis().coerceAtLeast(1))
        }
        repository.closeSubscriptions((stops.keys - desired.toSet()).mapNotNull { query ->
            snapshots.remove(query)
            stops.remove(query)
        })
        desired.filterNot { it in stops }.forEach { query ->
            val sub = repository.observe(place.id, source, query, automatic = query.block != WeatherBlock.HISTORY) { snapshot ->
                if (token != generation || query !in queries()) return@observe
                val previous = snapshots.put(query, snapshot)
                if (query.block == WeatherBlock.ALERTS && previous?.data != snapshot.data) alertPopup?.dismiss()
                when (query.block) {
                    WeatherBlock.CURRENT -> {
                        renderHero()
                        val wasUnavailable = source is WeatherSourceRef.HomeAssistant && previous?.state == WeatherState.UNSUPPORTED
                        if (wasUnavailable != haBindingUnavailable) {
                            renderSidebar()
                            sections.keys.toList().forEach { block ->
                                renderBlock(block, snapshots.values.firstOrNull { it.query.block == block })
                            }
                        } else if (!haBindingUnavailable) cities[place.id]?.let { renderCity(it, snapshot) }
                    }
                    WeatherBlock.DAILY -> {
                        renderHero()
                        renderBlock(query.block, snapshot)
                        if (section == Section.SKY) post { if (token == generation) subscribe() }
                    }
                    else -> renderBlock(query.block, snapshot)
                }
            }
            stops[query] = sub
            if (query.block == WeatherBlock.HISTORY && requestedHistory != query.date) {
                post {
                    if (token == generation && stops[query] === sub && requestedHistory != query.date) {
                        requestedHistory = query.date
                        if (snapshots[query]?.data == null) feedback(centerRefreshText(sub.refresh(), zone))
                    }
                }
            }
        }
    }

    private fun release(clear: Boolean = true, includePreviews: Boolean = true) {
        generation++
        removeCallbacks(astronomyMidnight)
        cityScroll.removeCallbacks(updatePreviews)
        cityScroll.removeCallbacks(trimPreviews)
        val closing = stops.values.toList() + if (includePreviews) previews.values.toList() else emptyList()
        stops.clear()
        if (includePreviews) previews.clear()
        repository.closeSubscriptions(closing)
        if (clear) snapshots.clear()
        if (listeningLegacy) backend.removeListener(legacyListener)
        listeningLegacy = false
    }

    private fun refresh() {
        feedback(stops.entries.joinToString("\n") { (query, subscription) ->
            "${ui.blockTitle(query.block)} · ${centerRefreshText(subscription.refresh(), zone)}"
        })
    }

    private fun feedback(message: String) {
        refreshFeedback.text = message
        refreshFeedback.visibility = if (message.isEmpty()) GONE else VISIBLE
    }

    private fun renderHero() = with(ui) {
        if (haBindingUnavailable) {
            renderUnavailable()
            hero.addView(metadata(current))
            return
        }
        val c = current.conditions()
        val today = daily.today(Instant.now())?.conditions
        renderCurrent(hero, c, range(today?.minTemperature, today?.maxTemperature, "°C"))
        hero.addView(metadata(current))
    }

    private fun renderLegacy(data: DashboardData) = with(ui) {
        if (!::hero.isInitialized) return
        val old = data.weather
        val c = old.conditions ?: WeatherConditions(code = old.condition,
            temperature = old.temperature?.toDouble()?.takeIf { old.temperatureUnit == "°C" },
            humidity = old.humidity?.toDouble())
        renderCurrent(hero, c, range(old.low, old.high, old.temperatureUnit), old.temperature.value(old.temperatureUnit))
        hero.addView(label(listOfNotNull(old.name, if (backend.settings.mode == BackendMode.DEMO) "演示天气"
            else "Home Assistant · 默认天气", old.status(),
            old.updatedAt?.atZone(zone)?.format(weatherUpdateFormat)).joinToString(" · "), 15f, subdued = true))
        val lines = old.lines(Instant.now(), 10)
        lines.forEach { item -> hero.addView(row(icon(item.sky, 32),
            label(listOfNotNull(item.label, item.sky.description, item.temperature, item.rain).joinToString(" · "), 17f))) }
    }

    private fun renderBlock(block: WeatherBlock, snapshot: WeatherSnapshot?) {
        if (block == WeatherBlock.DAILY && section == Section.SKY) {
            astronomyBlocks.forEach { target -> renderBlock(target, snapshots.values.firstOrNull { it.query.block == target }) }
            return
        }
        val box = sections[block] ?: return
        if (haBindingUnavailable) {
            box.removeAllViews()
            box.visibility = GONE
            return
        }
        if (block in astronomyBlocks) {
            ui.renderAstronomy(box, block, centerAstronomy(block, daily, snapshot, now(), zone), zone)
            return
        }
        ui.render(box, block, snapshot, range, zone, hourlyMetric, { hourlyMetric = it }) { builder ->
            val popup = show(builder)
            if (block == WeatherBlock.ALERTS) alertPopup = popup
        }
    }

    private val astronomyBlocks = listOf(WeatherBlock.SUN, WeatherBlock.MOON)
    // A stale Daily snapshot can stay identical across midnight while offline.
    private val astronomyMidnight = Runnable {
        if (active && isAttachedToWindow && section == Section.SKY) {
            renderBlock(WeatherBlock.DAILY, daily)
            subscribe()
        }
    }
    private fun needsAstronomy(block: WeatherBlock): Boolean {
        val info = centerAstronomy(block, daily, null, now(), zone)
        // Once started, a supplement stays useful while the daily request is retrying.
        return info.needsSupplement || (info.waitingForDaily && WeatherQuery(block) in stops)
    }

    private fun show(builder: AlertDialog.Builder): AlertDialog {
        val popup = builder.setNegativeButton("关闭", null).show()
        popups += popup
        popup.setOnDismissListener {
            popups -= popup
            if (alertPopup === popup) alertPopup = null
        }
        return popup
    }
}
