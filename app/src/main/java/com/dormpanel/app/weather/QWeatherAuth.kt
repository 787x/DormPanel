package com.dormpanel.app.weather

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.KeyPairGenerator
import org.json.JSONObject
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Locale

internal const val QWEATHER_CREDENTIAL_GUIDE = "https://dev.qweather.com/docs/configuration/project-and-key/#add-credential-for-jwt"

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

    class Jwt(val kid: String, val issuer: String, val subject: String, val key: QWeatherSigningKey) : QWeatherAuth {
        constructor(kid: String, issuer: String, subject: String, pem: String) :
            this(kid, issuer, subject, QWeatherSigningKey.read(pem))

        init {
            require(listOf(kid, issuer, subject).all { it.matches(Regex("[A-Za-z0-9]{1,64}")) }) {
                "JWT 标识应为 1–64 位字母或数字"
            }
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
                cached = "$input.${encoder.encodeToString(key.sign(input.toByteArray()))}"
            }
            return "Authorization" to "Bearer ${cached!!}"
        }

        override fun encode() = JSONObject().put("kid", kid).put("iss", issuer).put("sub", subject).put("private", key.encode())
    }

    companion object {
        fun decode(json: JSONObject): QWeatherAuth = if (json.has("key")) ApiKey(json.getString("key"))
        else Jwt(json.getString("kid"), json.getString("iss"), json.getString("sub"), json.getString("private"))
    }
}

internal class QWeatherSigningKey private constructor(private val privateKey: EdDSAPrivateKey) {
    // RFC 8410 SubjectPublicKeyInfo with absent parameters and a 32-byte Ed25519 public key.
    private val spki get() = Base64.getDecoder().decode("MCowBQYDK2VwAyEA") + privateKey.abyte
    val publicPem get() = "-----BEGIN PUBLIC KEY-----\n${Base64.getEncoder().encodeToString(spki)}\n-----END PUBLIC KEY-----\n"
    val spkiSha256 get() = sha256(spki)
    // QWeather documents trimming the uploaded PEM text before hashing it. Export uses LF.
    val qWeatherPemSha256 get() = sha256(publicPem.trim().toByteArray(Charsets.UTF_8))

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    fun encode(): String = Base64.getEncoder().encodeToString(privateKey.encoded)
    fun sign(input: ByteArray): ByteArray = EdDSAEngine(MessageDigest.getInstance("SHA-512")).run {
        initSign(privateKey)
        update(input)
        sign()
    }

    companion object {
        fun generate() = QWeatherSigningKey(KeyPairGenerator().generateKeyPair().private as EdDSAPrivateKey)
        fun read(pem: String): QWeatherSigningKey = try {
            val encoded = pem.trim().removePrefix("-----BEGIN PRIVATE KEY-----").removeSuffix("-----END PRIVATE KEY-----")
                .filterNot(Char::isWhitespace)
            QWeatherSigningKey(EdDSAPrivateKey(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded))))
        } catch (_: Exception) {
            throw IllegalArgumentException("请检查 Ed25519 PKCS#8 私钥")
        }
    }
}
