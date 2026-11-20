package com.dormpanel.app.ha

import com.dormpanel.app.dashboard.card.*
import com.dormpanel.app.dashboard.catalog.cardCandidates
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SwitchDiscoveryTest {
    @Test fun groupsByDeviceAndExcludesAuxiliaryEntities() {
        val entries = JSONArray()
        val states = JSONArray()
        fun add(id: String, device: String? = null, category: String? = null, disabled: String? = null) {
            entries.put(JSONObject().put("entity_id", id).put("device_id", device)
                .put("entity_category", category).put("disabled_by", disabled).put("id", "entry-$id"))
            states.put(JSONObject().put("entity_id", id).put("state", "off"))
        }
        for (size in 1..4) for (channel in size downTo 1) add("switch.panel_${size}_$channel", "device-$size")
        add("light.indicator", "device-2")
        add("switch.led_configuration", "device-2", "config")
        add("switch.diagnostic", "device-3", "diagnostic")
        add("switch.disabled", "device-3", disabled = "user")
        add("switch.no_device")
        val store = HaEntityStore().apply { snapshot(states); registry(entries) }
        val candidates = store.switches("HA", true).cardCandidates()
        assertEquals(11, candidates.count { it.providerType == SWITCH_CARD })
        val groups = candidates.filter { it.providerType == SWITCH_GROUP_CARD }.associateBy { it.candidateId }
        assertEquals(4, groups.size)
        for (size in 1..3) {
            val config = SwitchCardConfiguration.decode(groups.getValue("switch-group:device-$size").configurationJson)!!
            assertEquals((1..size).map { "ha:switch.panel_${size}_$it" }, config.channels.map { it.identity.entityId })
            val reversed = config.copy(channels = config.channels.reversed())
            assertEquals(reversed, SwitchCardConfiguration.decode(reversed.encode()))
            assertTrue(config.isValid(SWITCH_GROUP_CARD))
            assertFalse(config.copy(channels = List(2) { config.channels.first() }).isValid(SWITCH_GROUP_CARD))
        }
        val oversized = SwitchCardConfiguration.decode(groups.getValue("switch-group:device-4").configurationJson)!!
        assertTrue(oversized.channels.isEmpty())
        assertFalse(oversized.isValid(SWITCH_GROUP_CARD))
        val independent = candidates.single { it.candidateId == "switch:ha:switch.no_device" }
        assertNull(SwitchCardConfiguration.decode(independent.configurationJson)!!.deviceId)

        val original = store.switches("HA", true).channels.getValue("ha:switch.panel_2_1")
        entries.objects().first { it.text("entity_id") == "switch.panel_2_1" }.put("name", "Renamed")
        store.registry(entries)
        assertEquals("Renamed", store.switches("HA", true).resolve("HA", original.identity)?.name)
        assertNull(store.switches("other", true).resolve("HA", original.identity))
        entries.objects().first { it.text("entity_id") == "switch.panel_2_1" }.put("device_id", "moved")
        store.registry(entries)
        assertNull(store.switches("HA", true).resolve("HA", original.identity))
        store.deviceRegistry(JSONArray().put(JSONObject().put("id", "device-1")))
        assertFalse("A removed device must not offer controls", "ha:switch.panel_2_2" in store.switches("HA", true).channels)
    }

    @Test fun compactRegistryFiltersCategoriesAndUnregisteredIdentityCannotBeReused() {
        val switchState = JSONObject("""{"entity_id":"switch.outlet","state":"off"}""")
        val states = JSONArray().put(switchState).put(JSONObject("""{"entity_id":"switch.led","state":"on"}"""))
        val store = HaEntityStore().apply {
            snapshot(states)
            registry(JSONObject("""{"entity_categories":{"0":"config"},"entities":[{"ei":"switch.led","ec":0}]}"""))
        }
        assertTrue(store.switches("HA", false).channels.isEmpty())
        val identity = store.switches("HA", true).channels.values.single().identity
        assertEquals("ha:switch.outlet", identity.entityId)
        store.event(JSONObject().put("entity_id", "switch.outlet"))
        store.event(JSONObject().put("entity_id", "switch.outlet").put("new_state", switchState))
        assertNull(store.switches("HA", true).resolve("HA", identity))
        val next = store.switches("HA", true).channels.values.single().identity
        store.snapshot(states)
        assertNull(store.switches("HA", true).resolve("HA", next))
    }
}
