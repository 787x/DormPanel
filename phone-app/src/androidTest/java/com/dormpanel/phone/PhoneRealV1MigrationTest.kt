package com.dormpanel.phone

import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.*

/** Opt-in real HTTPS v1 fixtures. Never opens the daily package/database or production directory. */
class PhoneRealV1MigrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> call(action: ((Result<T>) -> Unit) -> Unit): T {
        val result = CompletableFuture<Result<T>>()
        action { result.complete(it) }; return result.get(20, TimeUnit.SECONDS).getOrThrow()
    }
    private fun waitFor(scenario: ActivityScenario<TimetableActivity>, predicate: (TimetableActivity) -> Boolean) {
        val until = System.currentTimeMillis() + 70_000
        while (System.currentTimeMillis() < until) {
            var done = false; scenario.onActivity { done = predicate(it) }
            if (done) return
            Thread.sleep(60)
        }
        error("Real migration UI did not reach expected state")
    }
    private fun canonical(bytes: ByteArray): ByteArray {
        val s = ScheduleSnapshotCodec.decode(bytes); val v = s.state
        return ScheduleSnapshotCodec.encode(s.copy(state = v.copy(
            events = v.events.sortedBy { it.id }, entries = v.entries.sortedBy { it.id },
            sources = v.sources.sortedBy { it.id }, imported = v.imported.sortedBy { it.id },
            dayAdjustments = v.dayAdjustments.sortedBy { it.date }, classOverrides = v.classOverrides.sortedBy { it.key },
            profiles = v.profiles.toSortedMap())))
    }
    @Test fun realV1ThroughManagement() {
        val args = InstrumentationRegistry.getArguments()
        val run = args.getString("pr36MigrationRun")
        org.junit.Assume.assumeTrue(run != null)
        ScheduleCommitCodec.uuid(requireNotNull(run))
        val context = instrumentation.targetContext
        require(context.packageName == "com.dormpanel.timetable.testbed")
        val kind = requireNotNull(args.getString("pr36MigrationCase"))
        require(kind in listOf("success", "join", "committed", "uncommitted", "missing", "corrupt", "dataset", "unprovable", "interrupted"))
        val phase = requireNotNull(args.getString("pr36MigrationPhase"))
        require(phase in listOf("seed", "migrate", "interrupt", "recover", "verify-rejected", "cleanup"))
        val name = "pr36-migration-$run-$kind"
        val base = requireNotNull(args.getString("pr36MigrationUrl"))
        val client = WebDavClient(); val url = client.url(base)
        require(url.scheme == "https" && url.pathSegments.last { it.isNotEmpty() } == name)
        SharedScheduleSettings.overrideNamespace = name
        WebDavSettings.overrideNamespace = name
        TermScheduleProfileStore.overrideNamespace = name
        val folder = File(context.getExternalFilesDir(null), name).apply { mkdirs() }
        fun snapshotFile(file: String) = File(folder, "$file.dps")
        val accountInput = WebDavAccount(base, requireNotNull(args.getString("pr36MigrationUser")), requireNotNull(args.getString("pr36MigrationPassword")))
        val pool = mutableListOf<ExecutorService>()
        fun open(): Pair<ScheduleDatabase, RoomScheduleStore> {
            val db = Room.databaseBuilder(context, ScheduleDatabase::class.java, "$name.db").build()
            val executor = Executors.newSingleThreadExecutor().also { pool += it }
            return db to RoomScheduleStore(db, executor, profiles = TermScheduleProfileStore(context), afterSnapshotRows = {
                if (phase == "interrupt") {
                    check(db.dao().syncRecord(100) != null)
                    Log.i("PR36Migration", "$run $kind ARCHIVE_COMMITTED_TRANSACTION_OPEN")
                    Thread.sleep(60_000) // Host kills the isolated process here; no fabricated server response.
                    error("Host did not interrupt the migration transaction")
                }
            })
        }
        fun remoteFiles(account: WebDavAccount): Map<String, String> = client.list(account, base, strict = true).filter {
            it.name == "dormpanel-head-v1" || it.name.endsWith(".dps")
        }.associate { it.name to digest(requireNotNull(client.readResource(account, it.url, ScheduleSnapshotCodec.MAX_BYTES)).bytes) }.toSortedMap()
        fun records(db: ScheduleDatabase, ids: IntRange) = ids.mapNotNull { id -> db.dao().syncRecord(id)?.let { id.toString() to digest(it.payload) } }.toMap().toSortedMap()
        fun credentialsUnchanged(): Boolean = WebDavSettings(context).account()?.let {
            it.baseUrl == accountInput.baseUrl && it.username == accountInput.username && it.password == accountInput.password
        } == true
        fun credentialsHash() = digest(File(context.applicationInfo.dataDir, "shared_prefs/${name}_credentials.xml").readBytes())
        fun audit(label: String, db: ScheduleDatabase, store: RoomScheduleStore) {
            val identity = ScheduleSnapshotCodec.decode(snapshotFile("root").readBytes())
            val state = store.syncFrame().local
            for (id in 1..117) db.dao().syncRecord(id)?.let { File(folder, "$label-checkpoint-$id.bin").writeBytes(it.payload) }
            val bytes = ScheduleSnapshotCodec.encode(identity.copy(state = state))
            snapshotFile(label).writeBytes(bytes)
            val account = requireNotNull(WebDavSettings(context).account())
            val report = JSONObject().put("phase", label).put("case", kind).put("dataset", identity.datasetId)
                .put("stateSha256", digest(bytes)).put("checkpoint", JSONObject(records(db, 1..117)))
                .put("v1Remote", JSONObject(remoteFiles(account))).put("credentialSha256", credentialsHash()).put("credentialsDecryptUnchanged", credentialsUnchanged())
                .put("counts", JSONObject().put("entries", state.entries.size).put("events", state.events.size)
                    .put("sources", state.sources.size).put("imported", state.imported.size).put("edits", state.classOverrides.size)
                    .put("days", state.dayAdjustments.size).put("profiles", state.profiles.size).put("customProfiles", state.customProfileKeys.size))
            File(folder, "$label.json").writeText(report.toString(2))
        }
        try {
            if (phase == "cleanup") {
                context.deleteDatabase("$name.db")
                check(!context.getDatabasePath("$name.db").exists())
                listOf(name, "${name}_credentials", "${name}_settings").forEach {
                    check(context.getSharedPreferences(it, 0).edit().clear().commit())
                }
                java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null); deleteEntry("DormPanel.WebDAV.Password.$name.v1")
                }
                // Keep synthetic exports as evidence. No remote deletion or daily storage access.
                Log.i("PR36Migration", "$run $kind ISOLATED_STATE_CLEANED")
                return
            }
            if (phase == "seed") {
                require(!context.getDatabasePath("$name.db").exists()) { "Fixture already exists; use a new run" }
                client.makeCollection(accountInput, base)
                val root = SnapshotFixture.snapshot(run).copy(datasetId = run, generation = 1)
                snapshotFile("root").writeBytes(ScheduleSnapshotCodec.encode(root))
                val (db, store) = open()
                try {
                    val localBase = if (kind == "unprovable") root.copy(revision = UUID.randomUUID().toString()) else root
                    call<Unit> { store.applySnapshot(ScheduleSnapshotCodec.encode(localBase), it) }
                    call<Unit> { store.put(root.state.entries.single().copy(location = if (kind in listOf("committed", "uncommitted")) "candidate" else "offline"), it) }
                    val next = root.copy(revision = UUID.randomUUID().toString(), parentRevision = root.revision, generation = 2,
                        state = root.state.copy(events = root.state.events.map { it.copy(note = "remote advanced") }))
                    client.ordinaryPut(accountInput, base + "snapshot-${root.revision}.dps", ScheduleSnapshotCodec.encode(root))
                    val rootHead = SharedScheduleHead(root.datasetId, root.revision, digest(ScheduleSnapshotCodec.encode(root))).bytes()
                    client.ordinaryPut(accountInput, base + "dormpanel-head-v1", rootHead)
                    val rootReceipt = requireNotNull(client.readResource(accountInput, base + "dormpanel-head-v1", 512))
                    assertArrayEquals(rootHead, rootReceipt.bytes)
                    val rootEtag = requireNotNull(rootReceipt.etag)
                    var tip = next
                    if (kind in listOf("committed", "uncommitted")) {
                        val capture = store.syncFrame().local
                        val candidate = next.copy(state = capture)
                        val bytes = ScheduleSnapshotCodec.encode(candidate)
                        store.stagePublication(bytes, capture)
                        // Real PUT: persist another local edit after the first network flush, before request completion.
                        val http = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                            val request = chain.request(); val original = requireNotNull(request.body)
                            val body = object : RequestBody() {
                                override fun contentType() = original.contentType()
                                override fun contentLength() = original.contentLength()
                                override fun writeTo(sink: BufferedSink) {
                                    val buffer = Buffer(); original.writeTo(buffer)
                                    sink.write(buffer, minOf(256L, buffer.size)); sink.flush()
                                    call<Unit> { store.put(root.state.events.single().copy(note = "during-v1-upload"), it) }
                                    Log.i("PR36Migration", "$run $kind V1_BODY_FLUSHED_NEW_EDIT_DURABLE")
                                    sink.writeAll(buffer)
                                }
                            }
                            chain.proceed(request.newBuilder().method(request.method, body).build())
                        }.build()
                        WebDavClient(http).ordinaryPut(accountInput, base + "snapshot-${candidate.revision}.dps", bytes)
                        tip = if (kind == "committed") candidate else root
                    } else if (kind == "missing" || kind == "corrupt") {
                        val missing = UUID.randomUUID().toString()
                        tip = next.copy(parentRevision = missing, generation = 3)
                        if (kind == "corrupt") client.ordinaryPut(accountInput, base + "snapshot-$missing.dps", byteArrayOf(1,2,3))
                        client.ordinaryPut(accountInput, base + "snapshot-${tip.revision}.dps", ScheduleSnapshotCodec.encode(tip))
                    } else {
                        if (kind == "dataset") tip = next.copy(datasetId = UUID.randomUUID().toString())
                        client.ordinaryPut(accountInput, base + "snapshot-${tip.revision}.dps", ScheduleSnapshotCodec.encode(tip))
                    }
                    val tipBytes = ScheduleSnapshotCodec.encode(tip)
                    client.ordinaryPut(accountInput, base + "dormpanel-head-v1", SharedScheduleHead(tip.datasetId, tip.revision, digest(tipBytes)).bytes())
                    if (kind == "join") {
                        val directory = base + "dormpanel-sync-v2/"; client.makeCollection(accountInput, directory)
                        WebDavCommitStore(accountInput, directory).publish(ScheduleCommitCodec.encode(ScheduleCommit(run, UUID.randomUUID().toString(), UUID.randomUUID().toString(), emptyList(), tip.state)))
                    }
                    WebDavSettings(context).saveAccount(accountInput)
                    val settings = SharedScheduleSettings(context); settings.folder = base
                    // Real v1 preference binding and durable checkpoint; no writes to daily preferences.
                    context.getSharedPreferences(name, 0).edit().putString("dataset", run).commit()
                    settings.published(localBase.revision, rootEtag, localBase.generation)
                    store.syncFrame().pending?.let { settings.pending(it, ScheduleSnapshotCodec.encode(it)) }
                    context.getSharedPreferences("${name}_credentials", 0).edit().commit()
                    context.getSharedPreferences("${name}_settings", 0).edit().commit()
                    val expected = if (kind in listOf("committed", "uncommitted")) store.syncFrame().local else
                        root.state.copy(entries = root.state.entries.map { it.copy(location = "offline") }, events = next.state.events)
                    snapshotFile("expected").writeBytes(ScheduleSnapshotCodec.encode(root.copy(state = expected)))
                    audit("before", db, store)
                } finally { store.close() }
                return
            }
            val before = JSONObject(File(folder, "before.json").readText())
            fun verifyOriginal(db: ScheduleDatabase, store: RoomScheduleStore) {
                assertArrayEquals(snapshotFile("before").readBytes(), ScheduleSnapshotCodec.encode(ScheduleSnapshotCodec.decode(snapshotFile("root").readBytes()).copy(state = store.syncFrame().local)))
                val old = before.getJSONObject("checkpoint")
                for (id in 1..7) assertEquals(old.optString("$id", ""), db.dao().syncRecord(id)?.let { digest(it.payload) }.orEmpty())
                assertTrue("Saved credentials must decrypt to the original account", credentialsUnchanged())
                assertNull(db.dao().syncRecord(21)); assertNull(db.dao().syncRecord(23))
            }
            if (phase == "verify-rejected" || phase == "recover") {
                val (db, store) = open()
                try { verifyOriginal(db, store); audit("reopened", db, store)
                    if (phase == "recover") { assertNotNull(db.dao().syncRecord(100)); assertNotNull(db.dao().syncRecord(111)) }
                } finally { store.close() }
                if (phase == "verify-rejected") return
            }
            var activeDb: ScheduleDatabase? = null
            ScheduleStores.overrideFactory = { open().also { activeDb = it.first }.second }
            launchPhoneTestActivity().use { scenario ->
                waitFor(scenario) { it.source.ready && !it.sharedSync.busy }
                onView(withText("管理")).perform(click())
                onView(withText("WebDAV 共享课表（双向同步）")).perform(click())
                onView(withText("将已有 v1 绑定切换到 v2")).perform(scrollTo(), click())
                onView(withText("旧客户端已停止，切换")).perform(click())
                Log.i("PR36Migration", "$run $kind MANAGEMENT_SWITCH_CONFIRMED")
                waitFor(scenario) { !it.sharedSync.busy }
                var activeStore: RoomScheduleStore? = null
                var status = ""
                scenario.onActivity { activeStore = it.source.syncStore; status = it.sharedSync.status }
                val db = requireNotNull(activeDb); val store = requireNotNull(activeStore)
                audit("after", db, store)
                if (kind in listOf("missing", "corrupt", "dataset", "unprovable")) {
                    verifyOriginal(db, store)
                    assertFalse(status.startsWith("已检查观察到的版本"))
                } else {
                    assertTrue(status, status.startsWith("已检查观察到的版本"))
                    assertNull(store.syncFrame().pending); assertNull(store.v2Frame().pending)
                    assertEquals(run, store.v2Frame().baseline!!.datasetId)
                    assertArrayEquals(canonical(snapshotFile("expected").readBytes()), canonical(ScheduleSnapshotCodec.encode(ScheduleSnapshotCodec.decode(snapshotFile("root").readBytes()).copy(state = store.syncFrame().local))))
                    val old = before.getJSONObject("checkpoint")
                    for (id in 1..7) if (old.has("$id")) assertEquals(old.getString("$id"), db.dao().syncRecord(110 + id)?.let { digest(it.payload) })
                    assertArrayEquals(snapshotFile("before").readBytes(), db.dao().syncRecord(100)!!.payload)
                    assertTrue("Saved credentials must decrypt to the original account", credentialsUnchanged())
                }
                audit("after", db, store)
                File(folder, "status.txt").writeText(status)
                assertEquals(before.getJSONObject("v1Remote").toString(), JSONObject(remoteFiles(requireNotNull(WebDavSettings(context).account()))).toString())
                Log.i("PR36Migration", "$run $kind VERIFIED $status")
            }
        } finally {
            ScheduleStores.overrideFactory = null
            SharedScheduleSettings.overrideNamespace = null; WebDavSettings.overrideNamespace = null; TermScheduleProfileStore.overrideNamespace = null
            pool.forEach { it.shutdown(); it.awaitTermination(10, TimeUnit.SECONDS) }
        }
    }
}
