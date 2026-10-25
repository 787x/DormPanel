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
class V3UiFixture(private val context: Context) : AutoCloseable {
    val server = MockWebServer()
    val files = linkedMapOf<String, ByteArray>()
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
            RoomScheduleStore(Room.databaseBuilder(c, ScheduleDatabase::class.java, "$namespace.db").build(), executor, profiles = TermScheduleProfileStore(c))
        }
    }
    override fun close() {
        executors.forEach { check(it.isShutdown); check(it.awaitTermination(15, TimeUnit.SECONDS)) }
        ScheduleStores.overrideFactory = null; SharedScheduleSettings.overrideNamespace = null; WebDavSettings.overrideNamespace = null; TermScheduleProfileStore.overrideNamespace = null
        server.shutdown()
    }
}
