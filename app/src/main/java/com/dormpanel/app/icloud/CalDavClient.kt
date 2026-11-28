package com.dormpanel.app.icloud

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.xml.parsers.DocumentBuilderFactory

internal class CalDavClient(
    email: String,
    password: String,
    private val root: HttpUrl = "https://caldav.icloud.com/".toHttpUrl(),
    private val allowedHost: (String) -> Boolean = ::isICloudHost,
    transport: OkHttpClient = defaultCalDavTransport,
) {
    private val authorization = Credentials.basic(email, password, Charsets.UTF_8)
    private val http = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .authenticator(okhttp3.Authenticator.NONE).cookieJar(okhttp3.CookieJar.NO_COOKIES).cache(null).build()
    private val writeHttp = http.newBuilder().retryOnConnectionFailure(false)
        // A closed idle connection must not consume the only attempt at a mutation.
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .eventListener(object : EventListener() {
            override fun requestHeadersStart(call: Call) {
                call.request().tag(WriteTransmission::class.java)?.headersStarted = true
            }
        }).build()

    fun discover(round: CalDavRound = CalDavRound()): List<CloudCalendar> {
        val properties = "<D:current-user-principal/><C:calendar-home-set/>"
        val bootstrap = try {
            properties(round, at(root, "/.well-known/caldav"), "0", properties)
        } catch (e: DavHttpStatus) {
            if (e.code !in setOf(404, 405, 501)) throw e.failure()
            try { properties(round, root, "0", properties) } catch (status: DavHttpStatus) { throw status.failure() }
        }
        val response = bootstrap.requiredCollection()
        val homeDocument = if (response.property(CAL, "calendar-home-set") != null) bootstrap else {
            val principal = response.property(DAV, "current-user-principal") ?: invalidCalendar()
            if (principal.child(DAV, "unauthenticated") != null) throw CalDavFailure(CalDavProblem.AUTH)
            val target = at(bootstrap.url, principal.child(DAV, "href")?.text() ?: invalidCalendar())
            try { properties(round, target, "0", "<C:calendar-home-set/>") } catch (e: DavHttpStatus) { throw e.failure() }
        }
        val homes = homeDocument.requiredCollection().property(CAL, "calendar-home-set")
            ?.children(DAV, "href")?.map { at(homeDocument.url, it.text()) }?.distinct().orEmpty()
        checkCalendar(homes.size in 1..4)
        val found = linkedMapOf<String, CloudCalendar>()
        for (home in homes) {
            val listing = try {
                properties(round, home, "1", "<D:resourcetype/><D:displayname/>" +
                    "<A:calendar-color/><D:current-user-privilege-set/><C:supported-calendar-component-set/><D:resource-id/>")
            } catch (e: DavHttpStatus) { throw e.failure() }
            listing.requiredCollection()
            for (item in listing.items.values) {
                val type = item.property(DAV, "resourcetype") ?: invalidCalendar()
                if (type.child(CAL, "calendar") == null) continue
                val components = item.property(CAL, "supported-calendar-component-set")
                if (components != null && components.children(CAL, "comp").none { it.getAttribute("name") == "VEVENT" }) continue
                val privileges = item.privilegeNames()
                val allowed = CalendarPrivileges.from(privileges)
                val access = when {
                    allowed.bind || allowed.content || allowed.unbind -> CalendarAccess.WRITABLE
                    "read" in privileges -> CalendarAccess.READ_ONLY
                    else -> CalendarAccess.UNKNOWN
                }
                val url = item.url
                checkCalendar(url.encodedQuery == null)
                val stable = item.property(DAV, "resource-id")?.child(DAV, "href")?.text()?.takeIf {
                    it.length <= 1024 && runCatching { java.net.URI(it).isAbsolute }.getOrDefault(false)
                }
                val id = calendarDigest(if (stable == null) url.toString() else "resource-id:$stable")
                val name = item.property(DAV, "displayname")?.text()?.take(160)?.ifBlank { null } ?: "iCloud"
                val color = item.property(APPLE, "calendar-color")?.text()?.let(::parseColor)
                val calendar = CloudCalendar(id, url, name, color, access, stable, allowed)
                val prior = found.putIfAbsent(id, calendar)
                // A multi-home listing may return the same binding twice. Ambiguous aliases
                // are kept out of selection reconciliation until discovery can be verified.
                checkCalendar(prior == null || prior.url == url)
                checkCalendarCapacity(found.size <= MAX_CALENDARS)
            }
        }
        return found.values.toList()
    }

    fun verifyCollection(calendar: CloudCalendar, round: CalDavRound): CalendarPrivileges {
        val item = exactProperties(calendar.url, "<D:resourcetype/><D:resource-id/><D:current-user-privilege-set/>", round, collection = true)
        checkCalendar(item.property(DAV, "resourcetype")?.child(CAL, "calendar") != null)
        val id = item.property(DAV, "resource-id")?.child(DAV, "href")?.text()
        checkCalendar(calendar.resourceId == null || id == calendar.resourceId)
        return CalendarPrivileges.from(item.privilegeNames())
    }

    fun objectPrivileges(collection: HttpUrl, href: HttpUrl, round: CalDavRound): CalendarPrivileges {
        requireMember(collection, href)
        return CalendarPrivileges.from(exactProperties(href, "<D:current-user-privilege-set/>", round).privilegeNames())
    }

    private fun exactProperties(url: HttpUrl, props: String, round: CalDavRound, collection: Boolean = false): DavItem = try {
        request(round, url, "PROPFIND", "0", "<D:propfind $NS><D:prop>$props</D:prop></D:propfind>", redirects = false)
            .let { if (collection) it.requiredCollection() else it.items[url.toString()] ?: invalidCalendar() }
    } catch (e: DavHttpStatus) { throw e.failure() }

    fun readObject(collection: HttpUrl, href: HttpUrl, round: CalDavRound): DavCalendarObject? {
        requireMember(collection, href)
        return objectRequest(Request.Builder().url(href).header("Accept", "text/calendar").get(), round) { response ->
            if (response.code == 404) return@objectRequest null
            if (response.code != 200) throw DavHttpStatus(response.code, retryAfter(response.header("Retry-After"))).failure()
            val body = response.body ?: invalidCalendar()
            val limit = round.bodyLimit(MAX_WRITE_ICS_BYTES.toLong())
            val source = body.source()
            if (body.contentLength() > limit || source.request(limit + 1)) {
                round.received(source.buffer.size)
                round.oversized(limit, MAX_WRITE_ICS_BYTES.toLong())
            }
            val bytes = source.readByteArray()
            round.received(bytes.size.toLong())
            DavCalendarObject(response.headers("ETag").singleOrNull(),
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
        }
    }

    fun mutateObject(collection: HttpUrl, href: HttpUrl, kind: CalendarWriteKind, etag: String?, ics: String?,
        round: CalDavRound,
    ): DavWriteResponse {
        requireMember(collection, href)
        checkCalendar((kind == CalendarWriteKind.CREATE && etag == null) ||
            (kind != CalendarWriteKind.CREATE && strongCalendarEtag(etag)))
        checkCalendar((kind == CalendarWriteKind.DELETE) == (ics == null))
        val bytes = ics?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        checkCalendarCapacity(bytes.size <= MAX_WRITE_ICS_BYTES)
        round.received(bytes.size.toLong())
        // Prevent OkHttp from replaying an already transmitted mutation on an HTTP follow-up.
        val body = object : RequestBody() {
            override fun contentType() = if (ics == null) null else "text/calendar; charset=utf-8".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
        }
        val transmission = WriteTransmission()
        val request = Request.Builder().url(href).tag(WriteTransmission::class.java, transmission)
            .method(if (kind == CalendarWriteKind.DELETE) "DELETE" else "PUT", body)
        if (etag == null) request.header("If-None-Match", "*") else request.header("If-Match", etag)
        return try {
            objectRequest(request, round, writeHttp) { DavWriteResponse(it.code, retryAfter(it.header("Retry-After"))) }
        } catch (e: CalDavFailure) {
            if (transmission.headersStarted) throw e
            throw CalDavWriteNotSent(e.problem, e.retryAt)
        }
    }

    private fun requireMember(collection: HttpUrl, href: HttpUrl) {
        trusted(collection)
        trusted(href)
        val directory = collection.encodedPath.trimEnd('/') + "/"
        val member = href.encodedPath.removePrefix(directory)
        checkCalendar(collection.encodedQuery == null && href.encodedQuery == null && href.host == collection.host &&
            href.port == collection.port && href.encodedPath.startsWith(directory) && member.isNotEmpty() &&
            !member.contains('/') && !member.contains(AMBIGUOUS_MEMBER))
    }

    private fun <T> objectRequest(builder: Request.Builder, round: CalDavRound, transport: OkHttpClient = http,
        read: (okhttp3.Response) -> T,
    ): T {
        val request = builder.header("Authorization", authorization).header("Cache-Control", "no-cache").build()
        val call = transport.newCall(request)
        round.begin(call)
        try {
            return call.execute().use(read)
        } catch (_: SSLException) {
            round.check()
            throw CalDavFailure(CalDavProblem.TLS)
        } catch (_: java.nio.charset.CharacterCodingException) {
            invalidCalendar()
        } catch (_: IOException) {
            round.check()
            throw CalDavFailure(CalDavProblem.NETWORK)
        } finally { round.end() }
    }

    fun synchronize(calendar: CloudCalendar, window: CalendarWindow, previous: CalendarSnapshot?, now: Long,
        round: CalDavRound = CalDavRound(),
    ): CalendarSnapshot {
        val address = calendar.url.toString()
        val before = token(round, calendar.url)
        if (before != null && previous?.collectionUrl == address && previous.problem == null &&
            previous.hasCurrentProjection && before == previous.collectionToken) return previous.copy(checkedAt = now)

        val range = "start=\"${WIRE_TIME.format(window.queryStart)}\" end=\"${WIRE_TIME.format(window.queryEnd)}\""
        val filter = "<C:filter><C:comp-filter name=\"VCALENDAR\"><C:comp-filter name=\"VEVENT\">" +
            "<C:time-range $range/></C:comp-filter></C:comp-filter></C:filter>"
        val manifest = report(round, calendar.url, "1", "<C:calendar-query $NS><D:prop><D:getetag/></D:prop>$filter</C:calendar-query>")
            .eventObjects()
        val result = linkedMapOf<String, CachedObject>()
        val changed = manifest.filter { (href, etag) ->
            val cached = previous?.objects?.get(href)
            if (!etag.startsWith("W/") && cached?.etag == etag && cached.hasCurrentProjection) {
                result[href] = cached
                false
            } else true
        }
        val data = "<D:prop><D:getetag/><C:calendar-data/></D:prop>"
        var count = result.values.sumOf { it.events.size }
        val budget = ExpansionBudget(round::check)
        var problem: CalDavProblem? = null
        fun collect(batch: List<String>) {
            val hrefs = batch.joinToString("") { "<D:href>${escapeXml(it.toHttpUrl().encodedPath)}</D:href>" }
            val document = try {
                report(round, calendar.url, "1", "<C:calendar-multiget $NS>$data$hrefs</C:calendar-multiget>")
            } catch (_: CalDavResponseTooLarge) {
                if (batch.size == 1) {
                    // This request addresses one manifest member. Keep its old cache and
                    // record the incomplete month without accepting any data from the response.
                    problem = CalDavProblem.CAPACITY
                } else {
                    val middle = batch.size / 2
                    collect(batch.subList(0, middle))
                    collect(batch.subList(middle, batch.size))
                }
                return
            }
            val returned = document.eventObjects()
            checkCalendar(returned.keys == batch.toSet())
            for ((href, etag) in returned) {
                round.check()
                checkCalendar(etag == manifest[href])
                val dataElement = document.items.getValue(href).property(CAL, "calendar-data") ?: invalidCalendar()
                checkCalendar(dataElement.children().isEmpty())
                val events = try {
                    readCloudEvents(dataElement.textContent, window, budget)
                } catch (e: CalDavFailure) {
                    if (e.problem !in setOf(CalDavProblem.DATA, CalDavProblem.CAPACITY)) throw e
                    if (problem != CalDavProblem.CAPACITY) problem = e.problem
                    continue
                }
                count += events.size
                checkCalendarCapacity(count <= MAX_INSTANCES)
                result[href] = CachedObject(etag, events, CURRENT_PROJECTION_VERSION)
            }
        }
        changed.keys.chunked(24).forEach(::collect)
        // The token validates this range snapshot only. It cannot seed a sync-collection REPORT.
        val after = if (before == null) null else token(round, calendar.url)
        checkCalendar(before == null || before == after)
        round.check()
        if (problem != null) {
            // A complete manifest is insufficient to establish absence when some resources
            // could not be interpreted. Keep everything previously known until a full check.
            previous?.objects?.forEach { (href, obj) -> result.putIfAbsent(href, obj) }
        }
        checkCalendarCapacity(result.size <= MAX_OBJECTS && result.values.sumOf { it.events.size } <= MAX_INSTANCES)
        val keys = hashSetOf<Pair<String, String?>>()
        checkCalendar(result.values.all { obj -> obj.events.all { keys.add(it.uid to it.recurrence) } })
        return CalendarSnapshot(if (problem == null) now else previous?.checkedAt ?: 0,
            after.takeIf { problem == null }, result, problem, address)
    }

    private fun token(round: CalDavRound, url: HttpUrl): String? {
        val document = try {
            properties(round, url, "0", "<D:sync-token/>")
        } catch (e: DavHttpStatus) {
            if (e.code in setOf(405, 501)) return null
            throw e.failure()
        }
        return document.requiredCollection().property(DAV, "sync-token")?.text()?.also {
            checkCalendar(it.length <= 2048)
        }?.takeUnless(String::isEmpty)
    }

    private fun properties(round: CalDavRound, url: HttpUrl, depth: String, props: String) =
        request(round, url, "PROPFIND", depth, "<D:propfind $NS><D:prop>$props</D:prop></D:propfind>")

    private fun report(round: CalDavRound, url: HttpUrl, depth: String, xml: String) =
        try { request(round, url, "REPORT", depth, xml) } catch (e: DavHttpStatus) { throw e.failure() }

    private fun request(round: CalDavRound, initial: HttpUrl, method: String, depth: String, xml: String,
        redirects: Boolean = true,
    ): DavDocument {
        var url = trusted(initial)
        try {
            repeat(5) {
                val request = Request.Builder().url(url).header("Depth", depth).header("Authorization", authorization)
                    .method(method, xml.toRequestBody(XML)).build()
                val call = http.newCall(request)
                round.begin(call)
                try {
                    call.execute().use { response ->
                        if (response.code in REDIRECTS) {
                            if (!redirects) throw CalDavFailure(CalDavProblem.ACCESS)
                            url = at(url, response.header("Location") ?: throw CalDavFailure(CalDavProblem.TLS))
                        } else {
                            if (response.code != 207) throw DavHttpStatus(response.code, retryAfter(response.header("Retry-After")))
                            val body = response.body ?: invalidCalendar()
                            val limit = round.bodyLimit(MAX_XML_BYTES)
                            val source = body.source()
                            if (body.contentLength() > limit || source.request(limit + 1)) {
                                round.received(source.buffer.size)
                                round.oversized(limit, MAX_XML_BYTES)
                            }
                            val bytes = source.readByteArray()
                            round.received(bytes.size.toLong())
                            val utf8 = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                            return parseDocument(url, utf8.removePrefix("\uFEFF"), method == "REPORT")
                        }
                    }
                } finally { round.end() }
            }
        } catch (_: SSLException) {
            round.check()
            throw CalDavFailure(CalDavProblem.TLS)
        } catch (_: java.nio.charset.CharacterCodingException) {
            invalidCalendar()
        } catch (_: IOException) {
            round.check()
            throw CalDavFailure(CalDavProblem.NETWORK)
        }
        throw CalDavFailure(CalDavProblem.TLS)
    }

    private fun parseDocument(url: HttpUrl, xml: String, isReport: Boolean): DavDocument {
        val root = try {
            // Decode to characters first so an alternate XML encoding cannot hide a DTD.
            checkCalendar(!hasDocumentType(xml))
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External entity") }
                setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                    override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
                    override fun error(e: org.xml.sax.SAXParseException) { throw e }
                })
            }.parse(InputSource(StringReader(xml))).documentElement
        } catch (_: Exception) {
            invalidCalendar()
        }
        checkCalendar(root.namespaceURI == DAV && root.localName == "multistatus")
        checkDavTree(root, 0)
        checkCalendar(root.children().all { it.namespaceURI == DAV && it.localName in MULTISTATUS_CHILDREN })
        val nodes = root.children(DAV, "response")
        checkCalendarCapacity(nodes.size <= MAX_OBJECTS + 1)
        val items = linkedMapOf<String, DavItem>()
        for (node in nodes) {
            val resolved = at(url, node.child(DAV, "href")?.text() ?: invalidCalendar())
            // iCloud may emit absolute member hrefs on its bootstrap hostname while serving the shard.
            val href = if (isReport && resolved.host == "caldav.icloud.com" && iCloudShard.matches(url.host))
                resolved.newBuilder().host(url.host).build() else resolved
            val status = node.child(DAV, "status")?.text()?.let(::statusCode)
            if (status != null && status != 200) throw DavHttpStatus(status).failure()
            val props = linkedMapOf<Pair<String?, String>, Element>()
            val propstats = node.children(DAV, "propstat")
            checkCalendar(propstats.isNotEmpty() || status == 200)
            for (propstat in propstats) {
                val code = statusCode(propstat.child(DAV, "status")?.text() ?: invalidCalendar())
                if (code == 200) {
                    val properties = propstat.child(DAV, "prop") ?: invalidCalendar()
                    for (property in properties.children()) {
                        checkCalendar(props.put(property.namespaceURI to property.localName, property) == null)
                    }
                } else if (code == 403 && !isReport && propstat.child(DAV, "prop")?.children()?.all {
                        (it.namespaceURI to it.localName) in OPTIONAL_PROPERTIES
                    } == true) continue
                else if (code != 404) throw DavHttpStatus(code).failure()
            }
            checkCalendar(items.put(href.toString(), DavItem(href, props)) == null)
        }
        return DavDocument(url, items)
    }

    private fun at(base: HttpUrl, href: String): HttpUrl {
        checkCalendar(href.isNotBlank() && href.length <= 2048)
        return trusted(base.resolve(href) ?: throw CalDavFailure(CalDavProblem.TLS))
    }

    private fun trusted(url: HttpUrl): HttpUrl {
        if (!url.isHttps || !allowedHost(url.host) || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.fragment != null || (isICloudHost(url.host) && url.port != 443)) throw CalDavFailure(CalDavProblem.TLS)
        return url
    }
}

private class WriteTransmission { @Volatile var headersStarted = false }

private val defaultCalDavTransport = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
private val iCloudShard = Regex("p[0-9]{1,5}-caldav\\.icloud\\.com")
private fun isICloudHost(host: String) = host == "caldav.icloud.com" || iCloudShard.matches(host)
private val XML = "application/xml; charset=utf-8".toMediaType()
private val WIRE_TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", java.util.Locale.ROOT).withZone(ZoneOffset.UTC)
private val REDIRECTS = setOf(301, 302, 303, 307, 308)
private val AMBIGUOUS_MEMBER = Regex("%(?:2f|5c|25|0[0-9a-f]|7f)", RegexOption.IGNORE_CASE)
private const val MAX_XML_BYTES = 1_048_576L
private const val DAV = "DAV:"
private const val CAL = "urn:ietf:params:xml:ns:caldav"
private const val APPLE = "http://apple.com/ns/ical/"
private const val NS = "xmlns:D=\"$DAV\" xmlns:C=\"$CAL\" xmlns:A=\"$APPLE\""
private val MULTISTATUS_CHILDREN = setOf("response", "responsedescription", "sync-token")
private val OPTIONAL_PROPERTIES = setOf(DAV to "displayname", DAV to "current-user-privilege-set", DAV to "sync-token", DAV to "resource-id",
    CAL to "supported-calendar-component-set", APPLE to "calendar-color")

private class DavHttpStatus(val code: Int, val retry: Long = 0) : Exception() {
    fun failure() = CalDavFailure(when (code) {
        401 -> CalDavProblem.AUTH
        403 -> CalDavProblem.ACCESS
        429 -> CalDavProblem.LIMITED
        408, 500, 502, 503, 504 -> CalDavProblem.SERVER
        else -> CalDavProblem.DATA
    }, retry)
}

private data class DavItem(val url: HttpUrl, val props: Map<Pair<String?, String>, Element>) {
    fun property(ns: String, name: String) = props[ns to name]
    fun privilegeNames() = property(DAV, "current-user-privilege-set")
        ?.children(DAV, "privilege")?.flatMap { it.children(DAV) }?.map { it.localName }.orEmpty()
}

private data class DavDocument(val url: HttpUrl, val items: Map<String, DavItem>) {
    fun requiredCollection() = items[url.toString()] ?: items.values.singleOrNull {
        it.url.host == url.host && it.url.port == url.port && it.url.encodedQuery == url.encodedQuery &&
            it.url.encodedPath.trimEnd('/') == url.encodedPath.trimEnd('/')
    } ?: invalidCalendar()

    fun eventObjects(): Map<String, String> = items.mapValues { (_, item) ->
        val directory = url.encodedPath.trimEnd('/') + "/"
        val member = item.url.encodedPath.removePrefix(directory)
        checkCalendar(item.url.host == url.host && item.url.port == url.port && item.url.encodedQuery == null &&
            item.url.encodedPath.startsWith(directory) && member.isNotEmpty() && '/' !in member)
        val etag = item.property(DAV, "getetag")?.text() ?: invalidCalendar()
        checkCalendar(etag.length in 2..512 && (etag.startsWith('"') || etag.startsWith("W/\"")) && etag.endsWith('"'))
        etag
    }.also { checkCalendarCapacity(it.size <= MAX_OBJECTS) }
}

private fun Element.children(ns: String? = null, name: String? = null): List<Element> = buildList {
    var node = firstChild
    while (node != null) {
        if (node is Element && (ns == null || node.namespaceURI == ns) && (name == null || node.localName == name)) add(node)
        node = node.nextSibling
    }
}

private fun Element.child(ns: String, name: String): Element? = children(ns, name).also {
    checkCalendar(it.size <= 1)
}.firstOrNull()

private fun Element.text() = textContent.trim()

private fun hasDocumentType(xml: String): Boolean {
    var cursor = 0
    while (cursor < xml.length) {
        while (cursor < xml.length && xml[cursor] in " \r\n\t") cursor++
        when {
            xml.startsWith("<!DOCTYPE", cursor) -> return true
            xml.startsWith("<!--", cursor) -> {
                val end = xml.indexOf("-->", cursor + 4)
                if (end < 0) return false
                cursor = end + 3
            }
            xml.startsWith("<?", cursor) -> {
                val end = xml.indexOf("?>", cursor + 2)
                if (end < 0) return false
                cursor = end + 2
            }
            else -> return false
        }
    }
    return false
}

private fun checkDavTree(element: Element, depth: Int) {
    checkCalendar(depth <= 24)
    element.children().forEach { checkDavTree(it, depth + 1) }
}
private val httpStatus = Regex("HTTP/\\d(?:\\.\\d)? (\\d{3})(?: .*|)$")
private fun statusCode(raw: String) = httpStatus.matchEntire(raw)?.groupValues?.get(1)?.toInt() ?: invalidCalendar()
private fun escapeXml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
internal fun calendarDigest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
private fun parseColor(text: String): Int? {
    if (!text.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) return null
    return 0xff000000.toInt() or text.substring(1, 7).toInt(16)
}

private fun retryAfter(header: String?): Long {
    if (header == null) return 0
    val now = System.currentTimeMillis()
    return header.toLongOrNull()?.let { now + it.coerceIn(0, 86400) * 1000 }
        ?: runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrDefault(0).coerceAtMost(now + 86400000)
}
