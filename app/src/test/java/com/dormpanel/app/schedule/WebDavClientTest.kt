package com.dormpanel.app.schedule

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class WebDavClientTest {
    private val client = WebDavClient()
    private fun xml(vararg entries: String) = "<d:multistatus xmlns:d=\"DAV:\">${entries.joinToString("")}</d:multistatus>"
    private fun entry(href: String, name: String, folder: Boolean = false, modified: String? = null) =
        "<d:response><d:href>$href</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status><d:prop>" +
            "<d:displayname>$name</d:displayname><d:resourcetype>${if (folder) "<d:collection/>" else ""}</d:resourcetype>" +
            "${modified?.let { "<d:getlastmodified>$it</d:getlastmodified>" }.orEmpty()}<d:getetag>\"abc\"</d:getetag>" +
            "</d:prop></d:propstat></d:response>"

    @Test fun basicAuthAndNamespacedListing() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml(
                entry("/dav/", "dav", true), entry("/dav/%E8%AF%BE%E8%A1%A8.ics", "课表.ics"))))
            val account = WebDavAccount(server.url("/dav/").toString(), "user", "secret")
            val items = client.list(account)
            assertEquals(2, items.size)
            assertTrue(items.first().folder)
            assertEquals("课表.ics", items[1].name)
            val request = server.takeRequest()
            assertEquals("Basic dXNlcjpzZWNyZXQ=", request.getHeader("Authorization"))
            assertEquals("1", request.getHeader("Depth"))
            assertTrue(request.body.readUtf8().contains("getlastmodified"))
        }
    }
    @Test fun relativeHrefAndFileDownload() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml(entry("current.ics", "current.ics"))))
            server.enqueue(MockResponse().setBody("BEGIN:VCALENDAR\nEND:VCALENDAR")
                .addHeader("ETag", "\"v2\""))
            val account = WebDavAccount(server.url("/dav/").toString(), "u", "p")
            val item = client.list(account).single()
            assertEquals(server.url("/dav/current.ics").toString(), item.url)
            val result = client.download(account, item.url)
            assertEquals("webdav", result.artifact!!.kind)
            assertFalse(result.artifact.locator!!.contains("u:p"))
            assertEquals("\"v2\"", result.etag)
        }
    }
    @Test fun conditionalGetAndHttpErrors() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(304))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(404))
            val account = WebDavAccount(server.url("/dav/").toString(), "u", "p")
            assertNull(client.download(account, server.url("/dav/a.ics").toString(), "\"old\"").artifact)
            assertEquals("\"old\"", server.takeRequest().getHeader("If-None-Match"))
            listOf("Authentication failed", "access denied", "not found").forEach { expected ->
                val error = assertThrows(WebDavException::class.java) { client.download(account, server.url("/dav/a.ics").toString()) }
                assertTrue(error.message!!.contains(expected))
            }
        }
    }
    @Test fun rejectsDoctypeAndLargeListing() {
        val url = "https://example.test/dav/".let(client::url)
        assertThrows(WebDavException::class.java) { client.parseListing("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x/>".toByteArray(), url) }
        assertThrows(WebDavException::class.java) { client.parseListing("<!DOCTYPE x><x/>".toByteArray(Charsets.UTF_16LE), url) }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(207).setBody("x".repeat(WebDavClient.MAX_XML + 1)))
            val account = WebDavAccount(server.url("/dav/").toString(), "u", "p")
            assertThrows(WebDavException::class.java) { client.list(account) }
        }
    }
    @Test fun redirectsNeverCrossOrigin() {
        MockWebServer().use { origin -> MockWebServer().use { other ->
            origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/steal")))
            val account = WebDavAccount(origin.url("/dav/").toString(), "u", "p")
            assertThrows(WebDavException::class.java) { client.list(account) }
            assertEquals(0, other.requestCount)
        } }
    }
    @Test fun canonicalRedirectIsBoundedAndKeepsCredentialsOnSameOrigin() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(301).addHeader("Location", "/dav/"))
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml()))
            val account = WebDavAccount(server.url("/dav").toString(), "u", "p")
            assertTrue(client.list(account, depth = 0).isEmpty())
            assertEquals("Basic dTpw", server.takeRequest().getHeader("Authorization"))
            assertEquals("Basic dTpw", server.takeRequest().getHeader("Authorization"))
        }
        MockWebServer().use { server ->
            repeat(4) { server.enqueue(MockResponse().setResponseCode(301)
                .addHeader("Location", if (it % 2 == 0) "/dav" else "/dav/")) }
            val account = WebDavAccount(server.url("/dav/").toString(), "u", "p")
            assertThrows(WebDavException::class.java) { client.list(account) }
            assertEquals(4, server.requestCount)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/dav/replacement.ics"))
            val account = WebDavAccount(server.url("/dav/").toString(), "u", "p")
            assertTrue(assertThrows(WebDavException::class.java) {
                client.download(account, server.url("/dav/current.ics").toString())
            }.message!!.contains("different resource"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun lastModifiedFallbackAndTimeout() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(304))
            val account = WebDavAccount(server.url("/dav/").toString(), "u", "p")
            assertNull(client.download(account, server.url("/dav/a.ics").toString(), lastModified = "Thu, 24 Sep 2026 09:00:00 GMT").artifact)
            assertEquals("Thu, 24 Sep 2026 09:00:00 GMT", server.takeRequest().getHeader("If-Modified-Since"))
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val account = WebDavAccount(server.url("/dav/").toString(), "u", "p")
            val shortClient = WebDavClient(OkHttpClient.Builder().followRedirects(false)
                .readTimeout(300, TimeUnit.MILLISECONDS).build())
            assertTrue(assertThrows(WebDavException::class.java) { shortClient.list(account) }.message!!.contains("timed out"))
        }
    }
    @Test fun unsupportedAuthenticationIsClear() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Digest realm=\"nas\""))
            val account = WebDavAccount(server.url("/dav/").toString(), "u", "p")
            assertTrue(assertThrows(WebDavException::class.java) { client.list(account) }.message!!.contains("unsupported"))
        }
    }
    @Test fun latestRequiresReliableTimeAndUsesStableTie() {
        val a = WebDavItem("https://e/dav/a.ics", "a.ics", false, null, null, 100L, null, null)
        val b = a.copy(url = "https://e/dav/b.ICS", name = "b.ICS")
        assertEquals(a, WebDavSelection.latest(listOf(b, a)))
        assertEquals(b.copy(modifiedAt = 101L), WebDavSelection.latest(listOf(a, b.copy(modifiedAt = 101L))))
        assertThrows(WebDavException::class.java) { WebDavSelection.latest(listOf(a, b.copy(modifiedAt = null))) }
        assertEquals(b.copy(modifiedAt = null), WebDavSelection.latest(listOf(b.copy(modifiedAt = null))))
        assertEquals(a.copy(name = "Current semester"), WebDavSelection.latest(listOf(a.copy(name = "Current semester"))))
        assertThrows(WebDavException::class.java) { WebDavSelection.latest(emptyList()) }
    }
    @Test fun credentialsInUrlRejected() {
        assertThrows(WebDavException::class.java) { client.url("https://u:p@example.test/dav/") }
        assertThrows(WebDavException::class.java) { client.url("ftp://example.test/") }
    }
}
