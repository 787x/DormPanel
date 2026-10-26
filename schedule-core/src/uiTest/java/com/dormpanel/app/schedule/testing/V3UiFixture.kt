package com.dormpanel.app.schedule.testing

import android.content.Context
import androidx.room.Room
import com.dormpanel.app.schedule.*
import okhttp3.mockwebserver.*
import okio.Buffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Shared by the two real Activity test APKs. All DBs and preferences are random. */
class V3UiFixture(private val context: Context, private val initialReadFailure: Boolean = false) : AutoCloseable {
    val server = MockWebServer()
    val files = linkedMapOf<String, ByteArray>()
    val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val databases = mutableListOf<ScheduleDatabase>()
    private var initialRepair: (() -> Unit)? = null
    fun repairInitialReadFailure() { requireNotNull(initialRepair).invoke(); initialRepair = null }
    fun checkpoints() = databases.last().dao().syncRecords().associate { it.id to digest(it.payload) }
    fun entries() = databases.last().dao().entries()
    /** Only a synthetic checkpoint is damaged; restoring it never deletes course rows. */
    fun injectReadFailure(): () -> Unit {
        val dao = databases.last().dao(); val previous = dao.syncRecord(1)
        dao.checkpoint(ScheduleSyncCheckpoint(1, byteArrayOf(1)))
        return { if (previous == null) dao.deleteSyncRecord(1) else dao.checkpoint(previous) }
    }
    private val directories = linkedSetOf("/")
    private val executors = mutableListOf<ExecutorService>()
    val account get() = WebDavAccount(server.url("/").toString(), "test", "test")
    fun published(title: String): Boolean = synchronized(files) {
        files.values.any { bytes -> runCatching { SyncV3Codec.decode(bytes) }.getOrNull()?.let { it.type == 3 && it.state?.entries?.any { row -> row.title == title } == true } == true }
    }
    init {
        check(context.packageName.endsWith(".testbed")) { "UI verification requires isolated testbed applicationId" }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(files) {
                requests += "${request.method} ${request.path}"
                val path = request.path!!.substringBefore('?')
                when (request.method) {
                    "MKCOL" -> { directories += path; MockResponse().setResponseCode(201) }
                    "PUT" -> { check(path !in files); files[path] = request.body.readByteArray(); MockResponse().setResponseCode(201) }
                    "GET" -> files[path]?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                    "PROPFIND" -> {
                        if (path !in directories) return@synchronized MockResponse().setResponseCode(404)
                        val children = (directories + files.keys).filter { it.startsWith(path) && it != path && '/' !in it.removePrefix(path).trimEnd('/') }
                        val xml = "<d:multistatus xmlns:d=\"DAV:\">" + (listOf(path) + children).distinct().joinToString("") { child ->
                            "<d:response><d:href>$child</d:href><d:propstat><d:prop><d:resourcetype>" +
                                (if (child in directories) "<d:collection/>" else "") + "</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                        } + "</d:multistatus>"
                        MockResponse().setResponseCode(207).setBody(xml)
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
        server.start(); nextWorkspace()
    }
    fun nextWorkspace() {
        executors.filter { it.isShutdown }.forEach { check(it.awaitTermination(15, TimeUnit.SECONDS)) }
        val namespace = "pr55-ui-${UUID.randomUUID()}"
        SharedScheduleSettings.overrideNamespace = namespace; WebDavSettings.overrideNamespace = namespace; TermScheduleProfileStore.overrideNamespace = namespace
        WebDavSettings(context).saveAccount(account); SharedScheduleSettings(context).folder = account.baseUrl
        ScheduleStores.overrideFactory = { c ->
            val executor = Executors.newSingleThreadExecutor().also { executors += it }
            val db = Room.databaseBuilder(c, ScheduleDatabase::class.java, "$namespace.db").build().also { databases += it }
            RoomScheduleStore(db, executor, profiles = TermScheduleProfileStore(c)).also { store ->
                if (initialReadFailure) {
                    val root = ScheduleCommit(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), emptyList(),
                        ScheduleState(entries = listOf(TimetableEntry("stable-first", "首次读取后可见课程", 1, 480, 530, ""))))
                    store.v2Start(root, true)
                    directories += "/dormpanel-sync-v2/"
                    files["/dormpanel-sync-v2/commit-${root.commitId}.dpc"] = ScheduleCommitCodec.encode(root)
                    val previous = executor.submit<ScheduleSyncCheckpoint> {
                        val row = requireNotNull(db.dao().syncRecord(21))
                        db.dao().checkpoint(ScheduleSyncCheckpoint(21, byteArrayOf(1))); row
                    }.get()
                    initialRepair = { db.dao().checkpoint(previous) }
                }
            }
        }
    }
    override fun close() {
        executors.forEach { check(it.isShutdown); check(it.awaitTermination(15, TimeUnit.SECONDS)) }
        ScheduleStores.overrideFactory = null; SharedScheduleSettings.overrideNamespace = null; WebDavSettings.overrideNamespace = null; TermScheduleProfileStore.overrideNamespace = null
        server.shutdown()
    }
}
