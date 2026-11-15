package com.dormpanel.app.weather

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import org.json.JSONObject
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Locale

internal fun qWeatherHost(input: String): String {
    val host = input.trim().lowercase(Locale.ROOT).removePrefix("https://")
    require(host.matches(Regex("[a-z0-9]{1,63}(\\.[a-z0-9]{1,63})?\\.qweatherapi\\.com"))) {
        "请输入控制台分配的 qweatherapi.com 专属主机名"
    }
    return host
}

internal sealed interface QWeatherAuth {
    fun header(now: Instant): Pair<String, String>
    fun encode(): JSONObject

    class ApiKey(private val secret: String) : QWeatherAuth {
        init { require(secret.matches(Regex("[A-Za-z0-9_-]{1,256}"))) { "API Key 格式不正确" } }
        override fun header(now: Instant) = "X-QW-Api-Key" to secret
        override fun encode() = JSONObject().put("key", secret)
    }

    class Jwt(private val kid: String, private val issuer: String, private val subject: String, pem: String) : QWeatherAuth {
        private val encoded = pem.removePrefix("-----BEGIN PRIVATE KEY-----").removeSuffix("-----END PRIVATE KEY-----")
            .filterNot(Char::isWhitespace)
        private val privateKey = try {
            require(listOf(kid, issuer, subject).all { it.matches(Regex("[A-Za-z0-9]{1,64}")) })
            EdDSAPrivateKey(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))
        } catch (_: Exception) {
            throw IllegalArgumentException("请检查 JWT 标识和 Ed25519 PKCS#8 私钥")
        }
        private var cached: String? = null
        private var issuedAt = 0L
        private var expiry = 0L

        @Synchronized override fun header(now: Instant): Pair<String, String> {
            val seconds = now.epochSecond
            if (cached == null || seconds < issuedAt || seconds >= expiry - 60) {
                issuedAt = seconds - 30
                expiry = seconds + 870
                val header = JSONObject().put("alg", "EdDSA").put("kid", kid)
                val payload = JSONObject().put("iss", issuer).put("sub", subject).put("iat", issuedAt).put("exp", expiry)
                val encoder = Base64.getUrlEncoder().withoutPadding()
                val input = listOf(header, payload).joinToString(".") { encoder.encodeToString(it.toString().toByteArray()) }
                val signer = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
                signer.initSign(privateKey)
                signer.update(input.toByteArray())
                cached = "$input.${encoder.encodeToString(signer.sign())}"
            }
            return "Authorization" to "Bearer ${cached!!}"
        }

        override fun encode() = JSONObject().put("kid", kid).put("iss", issuer).put("sub", subject).put("private", encoded)
    }

    companion object {
        fun decode(json: JSONObject): QWeatherAuth = if (json.has("key")) ApiKey(json.getString("key"))
        else Jwt(json.getString("kid"), json.getString("iss"), json.getString("sub"), json.getString("private"))
    }
}
