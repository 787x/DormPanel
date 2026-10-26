package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Review regressions only: random private Room/preferences and a read-only fake server. */
class SharedScheduleReviewAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun id() = UUID.randomUUID().toString()
    private fun <T> main(call: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(call)) }
        return result.get().getOrThrow()
    }
    private fun until(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(40) }
        error("Review state did not settle")
    }
    private inner class Harness : AutoCloseable {
        val context = instrumentation.targetContext
        val name = "pr60-review-${id()}"
        val executor = Executors.newSingleThreadExecutor()
        val db = Room.databaseBuilder(context, ScheduleDatabase::class.java, "$name.db").build()
        val server = MockWebServer()
        val files = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        val requests = CopyOnWriteArrayList<String>()
        @Volatile var disconnect = false
        lateinit var source: ScheduleSource
        lateinit var sync: SharedScheduleController
        val accounts: WebDavSettings
        val settings: SharedScheduleSettings
        val store: RoomScheduleStore
        val account get() = WebDavAccount(server.url("/").toString(), "fixture-user", "fixture-secret")
        init {
            SharedScheduleSettings.overrideNamespace = name; WebDavSettings.overrideNamespace = name; TermScheduleProfileStore.overrideNamespace = name
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += "${request.method} ${request.path}"
                    if (disconnect) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    val path = request.path!!
                    if (request.method == "GET") return files[path]?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                    if (request.method != "PROPFIND") return MockResponse().setResponseCode(405)
                    val dirs = mutableSetOf("/")
                    for (key in files.keys) for (index in key.indices) if (key[index] == '/') dirs += key.substring(0, index + 1)
                    if (path !in dirs) return MockResponse().setResponseCode(404)
                    val rows = (dirs + files.keys).filter { it.startsWith(path) && '/' !in it.removePrefix(path).trimEnd('/') }
                    return MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">" + rows.joinToString("") {
                        "<d:response><d:href>$it</d:href><d:propstat><d:prop><d:resourcetype>" +
                            (if (it in dirs) "<d:collection/>" else "") + "</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                    } + "</d:multistatus>")
                }
            }
            server.start()
            accounts = WebDavSettings(context).also { it.saveAccount(account) }
            settings = SharedScheduleSettings(context).also { it.folder = account.baseUrl }
            store = RoomScheduleStore(db, executor, profiles = TermScheduleProfileStore(context))
            main { source = ScheduleSource(store); sync = SharedScheduleController(context, source, accounts, settings) }
            until { main { source.ready && !sync.busy && sync.presentation.mode == SharedMode.UNBOUND } }
        }
        fun result(code: String) = until { main { !sync.busy && sync.presentation.result == code } }
        fun reload() {
            main { source.reload() }
            until { main { !source.applyingSnapshot } }
        }
        fun checkpoints() = db.dao().syncRecords().associate { it.id to digest(it.payload) }
        override fun close() {
            main { sync.close(); source.close() }
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            server.shutdown()
            SharedScheduleSettings.overrideNamespace = null; WebDavSettings.overrideNamespace = null; TermScheduleProfileStore.overrideNamespace = null
        }
    }
    @Test fun reloadFailureCannotReuseOldHealthyBindingAndSuccess() {
        Harness().use { h ->
            val snapshot = ScheduleSnapshot(id(), id(), null, 1, ScheduleState(), 1)
            h.db.dao().checkpoint(ScheduleSyncCheckpoint(1, ScheduleSnapshotCodec.encode(snapshot)))
            h.reload()
            h.settings.recordCheck(sharedCheckScope(h.account, h.settings.folder), "VERIFIED", true)
            main { h.sync.refreshPresentation() }; h.result("VERIFIED")
            assertTrue(main { h.sync.presentation.summary().contains("检查成功") })
            h.db.dao().checkpoint(ScheduleSyncCheckpoint(1, byteArrayOf(1)))
            h.reload()
            until { main { h.sync.presentation.localRead == SharedLocalRead.FAILED } }
            main {
                assertTrue(h.source.ready); assertTrue(h.source.error)
                val text = h.sync.presentation.summary()
                assertTrue(text.contains("读取失败")); assertFalse(text.contains("课表已保存")); assertFalse(text.contains("检查成功"))
            }
            h.db.dao().checkpoint(ScheduleSyncCheckpoint(1, ScheduleSnapshotCodec.encode(snapshot)))
            h.reload()
            until { main { h.sync.presentation.localRead == SharedLocalRead.LOADED } }
            assertTrue(main { h.sync.presentation.summary().contains("检查成功") })
            assertEquals(snapshot.datasetId, main { h.source.state.sync!!.datasetId })
        }
    }
    @Test fun connectionBRequiresItsOwnCheckAndPreservesDiagnosticA() {
        Harness().use { h ->
            h.settings.presentationResult = "VERIFIED"; h.settings.result("旧版未绑定位置的诊断", true)
            main { h.sync.refreshPresentation() }; h.result("UNCHECKED")
            assertEquals(0L, main { h.sync.presentation.lastSuccess })
            val rootA = ScheduleCommit(id(), id(), id(), emptyList(), ScheduleState())
            val rootB = ScheduleCommit(id(), id(), id(), emptyList(), ScheduleState())
            h.files["/A/dormpanel-sync-v2/commit-${rootA.commitId}.dpc"] = ScheduleCommitCodec.encode(rootA)
            h.files["/B/dormpanel-sync-v2/commit-${rootB.commitId}.dpc"] = ScheduleCommitCodec.encode(rootB)
            h.settings.folder = h.server.url("/A/").toString()
            main { h.sync.inspect {} }; h.result("VERIFIED")
            val rawSuccess = h.settings.lastSuccess; val rawResult = h.settings.lastResult
            h.settings.folder = h.server.url("/B/").toString()
            main { h.sync.connectionSaved() }
            assertEquals("UNCHECKED", main { h.sync.presentation.result })
            assertEquals(0L, main { h.sync.presentation.lastSuccess })
            assertEquals(rawResult, h.settings.lastResult); assertEquals(rawSuccess, h.settings.lastSuccess)
            main { h.sync.inspect {} }; h.result("VERIFIED")
            assertTrue(main { h.sync.presentation.lastSuccess > 0 })
            assertTrue(h.requests.any { it.startsWith("GET /B/") })
            assertTrue(h.requests.all { it.startsWith("GET ") || it.startsWith("PROPFIND ") })
        }
    }
    @Test fun staleV2CreationNeverTurnsIntoV3SyncAndStaleV3CreationNeverWritesV2() {
        Harness().use { h ->
            val confirmation = main { h.sync.confirmation(SharedAction.CREATE_V2) }
            h.store.prepareV3(h.settings.folder); h.reload()
            val before = h.checkpoints(); val count = h.requests.size
            main { h.sync.publish(confirmation) }
            until { main { !h.sync.busy && h.sync.status.contains("状态已变化") } }
            assertEquals(before, h.checkpoints()); assertEquals(count, h.requests.size); assertTrue(h.store.isV3())
        }
        Harness().use { h ->
            val confirmation = main { h.sync.confirmation(SharedAction.CREATE) }
            val root = ScheduleCommit(id(), id(), id(), emptyList(), ScheduleState())
            h.store.v2Start(root, true); h.reload()
            val before = h.checkpoints(); val count = h.requests.size
            main { h.sync.createV3(confirmation) }
            until { main { !h.sync.busy && h.sync.status.contains("状态已变化") } }
            assertEquals(before, h.checkpoints()); assertEquals(count, h.requests.size); assertFalse(h.store.isV3())
        }
    }
    @Test fun joinFindsV3OrVerifiedV2AndSeparatesEmptyInvalidAndNetworkWithoutWrites() {
        Harness().use { h ->
            val found = AtomicReference<SharedScheduleController.ExistingCandidates?>()
            fun discover(code: String) { found.set(null); main { h.sync.discoverExisting { found.set(it) } }; h.result(code) }
            val before = h.checkpoints()
            discover("NO_CANDIDATE"); assertNotNull(found.get()); assertNull(found.get()!!.v2)
            assertTrue(main { h.sync.status.contains("没有创建") })
            val root = ScheduleCommit(id(), id(), id(), emptyList(), ScheduleState())
            val v2Path = "/dormpanel-sync-v2/commit-${root.commitId}.dpc"
            h.files[v2Path] = ScheduleCommitCodec.encode(root)
            discover("CANDIDATE_V2"); assertEquals(root.datasetId, found.get()!!.v2!!.head.datasetId)
            val descriptor = V3Object(1, id(), id(), id())
            val v3Path = "/dormpanel-sync-v3/" + V3Paths.path(descriptor)
            h.files[v3Path] = SyncV3Codec.encode(descriptor)
            val offset = h.requests.size
            discover("CANDIDATE_V3"); assertEquals(descriptor.datasetId, found.get()!!.v3.single().descriptor.datasetId)
            assertNull(found.get()!!.v2); assertFalse(h.requests.drop(offset).any { "dormpanel-sync-v2/" in it })
            h.files[v3Path] = byteArrayOf(1)
            val invalidOffset = h.requests.size
            discover("VALIDATION_FAILED"); assertNull(found.get())
            assertFalse("新版验证失败不能静默改选旧版", h.requests.drop(invalidOffset).any { "dormpanel-sync-v2/" in it })
            h.files.remove(v3Path); h.files[v2Path] = byteArrayOf(1)
            discover("VALIDATION_FAILED"); assertNull(found.get())
            assertFalse(main { h.sync.presentation.summary().contains("检查成功") })
            h.disconnect = true
            discover("NETWORK"); assertNull(found.get())
            assertEquals(before, h.checkpoints())
            assertTrue(h.requests.all { it.startsWith("GET ") || it.startsWith("PROPFIND ") })
        }
    }
    @Test fun verifiedJoinConfirmationsExpireWhenAnotherProtocolBecomesActive() {
        Harness().use { h ->
            val root = ScheduleCommit(id(), id(), id(), emptyList(), ScheduleState())
            h.files["/dormpanel-sync-v2/commit-${root.commitId}.dpc"] = ScheduleCommitCodec.encode(root)
            val verified = AtomicReference<SharedScheduleRemote?>()
            val confirmation = main { h.sync.confirmation(SharedAction.JOIN_V2) }
            main { h.sync.inspect { verified.set(it) } }; h.result("VERIFIED")
            h.store.prepareV3(h.settings.folder); h.reload()
            val before = h.checkpoints(); val count = h.requests.size
            main { h.sync.bind(verified.get()!!, confirmation) }
            until { main { !h.sync.busy && h.sync.status.contains("状态已变化") } }
            assertEquals(before, h.checkpoints()); assertEquals(count, h.requests.size)
        }
        Harness().use { h ->
            val d = V3Object(1, id(), id(), id())
            val bytes = SyncV3Codec.encode(d)
            val genesis = V3Object(2, d.datasetId, id(), d.writerId, id(), 1, SyncV3Codec.hash(bytes), state = ScheduleState())
            h.files["/dormpanel-sync-v3/" + V3Paths.path(d)] = bytes
            h.files["/dormpanel-sync-v3/" + V3Paths.path(genesis)] = SyncV3Codec.encode(genesis)
            val confirmation = main { h.sync.confirmation(SharedAction.JOIN) }
            val preview = AtomicReference<UnboundV3JoinPreview?>()
            for (round in 0..7) {
                main { h.sync.previewJoinV3(bytes) { preview.set(it) } }
                until { main { !h.sync.busy } }
                if (preview.get() != null) break
            }
            assertNotNull(preview.get())
            h.store.v2Start(ScheduleCommit(id(), id(), id(), emptyList(), ScheduleState()), true); h.reload()
            val before = h.checkpoints(); val count = h.requests.size
            main { h.sync.joinV3(preview.get()!!, confirmation) }
            until { main { !h.sync.busy && h.sync.status.contains("状态已变化") } }
            assertEquals(before, h.checkpoints()); assertEquals(count, h.requests.size)
        }
    }
    @Test fun migrationConfirmationDoesNotResumeAnAlreadyPreparedV3Task() {
        Harness().use { h ->
            val root = ScheduleCommit(id(), id(), id(), emptyList(), ScheduleState())
            h.files["/dormpanel-sync-v2/commit-${root.commitId}.dpc"] = ScheduleCommitCodec.encode(root)
            h.store.v2Start(root, true); h.reload()
            val confirmation = main { h.sync.confirmation(SharedAction.MIGRATE_V3) }
            val preview = AtomicReference<ScheduleV3MigrationPreview?>()
            main { h.sync.previewV3Migration { preview.set(it) } }
            until { main { !h.sync.busy } }; assertNotNull(preview.get())
            h.store.prepareV3Migration(preview.get()!!, WebDavCommitStore(h.account, h.server.url("/dormpanel-sync-v2/").toString()), h.settings.folder)
            h.reload()
            val before = h.checkpoints(); val count = h.requests.size
            main { h.sync.migrateV3(preview.get()!!, confirmation) }
            until { main { !h.sync.busy && h.sync.status.contains("状态已变化") } }
            assertEquals(before, h.checkpoints()); assertEquals(count, h.requests.size)
            assertTrue(h.store.isV3())
        }
    }
}
