package com.dormpanel.app.schedule

/** A round owns one budget across every epoch/store/cache read; never reset it during discovery. */
class V3Budget(private val maxRequests: Int = 512, private val maxBytes: Long = 64L * 1024 * 1024,
    private val maxMillis: Long = 90_000, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val started = clock(); private var requests = 0; private var bytes = 0L
    init { require(maxRequests in 0..512 && maxBytes in 0..64L * 1024 * 1024 && maxMillis in 0..90_000) }
    @Synchronized fun charge(size: Long = 0, calls: Int = 1) {
        require(size >= 0 && calls >= 0)
        check(clock() - started < maxMillis && requests.toLong() + calls <= maxRequests && size <= maxBytes - bytes) { "v3 budget exhausted" }
        requests += calls; bytes += size
    }
    @Synchronized fun readLimit(max: Int): Int { charge(0, 0); return minOf(max.toLong(), maxBytes - bytes).toInt() }
}

object V3Paths {
    const val ROOT = "dormpanel-sync-v3/"
    fun dataset(id: String): String { ScheduleCommitCodec.uuid(id); require(id != SyncV3Codec.ZERO); return "dataset-$id/" }
    fun epoch(dataset: String, epoch: String): String { ScheduleCommitCodec.uuid(epoch); require(epoch != SyncV3Codec.ZERO); return dataset(dataset) + "epoch-$epoch/" }
    fun path(o: V3Object): String = (if (o.type == 1) dataset(o.datasetId) else epoch(o.datasetId, o.epochId)) +
        listOf("", "descriptor", "genesis", "commit", "transition")[o.type] + "-${o.objectId}.dpv"
    fun scope(s: String) {
        require(s == "" || s.matches(Regex("dataset-[0-9a-f-]{36}/(?:epoch-[0-9a-f-]{36}/)?")))
        if (s.isNotEmpty()) { require(dataset(s.substring(8, 44)) == s.take(45)); if (s.length > 45) require(epoch(s.substring(8, 44), s.substring(51, 87)) == s) }
    }
    fun file(s: String) {
        val scope = s.substringBeforeLast('/', "") + "/"; scope(scope)
        val name = s.substringAfterLast('/'); val match = Regex("(descriptor|genesis|commit|transition)-([0-9a-f-]{36})\\.dpv").matchEntire(name)
        requireNotNull(match); ScheduleCommitCodec.uuid(match.groupValues[2]); require(match.groupValues[2] != SyncV3Codec.ZERO)
        require((scope.length == 45) == (match.groupValues[1] == "descriptor"))
    }
    fun match(path: String, raw: ByteArray): V3Object { file(path); return SyncV3Codec.decode(raw).also { require(path(it) == path) } }
    fun child(scope: String, name: String, directory: Boolean): String {
        scope(scope)
        val p = scope + name + if (directory) "/" else ""
        if (directory) { scope(p); require(if (scope.isEmpty()) p.length == 45 else scope.length == 45 && p.length == 88) }
        else file(p)
        return p
    }
}

interface V3ObjectStore {
    /** Only observed direct children; absence is not a completeness proof. */
    fun list(scope: String): List<String>
    fun read(path: String): ByteArray?
    /** PR53 test-only primitive. No production publisher until durable intent/capture coordination exists. */
    fun createForIsolatedTest(path: String, bytes: ByteArray, beforeWriteIntent: () -> Unit)
}

class V3WebDavStore(private val account: WebDavAccount, folder: String,
    private val budget: V3Budget, private val client: WebDavClient = WebDavClient()) : V3ObjectStore {
    private val base = client.url(folder).also {
        require(it.encodedPath.endsWith('/') && it.pathSegments.none { s -> s in listOf("dormpanel-sync-v2", "dormpanel-sync-v3") || '/' in s || '\\' in s || '\u0000' in s })
        require(it.scheme == client.url(account.baseUrl).scheme && it.host == client.url(account.baseUrl).host && it.port == client.url(account.baseUrl).port)
    }.newBuilder().addPathSegment("dormpanel-sync-v3").addPathSegment("").build().toString()
    private fun url(path: String) = base + path
    override fun list(scope: String): List<String> {
        V3Paths.scope(scope); budget.charge(WebDavClient.MAX_XML.toLong())
        val directory = url(scope)
        val xml = listing(directory)
        return client.parseListing(xml, client.url(directory), true).mapNotNull { row ->
            if (row.url == directory || row.url == directory.trimEnd('/')) { require(row.folder); null } else {
                require(row.url.startsWith(directory))
                val tail = row.url.removePrefix(directory).removeSuffix("/")
                require(!tail.contains('/') && !tail.contains('%'))
                val path = V3Paths.child(scope, tail, row.folder)
                require(url(path) == row.url && (row.length == null || row.length in 0..SyncV3Codec.MAX_BYTES.toLong()))
                path
            }
        }.also { require(it.size <= 256 && it.distinct().size == it.size) }.sorted()
    }
    override fun read(path: String): ByteArray? {
        V3Paths.file(path); budget.charge()
        return client.readResource(account, url(path), budget.readLimit(SyncV3Codec.MAX_BYTES))?.bytes?.also { budget.charge(it.size.toLong(), 0); V3Paths.match(path, it) }
    }
    private val listingHttp = okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).callTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    private fun listing(directory: String): ByteArray {
        val body = "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"
        val request = okhttp3.Request.Builder().url(directory).header("Depth", "1")
            .header("Authorization", okhttp3.Credentials.basic(account.username, account.password))
            .method("PROPFIND", with(okhttp3.RequestBody.Companion) { body.toRequestBody(with(okhttp3.MediaType.Companion) { "application/xml".toMediaType() }) }).build()
        val raw = listingHttp.newCall(request).execute().use { response ->
            require(response.code == 207)
            val out = java.io.ByteArrayOutputStream()
            requireNotNull(response.body).byteStream().use { input ->
                val b = ByteArray(8192)
                while (true) { val n = input.read(b); if (n < 0) break; require(out.size() + n <= WebDavClient.MAX_XML); out.write(b, 0, n) }
            }; out.toByteArray()
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(raw)).toString()
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true))
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) } }
        val doc = parser.parse(org.xml.sax.InputSource(java.io.StringReader(text)))
        val hrefs = doc.getElementsByTagNameNS("DAV:", "href")
        for (index in 0 until hrefs.length) {
            val href = hrefs.item(index).textContent
            require('\\' !in href && !Regex("(?i)%2f|%5c|%00|(?:^|/)(?:\\.|%2e){1,2}(?:/|$)").containsMatchIn(href))
        }
        val props = doc.getElementsByTagNameNS("DAV:", "propstat")
        for (index in 0 until props.length) {
            val statuses = (props.item(index) as org.w3c.dom.Element).getElementsByTagNameNS("DAV:", "status")
            require(statuses.length == 1 && Regex("HTTP/\\d(?:\\.\\d)? 200(?: .*)?").matches(statuses.item(0).textContent))
        }
        return raw
    }
    fun prepareForIsolatedTest(scope: String) {
        V3Paths.scope(scope); budget.charge(); client.makeCollection(account, base)
        var p = ""; scope.split('/').filter { it.isNotEmpty() }.forEach { part -> p += "$part/"; budget.charge(); client.makeCollection(account, url(p)) }
    }
    override fun createForIsolatedTest(path: String, bytes: ByteArray, beforeWriteIntent: () -> Unit) {
        V3Paths.match(path, bytes)
        val existing = read(path)
        if (existing != null) { check(existing.contentEquals(bytes)) { "v3 occupied" }; return }
        budget.charge(bytes.size.toLong()); beforeWriteIntent()
        budget.charge(0, 0)
        client.ordinaryPut(account, url(path), bytes)
        check(read(path)?.contentEquals(bytes) == true) { "v3 uncertain write; do not retry PUT" }
    }
}
