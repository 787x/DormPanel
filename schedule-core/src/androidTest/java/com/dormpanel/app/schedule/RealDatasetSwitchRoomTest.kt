package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Explicit isolated real-WebDAV fixture; two Room clients on one emulator, never two physical devices. */
class RealDatasetSwitchRoomTest {
    @Test fun isolatedActualWebDav() {
        val args = InstrumentationRegistry.getArguments()
        val oldUrl = args.getString("migrationOldUrl") ?: return
        val newUrl = requireNotNull(args.getString("migrationNewUrl")); val targetId = requireNotNull(args.getString("migrationDataset"))
        check(oldUrl.contains("/pr47-isolated-") && newUrl.contains("/pr47-isolated-")) { "Only an explicitly isolated fixture is accepted" }
        val phase = args.getString("migrationNetworkPhase") ?: "migrate"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = WebDavAccount(requireNotNull(args.getString("migrationServer")), requireNotNull(args.getString("migrationUsername")), requireNotNull(args.getString("migrationPassword")))
        val old = WebDavCommitStore(account, oldUrl + "dormpanel-sync-v2/")
        val target = WebDavCommitStore(account, newUrl + "dormpanel-sync-v2/")
        val expectedOld = ScheduleV2Binding.inspect(old)
        val selected = args.getString("migrationDeviceIndex")?.toInt()?.also { require(it in 0..1) }
        for (device in if (selected == null) 0..1 else selected..selected) {
            val name = "pr47-real-network-room-$device"; TermScheduleProfileStore.overrideNamespace = name; SharedScheduleSettings.overrideNamespace = name
            val profiles = TermScheduleProfileStore(context); val settings = SharedScheduleSettings(context)
            val executor = Executors.newSingleThreadExecutor(); val db = Room.databaseBuilder(context, ScheduleDatabase::class.java, "$name.db").build()
            val store = RoomScheduleStore(db, executor, profiles = profiles)
            try {
                if (phase == "migrate") {
                    check(store.v2Frame().baseline == null) { "Isolated fixture already exists" }
                    val writer = store.v2Frame().writerId
                    store.v2Bind(expectedOld); settings.folder = oldUrl
                    store.v2BeginDatasetInspection(oldUrl, newUrl, targetId)
                    val (source, next) = ScheduleDatasetSwitch.inspect(store, old, target)
                    assertEquals(targetId, next.head.datasetId); assertEquals(1, next.witness.size)
                    val j = ScheduleDatasetMigration(UUID.randomUUID().toString(), "prepared", oldUrl, newUrl, source, next, writer)
                    store.v2PrepareMigration(j); ScheduleDatasetSwitch.inspect(store, old, target, j)
                    store.v2CommitMigration(j.id); settings.folder = newUrl
                    ScheduleV2Binding.inspect(old, source); ScheduleV2Binding.inspect(target, next)
                    store.v2FinishMigration(j.id)
                    assertEquals(writer, store.v2Frame().writerId); assertTrue(ScheduleMerge.same(source.snapshot.state, store.v2Frame().local))
                    assertEquals(next.witness, store.v2Witness())
                    assertTrue(AppendOnlyScheduleSync(store, target).synchronize().conflicts.isEmpty())
                } else if (phase == "edit") {
                    val writer = store.v2Frame().writerId
                    assertEquals(targetId, store.v2Frame().baseline!!.datasetId)
                    assertTrue(AppendOnlyScheduleSync(store, target).synchronize().conflicts.isEmpty())
                    val saved = CompletableFuture<Result<Unit>>()
                    store.put(TimetableEntry("pr47-android-$device", "隔离 Android Room $device", device + 1, 600, 650, "非实机三端")) { saved.complete(it) }
                    saved.get(15, TimeUnit.SECONDS).getOrThrow()
                    assertTrue(AppendOnlyScheduleSync(store, target).synchronize().conflicts.isEmpty()); assertEquals(writer, store.v2Frame().writerId)
                    val state = store.v2Frame().local
                    assertEquals(expectedOld.snapshot.state.sources, state.sources); assertEquals(expectedOld.snapshot.state.profiles, state.profiles)
                    assertEquals(expectedOld.snapshot.state.customProfileKeys, state.customProfileKeys)
                } else if (phase == "receive") {
                    assertTrue(AppendOnlyScheduleSync(store, target).synchronize().conflicts.isEmpty())
                    val ids = store.v2Frame().local.entries.map { it.id }
                    assertTrue(ids.containsAll(listOf("pr47-android-0", "pr47-android-1", "pr47-fpk-after")))
                } else error("Unknown isolated network phase")
            } finally {
                store.close(); executor.awaitTermination(10, TimeUnit.SECONDS)
                if (phase == "receive") { context.deleteDatabase("$name.db"); context.getSharedPreferences(name, 0).edit().clear().commit() }
                TermScheduleProfileStore.overrideNamespace = null; SharedScheduleSettings.overrideNamespace = null
            }
        }
        assertEquals(expectedOld.witness, ScheduleV2Binding.inspect(old, expectedOld).witness)
    }
}
