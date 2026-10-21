package com.dormpanel.app.schedule

import android.util.Log
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

/** Real HTTPS only. The host kills this isolated instrumentation process at a logged socket boundary. */
class RealUploadRecoveryTest {
    @Test fun recoverRealUpload() {
        val args = InstrumentationRegistry.getArguments()
        val run = args.getString("pr36Run")
        org.junit.Assume.assumeTrue(run != null)
        ScheduleCommitCodec.uuid(requireNotNull(run))
        val base = requireNotNull(args.getString("pr36Url"))
        val client = WebDavClient()
        val url = client.url(base)
        require(url.scheme == "https" && url.pathSegments.last { it.isNotEmpty() } == "pr36-recovery-$run")
        val account = WebDavAccount(base, requireNotNull(args.getString("pr36User")), requireNotNull(args.getString("pr36Password")))
        val directory = url.newBuilder().addPathSegment("commits").addPathSegment("").build().toString()
        val remote = WebDavCommitStore(account, directory)
        val phase = requireNotNull(args.getString("pr36Phase"))
        val mode = requireNotNull(args.getString("pr36Mode")); require(mode in listOf("body", "ack"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "pr36-recovery-$run"
        TermScheduleProfileStore.overrideNamespace = name
        val executor = Executors.newSingleThreadExecutor()
        val store = RoomScheduleStore(Room.databaseBuilder(context, ScheduleDatabase::class.java, "$name.db").build(), executor, profiles = TermScheduleProfileStore(context))
        fun put(entry: TimetableEntry) {
            val future = CompletableFuture<Result<Unit>>()
            store.put(entry) { future.complete(it) }; future.get(15, TimeUnit.SECONDS).getOrThrow()
        }
        try {
            when (phase) {
                "prepare" -> {
                    client.makeCollection(account, base); client.makeCollection(account, directory)
                    val fixture = SnapshotFixture.snapshot(run)
                    val root = ScheduleCommit(run, run, UUID.randomUUID().toString(), emptyList(), fixture.state)
                    remote.publish(ScheduleCommitCodec.encode(root)); store.v2Start(root, true)
                    put(store.v2Frame().local.entries.single().copy(location = "before upload"))
                    val f = store.v2Frame()
                    store.v2Stage(ScheduleCommitCodec.encode(ScheduleCommit(run, UUID.randomUUID().toString(), f.writerId, f.frontier, f.local)))
                    Log.i("PR36Recovery", "$run PREPARED")
                }
                "upload" -> {
                    val pending = requireNotNull(store.v2Frame().pending)
                    // This interceptor never fabricates a response/error. Bytes go to the real FN endpoint.
                    val http = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                        val request = chain.request()
                        if (request.method != "PUT") chain.proceed(request) else {
                            val original = requireNotNull(request.body)
                            val body = object : RequestBody() {
                                override fun contentType() = original.contentType()
                                override fun contentLength() = original.contentLength()
                                override fun writeTo(sink: BufferedSink) {
                                    val bytes = Buffer(); original.writeTo(bytes)
                                    var first = true
                                    while (bytes.size > 0) {
                                        sink.write(bytes, minOf(256L, bytes.size)); sink.flush()
                                        if (first) {
                                            first = false
                                            put(store.v2Frame().local.entries.single().copy(location = "during upload"))
                                            Log.i("PR36Recovery", "$run BODY_FLUSHED_EDIT_DURABLE")
                                        }
                                        if (mode == "body") Thread.sleep(350)
                                    }
                                }
                            }
                            val response = chain.proceed(request.newBuilder().method("PUT", body).build())
                            if (mode == "ack") {
                                check(response.code in listOf(200, 201, 204))
                                Log.i("PR36Recovery", "$run SERVER_ACK_${response.code}_BEFORE_DELIVERY")
                                Thread.sleep(45_000)
                            }
                            response
                        }
                    }.build()
                    WebDavCommitStore(account, directory, WebDavClient(http)).publishWithIntent(pending, true, store::v2Attempt)
                    error("Host did not terminate the process at the required upload boundary")
                }
                "recover" -> {
                    val f = store.v2Frame(); val bytes = requireNotNull(f.pending)
                    assertTrue(f.attempted)
                    assertEquals("during upload", f.local.entries.single().location)
                    assertEquals("before upload", ScheduleCommitCodec.decode(bytes).state.entries.single().location)
                    val oldId = ScheduleCommitCodec.decode(bytes).commitId
                    val oldBytes = remote.read(oldId)
                    val result = AppendOnlyScheduleSync(store, remote).synchronize()
                    assertTrue(result.conflicts.isEmpty())
                    assertNull(store.v2Frame().pending)
                    assertEquals("during upload", store.v2Frame().local.entries.single().location)
                    assertArrayEquals(oldBytes, remote.read(oldId)) // absent/partial/valid path is never overwritten
                    val ids = remote.list()
                    assertTrue(ids.any { it != oldId && it != run })
                    if (mode == "ack") {
                        assertArrayEquals(bytes, oldBytes)
                        assertFalse(result.message.contains("暂不可验证"))
                    } else if (oldBytes != null && runCatching { ScheduleCommitCodec.decode(oldBytes) }.isFailure) {
                        assertTrue(result.message.contains("尚未完成全部版本同步"))
                    }
                    Log.i("PR36Recovery", "$run RECOVERED old=${oldBytes?.size ?: -1} commits=${ids.size} ${result.message}")
                }
                "peer" -> {
                    val root = ScheduleCommitCodec.decode(requireNotNull(remote.read(run)))
                    store.v2Start(root, true)
                    val result = AppendOnlyScheduleSync(store, remote).synchronize()
                    assertTrue(result.conflicts.isEmpty())
                    assertEquals("during upload", store.v2Frame().local.entries.single().location)
                }
                else -> error("Unknown phase")
            }
        } finally {
            store.close(); assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
            TermScheduleProfileStore.overrideNamespace = null
        }
    }
}
