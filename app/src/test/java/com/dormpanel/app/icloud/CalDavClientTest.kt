package com.dormpanel.app.icloud

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class CalDavClientTest {
    private val server = MockWebServer()
    private val endpoint get() = server.url("/").newBuilder().host("localhost").build()
    private lateinit var client: CalDavClient
    private lateinit var transport: OkHttpClient
    private val window = CalendarWindow(YearMonth.of(2026, 3), ZoneId.of("America/New_York"))
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private var token: String? = "initial"
    private var afterToken: String? = null
    private var tokenReads = 0
    private var status = 0
    private var partial = false
    private var truncate = false
    private var externalRedirect: String? = null
    private var manifestOverride: String? = null
    private var alteredTag = false
    @Volatile private var manifestDelay = 0L
    @Volatile private var manifestStarted: CountDownLatch? = null
    private var workPath = "/account/calendars/work/"
    private var familyPath = "/account/calendars/family/"
    private var workResource: String? = null
    private val records = linkedMapOf("a.ics" to ("one" to basic("Shared name")), "b.ics" to ("one" to basic("Retained")))

    @Before fun start() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        transport = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .retryOnConnectionFailure(false).build()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val xml = request.body.readUtf8()
                requests += (request.method.orEmpty() + " " + request.path) to xml
                assertTrue(request.getHeader("Authorization").orEmpty().startsWith("Basic "))
                if (status != 0) return MockResponse().setResponseCode(status).setHeader("Retry-After", "90")
                return when (request.path) {
                    "/.well-known/caldav" -> MockResponse().setResponseCode(301).setHeader("Location", externalRedirect ?: "/")
                    "/" -> dav(item("/", "<D:current-user-principal><D:href>/account/</D:href></D:current-user-principal>"))
                    "/account/" -> dav(item("/account/", "<C:calendar-home-set><D:href>/account/calendars/</D:href></C:calendar-home-set>"))
                    "/account/calendars/" -> dav(item("/account/calendars/", type) +
                        calendar(workPath, "Work", "#123456FF", "<D:write-content/>", workResource) +
                        calendar(familyPath, if (workResource == null) "Family" else "Work", "#ff7200", "") +
                        item("/account/calendars/tasks/", "<D:resourcetype><D:collection/><C:calendar/></D:resourcetype>" +
                            "<C:supported-calendar-component-set><C:comp name=\"VTODO\"/></C:supported-calendar-component-set>"))
                    workPath, familyPath -> {
                        val path = request.path!!
                        when {
                            tokenTag in xml -> {
                                val current = if (++tokenReads == 2 && afterToken != null) afterToken else token
                                dav(if (current == null) item(path, tokenTag, 404) else item(path, "<D:sync-token>$current</D:sync-token>"))
                            }
                            "<C:calendar-query" in xml -> {
                                assertTrue(xml.contains("<C:time-range"))
                                val body = dav(manifestOverride ?: records.entries.joinToString("") { (file, pair) ->
                                    item(path + file, etag(pair.first))
                                })
                                manifestStarted?.countDown()
                                if (truncate) body.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                                else body.setHeadersDelay(manifestDelay, TimeUnit.MILLISECONDS)
                            }
                            "<C:calendar-multiget" in xml -> {
                                val matching = records.entries.filter { (file, _) -> "<D:href>$path$file</D:href>" in xml }
                                dav((if (partial) matching.dropLast(1) else matching).joinToString("") { (file, pair) ->
                                    item(path + file, etag(if (alteredTag) "race" else pair.first) + "<C:calendar-data><![CDATA[${pair.second}]]></C:calendar-data>")
                                })
                            }
                            else -> MockResponse().setResponseCode(400)
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        client = CalDavClient("example@example.invalid", "fixture-password", endpoint, { it == "localhost" }, transport)
    }

    @After fun shutdown() { server.shutdown() }

    @Test fun discoversMultipleCalendarsByPrincipalHomeAndPreservesPermissionsAndColors() {
        val calendars = client.discover()
        assertEquals(listOf("Work", "Family"), calendars.map { it.name })
        assertEquals(listOf(CalendarAccess.WRITABLE, CalendarAccess.READ_ONLY), calendars.map { it.access })
        assertEquals(0xff123456.toInt(), calendars.first().color)
        assertNotEquals(calendars.first().id, calendars.last().id)
        assertTrue(requests.all { it.first.startsWith("PROPFIND ") })
        val a = client.synchronize(calendars[0], window, null, 1L).objects.values.first().events.single()
        val b = client.synchronize(calendars[1], window, null, 1L).objects.values.first().events.single()
        assertEquals(a, b)
    }

    @Test fun reusesTokenAndEtagsThenAtomicallyReflectsAddModifyAndDelete() {
        val calendar = client.discover().first()
        val first = client.synchronize(calendar, window, null, 10)
        assertEquals(setOf("Shared name", "Retained"), first.titles())
        requests.clear()
        assertEquals(first.copy(checkedAt = 20), client.synchronize(calendar, window, first, 20))
        assertEquals(1, requests.size)

        token = "replacement"
        records["a.ics"] = "two" to basic("Updated")
        records["c.ics"] = "one" to basic("New")
        requests.clear()
        val updated = client.synchronize(calendar, window, first, 30)
        assertEquals(setOf("Updated", "New", "Retained"), updated.titles())
        val multiget = requests.single { "calendar-multiget" in it.second }.second
        assertTrue("a.ics" in multiget && "c.ics" in multiget)
        assertFalse("b.ics" in multiget)
        assertFalse(requests.any { "sync-collection" in it.second })

        token = null
        records.remove("a.ics")
        requests.clear()
        val deleted = client.synchronize(calendar, window, updated, 40)
        assertNull(deleted.collectionToken)
        assertEquals(setOf("New", "Retained"), deleted.titles())
        assertFalse(requests.any { "calendar-multiget" in it.second })
        records.clear()
        assertTrue(client.synchronize(calendar, window, deleted, 50).objects.isEmpty())
    }

    @Test fun incompleteMalformedInconsistentAndUnparseableDataCannotReplaceSnapshot() {
        val calendar = client.discover().first()
        val original = client.synchronize(calendar, window, null, 10)
        token = "dirty"
        records["a.ics"] = "two" to basic("Changed")
        partial = true
        expect(CalDavProblem.DATA) { client.synchronize(calendar, window, original, 20) }
        partial = false
        alteredTag = true
        expect(CalDavProblem.DATA) { client.synchronize(calendar, window, original, 20) }
        alteredTag = false
        tokenReads = 0
        afterToken = "changed-during-read"
        expect(CalDavProblem.DATA) { client.synchronize(calendar, window, original, 20) }
        afterToken = null
        records["a.ics"] = "three" to basic("Unsupported").replace("END:VEVENT", "RRULE:FREQ=SECONDLY\r\nEND:VEVENT")
        assertEquals(CalDavProblem.DATA, client.synchronize(calendar, window, original, 20).problem)
        assertEquals(setOf("Shared name", "Retained"), original.titles())
        records["a.ics"] = "four" to basic("Restored")
        assertEquals(setOf("Restored", "Retained"), client.synchronize(calendar, window, original, 30).titles())

        token = null
        val href = "/account/calendars/work/a.ics"
        manifestOverride = item(href, "<D:getetag/>") + item(href, etag("two"))
        expect(CalDavProblem.DATA) { client.synchronize(calendar, window, original, 40) }
        manifestOverride = item(href, etag("two"), 403)
        expect(CalDavProblem.ACCESS) { client.synchronize(calendar, window, original, 40) }
    }

    @Test fun distinguishesAuthAccessRateLimitAndInterruptedResponses() {
        val calendar = client.discover().first()
        val cache = client.synchronize(calendar, window, null, 100)
        status = 401
        expect(CalDavProblem.AUTH) { client.synchronize(calendar, window, cache, 200) }
        status = 403
        expect(CalDavProblem.ACCESS) { client.synchronize(calendar, window, cache, 200) }
        status = 429
        val limited = expect(CalDavProblem.LIMITED) { client.synchronize(calendar, window, cache, 200) }
        assertTrue(limited.retryAt > System.currentTimeMillis() + 60_000)
        status = 0
        token = null
        truncate = true
        expect(CalDavProblem.NETWORK) { client.synchronize(calendar, window, cache, 200) }
        assertEquals(setOf("Shared name", "Retained"), cache.titles())
    }

    @Test fun refusesUntrustedRedirectCleartextAndUntrustedCertificates() {
        externalRedirect = "http://localhost:${server.port}/"
        expect(CalDavProblem.TLS, client::discover)
        externalRedirect = "https://example.invalid/"
        expect(CalDavProblem.TLS, client::discover)
        assertEquals(2, requests.size)
        externalRedirect = null
        val untrusted = CalDavClient("a@b.c", "pw", endpoint, { it == "localhost" })
        expect(CalDavProblem.TLS, untrusted::discover)
        assertEquals(2, requests.size)
    }

    @Test fun parsesSeriesAcrossDstWithMovedExcludedAndCancelledInstancesAndKeepsTimeZones() {
        val raw = ics(
            "UID:weekly\nDTSTART;TZID=America/New_York:20260301T090000\nDTEND;TZID=America/New_York:20260301T100000\n" +
                "RRULE:FREQ=WEEKLY;COUNT=5\nEXDATE;TZID=America/New_York:20260322T090000\nSUMMARY:Lab",
            "UID:weekly\nRECURRENCE-ID;TZID=America/New_York:20260315T090000\nDTSTART;TZID=America/Los_Angeles:20260316T090000\n" +
                "DURATION:PT1H\nSUMMARY:Moved\nLOCATION:Room\\, 7\nDESCRIPTION:Line 1\\nLine 2",
            "UID:weekly\nRECURRENCE-ID:20260329T130000Z\nSTATUS:CANCELLED",
        )
        val events = readCloudEvents(raw, window)
        assertEquals(listOf("Lab", "Lab", "Moved"), events.map { it.title })
        assertEquals(listOf("2026-03-01T14:00:00Z", "2026-03-08T13:00:00Z", "2026-03-16T16:00:00Z"),
            events.map { Instant.ofEpochMilli(it.start).toString() })
        assertEquals("2026-03-15T13:00:00Z", events.last().recurrence)
        assertEquals("Room, 7", events.last().location)
        assertEquals("Line 1\nLine 2", events.last().description)
    }

    @Test fun treatsAllDayEndAsExclusiveAndSupportsCrossDayAndMonthlySetPosition() {
        val allDay = readCloudEvents(ics("UID:day\nDTSTART;VALUE=DATE:20260307\nDTEND;VALUE=DATE:20260310\nSUMMARY:All day"), window).single()
        assertEquals(LocalDate.of(2026, 3, 7), allDay.firstDay)
        assertEquals(LocalDate.of(2026, 3, 10), allDay.endDay)
        assertEquals(71 * 3600L, allDay.end(window.zone).epochSecond - allDay.start(window.zone).epochSecond)
        val endDay = LocalDate.of(2026, 3, 10)
        assertFalse(allDay.overlaps(endDay.atStartOfDay(window.zone).toInstant(), endDay.plusDays(1).atStartOfDay(window.zone).toInstant(), window.zone))

        val utc = window.copy(zone = ZoneOffset.UTC)
        val overnight = readCloudEvents(ics("UID:overnight\nDTSTART:20260228T230000Z\nDTEND:20260301T023000Z"), utc).single()
        assertEquals(Instant.parse("2026-03-01T02:30:00Z"), overnight.end(ZoneOffset.UTC))
        val monthly = readCloudEvents(ics("UID:monthly\nDTSTART;VALUE=DATE:20260130\n" +
            "RRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1\nRDATE;VALUE=DATE:20260310"), window)
        assertEquals(setOf(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 31)), monthly.map { it.firstDay }.toSet())
    }

    @Test fun recurrenceDoesNotCountDstGapAndHonorsAnEmbeddedTimezone() {
        val gap = readCloudEvents(ics("UID:gap\nDTSTART;TZID=America/New_York:20260307T023000\n" +
            "RRULE:FREQ=DAILY;COUNT=3\nDURATION:PT30M"), window)
        assertEquals(listOf("2026-03-07T07:30:00Z", "2026-03-09T06:30:00Z", "2026-03-10T06:30:00Z"),
            gap.map { Instant.ofEpochMilli(it.start).toString() })

        val embedded = "BEGIN:VTIMEZONE\r\nTZID:Fixture/Offset\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\n" +
            "TZOFFSETFROM:+0430\r\nTZOFFSETTO:+0430\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n"
        val event = ics("UID:embedded\nDTSTART;TZID=Fixture/Offset:20260313T093000").replace("BEGIN:VEVENT", embedded + "BEGIN:VEVENT")
        assertEquals(Instant.parse("2026-03-13T05:00:00Z"), readCloudEvents(event, window).single().start(ZoneOffset.UTC))
    }

    @Test fun rejectsExpansionOverflowAndMalformedIcalRatherThanDisplayingASingleEvent() {
        for (input in listOf(
            ics("UID:x\nDTSTART:20260230T090000Z"),
            ics("UID:x\nDTSTART:20260301T090000Z\nRRULE:FREQ=WEEKLY;FREQ=DAILY"),
            ics("UID:x\nDTSTART:20260301T090000Z\nDTSTART:20260302T090000Z"),
            ics("UID:x\nDTSTART:20260301T090000Z\nRRULE:FREQ=WEEKLY;BYHOUR=1,2"),
            ics("UID:x\nDTSTART:20260301T090000Z", "UID:x\nRECURRENCE-ID;RANGE=THISANDPRIOR:20260301T090000Z\nDTSTART:20260301T080000Z"),
            ics("UID:x\nDTSTART:20260301T090000Z").replace("END:VEVENT", ""),
        )) expect(CalDavProblem.DATA) { readCloudEvents(input, window) }
        val budget = ExpansionBudget().apply { scan(180_000) }
        expect(CalDavProblem.CAPACITY) {
            readCloudEvents(ics("UID:x\nDTSTART:20260301T090000Z\nRRULE:FREQ=DAILY"), window, budget)
        }
    }

    @Test fun appliesFutureEditByOriginalIdThroughDstAndHonorsDetachedAndExcludedInstances() {
        val tz = "America/New_York"
        val raw = ics(
            "UID:future\nDTSTART;TZID=$tz:20260222T090000\nDURATION:PT1H\nSUMMARY:Before\n" +
                "RRULE:FREQ=WEEKLY;COUNT=8\nEXDATE;TZID=$tz:20260322T090000\nRDATE;TZID=$tz:20260317T090000",
            "UID:future\nRECURRENCE-ID;RANGE=thisandfuture:20260301T140000Z\n" +
                "DTSTART;TZID=$tz:20260302T100000\nDURATION:PT2H\nSUMMARY:After",
            "UID:future\nRECURRENCE-ID;TZID=$tz:20260315T090000\nDTSTART;TZID=America/Los_Angeles:20260315T120000\n" +
                "DURATION:PT30M\nSUMMARY:Single",
            "UID:future\nRECURRENCE-ID;TZID=$tz:20260329T090000\nSTATUS:CANCELLED",
        )
        val before = readCloudEvents(raw, window.copy(month = YearMonth.of(2026, 2))).single()
        assertEquals("Before", before.title)
        val march = readCloudEvents(raw, window).sortedBy { it.start }
        assertEquals(listOf("After", "After", "Single", "After"), march.map { it.title })
        assertEquals(listOf("2026-03-02T15:00:00Z", "2026-03-09T14:00:00Z", "2026-03-15T19:00:00Z", "2026-03-18T14:00:00Z"),
            march.map { Instant.ofEpochMilli(it.start).toString() })
        assertEquals(listOf("2026-03-01T14:00:00Z", "2026-03-08T13:00:00Z", "2026-03-15T13:00:00Z", "2026-03-17T13:00:00Z"),
            march.map { it.recurrence })
        assertEquals(listOf(120, 120, 30, 120), march.map { ((it.end - it.start) / 60000).toInt() })
        val april = readCloudEvents(raw, window.copy(month = YearMonth.of(2026, 4)))
        assertEquals(listOf("2026-04-06T14:00:00Z", "2026-04-13T14:00:00Z"), april.map { Instant.ofEpochMilli(it.start).toString() })
    }

    @Test fun futureAllDayEditsCanMoveInstancesIntoAnotherMonthAndLaterEditsReplaceTheRange() {
        val dates = readCloudEvents(ics(
            "UID:day\nDTSTART;VALUE=DATE:20260220\nRRULE:FREQ=WEEKLY;COUNT=5\nSUMMARY:Original",
            "UID:day\nRECURRENCE-ID;VALUE=DATE;RANGE=THISANDFUTURE:20260227\nDTSTART;VALUE=DATE:20260228\n" +
                "DTEND;VALUE=DATE:20260303\nSUMMARY:Three days",
            "UID:day\nRECURRENCE-ID;VALUE=DATE;RANGE=THISANDFUTURE:20260313\nSTATUS:CANCELLED",
        ), window).sortedBy { it.start }
        assertEquals(listOf("2026-02-27", "2026-03-06"), dates.map { it.recurrence })
        assertEquals(listOf(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 7)), dates.map { it.firstDay })
        assertEquals(listOf(LocalDate.of(2026, 3, 3), LocalDate.of(2026, 3, 10)), dates.map { it.endDay })
    }

    @Test fun unsupportedResourceKeepsOldObjectAndUnconfirmedAbsenceWithoutBlockingValidEdits() {
        val calendar = client.discover().first()
        records["error.ics"] = "v1" to basic("Old rule")
        val previous = client.synchronize(calendar, window, null, 1)
        records["a.ics"] = "v2" to basic("Updated")
        records.remove("b.ics")
        records["new.ics"] = "v1" to basic("Added")
        records["error.ics"] = "v2" to basic("Old rule").replace("END:VEVENT", "EXRULE:FREQ=DAILY\r\nEND:VEVENT")
        token = "dirty"
        val mixed = client.synchronize(calendar, window, previous, 2)
        assertEquals(setOf("Updated", "Added", "Old rule", "Retained"), mixed.titles())
        assertEquals(CalDavProblem.DATA, mixed.problem)
        assertEquals(1, mixed.checkedAt)
        assertNull(mixed.collectionToken)
        assertEquals(mixed, decodeSnapshot(JSONObject(encodeSnapshot(mixed).toString())))
        records["error.ics"] = "v3" to basic("Resolved")
        val resolved = client.synchronize(calendar, window, mixed, 3)
        assertNull(resolved.problem)
        assertEquals("dirty", resolved.collectionToken)
        assertEquals(setOf("Updated", "Added", "Resolved"), resolved.titles())
    }

    @Test fun rediscoveryRetainsLegacyAndStableBindingsAndDoesNotGuessFromSameName() {
        val old = client.discover()
        val work = old.first()
        val family = old.last()
        val settings = CloudSettings(calendars = old, selected = setOf(work.id, family.id))
        workResource = "urn:uuid:4754216c-f21c-4fd5-a022-c8edfce188a2"
        val upgraded = settings.rediscovered(client.discover())
        assertEquals(work.id, upgraded.calendars.first().id)
        val previous = client.synchronize(upgraded.calendars.first(), window, null, 1)
        workPath = "/account/calendars/work-moved/"
        familyPath = "/account/calendars/family-moved/"
        val moved = upgraded.rediscovered(client.discover())
        assertEquals(listOf("Work", "Work"), moved.calendars.map { it.name })
        assertEquals(work.id, moved.calendars.first().id)
        assertEquals(work.id, CardCalendars.decode(CardCalendars(setOf(work.id)).encode()).selected!!.single())
        assertNotEquals(family.id, moved.calendars.last().id)
        assertEquals(settings.selected, moved.selected)
        assertEquals(family.id, moved.missingCalendars.single().id)
        requests.clear()
        val newAddress = client.synchronize(moved.calendars.first(), window, previous, 2)
        assertTrue(requests.any { "calendar-query" in it.second })
        assertEquals(moved.calendars.first().url.toString(), newAddress.collectionUrl)
        assertEquals(previous.titles(), newAddress.titles())
        familyPath = "/account/calendars/family/"
        assertEquals(family.id, moved.rediscovered(client.discover()).calendars.last().id)
    }

    @Test fun deadlineRequestAndByteBudgetsApplyToTheWholeRoundAndCancelledCallCanRecover() {
        val calendar = client.discover().first()
        val old = client.synchronize(calendar, window, null, 1)
        token = "dirty"
        expect(CalDavProblem.BUDGET) { client.synchronize(calendar, window, old, 2, CalDavRound(maxRequests = 1)) }
        val shared = CalDavRound(maxRequests = 4)
        assertEquals(old.titles(), client.synchronize(calendar, window, old, 2, shared).titles())
        expect(CalDavProblem.BUDGET) { client.discover(shared) }
        expect(CalDavProblem.BUDGET) { client.synchronize(calendar, window, old, 2, CalDavRound(maxBytes = 550)) }
        manifestDelay = 4_000
        expect(CalDavProblem.BUDGET) { client.synchronize(calendar, window, old, 2, CalDavRound(timeoutMillis = 450)) }

        val worker = Executors.newSingleThreadExecutor()
        try {
            val entered = CountDownLatch(1)
            manifestStarted = entered
            val round = CalDavRound()
            val task = worker.submit<CalDavFailure> {
                expect(CalDavProblem.CANCELLED) { client.synchronize(calendar, window, old, 2, round) }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            round.cancel()
            assertEquals(CalDavProblem.CANCELLED, task.get(2, TimeUnit.SECONDS).problem)
        } finally {
            worker.shutdownNow()
        }
        manifestDelay = 0
        assertEquals(old.titles(), client.synchronize(calendar, window, old, 3).titles())
    }

    @Test fun deduplicatedSnapshotRoundTripsLongRecurringDescriptionsAndReadsLegacyFormat() {
        val description = "说明 long description ".repeat(180)
        val events = (0 until 400).map {
            CloudEvent("uid", "id-$it", "Title", "Location", description, it.toLong(), it + 60L)
        }
        val snapshot = CalendarSnapshot(5, "token", mapOf("event.ics" to CachedObject("tag", events)))
        assertTrue(events.sumOf { it.description.toByteArray().size } > 512 * 1024)
        val compact = encodeSnapshot(snapshot).toString()
        assertTrue(compact.toByteArray().size < 45 * 1024)
        assertEquals(snapshot, decodeSnapshot(JSONObject(compact)))
        val decoded = decodeSnapshot(JSONObject("""{
            "checkedAt":5,"token":"legacy","objects":[{"href":"fixture","etag":"tag","events":[
            {"uid":"uid","title":"legacy title","location":"","description":"description","start":1,"end":2}]}]
        }"""))
        assertEquals("legacy title", decoded.objects.getValue("fixture").events.single().title)
        assertNull(decoded.collectionUrl)
        expect(CalDavProblem.CAPACITY) {
            encodeSnapshot(snapshot.copy(objects = mapOf("large" to CachedObject("tag", List(MAX_INSTANCES + 1) { events.first() }))))
        }
    }

    private fun expect(problem: CalDavProblem, run: () -> Any): CalDavFailure =
        assertThrows(CalDavFailure::class.java) { run() }.also { assertEquals(problem, it.problem) }

    private fun CalendarSnapshot.titles() = objects.values.flatMap { it.events }.map { it.title }.toSet()
    private val tokenTag = "<D:sync-token/>"
    private val type = "<D:resourcetype><D:collection/></D:resourcetype>"
    private fun calendar(path: String, name: String, color: String, access: String, resource: String? = null) =
        item(path, "<D:resourcetype><D:collection/><C:calendar/></D:resourcetype><D:displayname>$name</D:displayname>" +
            "<A:calendar-color>$color</A:calendar-color>" +
            (resource?.let { "<D:resource-id><D:href>$it</D:href></D:resource-id>" } ?: "") +
            "<D:current-user-privilege-set><D:privilege><D:read/></D:privilege>" +
            "${if (access.isBlank()) "" else "<D:privilege>$access</D:privilege>"}</D:current-user-privilege-set>")
    private fun item(path: String, props: String, status: Int = 200) = "<D:response><D:href>$path</D:href>" +
        "<D:propstat><D:prop>$props</D:prop><D:status>HTTP/1.1 $status Test</D:status></D:propstat></D:response>"
    private fun dav(xml: String) = MockResponse().setResponseCode(207).setBody(
        "<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\" xmlns:A=\"http://apple.com/ns/ical/\">$xml</D:multistatus>",
    )
    private fun etag(tag: String) = "<D:getetag>\"$tag\"</D:getetag>"
    private fun basic(title: String) = ics("UID:$title\nDTSTART:20260308T150000Z\nDTEND:20260308T160000Z\nSUMMARY:$title")
    private fun ics(vararg events: String) = ("BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//DormPanel//CalDAV Test//EN\n" +
        events.joinToString("") { "BEGIN:VEVENT\n$it\nEND:VEVENT\n" } + "END:VCALENDAR\n").replace("\n", "\r\n")
}
