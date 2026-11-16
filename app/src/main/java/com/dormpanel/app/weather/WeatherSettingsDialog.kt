package com.dormpanel.app.weather

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputType
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.text.TextWatcher
import android.text.Editable
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal class WeatherSettingsDialog(
    private val context: Context,
    private val weather: WeatherServices,
    private val onPlaceSelected: ((WeatherPlace) -> Unit)? = null,
    private val openPortal: (() -> Unit)? = null,
) {
    private val repository get() = weather.repository
    private val places get() = weather.places

    private val dialogs = linkedSetOf<AlertDialog>()

    fun dismiss() {
        dialogs.toList().asReversed().forEach { dialog ->
            dialog.window?.peekDecorView()?.hideFormIme()
            dialog.dismiss()
        }
        dialogs.clear()
    }

    fun show() {
        val layout = column()
        val list = column()
        val status = label("")
        fun render() {
            status.text = if (weather.settings.config != null) "和风天气已配置" else weather.settings.problem
            list.removeAllViews()
            places.places.forEach { place ->
                val suffix = if (places.isTemporary(place.id)) " · 临时预览" else ""
                list.addView(button("${place.name} · ${place.zone.id}$suffix") { placeMenu(place.id) })
            }
        }
        layout.addView(status)
        layout.addView(row(button("和风服务配置") { service(::render) }, button("搜索城市或区县") { search() },
            button("添加地点") { edit(null) }))
        openPortal?.let { layout.addView(button("扫码配置") { dismiss(); it() }) }
        layout.addView(list)
        layout.addView(button("清除全部离线缓存") { confirmOfflineClear(null) })
        render()
        val dialog = dialog("天气服务与地点", layout)
        val stop = places.observe(::render)
        dialog.onDismiss { stop() }
    }

    private fun service(updated: () -> Unit) {
        val layout = column()
        val host = field("专属 API Host", weather.settings.host)
            .apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val savedAuth = weather.settings.config?.auth
        val savedJwt = savedAuth as? QWeatherAuth.Jwt
        val apiKey = CheckBox(context).apply {
            text = "使用 API Key 认证"
            isChecked = savedAuth is QWeatherAuth.ApiKey
        }
        val kid = field("凭据 ID（kid）", savedJwt?.kid.orEmpty())
        val issuer = field("开发者 ID（iss）", savedJwt?.issuer.orEmpty())
        val subject = field("项目 ID（sub）", savedJwt?.subject.orEmpty())
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
        val status = label(weather.credentialStatus)
        val keyStatus = label("")
        val keyChoice = Spinner(context).apply { contentDescription = "JWT 签名密钥" }
        var keys = emptyList<QWeatherSigningKey>()
        fun renderKeys(usePending: Boolean = false) {
            val settings = weather.settings
            keys = listOfNotNull(settings.activeKey, settings.pendingKey)
            keyChoice.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                keys.map { if (it === settings.activeKey) "当前凭据密钥" else "新密钥（等待注册公钥）" })
            keyChoice.visibility = if (keys.isEmpty() || apiKey.isChecked) View.GONE else View.VISIBLE
            if (usePending) keyChoice.setSelection(keys.lastIndex.coerceAtLeast(0))
            keyStatus.text = when {
                settings.keyUnavailable -> "待注册密钥不可用，请重新生成"
                settings.pendingKey != null -> "已生成密钥，等待注册公钥"
                settings.activeKey != null -> "已有 JWT 签名密钥"
                else -> "尚未生成密钥"
            }
        }
        var stopTest: (() -> Unit)? = null
        fun renderStats() {
            val now = weather.budget.stats()
            stats.text = "${now.month} UTC 本机已用 ${now.requests} / ${now.limit}，剩余 ${now.remaining}\n" +
                "最近成功 ${now.lastSuccess ?: "—"}\n最近错误 ${now.lastError ?: "—"}"
        }
        fun renderAuth(checked: Boolean) {
            listOf(kid, issuer, subject).forEach { it.visibility = if (checked) View.GONE else View.VISIBLE }
            secret.hint = if (checked) "API Key（留空保留已保存 Key）" else "导入 PKCS#8 私钥（可选）"
            renderKeys()
        }
        apiKey.setOnCheckedChangeListener { _, checked -> secret.text.clear(); renderAuth(checked) }
        renderAuth(apiKey.isChecked)
        listOf(host, apiKey, kid, issuer, subject, secret).forEach(layout::addView)
        layout.addView(keyChoice)
        layout.addView(keyStatus)
        layout.addView(row(button("生成 / 重新生成密钥") {
            val settings = weather.settings
            val generate = {
                action(status) {
                    settings.generateKey()
                    renderKeys(usePending = true)
                    status.text = "新密钥已加密保存。注册公钥后填写对应凭据标识。"
                }
            }
            if (settings.activeKey != null || settings.pendingKey != null || settings.keyUnavailable)
                confirm("生成新的 Ed25519 密钥？", "将替换待注册密钥。当前已保存凭据继续使用原密钥，直到保存新凭据。", generate)
            else generate()
        }, button("查看 / 复制公钥") {
            val key = keys.getOrNull(keyChoice.selectedItemPosition) ?: weather.settings.pendingKey ?: weather.settings.activeKey
            if (key == null) status.text = "请先生成密钥或保存导入的私钥" else showPublicKey(key)
        }))
        layout.addView(row(button("保存凭据") {
            action(status) {
                val validatedHost = qWeatherHost(host.value())
                val auth = if (apiKey.isChecked) weather.settings.apiKeyFor(validatedHost, secret.value())
                else QWeatherAuth.Jwt(kid.value(), issuer.value(), subject.value(),
                    if (secret.value().isNotEmpty()) QWeatherSigningKey.read(secret.value())
                    else requireNotNull(keys.getOrNull(keyChoice.selectedItemPosition)) { "请生成密钥或导入私钥" })
                val save = {
                    action(status) {
                        weather.configure(validatedHost, auth)
                        secret.text.clear()
                        status.text = weather.credentialStatus
                        renderKeys()
                        renderStats()
                        updated()
                    }
                }
                if (weather.settings.hasPayload) confirm("更换已保存的和风凭据？", "请确认 API Host、凭据标识和选中的密钥属于同一凭据。", save)
                else save()
            }
        }, button("测试已保存凭据") {
            stopTest?.invoke()
            status.text = "正在请求实时天气…"
            stopTest = weather.test { status.text = it; renderStats() }
        }, button("清除凭据") {
            confirm("清除和风凭据？", "将删除已保存的认证凭据及待注册密钥。") {
                action(status) {
                    weather.clearCredentials()
                    secret.text.clear()
                    listOf(host, kid, issuer, subject).forEach { it.text.clear() }
                    renderKeys()
                    status.text = "已清除天气凭据"
                    renderStats()
                    updated()
                }
            }
        }))
        layout.addView(status)
        layout.addView(row(limit, button("保存预算") {
            action(status) {
                weather.setBudgetLimit(limit.value().toIntOrNull() ?: 0)
                status.text = "本机预算已保存"
                renderStats()
            }
        }))
        layout.addView(stats)
        layout.addView(button("刷新周期设置") { refreshSettings(::renderStats) })
        openPortal?.let { layout.addView(button("扫码配置") { dismiss(); it() }) }
        val edits = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                status.text = "表单已编辑，尚未保存凭据"
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        listOf(host, kid, issuer, subject, secret).forEach { it.addTextChangedListener(edits) }
        renderStats()
        val dialog = dialog("和风天气服务", layout, secure = true)
        dialog.onDismiss { stopTest?.invoke(); secret.text.clear() }
    }

    private fun showPublicKey(key: QWeatherSigningKey) {
        val layout = column()
        layout.addView(label("和风公钥文本 SHA-256（PEM 去除首尾空白，内部为 LF）\n${key.qWeatherPemSha256}")
            .apply { setTextIsSelectable(true) })
        layout.addView(label("SPKI DER SHA-256\n${key.spkiSha256}").apply { setTextIsSelectable(true) })
        layout.addView(label(key.publicPem).apply { setTextIsSelectable(true) })
        layout.addView(button("复制完整公钥") {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Ed25519 公钥", key.publicPem))
            Toast.makeText(context, "已复制公钥", Toast.LENGTH_SHORT).show()
        })
        layout.addView(attribution(WeatherAttribution(link = QWEATHER_CREDENTIAL_GUIDE)))
        dialog("和风 Ed25519 公钥", layout)
    }

    private fun refreshSettings(updated: () -> Unit) {
        val layout = column()
        val policy = weather.refreshPolicy
        val mode = Spinner(context).apply {
            contentDescription = "和风刷新模式"
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, WeatherRefreshMode.entries.map { it.title })
            setSelection(policy.mode.ordinal)
        }
        val fields = RefreshInterval.entries.associateWith {
            field("${it.title}（${it.minimum}–${it.maximum} 分钟）", (policy.custom[it] ?: it.standard).toString()).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
            }
        }
        val advanced = column()
        fields.forEach { (kind, field) -> advanced.addView(row(label(kind.title), field)) }
        val estimate = label("")
        val status = label("")
        fun readPolicy(): WeatherRefreshPolicy {
            val selected = WeatherRefreshMode.entries[mode.selectedItemPosition.coerceAtLeast(0)]
            val custom = if (selected == WeatherRefreshMode.CUSTOM) fields.mapValues { (kind, field) ->
                field.value().toLongOrNull()?.takeIf { it in kind.minimum..kind.maximum }
                    ?: throw IllegalArgumentException("${kind.title}请输入 ${kind.minimum}–${kind.maximum} 分钟")
            } else policy.custom
            return WeatherRefreshPolicy(selected, custom)
        }
        fun render() {
            val custom = mode.selectedItemPosition == WeatherRefreshMode.CUSTOM.ordinal
            advanced.visibility = if (custom) View.VISIBLE else View.GONE
            estimate.text = runCatching {
                val candidate = readPolicy()
                val (saved, active) = repository.estimatedRequests(candidate, weather.budget.stats().month.lengthOfMonth())
                "整月预计请求（按全天运行）\n已保存地点的核心天气约 $saved 次，当前自动订阅约 $active 次\n" +
                    "两项分别估算，未计入手动请求、重试和地点解析。实际费用以和风账单为准。"
            }.getOrElse { "填入有效周期后计算预计请求量" }
        }
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = render()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        fields.values.forEach { field -> field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(s: Editable?) = Unit
        }) }
        layout.addView(label("周期仅作用于和风天气。HA 继续接收服务器推送。"))
        layout.addView(mode)
        layout.addView(estimate)
        layout.addView(button("保存刷新周期") {
            action(status) {
                weather.setRefreshPolicy(readPolicy())
                status.text = "刷新周期已保存并生效"
                updated()
            }
        })
        layout.addView(status)
        layout.addView(advanced)
        render()
        dialog("和风刷新周期", layout)
    }

    internal fun showPlace(id: String) = placeMenu(id)

    private fun placeMenu(id: String) {
        val place = places.get(id) ?: return
        val temporary = places.isTemporary(id)
        val actions = arrayOf("检查天气数据", if (temporary) "收藏此地点" else "编辑地点", "清除此地点离线缓存", "删除地点") + if (temporary) emptyArray() else arrayOf(
            if (place.favorite) "取消置顶收藏" else "置顶收藏", "向上移", "向下移",
        )
        AlertDialog.Builder(context).setTitle(place.name).setItems(actions) { _, index ->
            when (index) {
                0 -> chooseSource(place)
                1 -> if (temporary) favorite(id, place.sources.filterIsInstance<WeatherSourceRef.QWeather>().single().locationId)
                    else edit(place)
                2 -> confirmOfflineClear(id)
                3 -> confirm("删除地点「${place.name}」？") { changePlace { places.delete(id) } }
                4 -> changePlace { places.save(place.copy(favorite = !place.favorite)) }
                5 -> changePlace { places.move(id, -1) }
                6 -> changePlace { places.move(id, 1) }
            }
        }.setNegativeButton("取消", null).showOwned()
    }

    private fun changePlace(action: () -> Unit) {
        runCatching(action).onFailure { android.widget.Toast.makeText(context, it.message, android.widget.Toast.LENGTH_LONG).show() }
    }

    internal fun search() {
        val layout = column()
        val name = field("城市、区县或行政区域")
        val status = label("请选择地点预览或收藏。收藏使用和风公开目录中的地点编号。")
        val results = column()
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
        dialog("搜索和风地点", layout).onDismiss { stop?.invoke() }
    }

    private fun selectCity(city: WeatherServices.City) {
        val layout = column()
        val zone = field("地点时区（例如 Asia/Shanghai）", city.zone?.id.orEmpty())
        val status = label("预览保留至应用进程退出。收藏后可长期使用。")
        lateinit var dialog: AlertDialog
        layout.addView(zone)
        layout.addView(status)
        layout.addView(row(button("临时预览") {
            action(status) {
                val place = weather.select(city, zone.parseZone())
                layout.hideFormIme()
                dialog.dismiss()
                chooseSource(place)
            }
        }, button("收藏此地点") {
            layout.hideFormIme()
            dialog.dismiss()
            favorite(null, city.locationId)
        }))
        dialog = dialog(city.name, layout)
    }

    private fun favorite(previewId: String?, locationId: String?) {
        val layout = column()
        val name = field("收藏名称（自行填写）")
        val zone = field("地点时区（自行填写，例如 Asia/Shanghai）")
        val status = label("仅使用和风公开目录发布的地点编号。未收录地点可填写独立获得的经纬度。")
        lateinit var dialog: AlertDialog
        var cancel: (() -> Unit)? = null
        layout.addView(name)
        layout.addView(zone)
        layout.addView(status)
        layout.addView(row(button("验证并收藏") {
            action(status) {
                val timeZone = zone.parseZone()
                cancel?.invoke()
                status.text = "正在核对公开地点编号…"
                cancel = weather.favorite(previewId, locationId, name.value(), timeZone) { place, error ->
                    if (place == null) status.text = error.orEmpty()
                    else {
                        layout.hideFormIme()
                        dialog.dismiss()
                        chooseSource(place)
                    }
                }
            }
        }, button("用自有坐标收藏") {
            layout.hideFormIme()
            dialog.dismiss()
            edit(null, previewId)
        }))
        dialog = dialog("收藏天气地点", layout)
        dialog.onDismiss { cancel?.invoke() }
    }

    internal fun edit(place: WeatherPlace?, previewId: String? = null) {
        val existingQ = place?.sources?.filterIsInstance<WeatherSourceRef.QWeather>()?.firstOrNull()
        val existingCity = place?.sources?.filterIsInstance<WeatherSourceRef.QWeatherCity>()?.firstOrNull()
        val existingHa = place?.sources?.filterIsInstance<WeatherSourceRef.HomeAssistant>().orEmpty()
        val ha = (weather.haSources() + existingHa.map { it to it.entityId }).distinctBy { it.first }
        val layout = column()
        val name = field("地点名称", place?.name.orEmpty())
        val zone = field("地点时区（例如 Asia/Shanghai）", place?.zone?.id.orEmpty())
        val latitude = field("纬度 −90 至 90", existingQ?.coordinates?.latitude?.toPlainString().orEmpty())
        val longitude = field("经度 −180 至 180", existingQ?.coordinates?.longitude?.toPlainString().orEmpty())
        listOf(latitude, longitude).forEach {
            it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val locationId = field("历史天气 Location ID（可选）", existingQ?.locationId.orEmpty())
        val selections = ha.associate { (source, title) ->
            source to CheckBox(context).apply { text = "$title · ${source.entityId}"; isChecked = source in existingHa }
        }
        val status = label("")
        lateinit var dialog: AlertDialog
        layout.addView(name)
        layout.addView(zone)
        if (existingCity != null) layout.addView(label("公开地点编号 ${existingCity.locationId}。填写经纬度可改为自定义来源。"))
        layout.addView(label("和风天气坐标"))
        layout.addView(row(latitude, longitude))
        layout.addView(button("在地图上选点") {
            val seed = runCatching { WeatherCoordinates(latitude.value().toBigDecimal(), longitude.value().toBigDecimal()) }.getOrNull()
            WeatherMapPicker.show(context, seed) { point ->
                latitude.setText(point.latitude.toPlainString())
                longitude.setText(point.longitude.toPlainString())
            }.let { map -> dialogs += map; map.onDismiss {} }
        })
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
                } else if (existingCity != null) sources.add(0, existingCity)
                if (previewId != null) require(sources.any { it is WeatherSourceRef.QWeather }) { "请填写自己的经纬度" }
                require(name.value().isNotEmpty() && sources.isNotEmpty()) { "请填写地点名称并添加至少一个天气来源" }
                val saved = WeatherPlace(place?.id ?: previewId ?: UUID.randomUUID().toString(), name.value(), zone.parseZone(), sources, favorite = place?.favorite ?: (previewId != null))
                if (previewId == null) places.save(saved) else places.saveFavorite(saved)
                layout.hideFormIme()
                dialog.dismiss()
                onPlaceSelected?.invoke(saved)
            }
        })
        dialog = dialog(if (place == null) "添加地点" else "编辑地点", layout)
    }

    private fun chooseSource(place: WeatherPlace) {
        onPlaceSelected?.let { it(place); return }
        if (place.sources.size == 1) inspect(place, place.sources.single())
        else AlertDialog.Builder(context).setTitle("选择天气来源")
            .setItems(place.sources.map(::sourceName).toTypedArray()) { _, index -> inspect(place, place.sources[index]) }
            .setNegativeButton("取消", null).showOwned()
    }

    private fun inspect(place: WeatherPlace, source: WeatherSourceRef) {
        val layout = column()
        val data = column()
        val requests = mutableMapOf<WeatherBlock, WeatherRepository.Subscription>()
        val status = label("")
        val dates = field("专项日期 YYYY-MM-DD（选填）")
        val choices = WeatherBlock.entries
        val select = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, choices.map(::blockName))
        }
        val details = label("").apply { autoLinkMask = Linkify.WEB_URLS; linksClickable = true }
        var extra: WeatherRepository.Subscription? = null
        var extraQuery: WeatherQuery? = null
        val live = source is WeatherSourceRef.HomeAssistant
        val network = label("")
        if (!live) layout.addView(network)
        if (live) layout.addView(label("HA 状态和预报由服务器推送。查看预报会订阅 HA 已有数据。"))
        else layout.addView(button("刷新核心天气") {
            status.text = requests.filterKeys(WeatherBlock::core).map { (block, request) ->
                blockName(block) + " · " + refreshText(request.refresh(), place.zone)
            }.joinToString("\n")
        })
        layout.addView(row(select, dates, button(if (live) "查看选中数据" else "刷新选中数据") {
            action(status) {
                val block = choices[select.selectedItemPosition]
                val date = dates.value().ifEmpty { null }?.let {
                    runCatching { LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("日期格式应为 YYYY-MM-DD") }
                }
                val query = WeatherQuery(block, date = date)
                if (extraQuery != query) {
                    extra?.close()
                    extra = repository.observe(place.id, source, query, automatic = live) {
                        details.text = snapshotText(it)
                    }
                    extraQuery = query
                }
                status.text = if (live) "" else refreshText(checkNotNull(extra).refresh(), place.zone)
            }
        }))
        layout.addView(status)
        layout.addView(details)
        layout.addView(button("清除此地点离线缓存") { confirmOfflineClear(place.id) })
        layout.addView(data)
        choices.forEach { block ->
            val result = label("").apply { autoLinkMask = Linkify.WEB_URLS; linksClickable = true }
            data.addView(result)
            requests[block] = repository.observe(place.id, source, WeatherQuery(block)) { snapshot ->
                result.text = snapshotText(snapshot)
                if (block == WeatherBlock.CURRENT && !live) network.text = when (snapshot.network) {
                    WeatherNetworkStatus.ONLINE -> "网络已连接"
                    WeatherNetworkStatus.OFFLINE -> "设备离线，联网后可更新和风天气"
                    WeatherNetworkStatus.UNKNOWN -> "网络可用性尚未确认"
                    WeatherNetworkStatus.NOT_APPLICABLE -> ""
                }
            }
        }
        val dialog = dialog("${place.name} · ${sourceName(source)}", layout)
        dialog.onDismiss { extra?.close(); requests.values.forEach { it.close() } }
    }

    private fun refreshText(result: WeatherRefreshResult, zone: ZoneId): String = when (result.status) {
        WeatherRefreshStatus.STARTED -> "已开始刷新（结果见数据状态）"
        WeatherRefreshStatus.JOINED -> "已加入正在刷新的请求"
        WeatherRefreshStatus.QUEUED -> "已排队"
        WeatherRefreshStatus.BLOCKED -> listOfNotNull(result.reason, result.retryAt?.let {
            "最早 " + DateTimeFormatter.ofPattern("MM-dd HH:mm:ss z").withZone(zone).format(it)
        }).joinToString(" · ")
    }

    private fun snapshotText(snapshot: WeatherSnapshot): String = buildString {
        append(blockName(snapshot.query.block)).append(" · ").append(stateName(snapshot.state))
        if (snapshot.stale || (snapshot.error != null && snapshot.data != null)) append(" · 上次数据")
        if (snapshot.state == WeatherState.UNLOADED && snapshot.network == WeatherNetworkStatus.OFFLINE)
            append(" · 离线期间无法获取，暂无数据")
        if (snapshot.unchanged && !snapshot.refreshing) append(" · 服务暂无新数据")
        if (snapshot.refreshing && snapshot.state != WeatherState.LOADING) append(" · 更新中")
        val dateFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss z").withZone(snapshot.zone)
        snapshot.fetchedAt?.let { append(" · 获取于 ").append(dateFormat.format(it)) }
        snapshot.dataAt?.let { append(" · 数据时间 ").append(dateFormat.format(it)) }
        snapshot.error?.let { append("\n").append(it) }
        when (snapshot.persistence) {
            WeatherPersistence.SAVED -> append(" · 已保存离线缓存")
            WeatherPersistence.SAVING -> append(" · 正在写入离线缓存")
            WeatherPersistence.FAILED -> append("\n离线缓存写入失败，当前数据仅在内存中")
            WeatherPersistence.SESSION_ONLY -> append(" · 仅在本次会话可用")
            WeatherPersistence.NONE -> Unit
        }
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
        is WeatherSourceRef.QWeather, is WeatherSourceRef.QWeatherCity -> "和风天气"
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
        WeatherState.INVALID_REQUEST -> "请求无效"
    }

    private fun attributionText(value: WeatherAttribution) =
        (value.sources + value.statements + value.licenses + listOfNotNull(value.link)).distinct().joinToString("\n")

    private fun attribution(value: WeatherAttribution) =
        label(attributionText(value)).apply { autoLinkMask = Linkify.WEB_URLS; linksClickable = true }

    private fun dialog(title: String, content: LinearLayout, secure: Boolean = false) = AlertDialog.Builder(context).setTitle(title)
        .setView(ScrollView(context).apply {
            isFillViewport = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            requestFocus()
            addView(content.apply { setPadding(dp(20), dp(8), dp(20), dp(8)) })
        }).setNegativeButton("关闭") { _, _ -> content.hideFormIme() }.create().also { dialog ->
            dialog.window?.apply {
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                if (secure) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            dialogs += dialog
            dialog.onDismiss {}
            dialog.show()
        }

    private fun confirmOfflineClear(placeId: String?) {
        val scope = if (placeId == null) "全部" else "此地点的"
        val shared = if (placeId == null) "" else "其他地点共用的缓存会保留。"
        AlertDialog.Builder(context)
            .setTitle("清除${scope}离线缓存？")
            .setMessage("当前会话的天气仍可查看。$shared")
            .setPositiveButton("清除离线缓存") { _, _ ->
                repository.clearOfflineCache(placeId) {
                    val message = when (it) {
                        WeatherCacheClearResult.CLEARED -> "离线缓存已清除"
                        WeatherCacheClearResult.SHARED_RETAINED -> "已处理，其他地点共用的离线缓存保留"
                        WeatherCacheClearResult.FAILED -> "离线缓存未能完全清除，请重试"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null).showOwned()
    }

    private fun confirm(title: String, message: String? = null, action: () -> Unit) = AlertDialog.Builder(context).setTitle(title)
        .setMessage(message)
        .setPositiveButton("确认") { _, _ -> action() }.setNegativeButton("取消", null).showOwned()

    private fun AlertDialog.Builder.showOwned() = show().also {
        dialogs += it
        it.onDismiss {}
    }

    private fun AlertDialog.onDismiss(cleanup: () -> Unit) {
        setOnDismissListener {
            dialogs -= this
            cleanup()
        }
    }

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
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
    }
    private fun EditText.value() = text.toString().trim()
    private fun View.hideFormIme() {
        windowToken?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it, 0) }
    }
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
