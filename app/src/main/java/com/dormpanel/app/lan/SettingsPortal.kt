package com.dormpanel.app.lan

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.ha.BackendMode
import com.dormpanel.app.ha.DashboardBackend
import com.dormpanel.app.ha.HaEndpoint
import com.dormpanel.app.ha.HaTokenRequired
import com.dormpanel.app.ha.requireHaToken
import com.dormpanel.app.schedule.WebDavClient
import com.dormpanel.app.schedule.WebDavCredentials
import com.dormpanel.app.schedule.WebDavSettings
import com.dormpanel.app.schedule.WebDavSyncController
import com.dormpanel.app.schedule.saveImportAccount
import com.dormpanel.app.weather.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class SettingsPortal(
    private val context: Context,
    private val backend: DashboardBackend,
    private val dav: WebDavSettings,
    private val sync: WebDavSyncController,
    localAddress: () -> String? = LanAddress::deviceAddress,
    private val weather: WeatherServices = backend.weatherServices,
) {
    private val main = Handler(Looper.getMainLooper())
    private val phone = PhoneEntryUi(context, localAddress)
    private var review: AlertDialog? = null
    private var davConfirmation: AlertDialog? = null
    private var result: AlertDialog? = null
    private var generation = 0

    fun close() {
        generation++
        phone.close()
        review?.dismiss()
        davConfirmation?.dismiss()
        result?.dismiss()
        review = null
        davConfirmation = null
        result = null
    }

    fun show(onSaved: () -> Unit = {}) {
        close()
        val session = Session()
        val template = context.assets.open("settings-portal.html").bufferedReader().use { it.readText() }
        phone.start({ nonce, remaining ->
            template.replace("{{NONCE}}", nonce).replace("{{REMAINING}}", remaining.toString())
                .replace("{{MODEL}}", session.model.toString().replace("<", "\\u003c"))
        }, session::post, onClosed = session::close) { values ->
            if (values["_action"] == "cancel") toast("已关闭扫码会话，已确认的保存仍然有效")
            else session.plan?.let { review(it, onSaved) }
        }
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    private sealed class ChangeOutcome(val message: String) {
        class Applied(message: String) : ChangeOutcome(message)
        class Failed(message: String) : ChangeOutcome(message)
    }

    private class Change(val title: String, val description: String, val apply: ((ChangeOutcome) -> Unit) -> Unit)

    private fun outcome(error: String?) =
        if (error == null) ChangeOutcome.Applied("已保存") else ChangeOutcome.Failed(error)

    private fun immediate(title: String, description: String, apply: () -> Unit) =
        immediateResult(title, description) { apply(); "已保存" }

    private fun immediateResult(title: String, description: String, apply: () -> String) =
        Change(title, description) { done ->
            val result = try { ChangeOutcome.Applied(apply()) } catch (_: Exception) {
                ChangeOutcome.Failed("未能保存，请检查当前配置或存储后重试")
            }
            done(result)
        }

    private data class PortalWeather(val config: QWeatherConfig?, val pendingKey: QWeatherSigningKey?, val public: JSONObject)
    private data class WeatherStep(
        val revision: Int = 0, val kind: String = "", val state: String = "ready", val message: String = "",
        val saveApplied: Boolean = false,
    )
    private val credentialFields = setOf("qw_host", "qw_auth", "qw_kid", "qw_iss", "qw_sub", "qw_key", "qw_api")

    private inner class Session {
        private val sessionGeneration = generation
        private var stepDialog: AlertDialog? = null
        private var stopTest: (() -> Unit)? = null
        @Volatile private var step = WeatherStep()
        private fun active() = sessionGeneration == generation && phone.isActive()

        fun close() {
            stepDialog?.dismiss()
            stepDialog = null
            stopTest?.invoke()
            stopTest = null
        }

        private val ha = backend.settings
        private val hasToken = backend.hasToken()
        private val tokenStamp = backend.tokenStamp()
        private val oldAccount = dav.account()
        private val davPublic = dav.publicAccount()
        private val refresh = weather.refreshPolicy
        private val limit = weather.budget.stats().limit
        @Volatile private var snapshot = captureWeather()
        private val savedPlaces = weather.places.places.filterNot { weather.places.isTemporary(it.id) }.associateBy { it.id }
        private val cityIds = mutableSetOf<String>()
        private var searches = 0
        @Volatile var plan: List<Change>? = null
            private set

        val model: JSONObject get() {
            val status = stateJson()
            return JSONObject(publicModel.toString()).put("qw", status.getJSONObject("qw")).put("step", status.getJSONObject("step"))
        }

        private val publicModel = JSONObject()
            .put("ha", JSONObject().put("url", ha.baseUrl.takeIf(String::isNotBlank)
                ?.let { runCatching { HaEndpoint.parse(it).base }.getOrNull() }.orEmpty())
                .put("configured", hasToken && ha.baseUrl.isNotEmpty())
                .put("mode", if (!hasToken && ha.baseUrl.isBlank() && ha.mode == BackendMode.DEMO) "" else ha.mode.name)
                .put("weather", ha.weatherEntity))
            .put("dav", JSONObject().put("url", runCatching { WebDavClient().url(davPublic.first).toString() }.getOrDefault(""))
                .put("username", davPublic.second).put("configured", oldAccount != null))
            .put("places", JSONArray(savedPlaces.values.map { place ->
                val source = place.sources.filterIsInstance<WeatherSourceRef.QWeather>().firstOrNull()
                JSONObject().put("id", place.id).put("name", place.name).put("zone", place.zone.id)
                    .put("lat", source?.coordinates?.latitude?.toPlainString().orEmpty())
                    .put("lon", source?.coordinates?.longitude?.toPlainString().orEmpty())
                    .put("locationId", source?.locationId.orEmpty())
                    .put("sources", place.sources.joinToString(" · ") {
                        when (it) {
                            is WeatherSourceRef.HomeAssistant -> "Home Assistant · ${it.entityId}"
                            is WeatherSourceRef.QWeather -> "和风坐标"
                            is WeatherSourceRef.QWeatherCity -> "和风公开地点 ${it.locationId}"
                        }
                    })
            }))

        private fun captureWeather(): PortalWeather {
            val qw = weather.settings.config
            val pendingKey = weather.settings.pendingKey
            val jwt = qw?.auth as? QWeatherAuth.Jwt
            val public = JSONObject().put("host", weather.settings.host).put("status", weather.credentialStatus)
                .put("auth", if (qw?.auth is QWeatherAuth.ApiKey) "api" else "jwt")
                .put("budget", limit).put("mode", refresh.mode.name)
                .put("modes", JSONObject(WeatherRefreshMode.entries.associate { it.name to it.title }))
                .put("kid", jwt?.kid.orEmpty()).put("iss", jwt?.issuer.orEmpty()).put("sub", jwt?.subject.orEmpty())
                .put("keys", JSONArray(listOfNotNull(
                    jwt?.key?.let { publicKey("active", "当前凭据密钥", it) },
                    pendingKey?.let { publicKey("pending", "待注册的新密钥", it) })))
                .put("guide", QWEATHER_CREDENTIAL_GUIDE)
                .put("configured", qw != null)
            return PortalWeather(qw, pendingKey, public)
        }

        private fun stateJson(): JSONObject {
            val current = step
            return JSONObject().put("qw", snapshot.public)
                .put("step", JSONObject().put("revision", current.revision).put("kind", current.kind)
                    .put("state", current.state).put("message", current.message).put("saveApplied", current.saveApplied))
        }

        private fun status() = SettingsEntryReply(200, stateJson().toString())

        private fun beginStep(values: Map<String, String>, kind: String): SettingsEntryReply {
            val current = step
            validateRevision(values)
            val generate = kind == "generate_key"
            if (generate) {
                expect(values.keys == setOf("_action", "_revision"), "", "生成请求格式无效")
                expect(snapshot.pendingKey == null, "qw_key", "已有待注册密钥。更换待注册密钥请在 X08E 的和风服务配置中操作。")
            } else {
                expect(values.keys == credentialFields + setOf("_action", "_revision"), "", "和风凭据表单格式无效")
            }
            val previous = snapshot
            val change = if (generate) null else prepare(values).single()
            step = WeatherStep(current.revision, kind, "pending", "请在 X08E 上核对并确认")
            main.post {
                if (!active()) return@post
                fun complete(state: String, message: String, saveApplied: Boolean = false) {
                    if (!active()) return
                    snapshot = captureWeather()
                    step = WeatherStep(current.revision + 1, kind, state, message, saveApplied)
                    if (!generate) toast(message)
                }
                fun cancel() = complete("cancelled", "已在 X08E 取消，可修改后重试")
                fun generateKey() {
                    if (!active()) return
                    try {
                        check(weather.settings.config === previous.config && weather.settings.pendingKey == null)
                        weather.settings.generateKey()
                        complete("success", "已生成待注册密钥。请复制公钥并到和风控制台创建 JWT 凭据。")
                    } catch (_: Exception) {
                        complete("failed", "无法生成待注册密钥，请检查设备上的天气设置")
                    }
                }
                fun save(test: Boolean) {
                    if (!active()) return
                    checkNotNull(change).apply { saved ->
                        if (saved is ChangeOutcome.Failed) {
                            complete("save_failed", "凭据保存失败。${saved.message}")
                            return@apply
                        }
                        val prefix = saved.message
                        if (!test) {
                            complete("saved_unverified", "$prefix。此次未验证连接。", saveApplied = true)
                            return@apply
                        }
                        snapshot = captureWeather()
                        step = WeatherStep(current.revision, kind, "testing", "$prefix，正在测试连接", saveApplied = true)
                        stopTest = weather.test { verification ->
                            val (state, message) = when (verification) {
                                WeatherConnectionTest.Verified -> "saved_verified" to verification.message
                                WeatherConnectionTest.NoWeather -> "saved_no_weather" to
                                    "连接尚未验证。${verification.message}，可在 X08E 重新测试。"
                                is WeatherConnectionTest.Failed -> "saved_test_failed" to
                                    "连接测试失败。${verification.message}。可修改配置后重新测试。"
                                is WeatherConnectionTest.NotRun -> "saved_test_not_run" to "测试未执行。${verification.message}"
                                WeatherConnectionTest.Cancelled -> "saved_test_cancelled" to verification.message
                            }
                            complete(state, "$prefix。$message", saveApplied = true)
                        }
                    }
                }
                val dialog = AlertDialog.Builder(context)
                    .setTitle(if (generate) "生成和风待注册密钥？" else "保存和风天气凭据？")
                    .setMessage(change?.description ?: "手机请求生成 Ed25519 密钥。私钥保留在 X08E，门户会显示公钥。当前已启用的 JWT 凭据继续使用原密钥。")
                    .setNegativeButton("取消") { _, _ -> cancel() }
                    .setOnCancelListener { cancel() }
                    .setPositiveButton(if (generate) "确认生成" else "保存并测试") { _, _ ->
                        if (generate) generateKey() else save(test = true)
                    }
                if (!generate) dialog.setNeutralButton("仅保存") { _, _ -> save(test = false) }
                stepDialog = dialog.create().also {
                    it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    it.setOnDismissListener { _ -> if (stepDialog === it) stepDialog = null }
                    it.show()
                }
            }
            return status()
        }

        private fun validateRevision(values: Map<String, String>) {
            expect(step.state !in setOf("pending", "testing"), "", "请先完成 X08E 上正在处理的请求")
            expect(values["_revision"] == step.revision.toString(), "", "配置状态已变化，请先检查设备进度")
        }

        private fun publicKey(slot: String, title: String, key: QWeatherSigningKey) = JSONObject()
            .put("id", key.spkiSha256).put("slot", slot).put("title", title).put("pem", key.publicPem).put("spkiSha256", key.spkiSha256).put("qWeatherPemSha256", key.qWeatherPemSha256)

        fun post(values: Map<String, String>): SettingsEntryReply = try {
            when (values["_action"]) {
                "cancel" -> {
                    expect(values.keys == setOf("_action"), "", "取消请求格式无效")
                    response("已关闭扫码会话。此前已在 X08E 确认的保存仍然有效。", terminal = true)
                }
                "status" -> {
                    expect(values.keys == setOf("_action"), "", "状态请求格式无效")
                    status()
                }
                "generate_key", "save_weather" -> beginStep(values, values.getValue("_action"))
                "search" -> search(values)
                "submit" -> {
                    validateRevision(values)
                    plan = prepare(values)
                    response("已发送待确认配置。请在 X08E 上核对并确认保存。", terminal = true)
                }
                else -> throw SettingsEntryError("", "操作无效")
            }
        } catch (error: SettingsEntryError) {
            val details = if (values["_action"] == "search") stateJson() else JSONObject()
            SettingsEntryReply(422, details.put("field", error.field).put("message", error.message).toString())
        }

        private fun search(values: Map<String, String>): SettingsEntryReply {
            val query = values["query"].orEmpty().trim()
            expect(values.keys == setOf("_action", "query") && query.length in 1..100, "query", "请输入 1–100 字的城市或区县名称")
            expect(step.state !in setOf("pending", "testing"), "query", "请先完成 X08E 上正在处理的请求")
            expect(snapshot.config != null, "query", "请先在和风分区保存凭据并在 X08E 确认，也可以用自有坐标添加地点")
            expect(searches < 12, "query", "本次会话的地点搜索次数已用完")
            searches++
            val latch = CountDownLatch(1)
            var payload: List<WeatherServices.City> = emptyList()
            var error: String? = null
            var stop: (() -> Unit)? = null
            val start = Runnable {
                if (!active()) {
                    latch.countDown()
                    return@Runnable
                }
                stop = weather.search(query) { cities, failure ->
                    snapshot = captureWeather()
                    payload = cities.take(20)
                    error = failure
                    latch.countDown()
                }
            }
            main.post(start)
            try {
                if (!latch.await(12, TimeUnit.SECONDS)) return response("搜索超时，请重试。", code = 504)
                error?.let { throw SettingsEntryError("query", it) }
                val cities = payload.mapNotNull { city ->
                    val id = city.locationId?.takeIf { it.matches(Regex("[0-9]{9}")) } ?: return@mapNotNull null
                    cityIds += id
                    JSONObject().put("id", id).put("title", "${city.name} · ${city.area}")
                }
                val first = payload.firstOrNull()?.attribution
                val credit = first?.let { (it.sources + it.statements + it.licenses + listOfNotNull(it.link)).distinct().joinToString(" · ") }
                return SettingsEntryReply(200, stateJson().put("cities", JSONArray(cities))
                    .put("credit", credit.orEmpty()).toString())
            } finally {
                main.removeCallbacks(start)
                main.post { stop?.invoke() }
            }
        }

        private fun prepare(values: Map<String, String>): List<Change> {
            val groups = listOf(
                listOf("ha_url", "ha_token", "ha_mode", "ha_weather"),
                listOf("dav_url", "dav_username", "dav_password"),
                credentialFields,
                listOf("mode", "budget"), listOf("places"),
            )
            expect(values.keys.all { it in setOf("_action", "_revision") || groups.any { group -> it in group } }, "", "提交包含未知字段")
            fun includes(index: Int): Boolean {
                val group = groups[index]
                if (group.none(values::containsKey)) return false
                expect(group.all(values::containsKey), "", "提交缺少必填字段")
                return true
            }
            fun field(name: String, max: Int, trim: Boolean = true): String {
                val value = values.getValue(name).let { if (trim) it.trim() else it }
                expect(value.length <= max, name, "输入超出长度限制")
                return value
            }
            val weatherAtSubmission = snapshot
            val qw = weatherAtSubmission.config
            val pendingKey = weatherAtSubmission.pendingKey
            val jwt = qw?.auth as? QWeatherAuth.Jwt
            return buildList {
                if (includes(0)) {
                    val raw = field("ha_url", 2048)
                    val url = checked("ha_url", "请输入有效的 HA 服务地址") {
                        require(raw.isNotBlank()); HaEndpoint.parse(raw).base
                    }
                    val token = field("ha_token", 8192)
                    val selected = field("ha_mode", 24)
                    val mode = checked("ha_mode", "请选择有效的连接模式") {
                        if (selected.isEmpty() && ha.mode == BackendMode.DEMO && ha.baseUrl.isBlank() && !hasToken)
                            BackendMode.HOME_ASSISTANT
                        else BackendMode.valueOf(selected)
                    }
                    val currentUrl = runCatching { HaEndpoint.parse(ha.baseUrl).base }.getOrDefault("")
                    try {
                        requireHaToken(url, currentUrl, hasToken, token, required = mode == BackendMode.HOME_ASSISTANT)
                    } catch (error: HaTokenRequired) {
                        throw SettingsEntryError("ha_token", error.message.orEmpty())
                    }
                    val entity = field("ha_weather", 255)
                    expect(entity.isEmpty() || entity.matches(Regex("weather\\.[a-z0-9_]+")), "ha_weather", "请输入 weather. 开头的实体 ID")
                    val candidate = ha.copy(mode = mode, baseUrl = url, weatherEntity = entity)
                    add(immediate("Home Assistant", "$url · ${if (mode == BackendMode.HOME_ASSISTANT) "连接 HA" else "演示模式"}\n令牌${if (token.isEmpty()) "保留原设置" else "已填写"} · 天气实体 ${entity.ifEmpty { "自动选择" }}") {
                        check(backend.settings == ha && backend.tokenStamp() == tokenStamp)
                        backend.save(candidate, token)
                    })
                }
                if (includes(1)) {
                    val url = field("dav_url", 2048)
                    val username = field("dav_username", 256, trim = false)
                    val password = field("dav_password", 4096, trim = false)
                    checked("dav_url", "请输入有效的 HTTP 或 HTTPS WebDAV 地址") { WebDavClient().url(url) }
                    expect(username.isNotBlank() && ':' !in username, "dav_username", "请输入有效用户名（不能包含冒号）")
                    val candidate = checked("dav_password", "请输入密码。只有服务器、用户名和 TLS 信任均一致时才能留空") {
                        WebDavCredentials.candidate(oldAccount, url, username, password,
                            WebDavCredentials.inheritedCertificate(oldAccount, url, username))
                    }
                    add(Change("WebDAV", "$url · $username\n密码${if (password.isEmpty()) "保留原设置" else "已填写"}") { done ->
                        if (dav.publicAccount() != davPublic) done(outcome("账号已变化，请重新填写"))
                        else davConfirmation = saveImportAccount(context, dav, sync, oldAccount, candidate) { done(outcome(it)) }?.also { dialog ->
                            dialog.setOnDismissListener { if (davConfirmation === dialog) davConfirmation = null }
                        }
                    })
                }
                if (includes(2)) {
                    val host = checked("qw_host", "请输入和风控制台分配的专属 API Host") { qWeatherHost(field("qw_host", 256)) }
                    val api = field("qw_api", 256)
                    val auth = when (field("qw_auth", 8)) {
                        "api" -> checked("qw_api", "请输入该 API Host 的有效 API Key") {
                            if (api.isNotEmpty()) QWeatherAuth.ApiKey(api)
                            else requireNotNull(qw?.takeIf { it.host == host }?.auth as? QWeatherAuth.ApiKey)
                        }
                        "jwt" -> {
                            fun id(name: String) = field(name, 64).also {
                                expect(it.matches(Regex("[A-Za-z0-9]{1,64}")), name, "请输入由字母和数字组成的标识")
                            }
                            val keyId = field("qw_key", 64)
                            val key = listOfNotNull(jwt?.key, pendingKey).firstOrNull { it.spkiSha256 == keyId }
                            expect(key != null, "qw_key", "请申请生成密钥并在 X08E 确认，或在设备上导入旧私钥")
                            expect(api.isEmpty(), "qw_api", "JWT 模式不能提交 API Key")
                            QWeatherAuth.Jwt(id("qw_kid"), id("qw_iss"), id("qw_sub"), checkNotNull(key))
                        }
                        else -> throw SettingsEntryError("qw_auth", "请选择有效的认证方式")
                    }
                    val detail = if (auth is QWeatherAuth.Jwt)
                        "JWT · kid ${auth.kid} · iss ${auth.issuer} · sub ${auth.subject}\n和风公钥文本 SHA-256 ${auth.key.qWeatherPemSha256}"
                    else "API Key ${if (api.isEmpty()) "保留原设置" else "已填写"}"
                    add(immediateResult("和风天气凭据", "$host\n$detail") {
                        check(weather.settings.config === qw && weather.settings.pendingKey === pendingKey)
                        if (weather.configure(host, auth)) "凭据已保存" else "凭据未变化"
                    })
                }
                if (includes(3)) {
                    val mode = checked("mode", "请选择有效的刷新模式") { WeatherRefreshMode.valueOf(field("mode", 12)) }
                    val max = field("budget", 7).toIntOrNull()
                    expect(max != null && max in 1..1_000_000, "budget", "月请求上限应为 1–1,000,000")
                    if (mode != refresh.mode) add(immediate("和风刷新模式", mode.title) {
                        check(weather.refreshPolicy == refresh)
                        weather.setRefreshPolicy(refresh.copy(mode = mode))
                    })
                    if (max != limit) add(immediate("和风本机月预算", "$max 次") {
                        check(weather.budget.stats().limit == limit)
                        weather.setBudgetLimit(checkNotNull(max))
                    })
                }
                if (includes(4)) addAll(placeChanges(values.getValue("places")))
                expect(isNotEmpty(), "", "请选择至少一项配置修改")
            }
        }

        private fun placeChanges(raw: String): List<Change> {
            val rows = checked("places", "地点表单格式无效") { JSONArray(raw) }
            expect(rows.length() in 1..12, "places", "一次可提交 1–12 个地点")
            val edited = mutableSetOf<String>()
            return (0 until rows.length()).map { index ->
                val row = checked("places", "地点表单格式无效") { rows.getJSONObject(index) }
                val keys = setOf("id", "name", "zone", "lat", "lon", "locationId", "city")
                expect(row.keys().asSequence().toSet() == keys, "places", "地点表单含未知字段或缺少字段")
                fun field(key: String, max: Int): String {
                    val text = (row.opt(key) as? String)?.trim()
                    expect(text != null && text.length <= max && text.none(Char::isISOControl), "places", "地点字段格式或长度无效")
                    return checkNotNull(text)
                }
                val id = field("id", 64)
                val previous = savedPlaces[id]
                expect(id.isEmpty() || (previous != null && edited.add(id)), "places", "待编辑地点无效或重复")
                val name = field("name", 100)
                expect(name.isNotBlank(), "places", "请填写地点名称")
                val zone = checked("places", "请输入 IANA 时区，例如 Asia/Shanghai") {
                    val value = field("zone", 64)
                    require(value in ZoneId.getAvailableZoneIds()); ZoneId.of(value)
                }
                val lat = field("lat", 32)
                val lon = field("lon", 32)
                val locationId = field("locationId", 40)
                val city = field("city", 9)
                if (city.isNotEmpty()) {
                    expect(id.isEmpty() && city in cityIds && lat.isEmpty() && lon.isEmpty() && locationId.isEmpty(),
                        "places", "请通过搜索选择要收藏的公开地点")
                    Change("收藏 $name", "${zone.id} · 核对和风公开地点 $city 后收藏") { done ->
                        weather.favorite(null, city, name, zone) { _, error -> done(outcome(error)) }
                    }
                } else {
                    val sources = previous?.sources.orEmpty()
                    val nextSources = if (lat.isEmpty() && lon.isEmpty()) {
                        expect(locationId.isEmpty(), "places", "填写历史天气编号时需要提供坐标")
                        sources
                    } else {
                        val source = checked("places", "请填写有效经纬度（最多 20 位小数）和可选的 Location ID") {
                            val decimal = Regex("[+-]?[0-9]{1,3}(\\.[0-9]{1,20})?")
                            require(lat.matches(decimal) && lon.matches(decimal))
                            WeatherSourceRef.QWeather(WeatherCoordinates(lat.toBigDecimal(), lon.toBigDecimal()), locationId.ifEmpty { null })
                        }
                        val first = sources.indexOfFirst { it is WeatherSourceRef.QWeather }
                        if (first < 0) listOf(source) + sources
                        else sources.mapIndexed { position, old -> if (position == first) source else old }
                    }
                    expect(nextSources.isNotEmpty(), "places", "新地点需要填写自己的经纬度或搜索并收藏")
                    val place = WeatherPlace(previous?.id ?: UUID.randomUUID().toString(), name, zone, nextSources, previous?.favorite == true)
                    val detail = if (lat.isEmpty()) "保留已有来源" else
                        listOf("$lat, $lon", locationId.takeIf(String::isNotEmpty)?.let { "历史天气编号 $it" })
                            .filterNotNull().joinToString(" · ")
                    immediate(if (previous == null) "添加 $name" else "编辑 ${previous.name} → $name", "${zone.id} · $detail") {
                        check(previous == null || weather.places.get(previous.id) == previous)
                        weather.places.save(place)
                    }
                }
            }
        }
    }

    private fun review(changes: List<Change>, onSaved: () -> Unit) {
        val summary = changes.joinToString("\n\n") { "${it.title}\n${it.description}" }
        review = AlertDialog.Builder(context).setTitle("待保存配置")
            .setMessage(summary).setNegativeButton("取消") { _, _ -> toast("已取消，未保存此次填写内容") }
            .setOnCancelListener { toast("已取消，未保存此次填写内容") }
            .setPositiveButton("确认应用") { _, _ -> apply(changes, onSaved) }.create().also {
                it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                it.setOnDismissListener { _ -> if (review === it) review = null }
                it.show()
            }
    }

    private fun apply(changes: List<Change>, onSaved: () -> Unit) {
        val currentGeneration = generation
        val status = TextView(context).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(context).apply { addView(status) }
        val rows = MutableList(changes.size) { "等待处理" }
        fun render() { status.text = changes.indices.joinToString("\n\n") { "${changes[it].title} · ${rows[it]}" } }
        result = AlertDialog.Builder(context).setTitle("配置应用结果").setView(scroll)
            .setPositiveButton("关闭", null).create().also {
                it.setCancelable(false)
                it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                it.setOnDismissListener { _ -> if (result === it) result = null }
                it.show()
                it.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            }
        fun next(index: Int) {
            if (generation != currentGeneration) return
            if (index == changes.size) {
                result?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                onSaved()
                return
            }
            rows[index] = "处理中…"
            render()
            val done: (ChangeOutcome) -> Unit = { saved ->
                rows[index] = if (saved is ChangeOutcome.Failed) "未保存 · ${saved.message}" else saved.message
                render()
                main.post { next(index + 1) }
            }
            try { changes[index].apply(done) } catch (_: Exception) { done(outcome("操作失败，请在设备设置中检查")) }
        }
        next(0)
    }

    private fun response(message: String, terminal: Boolean = false, code: Int = 200) =
        SettingsEntryReply(code, JSONObject().put("message", message).toString(), terminal)

    private fun expect(condition: Boolean, field: String, message: String) {
        if (!condition) throw SettingsEntryError(field, message)
    }

    private inline fun <T> checked(field: String, message: String, validate: () -> T): T = try {
        validate()
    } catch (_: Exception) {
        throw SettingsEntryError(field, message)
    }
}
