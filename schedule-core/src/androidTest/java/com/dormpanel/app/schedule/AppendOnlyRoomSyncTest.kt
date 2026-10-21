package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

class AppendOnlyRoomSyncTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun <T> call(block: ((Result<T>) -> Unit) -> Unit): T {
        val f = CompletableFuture<Result<T>>(); block { f.complete(it) }; return f.get(20, TimeUnit.SECONDS).getOrThrow()
    }
    private inner class Device : AutoCloseable {
        val name = "pr35-${UUID.randomUUID()}"
        var executor = Executors.newSingleThreadExecutor()
        val profiles: TermScheduleProfileStore
        var store: RoomScheduleStore
        init { TermScheduleProfileStore.overrideNamespace = name; profiles = TermScheduleProfileStore(context); store = open() }
        private fun open() = RoomScheduleStore(Room.databaseBuilder(context, ScheduleDatabase::class.java, "$name.db")
            .addMigrations(ScheduleDatabase.MIGRATION_1_2, ScheduleDatabase.MIGRATION_2_3, ScheduleDatabase.MIGRATION_3_4).build(), executor, profiles = profiles)
        fun reopen() { store.close(); check(executor.awaitTermination(10, TimeUnit.SECONDS)); executor = Executors.newSingleThreadExecutor(); store = open() }
        override fun close() { store.close(); check(executor.awaitTermination(10, TimeUnit.SECONDS)); context.deleteDatabase("$name.db"); context.getSharedPreferences(name, 0).edit().clear().commit(); TermScheduleProfileStore.overrideNamespace = null }
    }
    private class Remote : CommitStore {
        val files = linkedMapOf<String, ByteArray>()
        var loseResponse = false
        var omitted = emptySet<String>()
        var during: (() -> Unit)? = null
        override fun list() = files.keys.filter { it !in omitted }
        override fun read(commitId: String) = files[commitId]
        override fun publish(bytes: ByteArray, allowCreate: Boolean) {
            val id = ScheduleCommitCodec.decode(bytes).commitId
            files[id]?.let { if (!it.contentEquals(bytes)) throw CommitPathOccupied(); return }
            if (!allowCreate) throw CommitPathOccupied()
            files[id] = bytes
            during?.also { during = null }?.invoke()
            if (loseResponse) { loseResponse = false; error("lost response") }
        }
    }
    private fun root() = SnapshotFixture.snapshot().let { ScheduleCommit(it.datasetId, it.revision, UUID.randomUUID().toString(), emptyList(), it.state) }
    @Test fun threeDatabasesOfflineMergeDeleteResetCatalogAndReopen() {
        val root = root(); val remote = Remote().apply { files[root.commitId] = ScheduleCommitCodec.encode(root) }
        Device().use { a -> Device().use { b -> Device().use { c ->
            listOf(a,b,c).forEach { it.store.v2Start(root, true) }
            call<Unit> { a.store.put(root.state.entries.single().copy(location = "A301"), it) }
            call<Unit> { b.store.deleteEvent("event", it) }
            val edit = root.state.classOverrides.first { it.anchorDate != null }
            call<Unit> { c.store.putClassOverride(null, edit.key, it) }
            c.profiles.put(root.state.profiles.getValue("custom-term").copy(timezone = "Asia/Tokyo"))
            listOf(a,b,c).forEach { d ->
                val f = d.store.v2Frame()
                d.store.v2Stage(ScheduleCommitCodec.encode(ScheduleCommit(root.datasetId, UUID.randomUUID().toString(), f.writerId, listOf(root.commitId), f.local)))
                d.reopen()
            }
            repeat(4) { listOf(a,b,c).forEach { assertTrue(AppendOnlyScheduleSync(it.store, remote).synchronize().conflicts.isEmpty()) } }
            listOf(a,b,c).forEach {
                it.reopen(); val state = it.store.v2Frame().local
                assertEquals("A301", state.entries.single().location); assertTrue(state.events.isEmpty())
                assertTrue(state.classOverrides.none { row -> row.key == edit.key })
                assertEquals("Asia/Tokyo", state.profiles.getValue("custom-term").timezone)
                assertEquals(root.state.imported, state.imported)
            }
            val count = remote.files.size
            listOf(a,b,c).forEach { AppendOnlyScheduleSync(it.store, remote).synchronize() }
            assertEquals(count, remote.files.size)
        } } }
    }
    @Test fun responseLossReopenAndEditsDuringUploadPreserveCapture() {
        val root = root(); val remote = Remote().apply { files[root.commitId] = ScheduleCommitCodec.encode(root) }
        Device().use { d ->
            d.store.v2Start(root, true)
            call<Unit> { d.store.put(root.state.entries.single().copy(location = "before"), it) }
            remote.loseResponse = true
            remote.during = { call<Unit> { d.store.put(root.state.events.single().copy(note = "during"), it) } }
            assertThrows(IllegalStateException::class.java) { AppendOnlyScheduleSync(d.store, remote).synchronize() }
            val pending = d.store.v2Frame().pending!!
            d.reopen(); assertArrayEquals(pending, d.store.v2Frame().pending)
            AppendOnlyScheduleSync(d.store, remote).synchronize()
            assertEquals(3, remote.files.size); assertNull(d.store.v2Frame().pending)
            assertEquals("during", d.store.v2Frame().local.events.single().note)
            assertEquals("before", d.store.v2Frame().local.entries.single().location)
        }
    }
    @Test fun conflictReopenResolveAndRepublish() {
        val root = root(); val remote = Remote().apply { files[root.commitId] = ScheduleCommitCodec.encode(root) }
        Device().use { a -> Device().use { b ->
            a.store.v2Start(root, true); b.store.v2Start(root, true)
            call<Unit> { a.store.put(root.state.entries.single().copy(location = "A"), it) }
            call<Unit> { b.store.put(root.state.entries.single().copy(location = "B"), it) }
            AppendOnlyScheduleSync(a.store, remote).synchronize()
            assertFalse(AppendOnlyScheduleSync(b.store, remote).synchronize().conflicts.isEmpty())
            b.reopen(); assertNotNull(b.store.v2Frame().conflict)
            b.store.v2Resolve(ScheduleMerge.Choice.LOCAL); b.reopen(); assertNotNull(b.store.v2Frame().pending)
            AppendOnlyScheduleSync(b.store, remote).synchronize(); AppendOnlyScheduleSync(a.store, remote).synchronize()
            assertEquals("B", a.store.v2Frame().local.entries.single().location)
        } }
    }
    @Test fun v1MigrationKeepsCheckpointIdentityAndUnpublishedEdits() {
        val snapshot = SnapshotFixture.snapshot(); val bytes = ScheduleSnapshotCodec.encode(snapshot)
        Device().use { d ->
            call<Unit> { d.store.applySnapshot(bytes, it) }
            call<Unit> { d.store.put(snapshot.state.entries.single().copy(location = "offline"), it) }
            d.store.v2Migrate(snapshot); d.reopen()
            assertEquals(snapshot, d.store.syncFrame().baseline)
            assertEquals("offline", d.store.v2Frame().local.entries.single().location)
            assertEquals(snapshot.state.customProfileKeys, d.store.v2Frame().local.customProfileKeys)
            val root = root(); val remote = Remote().apply { files[root.commitId] = ScheduleCommitCodec.encode(root) }
            AppendOnlyScheduleSync(d.store, remote).synchronize()
            assertEquals("offline", d.store.v2Frame().local.entries.single().location)
            assertEquals(snapshot, d.store.syncFrame().baseline)
        }
    }
    @Test fun partialOwnUploadRenewsIdentityAndExplicitlyReportsQuarantine() {
        val root = root(); val remote = Remote().apply { files[root.commitId] = ScheduleCommitCodec.encode(root) }
        Device().use { d ->
            d.store.v2Start(root, true)
            call<Unit> { d.store.put(root.state.entries.single().copy(location = "offline"), it) }
            remote.loseResponse = true
            assertThrows(IllegalStateException::class.java) { AppendOnlyScheduleSync(d.store, remote).synchronize() }
            val old = ScheduleCommitCodec.decode(d.store.v2Frame().pending!!).commitId
            remote.files[old] = byteArrayOf(1,2,3)
            d.reopen()
            val outcome = AppendOnlyScheduleSync(d.store, remote).synchronize()
            assertTrue(outcome.message.contains("未确认"))
            assertNotEquals(old, d.store.v2Frame().baseline!!.commitId)
            assertEquals("offline", d.store.v2Frame().local.entries.single().location)
            assertArrayEquals(byteArrayOf(1,2,3), remote.files[old])
            d.reopen(); assertTrue(d.store.v2Frame().abandoned.containsKey(old))
            assertTrue(d.store.v2Frame().unverifiable.contains(old))
            Device().use { peer ->
                peer.store.v2Start(root, true)
                assertTrue(AppendOnlyScheduleSync(peer.store, remote).synchronize().message.contains("暂不可验证"))
                assertEquals("offline", peer.store.v2Frame().local.entries.single().location)
                peer.reopen(); remote.omitted = setOf(old)
                assertTrue(AppendOnlyScheduleSync(peer.store, remote).synchronize().message.contains("暂不可验证"))
                assertTrue(peer.store.v2Frame().unverifiable.contains(old))
            }
        }
    }
    @Test fun verifiedV1MigrationReadsOnlyAndRecoversCommittedPending() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val snapshot = SnapshotFixture.snapshot()
            val next = snapshot.copy(revision = UUID.randomUUID().toString(), parentRevision = snapshot.revision, generation = 2,
                state = snapshot.state.copy(entries = snapshot.state.entries.map { it.copy(location = "published") }))
            val snapshots = listOf(snapshot, next).associate { "snapshot-${it.revision}.dps" to ScheduleSnapshotCodec.encode(it) }
            val nextBytes = ScheduleSnapshotCodec.encode(next)
            val head = SharedScheduleHead(next.datasetId, next.revision, digest(nextBytes)).bytes()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    assertEquals("GET", request.method)
                    val bytes = if (request.path!!.endsWith("dormpanel-head-v1")) head else snapshots[request.path!!.substringAfterLast('/')]
                    return bytes?.let { okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(it)) }
                        ?: okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                }
            }
            Device().use { d ->
                call<Unit> { d.store.applySnapshot(ScheduleSnapshotCodec.encode(snapshot), it) }
                d.store.stagePublication(nextBytes, snapshot.state)
                call<Unit> { d.store.put(snapshot.state.events.single().copy(note = "after capture"), it) }
                val account = WebDavAccount(server.url("/v1/").toString(), "u", "p")
                val baseline = V1ScheduleMigration.prepare(d.store, SharedScheduleTransport(), account, account.baseUrl,
                    ScheduleSyncEngine.Legacy(snapshot.datasetId, snapshot.revision, 1))
                assertEquals(next.revision, baseline.revision); assertNull(d.store.syncFrame().pending)
                val recovery = Room.databaseBuilder(context, ScheduleDatabase::class.java, "${d.name}.db").build()
                try {
                    val original = ScheduleSnapshotCodec.decode(requireNotNull(recovery.dao().syncRecord(100)).payload)
                    assertEquals("after capture", original.state.events.single().note)
                    assertNotNull(recovery.dao().syncRecord(114)); assertNotNull(recovery.dao().syncRecord(115))
                } finally { recovery.close() }
                assertEquals("after capture", d.store.syncFrame().local.events.single().note)
                assertEquals("published", d.store.syncFrame().local.entries.single().location)
                d.store.v2Migrate(baseline); d.reopen()
                assertEquals(baseline, d.store.syncFrame().baseline)
            }
        }
    }
    @Test fun transactionFailureRollsBackCoursesCatalogAndFrontier() {
        val root = root()
        val name = "pr35-rollback-${UUID.randomUUID()}"
        TermScheduleProfileStore.overrideNamespace = name
        val db = Room.databaseBuilder(context, ScheduleDatabase::class.java, "$name.db").build()
        var fail = false
        val executor = Executors.newSingleThreadExecutor()
        val store = RoomScheduleStore(db, executor, profiles = TermScheduleProfileStore(context), afterSnapshotRows = { if (fail) error("injected transaction failure") })
        try {
            store.v2Start(root, true)
            val next = root.copy(commitId = UUID.randomUUID().toString(), parents = listOf(root.commitId), state = root.state.copy(events = emptyList(), customProfileKeys = emptySet()))
            fail = true
            assertThrows(IllegalStateException::class.java) { store.v2Apply(ScheduleV2Plan(root, root, listOf(next))) }
            val frame = store.v2Frame()
            assertTrue(ScheduleMerge.same(root.state, frame.local))
            assertEquals(listOf(root.commitId), frame.frontier)
            assertEquals(root.state.customProfileKeys, frame.local.customProfileKeys)
        } finally {
            store.close(); executor.awaitTermination(10, TimeUnit.SECONDS)
            context.deleteDatabase("$name.db"); context.getSharedPreferences(name, 0).edit().clear().commit(); TermScheduleProfileStore.overrideNamespace = null
        }
    }
    @Test fun editBetweenCandidateReadAndStageIsNotIncludedInCapture() {
        val root = root()
        Device().use { d ->
            d.store.v2Start(root, true)
            call<Unit> { d.store.put(root.state.entries.single().copy(location = "candidate"), it) }
            val frame = d.store.v2Frame()
            val bytes = ScheduleCommitCodec.encode(ScheduleCommit(root.datasetId, UUID.randomUUID().toString(), frame.writerId, frame.frontier, frame.local))
            call<Unit> { d.store.put(root.state.entries.single().copy(location = "new edit before stage"), it) }
            d.store.v2Stage(bytes); d.reopen(); d.store.v2Receipt(bytes)
            assertEquals("new edit before stage", d.store.v2Frame().local.entries.single().location)
            assertEquals("candidate", d.store.v2Frame().baseline!!.state.entries.single().location)
            assertTrue(call<ScheduleState>(d.store::load).sync!!.pending)
        }
    }
    @Test fun unchangedThirdRoomCanExplicitlyKeepLocalAgainstTwoRemoteBranches() {
        val root = root(); val remote = Remote().apply { files[root.commitId] = ScheduleCommitCodec.encode(root) }
        listOf("remote-A", "remote-B").forEach { room ->
            val child = root.copy(commitId = UUID.randomUUID().toString(), parents = listOf(root.commitId),
                state = root.state.copy(entries = root.state.entries.map { it.copy(location = room) }))
            remote.files[child.commitId] = ScheduleCommitCodec.encode(child)
        }
        Device().use { d ->
            d.store.v2Start(root, true)
            assertFalse(AppendOnlyScheduleSync(d.store, remote).synchronize().conflicts.isEmpty())
            d.reopen(); d.store.v2Resolve(ScheduleMerge.Choice.LOCAL)
            AppendOnlyScheduleSync(d.store, remote).synchronize()
            assertEquals(root.state.entries.single().location, d.store.v2Frame().local.entries.single().location)
            assertEquals(4, remote.files.size)
        }
    }
    @Test fun staleConflictConfirmationRefusesNewUnseenRemoteProposal() {
        val root = root(); val remote = Remote().apply { files[root.commitId] = ScheduleCommitCodec.encode(root) }
        Device().use { d ->
            d.store.v2Start(root, true)
            call<Unit> { d.store.put(root.state.entries.single().copy(location = "local"), it) }
            val a = root.copy(commitId = UUID.randomUUID().toString(), parents = listOf(root.commitId),
                state = root.state.copy(entries = root.state.entries.map { it.copy(location = "remote-A") }))
            remote.files[a.commitId] = ScheduleCommitCodec.encode(a)
            AppendOnlyScheduleSync(d.store, remote).synchronize()
            val token = requireNotNull(d.store.v2Frame().conflict).token()
            val b = a.copy(commitId = UUID.randomUUID().toString(), parents = listOf(a.commitId), state = a.state.copy(entries = a.state.entries.map { it.copy(location = "remote-B") }))
            remote.files[b.commitId] = ScheduleCommitCodec.encode(b)
            AppendOnlyScheduleSync(d.store, remote).synchronize()
            d.reopen()
            assertThrows(IllegalStateException::class.java) { d.store.v2Resolve(ScheduleMerge.Choice.REMOTE, token) }
            assertEquals("local", d.store.v2Frame().local.entries.single().location)
            assertNotNull(d.store.v2Frame().conflict); assertNull(d.store.v2Frame().pending)
        }
    }
    @Test fun pendingPublicationCannotMoveToAnotherAccountOrDirectoryAfterRestart() {
        val root = root()
        Device().use { d ->
            d.store.v2CheckTarget("a".repeat(64)); d.store.v2Start(root.copy(state = d.store.v2Frame().local), false)
            d.reopen()
            assertThrows(IllegalStateException::class.java) { d.store.v2CheckTarget("b".repeat(64)) }
            assertNotNull(d.store.v2Frame().pending)
            d.store.v2CheckTarget("a".repeat(64))
        }
    }
    @Test fun nonemptyPhoneRefusesBindingAndFailedDownloadKeepsRows() {
        val root = root()
        Device().use { d ->
            call<Unit> { d.store.put(root.state.entries.single(), it) }
            assertThrows(IllegalStateException::class.java) { d.store.v2Start(root, true) }
            assertEquals(root.state.entries, d.store.v2Frame().local.entries)
            assertNull(d.store.v2Frame().baseline)
        }
    }
}
