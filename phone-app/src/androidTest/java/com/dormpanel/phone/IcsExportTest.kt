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
  ScheduleStores.overrideFactory={c->RoomScheduleStore(Room.databaseBuilder(c,ScheduleDatabase::class.java,name).build(), profiles=TermScheduleProfileStore(c))}
  TermScheduleProfileStore.overrideNamespace=name
  Intents.init()
  i.context.contentResolver.query(Uri.parse("content://${i.context.packageName}.fixtures/export.ics"),null,null,null,null)?.close()
 }
 @After fun cleanup() { Intents.release();ScheduleStores.overrideFactory=null;i.targetContext.deleteDatabase(name);TermScheduleProfileStore.overrideNamespace=null }
 private fun runExport(target:String?,success:Boolean) {
  val response=if(target==null) ActivityResult(Activity.RESULT_CANCELED,null) else ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.parse("content://${i.context.packageName}.fixtures/$target")))
  Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(response)
  launchPhoneTestActivity().use { scenario ->
   repeat(100) { var ready=false;scenario.onActivity{ready=it.source.ready};if(ready)return@repeat;Thread.sleep(50) }
   scenario.onActivity { assertTrue(it.source.saveEntry("ICS 测试课程",it.source.clock.today().dayOfWeek.value,600,660,"教室 A")) }
   onView(withText("管理")).perform(click());onView(withText("导出 ICS")).perform(click());Thread.sleep(300);
   i.uiAutomation.takeScreenshot()?.let { bmp -> java.io.File(i.targetContext.getExternalFilesDir(null),"ics-range.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bmp.recycle() }
   onView(withText("预览")).perform(click())
   Thread.sleep(500);i.uiAutomation.takeScreenshot()?.let { bmp -> java.io.File(i.targetContext.getExternalFilesDir(null),"ics-preview.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bmp.recycle() };onView(withText("保存文件")).perform(click());Thread.sleep(700)
   Intents.intended(hasAction(Intent.ACTION_CREATE_DOCUMENT))
   if(target!=null) { if(success) onView(withText("ICS 文件已保存。")).check(matches(isDisplayed())) else onView(withText(org.hamcrest.Matchers.startsWith("文件保存失败"))).check(matches(isDisplayed())) }
   scenario.onActivity { assertEquals(1,it.source.state.entries.size);assertTrue(it.source.state.imported.isEmpty()) }
   if(success) { val uri=Uri.parse("content://${i.context.packageName}.fixtures/export.ics"); val text=i.context.contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()};assertTrue(text.startsWith("BEGIN:VCALENDAR"));assertTrue(text.contains("SUMMARY:ICS 测试课程")) }
  }
 }
 @Test fun realSaf() {
  org.junit.Assume.assumeTrue("实际系统选择器手工验收", InstrumentationRegistry.getArguments().getString("interactiveSaf")=="true")
  launchPhoneTestActivity().use { scenario ->
   Thread.sleep(1500)
   scenario.onActivity { assertTrue(it.source.saveEntry("ICS 测试课程",it.source.clock.today().dayOfWeek.value,600,660,"教室 A")) }
   onView(withText("管理")).perform(click());onView(withText("导出 ICS")).perform(click());Thread.sleep(300);
   i.uiAutomation.takeScreenshot()?.let { bmp -> java.io.File(i.targetContext.getExternalFilesDir(null),"ics-range.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bmp.recycle() }
   onView(withText("预览")).perform(click());Thread.sleep(500);onView(withText("保存文件")).perform(click())
   scenario.onActivity { it.recreate() }
   Thread.sleep(25000)
   onView(withText("ICS 文件已保存。")).check(matches(isDisplayed()))
   scenario.onActivity { assertEquals(1,it.source.state.entries.size) }
  }
 }
 @Test fun cancelled(){runExport(null,false)}
 @Test fun saved(){runExport("export.ics",true)}
 @Test fun writeFailure(){runExport("export-fail.ics",false)}
 @Test fun readFailureDoesNotBecomeEmptyExport() {
  ScheduleStores.overrideFactory={c->
   val store=RoomScheduleStore(Room.databaseBuilder(c,ScheduleDatabase::class.java,name).build(), profiles=TermScheduleProfileStore(c))
   object:ScheduleStore by store { override fun loadPresentation(callback:(Result<ScheduleState>)->Unit) { callback(Result.failure(IllegalStateException("测试读取失败"))) } }
  }
  launchPhoneTestActivity().use { scenario ->
   Thread.sleep(1200);onView(withText("管理")).perform(click());onView(withText("导出 ICS")).perform(click());onView(withText("预览")).perform(click())
   onView(withText("测试读取失败")).check(matches(isDisplayed()))
   scenario.onActivity { assertTrue(it.source.state.entries.isEmpty()) }
  }
 }
 private fun awaitText(text: String) {
  val deadline=System.currentTimeMillis()+10000
  while(true) { try { onView(withText(text)).check(matches(isDisplayed()));return } catch(e: androidx.test.espresso.NoMatchingViewException) { if(System.currentTimeMillis()>deadline)throw e;Thread.sleep(50) } }
 }
 private fun cancelDelayedPreview(read: Boolean, fail: Boolean) {
  var complete: ((Result<ScheduleState>)->Unit)?=null
  if(read) ScheduleStores.overrideFactory={c->
   val store=RoomScheduleStore(Room.databaseBuilder(c,ScheduleDatabase::class.java,name).build(),profiles=TermScheduleProfileStore(c))
   object:ScheduleStore by store { override fun loadPresentation(callback:(Result<ScheduleState>)->Unit) { complete=callback } }
  }
  launchPhoneTestActivity().use { scenario ->
   Thread.sleep(1000)
   val gate=java.util.concurrent.CountDownLatch(1)
   if(!read) scenario.onActivity { a ->
    val field=TimetableActivity::class.java.getDeclaredField("worker");field.isAccessible=true
    (field.get(a) as java.util.concurrent.ExecutorService).execute { gate.await(10,java.util.concurrent.TimeUnit.SECONDS) }
   }
   onView(withText("管理")).perform(click());onView(withText("导出 ICS")).perform(click())
   if(!read && fail) onView(withHint("开始日期 YYYY-MM-DD")).perform(androidx.test.espresso.action.ViewActions.replaceText("invalid"))
   onView(withText("预览")).perform(click());onView(withText("取消")).perform(click())
   if(read) scenario.onActivity { complete!!(if(fail) Result.failure(IllegalStateException("迟到错误")) else Result.success(ScheduleState())) }
   gate.countDown();Thread.sleep(800)
   onView(withText("导出预览")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
   onView(withText("迟到错误")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
   onView(withText("保存文件")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
   // Any late error dialog would hide the normal management button.
   onView(withText("管理")).perform(click());onView(withText("导出 ICS")).check(matches(isDisplayed()))
  }
 }
 @Test fun cancelledReadSuccess(){cancelDelayedPreview(true,false)}
 @Test fun cancelledReadFailure(){cancelDelayedPreview(true,true)}
 @Test fun cancelledGenerationSuccess(){cancelDelayedPreview(false,false)}
 @Test fun cancelledGenerationFailure(){cancelDelayedPreview(false,true)}
 private fun recreateWriting(fail:Boolean) {
  val root=Uri.parse("content://${i.context.packageName}.fixtures")
  fun control(method:String)=i.context.contentResolver.call(root,method,null,null)!!
  control("reset-export")
  val target=Uri.withAppendedPath(root,if(fail) "export-slow-fail.ics" else "export-slow.ics")
  Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK,Intent().setData(target)))
  launchPhoneTestActivity().use { scenario ->
   Thread.sleep(1000);onView(withText("管理")).perform(click());onView(withText("导出 ICS")).perform(click());onView(withText("预览")).perform(click());awaitText("保存文件");onView(withText("保存文件")).perform(click())
   val deadline=System.currentTimeMillis()+10000
   while(control("status-export").getInt("opens")==0 && System.currentTimeMillis()<deadline)Thread.sleep(50)
   assertEquals(1,control("status-export").getInt("opens"));scenario.recreate();control("release-export")
   if(fail) { Thread.sleep(700);onView(withText(org.hamcrest.Matchers.startsWith("文件保存失败"))).check(matches(isDisplayed())) }
   else { awaitText("ICS 文件已保存。");val text=i.context.contentResolver.openInputStream(target)!!.bufferedReader().use{it.readText()};assertTrue(text.startsWith("BEGIN:VCALENDAR")) }
   assertEquals(1,control("status-export").getInt("opens"))
   onView(withText("确定")).perform(click());scenario.recreate();Thread.sleep(300)
   onView(withText("ICS 文件已保存。")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
  }
 }
 @Test fun writingRecreationSuccess(){recreateWriting(false)}
 @Test fun writingRecreationFailure(){recreateWriting(true)}
}
