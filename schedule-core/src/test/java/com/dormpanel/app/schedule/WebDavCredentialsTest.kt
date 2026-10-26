package com.dormpanel.app.schedule

import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class WebDavCredentialsTest {
    private fun listing()=MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"/>")
    @Test fun originAndUsernameChangesCannotInheritAuthorization() {
        MockWebServer().use { a->MockWebServer().use { b->
            a.start();b.start()
            val old=WebDavAccount(a.url("/dav/").toString(),"user-a","secret-a")
            val same=WebDavCredentials.candidate(old,a.url("/dav/child/").toString(),"user-a","",null)
            a.enqueue(listing());WebDavClient().list(same)
            assertEquals(Credentials.basic("user-a","secret-a"),a.takeRequest().getHeader("Authorization"))
            for((url,user) in listOf(b.url("/").toString() to "user-a",old.baseUrl to "user-b")) {
                assertThrows(IllegalArgumentException::class.java) { WebDavClient().list(WebDavCredentials.candidate(old,url,user,"",null)) }
            }
            assertEquals(1,a.requestCount);assertEquals(0,b.requestCount)
            b.enqueue(listing())
            WebDavClient().list(WebDavCredentials.candidate(old,b.url("/").toString(),"user-b","secret-b",null))
            assertEquals(Credentials.basic("user-b","secret-b"),b.takeRequest().getHeader("Authorization"))
        } }
    }
    @Test fun exactTrustIdentityIsRequiredBeforeReusingPassword() {
        val first=HeldCertificate.Builder().commonName("first").build()
        val second=HeldCertificate.Builder().commonName("second").build()
        MockWebServer().use { a->MockWebServer().use { b->
            a.useHttps(HandshakeCertificates.Builder().heldCertificate(first).build().sslSocketFactory(),false)
            b.useHttps(HandshakeCertificates.Builder().heldCertificate(second).build().sslSocketFactory(),false)
            a.start();b.start()
            val old=WebDavAccount(a.url("/dav/").toString(),"u","secret-a",first.certificatePem())
            a.enqueue(listing())
            val same=WebDavCredentials.candidate(old,a.url("/child/").toString(),"u","","\n"+first.certificatePem()+"\n")
            WebDavClient().list(same);assertEquals(Credentials.basic("u","secret-a"),a.takeRequest().getHeader("Authorization"))
            for(trust in listOf(null,second.certificatePem()))assertThrows(IllegalArgumentException::class.java) {
                WebDavClient().list(WebDavCredentials.candidate(old,old.baseUrl,"u","",trust))
            }
            assertEquals(1,a.requestCount);assertEquals(0,b.requestCount)
            b.enqueue(listing());WebDavClient().list(WebDavCredentials.candidate(old,b.url("/").toString(),"u","secret-b",second.certificatePem()))
            assertEquals(Credentials.basic("u","secret-b"),b.takeRequest().getHeader("Authorization"))
            assertEquals(first.certificatePem(),WebDavCredentials.inheritedCertificate(old,a.url("/child/").toString(),"u"))
            assertNull(WebDavCredentials.inheritedCertificate(old,b.url("/").toString(),"u"))
        } }
    }
    @Test fun missingPasswordAndMissingSharedFolderDoNotDefaultToServerRoot() {
        MockWebServer().use { server->
            server.start();val account=WebDavAccount(server.url("/").toString(),"u","secret")
            assertThrows(IllegalArgumentException::class.java) { WebDavCredentials.candidate(null,account.baseUrl,"u","",null) }
            assertThrows(WebDavException::class.java) { SharedScheduleTransport().folder(account,"") }
            assertEquals(0,server.requestCount)
            assertEquals(account.baseUrl,SharedScheduleTransport().folder(account,account.baseUrl))
        }
    }
}
