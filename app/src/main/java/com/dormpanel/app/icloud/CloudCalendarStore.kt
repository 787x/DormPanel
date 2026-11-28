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
    val missingCalendars: List<CloudCalendar> = emptyList(),
) {
    val connected get() = account.isNotEmpty()

    fun rediscovered(found: List<CloudCalendar>): CloudSettings {
        val known = calendars + missingCalendars
        val confirmed = found.mapNotNull { candidate ->
            candidate.resourceId?.let { id -> known.singleOrNull { it.resourceId == id } }?.let { candidate.url to it }
        }.toMap()
        val reserved = confirmed.values.mapTo(hashSetOf()) { it.id }
        val previousIds = known.mapTo(hashSetOf()) { it.id }
        val used = hashSetOf<String>()
        val active = found.map { candidate ->
            val previous = confirmed[candidate.url] ?: known.singleOrNull {
                it.id !in reserved && it.url == candidate.url && (it.resourceId == null || candidate.resourceId == null)
            }
            val current = when {
                previous != null -> candidate.copy(id = previous.id, resourceId = candidate.resourceId ?: previous.resourceId)
                candidate.id !in previousIds -> candidate
                else -> candidate.copy(id = calendarDigest("binding:${UUID.randomUUID()}"))
            }
            checkCalendar(used.add(current.id))
            current
        }
        // Keep missing bindings available for a later rediscovery, including per-card IDs.
        // Selection IDs themselves are never removed by discovery.
        return copy(calendars = active, missingCalendars = known.filterNot { it.id in used }.take(MAX_MISSING_CALENDARS))
    }
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
        val json = read(meta, SETTINGS_BYTES) ?: return CloudSettings()
        val account = json.getString("account")
        check(account.isEmpty() || UUID.fromString(account).toString() == account)
        val calendars = json.getJSONArray("calendars").objects().map(::decodeCalendar)
        val missing = json.optJSONArray("missingCalendars")?.objects()?.map(::decodeCalendar).orEmpty()
        check(calendars.size <= MAX_CALENDARS && missing.size <= MAX_MISSING_CALENDARS)
        check((calendars + missing).map { it.id }.let { it.all(::validCalendarId) && it.size == it.toSet().size })
        return CloudSettings(account, json.getString("login"), calendars,
            json.getJSONArray("selected").strings(), json.getInt("minutes").takeIf { it in refreshChoices } ?: 30, missing)
    }

    fun save(settings: CloudSettings) = write(meta, JSONObject().put("account", settings.account)
        .put("login", settings.encryptedLogin).put("selected", JSONArray(settings.selected)).put("minutes", settings.refreshMinutes)
        .put("calendars", JSONArray(settings.calendars.map(::encodeCalendar)))
        .put("missingCalendars", JSONArray(settings.missingCalendars.map(::encodeCalendar))), SETTINGS_BYTES)

    private fun decodeCalendar(json: JSONObject) = CloudCalendar(
        json.getString("id"), json.getString("url").toHttpUrl(), json.getString("name"), json.optIntOrNull("color"),
        CalendarAccess.valueOf(json.getString("access")), json.optionalString("resourceId"),
    )

    private fun encodeCalendar(calendar: CloudCalendar) = JSONObject()
        .put("id", calendar.id).put("url", calendar.url.toString()).put("name", calendar.name)
        .put("color", calendar.color).put("access", calendar.access.name).put("resourceId", calendar.resourceId)

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
        return decodeSnapshot(json)
    }

    fun saveSnapshot(account: String, key: CalendarCacheKey, snapshot: CalendarSnapshot, active: Set<CalendarCacheKey> = emptySet()) {
        val value = encodeSnapshot(snapshot).put("month", key.window.month.toString()).put("zone", key.window.zone.id)
        val bytes = value.toString().toByteArray()
        checkCalendarCapacity(bytes.size <= MAX_SNAPSHOT_BYTES)
        val file = snapshotFile(account, key)
        val protected = active.mapTo(hashSetOf()) { snapshotFile(account, it).baseFile }
        protected.add(file.baseFile)
        val others = File(directory, account).listFiles().orEmpty().filter {
            it.name.endsWith(".json") && it != file.baseFile
        }
        val pinned = others.filter { it in protected }
        var retainedBytes = bytes.size + pinned.sumOf { it.length() }
        var retainedCount = 1 + pinned.size
        checkCalendarCapacity(retainedBytes <= TOTAL_CACHE_BYTES && retainedCount <= MAX_CACHE_FILES)
        val evict = others.filterNot { it in protected }.sortedByDescending { it.lastModified() }.filter { other ->
            if (retainedBytes + other.length() > TOTAL_CACHE_BYTES || retainedCount >= MAX_CACHE_FILES) true
            else {
                retainedBytes += other.length()
                retainedCount++
                false
            }
        }
        write(file, bytes)
        // Eviction follows the successful atomic replacement and never touches an active window.
        evict.forEach { AtomicFile(it).delete() }
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
        checkCalendarCapacity(bytes.size <= limit)
        write(file, bytes)
    }

    private fun write(file: AtomicFile, bytes: ByteArray) {
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

}

/** Repeated instances share event text. Old snapshots remain readable until replaced atomically. */
internal fun encodeSnapshot(snapshot: CalendarSnapshot): JSONObject {
    checkCalendarCapacity(snapshot.objects.size <= MAX_OBJECTS &&
        snapshot.objects.values.sumOf { it.events.size } <= MAX_INSTANCES)
    val texts = linkedMapOf<List<String>, Int>()
    var textChars = 0L
    val objects = snapshot.objects.map { (href, obj) ->
        JSONObject().put("href", href).put("etag", obj.etag).put("projectionVersion", obj.projectionVersion)
            .put("events", JSONArray(obj.events.map { event ->
                val text = listOf(event.uid, event.title, event.location, event.description)
                val index = texts.getOrPut(text) {
                    textChars += text.sumOf { it.length }.toLong()
                    checkCalendarCapacity(textChars <= MAX_SNAPSHOT_BYTES)
                    texts.size
                }
                JSONArray(listOf(index, event.recurrence, event.start, event.end,
                    event.firstDay?.toString(), event.endDay?.toString()))
            }))
    }
    return JSONObject().put("format", 2).put("checkedAt", snapshot.checkedAt).put("token", snapshot.collectionToken)
        .put("problem", snapshot.problem?.name).put("collectionUrl", snapshot.collectionUrl)
        .put("text", JSONArray(texts.keys.map { JSONArray(it) })).put("objects", JSONArray(objects))
}

internal fun decodeSnapshot(json: JSONObject): CalendarSnapshot {
    val format = json.optInt("format", 1)
    check(format in 1..2)
    val text = if (format == 2) json.getJSONArray("text") else null
    check(text == null || text.length() <= MAX_INSTANCES)
    val entries = json.getJSONArray("objects")
    check(entries.length() <= MAX_OBJECTS)
    var instances = 0
    val objects = entries.objects().associate { obj ->
        val events = obj.getJSONArray("events")
        instances += events.length()
        check(instances <= MAX_INSTANCES)
        obj.getString("href") to CachedObject(obj.getString("etag"), (0 until events.length()).map { i ->
            if (text == null) {
                val event = events.getJSONObject(i)
                CloudEvent(event.getString("uid"), event.optionalString("recurrence"), event.getString("title"),
                    event.getString("location"), event.getString("description"), event.getLong("start"), event.getLong("end"),
                    event.optionalString("firstDay")?.let(LocalDate::parse), event.optionalString("endDay")?.let(LocalDate::parse))
            } else {
                val event = events.getJSONArray(i)
                val words = text.getJSONArray(event.getInt(0))
                CloudEvent(words.getString(0), event.optionalString(1), words.getString(1), words.getString(2), words.getString(3),
                    event.getLong(2), event.getLong(3), event.optionalString(4)?.let(LocalDate::parse),
                    event.optionalString(5)?.let(LocalDate::parse))
            }
        }, obj.optInt("projectionVersion", 0))
    }
    check(objects.size == entries.length())
    return CalendarSnapshot(json.getLong("checkedAt"), json.optionalString("token"), objects,
        json.optionalString("problem")?.let(CalDavProblem::valueOf), json.optionalString("collectionUrl"))
}

internal const val MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024
private const val TOTAL_CACHE_BYTES = 48L * 1024 * 1024
private const val MAX_CACHE_FILES = 192
private fun JSONArray.optionalString(index: Int) = if (isNull(index)) null else getString(index)

private const val SETTINGS_BYTES = 512 * 1024
private const val MAX_MISSING_CALENDARS = 96

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
