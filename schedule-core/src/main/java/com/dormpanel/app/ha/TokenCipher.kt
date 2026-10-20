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
