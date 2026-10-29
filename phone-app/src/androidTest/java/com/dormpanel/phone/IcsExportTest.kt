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
 private fun assertExportOptions(enabled:Boolean) {
  for(hint in listOf("开始日期 YYYY-MM-DD","结束日期 YYYY-MM-DD","IANA 时区"))
   onView(withHint(hint)).check(matches(if(enabled) isEnabled() else org.hamcrest.Matchers.not(isEnabled())))
  for(text in listOf("包含日历事件","本周","未来四周"))
   onView(withText(text)).check(matches(if(enabled) isEnabled() else org.hamcrest.Matchers.not(isEnabled())))
  onView(withText("取消")).check(matches(isEnabled()))
 }
 @Test fun repeatedPreviewSubmitsOneReadAndGeneration() {
  var reads=0;var complete:((Result<ScheduleState>)->Unit)?=null
  ScheduleStores.overrideFactory={c->
   val store=RoomScheduleStore(Room.databaseBuilder(c,ScheduleDatabase::class.java,name).build(),profiles=TermScheduleProfileStore(c))
   object:ScheduleStore by store { override fun loadPresentation(callback:(Result<ScheduleState>)->Unit) { reads++;complete=callback } }
  }
  launchPhoneTestActivity().use { scenario ->
   Thread.sleep(1000)
   val gate=java.util.concurrent.CountDownLatch(1);val submissions=java.util.concurrent.atomic.AtomicInteger()
   scenario.onActivity { a ->
    val field=TimetableActivity::class.java.getDeclaredField("worker");field.isAccessible=true
    val actual=field.get(a) as java.util.concurrent.ExecutorService
    actual.execute { gate.await(10,java.util.concurrent.TimeUnit.SECONDS) }
    field.set(a,object:java.util.concurrent.ExecutorService by actual { override fun execute(task:Runnable) { submissions.incrementAndGet();actual.execute(task) } })
   }
   try {
    onView(withText("管理")).perform(click());onView(withText("导出 ICS")).perform(click())
    // Direct performClick also exercises the admission guard independently of disabled touch handling.
    val repeatClick=object:androidx.test.espresso.ViewAction {
     override fun getConstraints()=isAssignableFrom(android.widget.Button::class.java)
     override fun getDescription()="rapid preview submission"
     override fun perform(c:androidx.test.espresso.UiController,v:android.view.View) { repeat(20){v.performClick()} }
    }
    onView(withText("预览")).perform(repeatClick)
    assertEquals(1,reads);assertEquals(0,submissions.get());assertExportOptions(false)
    onView(withText("正在生成预览…")).check(matches(org.hamcrest.Matchers.not(isEnabled())))
    scenario.onActivity { complete!!(Result.success(ScheduleState())) }
    onView(withText("正在生成预览…")).perform(repeatClick)
    assertEquals(1,reads);assertEquals(1,submissions.get());assertExportOptions(false)
    gate.countDown();awaitText("保存文件");assertEquals(1,submissions.get())
   } finally { gate.countDown() }
  }
 }
 private fun previewFailureRetry(readFailure:Boolean) {
  var reads=0
  val event=CalendarEvent("export-event","已选择日历事件",java.time.Instant.parse("2026-10-12T08:00:00Z").toEpochMilli(),java.time.Instant.parse("2026-10-12T09:00:00Z").toEpochMilli())
  ScheduleStores.overrideFactory={c->
   val store=RoomScheduleStore(Room.databaseBuilder(c,ScheduleDatabase::class.java,name).build(),profiles=TermScheduleProfileStore(c))
   object:ScheduleStore by store { override fun loadPresentation(callback:(Result<ScheduleState>)->Unit) { reads++;callback(if(readFailure && reads==1) Result.failure(IllegalStateException("可重试读取失败")) else Result.success(ScheduleState(events=listOf(event)))) } }
  }
  val uri=Uri.parse("content://${i.context.packageName}.fixtures/export.ics")
  Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK,Intent().setData(uri)))
  launchPhoneTestActivity().use {
   Thread.sleep(1000);onView(withText("管理")).perform(click());onView(withText("导出 ICS")).perform(click())
   onView(withHint("开始日期 YYYY-MM-DD")).perform(androidx.test.espresso.action.ViewActions.replaceText("2026-10-12"))
   onView(withHint("结束日期 YYYY-MM-DD")).perform(androidx.test.espresso.action.ViewActions.replaceText("2026-10-18"))
   onView(withHint("IANA 时区")).perform(androidx.test.espresso.action.ViewActions.replaceText(if(readFailure) "UTC" else "bad-zone"))
   onView(withText("包含日历事件")).perform(click());onView(withText("预览")).perform(click())
   if(readFailure) awaitText("可重试读取失败") else {
    val deadline=System.currentTimeMillis()+10000
    while(true) { try { onView(withText(org.hamcrest.Matchers.containsString("bad-zone"))).check(matches(isDisplayed()));break } catch(e:androidx.test.espresso.NoMatchingViewException){if(System.currentTimeMillis()>deadline)throw e;Thread.sleep(50)} }
   }
   onView(withText("确定")).perform(click());assertExportOptions(true);onView(withText("预览")).check(matches(isEnabled()))
   onView(withHint("IANA 时区")).perform(androidx.test.espresso.action.ViewActions.replaceText("UTC"))
   onView(withText("预览")).perform(click());awaitText("保存文件");assertEquals(2,reads)
   onView(withText(org.hamcrest.Matchers.containsString("2026-10-12 至 2026-10-18\n时区 UTC\n课程 0 · 日历事件 1"))).check(matches(isDisplayed()))
   onView(withText("保存文件")).perform(click());awaitText("ICS 文件已保存。")
   val text=i.context.contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()}
   assertTrue(text.contains("SUMMARY:已选择日历事件"));assertTrue(text.contains("DTSTART:20261012T080000Z"))
  }
 }
 @Test fun readFailureRestoresInputsAndRetries(){previewFailureRetry(true)}
 @Test fun generationFailureRestoresInputsAndRetries(){previewFailureRetry(false)}
}
