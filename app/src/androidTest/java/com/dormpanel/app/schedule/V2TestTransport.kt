package com.dormpanel.app.schedule

import com.dormpanel.app.schedule.*
import okhttp3.mockwebserver.*
import java.util.UUID

internal fun v2Listing(files: Map<String, ByteArray>, request: RecordedRequest): MockResponse? {
    if (request.method == "MKCOL") return MockResponse().setResponseCode(201)
    if (request.method != "PROPFIND") return null
    // Parent directory inspection must expose collections, like a real Depth-1 WebDAV response.
    val directories = mutableSetOf("/")
    for (path in files.keys) for (index in path.indices) if (path[index] == '/') directories += path.substring(0, index + 1)
    val rows = (directories + files.keys).filter { it.startsWith(request.path!!) && '/' !in it.removePrefix(request.path!!).trimEnd('/') }.joinToString("") {
        "<d:response><d:href>$it</d:href><d:propstat><d:prop><d:resourcetype>" +
            (if (it in directories) "<d:collection/>" else "") + "</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
    }
    return MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">$rows</d:multistatus>")
}
internal class V2TestTransport {
    private fun store(a: WebDavAccount, folder: String) = WebDavCommitStore(a, WebDavClient().url(SharedScheduleTransport().folder(a, folder))
        .newBuilder().addPathSegment("dormpanel-sync-v2").addPathSegment("").build().toString())
    fun publish(a: WebDavAccount, folder: String, bytes: ByteArray, parent: String?, etag: String?): String {
        val s = ScheduleSnapshotCodec.decode(bytes)
        store(a, folder).publish(ScheduleCommitCodec.encode(ScheduleCommit(s.datasetId, s.revision, UUID.randomUUID().toString(), listOfNotNull(parent), s.state)))
        return "unused"
    }
    fun inspect(a: WebDavAccount, folder: String): SharedScheduleRemote {
        val remote = store(a, folder)
        val first = ScheduleCommitCodec.decode(remote.read(remote.list().first())!!)
        val graph = ScheduleCommitGraph(remote, first.datasetId).load()
        val c = graph.commits.getValue(graph.tips.single())
        fun depth(id: String): Long = graph.commits.getValue(id).parents.maxOfOrNull { depth(it) + 1 } ?: 1
        val snapshot = c.snapshot().copy(parentRevision = c.parents.firstOrNull(), generation = depth(c.commitId))
        val bytes = ScheduleSnapshotCodec.encode(snapshot)
        return SharedScheduleRemote(SharedScheduleHead(c.datasetId, c.commitId, digest(bytes)), null, bytes, snapshot)
    }
}
