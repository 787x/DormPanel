package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ScheduleV2BindingTest {
    private class Remote : CommitStore {
        override val bindingKey = digest("binding-test".toByteArray())
        val files = linkedMapOf<String, ByteArray>()
        var offline = false
        override fun list(): List<String> { check(!offline); return files.keys.reversed() }
        override fun read(commitId: String): ByteArray? { check(!offline); return files[commitId] }
        override fun publish(bytes: ByteArray, allowCreate: Boolean) { error("Binding must never write") }
        fun add(c: ScheduleCommit) { files[c.commitId] = ScheduleCommitCodec.encode(c) }
    }
    private fun root() = ScheduleCommitCodec.decode(javaClass.getResourceAsStream("/sync-v2/complex-root.dpc")!!.readBytes())
    private fun child(root: ScheduleCommit) = root.copy(commitId = UUID.randomUUID().toString(), parents = listOf(root.commitId))
    private fun rejects(block: () -> Unit) { assertThrows(Exception::class.java, block) }

    @Test fun portableExistingDatasetPreviewAndConfirmationAreReadOnly() {
        val root = root(); val tip = child(root)
        val remote = Remote().apply { add(root); add(tip) }
        val before = remote.files.mapValues { digest(it.value) }
        val preview = ScheduleV2Binding.inspect(remote)
        assertEquals(tip.datasetId, preview.head.datasetId)
        assertEquals(tip.commitId, preview.head.revision)
        assertEquals(2, preview.witness.size)
        assertTrue(ScheduleMerge.same(root.state, preview.snapshot.state))
        assertEquals(preview.head, ScheduleV2Binding.inspect(remote, preview).head)
        assertEquals(before, remote.files.mapValues { digest(it.value) })
    }
    @Test fun newDescendantOrConcurrentBranchRequiresFreshPreview() {
        val root = root(); val remote = Remote().apply { add(root) }
        val preview = ScheduleV2Binding.inspect(remote)
        val tip = child(root); remote.add(tip)
        rejects { ScheduleV2Binding.inspect(remote, preview) }
        val refreshed = ScheduleV2Binding.inspect(remote)
        remote.add(child(root))
        rejects { ScheduleV2Binding.inspect(remote, refreshed) }
        rejects { ScheduleV2Binding.inspect(remote) }
    }
    @Test fun missingAncestorChangedHistoryDatasetOrTargetNeverUsesOldPreview() {
        val root = root(); val tip = child(root)
        val remote = Remote().apply { add(root); add(tip) }
        val preview = ScheduleV2Binding.inspect(remote)
        remote.files.remove(root.commitId)
        rejects { ScheduleV2Binding.inspect(remote, preview) }
        remote.add(root.copy(writerId = UUID.randomUUID().toString()))
        rejects { ScheduleV2Binding.inspect(remote, preview) }
        remote.add(root); remote.add(tip.copy(datasetId = UUID.randomUUID().toString()))
        rejects { ScheduleV2Binding.inspect(remote, preview) }
        remote.add(tip)
        rejects { ScheduleV2Binding.inspect(remote, preview.copy(bindingKey = digest("other".toByteArray()))) }
        remote.offline = true
        rejects { ScheduleV2Binding.inspect(remote, preview) }
    }
    @Test fun anomaliesAreDisplayedRetainedAndRevalidated() {
        val root = root(); val remote = Remote().apply { add(root) }
        val bad = UUID.randomUUID().toString(); remote.files[bad] = byteArrayOf(1, 2)
        val preview = ScheduleV2Binding.inspect(remote)
        assertEquals(setOf(bad), preview.unverifiable)
        assertEquals(setOf(bad), ScheduleV2Binding.inspect(remote, preview).unverifiable)
        remote.files[bad] = ScheduleCommitCodec.encode(child(root).copy(commitId = bad))
        rejects { ScheduleV2Binding.inspect(remote, preview) }
        remote.files[root.commitId] = byteArrayOf(1)
        rejects { ScheduleV2Binding.inspect(remote) }
    }
    @Test fun limitsAndUnrelatedRootsAreRejected() {
        val root = root(); val remote = Remote().apply { add(root); add(root.copy(commitId = UUID.randomUUID().toString())) }
        rejects { ScheduleV2Binding.inspect(remote) }
        repeat(257) { remote.files[UUID.randomUUID().toString()] = byteArrayOf(1) }
        rejects { ScheduleV2Binding.inspect(remote) }
    }
}
