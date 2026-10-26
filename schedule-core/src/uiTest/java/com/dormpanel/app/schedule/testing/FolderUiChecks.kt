package com.dormpanel.app.schedule.testing

import android.app.Activity
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*

/** Real dialogs, synthetic credentials and read-only server. No day-to-day preferences. */
object FolderUiChecks {
    fun installListing(f:V3UiFixture) {
        val previous=f.server.dispatcher
        f.server.dispatcher=object:Dispatcher() {
            override fun dispatch(r:RecordedRequest):MockResponse {
                val p=r.path!!
                if(r.method=="PROPFIND" && p in listOf("/","/%E4%B8%AD%E6%96%87%20a/")) {
                    val children=if(p=="/")listOf(p,"/%E4%B8%AD%E6%96%87%20a/","/dormpanel-sync-v3/","/sample.ics") else listOf(p)
                    return MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">"+children.joinToString("") {
                        "<d:response><d:href>$it</d:href><d:propstat><d:prop><d:resourcetype>"+(if(it.endsWith('/'))"<d:collection/>" else "")+"</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                    }+"</d:multistatus>")
                }
                return previous.dispatch(r)
            }
        }
    }
    private fun visible(text:String) {
        val end=System.currentTimeMillis()+15000
        while(System.currentTimeMillis()<end) {
            if(runCatching { onView(withText(text)).check(matches(isDisplayed())) }.isSuccess)return
            Thread.sleep(60)
        }
        onView(withText(text)).check(matches(isDisplayed()))
    }
    fun sharedDraft(f:V3UiFixture, onActivity:((Activity)->Unit)->Unit, settings:SharedScheduleSettings, open:()->Unit, source:(Activity)->ScheduleSource, sync:(Activity)->SharedScheduleController, label:String) {
        val initial=settings.folder
        open();onView(withText("浏览共享目录")).perform(scrollTo(),click());visible("📁 中文 a")
        onView(withText("📁 中文 a")).perform(click());visible("当前目录为空")
        screenshot("pr59-$label-folder.png")
        onView(withText("取消")).perform(click());assertEquals(initial,settings.folder)
        onView(withText("浏览共享目录")).perform(scrollTo(),click());visible("📁 中文 a")
        onView(withText("📁 中文 a")).perform(click());visible("当前目录为空")
        onView(withText("选择此文件夹")).perform(click());assertEquals(initial,settings.folder)
        onView(withText("关闭")).perform(click());assertEquals(initial,settings.folder)
        // Same navigator renders import files and keeps one dialog while navigating.
        var picked:String?=null
        onActivity { WebDavFolderDialog.show(it,f.account,importSelected={ mode,url->picked=mode.name+url }) {} }
        visible("📁 中文 a");onView(withText("📁 中文 a")).perform(click());visible("当前目录为空")
        onView(withText("上一级")).perform(click());visible("📁 中文 a");onView(withText("📄 sample.ics")).perform(scrollTo())
        screenshot("pr59-$label-import.png")
        assertNull(picked);onView(withText("📄 sample.ics")).perform(scrollTo(),click());assertTrue(picked!!.startsWith("FILE"))
        assertTrue(f.files.isEmpty())
        open();onView(withText("创建 Sync v3 数据集（保留本地课程）")).perform(scrollTo(),click());onView(withText("创建")).perform(click())
        val until=System.currentTimeMillis()+30000
        while(true) { var bound=false;onActivity { bound=source(it).syncStore?.isV3()==true && runCatching { source(it).syncStore!!.v3EndpointFolder() }.isSuccess;if(!bound && !sync(it).busy)sync(it).sync() };if(bound)break;check(System.currentTimeMillis()<until);Thread.sleep(150) }
        var binding="";onActivity { binding=source(it).syncStore!!.v3EndpointFolder() }
        val remoteCount=f.files.size
        onView(withText("安全更换 v3 访问端点 / 继续预检")).perform(scrollTo(),click())
        onView(withText("浏览候选服务器文件夹")).perform(scrollTo(),click());visible("📁 中文 a")
        onView(withText("📁 中文 a")).perform(click());visible("当前目录为空");onView(withText("选择此文件夹")).perform(click())
        screenshot("pr59-$label-endpoint.png")
        onActivity { assertEquals(binding,source(it).syncStore!!.v3EndpointFolder()) }
        assertEquals(remoteCount,f.files.size)
        onView(withText("关闭（保留预检）")).perform(click());onView(withText("关闭")).perform(click())
    }
    fun screenshot(name:String) {
        val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();val bitmap=i.uiAutomation.takeScreenshot()
        java.io.File(i.targetContext.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
}
