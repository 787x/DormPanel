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

internal class SettingsEntryReply(val code: Int, val text: String, val terminal: Boolean = false)
internal class SettingsEntryError(val field: String, message: String) : IllegalArgumentException(message)

/** Bounded capability URL. Only a successfully staged submission or cancellation consumes it. */
internal class TemporarySettingsEntryServer private constructor(
    private val listener: ServerSocket, val address: String, val token: String,
    val expiresAt: Long, private val page: (String, Long) -> String,
    private val onPost: (Map<String, String>) -> SettingsEntryReply,
    private val onFinished: (Map<String, String>) -> Unit, private val onExpired: () -> Unit,
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
                if (closed.get()) { clients.remove(socket); socket.close(); break }
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
                if (request[0] == "GET") { reply(it, 200, page(token, expiresAt - now()), html = true, nonce = token); return }
                if (request[0] != "POST") { reply(it, 405, "Method not allowed."); return }
                if (!submitting.compareAndSet(false, true)) { reply(it, 409, "Submission in progress."); return }
                try {
                    val pairs = lines.drop(1).filter { line -> line.isNotEmpty() }.map { line ->
                        val colon = line.indexOf(':')
                        if (colon <= 0) { reply(it, 400, "Invalid request."); return }
                        line.substring(0, colon).lowercase() to line.substring(colon + 1).trim()
                    }
                    val headers = pairs.toMap()
                    val origin = "http://$address:$port"
                    if (pairs.count { entry -> entry.first == "origin" } > 1 ||
                        headers["origin"]?.let { value -> value != origin } == true ||
                        headers["sec-fetch-site"] == "cross-site") {
                        reply(it, 403, "请求来源无效"); return
                    }
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
                    val values = parse(bytes) ?: run { reply(it, 400, "表单格式无效"); return }
                    if (closed.get() || now() >= expiresAt) {
                        reply(it, 404, "会话已结束"); return
                    }
                    val response = try { onPost(values) } catch (_: Exception) {
                        SettingsEntryReply(500, """{"message":"设备暂时无法处理请求"}""")
                    }
                    if (!response.terminal) {
                        if (!closed.get()) reply(it, response.code, response.text)
                        return
                    }
                    if (now() >= expiresAt || !finish(it)) {
                        reply(it, 404, "会话已结束"); return
                    }
                    try { reply(it, response.code, response.text) }
                    finally { onFinished(values) }
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
            require(key.matches(Regex("[A-Za-z_][A-Za-z_0-9]{0,63}")) && key !in result && result.size < 64)
            result[key] = decode(part.substring(equals + 1))
        }
        require(result.values.none { value -> value.any { it.isISOControl() } })
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

        fun start(address: String, page: (String, Long) -> String,
            onPost: (Map<String, String>) -> SettingsEntryReply,
            onFinished: (Map<String, String>) -> Unit, onExpired: () -> Unit = {},
            now: () -> Long = { System.nanoTime() / 1_000_000 }, lifetimeMs: Long = LIFETIME_MS): TemporarySettingsEntryServer {
            require(lifetimeMs > 0)
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            val token = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
            val listener = ServerSocket(0, 8, InetAddress.getByName(address))
            return TemporarySettingsEntryServer(listener, address, token, now() + lifetimeMs, page,
                onPost, onFinished, onExpired, now)
        }

        private fun reply(socket: Socket, code: Int, message: String, html: Boolean = false, nonce: String = "") {
            val bytes = message.toByteArray(StandardCharsets.UTF_8)
            val headers = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
                "Content-Type: ${if (html) "text/html" else "text/plain"}; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
                "Content-Security-Policy: default-src 'none'; script-src 'nonce-$nonce'; connect-src 'self'; style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'\r\n" +
                "X-Frame-Options: DENY\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(headers.toByteArray(StandardCharsets.US_ASCII))
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }
    }
}
