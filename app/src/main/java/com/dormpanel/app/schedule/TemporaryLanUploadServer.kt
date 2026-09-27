package com.dormpanel.app.schedule

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A single, explicitly started capability URL. No schedule storage or UI state is owned here. */
class TemporaryLanUploadServer private constructor(
    private val listener: ServerSocket, val address: String, val token: String,
    val startedAt: Long, val expiresAt: Long, private val validate: (ScheduleArtifact) -> Boolean,
    private val onAccepted: () -> Unit,
    private val onExpired: () -> Unit, private val now: () -> Long
) : AutoCloseable {
    val url: String = "http://$address:${listener.localPort}/$token/"
    val port: Int get() = listener.localPort
    private val closed = AtomicBoolean(false)
    private val uploading = AtomicBoolean(false)
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val workers = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue()) {
        task -> Thread(task, "lan-upload-client").apply { isDaemon = true }
    }
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "lan-upload-expiry").apply { isDaemon = true } }
    private val expiry: ScheduledFuture<*>
    private val acceptThread: Thread

    init {
        expiry = timer.schedule({ if (finish(null)) onExpired() }, (expiresAt - now()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        acceptThread = Thread({
            while (!closed.get()) {
                val socket = try { listener.accept() } catch (_: IOException) { break }
                if (closed.get()) { socket.close(); break }
                socket.soTimeout = 15_000
                clients.add(socket)
                try { workers.execute { serve(socket) } } catch (_: Exception) {
                    clients.remove(socket)
                    try { reply(socket.getOutputStream(), 503, "Receiver busy. Try again shortly.") } catch (_: IOException) {}
                    socket.close()
                }
            }
        }, "lan-upload-accept").apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket) {
        var successful = false
        try {
            socket.use {
                val input = it.getInputStream()
                val output = it.getOutputStream()
                val header = ByteArrayOutputStream()
                var previous = 0
                while (header.size() < HEADER_LIMIT) {
                    val next = input.read()
                    if (next < 0) return
                    header.write(next)
                    if (previous == 13 && next == 10 && header.size() >= 4) {
                        val bytes = header.toByteArray()
                        if (bytes[bytes.size - 4] == 13.toByte() && bytes[bytes.size - 3] == 10.toByte()) break
                    }
                    previous = next
                }
                if (header.size() >= HEADER_LIMIT) { reply(output, 413, "Request headers too large."); return }
                val lines = header.toString("ISO-8859-1").split("\r\n")
                val request = lines.firstOrNull()?.split(' ') ?: emptyList()
                if (request.size != 3 || !request[2].startsWith("HTTP/1.")) { reply(output, 400, "Invalid request."); return }
                val path = request[1]
                val base = "/$token/"
                val allowed = (path.length == base.length || path.length == base.length + 6) &&
                    MessageDigest.isEqual(path.take(token.length + 1).toByteArray(), "/$token".toByteArray()) &&
                    (path == base || path == "${base}upload")
                if (closed.get() || now() >= expiresAt || !allowed) { reply(output, 404, "Not found."); return }
                val method = request[0]
                if (path == base && method == "GET") { reply(output, 200, FORM); return }
                if (path != "${base}upload" || method != "POST") { reply(output, 405, "Method not allowed."); return }
                if (!uploading.compareAndSet(false, true)) { reply(output, 409, "Another upload is in progress."); return }
                try {
                    val headers = lines.drop(1).mapNotNull { line ->
                        val index = line.indexOf(':'); if (index <= 0) null else line.substring(0, index).lowercase() to line.substring(index + 1).trim()
                    }.toMap()
                    val length = headers["content-length"]?.toIntOrNull()
                    if (length == null || length < 0) { reply(output, 411, "Content length required."); return }
                    if (length > BODY_LIMIT) { reply(output, 413, "Timetable file exceeds the upload size limit."); return }
                    val type = headers["content-type"].orEmpty()
                    val boundary = Regex("boundary=(?:\"([^\"]+)\"|([^;]+))", RegexOption.IGNORE_CASE)
                        .find(type)?.let { it.groupValues[1].ifEmpty { it.groupValues[2].trim() } }
                    if (!type.startsWith("multipart/form-data", true) || boundary == null || boundary.length !in 1..70) {
                        reply(output, 400, "Choose one .ics or .csv file."); return
                    }
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) { val count = input.read(body, read, length - read); if (count < 0) throw IOException("Short body"); read += count }
                    val artifact = try { multipart(body, boundary) } catch (error: ScheduleImportException) {
                        if (error.message?.contains("limit") == true) reply(output, 413, "Timetable file exceeds the 1 MiB size limit.")
                        else reply(output, 400, "Choose one valid .ics or .csv file.")
                        return
                    }
                    val valid = try { validate(artifact) } catch (_: Exception) { false }
                    if (!valid) {
                        reply(output, 422, "Timetable file could not be imported. Check the file and try again."); return
                    }
                    if (now() >= expiresAt || !finish(socket)) { reply(output, 404, "Session expired."); return }
                    successful = true
                    try { reply(output, 200, "Upload received. Confirm the timetable on DormPanel.") }
                    finally { onAccepted() }
                } finally { uploading.set(false) }
            }
        } catch (_: IOException) { /* A disconnected browser or closed session has no UI effect. */ }
        finally { clients.remove(socket); if (successful) close() }
    }

    override fun close() { finish(null) }

    private fun finish(except: Socket?): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        expiry.cancel(false); timer.shutdownNow()
        try { listener.close() } catch (_: IOException) {}
        synchronized(clients) { clients.toList().filter { it !== except }.forEach { try { it.close() } catch (_: IOException) {} } }
        if (except == null) workers.shutdownNow() else workers.shutdown()
        return true
    }

    companion object {
        const val LIFETIME_MS = 10 * 60 * 1000L
        private const val HEADER_LIMIT = 8192
        private const val BODY_LIMIT = ImportLimits.BYTES + 16 * 1024
        private val FORM = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>DormPanel timetable upload</title><style>body{font:18px system-ui,sans-serif;max-width:36rem;margin:2rem auto;padding:1rem;line-height:1.5}input,button{font:inherit;margin:.5rem 0;padding:.6rem}button{min-height:3rem}</style></head><body><h1>DormPanel timetable upload</h1><form method="post" action="upload" enctype="multipart/form-data"><label>Choose .ics or .csv file <input type="file" name="file" accept=".ics,.csv,text/calendar,text/csv" required></label><br><button type="submit">Upload</button></form><p>This upload goes directly to the DormPanel currently showing the QR code.</p></body></html>"""

        fun start(address: String, validate: (ScheduleArtifact) -> Boolean, onAccepted: () -> Unit = {}, onExpired: () -> Unit = {},
            now: () -> Long = System::currentTimeMillis, lifetimeMs: Long = LIFETIME_MS): TemporaryLanUploadServer {
            require(lifetimeMs > 0)
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            val token = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
            val socket = ServerSocket(0, 8, InetAddress.getByName(address))
            val start = now()
            return TemporaryLanUploadServer(socket, address, token, start, start + lifetimeMs, validate, onAccepted, onExpired, now)
        }

        /** Pure policy so address choice can be exercised without device interfaces. */
        fun selectAddress(candidates: List<String>): String? = candidates.mapNotNull { raw ->
            if (!raw.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) return@mapNotNull null
            val address = runCatching { java.net.InetAddress.getByName(raw) }.getOrNull()
            if (address is Inet4Address && address.isSiteLocalAddress && !address.isLoopbackAddress &&
                !address.isLinkLocalAddress && !address.isAnyLocalAddress) raw else null
        }.firstOrNull()

        fun deviceAddress(): String? = runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            selectAddress(interfaces.filter { it.isUp && !it.isLoopback && !it.isVirtual &&
                !it.name.startsWith("tun") && !it.name.startsWith("tap") && !it.name.startsWith("rmnet") }
                .sortedBy { candidate -> when {
                    candidate.name.startsWith("wlan") || candidate.name.startsWith("wifi") -> 0
                    candidate.name.startsWith("eth") || candidate.name.startsWith("en") -> 1
                    else -> 2
                } }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress.orEmpty() })
        }.getOrNull()

        private fun multipart(body: ByteArray, boundary: String): ScheduleArtifact {
            val raw = String(body, StandardCharsets.ISO_8859_1)
            val delimiter = "--$boundary"
            importCheck(raw.startsWith("$delimiter\r\n") && raw.endsWith("$delimiter--\r\n") ||
                raw.startsWith("$delimiter\r\n") && raw.endsWith("$delimiter--"), "Malformed multipart.")
            var cursor = delimiter.length + 2
            var artifact: ScheduleArtifact? = null
            while (true) {
                val headersEnd = raw.indexOf("\r\n\r\n", cursor)
                importCheck(headersEnd in cursor..(cursor + HEADER_LIMIT), "Malformed multipart.")
                val headers = raw.substring(cursor, headersEnd)
                val next = raw.indexOf("\r\n$delimiter", headersEnd + 4)
                importCheck(next >= 0, "Malformed multipart.")
                val disposition = headers.lines().firstOrNull { it.startsWith("Content-Disposition:", true) }.orEmpty()
                val filename = Regex("filename=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)
                if (filename != null) {
                    val safeName = filename.substringAfterLast('/').substringAfterLast('\\')
                    val isTimetable = safeName.endsWith(".ics", true) || safeName.endsWith(".csv", true)
                    importCheck(artifact == null && isTimetable, "Choose one .ics or .csv file.")
                    val bytes = body.copyOfRange(headersEnd + 4, next)
                    importCheck(bytes.size <= ImportLimits.BYTES, "Timetable file exceeds the 1 MiB size limit.")
                    val mime = headers.lines().firstOrNull { it.startsWith("Content-Type:", true) }?.substringAfter(':')?.trim()
                    artifact = ScheduleArtifact(safeName, bytes, mime, "lan_upload", "lan_upload")
                }
                cursor = next + 2 + delimiter.length
                if (raw.startsWith("--", cursor)) break
                importCheck(raw.startsWith("\r\n", cursor), "Malformed multipart.")
                cursor += 2
            }
            return artifact ?: throw ScheduleImportException("Missing timetable file.")
        }

        private fun reply(output: java.io.OutputStream, code: Int, message: String) {
            val page = if (message.startsWith("<!doctype")) message else "<!doctype html><html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><body><p>$message</p></body></html>"
            val bytes = page.toByteArray(StandardCharsets.UTF_8)
            output.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write(bytes); output.flush()
        }
    }
}
