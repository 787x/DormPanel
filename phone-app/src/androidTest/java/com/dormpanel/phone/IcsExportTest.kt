package com.dormpanel.phone
import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
class IcsExportTest {
 private val i get()=InstrumentationRegistry.getInstrumentation()
 private val name="ics-test-${UUID.randomUUID()}.db"
 @Before fun setup() {
  ScheduleStores.overrideFactory={c->RoomScheduleStore(Room.databaseBuilder(c,ScheduleDatabase::class.java,name).build())}
  TermScheduleProfileStore.overrideNamespace=name
  Intents.init()
 }
 @After fun cleanup() { Intents.release();ScheduleStores.overrideFactory=null;i.targetContext.deleteDatabase(name);TermScheduleProfileStore.overrideNamespace=null }
 private fun runExport(target:String?,success:Boolean) {
  val response=if(target==null) ActivityResult(Activity.RESULT_CANCELED,null) else ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.parse("content://${i.context.packageName}.fixtures/$target")))
  Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(response)
  launchPhoneTestActivity().use { scenario ->
   repeat(100) { var ready=false;scenario.onActivity{ready=it.source.ready};if(ready)return@repeat;Thread.sleep(50) }
   onView(withText("管理")).perform(click());onView(withText("导出 ICS")).perform(click());onView(withText("预览")).perform(click())
   Thread.sleep(500);onView(withText("保存文件")).perform(click());Thread.sleep(700)
   Intents.intended(hasAction(Intent.ACTION_CREATE_DOCUMENT))
   if(target!=null) { if(success) onView(withText("ICS 文件已保存。")).check(matches(isDisplayed())) else onView(withText(org.hamcrest.Matchers.startsWith("文件保存失败"))).check(matches(isDisplayed())) }
   scenario.onActivity { assertTrue(it.source.state.entries.isEmpty());assertTrue(it.source.state.imported.isEmpty()) }
   if(success) { val uri=Uri.parse("content://${i.context.packageName}.fixtures/export.ics"); val text=i.context.contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()};assertTrue(text.startsWith("BEGIN:VCALENDAR")) }
  }
 }
 @Test fun realSaf() {
  org.junit.Assume.assumeTrue("实际系统选择器手工验收", InstrumentationRegistry.getArguments().getString("interactiveSaf")=="true")
  launchPhoneTestActivity().use { scenario ->
   Thread.sleep(1500)
   onView(withText("管理")).perform(click());onView(withText("导出 ICS")).perform(click());onView(withText("预览")).perform(click());Thread.sleep(500);onView(withText("保存文件")).perform(click())
   Thread.sleep(25000)
   onView(withText("ICS 文件已保存。")).check(matches(isDisplayed()))
   scenario.onActivity { assertTrue(it.source.state.entries.isEmpty()) }
  }
 }
 @Test fun cancelled(){runExport(null,false)}
 @Test fun saved(){runExport("export.ics",true)}
 @Test fun writeFailure(){runExport("export-fail.ics",false)}
}
