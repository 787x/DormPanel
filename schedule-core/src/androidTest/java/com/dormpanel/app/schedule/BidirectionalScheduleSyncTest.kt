package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

class BidirectionalScheduleSyncTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun <T> call(block: ((Result<T>) -> Unit) -> Unit): T {
        val future = CompletableFuture<Result<T>>()
        block { future.complete(it) }; return future.get(20, TimeUnit.SECONDS).getOrThrow()
    }
    private inner class Device : AutoCloseable {
        val name = "pr32-${UUID.randomUUID()}"
        val profiles: TermScheduleProfileStore
        private var executor = Executors.newSingleThreadExecutor()
        var store: RoomScheduleStore; private set
        init {
            TermScheduleProfileStore.overrideNamespace = name
            profiles = TermScheduleProfileStore(context)
            store = open()
        }
        private fun open() = RoomScheduleStore(Room.databaseBuilder(context, ScheduleDatabase::class.java, "$name.db")
            .addMigrations(ScheduleDatabase.MIGRATION_1_2, ScheduleDatabase.MIGRATION_2_3, ScheduleDatabase.MIGRATION_3_4).build(), executor, profiles = profiles)
        fun bind(snapshot: ScheduleSnapshot) = call<Unit> { store.applySnapshot(ScheduleSnapshotCodec.encode(snapshot), it) }
        fun room(location: String) = call<Unit> { store.put(store.syncFrame().local.entries.single().copy(location = location), it) }
        fun reopen() { store.close(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); executor = Executors.newSingleThreadExecutor(); store = open() }
        override fun close() {
            store.close(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); context.deleteDatabase("$name.db")
            context.getSharedPreferences(name, 0).edit().clear().commit(); TermScheduleProfileStore.overrideNamespace = null
        }
    }
    private class Dav : Dispatcher() {
        val files = ConcurrentHashMap<String, ByteArray>()
        var raceEtag: String? = null
        var barrier: CountDownLatch? = null
        var oldHeadSuccesses = 0
        var rejectHead = false
        var afterHead: (() -> Unit)? = null
        override fun dispatch(request: RecordedRequest): MockResponse {
            val key = request.path!!
            if (request.method == "PUT" && key.endsWith("dormpanel-head-v1") && request.getHeader("If-Match") == raceEtag && raceEtag != null) {
                barrier!!.countDown(); check(barrier!!.await(15, TimeUnit.SECONDS))
            }
            return synchronized(files) {
                val old = files[key]; val etag = old?.let { "\"${digest(it)}\"" }
                if (request.method == "GET") return@synchronized if (old == null) MockResponse().setResponseCode(404)
                    else MockResponse().setBody(okio.Buffer().write(old)).addHeader("ETag", etag!!)
                if (request.method != "PUT") return@synchronized MockResponse().setResponseCode(405)
                if (rejectHead && key.endsWith("dormpanel-head-v1")) return@synchronized MockResponse().setResponseCode(412)
                if (request.getHeader("If-None-Match") == "*" && old != null || request.getHeader("If-Match")?.let { it != etag } == true)
                    return@synchronized MockResponse().setResponseCode(412)
                files[key] = request.body.readByteArray()
                if (key.endsWith("dormpanel-head-v1")) {
                    if (raceEtag != null && request.getHeader("If-Match") == raceEtag) oldHeadSuccesses++
                    afterHead?.also { afterHead = null }?.invoke()
                }
                MockResponse().setResponseCode(if (old == null) 201 else 204)
            }
        }
    }
    private fun server(test: (SharedScheduleTransport, WebDavAccount, Dav) -> Unit) = MockWebServer().use { server ->
        val dav = Dav(); server.dispatcher = dav
        test(SharedScheduleTransport(), WebDavAccount(server.url("/shared/").toString(), "user", "secret"), dav)
    }
    private fun engine(device: Device, transport: SharedScheduleTransport, account: WebDavAccount) =
        ScheduleSyncEngine(device.store, transport, account, account.baseUrl)
    private fun seed(t: SharedScheduleTransport, a: WebDavAccount): ScheduleSnapshot {
        val s = SnapshotFixture.snapshot(); t.publish(a, a.baseUrl, ScheduleSnapshotCodec.encode(s), null, null); return s
    }
    @Test fun twoRoomDatabasesMultiRoundMergeDeleteResetCatalogAndNoOp() = server { t, a, _ ->
        val initial = seed(t, a)
        Device().use { x -> Device().use { phone ->
            x.bind(initial); phone.bind(initial)
            x.room("A301")
            call<Unit> { phone.store.put(initial.state.events.single().copy(note = "phone offline"), it) }
            assertTrue(x.store.syncFrame().dirty); assertTrue(phone.store.syncFrame().dirty)
            assertTrue(engine(x, t, a).synchronize().conflicts.isEmpty())
            assertTrue(engine(phone, t, a).synchronize().conflicts.isEmpty())
            engine(x, t, a).synchronize()
            assertTrue(ScheduleMerge.same(x.store.syncFrame().local, phone.store.syncFrame().local))
            assertEquals("A301", x.store.syncFrame().local.entries.single().location)
            assertEquals("phone offline", x.store.syncFrame().local.events.single().note)
            val previous = t.inspect(a, a.baseUrl).head.revision
            repeat(2) { engine(x, t, a).synchronize(); engine(phone, t, a).synchronize() }
            assertEquals(previous, t.inspect(a, a.baseUrl).head.revision)
            call<Unit> { phone.store.deleteEntry("local", it) }
            val weekly = initial.state.classOverrides.first { it.anchorDate != null }
            call<Unit> { x.store.putClassOverride(null, weekly.key, it) }
            phone.profiles.put(BuiltInProfiles.term2026.copy(timezone = "Asia/Tokyo"))
            phone.reopen(); assertTrue(phone.store.syncFrame().dirty)
            engine(phone, t, a).synchronize(); engine(x, t, a).synchronize(); engine(phone, t, a).synchronize()
            val final = phone.store.syncFrame().local
            assertTrue(final.entries.isEmpty()); assertTrue(final.classOverrides.none { it.key == weekly.key || it.sourceId == null })
            assertEquals("Asia/Tokyo", final.profiles.getValue(BuiltInProfiles.term2026.termKey).timezone)
            phone.profiles.remove(BuiltInProfiles.term2026.termKey)
            engine(phone, t, a).synchronize(); engine(x, t, a).synchronize()
            assertEquals(BuiltInProfiles.term2026, x.store.syncFrame().local.profiles[BuiltInProfiles.term2026.termKey])
            assertFalse(x.store.syncFrame().dirty)
        } }
    }
    @Test fun conflictSurvivesReopenAndResolutionRechecksNewRemote() = server { t, a, _ ->
        val initial = seed(t, a)
        Device().use { x -> Device().use { phone ->
            x.bind(initial); phone.bind(initial); x.room("A301"); phone.room("B201")
            engine(x, t, a).synchronize()
            assertEquals(1, engine(phone, t, a).synchronize().conflicts.size)
            val confirmed = t.inspect(a, a.baseUrl).head.revision
            phone.reopen()
            assertEquals(initial.revision, phone.store.syncFrame().baseline!!.revision)
            assertEquals(confirmed, phone.store.syncFrame().conflict!!.revision)
            assertEquals("B201", phone.store.syncFrame().local.entries.single().location)
            phone.store.resolveConflict(ScheduleMerge.Choice.LOCAL)
            x.room("C901"); engine(x, t, a).synchronize()
            assertEquals(1, engine(phone, t, a).synchronize().conflicts.size)
            phone.store.resolveConflict(ScheduleMerge.Choice.LOCAL)
            engine(phone, t, a).synchronize(); engine(x, t, a).synchronize()
            assertEquals("B201", x.store.syncFrame().local.entries.single().location)
            assertFalse(phone.store.syncFrame().dirty)
        } }
    }
    @Test fun uncertainReceiptKeepsEditsMadeDuringUploadEvenAfterAnotherPublisher() = server { t, a, _ ->
        val initial = seed(t, a)
        Device().use { x -> Device().use { phone ->
            x.bind(initial); phone.bind(initial); phone.room("A301")
            val captured = phone.store.syncFrame().local
            val candidate = initial.copy(revision = UUID.randomUUID().toString(), parentRevision = initial.revision, generation = 2, state = captured)
            val bytes = ScheduleSnapshotCodec.encode(candidate)
            phone.store.stagePublication(bytes, captured)
            t.publish(a, a.baseUrl, bytes, initial.revision, t.inspect(a, a.baseUrl).etag)
            phone.room("C901"); phone.reopen()
            call<Unit> { x.store.put(initial.state.events.single().copy(note = "other device"), it) }
            engine(x, t, a).synchronize()
            assertEquals("已同步", engine(phone, t, a).synchronize().message)
            engine(x, t, a).synchronize()
            val final = x.store.syncFrame().local
            assertEquals("C901", final.entries.single().location); assertEquals("other device", final.events.single().note)
            assertNull(phone.store.syncFrame().pending); assertFalse(phone.store.syncFrame().dirty)
        } }
    }
    @Test fun competingConditionalCommitsReMergeInsteadOfRetryingOldSnapshot() = server { t, a, dav ->
        val initial = seed(t, a)
        Device().use { x -> Device().use { phone ->
            x.bind(initial); phone.bind(initial); x.room("A301")
            call<Unit> { phone.store.put(initial.state.events.single().copy(note = "B201"), it) }
            dav.raceEtag = t.inspect(a, a.baseUrl).etag; dav.barrier = CountDownLatch(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val one = pool.submit<ScheduleSyncEngine.Outcome> { engine(x, t, a).synchronize() }
                val two = pool.submit<ScheduleSyncEngine.Outcome> { engine(phone, t, a).synchronize() }
                assertTrue(one.get(45, TimeUnit.SECONDS).conflicts.isEmpty()); assertTrue(two.get(45, TimeUnit.SECONDS).conflicts.isEmpty())
                assertEquals(1, dav.oldHeadSuccesses)
                engine(x, t, a).synchronize(); engine(phone, t, a).synchronize()
                assertEquals("A301", x.store.syncFrame().local.entries.single().location)
                assertEquals("B201", x.store.syncFrame().local.events.single().note)
                assertTrue(ScheduleMerge.same(x.store.syncFrame().local, phone.store.syncFrame().local))
            } finally { pool.shutdownNow() }
        } }
    }
    @Test fun pr31PublisherUsesHistoricalBaselineAndMissingHistoryRefusesUpgrade() = server { t, a, dav ->
        val initial = seed(t, a)
        Device().use { x ->
            // An old publisher has live rows and preferences, but no Room checkpoint.
            initial.state.profiles.values.filter { it.termKey in initial.state.customProfileKeys }.forEach(x.profiles::put)
            val db = Room.databaseBuilder(context, ScheduleDatabase::class.java, "${x.name}.db").build()
            db.runInTransaction {
                val d = db.dao(); val s = initial.state
                s.entries.forEach { d.put(TimetableRow(it.id, it.title, it.day, it.startMinute, it.endMinute, "unpublished")) }
                s.events.forEach { d.put(EventRow(it.id, it.title, it.start, it.end, it.note)) }
                s.sources.forEach(d::insertSource); d.insertOccurrences(s.imported)
                s.classOverrides.forEach(d::put); s.dayAdjustments.forEach(d::put)
            }; db.close()
            val remote = initial.copy(revision = UUID.randomUUID().toString(), parentRevision = initial.revision, generation = 2,
                state = initial.state.copy(events = initial.state.events.map { it.copy(note = "phone changed") }))
            t.publish(a, a.baseUrl, ScheduleSnapshotCodec.encode(remote), initial.revision, t.inspect(a, a.baseUrl).etag)
            val legacy = ScheduleSyncEngine.Legacy(initial.datasetId, initial.revision, 1)
            val history = dav.files.remove("/shared/snapshot-${initial.revision}.dps")!!
            assertThrows(Exception::class.java) { engine(x, t, a).synchronize(legacy = legacy) }
            assertNull(x.store.syncFrame().baseline); assertEquals("unpublished", x.store.syncFrame().local.entries.single().location)
            dav.files["/shared/snapshot-${initial.revision}.dps"] = history
            assertEquals("已同步", engine(x, t, a).synchronize(legacy = legacy).message)
            val final = x.store.syncFrame().local
            assertEquals("unpublished", final.entries.single().location); assertEquals("phone changed", final.events.single().note)
        }
    }
    @Test fun pr31PhoneCheckpointCatalogUpgradesWithoutReplacingLocalChanges() = server { t, a, _ ->
        val initial = seed(t, a)
        Device().use { phone ->
            // Reproduce exactly PR31 v4: only id=1, complete authoritative catalog and rows.
            phone.bind(initial)
            val db = Room.databaseBuilder(context, ScheduleDatabase::class.java, "${phone.name}.db").build()
            db.dao().deleteSyncRecord(2); db.close()
            phone.reopen(); phone.room("phone edit")
            assertEquals(initial.state.profiles, phone.profiles.snapshotCatalog().first)
            assertEquals("已同步", engine(phone, t, a).synchronize().message)
            assertEquals(initial.datasetId, phone.store.syncFrame().baseline!!.datasetId)
            assertEquals("phone edit", t.inspect(a, a.baseUrl).snapshot.state.entries.single().location)
        }
    }
    @Test fun sourceDeletionAgainstChildEditPersistsConflictAndResolvesWithoutOrphans() = server { t, a, _ ->
        val initial = seed(t, a)
        Device().use { x -> Device().use { phone ->
            x.bind(initial); phone.bind(initial)
            call<Unit> { x.store.deleteImport("stable-source", it) }
            val edit = initial.state.classOverrides.first { it.sourceId != null }.copy(location = "A301")
            call<Unit> { phone.store.putClassOverride(edit, edit.key, it) }
            engine(x, t, a).synchronize()
            assertEquals(1, engine(phone, t, a).synchronize().conflicts.size)
            phone.reopen(); assertEquals("A301", phone.store.syncFrame().local.classOverrides.first { it.key == edit.key }.location)
            phone.store.resolveConflict(ScheduleMerge.Choice.REMOTE)
            engine(phone, t, a).synchronize()
            val final = phone.store.syncFrame().local
            assertTrue(final.sources.isEmpty()); assertTrue(final.imported.isEmpty()); assertTrue(final.classOverrides.all { it.sourceId == null })
            assertFalse(phone.store.syncFrame().dirty)
        } }
    }
    @Test fun longPr31HistoryVerificationResumesFromDurableCursor() = server { t, a, dav ->
        Device().use { x ->
            var snapshot = ScheduleSnapshot(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, 123, x.store.syncFrame().local, 1)
            val first = snapshot
            repeat(260) { index ->
                if (index > 0) snapshot = snapshot.copy(revision = UUID.randomUUID().toString(), parentRevision = snapshot.revision, generation = snapshot.generation + 1)
                dav.files["/shared/snapshot-${snapshot.revision}.dps"] = ScheduleSnapshotCodec.encode(snapshot)
            }
            val bytes = ScheduleSnapshotCodec.encode(snapshot)
            dav.files["/shared/dormpanel-head-v1"] = SharedScheduleHead(snapshot.datasetId, snapshot.revision, digest(bytes)).bytes()
            val legacy = ScheduleSyncEngine.Legacy(first.datasetId, first.revision, 1)
            assertThrows(ScheduleHistoryPending::class.java) { engine(x, t, a).synchronize(legacy = legacy) }
            assertNotNull(x.store.historyCursor(bytes)); assertNull(x.store.syncFrame().baseline)
            x.reopen()
            assertEquals("已同步", engine(x, t, a).synchronize(legacy = legacy).message)
            assertEquals(snapshot.revision, x.store.syncFrame().baseline!!.revision)
            assertEquals(snapshot.revision, t.inspect(a, a.baseUrl).head.revision)
        }
    }
    @Test fun interruptedFirstUploadWithoutHeadKeepsDatasetAndNewLocalEdits() = server { t, a, dav ->
        Device().use { phone ->
            val entry = TimetableEntry("first", "数学", 1, 600, 645, "A301")
            call<Unit> { phone.store.put(entry, it) }
            val captured = phone.store.syncFrame().local
            val candidate = ScheduleSnapshot(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, 123, captured, 1)
            val bytes = ScheduleSnapshotCodec.encode(candidate)
            phone.store.stagePublication(bytes, captured)
            dav.files["/shared/snapshot-${candidate.revision}.dps"] = bytes // upload completed, head never committed
            call<Unit> { phone.store.put(entry.copy(location = "B201"), it) }; phone.reopen()
            assertEquals("已同步", engine(phone, t, a).synchronize().message)
            val remote = t.inspect(a, a.baseUrl).snapshot
            assertEquals(candidate.datasetId, remote.datasetId); assertEquals(1L, remote.generation)
            assertNotEquals(candidate.revision, remote.revision); assertEquals("B201", remote.state.entries.single().location)
            assertNull(phone.store.syncFrame().pending); assertFalse(phone.store.syncFrame().dirty)
        }
    }
    @Test fun folderRejectsCredentialBearingUrlsBeforeSavingPlainPreferences() {
        val name = "pr32-settings-${UUID.randomUUID()}"
        SharedScheduleSettings.overrideNamespace = name
        try {
            val settings = SharedScheduleSettings(context)
            val original = "https://example.org/shared/"
            settings.folder = original
            listOf("https://user:fake-secret@example.org/shared/", "https://example.org/shared/?token=fake-secret",
                "https://example.org/shared/#fake-secret").forEach { unsafe ->
                assertThrows(WebDavException::class.java) { settings.folder = unsafe }
                assertEquals(original, settings.folder)
            }
            assertFalse(context.getSharedPreferences(name, 0).all.values.any { it.toString().contains("fake-secret") })
        } finally {
            SharedScheduleSettings.overrideNamespace = null
            context.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }
}
