package com.dormpanel.app.schedule

import okhttp3.OkHttpClient
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Connection-scoped exact leaf trust. Never installs a global CA or accepts arbitrary peers. */
internal object WebDavTls {
    fun certificate(pem:String):X509Certificate {
        require(pem.toByteArray(Charsets.UTF_8).size<=16384 && Regex("-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\s]+-----END CERTIFICATE-----").matches(pem.trim())) { "需要单个 PEM 公钥证书" }
        val cert=CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate
        cert.checkValidity();return cert
    }
    fun fingerprint(pem:String)=digest(certificate(pem).encoded)
    fun client(base:OkHttpClient,pem:String):OkHttpClient {
        val expected=certificate(pem)
        val trust=object:X509TrustManager {
            override fun getAcceptedIssuers()=arrayOf(expected)
            override fun checkClientTrusted(chain:Array<out X509Certificate>?,authType:String?) { throw CertificateException("WebDAV client certificate unsupported") }
            override fun checkServerTrusted(chain:Array<out X509Certificate>?,authType:String?) {
                val leaf=chain?.firstOrNull() ?: throw CertificateException("WebDAV certificate missing")
                leaf.checkValidity()
                if(!leaf.encoded.contentEquals(expected.encoded))throw CertificateException("WebDAV certificate changed")
            }
        }
        val context=SSLContext.getInstance("TLS").apply { init(null,arrayOf(trust),null) }
        return base.newBuilder().sslSocketFactory(context.socketFactory,trust).hostnameVerifier { _,session ->
            runCatching { session.peerCertificates.first().encoded.contentEquals(expected.encoded) }.getOrDefault(false)
        }.build()
    }
}
