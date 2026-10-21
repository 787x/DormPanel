package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AppendOnlyScheduleSyncTest {
    private fun id() = UUID.randomUUID().toString()
    private fun commit(state: ScheduleState, parents: List<String> = emptyList(), dataset: String = SnapshotFixture.dataset) =
        ScheduleCommit(dataset, id(), id(), parents.sorted(), state)
    private val initial get() = SnapshotFixture.snapshot().state
    private class Remote : CommitStore {
        val files = linkedMapOf<String, ByteArray>()
        var lost = false
        var during: (() -> Unit)? = null
        override fun list() = files.keys.reversed()
        override fun read(commitId: String) = files[commitId]
        override fun publish(bytes: ByteArray, allowCreate: Boolean) {
            val c = ScheduleCommitCodec.decode(bytes)
            files[c.commitId]?.let { if (!it.contentEquals(bytes)) throw CommitPathOccupied(); return }
            if (!allowCreate) throw CommitPathOccupied()
            files[c.commitId] = bytes
            during?.also { during = null }?.invoke()
            if (lost) { lost = false; error("response lost") }
        }
        fun add(c: ScheduleCommit) { files[c.commitId] = ScheduleCommitCodec.encode(c) }
    }
    private class Local(root: ScheduleCommit) : ScheduleV2Store {
        var state = root.state
        var baseline: ScheduleCommit? = root
        var frontier = listOf(root.commitId)
        val writer = UUID.randomUUID().toString()
        var pending: ByteArray? = null
        var capture: ScheduleState? = null
        var attempted = false
        var plan: ScheduleV2Plan? = null
        var hashes = emptyMap<String, String>()
        var unverifiable = emptySet<String>()
        override fun v2Frame() = ScheduleV2Frame(state, baseline, frontier, writer, pending, attempted, plan, unverifiable = unverifiable)
        override fun v2Attempt() { attempted = true }
        override fun v2Unverifiable(ids: Set<String>) { unverifiable = ids }
        override fun v2Observe(commits: Map<String, String>) { check(hashes.all { commits[it.key] == it.value }); hashes = commits }
        override fun v2Migrate(baseline: ScheduleSnapshot) { error("unused") }
        override fun v2Start(root: ScheduleCommit, binding: Boolean) { error("unused") }
        override fun v2Stage(bytes: ByteArray) { pending = bytes; capture = ScheduleCommitCodec.decode(bytes).state }
        override fun v2RenewPending() {
            pending = ScheduleCommitCodec.encode(ScheduleCommitCodec.decode(pending!!).copy(commitId = UUID.randomUUID().toString())); attempted = false
        }
        override fun v2Receipt(bytes: ByteArray) {
            val c = ScheduleCommitCodec.decode(bytes)
            state = ScheduleMerge.merge(capture!!, state, c.state).state
            baseline = c; frontier = listOf(c.commitId); pending = null; capture = null; attempted = false
        }
        override fun v2Apply(plan: ScheduleV2Plan): List<ScheduleMerge.Conflict> {
            val result = plan.merge(state)
            if (result.conflicts.isNotEmpty()) this.plan = plan else {
                this.plan = null; state = result.state; baseline = plan.branches.first().copy(state = plan.remoteState()); frontier = plan.branches.map { it.commitId }
            }
            return result.conflicts
        }
        override fun v2Resolve(choice: ScheduleMerge.Choice, expectedToken: String?) {
            val p = plan!!; state = p.merge(state, choice).state
            v2Stage(ScheduleCommitCodec.encode(ScheduleCommit(p.base.datasetId, UUID.randomUUID().toString(), writer, p.branches.map { it.commitId }.sorted(), state)))
            plan = null
        }
    }
    @Test fun independentPythonVectorsAreByteExact() {
        fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/sync-v2/$name.dpc")).use { it.readBytes() }
        listOf("empty", "complex-root", "left", "right", "multi-parent").forEach { name ->
            val b = bytes(name); val c = ScheduleCommitCodec.decode(b)
            assertArrayEquals(b, ScheduleCommitCodec.encode(c))
            if (name == "multi-parent") assertEquals(2, c.parents.size)
            if (name == "complex-root") {
                assertEquals("", c.state.classOverrides.single().location)
                assertNull(c.state.classOverrides.single().title)
                assertEquals("备注😀", c.state.events.single().note)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { ScheduleCommitCodec.decode(bytes("bad-checksum")) }
    }
    @Test fun canonicalIntegrityAndUnknownSchema() {
        val c = commit(initial)
        val bytes = ScheduleCommitCodec.encode(c)
        assertTrue(ScheduleMerge.same(c.state, ScheduleCommitCodec.decode(bytes).state))
        assertArrayEquals(bytes, ScheduleCommitCodec.encode(c.copy(state = initial.copy(profiles = initial.profiles.toList().reversed().toMap(), classOverrides = initial.classOverrides.reversed()))))
        val future = bytes.copyOf()
        java.nio.ByteBuffer.wrap(future).putInt(4, 3)
        val hash = java.security.MessageDigest.getInstance("SHA-256").apply { update(future, 0, future.size - 32) }.digest()
        hash.copyInto(future, future.size - 32)
        assertThrows(IllegalArgumentException::class.java) { ScheduleCommitCodec.decode(future) }
        val unsupported = Remote().apply { files[c.commitId] = future }
        assertThrows(IllegalArgumentException::class.java) { ScheduleCommitGraph(unsupported, c.datasetId).load() }
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { ScheduleCommitCodec.decode(bytes) }
    }
    @Test fun localCatalogOrderingDoesNotCreateMeaninglessCommits() {
        val root = ScheduleCommitCodec.decode(ScheduleCommitCodec.encode(commit(initial)))
        val remote = Remote().apply { add(root) }; val local = Local(root)
        local.state = local.state.copy(profiles = local.state.profiles.mapValues { (_, p) ->
            p.copy(phases = p.phases.reversed().map { it.copy(periods = it.periods.reversed()) })
        })
        repeat(3) { assertTrue(AppendOnlyScheduleSync(local, remote).synchronize().conflicts.isEmpty()) }
        assertEquals(1, remote.files.size)
    }
    @Test fun threeWritersIndependentOfflineEditsConvergeAndNoOp() {
        val root = commit(initial); val remote = Remote().apply { add(root) }
        val devices = List(3) { Local(root) }
        devices[0].state = initial.copy(entries = initial.entries.map { it.copy(location = "A301") })
        devices[1].state = initial.copy(events = initial.events.map { it.copy(note = "phone") })
        devices[2].state = initial.copy(dayAdjustments = emptyList())
        // Capture each independent branch before any device can see another upload.
        devices.forEach { d -> d.v2Stage(ScheduleCommitCodec.encode(commit(d.state, listOf(root.commitId)))) }
        repeat(4) { devices.shuffled(java.util.Random(it.toLong())).forEach { AppendOnlyScheduleSync(it, remote).synchronize() } }
        devices.forEach {
            assertEquals("A301", it.state.entries.single().location)
            assertEquals("phone", it.state.events.single().note)
            assertTrue(it.state.dayAdjustments.isEmpty())
            assertNull(it.plan)
        }
        val count = remote.files.size
        repeat(3) { devices.forEach { AppendOnlyScheduleSync(it, remote).synchronize() } }
        assertEquals(count, remote.files.size)
    }
    @Test fun simultaneousEquivalentMergesDoNotPublishForever() {
        val root = commit(initial)
        val a = commit(initial.copy(entries = initial.entries.map { it.copy(location = "A") }), listOf(root.commitId))
        val b = commit(initial.copy(events = initial.events.map { it.copy(note = "B") }), listOf(root.commitId))
        val merged = ScheduleMerge.merge(initial, a.state, b.state).state
        val m1 = commit(merged, listOf(a.commitId, b.commitId)); val m2 = commit(merged, listOf(a.commitId, b.commitId))
        val remote = Remote().apply { listOf(root, a, b, m1, m2).forEach(::add) }
        val lateRemote = Remote().apply { listOf(root, a, b, m1, m2).forEach(::add) }
        val late = Local(m1)
        late.state = late.state.copy(events = late.state.events.map { it.copy(note = "late local edit") })
        assertTrue(AppendOnlyScheduleSync(late, lateRemote).synchronize().conflicts.isEmpty())
        assertEquals("late local edit", late.state.events.single().note)
        val local = Local(root)
        repeat(4) { assertTrue(AppendOnlyScheduleSync(local, remote).synchronize().conflicts.isEmpty()) }
        assertEquals(5, remote.files.size); assertTrue(ScheduleMerge.same(merged, local.state))
        local.state = local.state.copy(events = local.state.events.map { it.copy(note = "next") })
        AppendOnlyScheduleSync(local, remote).synchronize()
        assertEquals(6, remote.files.size)
        assertEquals(setOf(m1.commitId, m2.commitId), local.baseline!!.parents.toSet())
    }
    @Test fun sameRecordConflictPersistsUntilExplicitChoice() {
        val root = commit(initial); val remote = Remote().apply { add(root) }; val local = Local(root)
        local.state = initial.copy(entries = initial.entries.map { it.copy(location = "local") })
        remote.add(commit(initial.copy(entries = initial.entries.map { it.copy(location = "remote") }), listOf(root.commitId)))
        assertFalse(AppendOnlyScheduleSync(local, remote).synchronize().conflicts.isEmpty())
        assertEquals("local", local.state.entries.single().location)
        assertNotNull(local.plan)
        local.v2Resolve(ScheduleMerge.Choice.LOCAL)
        AppendOnlyScheduleSync(local, remote).synchronize()
        assertEquals("local", local.state.entries.single().location)
        assertEquals(3, remote.files.size)
    }
    @Test fun unchangedThirdWriterCanKeepItsActualLocalValueInRemoteOnlyConflict() {
        for (choice in ScheduleMerge.Choice.values()) {
            val root = commit(initial); val remote = Remote().apply { add(root) }; val local = Local(root)
            remote.add(commit(initial.copy(entries = initial.entries.map { it.copy(location = "A") }), listOf(root.commitId)))
            remote.add(commit(initial.copy(entries = initial.entries.map { it.copy(location = "B") }), listOf(root.commitId)))
            assertFalse(AppendOnlyScheduleSync(local, remote).synchronize().conflicts.isEmpty())
            val shownRemote = local.plan!!.remoteState().entries.single().location
            assertTrue(local.plan!!.merge(local.state).conflicts.single().description.contains(shownRemote))
            local.v2Resolve(choice); AppendOnlyScheduleSync(local, remote).synchronize()
            assertEquals(if (choice == ScheduleMerge.Choice.LOCAL) initial.entries.single().location else shownRemote, local.state.entries.single().location)
        }
    }
    @Test fun excessiveConflictsStopBeforeReplacingLocalState() {
        val state = ScheduleState(entries = List(300) { TimetableEntry("class-$it", "课程 $it", 1, 600, 645, "original") })
        val root = commit(state); val remote = Remote().apply { add(root) }; val local = Local(root)
        listOf("A", "B").forEach { room -> remote.add(commit(state.copy(entries = state.entries.map { it.copy(location = room) }), listOf(root.commitId))) }
        assertThrows(IllegalStateException::class.java) { AppendOnlyScheduleSync(local, remote).synchronize() }
        assertEquals(state.entries, local.state.entries); assertEquals(3, remote.files.size)
    }
    @Test fun lostReceiptAndNewEditsReplayWithoutDuplicateCommit() {
        val root = commit(initial); val remote = Remote().apply { add(root) }; val local = Local(root)
        local.state = initial.copy(entries = initial.entries.map { it.copy(location = "capture") })
        remote.lost = true
        remote.during = { local.state = local.state.copy(events = local.state.events.map { it.copy(note = "during upload") }) }
        assertThrows(IllegalStateException::class.java) { AppendOnlyScheduleSync(local, remote).synchronize() }
        val pending = ScheduleCommitCodec.decode(local.pending!!).commitId
        assertNotNull(remote.files[pending]); assertTrue(local.attempted)
        AppendOnlyScheduleSync(local, remote).synchronize()
        assertEquals(3, remote.files.size)
        assertEquals("during upload", local.state.events.single().note)
        assertEquals("capture", local.state.entries.single().location)
    }
    @Test fun interruptedOrphanDoesNotBlockOtherWriterButNeverReportsComplete() {
        val root = commit(initial); val remote = Remote().apply { add(root) }; val a = Local(root); val b = Local(root)
        a.state = initial.copy(entries = initial.entries.map { it.copy(location = "recovered") })
        remote.lost = true
        assertThrows(IllegalStateException::class.java) { AppendOnlyScheduleSync(a, remote).synchronize() }
        val broken = ScheduleCommitCodec.decode(a.pending!!).commitId
        remote.files[broken] = byteArrayOf(1,2,3)
        assertTrue(AppendOnlyScheduleSync(a, remote).synchronize().message.contains("暂不可验证"))
        assertTrue(AppendOnlyScheduleSync(b, remote).synchronize().message.contains("暂不可验证"))
        assertEquals("recovered", b.state.entries.single().location)
        b.state = b.state.copy(events = b.state.events.map { it.copy(note = "other writer") })
        AppendOnlyScheduleSync(b, remote).synchronize(); AppendOnlyScheduleSync(a, remote).synchronize()
        assertEquals("other writer", a.state.events.single().note)
        assertArrayEquals(byteArrayOf(1,2,3), remote.files[broken])
    }
    @Test fun knownUnverifiablePathCannotBeForgottenWhenListingOmitsIt() {
        val root = commit(initial); val remote = Remote().apply { add(root) }; val local = Local(root)
        val broken = id(); remote.files[broken] = byteArrayOf(1,2)
        assertTrue(AppendOnlyScheduleSync(local, remote).synchronize().message.contains("暂不可验证"))
        val delayedListing = object : CommitStore by remote { override fun list() = remote.list().filter { it != broken } }
        assertTrue(AppendOnlyScheduleSync(local, delayedListing).synchronize().message.contains("暂不可验证"))
        assertTrue(broken in local.unverifiable)
    }
    @Test fun changedVerifiedHistoryRefusesWithoutTouchingLocal() {
        val root = commit(initial); val remote = Remote().apply { add(root) }; val local = Local(root)
        AppendOnlyScheduleSync(local, remote).synchronize()
        remote.add(root.copy(state = initial.copy(events = emptyList())))
        assertThrows(IllegalStateException::class.java) { AppendOnlyScheduleSync(local, remote).synchronize() }
        assertTrue(ScheduleMerge.same(initial, local.state))
    }
    @Test fun missingAncestorWrongDatasetAndCorruptionRefuse() {
        val root = commit(initial); val child = commit(initial, listOf(root.commitId)); val remote = Remote().apply { add(child) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleCommitGraph(remote, root.datasetId).load() }
        remote.add(root.copy(datasetId = id()))
        assertThrows(IllegalStateException::class.java) { ScheduleCommitGraph(remote, root.datasetId).load() }
        remote.add(root); remote.files[root.commitId] = byteArrayOf(0)
        assertThrows(IllegalArgumentException::class.java) { ScheduleCommitGraph(remote, root.datasetId).load() }
    }
    @Test fun divergentCrissCrossBaselinesRefuse() {
        val root = commit(initial)
        val a = commit(initial.copy(events = emptyList()), listOf(root.commitId))
        val b = commit(initial.copy(dayAdjustments = emptyList()), listOf(root.commitId))
        val m1 = commit(a.state, listOf(a.commitId, b.commitId)); val m2 = commit(b.state, listOf(a.commitId, b.commitId))
        val remote = Remote().apply { listOf(root, a, b, m1, m2).forEach(::add) }
        assertThrows(IllegalStateException::class.java) { ScheduleCommitGraph(remote, root.datasetId).load().mergeTips() }
    }
    @Test fun randomInterleavingsRetainAllIndependentEdits() {
        repeat(12) { seed ->
            val root = commit(initial); val remote = Remote().apply { add(root) }; val devices = List(3) { Local(root) }
            val random = java.util.Random(seed.toLong())
            repeat(12) { round ->
                val i = random.nextInt(3); val d = devices[i]
                d.state = when(i) {
                    0 -> d.state.copy(entries = d.state.entries.map { it.copy(location = "room-$round") })
                    1 -> d.state.copy(events = d.state.events.map { it.copy(note = "note-$round") })
                    else -> d.state.copy(dayAdjustments = d.state.dayAdjustments.map { it.copy(label = "day-$round") })
                }
                AppendOnlyScheduleSync(d, remote).synchronize()
                if (random.nextBoolean()) AppendOnlyScheduleSync(devices[random.nextInt(3)], remote).synchronize()
            }
            repeat(3) { devices.shuffled(random).forEach { assertTrue(AppendOnlyScheduleSync(it, remote).synchronize().conflicts.isEmpty()) } }
            assertTrue(devices.all { ScheduleMerge.same(devices[0].state, it.state) })
        }
    }
}
