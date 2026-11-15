package com.dormpanel.app.weather

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.dormpanel.app.ha.TokenCipher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal fun weatherDigest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

internal class QWeatherSettings(context: Context, private val alias: String = "DormPanel.Weather.QWeather.v1") {
    private val prefs = context.getSharedPreferences("weather_service", Context.MODE_PRIVATE)

    private val cipher = TokenCipher {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }
    var config: QWeatherConfig? = load()
        private set
    val hasPayload get() = prefs.contains("payload")
    val host get() = prefs.getString("host", "").orEmpty()
    val problem get() = if (hasPayload) "无法解密天气凭据，请重新配置" else "请先配置和风天气"

    private fun load() = runCatching {
        val json = JSONObject(cipher.decrypt(checkNotNull(prefs.getString("payload", null))) ?: return null)
        QWeatherConfig(json.getString("id"), json.getString("host"), QWeatherAuth.decode(json))
    }.getOrNull()

    fun save(host: String, auth: QWeatherAuth) {
        val validated = qWeatherHost(host)
        val next = QWeatherConfig(UUID.randomUUID().toString(), validated, auth)
        val payload = runCatching {
            cipher.encrypt(auth.encode().put("host", next.host).put("id", next.id).toString())
        }.getOrElse { error("无法安全保存凭据，请检查 Android Keystore") }
        check(prefs.edit().putString("host", next.host).putString("payload", payload).commit()) { "无法保存天气设置" }
        config = next
    }

    fun clear() {
        check(prefs.edit().remove("payload").commit()) { "无法清除天气凭据" }
        config = null
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }
}

class WeatherPlaceStore internal constructor(
    initial: String,
    private val write: (String) -> Boolean,
) {
    private constructor(prefs: android.content.SharedPreferences) : this(
        prefs.getString("places", "[]").orEmpty(),
        { prefs.edit().putString("places", it).commit() },
    )
    internal constructor(context: Context) : this(context.getSharedPreferences("weather_places", Context.MODE_PRIVATE))

    private val temporary = linkedMapOf<String, WeatherPlace>()
    private val saved = runCatching {
        JSONArray(initial).objects().mapNotNull { row ->
            runCatching { decode(row) }.getOrNull()
        }
    }.getOrDefault(emptyList()).associateByTo(linkedMapOf()) { it.id }
    private val listeners = linkedSetOf<() -> Unit>()
    val places: List<WeatherPlace> get() = saved.values.toList() + temporary.values
    fun get(id: String): WeatherPlace? = saved[id] ?: temporary[id]
    fun isTemporary(id: String) = id in temporary

    fun save(place: WeatherPlace) {
        require(place.id !in temporary) { "请通过收藏流程保存预览地点" }
        persist(saved + (place.id to place))
    }

    internal fun saveFavorite(place: WeatherPlace) {
        require(place.sources.none { it is WeatherSourceRef.QWeather && temporary[place.id]?.sources.orEmpty().any { preview -> preview === it } })
        persist(saved + (place.id to place)) { temporary.remove(place.id) }
    }

    internal fun addLookup(place: WeatherPlace) {
        require(place.id !in saved && temporary.size < 32) { "临时地点已达上限，请先删除不用的地点" }
        temporary[place.id] = place
        notifyChanged()
    }

    fun delete(id: String) {
        if (temporary.remove(id) != null) notifyChanged()
        if (id in saved) persist(saved - id)
    }

    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun notifyChanged() = listeners.toList().forEach { it() }
    private fun persist(next: Map<String, WeatherPlace>, committed: () -> Unit = {}) {
        require(next.size <= 100) { "已保存的地点已达上限" }
        check(write(JSONArray(next.values.map(::encode)).toString())) { "无法保存地点" }
        saved.clear()
        saved.putAll(next)
        committed()
        notifyChanged()
    }

    companion object {
        private fun encode(place: WeatherPlace) = JSONObject().put("id", place.id).put("name", place.name)
            .put("zone", place.zone.id).put("sources", JSONArray(place.sources.map { source ->
                when (source) {
                    is WeatherSourceRef.QWeather -> JSONObject().put("kind", "qw")
                        .put("lat", source.coordinates.latitude.toPlainString()).put("lon", source.coordinates.longitude.toPlainString())
                        .put("location", source.locationId)
                    is WeatherSourceRef.QWeatherCity -> JSONObject().put("kind", "qw-city").put("location", source.locationId)
                    is WeatherSourceRef.HomeAssistant -> JSONObject().put("kind", "ha")
                        .put("entity", source.entityId).put("server", source.serverId)
                }
            }))

        private fun decode(row: JSONObject) = WeatherPlace(row.getString("id"), row.getString("name"), ZoneId.of(row.getString("zone")),
            row.objects("sources").map { source ->
                when (source.getString("kind")) {
                    "qw" -> WeatherSourceRef.QWeather(WeatherCoordinates(BigDecimal(source.getString("lat")),
                        BigDecimal(source.getString("lon"))), source.text("location"))
                    "qw-city" -> WeatherSourceRef.QWeatherCity(source.getString("location"))
                    "ha" -> WeatherSourceRef.HomeAssistant(source.getString("entity"), source.getString("server"))
                    else -> error("Unknown weather source")
                }
            })
    }
}

internal data class WeatherCacheRecord(val wire: JSONObject, val fetchedAt: Instant)

internal interface WeatherCache {
    fun read(group: String, key: String, done: (WeatherCacheRecord?) -> Unit)
    fun write(group: String, key: String, record: WeatherCacheRecord, done: (Boolean) -> Unit)
    fun clear(groups: Set<String>?)
}

/** File operations are serialized on the supplied worker and callbacks return to the repository scheduler. */
internal class DiskWeatherCache(
    private val directory: File,
    private val worker: java.util.concurrent.Executor,
    private val dispatch: (() -> Unit) -> Unit,
) : WeatherCache {
    private fun file(group: String, key: String) = File(directory, "${weatherDigest(group)}-${weatherDigest(key)}.json")

    override fun read(group: String, key: String, done: (WeatherCacheRecord?) -> Unit) {
        worker.execute {
            val record = runCatching {
                val source = file(group, key)
                if (source.length() > MAX_FILE) return@runCatching null
                val json = JSONObject(AtomicFile(source).readFully().toString(Charsets.UTF_8))
                WeatherCacheRecord(json.getJSONObject("payload"), Instant.parse(json.getString("fetchedAt")))
            }.getOrNull()
            dispatch { done(record) }
        }
    }

    override fun write(group: String, key: String, record: WeatherCacheRecord, done: (Boolean) -> Unit) {
        worker.execute {
            val persisted = runCatching {
                directory.mkdirs()
                val json = JSONObject().put("fetchedAt", record.fetchedAt.toString()).put("payload", record.wire)
                val bytes = json.toString().toByteArray()
                check(bytes.size <= MAX_FILE)
                val file = AtomicFile(file(group, key))
                val stream = file.startWrite()
                try {
                    stream.write(bytes)
                    stream.fd.sync()
                    file.finishWrite(stream)
                } catch (e: Exception) {
                    file.failWrite(stream)
                    throw e
                }
                check(file.readFully().contentEquals(bytes))
            }.isSuccess
            if (persisted) runCatching {
                directory.listFiles { candidate -> candidate.extension == "json" }.orEmpty()
                    .sortedByDescending(File::lastModified).drop(128).forEach { AtomicFile(it).delete() }
            }
            dispatch { done(persisted) }
        }
    }

    override fun clear(groups: Set<String>?) {
        worker.execute {
            val prefixes = groups?.map(::weatherDigest)?.toSet()
            directory.listFiles().orEmpty().filter { prefixes == null || it.name.substringBefore('-') in prefixes }
                .forEach { it.delete() }
        }
    }

    companion object { private const val MAX_FILE = 2L * 1024 * 1024 + 256 }
}
