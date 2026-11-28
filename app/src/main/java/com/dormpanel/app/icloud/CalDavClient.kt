package com.dormpanel.app.icloud

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    fun discover(): List<CloudCalendar> {
        val properties = "<D:current-user-principal/><C:calendar-home-set/>"
        val bootstrap = try {
            properties(at(root, "/.well-known/caldav"), "0", properties)
        } catch (e: DavHttpStatus) {
            if (e.code !in setOf(404, 405, 501)) throw e.failure()
            try { properties(root, "0", properties) } catch (status: DavHttpStatus) { throw status.failure() }
        }
        val response = bootstrap.requiredCollection()
        val homeDocument = if (response.property(CAL, "calendar-home-set") != null) bootstrap else {
            val principal = response.property(DAV, "current-user-principal") ?: invalidCalendar()
            if (principal.child(DAV, "unauthenticated") != null) throw CalDavFailure(CalDavProblem.AUTH)
            val target = at(bootstrap.url, principal.child(DAV, "href")?.text() ?: invalidCalendar())
            try { properties(target, "0", "<C:calendar-home-set/>") } catch (e: DavHttpStatus) { throw e.failure() }
        }
        val homes = homeDocument.requiredCollection().property(CAL, "calendar-home-set")
            ?.children(DAV, "href")?.map { at(homeDocument.url, it.text()) }?.distinct().orEmpty()
        checkCalendar(homes.size in 1..4)
        val found = linkedMapOf<String, CloudCalendar>()
        for (home in homes) {
            val listing = try {
                properties(home, "1", "<D:resourcetype/><D:displayname/>" +
                    "<A:calendar-color/><D:current-user-privilege-set/><C:supported-calendar-component-set/>")
            } catch (e: DavHttpStatus) { throw e.failure() }
            listing.requiredCollection()
            for (item in listing.items.values) {
                val type = item.property(DAV, "resourcetype") ?: invalidCalendar()
                if (type.child(CAL, "calendar") == null) continue
                val components = item.property(CAL, "supported-calendar-component-set")
                if (components != null && components.children(CAL, "comp").none { it.getAttribute("name") == "VEVENT" }) continue
                val privileges = item.property(DAV, "current-user-privilege-set")
                    ?.children(DAV, "privilege")?.flatMap { it.children(DAV) }?.map { it.localName }.orEmpty()
                val access = when {
                    "all" in privileges || "write" in privileges || "write-content" in privileges -> CalendarAccess.WRITABLE
                    "read" in privileges -> CalendarAccess.READ_ONLY
                    else -> CalendarAccess.UNKNOWN
                }
                val url = item.url
                checkCalendar(url.encodedQuery == null)
                val id = digest(url.toString())
                val name = item.property(DAV, "displayname")?.text()?.take(160)?.ifBlank { null } ?: "iCloud"
                val color = item.property(APPLE, "calendar-color")?.text()?.let(::parseColor)
                found[id] = CloudCalendar(id, url, name, color, access)
                checkCalendar(found.size <= MAX_CALENDARS)
            }
        }
        return found.values.toList()
    }

    fun synchronize(calendar: CloudCalendar, window: CalendarWindow, previous: CalendarSnapshot?, now: Long): CalendarSnapshot {
        val before = token(calendar.url)
        if (before != null && before == previous?.collectionToken) return previous.copy(checkedAt = now)

        val range = "start=\"${WIRE_TIME.format(window.queryStart)}\" end=\"${WIRE_TIME.format(window.queryEnd)}\""
        val filter = "<C:filter><C:comp-filter name=\"VCALENDAR\"><C:comp-filter name=\"VEVENT\">" +
            "<C:time-range $range/></C:comp-filter></C:comp-filter></C:filter>"
        val manifest = report(calendar.url, "1", "<C:calendar-query $NS><D:prop><D:getetag/></D:prop>$filter</C:calendar-query>")
            .eventObjects()
        val result = linkedMapOf<String, CachedObject>()
        val changed = manifest.filter { (href, etag) ->
            val cached = previous?.objects?.get(href)
            if (!etag.startsWith("W/") && cached?.etag == etag) {
                result[href] = cached
                false
            } else true
        }
        val data = "<D:prop><D:getetag/><C:calendar-data/></D:prop>"
        var count = result.values.sumOf { it.events.size }
        val budget = ExpansionBudget()
        for (batch in changed.keys.chunked(12)) {
            val hrefs = batch.joinToString("") { "<D:href>${escapeXml(it.toHttpUrl().encodedPath)}</D:href>" }
            val document = report(calendar.url, "1", "<C:calendar-multiget $NS>$data$hrefs</C:calendar-multiget>")
            val returned = document.eventObjects()
            checkCalendar(returned.keys == batch.toSet())
            for ((href, etag) in returned) {
                checkCalendar(etag == manifest[href])
                val dataElement = document.items.getValue(href).property(CAL, "calendar-data") ?: invalidCalendar()
                checkCalendar(dataElement.children().isEmpty())
                val ics = dataElement.textContent
                val events = readCloudEvents(ics, window, budget)
                count += events.size
                checkCalendar(count <= MAX_INSTANCES)
                result[href] = CachedObject(etag, events)
            }
        }
        // The token validates this range snapshot only. It cannot seed a sync-collection REPORT.
        val after = if (before == null) null else token(calendar.url)
        checkCalendar(before == null || before == after)
        val keys = hashSetOf<Pair<String, String?>>()
        checkCalendar(result.values.all { obj -> obj.events.all { keys.add(it.uid to it.recurrence) } })
        return CalendarSnapshot(now, after, result)
    }

    private fun token(url: HttpUrl): String? {
        val document = try {
            properties(url, "0", "<D:sync-token/>")
        } catch (e: DavHttpStatus) {
            if (e.code in setOf(405, 501)) return null
            throw e.failure()
        }
        return document.requiredCollection().property(DAV, "sync-token")?.text()?.also {
            checkCalendar(it.length <= 2048)
        }?.takeUnless(String::isEmpty)
    }

    private fun properties(url: HttpUrl, depth: String, props: String) =
        request(url, "PROPFIND", depth, "<D:propfind $NS><D:prop>$props</D:prop></D:propfind>")

    private fun report(url: HttpUrl, depth: String, xml: String) =
        try { request(url, "REPORT", depth, xml) } catch (e: DavHttpStatus) { throw e.failure() }

    private fun request(initial: HttpUrl, method: String, depth: String, xml: String): DavDocument {
        var url = trusted(initial)
        try {
            repeat(5) {
                val request = Request.Builder().url(url).header("Depth", depth).header("Authorization", authorization)
                    .method(method, xml.toRequestBody(XML)).build()
                http.newCall(request).execute().use { response ->
                    if (response.code in REDIRECTS) {
                        url = at(url, response.header("Location") ?: throw CalDavFailure(CalDavProblem.TLS))
                    } else {
                        if (response.code != 207) throw DavHttpStatus(response.code, retryAfter(response.header("Retry-After")))
                        val body = response.body ?: invalidCalendar()
                        checkCalendar(body.contentLength() <= MAX_XML_BYTES)
                        val source = body.source()
                        checkCalendar(!source.request(MAX_XML_BYTES + 1))
                        val utf8 = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source.readByteArray())).toString()
                        return parseDocument(url, utf8.removePrefix("\uFEFF"), method == "REPORT")
                    }
                }
            }
        } catch (_: SSLException) {
            throw CalDavFailure(CalDavProblem.TLS)
        } catch (_: java.nio.charset.CharacterCodingException) {
            invalidCalendar()
        } catch (_: IOException) {
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
        checkCalendar(nodes.size <= MAX_OBJECTS + 1)
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

private val defaultCalDavTransport = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
private val iCloudShard = Regex("p[0-9]{1,5}-caldav\\.icloud\\.com")
private fun isICloudHost(host: String) = host == "caldav.icloud.com" || iCloudShard.matches(host)
private val XML = "application/xml; charset=utf-8".toMediaType()
private val WIRE_TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", java.util.Locale.ROOT).withZone(ZoneOffset.UTC)
private val REDIRECTS = setOf(301, 302, 303, 307, 308)
private const val MAX_XML_BYTES = 1_048_576L
private const val DAV = "DAV:"
private const val CAL = "urn:ietf:params:xml:ns:caldav"
private const val APPLE = "http://apple.com/ns/ical/"
private const val NS = "xmlns:D=\"$DAV\" xmlns:C=\"$CAL\" xmlns:A=\"$APPLE\""
private val MULTISTATUS_CHILDREN = setOf("response", "responsedescription", "sync-token")
private val OPTIONAL_PROPERTIES = setOf(DAV to "displayname", DAV to "current-user-privilege-set", DAV to "sync-token",
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
}

private data class DavDocument(val url: HttpUrl, val items: Map<String, DavItem>) {
    fun requiredCollection() = items[url.toString()] ?: items.values.singleOrNull {
        it.url.host == url.host && it.url.port == url.port && it.url.encodedPath.trimEnd('/') == url.encodedPath.trimEnd('/')
    } ?: invalidCalendar()

    fun eventObjects(): Map<String, String> = items.mapValues { (_, item) ->
        val directory = url.encodedPath.trimEnd('/') + "/"
        val member = item.url.encodedPath.removePrefix(directory)
        checkCalendar(item.url.host == url.host && item.url.port == url.port && item.url.encodedQuery == null &&
            item.url.encodedPath.startsWith(directory) && member.isNotEmpty() && '/' !in member)
        val etag = item.property(DAV, "getetag")?.text() ?: invalidCalendar()
        checkCalendar(etag.length in 2..512 && (etag.startsWith('"') || etag.startsWith("W/\"")) && etag.endsWith('"'))
        etag
    }.also { checkCalendar(it.size <= MAX_OBJECTS) }
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
private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
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
