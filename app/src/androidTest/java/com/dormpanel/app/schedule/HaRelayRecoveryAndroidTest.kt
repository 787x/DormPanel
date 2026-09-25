package com.dormpanel.app.schedule

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

class HaRelayRecoveryAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(latch: CountDownLatch) = assertTrue("Relay preview timed out", latch.await(12, TimeUnit.SECONDS))

    private class Channel(override val origin: String) : HaRelayChannel {
        override var ready = true
        val pending = linkedSetOf<String>()
        val claims = mutableListOf<String>()
        val acknowledgements = mutableListOf<Pair<String, String>>()
        var rejectTerminalAck = false
        var deferTerminalAck = false
        var deferredTerminalAck: ((JSONObject) -> Unit)? = null
        var deferNextClaim = false
        var deferredClaim: ((JSONObject) -> Unit)? = null
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
                    if (deferNextClaim) { deferNextClaim = false; deferredClaim = callback }
                    else callback(JSONObject().put("success", true).put("result", claimResponse(id)))
                }
                "dormpanel/ack_transfer" -> {
                    val outcome = message.getString("outcome")
                    acknowledgements += id to outcome
                    val success = outcome == "preview_ready" || !rejectTerminalAck
                    if (deferTerminalAck && outcome != "preview_ready") deferredTerminalAck = callback
                    else {
                        if (success && outcome != "preview_ready") pending.remove(id)
                        callback(JSONObject().put("success", success))
                    }
                }
                else -> error("Unexpected relay request")
            }
            return true
        }
        override fun addReadyListener(listener: () -> Unit) { readyListeners += listener; if (ready) listener() }
        override fun removeReadyListener(listener: () -> Unit) { readyListeners -= listener }
        override fun addEventListener(listener: (JSONObject) -> Unit) { eventListeners += listener }
        override fun removeEventListener(listener: (JSONObject) -> Unit) { eventListeners -= listener }
        fun reconnect() { readyListeners.toList().forEach { it() } }
        fun push(id: String, installationId: String) {
            val event = JSONObject().put("transfer_id", id)
                .put("target_installation_ids", JSONArray().put(installationId))
            eventListeners.toList().forEach { it(event) }
        }
        val listenerCount get() = readyListeners.size + eventListeners.size
    }

    @Test fun apkUsesSameClaimQueueAndNeverAutoInstalls() {
        MockWebServer().use { server ->
            val context = instrumentation.targetContext
            val identity = HaRelayIdentityStore(context)
            val bytes = java.io.File(context.packageCodePath).readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val id = "a".repeat(32)
            val channel = Channel(server.url("/").toString()).apply {
                pending += id
                claimResponse = { JSONObject().put("transfer_id", id).put("kind", "apk")
                    .put("size", bytes.size).put("filename", "testbed.apk").put("sha256", hash)
                    .put("signed_path", "/api/dormpanel/transfers/$id?authSig=test") }
            }
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val source = com.dormpanel.app.apps.AppSources.create(context)
            val apk = com.dormpanel.app.apps.ApkInstallController(context, source)
            val received = CountDownLatch(1)
            lateinit var relay: HaScheduleRelayController
            main {
                apk.observe { if (apk.candidate != null) received.countDown() }
                relay = HaScheduleRelayController(channel, identity, OkHttpClient(), {}, apk)
            }
            try {
                await(received)
                main {
                    assertEquals(context.packageName, apk.candidate?.packageName)
                    assertEquals(listOf(id), channel.claims)
                    assertTrue(channel.acknowledgements.contains(id to "preview_ready"))
                    assertFalse(channel.acknowledgements.any { it.second == "installed" })
                    apk.dismiss()
                    assertTrue(channel.acknowledgements.contains(id to "dismissed"))
                }
            } finally { main { relay.close(); apk.close(); source.close() } }
        }
    }

    @Test fun lostTerminalAckCallbackIsRediscoveredAfterReconnect() {
        MockWebServer().use { server ->
            val identity = HaRelayIdentityStore(instrumentation.targetContext)
            val raw = instrumentation.context.assets.open("wakeup.ics").use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
            val id = "d".repeat(32)
            val channel = Channel(server.url("/").toString()).apply {
                pending += id
                deferTerminalAck = true
                claimResponse = { JSONObject().put("transfer_id", id).put("size", raw.size)
                    .put("filename", "schedule.ics").put("sha256", hash)
                    .put("signed_path", "/api/dormpanel/transfers/$id?authSig=test") }
            }
            repeat(2) { server.enqueue(MockResponse().setBody(String(raw, Charsets.UTF_8))) }
            val first = CountDownLatch(1)
            val second = CountDownLatch(1)
            var previews = 0
            lateinit var controller: HaScheduleRelayController
            main {
                controller = HaScheduleRelayController(channel, identity, OkHttpClient())
                controller.attach { previews++; if (previews == 1) first.countDown() else second.countDown() }
            }
            try {
                await(first)
                main {
                    controller.resolve(id, "imported")
                    assertNotNull(channel.deferredTerminalAck)
                    assertTrue(id in channel.pending)
                    channel.reconnect() // Socket dropped the ack callback.
                    channel.deferredTerminalAck?.invoke(JSONObject().put("success", true)) // Obsolete reply cannot settle it.
                }
                await(second)
                main {
                    assertEquals(2, previews)
                    assertEquals(listOf(id, id), channel.claims)
                    assertTrue(id in channel.pending)
                    controller.close()
                }
            } finally { main { controller.close() } }
        }
    }

    @Test fun reconnectReclaimsInterruptedTransferAndIgnoresLateCallback() {
        MockWebServer().use { server ->
            val identity = HaRelayIdentityStore(instrumentation.targetContext)
            val raw = instrumentation.context.assets.open("wakeup.ics").use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
            val id = "c".repeat(32)
            val channel = Channel(server.url("/").toString()).apply {
                pending += id
                deferNextClaim = true
                claimResponse = { JSONObject().put("transfer_id", id).put("size", raw.size)
                    .put("filename", "schedule.ics").put("sha256", hash)
                    .put("signed_path", "/api/dormpanel/transfers/$id?authSig=test") }
            }
            server.enqueue(MockResponse().setBody(String(raw, Charsets.UTF_8)))
            val delivered = CountDownLatch(1)
            var previews = 0
            lateinit var controller: HaScheduleRelayController
            main {
                controller = HaScheduleRelayController(channel, identity, OkHttpClient())
                controller.attach { previews++; delivered.countDown() }
                assertEquals(listOf(id), channel.claims)
                channel.reconnect()
                channel.deferredClaim?.invoke(JSONObject().put("success", false))
            }
            try {
                await(delivered)
                main {
                    assertEquals(listOf(id, id), channel.claims)
                    assertEquals(1, previews)
                    controller.close()
                }
            } finally { main { controller.close() } }
        }
    }

    @Test fun duplicatePushPendingAndAcknowledgementRecovery() {
        MockWebServer().use { server ->
            val identity = HaRelayIdentityStore(instrumentation.targetContext)
            val raw = instrumentation.context.assets.open("wakeup.ics").use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
            val ids = listOf("a".repeat(32), "b".repeat(32))
            val channel = Channel(server.url("/").toString()).apply {
                pending.addAll(ids)
                claimResponse = { id -> JSONObject().put("transfer_id", id).put("size", raw.size)
                    .put("filename", "schedule.ics").put("sha256", hash)
                    .put("signed_path", "/api/dormpanel/transfers/$id?authSig=test") }
            }
            repeat(4) { server.enqueue(MockResponse().setBody(String(raw, Charsets.UTF_8))) }
            val delivered = mutableListOf<String>()
            val first = CountDownLatch(1)
            val second = CountDownLatch(1)
            lateinit var controller: HaScheduleRelayController
            main {
                controller = HaScheduleRelayController(channel, identity, OkHttpClient())
                controller.attach { delivered += it.transferId; if (delivered.size == 1) first.countDown() else second.countDown() }
                channel.push(ids[0], identity.installationId)
            }
            try {
                await(first)
                main {
                    assertEquals(listOf(ids[0]), delivered)
                    controller.detach()
                    var replayed = false
                    controller.attach {
                        if (!replayed) { assertEquals(ids[0], it.transferId); replayed = true }
                        else { delivered += it.transferId; second.countDown() }
                    }
                    channel.rejectTerminalAck = true
                    controller.resolve(ids[0], "imported")
                }
                // The next pending item retains the order returned by list_pending.
                await(second)
                main {
                    assertEquals(ids.take(2), channel.claims.take(2))
                    assertTrue(ids[0] in channel.pending)
                    controller.close()
                    controller.close()
                    assertEquals(0, channel.listenerCount)
                }
                // A process-owned controller rebuilt after restart discovers the
                // unacknowledged imported transfer from HA's pending list.
                val recovered = CountDownLatch(1)
                lateinit var restarted: HaScheduleRelayController
                main {
                    channel.pending.remove(ids[1])
                    restarted = HaScheduleRelayController(channel, identity, OkHttpClient())
                    restarted.attach { if (it.transferId == ids[0]) recovered.countDown() }
                }
                await(recovered)
                main {
                    channel.rejectTerminalAck = false
                    restarted.resolve(ids[0], "imported")
                    val priorClaims = channel.claims.size
                    channel.push(ids[0], identity.installationId)
                    channel.reconnect()
                    assertEquals(priorClaims, channel.claims.size)
                    restarted.close()
                }
            } finally { main { controller.close() } }
        }
    }
}
