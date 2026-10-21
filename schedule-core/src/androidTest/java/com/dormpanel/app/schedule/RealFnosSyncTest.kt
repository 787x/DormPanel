package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

/** 显式启用的 fnOS / 双 Android 实机测试；仅使用 pr38-e2e-<run>/dormpanel-sync-v2 合成数据。 */
class RealFnosSyncTest {
    @Test fun isolatedPhysicalWriter() {
        val args = InstrumentationRegistry.getArguments()
        val run = args.getString("pr38RealRun")
        org.junit.Assume.assumeTrue("真实服务器测试需显式启用", run != null)
        ScheduleCommitCodec.uuid(requireNotNull(run))
        val base = requireNotNull(args.getString("pr38RealUrl"))
        val builder = okhttp3.OkHttpClient.Builder()
        args.getString("pr38RealCertificate")?.let { encoded ->
            val cert = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(
                java.io.ByteArrayInputStream(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)))
            val keys = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply {
                load(null); setCertificateEntry("isolated-fnos", cert)
            }
            val managers = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers
            val trust = managers.single { it is javax.net.ssl.X509TrustManager } as javax.net.ssl.X509TrustManager
            val tls = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            builder.sslSocketFactory(tls.socketFactory,trust).hostnameVerifier { _, session ->
                session.peerCertificates.first().encoded.contentEquals(cert.encoded)
            }
        }
        val client = WebDavClient(builder.build())
        val url = client.url(base)
        require(url.pathSegments.last { it.isNotEmpty() } == "pr38-e2e-$run")
        val account = WebDavAccount(base, requireNotNull(args.getString("pr38RealUser")), requireNotNull(args.getString("pr38RealPassword")))
        val dir = url.newBuilder().addPathSegment("dormpanel-sync-v2").addPathSegment("").build().toString()
        val backend = WebDavCommitStore(account, dir, client)
        val original = args.getString("pr38RealOriginalUrl")
        // 诊断地址别名不变更测试数据库的既有绑定身份；由 fnOS 端先完整证明为同一目录。
        val originalKey = original?.let { old ->
            val oldAccount = WebDavAccount(old, account.username, account.password)
            WebDavCommitStore(oldAccount, old + "dormpanel-sync-v2/", client).bindingKey
        }
        val remote = object : CommitStore by backend { override val bindingKey get() = originalKey ?: backend.bindingKey }
        val phase = requireNotNull(args.getString("pr38RealPhase"))
        if (phase == "seed") {
            client.makeCollection(account, base); client.makeCollection(account, dir)
            val fixture = SnapshotFixture.snapshot(run)
            remote.publish(ScheduleCommitCodec.encode(ScheduleCommit(run, run, "00000000-0000-4000-8000-000000000001", emptyList(), fixture.state)))
            assertEquals(listOf(run), remote.list()); return
        }
        val role = requireNotNull(args.getString("pr38RealRole")); require(role in listOf("a", "b"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "pr38-real-$run-$role"
        TermScheduleProfileStore.overrideNamespace = name
        val executor = Executors.newSingleThreadExecutor()
        val store = RoomScheduleStore(Room.databaseBuilder(context, ScheduleDatabase::class.java, "$name.db").build(), executor, profiles = TermScheduleProfileStore(context))
        fun <T> call(operation: ((Result<T>) -> Unit) -> Unit): T {
            val future = CompletableFuture<Result<T>>(); operation { future.complete(it) }; return future.get(20, TimeUnit.SECONDS).getOrThrow()
        }
        try {
            when (phase) {
                "verify-baseline" -> {
                    val state = requireNotNull(store.v2Frame().baseline).state
                    assertEquals("X08E offline", state.entries.first { it.id == "local" }.location)
                    assertEquals("Phone offline", state.events.single().note)
                    assertEquals("FPK fnOS", state.dayAdjustments.single().label)
                    assertTrue(state.entries.any { it.title == "FPK 新课" })
                }
                "prepare" -> {
                    val root = ScheduleCommitCodec.decode(requireNotNull(remote.read(run)))
                    require(root.datasetId == run && root.commitId == run)
                    store.v2Start(root, true)
                    if (role == "a") call<Unit> { store.put(root.state.entries.single().copy(location = "X08E offline"), it) }
                    else call<Unit> { store.put(root.state.events.single().copy(note = "Phone offline"), it) }
                    val frame = store.v2Frame()
                    store.v2Stage(ScheduleCommitCodec.encode(ScheduleCommit(run, UUID.randomUUID().toString(), frame.writerId, frame.frontier, frame.local)))
                    assertNotNull(store.v2Frame().pending)
                }
                "upload" -> {
                    val f = store.v2Frame(); val bytes = requireNotNull(f.pending)
                    remote.publishWithIntent(bytes, !f.attempted, store::v2Attempt); store.v2Receipt(bytes)
                    assertNull(store.v2Frame().pending)
                }
                "sync" -> assertTrue(AppendOnlyScheduleSync(store, remote).synchronize().conflicts.isEmpty())
                "verify" -> {
                    repeat(2) { assertTrue(AppendOnlyScheduleSync(store, remote).synchronize().conflicts.isEmpty()) }
                    val before = remote.list().size
                    repeat(2) { AppendOnlyScheduleSync(store, remote).synchronize() }
                    assertEquals(before, remote.list().size)
                    val state = store.v2Frame().local
                    assertEquals("X08E offline", state.entries.first { it.id == "local" }.location)
                    assertEquals("Phone offline", state.events.single().note)
                    assertEquals("FPK fnOS", state.dayAdjustments.single().label)
                    assertEquals("stable-instance", state.imported.single().id)
                    assertTrue(state.entries.any { it.title == "FPK 新课" })
                    assertTrue(ScheduleProjection.week(state, java.time.LocalDate.parse("2026-10-19"), java.time.ZoneId.of("Asia/Shanghai")).any { it.entry.title == "FPK 新课" })
                }
                "prepare-conflict" -> {
                    val frame = store.v2Frame()
                    call<Unit> { store.put(frame.local.entries.first { it.id == "local" }.copy(location = "conflict-$role"), it) }
                    val current = store.v2Frame()
                    store.v2Stage(ScheduleCommitCodec.encode(ScheduleCommit(run, UUID.randomUUID().toString(), current.writerId, current.frontier, current.local)))
                }
                "conflict" -> {
                    assertFalse(AppendOnlyScheduleSync(store, remote).synchronize().conflicts.isEmpty())
                    assertNotNull(store.v2Frame().conflict)
                    assertEquals("conflict-$role", store.v2Frame().local.entries.first { it.id == "local" }.location)
                }
                "resolve" -> {
                    store.v2Resolve(ScheduleMerge.Choice.LOCAL)
                    assertTrue(AppendOnlyScheduleSync(store, remote).synchronize().conflicts.isEmpty())
                }
                "verify-resolution" -> {
                    assertTrue(AppendOnlyScheduleSync(store, remote).synchronize().conflicts.isEmpty())
                    assertEquals("conflict-fpk", store.v2Frame().local.entries.first { it.id == "local" }.location)
                    assertNull(store.v2Frame().conflict)
                }
                else -> error("Unknown isolated test phase")
            }
        } finally { store.close(); assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS)); TermScheduleProfileStore.overrideNamespace = null }
    }
}
