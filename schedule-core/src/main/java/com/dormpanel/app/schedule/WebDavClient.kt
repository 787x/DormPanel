package com.dormpanel.app.schedule

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

class WebDavException(val reason: String) : IOException(reason)
class WebDavAccount(val baseUrl: String, val username: String, val password: String, val certificatePem:String?=null) {
    override fun toString() = "WebDavAccount(<redacted>)"
}
data class WebDavItem(val url: String, val name: String, val folder: Boolean, val etag: String?,
    val lastModified: String?, val modifiedAt: Long?, val length: Long?, val mimeType: String?)
data class WebDavDownload(val artifact: ScheduleArtifact?, val etag: String?, val lastModified: String?)

/** One-origin, bounded WebDAV transport. Request errors contain only fixed, credential-free text. */
class WebDavClient(http: OkHttpClient = OkHttpClient.Builder().followRedirects(false)
    .followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(if (http.callTimeoutMillis > 0) http.callTimeoutMillis.toLong() else 60_000L, TimeUnit.MILLISECONDS).build()
    private val immutableHttp = this.http.newBuilder().retryOnConnectionFailure(false).build()
    private var pinnedClients:Pair<String,Pair<OkHttpClient,OkHttpClient>>?=null
    @Synchronized private fun transport(account:WebDavAccount,immutable:Boolean):OkHttpClient {
        val pem=account.certificatePem ?: return if(immutable)immutableHttp else http
        require(url(account.baseUrl).isHttps) { "HTTP 不支持证书信任" }
        val clients=pinnedClients?.takeIf { it.first==pem }?.second ?: WebDavTls.client(http,pem).let {
            (it to it.newBuilder().retryOnConnectionFailure(false).build()).also { pair -> pinnedClients=pem to pair }
        }
        return if(immutable)clients.second else clients.first
    }
    companion object {
        const val MAX_XML = 512 * 1024
        private val OK_STATUS = Regex("\\s200(?:\\s|$)")
        private const val PROPERTIES = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getetag/>
            <d:getlastmodified/><d:getcontentlength/><d:getcontenttype/></d:prop></d:propfind>"""
    }
    fun url(raw: String): HttpUrl {
        val value = raw.trim().toHttpUrlOrNull() ?: throw WebDavException("Enter an HTTP or HTTPS WebDAV URL.")
        if (value.username.isNotEmpty() || value.password.isNotEmpty()) throw WebDavException("Remove credentials from the URL.")
        if (value.fragment != null) throw WebDavException("Remove the URL fragment.")
        if (value.query != null) throw WebDavException("Remove query parameters from the WebDAV URL.")
        return value
    }
    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    private fun execute(account: WebDavAccount, target: HttpUrl, method: String, depth: Int? = null,
        etag: String? = null, lastModified: String? = null, payload: ByteArray? = null, match: String? = null, create: Boolean = false): Response {
        val base = url(account.baseUrl)
        if (!sameOrigin(base, target)) throw WebDavException("Remote resource belongs to another server.")
        var current = target
        repeat(4) { redirect ->
            val request = Request.Builder().url(current).header("Authorization", Credentials.basic(account.username, account.password))
                .apply { if (depth != null) header("Depth", depth.toString())
                    if (etag != null) header("If-None-Match", etag)
                    if (create) header("If-None-Match", "*")
                    if (match != null) header("If-Match", match)
                    if (lastModified != null && etag == null) header("If-Modified-Since", lastModified) }
                .method(method, if (payload != null) payload.toRequestBody("application/octet-stream".toMediaType()) else if (method == "PROPFIND") PROPERTIES.toRequestBody("application/xml; charset=utf-8".toMediaType()) else null).build()
            val response = try { transport(account,method == "PUT" && match == null && !create).newCall(request).execute() } catch (_: IOException) { throw WebDavException("Network unavailable or TLS certificate rejected.") }
            if (response.code in listOf(301, 302, 307, 308)) {
                if (method == "PUT" && match == null && !create) {
                    response.close(); throw WebDavException("Immutable upload redirected; verify the original path before retry.")
                }
                val location = response.header("Location")
                response.close()
                val next = location?.let(current::resolve) ?: throw WebDavException("Invalid WebDAV redirect.")
                if (!sameOrigin(base, next) || next.username.isNotEmpty() || next.password.isNotEmpty())
                    throw WebDavException("WebDAV redirected to another server.")
                if (next.encodedPath.trimEnd('/') != current.encodedPath.trimEnd('/'))
                    throw WebDavException("WebDAV redirected to a different resource.")
                if (redirect == 3 || next == current) throw WebDavException("Too many WebDAV redirects.")
                url(next.toString())
                current = next
            } else return response
        }
        throw WebDavException("Too many WebDAV redirects.")
    }
    private fun status(response: Response, expected: Int) {
        if (response.code == expected) return
        throw WebDavException(when (response.code) {
            401 -> if (response.headers.values("WWW-Authenticate").isNotEmpty() &&
                response.headers.values("WWW-Authenticate").none { it.trimStart().startsWith("Basic", true) })
                "Server authentication is unsupported; HTTP Basic is required."
                else "Authentication failed. Check username and password."
            403 -> "WebDAV access denied."
            404 -> "Remote file or folder not found."
            else -> "WebDAV request failed (HTTP ${response.code})."
        })
    }
    fun list(account: WebDavAccount, folder: String = account.baseUrl, depth: Int = 1, strict: Boolean = false): List<WebDavItem> {
        require(depth in 0..1)
        execute(account, url(folder), "PROPFIND", depth).use { response ->
            status(response, 207)
            val stream = response.body?.byteStream() ?: throw WebDavException("Empty WebDAV response.")
            val bytes = try { stream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break
                    if (output.size() + count > MAX_XML) throw WebDavException("WebDAV folder listing is too large.")
                    output.write(buffer, 0, count) }
                output.toByteArray()
            } } catch (error: WebDavException) { throw error }
            catch (_: IOException) { throw WebDavException("Network unavailable or timed out.") }
            return parseListing(bytes, response.request.url, strict)
        }
    }
    fun download(account: WebDavAccount, remote: String, etag: String? = null,
        lastModified: String? = null): WebDavDownload {
        execute(account, url(remote), "GET", etag = etag, lastModified = lastModified).use { response ->
            if (response.code == 304) return WebDavDownload(null, response.header("ETag") ?: etag,
                response.header("Last-Modified") ?: lastModified)
            status(response, 200)
            val body = response.body ?: throw WebDavException("Empty remote file.")
            if (body.contentLength() > ImportLimits.BYTES) throw ScheduleImportException("ICS exceeds the 1 MiB size limit.")
            val filename = filename(response.request.url)
            val artifact = try { body.byteStream().use { ScheduleArtifact.read(filename, it, response.header("Content-Type"),
                "webdav", response.request.url.newBuilder().username("").password("").build().toString()) } }
            catch (error: ScheduleImportException) { throw error }
            catch (_: IOException) { throw WebDavException("Network unavailable or timed out.") }
            return WebDavDownload(artifact, response.header("ETag"), response.header("Last-Modified"))
        }
    }
    /** APK bytes are handed to the shared file sink while the response is open. */
    fun <T> downloadStream(account: WebDavAccount, remote: String, consume: (java.io.InputStream, Long) -> T): T {
        execute(account, url(remote), "GET").use { response ->
            status(response, 200)
            val body = response.body ?: throw WebDavException("Empty remote file.")
            return body.byteStream().use { consume(it, body.contentLength()) }
        }
    }
    /** Shared-snapshot transport uses the same URL, origin, redirect and authentication rules. */
    data class Resource(val bytes: ByteArray, val etag: String?)
    fun readResource(account: WebDavAccount, remote: String, limit: Int): Resource? {
        execute(account, url(remote), "GET").use { response ->
            if (response.code == 404) return null
            status(response, 200)
            try {
                val body = response.body ?: throw WebDavException("Empty WebDAV response.")
                if (body.contentLength() > limit) throw WebDavException("Shared timetable exceeds size limit.")
                val output = java.io.ByteArrayOutputStream()
                body.byteStream().use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) { val n = stream.read(buffer); if (n < 0) break
                        if (output.size().toLong() + n > limit) throw WebDavException("Shared timetable exceeds size limit.")
                        output.write(buffer, 0, n)
                    }
                }
                return Resource(output.toByteArray(), response.header("ETag"))
            } catch (error: WebDavException) { throw error }
            catch (_: IOException) { throw WebDavException("Network unavailable or incomplete download.") }
        }
    }
    fun conditionalPut(account: WebDavAccount, remote: String, bytes: ByteArray, expected: String?): Int {
        if (expected != null) requireStrongEtag(expected)
        execute(account, url(remote), "PUT", payload = bytes, match = expected, create = expected == null).use { response ->
            if (response.code in listOf(409, 412)) return response.code
            if (response.code !in listOf(200, 201, 204)) status(response, 201)
            return response.code
        }
    }
    fun requireStrongEtag(etag: String?): String {
        if (etag == null || !etag.matches(Regex("\"[^\"\r\n]+\""))) throw WebDavException("Server must supply a strong ETag; unsafe publication refused.")
        return etag
    }
    fun filename(url: HttpUrl): String = url.encodedPathSegments.lastOrNull()?.let {
        runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it)
    }?.ifBlank { "timetable.ics" } ?: "timetable.ics"
    fun ordinaryPut(account: WebDavAccount, remote: String, bytes: ByteArray) {
        execute(account, url(remote), "PUT", payload = bytes).use { response ->
            if (response.code !in listOf(200, 201, 204)) status(response, 201)
        }
    }
    fun makeCollection(account: WebDavAccount, remote: String) {
        execute(account, url(remote), "MKCOL").use { response ->
            if (response.code !in listOf(201, 405)) status(response, 201)
        }
    }
    fun parseListing(xml: ByteArray, requestUrl: HttpUrl, strict: Boolean = false): List<WebDavItem> {
        try {
            if (xml.size > MAX_XML) throw WebDavException("WebDAV folder listing is too large.")
            // Android's API 28 XML provider lacks some JAXP security feature flags. Reject DTDs
            // before parsing, and resolve every attempted external entity to empty content.
            val scan = when {
                xml.size >= 2 && xml[0] == 0xFE.toByte() && xml[1] == 0xFF.toByte() -> String(xml, Charsets.UTF_16BE)
                xml.size >= 2 && xml[0] == 0xFF.toByte() && xml[1] == 0xFE.toByte() -> String(xml, Charsets.UTF_16LE)
                xml.size >= 2 && xml[0] == 0.toByte() && xml[1] == '<'.code.toByte() -> String(xml, Charsets.UTF_16BE)
                xml.size >= 2 && xml[0] == '<'.code.toByte() && xml[1] == 0.toByte() -> String(xml, Charsets.UTF_16LE)
                else -> String(xml, Charsets.UTF_8)
            }
            if ('\u0000' in scan || scan.contains("<!DOCTYPE", ignoreCase = true))
                throw WebDavException("WebDAV listing contains a forbidden DTD.")
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { isXIncludeAware = false }
                runCatching { isExpandEntityReferences = false }
            }
            val parser = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }
            val root = parser.parse(xml.inputStream()).documentElement
            if (root.localName != "multistatus" || root.namespaceURI != "DAV:") throw WebDavException("Invalid WebDAV listing.")
            val rows = children(root, "response").mapNotNull { entry ->
                val href = text(entry, "href") ?: if (strict) throw WebDavException("Missing listing href") else return@mapNotNull null
                val target = requestUrl.resolve(href) ?: throw WebDavException("Invalid WebDAV resource address.")
                if (!sameOrigin(requestUrl, target) || target.username.isNotEmpty() || target.password.isNotEmpty())
                    throw WebDavException("WebDAV listing contains another server.")
                val props = children(entry, "propstat").firstOrNull { text(it, "status")?.let(OK_STATUS::containsMatchIn) == true }
                    ?.let { children(it, "prop").firstOrNull() } ?: if (strict) throw WebDavException("Unreadable listing resource") else return@mapNotNull null
                val modified = text(props, "getlastmodified")
                WebDavItem(target.toString(), text(props, "displayname")?.ifBlank { null } ?: filename(target),
                    children(props, "resourcetype").any { children(it, "collection").isNotEmpty() },
                    text(props, "getetag"), modified,
                    modified?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() },
                    text(props, "getcontentlength")?.toLongOrNull(), text(props, "getcontenttype"))
            }
            if (strict && rows.map { it.url }.distinct().size != rows.size) throw WebDavException("Duplicate listing resource")
            return rows.distinctBy { it.url }
        } catch (error: WebDavException) { throw error }
        catch (_: Exception) { throw WebDavException("Invalid WebDAV listing.") }
    }
    private fun children(element: Element, name: String): List<Element> = (0 until element.childNodes.length).mapNotNull { i ->
        (element.childNodes.item(i) as? Element)?.takeIf { it.localName == name && it.namespaceURI == "DAV:" }
    }
    private fun text(element: Element, name: String): String? = children(element, name).firstOrNull()?.textContent?.trim()
}

object WebDavSelection {
    private fun WebDavItem.decodedName(): String = url.toHttpUrlOrNull()?.encodedPathSegments?.lastOrNull()?.let {
        runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it)
    } ?: ""
    private fun WebDavItem.isIcs(): Boolean = decodedName().endsWith(".ics", true)
    private fun WebDavItem.isXls(): Boolean = decodedName().endsWith(".xls", true)
    private fun WebDavItem.isCsv(): Boolean = decodedName().endsWith(".csv", true)
    fun children(folder: String, items: List<WebDavItem>): List<WebDavItem> = items.filter { it.url.trimEnd('/') != folder.trimEnd('/') &&
        (it.folder || it.isIcs() || it.isCsv() || it.isXls()) }.sortedWith(compareByDescending<WebDavItem> { it.folder }.thenBy { it.name.lowercase() })
    fun latest(items: List<WebDavItem>, extension: String = "ics"): WebDavItem {
        val match: (WebDavItem) -> Boolean = when (extension.lowercase()) {
            "xls" -> { item -> !item.folder && item.isXls() }
            "csv" -> { item -> !item.folder && item.isCsv() }
            else -> { item -> !item.folder && item.isIcs() }
        }
        val label = extension.uppercase()
        val files = items.filter(match)
        if (files.isEmpty()) throw WebDavException("No $label files in folder.")
        if (files.size > 1 && files.any { it.modifiedAt == null })
            throw WebDavException("Cannot determine newest $label; choose an exact file.")
        return files.sortedWith(compareByDescending<WebDavItem> { it.modifiedAt ?: Long.MIN_VALUE }
            .thenBy { it.url }).first()
    }
}
