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

class TokenCipher(private val key: () -> SecretKey) {
    fun encrypt(token: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.getEncoder().encodeToString(cipher.iv) + ":" + Base64.getEncoder().encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)))
    }
    fun decrypt(payload: String): String? = runCatching {
        val parts = payload.split(':'); require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])))
        String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8)
    }.getOrNull()
}
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
    )
    fun write(value: HaConnectionSettings) { prefs.edit().putString("mode", value.mode.name).putString("url", value.baseUrl)
        .putString("weather", value.weatherEntity).putString("theme", value.themeEntity).putString("opacity", value.opacityEntity).apply() }
}
