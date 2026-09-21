package com.dormpanel.app.home

import com.dormpanel.app.ha.HaEntityStore
import com.dormpanel.app.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeControlTest {
    private fun store() = HaEntityStore().apply {
        areaRegistry(JSONArray("""[{"area_id":"b","name":"Bedroom","new_field":{}},{"area_id":"a","name":"Study"}]"""))
        deviceRegistry(JSONArray("""[{"id":"d","name_by_user":"Desk","name":"Factory","area_id":"a","future":true},{"id":"fallback","name":"Factory"},{"id":"id-only"}]"""))
        registry(JSONArray("""[{"entity_id":"light.override","device_id":"d","area_id":"b"},{"entity_id":"switch.desk","device_id":"d"},{"entity_id":"sensor.hidden","disabled_by":"user"},{"entity_id":"sensor.diagnostic","entity_category":"diagnostic"},{"entity_id":"sensor.config","entity_category":"config"}]"""))
        snapshot(JSONArray("""[
            {"entity_id":"light.override","state":"on","attributes":{}},
            {"entity_id":"switch.desk","state":"off","attributes":{}},
            {"entity_id":"scene.night","state":"2026-09-21","attributes":{}},
            {"entity_id":"sensor.hidden","state":"1","attributes":{}},
            {"entity_id":"sensor.diagnostic","state":"1","attributes":{}},
            {"entity_id":"sensor.config","state":"1","attributes":{}},
            {"entity_id":"sensor.temperature","state":"23.4","attributes":{"unit_of_measurement":"°C"}},
            {"entity_id":"script.simple","state":"off","attributes":{}},
            {"entity_id":"script.input","state":"off","attributes":{}},
            {"entity_id":"lock.door","state":"locked","attributes":{}}
        ]"""))
        scriptServices(JSONObject("""{"script":{"simple":{"fields":{}},"input":{"fields":{"message":{"required":true}}}}}"""))
    }
    @Test fun registriesResolveNamesAreasAndEntityOnlyGroups() {
        val store = store(); val home = store.home(true, store.normalized(true, "").lights)
        assertEquals(listOf("Bedroom", "Study"), home.areas.map { it.name })
        assertEquals(listOf("Desk", "Factory", "id-only"), home.devices.map { it.name })
        assertEquals("b", home.entities.single { it.kind == HomeKind.LIGHT }.areaId)
        assertEquals("a", home.entities.single { it.kind == HomeKind.SWITCH }.areaId)
        assertNull(home.entities.single { it.kind == HomeKind.SCENE }.areaId)
        assertEquals(6, home.entities.size)
        assertTrue(home.groups("").any { it.device == null && it.entities.any { e -> e.kind == HomeKind.SCENE } })
        assertTrue(home.entities.single { it.id == "ha:script.simple" }.actionable)
        assertFalse(home.entities.single { it.id == "ha:script.input" }.actionable)
        assertEquals("23.4", home.entities.single { it.kind == HomeKind.SENSOR }.value)
        val groups = home.groups(null)
        store.snapshot(JSONArray(store.entities.values.reversed().map { JSONObject().put("entity_id", it.id).put("state", it.value).put("attributes", it.attributes) }))
        assertEquals(groups, store.home(true, store.normalized(true, "").lights).groups(null))
    }
    @Test fun topologyChangesMoveInheritedEntitiesAndKeepExplicitOverrides() {
        val store = store()
        store.deviceRegistry(JSONArray("""[{"id":"d","name":"Renamed desk","area_id":"b"}]"""))
        var state = store.home(true, emptyMap())
        assertEquals("b", state.entities.single { it.kind == HomeKind.SWITCH }.areaId)
        assertEquals("b", state.entities.single { it.kind == HomeKind.LIGHT }.areaId)
        store.areaRegistry(JSONArray("""[{"area_id":"a","name":"Study"}]"""))
        state = store.home(true, emptyMap())
        assertNull(state.entities.single { it.kind == HomeKind.LIGHT }.areaId)
        assertNull(state.entities.single { it.kind == HomeKind.SWITCH }.areaId)
    }
    @Test fun hundredsOfEntitiesKeepStableIdentityAndOrdering() {
        val store = HaEntityStore()
        store.snapshot(JSONArray((499 downTo 0).map { JSONObject().put("entity_id", "sensor.$it").put("state", "$it").put("attributes", JSONObject()) }))
        val first = store.home(true, emptyMap()).groups(null)
        store.event(JSONObject("""{"entity_id":"sensor.12","new_state":{"entity_id":"sensor.12","state":"42","attributes":{}}}"""))
        val next = store.home(true, emptyMap()).groups(null)
        assertEquals(500, next.single().entities.size)
        assertEquals(first.single().entities.map { it.id }, next.single().entities.map { it.id })
        assertEquals(1, first.single().entities.zip(next.single().entities).count { it.first != it.second })
    }
    @Test fun disconnectPreservesValuesAndMarksStale() {
        val store = store(); val live = store.home(true, store.normalized(true, "").lights)
        val stale = store.home(false, store.normalized(false, "").lights)
        assertEquals(live.entities.map { it.value }, stale.entities.map { it.value })
        assertTrue(stale.entities.all { it.availability == Availability.STALE })
    }
    @Test fun selectionSurvivesReinflationButFallsBackWhenAreaDisappears() {
        val selection = HomeSelection(); val state = store().home(true, emptyMap())
        selection.areaId = "b"; repeat(3) { selection.reconcile(state) }; assertEquals("b", selection.areaId)
        selection.reconcile(state.copy(areas = emptyList())); assertNull(selection.areaId)
        selection.areaId = ""; selection.reconcile(state); assertEquals("", selection.areaId)
        selection.reconcile(HomeControlState()); assertNull(selection.areaId)
    }
    @Test fun demoSharesLightOwnerAndImplementsAllKinds() {
        val dashboard = FakeDashboardDataSource(); val demo: HomeControlSource = DemoHomeSource(dashboard)
        assertEquals(HomeKind.entries.toSet(), demo.homeState.entities.map { it.kind }.toSet())
        demo.activateEntity("desk"); assertFalse(dashboard.state.lights.getValue("desk").isOn)
        dashboard.setBrightness("desk", 20)
        assertEquals(20, demo.homeState.entities.single { it.id == "desk" }.light?.brightness)
        demo.activateEntity("switch:desk"); assertTrue(demo.homeState.entities.single { it.kind == HomeKind.SWITCH }.isOn)
        demo.activateEntity("scene:night"); assertTrue(dashboard.state.lights.values.none { it.isOn })
        demo.activateEntity("script:study"); assertTrue(dashboard.state.lights.getValue("desk").isOn)
    }
}
