package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

/** Explicit opt-in; only synthetic data in pr35-e2e-<run>/commits/. Credentials never logged. */
class RealAppendOnlySyncTest {
    @Test fun isolatedPhysicalWriter() {
        val args = InstrumentationRegistry.getArguments()
        val run = args.getString("pr35RealRun")
        org.junit.Assume.assumeTrue("真实服务器测试需显式启用", run != null)
        ScheduleCommitCodec.uuid(requireNotNull(run))
        val base = requireNotNull(args.getString("pr35RealUrl"))
        val client = WebDavClient()
        val url = client.url(base)
        require(url.pathSegments.last { it.isNotEmpty() } == "pr35-e2e-$run")
        val account = WebDavAccount(base, requireNotNull(args.getString("pr35RealUser")), requireNotNull(args.getString("pr35RealPassword")))
        val dir = url.newBuilder().addPathSegment("commits").addPathSegment("").build().toString()
        val remote = WebDavCommitStore(account, dir)
        val phase = requireNotNull(args.getString("pr35RealPhase"))
        if (phase == "seed") {
            client.makeCollection(account, base); client.makeCollection(account, dir)
            val fixture = SnapshotFixture.snapshot(run)
            remote.publish(ScheduleCommitCodec.encode(ScheduleCommit(run, run, "00000000-0000-4000-8000-000000000001", emptyList(), fixture.state)))
            assertEquals(listOf(run), remote.list()); return
        }
        val role = requireNotNull(args.getString("pr35RealRole")); require(role in listOf("a", "b"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "pr35-real-$run-$role"
        TermScheduleProfileStore.overrideNamespace = name
        val executor = Executors.newSingleThreadExecutor()
        val store = RoomScheduleStore(Room.databaseBuilder(context, ScheduleDatabase::class.java, "$name.db").build(), executor, profiles = TermScheduleProfileStore(context))
        fun <T> call(operation: ((Result<T>) -> Unit) -> Unit): T {
            val future = CompletableFuture<Result<T>>(); operation { future.complete(it) }; return future.get(20, TimeUnit.SECONDS).getOrThrow()
        }
        try {
            when (phase) {
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
                    assertEquals("X08E offline", state.entries.single().location)
                    assertEquals("Phone offline", state.events.single().note)
                    assertEquals("FPK future Python", state.dayAdjustments.single().label)
                    assertEquals("stable-instance", state.imported.single().id)
                }
                "prepare-conflict" -> {
                    val frame = store.v2Frame()
                    call<Unit> { store.put(frame.local.entries.single().copy(location = "conflict-$role"), it) }
                    val current = store.v2Frame()
                    store.v2Stage(ScheduleCommitCodec.encode(ScheduleCommit(run, UUID.randomUUID().toString(), current.writerId, current.frontier, current.local)))
                }
                "conflict" -> {
                    assertFalse(AppendOnlyScheduleSync(store, remote).synchronize().conflicts.isEmpty())
                    assertNotNull(store.v2Frame().conflict)
                    assertEquals("conflict-$role", store.v2Frame().local.entries.single().location)
                }
                "resolve" -> {
                    store.v2Resolve(ScheduleMerge.Choice.LOCAL)
                    assertTrue(AppendOnlyScheduleSync(store, remote).synchronize().conflicts.isEmpty())
                }
                "verify-resolution" -> {
                    assertTrue(AppendOnlyScheduleSync(store, remote).synchronize().conflicts.isEmpty())
                    assertEquals("conflict-b", store.v2Frame().local.entries.single().location)
                    assertNull(store.v2Frame().conflict)
                }
                else -> error("Unknown isolated test phase")
            }
        } finally { store.close(); assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS)); TermScheduleProfileStore.overrideNamespace = null }
    }
}
