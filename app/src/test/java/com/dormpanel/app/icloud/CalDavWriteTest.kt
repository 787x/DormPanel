package com.dormpanel.app.icloud

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class CalDavWriteTest {
    private val server = MockWebServer()
    private lateinit var client: CalDavClient
    private var interruptPutBeforeSend = false
    private fun url(path: String) = server.url(path).newBuilder().host("localhost").build()
    private val calendar get() = CloudCalendar("calendar", url(PATH), "Fixture", null, CalendarAccess.UNKNOWN, "urn:uuid:fixture")
    private val href get() = url("${PATH}fixed.ics")
    private val now = Instant.parse("2026-03-01T00:00:00Z")
    private val date = LocalDate.of(2026, 3, 29)
    private val original = CloudEventDraft("原始日程", "地点", "说明", date.atTime(8, 0), date.atTime(9, 0), false, ZoneOffset.UTC)
    private val target = original.copy(title = "修改日程", allDay = true, start = date.atStartOfDay(), end = date.plusDays(2).atStartOfDay())
    private val oldIcs = EditableCalendarObject.create(UID, original, now)
    private val newIcs = EditableCalendarObject.parse(oldIcs, UID, ZoneOffset.UTC).edit(target, now)

    @Before fun start() {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        server.start()
        val transport = OkHttpClient.Builder().sslSocketFactory(tls.sslSocketFactory(), tls.trustManager)
            .addInterceptor {
                if (it.request().method == "PUT" && interruptPutBeforeSend) throw java.io.IOException()
                it.proceed(it.request())
            }
            .callTimeout(3, TimeUnit.SECONDS).readTimeout(500, TimeUnit.MILLISECONDS).build()
        client = CalDavClient("fixture@example.invalid", "secret", url("/"), { it == "localhost" }, transport)
    }

    @After fun stop() { server.shutdown() }

    @Test fun connectionFailureBeforeMutationIsSafeToRetryWithSameIdentity() {
        val create = entry(CalendarWriteKind.CREATE)
        interruptPutBeforeSend = true
        enqueue(collection("bind"), response(404))
        val queued = process(create)
        assertEquals(CalendarWriteState.QUEUED, queued.state)
        assertFalse(queued.attempted)
        assertEquals(create.ref, queued.ref)
        assertRequests("PROPFIND", "GET")
        interruptPutBeforeSend = false
        enqueue(collection("bind"), response(404), response(201), remote(create.ics!!, NEW))
        assertEquals(CalendarWriteState.CONFIRMED, process(queued).state)
        assertRequests("PROPFIND", "GET", "PUT", "GET") { method, request ->
            if (method == "PUT") assertEquals(href.encodedPath, request.path)
        }
    }

    @Test fun conditionalCreateUpdateAndDeleteRequireReadback() {
        val create = entry(CalendarWriteKind.CREATE)
        enqueue(collection("bind"), response(404), response(201), remote(create.ics!!.replace("\r\n", "\n"), NEW))
        process(create).let { assertEquals(it.detail, CalendarWriteState.CONFIRMED, it.state) }
        assertRequests("PROPFIND", "GET", "PUT", "GET") { method, request ->
            if (method == "PUT") {
                assertEquals("*", request.getHeader("If-None-Match"))
                assertNull(request.getHeader("If-Match"))
                assertEquals(create.ics, request.body.readUtf8())
            }
        }

        enqueue(collection("read"), remote(), permissions("write-content"), response(204), remote(newIcs, NEW))
        var writes = 0
        assertEquals(CalendarWriteState.CONFIRMED, process(entry(CalendarWriteKind.UPDATE)) {
            writes++
            assertTrue(it.attempted)
            assertEquals(CalendarWriteState.CHECKING, it.state)
        }.state)
        assertEquals(2, writes)
        assertRequests("PROPFIND", "GET", "PROPFIND", "PUT", "GET") { method, request ->
            if (method == "PUT") assertEquals(OLD, request.getHeader("If-Match"))
        }

        enqueue(collection("unbind"), remote(newIcs, NEW), response(204), response(404))
        assertEquals(CalendarWriteState.CONFIRMED, process(entry(CalendarWriteKind.DELETE, NEW)).state)
        assertRequests("PROPFIND", "GET", "DELETE", "GET") { method, request ->
            if (method == "DELETE") assertEquals(NEW, request.getHeader("If-Match"))
        }
    }

    @Test fun lostCreationResponseRecoversOriginalHrefWithoutRepeatingPut() {
        val create = entry(CalendarWriteKind.CREATE)
        enqueue(collection("write"), response(404), response(201).setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        var persisted = create
        val uncertain = process(create) { persisted = it }
        assertEquals(uncertain.detail, CalendarWriteState.CHECKING, uncertain.state)
        assertTrue(persisted.attempted)
        assertRequests("PROPFIND", "GET", "PUT")
        enqueue(collection("write"), remote(create.ics!!, NEW))
        assertEquals(CalendarWriteState.CONFIRMED, process(persisted).state)
        assertRequests("PROPFIND", "GET") { _, request ->
            assertTrue(request.path == PATH || request.path == href.encodedPath)
        }
    }

    @Test fun lostUpdateOrDeleteResponseIsVerifiedBeforeAnyReplay() {
        val update = entry(CalendarWriteKind.UPDATE).copy(attempted = true)
        enqueue(collection("write"), remote(newIcs, NEW))
        assertEquals(CalendarWriteState.CONFIRMED, process(update).state)
        assertRequests("PROPFIND", "GET")
        enqueue(collection("write"), remote(oldIcs, NEW))
        assertEquals(CalendarWriteState.CONFLICT, process(update).state)
        assertRequests("PROPFIND", "GET")
        enqueue(collection("write"), response(404))
        assertEquals(CalendarWriteState.CONFIRMED, process(entry(CalendarWriteKind.DELETE).copy(attempted = true)).state)
        assertRequests("PROPFIND", "GET")
        enqueue(collection("write"), remote(), permissions("write"), response(200), remote(newIcs, NEW))
        assertEquals(CalendarWriteState.CONFIRMED, process(update).state)
        assertRequests("PROPFIND", "GET", "PROPFIND", "PUT", "GET")
    }

    @Test fun acknowledgedWriteWithUnavailableReadbackStaysUncertainWithoutResending() {
        val update = entry(CalendarWriteKind.UPDATE)
        enqueue(collection("all"), remote(), permissions("write"), response(204), response(503))
        val unknown = process(update)
        assertEquals(CalendarWriteState.CHECKING, unknown.state)
        assertTrue(unknown.acknowledged)
        assertRequests("PROPFIND", "GET", "PROPFIND", "PUT", "GET")
        enqueue(collection("all"), remote())
        assertEquals(CalendarWriteState.CHECKING, process(unknown).state)
        assertRequests("PROPFIND", "GET")
        enqueue(collection("all"), remote(newIcs, NEW))
        val confirmed = process(unknown)
        assertEquals(CalendarWriteState.CONFIRMED, confirmed.state)
        assertEquals(newIcs, confirmed.ics)
        assertRequests("PROPFIND", "GET")
        enqueue(collection("all"), remote(newIcs, "W/\"weak\""))
        assertEquals(CalendarWriteState.CHECKING, process(unknown).state)
        assertRequests("PROPFIND", "GET")
    }

    @Test fun staleTagsWrongUidAndPartialPrivilegesDoNotAuthorizeMutations() {
        val update = entry(CalendarWriteKind.UPDATE)
        for (remote in listOf(remote(oldIcs, NEW), remote(oldIcs, "W/\"weak\""), remote(oldIcs.replace(UID, "other"), OLD))) {
            enqueue(collection("write"), remote)
            assertEquals(CalendarWriteState.CONFLICT, process(update).state)
            assertRequests("PROPFIND", "GET")
        }
        enqueue(collection("write"), remote(), permissions("read"))
        assertEquals(CalendarWriteState.FAILED, process(update).state)
        assertRequests("PROPFIND", "GET", "PROPFIND")
        enqueue(collection("write-content"), remote())
        assertEquals(CalendarWriteState.FAILED, process(entry(CalendarWriteKind.DELETE)).state)
        assertRequests("PROPFIND", "GET")
        enqueue(collection("write-content"), response(404))
        assertEquals(CalendarWriteState.FAILED, process(entry(CalendarWriteKind.CREATE)).state)
        assertRequests("PROPFIND", "GET")
        enqueue(collection("bind"), remote(oldIcs.replace(UID, "occupied"), NEW))
        assertEquals(CalendarWriteState.CONFLICT, process(entry(CalendarWriteKind.CREATE)).state)
        assertRequests("PROPFIND", "GET")
        enqueue(collection("bind", id = "urn:uuid:different"))
        assertEquals(CalendarWriteState.FAILED, process(entry(CalendarWriteKind.CREATE)).state)
        assertRequests("PROPFIND")
    }

    @Test fun rejectionRedirectAndServerErrorsNeverFallBackToUnconditionalWrites() {
        val expectations = mapOf(401 to CalendarWriteState.FAILED, 403 to CalendarWriteState.FAILED,
            404 to CalendarWriteState.CONFLICT, 409 to CalendarWriteState.CONFLICT, 412 to CalendarWriteState.CONFLICT,
            429 to CalendarWriteState.QUEUED, 307 to CalendarWriteState.FAILED, 500 to CalendarWriteState.CHECKING,
            503 to CalendarWriteState.CHECKING)
        expectations.forEach { (code, expected) ->
            enqueue(collection("unbind"), remote(), response(code).setHeader("Location", "/other/else.ics")
                .setHeader("Retry-After", if (code == 503) "0" else "60"))
            val result = process(entry(CalendarWriteKind.DELETE))
            assertEquals("HTTP $code", expected, result.state)
            if (code in listOf(401, 403, 412)) assertFalse(result.canResumeAutomatically())
            if (code == 429) assertTrue(result.retryAt > System.currentTimeMillis())
            assertRequests("PROPFIND", "GET", "DELETE") { _, request ->
                assertTrue(request.path == PATH || request.path == href.encodedPath)
            }
        }
        enqueue(response(307).setHeader("Location", "/other/"))
        assertEquals(CalendarWriteState.FAILED, process(entry(CalendarWriteKind.CREATE)).state)
        assertRequests("PROPFIND")
        assertThrows(CalDavFailure::class.java) {
            client.mutateObject(calendar.url, url("${PATH}bad%2fchild.ics"), CalendarWriteKind.DELETE, OLD, null, CalDavRound())
        }
        assertThrows(CalDavFailure::class.java) {
            client.mutateObject(calendar.url, url("/other/else.ics"), CalendarWriteKind.DELETE, OLD, null, CalDavRound())
        }
        assertThrows(CalDavFailure::class.java) {
            client.mutateObject(calendar.url, href, CalendarWriteKind.DELETE, "W/\"weak\"", null, CalDavRound())
        }
        assertNull(server.takeRequest(25, TimeUnit.MILLISECONDS))
    }

    private fun entry(kind: CalendarWriteKind, etag: String = OLD): CalendarWrite {
        val ref = CloudObjectRef("account", calendar.id, calendar.url, href, UID, null,
            etag.takeUnless { kind == CalendarWriteKind.CREATE }, calendar.resourceId)
        return CalendarWrite("intent", ref, calendar.name, kind, target, when (kind) {
            CalendarWriteKind.CREATE -> EditableCalendarObject.create(UID, target, now)
            CalendarWriteKind.UPDATE -> newIcs
            CalendarWriteKind.DELETE -> null
        })
    }

    private fun process(entry: CalendarWrite, persist: (CalendarWrite) -> Unit = {}) =
        processCalendarWrite(client, calendar, entry, CalDavRound(), persist)

    private fun assertRequests(vararg methods: String, check: (String, okhttp3.mockwebserver.RecordedRequest) -> Unit = { _, _ -> }) {
        for (method in methods) {
            val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals(method, request.method)
            assertTrue(request.getHeader("Authorization")?.startsWith("Basic ") == true)
            check(method, request)
        }
        assertNull(server.takeRequest(25, TimeUnit.MILLISECONDS))
    }

    private fun enqueue(vararg responses: MockResponse) = responses.forEach(server::enqueue)
    private fun response(code: Int) = MockResponse().setResponseCode(code)
    private fun remote(ics: String = oldIcs, etag: String = OLD) = response(200).setHeader("ETag", etag).setBody(ics)

    private fun collection(privilege: String, id: String = calendar.resourceId!!) =
        permissions(privilege, PATH, "<D:resourcetype><C:calendar/></D:resourcetype><D:resource-id><D:href>$id</D:href></D:resource-id>")

    private fun permissions(privilege: String, path: String = href.encodedPath, extra: String = "") =
        response(207).setBody("""<D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
            <D:response><D:href>$path</D:href><D:propstat><D:prop>$extra
            <D:current-user-privilege-set><D:privilege><D:$privilege/></D:privilege></D:current-user-privilege-set>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>""")

    private companion object {
        const val PATH = "/test/dates/"
        const val UID = "fixture@dormpanel"
        const val OLD = "\"old\""
        const val NEW = "\"new\""
    }
}
