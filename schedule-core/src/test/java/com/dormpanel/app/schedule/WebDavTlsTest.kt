package com.dormpanel.app.schedule

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class WebDavTlsTest {
    @Test fun exactSelfSignedLeafWorksForIpAndOtherCertificateIsRejected() {
        val leaf=HeldCertificate.Builder().commonName("fnOS").addSubjectAlternativeName("fnOS").build()
        val wrong=HeldCertificate.Builder().commonName("fnOS").addSubjectAlternativeName("fnOS").build()
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf).build().sslSocketFactory(),false)
            server.enqueue(MockResponse().setBody("exact certificate"));server.start()
            val request=Request.Builder().url(server.url("/")).build()
            val base=OkHttpClient.Builder().retryOnConnectionFailure(false).build()
            assertThrows(java.io.IOException::class.java) { base.newCall(request).execute() }
            WebDavTls.client(base,leaf.certificatePem()).newCall(request).execute().use { assertEquals("exact certificate",it.body!!.string()) }
            assertThrows(java.io.IOException::class.java) { WebDavTls.client(base,wrong.certificatePem()).newCall(request).execute() }
            assertFalse(WebDavAccount("https://localhost/","user","secret",leaf.certificatePem()).toString().contains("CERTIFICATE"))
        }
    }
    @Test fun expiredMalformedAndMultipleCertificatesAreRejected() {
        val expired=HeldCertificate.Builder().validityInterval(1,1000).build().certificatePem()
        assertThrows(Exception::class.java) { WebDavTls.certificate(expired) }
        assertThrows(Exception::class.java) { WebDavTls.certificate("invalid") }
        val pem=HeldCertificate.Builder().build().certificatePem()
        assertThrows(Exception::class.java) { WebDavTls.certificate(pem+pem) }
        assertThrows(Exception::class.java) { WebDavTls.certificate(pem+"PRIVATE KEY") }
    }
}
