package com.dormpanel.app.icloud

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

/** Optional interoperability test for a disposable, authenticated, TLS-enabled Radicale on loopback. */
class CalDavRadicaleTest {
    @Test fun realServerDiscoveryFirstReadWithRecurrenceAndSubsequentDeletion() {
        val server = System.getenv("DORMPANEL_TEST_RADICALE_URL")
        val cert = System.getenv("DORMPANEL_TEST_RADICALE_CERT")
        assumeTrue(server != null && cert != null)
        val root = server!!.toHttpUrl()
        require(root.isHttps && root.host == "127.0.0.1" && root.encodedPath == "/")
        val certificate = File(cert!!).inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val tls = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        val transport = OkHttpClient.Builder().sslSocketFactory(tls.sslSocketFactory(), tls.trustManager)
            .followRedirects(false).build()
        val email = "fixture@example.invalid"
        val password = "isolated-fixture-password"
        val client = CalDavClient(email, password, root, { it == root.host }, transport)
        client.discover() // Radicale provisions the authenticated principal.

        val run = UUID.randomUUID().toString()
        val firstName = "First $run"
        val secondName = "Second $run"
        fun seed(method: String, path: String, body: String? = null, mime: String = "application/xml"): Int {
            val request = Request.Builder().url(requireNotNull(root.resolve(path))).header("Authorization", Credentials.basic(email, password))
                .method(method, body?.toRequestBody(mime.toMediaType())).build()
            return transport.newCall(request).execute().use { it.code }
        }
        fun create(path: String, title: String) = seed("MKCALENDAR", path,
            """<C:mkcalendar xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:D="DAV:">
                <D:set><D:prop><D:displayname>$title</D:displayname></D:prop></D:set>
                </C:mkcalendar>""")
        val firstPath = "/$email/$run/"
        val secondPath = "/$email/$run-other/"
        assertEquals(201, create(firstPath, firstName))
        assertEquals(201, create(secondPath, secondName))
        val objectPath = firstPath + "fixture.ics"
        val fixture = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//DormPanel//Fixture//EN
            BEGIN:VEVENT
            UID:radicale-fixture
            DTSTART:20260301T090000Z
            DTEND:20260301T100000Z
            DTSTAMP:20260101T000000Z
            RRULE:FREQ=WEEKLY;COUNT=3
            EXDATE:20260308T090000Z
            SUMMARY:Fixture series
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        assertEquals(201, seed("PUT", objectPath, fixture, "text/calendar"))
        val calendars = client.discover()
        val first = calendars.single { it.name == firstName }
        val second = calendars.single { it.name == secondName }
        assertNotEquals(first.id, second.id)
        val window = CalendarWindow(YearMonth.of(2026, 3), ZoneOffset.UTC)
        val initial = client.synchronize(first, window, null, 1)
        assertEquals(2, initial.objects.values.single().events.size)
        assertNotNull(initial.collectionToken)
        assertTrue(client.synchronize(second, window, null, 1).objects.isEmpty())
        assertEquals(initial.copy(checkedAt = 2), client.synchronize(first, window, initial, 2))
        assertTrue(seed("DELETE", objectPath) in 200..299)
        assertTrue(client.synchronize(first, window, initial, 3).objects.isEmpty())
    }
}
