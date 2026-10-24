package com.dormpanel.app.schedule

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses only an instrumentation-private random directory, never schedule.db/preferences. */
class SyncV3CacheAndroidTest {
    @Test fun durableBlobReceiptReopenAndMissingBlobRetainsIdentity() {
        val parent = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "pr53-${UUID.randomUUID()}")
        val directory = File(parent, "sync-v3-cache-v1")
        val dataset = UUID.randomUUID().toString()
        val descriptor = V3Object(1, dataset, UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val objectValue = V3Object(2, dataset, UUID.randomUUID().toString(), descriptor.writerId,
            UUID.randomUUID().toString(), 1, SyncV3Codec.hash(SyncV3Codec.encode(descriptor)),
            state = ScheduleState(events = listOf(CalendarEvent("event", "缓存恢复", 1000, 2000, ""))))
        val raw = SyncV3Codec.encode(objectValue); val path = V3Paths.path(objectValue)
        try {
            val first = V3ObjectCache(directory, V3Budget()) { error("injected after fsync blob") }
            assertThrows(Exception::class.java) { first.put(path, raw) }
            val reopened = V3ObjectCache(directory, V3Budget())
            assertArrayEquals(raw, reopened.get(path)); assertEquals(1, reopened.index().size)
            assertTrue(File(directory, "${SyncV3Codec.hash(raw)}.dpv").delete())
            val missing = V3ObjectCache(directory, V3Budget()); assertNull(missing.get(path))
            assertThrows(Exception::class.java) { missing.put(path, SyncV3Codec.encode(objectValue.copy(writerId = dataset))) }
            missing.put(path, raw); assertArrayEquals(raw, V3ObjectCache(directory, V3Budget()).get(path))
        } finally { parent.deleteRecursively() }
    }
}
