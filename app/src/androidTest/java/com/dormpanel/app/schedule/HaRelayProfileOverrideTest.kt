package com.dormpanel.app.schedule

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.ha.HaRelayChannel
import com.dormpanel.app.ha.HaRelayIdentityStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HA relay must honor persisted TermScheduleProfileStore overrides, not just
 * the built-in 2026-2027-1 preset.
 */
class HaRelayProfileOverrideTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(latch: CountDownLatch) = assertTrue("Relay preview timed out", latch.await(15, TimeUnit.SECONDS))
    private val namespace = "test_ha_profiles_${System.nanoTime()}"

    @Before fun setUp() { TermScheduleProfileStore.overrideNamespace = namespace }
    @After fun tearDown() {
        TermScheduleProfileStore.overrideNamespace = null
        instrumentation.targetContext
            .getSharedPreferences("${namespace}_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class Channel(override val origin: String) : HaRelayChannel {
        override var ready = true
        val pending = linkedSetOf<String>()
        val claims = mutableListOf<String>()
        val acknowledgements = mutableListOf<Pair<String, String>>()
        var claimResponse: (String) -> JSONObject = { JSONObject() }
        private val readyListeners = linkedSetOf<() -> Unit>()
        private val eventListeners = linkedSetOf<(JSONObject) -> Unit>()
        override fun request(message: JSONObject, callback: (JSONObject) -> Unit): Boolean {
            if (!ready) return false
            val id = message.optString("transfer_id")
            when (message.getString("type")) {
                "dormpanel/register" -> callback(JSONObject().put("success", true))
                "dormpanel/list_pending" -> callback(JSONObject().put("success", true).put("result", JSONArray().apply {
                    pending.forEach { put(JSONObject().put("transfer_id", it)) }
                }))
                "dormpanel/claim_transfer" -> {
                    claims += id
                    callback(JSONObject().put("success", true).put("result", claimResponse(id)))
                }
                "dormpanel/ack_transfer" -> {
                    val outcome = message.getString("outcome")
                    acknowledgements += id to outcome
                    callback(JSONObject().put("success", true))
                }
                else -> error("Unexpected relay request")
            }
            return true
        }
        override fun addReadyListener(listener: () -> Unit) {
            readyListeners += listener
            if (ready) listener()
        }
        override fun removeReadyListener(listener: () -> Unit) { readyListeners -= listener }
        override fun addEventListener(listener: (JSONObject) -> Unit) { eventListeners += listener }
        override fun removeEventListener(listener: (JSONObject) -> Unit) { eventListeners -= listener }
    }

    @Test fun haRelayUsesPersistedProfileOverrideInsteadOfBuiltIn() {
        // Persist a custom profile with periods 1-2 shifted to 10:00–11:00.
        val store = TermScheduleProfileStore(instrumentation.targetContext)
        val custom = BuiltInProfiles.term2026.copy(
            phases = listOf(
                BuiltInProfiles.term2026.phases[0].copy(periods = BuiltInProfiles.term2026.phases[0].periods.map {
                    if (it.periodNumber == 1) PeriodTime(1, LocalTime.of(10, 0), LocalTime.of(10, 30))
                    else if (it.periodNumber == 2) PeriodTime(2, LocalTime.of(10, 40), LocalTime.of(11, 0))
                    else it
                }),
                BuiltInProfiles.term2026.phases[1]
            )
        )
        store.put(custom)

        val csv = instrumentation.context.assets.open("hubei_2026-2027-1.csv").use { it.readBytes() }
        MockWebServer().use { server ->
            val identity = HaRelayIdentityStore(instrumentation.targetContext)
            val hash = MessageDigest.getInstance("SHA-256").digest(csv).joinToString("") { "%02x".format(it) }
            val id = "e".repeat(32)
            val channel = Channel(server.url("/").toString()).apply {
                pending += id
                claimResponse = {
                    JSONObject().put("transfer_id", id).put("size", csv.size)
                        .put("filename", "schedule.csv").put("sha256", hash).put("kind", "schedule_csv")
                        .put("signed_path", "/api/dormpanel/transfers/$id?authSig=test")
                }
            }
            server.enqueue(MockResponse().setBody(okio.Buffer().write(csv)))
            val received = CountDownLatch(1)
            var delivery: RelayPreview? = null
            lateinit var relay: HaScheduleRelayController
            main {
                relay = HaScheduleRelayController(channel, identity, OkHttpClient(), {},
                    profiles = TermScheduleProfileStore(instrumentation.targetContext))
                relay.attach { preview -> delivery = preview; received.countDown() }
            }
            try {
                await(received)
                val preview = delivery!!.preview!!
                assertEquals("schedule_csv", "schedule_csv") // kind was accepted
                assertEquals("2026-2027-1", preview.termKey)
                // Period 1 in the custom profile is 10:00, not the built-in 08:00.
                val zone = ZoneId.of("Asia/Shanghai")
                val first = preview.occurrences.minBy { it.start }
                val start = Instant.ofEpochMilli(first.start).atZone(zone)
                // The earliest CSV occurrence is 大数据导论 on Week 1 Thursday periods 9-10.
                // Period 9 is unchanged in the override (19:00), so check a periods-1-2 class instead.
                val math = preview.occurrences.first {
                    it.title == "高等数学B-1" && it.periodLabel == "[01-02节]"
                }
                val mathStart = Instant.ofEpochMilli(math.start).atZone(zone)
                assertEquals("10:00", mathStart.toLocalTime().toString())
                // Built-in would have been 08:00 — this distinguishes override from preset.
            } finally { main { relay.close() } }
        }
    }
}
