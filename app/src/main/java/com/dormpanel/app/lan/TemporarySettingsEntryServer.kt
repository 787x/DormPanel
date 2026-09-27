package com.dormpanel.app.lan

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class SettingsEntryForm(val title: String, val fields: List<Pair<String, String>>, val secret: String) {
    HA("Home Assistant", listOf("url" to "Home Assistant base URL", "token" to "Access token"), "token"),
    WEBDAV("WebDAV", listOf("url" to "WebDAV server URL", "username" to "Username", "password" to "Password"), "password")
}

/** Short-lived, one-shot LAN form. No configuration or credential store is reachable from this server. */
class TemporarySettingsEntryServer private constructor(
    private val listener: ServerSocket, val address: String, val token: String,
    val expiresAt: Long, private val form: SettingsEntryForm, private val publicValues: Map<String, String>,
    private val onAccepted: (Map<String, String>) -> Unit, private val onExpired: () -> Unit,
    private val now: () -> Long
) : AutoCloseable {
    val url = "http://$address:${listener.localPort}/$token/"
    val port: Int get() = listener.localPort
    private val closed = AtomicBoolean(false)
    private val submitting = AtomicBoolean(false)
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val workers = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue()) {
        task -> Thread(task, "settings-entry-client").apply { isDaemon = true }
    }
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "settings-entry-expiry").apply { isDaemon = true } }

    init {
        timer.schedule({ if (finish(null)) onExpired() }, (expiresAt - now()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        Thread({
            while (!closed.get()) {
                val socket = try { listener.accept() } catch (_: IOException) { break }
                if (closed.get()) { socket.close(); break }
                socket.soTimeout = 15_000
                clients.add(socket)
                try { workers.execute { serve(socket) } } catch (_: Exception) {
                    clients.remove(socket)
                    runCatching { reply(socket, 503, "Receiver busy.") }
                    socket.close()
                }
            }
        }, "settings-entry-accept").apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                val input = it.getInputStream()
                val header = ByteArrayOutputStream()
                var end = 0
                while (header.size() < HEADER_LIMIT && end != 4) {
                    val next = input.read()
                    if (next < 0) return
                    header.write(next)
                    end = when {
                        next == "\r\n\r\n"[end].code -> end + 1
                        next == '\r'.code -> 1
                        else -> 0
                    }
                }
                if (end != 4) { reply(it, 413, "Request headers too large."); return }
                val lines = header.toString("ISO-8859-1").split("\r\n")
                val request = lines.firstOrNull()?.split(' ') ?: emptyList()
                if (request.size != 3 || request[2] !in listOf("HTTP/1.0", "HTTP/1.1")) {
                    reply(it, 400, "Invalid request."); return
                }
                val expected = "/$token/".toByteArray(StandardCharsets.US_ASCII)
                val path = request[1].toByteArray(StandardCharsets.US_ASCII)
                if (closed.get() || now() >= expiresAt || !MessageDigest.isEqual(path, expected)) {
                    reply(it, 404, "Not found."); return
                }
                if (request[0] == "GET") { reply(it, 200, page()); return }
                if (request[0] != "POST") { reply(it, 405, "Method not allowed."); return }
                if (!submitting.compareAndSet(false, true)) { reply(it, 409, "Submission in progress."); return }
                try {
                    val pairs = lines.drop(1).filter { line -> line.isNotEmpty() }.map { line ->
                        val colon = line.indexOf(':')
                        if (colon <= 0) { reply(it, 400, "Invalid request."); return }
                        line.substring(0, colon).lowercase() to line.substring(colon + 1).trim()
                    }
                    val headers = pairs.toMap()
                    if (pairs.count { entry -> entry.first == "content-length" } != 1 ||
                        headers.containsKey("transfer-encoding")) { reply(it, 411, "Content length required."); return }
                    val length = headers["content-length"]?.toIntOrNull()
                    if (length == null || length < 0) { reply(it, 411, "Content length required."); return }
                    if (length > BODY_LIMIT) { reply(it, 413, "Form too large."); return }
                    val type = headers["content-type"].orEmpty().lowercase()
                    if (type.substringBefore(';').trim() != "application/x-www-form-urlencoded" ||
                        (type.contains(';') && type.substringAfter(';').trim() != "charset=utf-8")) {
                        reply(it, 415, "Unsupported form type."); return
                    }
                    val bytes = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val count = input.read(bytes, read, length - read)
                        if (count < 0) throw IOException("Incomplete form")
                        read += count
                    }
                    val values = parse(bytes) ?: run { reply(it, 400, "Invalid form."); return }
                    if (closed.get() || now() >= expiresAt || !finish(it)) {
                        reply(it, 404, "Session expired."); return
                    }
                    try { reply(it, 200, "Received. Review and save on DormPanel.") }
                    finally { onAccepted(values) }
                } finally { submitting.set(false) }
            }
        } catch (_: IOException) { /* Closed or disconnected browser. */ }
        finally { clients.remove(socket) }
    }

    private fun parse(bytes: ByteArray): Map<String, String>? = runCatching {
        val raw = String(bytes, StandardCharsets.US_ASCII)
        val result = linkedMapOf<String, String>()
        for (part in raw.split('&')) {
            val equals = part.indexOf('=')
            require(equals > 0)
            val key = decode(part.substring(0, equals))
            require(form.fields.any { it.first == key } && key !in result)
            result[key] = decode(part.substring(equals + 1))
        }
        require(result.keys == form.fields.map { it.first }.toSet())
        require(result.values.none { it.any { char -> char == '\u0000' || char == '\r' || char == '\n' } })
        require(result[form.secret]!!.length <= if (form == SettingsEntryForm.HA) 8192 else 4096)
        require(result["username"].orEmpty().length <= 256)
        val url = result["url"].orEmpty()
        require(url.length <= 2048 && url.isNotBlank())
        result
    }.getOrNull()

    private fun decode(raw: String): String {
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < raw.length) {
            when (val char = raw[index]) {
                '+' -> out.write(' '.code)
                '%' -> {
                    require(index + 2 < raw.length)
                    val value = raw.substring(index + 1, index + 3).toIntOrNull(16) ?: error("Invalid escape")
                    out.write(value); index += 2
                }
                else -> {
                    require(char.code in 0x21..0x7e)
                    out.write(char.code)
                }
            }
            index++
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString()
    }

    private fun page(): String = buildString {
        append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>DormPanel settings entry</title>")
        append("<style>body{font:18px system-ui,sans-serif;max-width:36rem;margin:2rem auto;padding:1rem;line-height:1.5}label{display:block;margin:1rem 0}input{display:block;width:100%;box-sizing:border-box;font:inherit;padding:.6rem}button{font:inherit;padding:.7rem;min-height:3rem}</style></head><body>")
        append("<h1>").append(escape(form.title)).append(" settings</h1><p>Use only on a trusted local network. Credentials are sent directly to this DormPanel over the LAN.</p>")
        append("<form method=\"post\" enctype=\"application/x-www-form-urlencoded\">")
        for ((key, label) in form.fields) {
            append("<label>").append(escape(label)).append("<input name=\"").append(key).append("\" type=\"")
                .append(if (key == form.secret) "password" else "text").append('"')
            if (key == "url") append(" required")
            append(" maxlength=\"")
                .append(when (key) { "url" -> 2048; "username" -> 256; "token" -> 8192; else -> 4096 }).append('"')
            if (key != form.secret) append(" value=\"").append(escape(publicValues[key].orEmpty())).append('"')
            append("></label>")
        }
        append("<button type=\"submit\">Send to DormPanel</button></form><p>Review and save on DormPanel after sending.</p></body></html>")
    }

    override fun close() { finish(null) }

    private fun finish(except: Socket?): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        timer.shutdownNow()
        runCatching { listener.close() }
        synchronized(clients) { clients.toList().filter { it !== except }.forEach { runCatching { it.close() } } }
        if (except == null) workers.shutdownNow() else workers.shutdown()
        return true
    }

    companion object {
        const val LIFETIME_MS = 10 * 60 * 1000L
        private const val HEADER_LIMIT = 8192
        private const val BODY_LIMIT = 16 * 1024

        fun start(address: String, form: SettingsEntryForm, publicValues: Map<String, String>,
            onAccepted: (Map<String, String>) -> Unit, onExpired: () -> Unit = {},
            now: () -> Long = System::currentTimeMillis, lifetimeMs: Long = LIFETIME_MS): TemporarySettingsEntryServer {
            require(lifetimeMs > 0)
            val safeValues = publicValues.filterKeys { key -> key != form.secret && form.fields.any { it.first == key } }
                .mapValues { (key, value) -> value.take(if (key == "url") 2048 else 256) }
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            val token = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
            val listener = ServerSocket(0, 8, InetAddress.getByName(address))
            return TemporarySettingsEntryServer(listener, address, token, now() + lifetimeMs, form, safeValues,
                onAccepted, onExpired, now)
        }

        private fun escape(value: String): String = buildString {
            for (char in value) append(when (char) {
                '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&#39;"
                else -> if (char.code < 32 || char.code == 127) "" else char.toString()
            })
        }

        private fun reply(socket: Socket, code: Int, message: String) {
            val page = if (message.startsWith("<!doctype")) message else
                "<!doctype html><html><meta charset=\"utf-8\"><body><p>${escape(message)}</p></body></html>"
            val bytes = page.toByteArray(StandardCharsets.UTF_8)
            val headers = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
                "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
                "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'\r\n" +
                "X-Frame-Options: DENY\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(headers.toByteArray(StandardCharsets.US_ASCII))
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }
    }
}
