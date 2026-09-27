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
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * When a user edits the term profile from an HA-delivered preview, the old preview
 * is invalidated and the HA transfer must get exactly one `dismissed` terminal
 * outcome so the relay queue can proceed.
 */
class HaRelayInvalidatedPreviewTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(latch: CountDownLatch) = assertTrue("Timed out", latch.await(15, TimeUnit.SECONDS))

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
                    if (outcome != "preview_ready") pending.remove(id)
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

    @Test fun editProfileInvalidationResolvesActiveHaTransferAsDismissed() {
        val csv = instrumentation.context.assets.open("hubei_2026-2027-1.csv").use { it.readBytes() }
        MockWebServer().use { server ->
            val identity = HaRelayIdentityStore(instrumentation.targetContext)
            val hash = MessageDigest.getInstance("SHA-256").digest(csv).joinToString("") { "%02x".format(it) }
            val id1 = "a".repeat(32)
            val id2 = "b".repeat(32)
            val channel = Channel(server.url("/").toString()).apply {
                pending += id1
                pending += id2
                claimResponse = { id ->
                    JSONObject().put("transfer_id", id).put("size", csv.size)
                        .put("filename", "schedule.csv").put("sha256", hash).put("kind", "schedule_csv")
                        .put("signed_path", "/api/dormpanel/transfers/$id?authSig=test")
                }
            }
            repeat(2) { server.enqueue(MockResponse().setBody(okio.Buffer().write(csv))) }
            val firstPreview = CountDownLatch(1)
            val secondPreview = CountDownLatch(1)
            var previewCount = 0
            var delivery: RelayPreview? = null
            lateinit var relay: HaScheduleRelayController
            main {
                relay = HaScheduleRelayController(channel, identity, OkHttpClient(), {},
                    profiles = TermScheduleProfileStore(instrumentation.targetContext))
                relay.attach { p ->
                    delivery = p
                    previewCount++
                    if (previewCount == 1) firstPreview.countDown() else secondPreview.countDown()
                }
            }
            try {
                await(firstPreview)
                val first = delivery!!
                assertEquals(id1, first.transferId)

                // Simulate "Edit term profile" from the preview: the UI invalidates the
                // preview and must resolve the HA transfer as dismissed.
                main { relay.resolve(id1, "dismissed") }

                // Exactly one dismissed for id1.
                assertEquals(1, channel.acknowledgements.count { it.first == id1 && it.second == "dismissed" })
                assertTrue(channel.acknowledgements.none { it.first == id1 && it.second == "imported" })

                // The relay must proceed to the next queued transfer.
                await(secondPreview)
                assertEquals(id2, delivery!!.transferId)
            } finally { main { relay.close() } }
        }
    }
}
