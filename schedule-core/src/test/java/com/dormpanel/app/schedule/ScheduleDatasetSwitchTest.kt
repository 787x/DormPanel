package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ScheduleDatasetSwitchTest {
    private fun root() = ScheduleCommitCodec.decode(javaClass.getResourceAsStream("/sync-v2/complex-root.dpc")!!.readBytes())
    private class Remote(label: String) : CommitStore {
        override val bindingKey = digest(label.toByteArray())
        val files = linkedMapOf<String, ByteArray>(); var omitted = emptySet<String>(); var offline = false
        override fun list(): List<String> { check(!offline); return files.keys.filter { it !in omitted } }
        override fun read(commitId: String): ByteArray? { check(!offline); return files[commitId] }
        override fun publish(bytes: ByteArray, allowCreate: Boolean) { error("Migration never publishes") }
        fun add(c: ScheduleCommit) { files[c.commitId] = ScheduleCommitCodec.encode(c) }
    }
    private class Local(var frame: ScheduleV2Frame, var hashes: Map<String, String>) : ScheduleV2Store {
        var problems = emptySet<String>(); var journal: ScheduleDatasetMigration? = null
        override fun v2Frame() = frame
        override fun v2Witness() = hashes
        override fun v2Migration() = journal
        override fun v2Unverifiable(ids: Set<String>) { problems = ids }
        override fun v2Observe(commits: Map<String, String>) { check(hashes.all { commits[it.key] == it.value }); hashes = commits }
        override fun v2Migrate(baseline: ScheduleSnapshot) = error("not used")
        override fun v2Start(root: ScheduleCommit, binding: Boolean) = error("not used")
        override fun v2Stage(bytes: ByteArray) = error("not used")
        override fun v2Attempt() = error("not used")
        override fun v2RenewPending() = error("not used")
        override fun v2Receipt(bytes: ByteArray) = error("not used")
        override fun v2Apply(plan: ScheduleV2Plan): List<ScheduleMerge.Conflict> = error("not used")
        override fun v2Resolve(choice: ScheduleMerge.Choice, expectedToken: String?) = error("not used")
    }
    @Test fun fullBusinessStateAndStableIdentitiesRetainedWithIndependentWitness() {
        val a = root(); val tip = a.copy(commitId = UUID.randomUUID().toString(), parents = listOf(a.commitId))
        val b = a.copy(datasetId = UUID.randomUUID().toString(), commitId = UUID.randomUUID().toString(), parents = emptyList())
        val old = Remote("old").apply { add(a); add(tip) }; val target = Remote("target").apply { add(b) }
        val local = Local(ScheduleV2Frame(tip.state, tip, listOf(tip.commitId), UUID.randomUUID().toString(), null, false, null), old.files.mapValues { digest(it.value) })
        val (source, next) = ScheduleDatasetSwitch.inspect(local, old, target)
        assertEquals(2, source.witness.size); assertEquals(1, next.witness.size)
        assertTrue(ScheduleMerge.same(source.snapshot.state, next.snapshot.state)); assertNotEquals(source.head.datasetId, next.head.datasetId)
        val j = ScheduleDatasetMigration(UUID.randomUUID().toString(), "prepared", "https://test/old/", "https://test/new/", source, next, local.frame.writerId)
        val reopened = ScheduleDatasetMigration.decode(j.bytes())
        assertEquals(j.id, reopened.id); assertEquals(j.writerId, reopened.writerId)
        assertEquals(j.source.witness, reopened.source.witness); assertEquals(j.target.witness, reopened.target.witness)
        assertArrayEquals(j.source.commitBytes, reopened.source.commitBytes); assertArrayEquals(j.target.commitBytes, reopened.target.commitBytes)
        local.journal = reopened
        assertThrows(IllegalStateException::class.java) { AppendOnlyScheduleSync(local, target).synchronize() }
        ScheduleDatasetSwitch.inspect(local, old, target, reopened)
        val late = tip.copy(commitId = UUID.randomUUID().toString(), parents = listOf(tip.commitId)); old.add(late)
        assertThrows(Exception::class.java) { ScheduleDatasetSwitch.inspect(local, old, target, reopened) }
    }
    @Test fun unsafeFramesAndTargetIdentityNeverSwitch() {
        val a = root(); val old = Remote("old").apply { add(a) }; val b = a.copy(datasetId = UUID.randomUUID().toString(), commitId = UUID.randomUUID().toString())
        val target = Remote("new").apply { add(b) }
        val clean = ScheduleV2Frame(a.state, a, listOf(a.commitId), UUID.randomUUID().toString(), null, false, null)
        val variants = listOf(clean.copy(pending = ScheduleCommitCodec.encode(a)), clean.copy(attempted = true), clean.copy(abandoned = mapOf(a.commitId to "a".repeat(64))), clean.copy(unverifiable = setOf(a.commitId)), clean.copy(baseline = null), clean.copy(local = a.state.copy(entries = emptyList())), clean.copy(conflict = ScheduleV2Plan(a, a, listOf(a))))
        variants.forEach { frame -> assertThrows(Exception::class.java) { ScheduleDatasetSwitch.inspect(Local(frame, emptyMap()), old, target) } }
        val local = Local(clean, old.files.mapValues { digest(it.value) })
        old.omitted = setOf(a.commitId); ScheduleDatasetSwitch.inspect(local, old, target) // Known paths must still be read.
        old.add(a.copy(writerId = UUID.randomUUID().toString()))
        assertThrows(Exception::class.java) { ScheduleDatasetSwitch.inspect(local, old, target) }
        old.add(a); target.add(b.copy(state = b.state.copy(entries = emptyList())))
        assertThrows(Exception::class.java) { ScheduleDatasetSwitch.inspect(local, old, target) }
        target.add(b); target.offline = true
        assertThrows(Exception::class.java) { ScheduleDatasetSwitch.inspect(local, old, target) }
    }
}
