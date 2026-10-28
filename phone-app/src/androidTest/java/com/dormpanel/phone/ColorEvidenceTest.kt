package com.dormpanel.phone

import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import org.junit.Test
import org.junit.Assert.*
import java.time.*
import java.util.concurrent.*

/** Only fresh PR68 packages. Shared unchanged between before/after screenshot builds. */
class ColorEvidenceTest {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val c get()=i.targetContext
    private fun <T> await(call:((Result<T>)->Unit)->Unit):T { val f=CompletableFuture<Result<T>>();call{f.complete(it)};return f.get(20,TimeUnit.SECONDS).getOrThrow() }
    private fun shot(name:String) { i.waitForIdleSync();val b=i.uiAutomation.takeScreenshot();java.io.File(c.getExternalFilesDir(null),"pr68-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle() }
    @Test fun seedAndShowImportedCourses() {
        require(c.packageName.endsWith(".pr68testbed") || c.packageName.endsWith(".pr68before"))
        val store=ScheduleStores.create(c)
        val monday=LocalDate.now().with(java.time.DayOfWeek.MONDAY)
        fun event(id:String,title:String,day:Int,hour:Int) = "BEGIN:VEVENT\r\nUID:$id\r\nDTSTART;TZID=Asia/Shanghai:${monday.plusDays(day.toLong()).toString().replace("-","")}T${"%02d".format(hour)}0000\r\nDTEND;TZID=Asia/Shanghai:${monday.plusDays(day.toLong()).toString().replace("-","")}T${"%02d".format(hour+2)}0000\r\nRRULE:FREQ=WEEKLY;COUNT=8\r\nSUMMARY:$title\r\nLOCATION:A101\r\nEND:VEVENT\r\n"
        val text="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//DormPanel//PR68 isolated//ZH\r\n" +
            event("math-a","高等数学 B-1",0,8)+event("math-b","高等数学 B-1",1,10)+event("math-c","高等数学 B-1",2,18)+
            listOf("大学英语","管理学原理","体育基础素质课","管理科学与工程导论","思想道德与法治","程序设计","物理实验","心理健康教育").mapIndexed{n,title->event("other-$n",title,n%5,if(n<5)14 else 12)}.joinToString("")+"END:VCALENDAR\r\n"
        val initial=await<ScheduleState>(store::load)
        val preview=TimetableImporter().parse(ScheduleArtifact("pr68.ics",text.toByteArray()))
        val previous=initial.sources.singleOrNull{it.displayName=="PR68 隔离课程"}?.id
        await<ImportCommit>{IcsScheduleImporter().commit(preview,store,"PR68 隔离课程",previous,1,it)}
        val state=await<ScheduleState>(store::load)
        assertEquals(3,CourseBatch.candidates(state).single{it.originalTitle=="高等数学 B-1"}.series.size)
        c.getSharedPreferences("com.dormpanel.phone.TimetableActivity",0).edit().putString("date",monday.toString()).putBoolean("weekView",true).commit()
        launchPhoneTestActivity().use { scenario ->
            Thread.sleep(1000);shot("imported-week-${if(c.packageName.endsWith("before")) "before" else "after"}")
        }
    }
}
