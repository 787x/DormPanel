package com.dormpanel.app.schedule

/** Pure UI navigation policy; transport still owns bounded XML and authentication. */
object WebDavFolders {
    fun checked(client: WebDavClient, root: String, candidate: String): String {
        val base = client.url(root); val target = client.url(candidate)
        require(base.scheme == target.scheme && base.host == target.host && base.port == target.port) { "目录不属于当前服务器" }
        val prefix = base.pathSegments.filter { it.isNotEmpty() }
        val parts = target.pathSegments.filter { it.isNotEmpty() }
        require(parts.take(prefix.size) == prefix && parts.none { it.contains('/') || it.contains('\\') || it == "." || it == ".." || Regex("%2[fFeE]|%5[cC]", RegexOption.IGNORE_CASE).containsMatchIn(it) }) { "目录超出可浏览范围" }
        return target.newBuilder().apply { if (!target.encodedPath.endsWith('/')) addPathSegment("") }.build().toString()
    }
    fun children(client: WebDavClient, root: String, current: String, items: List<WebDavItem>): List<WebDavItem> {
        val here = checked(client,root,current)
        return items.map { it.copy(url = checked(client,root,it.url), name = client.url(it.url).pathSegments.lastOrNull { part -> part.isNotEmpty() }.orEmpty()) }.filter {
            it.folder && it.url != here && client.url(it.url).pathSegments.filter(String::isNotEmpty).size == client.url(here).pathSegments.filter(String::isNotEmpty).size + 1 &&
                client.url(it.url).pathSegments.filter(String::isNotEmpty).takeLast(1) != listOf("dormpanel-sync-v2") && it.url.startsWith(here)
        }.sortedWith(compareBy<WebDavItem> { it.name }.thenBy { it.url })
    }
}
