package com.dormpanel.app.schedule

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.dormpanel.app.ha.TokenCipher
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

enum class WebDavMode { FILE, FOLDER_LATEST_ICS, FOLDER_LATEST_CSV }
data class WebDavBinding(val sourceId: String, val mode: WebDavMode, val remote: String,
    val autoSync: Boolean = false, val etag: String? = null, val lastModified: String? = null,
    val currentFile: String? = null, val lastSuccess: Long = 0, val lastAttempt: Long = 0,
    val lastError: String? = null,
    /** CSV-only: detected term key, last-used profile fingerprint, and format label. */
    val format: String? = null, val termKey: String? = null, val profileFingerprint: String? = null)

/** Preferences carry only non-secret state. The password is AES-GCM encrypted with an Android Keystore key. */
class WebDavSettings(context: Context) {
    companion object { @Volatile var overrideNamespace: String? = null }
    private val namespace = overrideNamespace ?: "webdav"
    private val prefs = context.getSharedPreferences("${namespace}_settings", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences("${namespace}_credentials", Context.MODE_PRIVATE)
    private val alias = "DormPanel.WebDAV.Password.$namespace.v1"
    private val cipher = TokenCipher {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build())
            generateKey()
        }
    }
    fun account(): WebDavAccount? {
        val base = prefs.getString("url", null) ?: return null
        val password = secrets.getString("payload", null)?.let(cipher::decrypt) ?: return null
        return WebDavAccount(base, prefs.getString("username", "").orEmpty(), password)
    }
    fun publicAccount(): Pair<String, String> = Pair(prefs.getString("url", "").orEmpty(), prefs.getString("username", "").orEmpty())
    fun saveAccount(account: WebDavAccount) {
        WebDavClient().url(account.baseUrl)
        secrets.edit().putString("payload", cipher.encrypt(account.password)).apply()
        prefs.edit().putString("url", account.baseUrl.trim()).putString("username", account.username).apply()
    }
    fun clearAccount() {
        prefs.edit().remove("url").remove("username").apply()
        secrets.edit().clear().apply()
    }
    fun bindings(): List<WebDavBinding> = runCatching {
        val array = JSONArray(prefs.getString("bindings", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            WebDavBinding(item.getString("sourceId"), WebDavMode.valueOf(item.getString("mode")), item.getString("remote"),
                item.optBoolean("autoSync"), item.takeUnless { it.isNull("etag") }?.optString("etag")?.ifBlank { null },
                item.takeUnless { it.isNull("lastModified") }?.optString("lastModified")?.ifBlank { null },
                item.takeUnless { it.isNull("currentFile") }?.optString("currentFile")?.ifBlank { null },
                item.optLong("lastSuccess"), item.optLong("lastAttempt"),
                item.takeUnless { it.isNull("lastError") }?.optString("lastError")?.ifBlank { null },
                item.takeUnless { it.isNull("format") }?.optString("format")?.ifBlank { null },
                item.takeUnless { it.isNull("termKey") }?.optString("termKey")?.ifBlank { null },
                item.takeUnless { it.isNull("profileFingerprint") }?.optString("profileFingerprint")?.ifBlank { null })
        }
    }.getOrDefault(emptyList())
    fun saveBindings(bindings: List<WebDavBinding>) {
        val array = JSONArray()
        bindings.forEach { item -> array.put(JSONObject().put("sourceId", item.sourceId).put("mode", item.mode.name)
            .put("remote", item.remote).put("autoSync", item.autoSync).put("etag", item.etag)
            .put("lastModified", item.lastModified).put("currentFile", item.currentFile)
            .put("lastSuccess", item.lastSuccess).put("lastAttempt", item.lastAttempt).put("lastError", item.lastError)
            .put("format", item.format).put("termKey", item.termKey).put("profileFingerprint", item.profileFingerprint)) }
        prefs.edit().putString("bindings", array.toString()).apply()
    }
    // Account lifetime is independent of timetable bindings. Preserve the existing
    // preference names and Keystore alias so upgrades retain saved credentials.
}
