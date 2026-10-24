package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.Base64

class SyncV3InitializationRecoveryTest {
    @Test fun bindingAndCandidatesCommitTogetherAndRetryKeepsIdentity() {
        for (phase in listOf("before-commit", "after-commit")) {
            val directory = Files.createTempDirectory("pr55-create-").toFile()
            val writer = UUID.randomUUID().toString()
            var journal = V3Journal(directory)
            val objects = linkedMapOf<String, ByteArray>()
            val remote = object : V3ObjectStore, V3PublicationStore {
                override fun read(path: String) = objects[path]
                override fun putOnce(path: String, raw: ByteArray) { check(path !in objects); objects[path] = raw.copyOf() }
                override fun prepare(scope: String) {}
                override fun list(scope: String): List<String> = emptyList()
                override fun createForIsolatedTest(path: String, bytes: ByteArray, beforeWriteIntent: () -> Unit) = error("test primitive")
            }
            var expected: String? = null
            journal.meta.fault = { at ->
                val frame = journal.meta.get("state", "frame")
                if (at == phase && frame?.has("binding") == true) {
                    expected = frame.getJSONObject("binding").getString("datasetId")
                    val candidates = frame.getJSONArray("initialization")
                    assertEquals(2, candidates.length())
                    repeat(2) { assertEquals(expected, SyncV3Codec.decode(Base64.getDecoder().decode(candidates.getString(it))).datasetId) }
                    error("injected first binding")
                }
            }
            assertThrows(Exception::class.java) { V3Engine(journal, writer).initialize(remote) }
            assertTrue(objects.isEmpty()); journal.close(); journal = V3Journal(directory)
            if (phase == "before-commit") assertFalse(journal.snapshot().has("binding"))
            else assertEquals(expected, journal.snapshot().getJSONObject("binding").getString("datasetId"))
            val binding = V3Engine(journal, writer).initialize(remote)
            if (phase == "after-commit") assertEquals(expected, binding.getString("datasetId"))
            assertEquals(2, objects.size)
            assertEquals(1, objects.values.map { SyncV3Codec.decode(it).datasetId }.toSet().size)
            assertEquals(0, journal.snapshot().getJSONArray("initialization").length()); journal.close()
        }
    }
}
