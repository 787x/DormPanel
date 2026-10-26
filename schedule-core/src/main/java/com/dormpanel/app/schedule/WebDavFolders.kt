package com.dormpanel.app.schedule

/** Pure UI navigation policy; transport still owns bounded XML and authentication. */
object WebDavFolders {
    fun checked(client: WebDavClient, root: String, candidate: String): String {
        require(candidate.none { it == '\\' || it.code < 32 || it.code == 127 }) { "目录地址无效" }
        val rawPath=candidate.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        require(rawPath.split('/').none { segment ->
            val decoded=java.net.URLDecoder.decode(segment.replace("+","%2B"),"UTF-8")
            decoded in listOf(".","..") || decoded.any { it=='/' || it=='\\' || it.code<32 || it.code==127 }
        }) { "目录路径越界" }
        val base = client.url(root); val target = client.url(candidate)
        require(base.scheme == target.scheme && base.host == target.host && base.port == target.port) { "目录不属于当前服务器" }
        val prefix = base.pathSegments.filter { it.isNotEmpty() }
        val parts = target.pathSegments.filter { it.isNotEmpty() }
        require(parts.take(prefix.size) == prefix && parts.none { it.contains('/') || it.contains('\\') || it == "." || it == ".." || Regex("%2[fFeE]|%5[cC]", RegexOption.IGNORE_CASE).containsMatchIn(it) }) { "目录超出可浏览范围" }
        return target.newBuilder().apply { if (!target.encodedPath.endsWith('/')) addPathSegment("") }.build().toString()
    }
    fun entries(client: WebDavClient, root: String, current: String, items: List<WebDavItem>, files: Boolean): List<WebDavItem> {
        val here=checked(client,root,current)
        require(client.url(here).pathSegments.filter(String::isNotEmpty).size <= 64 && items.size <= 1024) { "目录层级或条目超限" }
        val direct=items.map { item ->
            val safe=checked(client,root,item.url)
            item.copy(url=if(item.folder)safe else item.url,name=client.url(safe).pathSegments.lastOrNull(String::isNotEmpty).orEmpty())
        }.filter {
            val parts=client.url(it.url).pathSegments.filter(String::isNotEmpty)
            val parent=client.url(here).pathSegments.filter(String::isNotEmpty)
            parts.size==parent.size+1 && parts.take(parent.size)==parent && parts.last() !in listOf("dormpanel-sync-v2","dormpanel-sync-v3")
        }
        return if(files)WebDavSelection.children(here,direct) else direct.filter { it.folder }.sortedBy { it.name }
    }
    fun children(client: WebDavClient, root: String, current: String, items: List<WebDavItem>): List<WebDavItem> {
        return entries(client,root,current,items,false)
    }
}
