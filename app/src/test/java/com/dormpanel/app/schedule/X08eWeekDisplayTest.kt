package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class X08eWeekDisplayTest {
    private val week=LocalDate.of(2026,10,26)
    private val zone=ZoneId.of("Asia/Shanghai")
    private fun state(title: String="课程") = ScheduleState(entries=listOf(TimetableEntry("local",title,1,480,540,"教室")))
    private fun await(condition: ()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!condition() && System.nanoTime()<deadline) Thread.sleep(5)
        assertTrue(condition())
    }
    @Test fun pureSnapshotRetainsProjectionGeometryLanesAndText() {
        val s=state()
        for(layout in X08eScheduleLayout.entries) for(locale in listOf(Locale.CHINA,Locale.US)) {
            val data=X08eWeekDisplay.prepare(s,week,zone,X08eScheduleStyle(layout,true,true),locale)
            assertEquals(ScheduleProjection.week(s,week,zone),data.items)
            assertEquals(data.items,data.days[0]); assertTrue(data.days.drop(1).all { it.isEmpty() })
            val text=data.texts.getValue(data.items.single())
            assertEquals("课程",text.title); assertEquals("08:00–09:00",text.time)
            assertNull(text.periods); assertEquals("",text.status)
            if(layout!=X08eScheduleLayout.CLASSIC) {
                assertFalse(data.usesPeriods)
                assertEquals(X08eWeekGeometry.segments(data.items,week,zone),data.segments)
                assertEquals(X08eWeekGeometry.range(data.segments),data.range)
                assertEquals(data.segments,data.lanes[0].flatten().flatten())
            }
        }
    }
    @Test fun immutableEpochDropsLateWorkAndBoundsCachedWeeks() {
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val old=state("旧数据"); val fresh=state("新数据")
        val cache=X08eDisplayCache { s,w,z,style,locale ->
            if(s===old) {
                entered.countDown()
                // Simulate a pure calculation that cannot stop in its current small operation.
                var waiting=true
                while(waiting) try { release.await(); waiting=false } catch(_: InterruptedException) { }
            }
            X08eWeekDisplay.prepare(s,w,z,style,locale)
        }
        try {
            val style=X08eScheduleStyle(swipeWeeks=true)
            cache.configure(old,zone,style,Locale.CHINA,"1280:800:160:1:dark"); cache.warm(week)
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            cache.configure(fresh,zone,style,Locale.CHINA,"1280:800:160:1:dark"); cache.warm(week)
            release.countDown()
            await { cache.get(week.plusWeeks(1))!=null }
            assertEquals("新数据",cache.get(week.plusWeeks(1))!!.items.single().entry.title)
            cache.warm(week.plusWeeks(10))
            await { cache.get(week.plusWeeks(11))!=null }
            assertNull(cache.get(week.plusWeeks(1))); assertNull(cache.get(week.minusWeeks(1)))
            cache.clear(); assertNull(cache.get(week.plusWeeks(11)))
        } finally { release.countDown(); cache.clear() }
    }
    @Test fun displaySettingsLocaleZoneAndConfigurationStartNewEpoch() {
        val calls=AtomicInteger()
        val cache=X08eDisplayCache { s,w,z,style,locale -> calls.incrementAndGet(); X08eWeekDisplay.prepare(s,w,z,style,locale) }
        val s=state(); val style=X08eScheduleStyle(swipeWeeks=true)
        try {
            fun prepare(option: X08eScheduleStyle=style, locale: Locale=Locale.CHINA, z: ZoneId=zone, config: String="dark:160:1:1280:800") {
                cache.configure(s,z,option,locale,config); cache.warm(week)
                await { cache.get(week.minusWeeks(1))!=null }
            }
            prepare(); val initial=calls.get(); prepare(); assertEquals(initial,calls.get())
            prepare(style.copy(layout=X08eScheduleLayout.PERIODS)); assertTrue(calls.get()>initial)
            val changed=calls.get(); prepare(style.copy(colors=true),Locale.US,ZoneId.of("UTC"),"light:240:1.3:800:1280")
            assertTrue(calls.get()>changed)
            cache.invalidate(); assertNull(cache.get(week.minusWeeks(1)))
        } finally { cache.clear() }
    }
}
