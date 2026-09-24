package com.dormpanel.app.ha

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Per-installation credential; preference file is excluded from both backup mechanisms. */
class HaRelayIdentityStore(context: Context) {
    private val preferences = context.getSharedPreferences("ha_relay_identity", Context.MODE_PRIVATE)
    private val alias = "DormPanel.HA.Relay.v1"
    private val cipher = TokenCipher {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build())
            generateKey()
        }
    }
    val installationId: String
    private val secret: String
    var displayName: String
        private set

    init {
        val storedId = preferences.getString("installation_id", null)
        val storedSecret = preferences.getString("secret_payload", null)?.let(cipher::decrypt)
        if (storedId != null && storedSecret != null) {
            installationId = storedId
            secret = storedSecret
        } else {
            installationId = UUID.randomUUID().toString()
            secret = ByteArray(32).also(SecureRandom()::nextBytes).let {
                Base64.getUrlEncoder().withoutPadding().encodeToString(it)
            }
            check(preferences.edit().putString("installation_id", installationId)
                .putString("secret_payload", cipher.encrypt(secret)).commit())
        }
        displayName = preferences.getString("display_name", "DormPanel X08E") ?: "DormPanel X08E"
    }

    fun setDisplayName(value: String) {
        val normalized = value.trim().take(80)
        require(normalized.isNotEmpty())
        displayName = normalized
        preferences.edit().putString("display_name", normalized).apply()
    }

    internal fun credentials(): Pair<String, String> = installationId to secret
    override fun toString() = "HaRelayIdentityStore(installationId=$installationId, displayName=$displayName)"
}
