package com.dormpanel.app.icloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.dormpanel.app.ha.TokenCipher
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal data class CloudSettings(
    val account: String = "",
    val encryptedLogin: String = "",
    val calendars: List<CloudCalendar> = emptyList(),
    val selected: Set<String> = emptySet(),
    val refreshMinutes: Int = 30,
) {
    val connected get() = account.isNotEmpty()
}

internal data class CalendarCacheKey(val calendarId: String, val window: CalendarWindow)

internal class CloudCalendarStore(context: Context, namespace: String = "icloud-calendar-v1") {
    private val directory = File(context.noBackupFilesDir, namespace)
    private val keyAlias = "DormPanel.iCloud.Calendar.$namespace"
    private val meta = AtomicFile(File(directory, "account.json"))
    private val cipher = TokenCipher {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    fun settings(): CloudSettings {
        val json = read(meta, 256 * 1024) ?: return CloudSettings()
        val account = json.getString("account")
        check(account.isEmpty() || UUID.fromString(account).toString() == account)
        val calendars = json.getJSONArray("calendars").objects().map {
            CloudCalendar(it.getString("id"), it.getString("url").toHttpUrl(), it.getString("name"),
                it.optIntOrNull("color"), CalendarAccess.valueOf(it.getString("access")))
        }
        check(calendars.size <= MAX_CALENDARS && calendars.all { validCalendarId(it.id) })
        return CloudSettings(account, json.getString("login"), calendars,
            json.getJSONArray("selected").strings(), json.getInt("minutes").takeIf { it in refreshChoices } ?: 30)
    }

    fun save(settings: CloudSettings) = write(meta, JSONObject().put("account", settings.account)
        .put("login", settings.encryptedLogin).put("selected", JSONArray(settings.selected)).put("minutes", settings.refreshMinutes)
        .put("calendars", JSONArray(settings.calendars.map {
            JSONObject().put("id", it.id).put("url", it.url.toString()).put("name", it.name)
                .put("color", it.color).put("access", it.access.name)
        })), 256 * 1024)

    fun encrypt(email: String, password: String): String =
        cipher.encrypt(JSONObject().put("email", email).put("password", password).toString())

    fun login(settings: CloudSettings): Pair<String, String>? = cipher.decrypt(settings.encryptedLogin)?.let {
        JSONObject(it).let { json -> json.getString("email") to json.getString("password") }
    }

    fun disconnect() {
        // Remove the only persisted credential reference before deleting cache files.
        save(CloudSettings())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        pruneAccounts("")
    }

    fun pruneAccounts(retain: String) {
        directory.listFiles().orEmpty().filter { it.isDirectory && it.name != retain }.forEach { folder ->
            folder.listFiles().orEmpty().forEach { check(it.delete() || !it.exists()) }
            check(folder.delete() || !folder.exists())
        }
    }

    fun clearCache(account: String) {
        if (account.isEmpty()) return
        File(directory, account).listFiles().orEmpty().forEach { check(it.delete() || !it.exists()) }
    }

    fun snapshot(account: String, key: CalendarCacheKey): CalendarSnapshot? {
        val json = read(snapshotFile(account, key), MAX_SNAPSHOT_BYTES) ?: return null
        check(json.getString("month") == key.window.month.toString() && json.getString("zone") == key.window.zone.id)
        val objects = json.getJSONArray("objects").objects().associate { obj ->
            obj.getString("href") to CachedObject(obj.getString("etag"), obj.getJSONArray("events").objects().map { event ->
                CloudEvent(event.getString("uid"), event.optionalString("recurrence"), event.getString("title"),
                    event.getString("location"), event.getString("description"), event.getLong("start"), event.getLong("end"),
                    event.optionalString("firstDay")?.let(LocalDate::parse), event.optionalString("endDay")?.let(LocalDate::parse))
            })
        }
        check(objects.size <= MAX_OBJECTS && objects.values.sumOf { it.events.size } <= MAX_INSTANCES)
        return CalendarSnapshot(json.getLong("checkedAt"), json.optionalString("token"), objects)
    }

    fun saveSnapshot(account: String, key: CalendarCacheKey, snapshot: CalendarSnapshot) {
        val value = JSONObject().put("month", key.window.month.toString()).put("zone", key.window.zone.id)
            .put("checkedAt", snapshot.checkedAt).put("token", snapshot.collectionToken)
            .put("objects", JSONArray(snapshot.objects.map { (href, obj) ->
                JSONObject().put("href", href).put("etag", obj.etag)
                    .put("events", JSONArray(obj.events.map { event ->
                        JSONObject().put("uid", event.uid).put("recurrence", event.recurrence)
                            .put("title", event.title).put("location", event.location).put("description", event.description)
                            .put("start", event.start).put("end", event.end).put("firstDay", event.firstDay?.toString())
                            .put("endDay", event.endDay?.toString())
                    }))
            }))
        val file = snapshotFile(account, key)
        write(file, value, MAX_SNAPSHOT_BYTES)
        pruneSnapshots(account, file.baseFile)
    }

    private fun pruneSnapshots(account: String, newest: File) {
        val files = File(directory, account).listFiles().orEmpty().filter { it.name.endsWith(".json") }
            .sortedWith(compareByDescending<File> { it == newest }.thenByDescending { it.lastModified() })
        var bytes = 0L
        files.forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= 192 || bytes > 48L * 1024 * 1024) AtomicFile(file).delete()
        }
    }

    private fun snapshotFile(account: String, key: CalendarCacheKey): AtomicFile {
        check(account.isNotEmpty() && validCalendarId(key.calendarId))
        val zone = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key.window.zone.id.toByteArray())
        return AtomicFile(File(File(directory, account), "${key.calendarId}_${key.window.month}_$zone.json"))
    }

    private fun read(file: AtomicFile, limit: Int): JSONObject? {
        val input = try { file.openRead() } catch (e: FileNotFoundException) {
            if (file.baseFile.exists()) throw e
            return null
        }
        return input.use {
            check(it.channel.size() <= limit)
            val bytes = it.readBytes()
            check(bytes.size <= limit)
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }

    private fun write(file: AtomicFile, value: JSONObject, limit: Int) {
        val bytes = value.toString().toByteArray()
        check(bytes.size <= limit)
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    private companion object {
        const val MAX_SNAPSHOT_BYTES = 524_288
    }
}

internal val refreshChoices = listOf(15, 30, 60, 120)
internal fun validCalendarId(id: String) = id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }
private fun JSONObject.optionalString(name: String) = if (isNull(name)) null else getString(name)
private fun JSONObject.optIntOrNull(name: String) = if (isNull(name)) null else getInt(name)
private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
private fun JSONArray.strings() = (0 until length()).map(::getString).toSet()

internal data class CardCalendars(val selected: Set<String>? = null) {
    fun encode(): String = JSONObject().apply { selected?.let { put("icloudCalendars", JSONArray(it.sorted())) } }.toString()

    companion object {
        fun decode(json: String) = decodeOrNull(json) ?: CardCalendars()

        fun decodeOrNull(json: String): CardCalendars? = runCatching {
            val value = JSONObject(json)
            if (!value.has("icloudCalendars")) return CardCalendars()
            val ids = value.getJSONArray("icloudCalendars")
            check(ids.length() <= MAX_CALENDARS)
            CardCalendars(ids.strings().also { check(it.size == ids.length() && it.all(::validCalendarId)) })
        }.getOrNull()
    }
}
