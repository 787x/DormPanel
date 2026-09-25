package com.dormpanel.app.apps

import com.dormpanel.app.schedule.TemporaryLanUploadServer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One-shot private-LAN capability URL. The browser sends the selected File as a stream. */
class TemporaryApkUploadServer private constructor(private val server: ServerSocket, val address: String,
    val token: String, val expiresAt: Long, private val temporaryDirectory: File,
    private val accept: (InputStream, Long) -> Boolean,
    private val expired: () -> Unit) : AutoCloseable {
    val url = "http://$address:${server.localPort}/$token/"
    private val closed = AtomicBoolean(false)
    private val uploading = AtomicBoolean(false)
    private val worker = Executors.newFixedThreadPool(2) { task -> Thread(task, "apk-lan-client").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "apk-lan-expiry").apply { isDaemon = true } }
    init {
        timer.schedule({ if (finish()) expired() }, (expiresAt - System.currentTimeMillis()).coerceAtLeast(0), java.util.concurrent.TimeUnit.MILLISECONDS)
        Thread({ while (!closed.get()) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            socket.soTimeout = 20_000
            worker.execute { serve(socket) }
        } }, "apk-lan-accept").apply { isDaemon = true; start() }
    }
    private fun serve(socket: Socket) {
        try { socket.use {
            val input = it.getInputStream(); val output = it.getOutputStream()
            val raw = ByteArrayOutputStream()
            var tail = 0
            while (raw.size() < 8192) {
                val next = input.read(); if (next < 0) return
                raw.write(next)
                tail = ((tail shl 8) or next) and 0xffff_ffff.toInt()
                if (tail == 0x0d0a0d0a) break
            }
            if (raw.size() >= 8192) { reply(output, 413, "Headers too large."); return }
            val lines = raw.toString("ISO-8859-1").split("\r\n")
            val parts = lines.firstOrNull()?.split(' ') ?: emptyList()
            if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) { reply(output, 400, "Invalid request."); return }
            val path = parts[1]; val base = "/$token/"
            if (closed.get() || System.currentTimeMillis() >= expiresAt ||
                !MessageDigest.isEqual(path.take(token.length + 1).toByteArray(), "/$token".toByteArray()) ||
                path !in setOf(base, "${base}upload")) { reply(output, 404, "Not found."); return }
            if (parts[0] == "GET" && path == base) { reply(output, 200, FORM); return }
            if (parts[0] != "POST" || path != "${base}upload") { reply(output, 405, "Method not allowed."); return }
            if (!uploading.compareAndSet(false, true)) { reply(output, 409, "Another upload is in progress."); return }
            try {
                val headers = lines.drop(1).mapNotNull { line ->
                    val at = line.indexOf(':'); if (at <= 0) null else line.substring(0, at).lowercase() to line.substring(at + 1).trim()
                }.toMap()
                val length = headers["content-length"]?.toLongOrNull() ?: run { reply(output, 411, "File length required."); return }
                if (length <= 0) { reply(output, 411, "File length required."); return }
                val contentType = headers["content-type"].orEmpty()
                if (contentType.startsWith("multipart/form-data", true)) {
                    val boundary = Regex("boundary=(?:\"([^\"]+)\"|([^;]+))", RegexOption.IGNORE_CASE)
                        .find(contentType)?.let { it.groupValues[1].ifEmpty { it.groupValues[2].trim() } }
                    if (boundary == null || boundary.length !in 1..70) { reply(output, 400, "Invalid upload boundary."); return }
                    if (length > ApkStaging.LIMIT + 16 * 1024) { reply(output, 413, "APK exceeds 256 MiB."); return }
                    val accepted = readMultipart(input, length, boundary)
                    if (!accepted) { reply(output, 422, "Invalid APK. Retry with one ordinary APK file."); return }
                    if (!finish()) { reply(output, 404, "Session expired."); return }
                    reply(output, 200, "APK received. Confirm installation on DormPanel.")
                    return
                }
                if (!contentType.startsWith("application/vnd.android.package-archive")) {
                    reply(output, 400, "Choose one .apk file."); return
                }
                if (length > ApkStaging.LIMIT) { reply(output, 413, "APK exceeds 256 MiB."); return }
                val bounded = object : InputStream() {
                    var remaining = length
                    override fun read(): Int { if (remaining <= 0) return -1; val value = input.read(); if (value >= 0) remaining--; return value }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (remaining <= 0) return -1
                        val count = input.read(b, off, minOf(len.toLong(), remaining).toInt())
                        if (count > 0) remaining -= count
                        return count
                    }
                }
                val accepted = try { accept(bounded, length) } catch (_: Exception) { false }
                if (bounded.remaining != 0L || !accepted) { reply(output, 422, "Invalid APK. Retry with one ordinary APK file."); return }
                if (!finish()) { reply(output, 404, "Session expired."); return }
                reply(output, 200, "APK received. Confirm installation on DormPanel.")
            } finally { uploading.set(false) }
        } } catch (_: Exception) { /* client disconnected or session closed */ }
    }
    override fun close() { finish() }
    private fun readMultipart(input: InputStream, length: Long, boundary: String): Boolean {
        temporaryDirectory.mkdirs()
        val temporary = File(temporaryDirectory, "${java.util.UUID.randomUUID()}.multipart")
        try {
            var count = 0L
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (count < length) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - count).toInt())
                    if (read < 0) return false
                    count += read; if (count > ApkStaging.LIMIT + 16 * 1024) return false
                    output.write(buffer, 0, read)
                }
            }
            val prefix = ByteArray(minOf(temporary.length(), 8192).toInt())
            temporary.inputStream().use { java.io.DataInputStream(it).readFully(prefix) }
            val marker = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
            var headerEnd = -1
            for (index in 0..(prefix.size - marker.size)) {
                if (marker.indices.all { prefix[index + it] == marker[it] }) { headerEnd = index; break }
            }
            if (headerEnd < 0) return false
            val header = String(prefix, 0, headerEnd, Charsets.ISO_8859_1)
            if (!header.startsWith("--$boundary\r\n") ||
                !Regex("filename=\"[^\"]+\\.apk\"", RegexOption.IGNORE_CASE).containsMatchIn(header) ||
                !header.contains("Content-Disposition: form-data", true)) return false
            val payloadStart = headerEnd + marker.size
            val suffixes = listOf("\r\n--$boundary--\r\n", "\r\n--$boundary--")
            val suffix = suffixes.firstOrNull { value ->
                val bytes = value.toByteArray(Charsets.ISO_8859_1)
                if (temporary.length() < payloadStart + bytes.size) false else java.io.RandomAccessFile(temporary, "r").use { random ->
                    random.seek(temporary.length() - bytes.size); ByteArray(bytes.size).also { random.readFully(it) }.contentEquals(bytes)
                }
            } ?: return false
            val payloadSize = temporary.length() - payloadStart - suffix.toByteArray(Charsets.ISO_8859_1).size
            if (payloadSize !in 1L..ApkStaging.LIMIT) return false
            temporary.inputStream().use { fileInput ->
                var skipped = 0L
                while (skipped < payloadStart.toLong()) skipped += fileInput.skip(payloadStart.toLong() - skipped).takeIf { it > 0 } ?: return false
                val part = object : InputStream() {
                    var remaining = payloadSize
                    override fun read(): Int { if (remaining <= 0) return -1; val value = fileInput.read(); if (value >= 0) remaining--; return value }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (remaining <= 0) return -1
                        val read = fileInput.read(b, off, minOf(len.toLong(), remaining).toInt())
                        if (read > 0) remaining -= read
                        return read
                    }
                }
                return accept(part, payloadSize) && part.remaining == 0L
            }
        } catch (_: Exception) { return false }
        finally { temporary.delete() }
    }
    private fun finish(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        runCatching { server.close() }; timer.shutdownNow(); worker.shutdownNow(); return true
    }
    companion object {
        private const val FORM = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>DormPanel APK upload</title><style>body{font:18px system-ui,sans-serif;max-width:36rem;margin:2rem auto;padding:1rem}input,button{font:inherit;padding:.7rem;margin:.5rem}</style></head><body><h1>DormPanel APK upload</h1><input id="apk" type="file" accept=".apk,application/vnd.android.package-archive"><button id="send">Upload APK</button><p id="status"></p><script>document.getElementById('send').onclick=async()=>{const f=document.getElementById('apk').files[0],s=document.getElementById('status');if(!f||!f.name.toLowerCase().endsWith('.apk')||f.size<1||f.size>268435456){s.textContent='Choose one APK under 256 MiB.';return}try{const r=await fetch('upload',{method:'POST',headers:{'Content-Type':'application/vnd.android.package-archive'},body:f});s.textContent=await r.text()}catch(e){s.textContent='Upload failed.'}};</script></body></html>"""
        fun start(temporaryDirectory: File, accept: (InputStream, Long) -> Boolean, expired: () -> Unit = {}): TemporaryApkUploadServer {
            val address = TemporaryLanUploadServer.deviceAddress() ?: throw IllegalStateException("Private network unavailable.")
            return create(address, temporaryDirectory, 10 * 60 * 1000L, accept, expired)
        }
        internal fun startForTest(address: String, temporaryDirectory: File, lifetimeMs: Long,
            accept: (InputStream, Long) -> Boolean, expired: () -> Unit = {}): TemporaryApkUploadServer =
            create(address, temporaryDirectory, lifetimeMs, accept, expired)
        private fun create(address: String, temporaryDirectory: File, lifetimeMs: Long,
            accept: (InputStream, Long) -> Boolean, expired: () -> Unit): TemporaryApkUploadServer {
            val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
            return TemporaryApkUploadServer(ServerSocket(0, 8, InetAddress.getByName(address)), address,
                token, System.currentTimeMillis() + lifetimeMs, temporaryDirectory, accept, expired)
        }
        private fun reply(output: java.io.OutputStream, code: Int, message: String) {
            val html = if (message.startsWith("<!doctype")) message else "<!doctype html><meta charset=\"utf-8\"><p>$message</p>"
            val bytes = html.toByteArray(Charsets.UTF_8)
            output.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(bytes); output.flush()
        }
    }
}
