package com.dormpanel.app.schedule

import org.junit.Test
import org.junit.Assert.*

class WebDavFoldersTest {
    private val client = WebDavClient()
    @Test fun encodedNamesAndBoundaries() {
        val root = "https://example.org/dav/"
        assertEquals("https://example.org/dav/%E4%B8%AD%E6%96%87%20a/", WebDavFolders.checked(client,root,"https://example.org/dav/中文 a/"))
        for (bad in listOf("https://other.org/dav/", "https://example.org/dav2/", "https://example.org/dav/../", "https://example.org/dav/a%2fb/")) {
            assertThrows(IllegalArgumentException::class.java) { WebDavFolders.checked(client,root,bad) }
        }
    }
    @Test fun onlyDirectCollectionsAndNoProtocolSubfolder() {
        fun item(path: String, folder: Boolean) = WebDavItem("https://example.org/dav/$path",path,folder,null,null,null,null,null)
        val result = WebDavFolders.children(client,"https://example.org/dav/","https://example.org/dav/",listOf(item("",true),item("x/",true),item("file",false),item("x/deep/",true),item("dormpanel-sync-v2/",true)))
        assertEquals(listOf("x"), result.map { it.name })
    }
    @Test fun networkPermissionsAndXmlLimitsAreReadOnlyFailures() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            val account = WebDavAccount(server.url("/dav/").toString(), "u", "secret")
            for (response in listOf(
                okhttp3.mockwebserver.MockResponse().setResponseCode(403),
                okhttp3.mockwebserver.MockResponse().setResponseCode(207).setBody("x".repeat(WebDavClient.MAX_XML + 1)),
                okhttp3.mockwebserver.MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>https://other.org/x/</d:href></d:response></d:multistatus>"))) {
                server.enqueue(response)
                assertThrows(WebDavException::class.java) { client.list(account, strict = true) }
                assertEquals("PROPFIND", server.takeRequest().method)
            }
        }
    }
}
