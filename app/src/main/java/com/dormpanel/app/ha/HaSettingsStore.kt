package com.dormpanel.app.ha

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class HaTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("ha_credentials", Context.MODE_PRIVATE)
    private val alias = "DormPanel.HA.Token.v1"
    private val cipher = TokenCipher {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build())
            generateKey()
        }
    }
    fun read(): String? = prefs.getString("payload", null)?.let(cipher::decrypt)
    fun write(token: String) { prefs.edit().putString("payload", cipher.encrypt(token)).apply() }
    fun clear() {
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }
}
class HaSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ha_settings", Context.MODE_PRIVATE)
    fun read() = HaConnectionSettings(
        runCatching { BackendMode.valueOf(prefs.getString("mode", "DEMO")!!) }.getOrDefault(BackendMode.DEMO),
        prefs.getString("url", "")!!, prefs.getString("weather", "")!!,
        prefs.getString("theme", "")!!, prefs.getString("opacity", "")!!,
        prefs.getString("display_brightness", "")!!, prefs.getString("media_volume", "")!!,
        prefs.getString("system_brightness", "")!!, prefs.getString("blackout", "")!!,
        prefs.getString("system_automatic", "")!!, prefs.getString("follow_system", "")!!,
        prefs.getString("keep_awake", "")!!, prefs.getString("start_after_boot", "")!!,
    )
    fun write(value: HaConnectionSettings) { prefs.edit().putString("mode", value.mode.name).putString("url", value.baseUrl)
        .putString("weather", value.weatherEntity).putString("theme", value.themeEntity).putString("opacity", value.opacityEntity)
        .putString("display_brightness", value.displayBrightnessEntity).putString("media_volume", value.mediaVolumeEntity)
        .putString("system_brightness", value.systemBrightnessEntity).putString("blackout", value.blackoutEntity)
        .putString("system_automatic", value.systemAutomaticEntity).putString("follow_system", value.followSystemEntity)
        .putString("keep_awake", value.keepAwakeEntity).putString("start_after_boot", value.startAfterBootEntity).apply() }
}
