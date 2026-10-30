package com.dormpanel.phone

import android.app.Notification
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Only a fresh, isolated package; never writes the daily Store. */
class CourseNotificationTest {
    @Test fun settingsPublicationPrivacyAndOffCancellation() {
        val i=InstrumentationRegistry.getInstrumentation()
        val c=i.targetContext
        check(c.packageName.endsWith(".pr76testbed"))
        val manager=c.getSystemService(NotificationManager::class.java)
        assertEquals(CourseNoticeMode.OFF,CourseNoticeSettings.read(c).mode)
        val options=CourseNoticeOptions(CourseNoticeMode.NEAR_CLASS,30,true)
        CourseNoticeSettings.write(c,options)
        assertEquals(options,CourseNoticeSettings.read(c))
        assertTrue(CourseNotificationPublisher.available(c))
        val now=Instant.now()
        val course=ClassOccurrence(TimetableEntry("test","Private title",1,480,540,"Private room"),
            LocalDate.now(),now.minusSeconds(60),now.plusSeconds(600))
        try {
            CourseNotificationPublisher.publish(c,CourseNoticePlan(course,true,false,course.end),options)
            Thread.sleep(500)
            val active=manager.activeNotifications.single { it.id==21001 }.notification
            assertEquals(R.drawable.ic_notification_course, active.smallIcon.resId)
            assertEquals(R.drawable.ic_notification_course, active.publicVersion.smallIcon.resId)
            assertTrue(active.timeoutAfter in 1..600_000)
            assertEquals(Notification.VISIBILITY_PRIVATE,active.visibility)
            assertTrue(active.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(active.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertTrue(active.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertTrue(active.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
            assertEquals(course.end.toEpochMilli(),active.`when`)
            assertFalse(active.publicVersion.extras.toString().contains("Private title"))
            assertFalse(active.publicVersion.extras.toString().contains("Private room"))
            CourseNotificationPublisher.publish(c,CourseNoticePlan(course,false,true,course.start),options)
            Thread.sleep(500)
            assertEquals(course.start.toEpochMilli(),manager.activeNotifications.single { it.id==21001 }.notification.`when`)
            // With no refresh at all, the OS still removes the expired notification.
            CourseNotificationPublisher.publish(c,
                CourseNoticePlan(course,true,false,Instant.now().plusMillis(1000)),options)
            val expiryLimit=android.os.SystemClock.elapsedRealtime()+15_000
            while (manager.activeNotifications.any { it.id==21001 } &&
                android.os.SystemClock.elapsedRealtime()<expiryLimit) Thread.sleep(250)
            assertFalse(manager.activeNotifications.any { it.id==21001 })
            CourseNotificationPublisher.publish(c,CourseNoticePlan(course,true,false,course.end),options)
            Thread.sleep(500)
            assertTrue(manager.activeNotifications.any { it.id==21001 })
            CourseNoticeSettings.write(c,options.copy(mode=CourseNoticeMode.OFF))
            val done=CountDownLatch(1)
            CourseNotificationCoordinator.request(c) { done.countDown() }
            assertTrue(done.await(9,TimeUnit.SECONDS))
            Thread.sleep(500)
            assertFalse(manager.activeNotifications.any { it.id==21001 })
        } finally {
            CourseNoticeSettings.write(c,CourseNoticeOptions())
            CourseNotificationPublisher.cancel(c)
        }
    }
}
