package com.dormpanel.app

import android.os.SystemClock
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.HandlerThread
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.airplay.AirPlayController
import com.dormpanel.app.airplay.AirPlayCommand
import com.dormpanel.app.airplay.AirPlayPhase
import com.dormpanel.app.airplay.AirPlayRemote
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.dashboard.layout.LayoutMutationResult
import com.dormpanel.app.dashboard.model.CardSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AirPlayAndroidTest {
    @get:Rule val dashboard = IsolatedDashboardRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val controller get() = AirPlayController.get(instrumentation.targetContext)

    @Test fun receiverRunsWithoutActivityAndStopsTwice() {
        val displayName = "DormPanel AirPlay test"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    assertFalse(controller.settings.enabled)
                    assertFalse(controller.settings.autoStart)
                }
                onView(withId(R.id.page_container)).perform(swipeDown())
                onView(withText(R.string.airplay_settings)).perform(scrollTo(), click())
                onView(withText(R.string.airplay_connect_help)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click())
                onView(withText(R.string.airplay_enable)).perform(scrollTo(), click())
                await(AirPlayPhase.WAITING)
                scenario.onActivity {
                    assertTrue(controller.configure(displayName, true, false))
                    assertTrue(controller.configure(displayName, true, false))
                }
                await(AirPlayPhase.WAITING)
                scenario.recreate()
                await(AirPlayPhase.WAITING)
            }
            await(AirPlayPhase.WAITING)
            instrumentation.runOnMainSync {
                assertTrue(controller.configure(displayName, false, false))
                assertTrue(controller.configure(displayName, false, false))
            }
            await(AirPlayPhase.OFF)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { assertTrue(controller.configure(displayName, true, false)) }
                await(AirPlayPhase.WAITING)
            }
        } finally {
            instrumentation.runOnMainSync {
                assertTrue(controller.configure("DormPanel X08E", false, false))
            }
            await(AirPlayPhase.OFF)
        }
    }

    @Test fun cardCanBeAddedResizedAndRemovedWithoutChangingExistingCards() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 8_000
            var loaded = false
            while (!loaded && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { loaded = ViewModelProvider(it)[DashboardViewModel::class.java].stateHolder.state.loaded }
                if (!loaded) Thread.sleep(30)
            }
            assertTrue(loaded)
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[DashboardViewModel::class.java]
                val original = vm.stateHolder.state.cards
                val candidate = vm.catalog.candidates.single { it.providerType == "airplay_music" }
                assertFalse(original.any { it.providerType == candidate.providerType })
                assertTrue(vm.stateHolder.add(candidate) is LayoutMutationResult.Success)
                val compact = vm.stateHolder.state.cards.single { it.providerType == candidate.providerType }
                assertTrue(vm.stateHolder.resize(compact.id, CardSize(4, 1)) is LayoutMutationResult.Success)
                assertTrue(vm.stateHolder.delete(compact.id) is LayoutMutationResult.Success)
                assertEquals(original, vm.stateHolder.state.cards)

                // The demo layout fills its upper five rows. Free the bottom three rows in the test store.
                original.filter { it.row >= 3 }.forEach { vm.stateHolder.delete(it.id) }
                val withSpace = vm.stateHolder.state.cards
                assertTrue(vm.stateHolder.add(candidate) is LayoutMutationResult.Success)
                val expanded = vm.stateHolder.state.cards.single { it.providerType == candidate.providerType }
                assertTrue(vm.stateHolder.move(expanded.id, 0, 3) is LayoutMutationResult.Success)
                assertTrue(vm.stateHolder.resize(expanded.id, CardSize(4, 3)) is LayoutMutationResult.Success)
                assertTrue(vm.stateHolder.delete(expanded.id) is LayoutMutationResult.Success)
                assertEquals(withSpace, vm.stateHolder.state.cards)
            }
        }
    }

    @Test fun dacpWaitsForAnAuthenticatedStatusResponseBeforeSendingACommand() {
        val context = instrumentation.targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val peer = requireNotNull(connectivity.getLinkProperties(connectivity.activeNetwork))
            .linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().first()
        val nsd = context.getSystemService(NsdManager::class.java)
        val thread = HandlerThread("DACP test").apply { start() }
        val worker = Handler(thread.looper)
        val registered = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val service = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { registered.countDown() }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        val id = System.nanoTime().toString(16)
        val remote = AirPlayRemote(nsd, worker, peer, id, "114283") {
            if (AirPlayCommand.NEXT in it) ready.countDown()
        }
        try {
            MockWebServer().use { sender ->
                sender.start(peer, 0)
                sender.enqueue(MockResponse().setBody("cmst\u0000\u0000\u0000\u0000"))
                sender.enqueue(MockResponse().setResponseCode(204))
                nsd.registerService(NsdServiceInfo().apply {
                    serviceName = "iTunes_Ctrl_$id"
                    serviceType = "_dacp._tcp."
                    port = sender.port
                }, NsdManager.PROTOCOL_DNS_SD, service)
                assertTrue(registered.await(8, TimeUnit.SECONDS))
                worker.post {
                    remote.send(AirPlayCommand.NEXT)
                    remote.start()
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                assertEquals("/ctrl-int/1/playstatusupdate?revision-number=1",
                    sender.takeRequest(2, TimeUnit.SECONDS)?.path)
                worker.post { remote.send(AirPlayCommand.NEXT) }
                val request = sender.takeRequest(4, TimeUnit.SECONDS)
                assertEquals("/ctrl-int/1/nextitem", request?.path)
                assertEquals("114283", request?.getHeader("Active-Remote"))
            }
        } finally {
            runCatching { nsd.unregisterService(service) }
            worker.post { remote.close(); thread.quitSafely() }
        }
    }

    private fun await(expected: AirPlayPhase) {
        val end = SystemClock.elapsedRealtime() + 16_000
        var actual: AirPlayPhase? = null
        var detail: String? = null
        while (SystemClock.elapsedRealtime() < end) {
            instrumentation.runOnMainSync {
                actual = controller.state.phase
                detail = controller.state.detail
            }
            if (actual == expected) return
            Thread.sleep(100)
        }
        assertEquals(detail, expected, actual)
    }
}
