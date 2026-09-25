package com.dormpanel.app.schedule

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WebDavAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun isolate() {
        WebDavSettings.overrideNamespace = "webdav_protocol_test"
        context.getSharedPreferences("webdav_protocol_test_settings", 0).edit().clear().commit()
        context.getSharedPreferences("webdav_protocol_test_credentials", 0).edit().clear().commit()
    }
    @After fun restore() {
        WebDavSettings(context).clearAccount()
        WebDavSettings.overrideNamespace = null
    }
    @Test fun keystoreEncryptedPasswordAndRestartRecovery() {
        val settings = WebDavSettings(context)
        settings.saveAccount(WebDavAccount("https://example.test/dav/", "alice", "private-password"))
        assertEquals("private-password", WebDavSettings(context).account()?.password)
        val ciphertext = context.getSharedPreferences("webdav_protocol_test_credentials", 0)
            .getString("payload", "")!!
        assertFalse(ciphertext.contains("private-password"))
        val binding = WebDavBinding("source", WebDavMode.FILE, "https://example.test/dav/timetable.ics", true)
        settings.saveBindings(listOf(binding))
        assertEquals(binding, WebDavSettings(context).bindings().single())
        settings.saveBindings(emptyList())
        assertEquals("private-password", WebDavSettings(context).account()?.password)
        settings.clearAccount()
        assertNull(WebDavSettings(context).account())
    }
    @Test fun androidXmlParserAndAuthenticatedPropfind() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(207).setBody(
                "<D:multistatus xmlns:D='DAV:'><D:response><D:href>/dav/a.ics</D:href>" +
                    "<D:propstat><D:status>HTTP/1.1 200 OK</D:status><D:prop>" +
                    "<D:displayname>a.ics</D:displayname><D:resourcetype/>" +
                    "</D:prop></D:propstat></D:response></D:multistatus>"))
            val account = WebDavAccount(server.url("/dav/").toString(), "alice", "private-password")
            assertEquals("a.ics", WebDavClient().list(account).single().name)
            val request = server.takeRequest()
            assertEquals("PROPFIND", request.method)
            assertNotNull(request.getHeader("Authorization"))
        }
    }
}
