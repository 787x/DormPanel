package com.dormpanel.app.weather

import android.content.Context
import android.text.InputType
import android.text.util.Linkify
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal class WeatherSettingsDialog(private val context: Context, private val weather: WeatherServices) {
    private val repository get() = weather.repository
    private val places get() = weather.places

    fun show() {
        val layout = column()
        val list = column()
        val status = label("")
        val dialog = dialog("天气服务与地点", layout)
        fun render() {
            status.text = if (weather.settings.config != null) "和风天气已配置" else weather.settings.problem
            list.removeAllViews()
            places.places.forEach { place ->
                val suffix = if (places.isTemporary(place.id)) " · 本次会话" else ""
                list.addView(button("${place.name} · ${place.zone.id}$suffix") { placeMenu(place.id) })
            }
        }
        layout.addView(status)
        layout.addView(row(button("和风服务配置") { service(::render) }, button("搜索城市或区县") { search() },
            button("添加地点") { edit(null) }))
        layout.addView(list)
        layout.addView(button("清除全部天气缓存") {
            confirm("清除全部天气缓存？") { repository.clearCache() }
        })
        render()
        val stop = places.observe(::render)
        dialog.setOnDismissListener { stop() }
    }

    private fun service(updated: () -> Unit) {
        val layout = column()
        val host = field("专属 API Host", weather.settings.host)
        val apiKey = CheckBox(context).apply { text = "使用 API Key 认证" }
        val kid = field("凭据 ID（kid）")
        val issuer = field("开发者 ID（iss）")
        val subject = field("项目 ID（sub）")
        val secret = field("Ed25519 PKCS#8 私钥").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            maxLines = 3
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
        }
        val limit = field("本机月请求上限", weather.budget.stats().limit.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val stats = label("")
        val status = label(if (weather.settings.config == null) weather.settings.problem else "已保存加密凭据")
        val dialog = dialog("和风天气服务", layout)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        var stopTest: (() -> Unit)? = null
        fun renderStats() {
            val now = weather.budget.stats()
            stats.text = "${now.month} UTC 本机请求 ${now.requests} / ${now.limit}\n" +
                "最近成功 ${now.lastSuccess ?: "—"}\n最近错误 ${now.lastError ?: "—"}"
        }
        apiKey.setOnCheckedChangeListener { _, checked ->
            listOf(kid, issuer, subject).forEach { it.visibility = if (checked) View.GONE else View.VISIBLE }
            secret.text.clear()
            secret.hint = if (checked) "API Key" else "Ed25519 PKCS#8 私钥"
        }
        listOf(host, apiKey, kid, issuer, subject, secret).forEach(layout::addView)
        layout.addView(row(button("保存凭据") {
            action(status) {
                val auth = if (apiKey.isChecked) QWeatherAuth.ApiKey(secret.value())
                else QWeatherAuth.Jwt(kid.value(), issuer.value(), subject.value(), secret.value())
                weather.configure(host.value(), auth)
                secret.text.clear()
                status.text = "已保存加密凭据"
                renderStats()
                updated()
            }
        }, button("测试已保存凭据") {
            stopTest?.invoke()
            status.text = "正在请求实时天气…"
            stopTest = weather.test { status.text = it; renderStats() }
        }, button("清除凭据") {
            confirm("清除和风凭据？") {
                action(status) {
                    weather.clearCredentials()
                    secret.text.clear()
                    status.text = "已清除天气凭据"
                    renderStats()
                    updated()
                }
            }
        }))
        layout.addView(status)
        layout.addView(row(limit, button("保存预算") {
            action(status) {
                weather.budget.setLimit(limit.value().toIntOrNull() ?: 0)
                repository.onConfigurationChanged(resetBudget = true)
                status.text = "本机预算已保存"
                renderStats()
            }
        }))
        layout.addView(stats)
        renderStats()
        dialog.setOnDismissListener { stopTest?.invoke(); secret.text.clear() }
    }

    private fun placeMenu(id: String) {
        val place = places.get(id) ?: return
        AlertDialog.Builder(context).setTitle(place.name).setItems(arrayOf("检查天气数据", "编辑地点", "清除此地点天气缓存", "删除地点")) { _, index ->
            when (index) {
                0 -> chooseSource(place)
                1 -> edit(place)
                2 -> confirm("清除该地点使用的来源缓存？") { repository.clearCache(id) }
                3 -> confirm("删除地点「${place.name}」？") { places.delete(id) }
            }
        }.setNegativeButton("取消", null).show()
    }

    private fun search() {
        val layout = column()
        val name = field("城市、区县或行政区域")
        val status = label("搜索结果可在本次会话中使用。长期保存请添加自定义地点。")
        val results = column()
        val dialog = dialog("搜索和风地点", layout)
        var stop: (() -> Unit)? = null
        layout.addView(row(name, button("搜索") {
            stop?.invoke()
            results.removeAllViews()
            action(status) {
                status.text = "正在查询…"
                stop = weather.search(name.value()) { cities, error ->
                    status.text = error ?: if (cities.isEmpty()) "未找到匹配地点" else "选择地点查看天气"
                    cities.forEach { city ->
                        results.addView(button("${city.name} · ${city.area}") { selectCity(city) })
                    }
                    cities.firstOrNull()?.let { results.addView(attribution(it.attribution)) }
                }
            }
        }))
        layout.addView(status)
        layout.addView(results)
        dialog.setOnDismissListener { stop?.invoke() }
    }

    private fun selectCity(city: WeatherServices.City) {
        val layout = column()
        val zone = field("地点时区（例如 Asia/Shanghai）", city.zone?.id.orEmpty())
        val status = label("此地点可使用到应用进程退出")
        val dialog = dialog(city.name, layout)
        layout.addView(zone)
        layout.addView(status)
        layout.addView(button("使用地点") {
            action(status) {
                val place = weather.select(city, zone.parseZone())
                dialog.dismiss()
                chooseSource(place)
            }
        })
    }

    private fun edit(place: WeatherPlace?) {
        val existingQ = place?.sources?.filterIsInstance<WeatherSourceRef.QWeather>()?.firstOrNull()
        val existingHa = place?.sources?.filterIsInstance<WeatherSourceRef.HomeAssistant>().orEmpty()
        val ha = (weather.haSources() + existingHa.map { it to it.entityId }).distinctBy { it.first }
        val layout = column()
        val name = field("地点名称", place?.name.orEmpty())
        val zone = field("地点时区（例如 Asia/Shanghai）", place?.zone?.id.orEmpty())
        val latitude = field("纬度 −90 至 90", existingQ?.coordinates?.latitude?.toPlainString().orEmpty())
        val longitude = field("经度 −180 至 180", existingQ?.coordinates?.longitude?.toPlainString().orEmpty())
        val locationId = field("历史天气 Location ID（可选）", existingQ?.locationId.orEmpty())
        val selections = ha.associate { (source, title) ->
            source to CheckBox(context).apply { text = "$title · ${source.entityId}"; isChecked = source in existingHa }
        }
        val status = label("")
        val dialog = dialog(if (place == null) "添加地点" else "编辑地点", layout)
        layout.addView(name)
        layout.addView(zone)
        layout.addView(label("和风天气坐标"))
        layout.addView(row(latitude, longitude))
        layout.addView(locationId)
        layout.addView(label("Home Assistant 天气实体"))
        if (ha.isEmpty()) layout.addView(label("HA 连接后可选择发现的天气实体"))
        selections.values.forEach(layout::addView)
        layout.addView(status)
        layout.addView(button("保存地点") {
            action(status) {
                val sources = selections.filterValues(CheckBox::isChecked).keys.toMutableList<WeatherSourceRef>()
                if (latitude.value().isNotEmpty() || longitude.value().isNotEmpty()) {
                    val lat = latitude.value().toBigDecimalOrNull()
                    val lon = longitude.value().toBigDecimalOrNull()
                    require(lat != null && lon != null && lat.abs() <= 90.toBigDecimal() && lon.abs() <= 180.toBigDecimal() &&
                        lat.scale() <= 20 && lon.scale() <= 20) { "请填写有效经纬度，最多 20 位小数" }
                    val id = locationId.value().ifEmpty { null }
                    require(id == null || id.matches(Regex("[A-Za-z0-9]{1,40}"))) { "Location ID 格式不正确" }
                    sources.add(0, WeatherSourceRef.QWeather(WeatherCoordinates(lat, lon), id))
                }
                require(name.value().isNotEmpty() && sources.isNotEmpty()) { "请填写地点名称并添加至少一个天气来源" }
                places.save(WeatherPlace(place?.id ?: UUID.randomUUID().toString(), name.value(), zone.parseZone(), sources))
                dialog.dismiss()
            }
        })
    }

    private fun chooseSource(place: WeatherPlace) {
        if (place.sources.size == 1) inspect(place, place.sources.single())
        else AlertDialog.Builder(context).setTitle("选择天气来源")
            .setItems(place.sources.map(::sourceName).toTypedArray()) { _, index -> inspect(place, place.sources[index]) }
            .setNegativeButton("取消", null).show()
    }

    private fun inspect(place: WeatherPlace, source: WeatherSourceRef) {
        val layout = column()
        val dialog = dialog("${place.name} · ${sourceName(source)}", layout)
        val data = column()
        val requests = mutableListOf<WeatherRepository.Subscription>()
        val status = label("")
        val dates = field("专项日期 YYYY-MM-DD（选填）")
        val choices = WeatherBlock.entries
        val select = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, choices.map(::blockName))
        }
        val details = label("").apply { autoLinkMask = Linkify.WEB_URLS; linksClickable = true }
        var extra: WeatherRepository.Subscription? = null
        layout.addView(row(select, dates, button("请求") {
            action(status) {
                val block = choices[select.selectedItemPosition]
                val date = dates.value().ifEmpty { null }?.let {
                    runCatching { LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("日期格式应为 YYYY-MM-DD") }
                }
                extra?.close()
                extra = repository.observe(place.id, source, WeatherQuery(block, date = date), automatic = false) {
                    details.text = snapshotText(it)
                }.also { it.refresh() }
                status.text = ""
            }
        }))
        layout.addView(status)
        layout.addView(details)
        layout.addView(button("清除此地点天气缓存") { repository.clearCache(place.id) })
        layout.addView(data)
        choices.forEach { block ->
            val result = label("").apply { autoLinkMask = Linkify.WEB_URLS; linksClickable = true }
            data.addView(result)
            requests += repository.observe(place.id, source, WeatherQuery(block)) { result.text = snapshotText(it) }
        }
        dialog.setOnDismissListener { extra?.close(); requests.forEach { it.close() } }
    }

    private fun snapshotText(snapshot: WeatherSnapshot): String = buildString {
        append(blockName(snapshot.query.block)).append(" · ").append(stateName(snapshot.state))
        if (snapshot.stale) append(" · 上次缓存")
        if (snapshot.refreshing && snapshot.state != WeatherState.LOADING) append(" · 更新中")
        val dateFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss z").withZone(snapshot.zone)
        snapshot.fetchedAt?.let { append(" · 获取于 ").append(dateFormat.format(it)) }
        snapshot.dataAt?.let { append(" · 数据时间 ").append(dateFormat.format(it)) }
        snapshot.error?.let { append("\n").append(it) }
        when (val data = snapshot.data) {
            is WeatherData.Current -> {
                val c = data.conditions
                append("\n").append(c.description ?: c.code ?: "—")
                c.temperature?.let { append(" · ").append(it).append(" °C") }
                c.feelsLike?.let { append(" · 体感 ").append(it).append(" °C") }
                c.humidity?.let { append(" · 湿度 ").append("%.0f".format(it)).append("%") }
                c.windSpeed?.let { append(" · 风速 ").append(it).append(" m/s") }
            }
            is WeatherData.Forecast -> append(" · ").append(data.entries.size).append(" 条")
            is WeatherData.Alerts -> {
                if (data.entries.isEmpty()) append(" · 无有效预警")
                data.entries.forEach { append("\n").append(listOfNotNull(it.headline, it.sender).joinToString(" · ")) }
                data.upcoming.forEach { append("\n即将生效 · ").append(listOfNotNull(it.headline, it.sender).joinToString(" · ")) }
            }
            is WeatherData.Air -> append("\n").append(data.entries.firstOrNull()?.indexes.orEmpty().joinToString(" · ") {
                listOfNotNull(it.name, it.display, it.category).joinToString(" ")
            })
            is WeatherData.Minutely -> append("\n").append(data.summary.orEmpty()).append(" · ").append(data.entries.size).append(" 条")
            is WeatherData.Indices -> append("\n").append(data.entries.take(3).joinToString(" · ") {
                listOfNotNull(it.name, it.category).joinToString(" ")
            })
            is WeatherData.Astronomy -> append("\n").append(data.value.events.entries.take(2).joinToString(" · ") {
                dateFormat.format(it.value)
            })
            is WeatherData.History -> append(" · ").append(data.hourly.size).append(" 小时")
            null -> Unit
        }
        val attribution = attributionText(snapshot.attribution)
        if (attribution.isNotEmpty()) append("\n").append(attribution)
    }

    private fun sourceName(source: WeatherSourceRef) = when (source) {
        is WeatherSourceRef.QWeather -> "和风天气"
        is WeatherSourceRef.HomeAssistant -> source.entityId
    }

    private fun blockName(block: WeatherBlock) = when (block) {
        WeatherBlock.CURRENT -> "实时天气"
        WeatherBlock.HOURLY -> "小时预报"
        WeatherBlock.DAILY -> "每日预报"
        WeatherBlock.TWICE_DAILY -> "半日预报"
        WeatherBlock.ALERTS -> "天气预警"
        WeatherBlock.AIR_CURRENT -> "实时空气质量"
        WeatherBlock.AIR_HOURLY -> "小时空气质量"
        WeatherBlock.AIR_DAILY -> "每日空气质量"
        WeatherBlock.MINUTELY -> "分钟降水"
        WeatherBlock.INDICES -> "生活指数"
        WeatherBlock.SUN -> "日出日落"
        WeatherBlock.MOON -> "月升月落与月相"
        WeatherBlock.HISTORY -> "近期历史天气"
    }

    private fun stateName(state: WeatherState) = when (state) {
        WeatherState.UNLOADED -> "未加载"
        WeatherState.LOADING -> "正在加载"
        WeatherState.FRESH -> "有效"
        WeatherState.STALE -> "待更新"
        WeatherState.EMPTY -> "服务无数据"
        WeatherState.UNSUPPORTED -> "不支持"
        WeatherState.UNCONFIGURED -> "未配置"
        WeatherState.UNAUTHORIZED -> "认证或权限失败"
        WeatherState.BUDGET -> "达到预算"
        WeatherState.ERROR -> "暂时失败"
    }

    private fun attributionText(value: WeatherAttribution) =
        (value.sources + value.statements + value.licenses + listOfNotNull(value.link)).distinct().joinToString("\n")

    private fun attribution(value: WeatherAttribution) =
        label(attributionText(value)).apply { autoLinkMask = Linkify.WEB_URLS; linksClickable = true }

    private fun dialog(title: String, content: LinearLayout) = AlertDialog.Builder(context).setTitle(title)
        .setView(ScrollView(context).apply {
            isFillViewport = true
            addView(content.apply { setPadding(dp(20), dp(8), dp(20), dp(8)) })
        }).setNegativeButton("关闭", null).show().also { it.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }

    private fun confirm(title: String, action: () -> Unit) = AlertDialog.Builder(context).setTitle(title)
        .setPositiveButton("确认") { _, _ -> action() }.setNegativeButton("取消", null).show()

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row(vararg views: View) = LinearLayout(context).apply {
        views.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }
    private fun label(value: String) = TextView(context).apply {
        text = value
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }
    private fun button(title: String, action: () -> Unit) = Button(context).apply {
        text = title
        minimumHeight = dp(48)
        isAllCaps = false
        setOnClickListener { action() }
    }
    private fun field(label: String, value: String = "") = EditText(context).apply {
        hint = label
        contentDescription = label
        setText(value)
        setSingleLine()
    }
    private fun EditText.value() = text.toString().trim()
    private fun EditText.parseZone(): ZoneId = runCatching { ZoneId.of(value()) }
        .getOrElse { throw IllegalArgumentException("请输入有效时区，例如 Asia/Shanghai") }
    private fun action(status: TextView, action: () -> Unit) {
        try {
            action()
        } catch (e: IllegalArgumentException) {
            status.text = e.message ?: "输入无效"
        } catch (_: Exception) {
            status.text = "无法完成操作，请检查设置或存储"
        }
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
