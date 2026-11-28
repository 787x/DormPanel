package com.dormpanel.app.ha

import com.dormpanel.app.dashboard.catalog.*
import com.dormpanel.app.data.Availability
import com.dormpanel.app.home.*
import com.dormpanel.app.home.HomeDeviceState.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HaDeviceControlTest {
    private val store = HaEntityStore().apply {
        homeSourceId = "server"
        temperatureUnit = "°F"
    }

    private fun entity(domain: String, state: String, attributes: String = "{}", connected: Boolean = true) =
        checkNotNull(store.homeDevice(HaEntity("$domain.room", state, JSONObject(attributes)), connected))

    private fun HomeEntity.expect(command: HomeCommand, service: String, vararg data: Pair<String, Any>) {
        val domain = kind.name.lowercase()
        assertEquals(DeviceService(domain, service, "$domain.room", data.toMap()), deviceService(command))
    }

    @Test fun climateUsesModeFeaturesAndBothTemperaturePayloadForms() {
        val attrs = """{"supported_features":59,"hvac_modes":["off","auto","heat","heat_cool","unsupported"],
            "hvac_action":"heating","current_temperature":68.5,"temperature":70,"target_temp_low":66,"target_temp_high":74,
            "min_temp":60,"max_temp":80,"target_temp_step":0.5,"fan_modes":["low","high"],"fan_mode":"low",
            "swing_modes":["off","on"],"preset_modes":["eco",12]}"""
        val heat = entity("climate", "heat", attrs)
        val controls = heat.controls as Climate
        assertTrue(controls.active)
        assertEquals("heating", controls.action)
        assertEquals(68.5, controls.current)
        assertEquals(70.0, controls.target)
        assertEquals("°F", controls.unit)
        heat.expect(HomeCommand.Power(false), "set_hvac_mode", "hvac_mode" to "off")
        heat.expect(HomeCommand.Select(DeviceOption.HVAC, "heat_cool"), "set_hvac_mode", "hvac_mode" to "heat_cool")
        heat.expect(HomeCommand.Select(DeviceOption.FAN, "high"), "set_fan_mode", "fan_mode" to "high")
        heat.expect(HomeCommand.Select(DeviceOption.SWING, "on"), "set_swing_mode", "swing_mode" to "on")
        heat.expect(HomeCommand.Select(DeviceOption.PRESET, "eco"), "set_preset_mode", "preset_mode" to "eco")
        heat.expect(HomeCommand.Temperature(71.5), "set_temperature", "temperature" to 71.5)
        assertNull(heat.deviceService(HomeCommand.Select(DeviceOption.HVAC, "cool")))
        assertNull(heat.deviceService(HomeCommand.Select(DeviceOption.PRESET, "12")))
        listOf(81.0, Double.NaN, 65.25).forEach { assertNull(heat.deviceService(HomeCommand.Temperature(it))) }

        val range = entity("climate", "heat_cool", attrs)
        assertEquals(66.0, (range.controls as Climate).low)
        assertEquals(74.0, range.controls.high)
        range.expect(HomeCommand.TemperatureRange(66.0, 75.5), "set_temperature", "target_temp_low" to 66.0, "target_temp_high" to 75.5)
        assertNull(range.deviceService(HomeCommand.TemperatureRange(75.5, 66.0)))
        assertNull(range.deviceService(HomeCommand.Temperature(70.0)))
        val auto = entity("climate", "auto", attrs)
        assertNull(auto.deviceService(HomeCommand.Temperature(70.0)))
        auto.expect(HomeCommand.TemperatureRange(66.0, 75.5), "set_temperature",
            "target_temp_low" to 66.0, "target_temp_high" to 75.5)
        entity("climate", "cool", attrs).expect(HomeCommand.Temperature(71.5), "set_temperature", "temperature" to 71.5)
        assertNull(entity("climate", "off", attrs).deviceService(HomeCommand.Power(true)))
        entity("climate", "off", """{"hvac_modes":["off","heat"]}""")
            .expect(HomeCommand.Power(true), "set_hvac_mode", "hvac_mode" to "heat")
        entity("climate", "off", """{"supported_features":256}""").expect(HomeCommand.Power(true), "turn_on")
    }

    @Test fun autoUsesTheOnlyAdvertisedTargetFormEvenWithoutAReading() {
        val single = HomeCommand.Temperature(71.5)
        val range = HomeCommand.TemperatureRange(66.0, 75.5)
        val limits = """"min_temp":60,"max_temp":80,"target_temp_step":0.5"""
        listOf(""" "temperature":70 """, """ "temperature":null """).forEach { reading ->
            val auto = entity("climate", "auto", """{"supported_features":1,$limits,$reading}""")
            auto.expect(single, "set_temperature", "temperature" to 71.5)
            assertNull(auto.deviceService(range))
        }
        listOf(
            """ "target_temp_low":66,"target_temp_high":75 """,
            """ "target_temp_low":null,"target_temp_high":null """,
        ).forEach { readings ->
            val auto = entity("climate", "auto", """{"supported_features":2,$limits,$readings}""")
            auto.expect(range, "set_temperature", "target_temp_low" to 66.0, "target_temp_high" to 75.5)
            assertNull(auto.deviceService(single))
            assertNull(auto.deviceService(HomeCommand.TemperatureRange(71.25, 75.5)))
            assertNull(auto.deviceService(HomeCommand.TemperatureRange(66.0, 81.0)))
            assertNull(auto.deviceService(HomeCommand.TemperatureRange(75.5, 66.0)))
        }
    }

    @Test fun autoWithBothCapabilitiesUsesValidReadingsToSelectOneTargetForm() {
        fun auto(readings: String) = entity("climate", "auto",
            """{"supported_features":3,"min_temp":60,"max_temp":80,$readings}""")
        val single = HomeCommand.Temperature(71.0)
        val range = HomeCommand.TemperatureRange(66.0, 75.0)
        val withSingle = auto(""" "temperature":70,"target_temp_low":null,"target_temp_high":null """)
        withSingle.expect(single, "set_temperature", "temperature" to 71.0)
        assertNull(withSingle.deviceService(range))
        val withRange = auto(""" "temperature":null,"target_temp_low":66,"target_temp_high":74 """)
        withRange.expect(range, "set_temperature", "target_temp_low" to 66.0, "target_temp_high" to 75.0)
        assertNull(withRange.deviceService(single))
        val invalidRange = auto(""" "temperature":70,"target_temp_low":58,"target_temp_high":74 """)
        invalidRange.expect(single, "set_temperature", "temperature" to 71.0)
        val ambiguous = auto(""" "temperature":null,"target_temp_low":null,"target_temp_high":null """)
        assertNull(ambiguous.deviceService(single))
        assertNull(ambiguous.deviceService(range))
    }

    @Test fun autoRejectsUnsupportedTemperatureAndMalformedLimits() {
        val single = HomeCommand.Temperature(71.0)
        val range = HomeCommand.TemperatureRange(66.0, 75.0)
        for (attrs in listOf(
            """{"min_temp":60,"max_temp":80,"temperature":70}""",
            """{"supported_features":1,"min_temp":"60","max_temp":80}""",
            """{"supported_features":1,"min_temp":80,"max_temp":60}""",
            """{"supported_features":2,"min_temp":60,"max_temp":80,"target_temp_step":0}""",
        )) {
            val auto = entity("climate", "auto", attrs)
            assertNull(auto.deviceService(single))
            assertNull(auto.deviceService(range))
        }
    }

    @Test fun fanSeparatesSpeedPresetAndPowerCapabilities() {
        val fan = entity("fan", "on", """{"supported_features":57,"percentage":66,"percentage_step":33.333333333333336,
            "preset_mode":"sleep","preset_modes":["normal","sleep"]}""")
        val controls = fan.controls as Fan
        assertEquals(66, controls.percentage)
        assertEquals("sleep", controls.preset.current)
        assertEquals(listOf(33, 66, 100), controls.speeds)
        fan.expect(HomeCommand.Speed(33), "set_percentage", "percentage" to 33)
        fan.expect(HomeCommand.Speed(0), "turn_off")
        fan.expect(HomeCommand.Power(false), "turn_off")
        fan.expect(HomeCommand.Select(DeviceOption.PRESET, "normal"), "set_preset_mode", "preset_mode" to "normal")
        listOf(-1, 101, 50).forEach { assertNull(fan.deviceService(HomeCommand.Speed(it))) }
        val minimal = entity("fan", "off", """{"supported_features":32,"percentage_step":25,"preset_modes":["sleep"]}""")
        minimal.expect(HomeCommand.Power(true), "turn_on")
        assertNull(minimal.deviceService(HomeCommand.Power(false)))
        assertNull(minimal.deviceService(HomeCommand.Speed(25)))
        assertNull(minimal.deviceService(HomeCommand.Select(DeviceOption.PRESET, "sleep")))
    }

    @Test fun zeroSpeedRequiresSpeedControlAndFallsBackWithoutTurnOff() {
        val zero = HomeCommand.Speed(0)
        val speedOnly = """{"supported_features":1,"percentage_step":25}"""
        entity("fan", "on", speedOnly).expect(zero, "set_percentage", "percentage" to 0)
        assertNull(entity("fan", "on", """{"supported_features":16,"percentage_step":25}""").deviceService(zero))
        assertNull(entity("fan", "on", """{"supported_features":17,"percentage_step":0}""").deviceService(zero))
        assertNull(entity("fan", "unavailable", speedOnly).deviceService(zero))
        assertNull(entity("fan", "on", speedOnly, connected = false).deviceService(zero))
    }

    @Test fun humidifierCoverAndVirtualSwitchRejectUnsupportedOrUnknownValues() {
        val humidifier = entity("humidifier", "on", """{"supported_features":1,"humidity":55,"current_humidity":49,
            "min_humidity":30,"max_humidity":70,"available_modes":["eco"]}""")
        assertEquals(49.0, (humidifier.controls as Humidifier).current)
        humidifier.expect(HomeCommand.Power(false), "turn_off")
        humidifier.expect(HomeCommand.Humidity(60.0), "set_humidity", "humidity" to 60.0)
        humidifier.expect(HomeCommand.Select(DeviceOption.HUMIDIFIER_MODE, "eco"), "set_mode", "mode" to "eco")
        assertNull(humidifier.deviceService(HomeCommand.Humidity(71.0)))
        assertNull(entity("humidifier", "off", """{"min_humidity":"30","max_humidity":70}""")
            .deviceService(HomeCommand.Humidity(45.0)))

        val cover = entity("cover", "opening", """{"current_position":45,"supported_features":13}""")
        assertEquals(CoverPhase.OPENING, (cover.controls as Cover).phase)
        assertEquals(45, cover.controls.position)
        cover.expect(HomeCommand.MoveCover(CoverOperation.OPEN), "open_cover")
        cover.expect(HomeCommand.MoveCover(CoverOperation.STOP), "stop_cover")
        cover.expect(HomeCommand.Position(99), "set_cover_position", "position" to 99)
        assertNull(cover.deviceService(HomeCommand.Position(101)))
        assertNull(cover.deviceService(HomeCommand.MoveCover(CoverOperation.CLOSE)))
        val closeOnly = entity("cover", "open", """{"supported_features":2,"current_position":101}""")
        assertNull((closeOnly.controls as Cover).position)
        closeOnly.expect(HomeCommand.MoveCover(CoverOperation.CLOSE), "close_cover")
        assertNull(closeOnly.deviceService(HomeCommand.Position(100)))
        assertNull(entity("cover", "unavailable", """{"supported_features":15}""").deviceService(HomeCommand.Position(50)))

        entity("input_boolean", "off").expect(HomeCommand.Power(true), "turn_on")
        entity("input_boolean", "on").expect(HomeCommand.Power(false), "turn_off")
        assertNull(entity("input_boolean", "unknown").deviceService(HomeCommand.Power(true)))
        assertNull(entity("input_boolean", "on", connected = false).deviceService(HomeCommand.Power(false)))
    }

    @Test fun discoveryUsesRegistryAndCardsRequireTheExactSourceAndEntry() {
        store.areaRegistry(JSONArray("""[{"area_id":"bedroom","name":"Bedroom"}]"""))
        store.deviceRegistry(JSONArray("""[{"id":"a","area_id":"bedroom"}]"""))
        store.registry(JSONArray("""[
            {"entity_id":"climate.room","device_id":"a","id":"climate-entry"},
            {"entity_id":"fan.room","area_id":"unknown-area","id":"fan-entry"},
            {"entity_id":"humidifier.hidden","disabled_by":"user"},
            {"entity_id":"cover.diag","entity_category":"diagnostic"},
            {"entity_id":"input_boolean.cfg","entity_category":"config"},
            {"entity_id":"input_boolean.room","id":"helper-entry"}
        ]"""))
        store.snapshot(JSONArray("""[
            {"entity_id":"climate.room","state":"cool"},
            {"entity_id":"fan.room","state":"on","attributes":{"supported_features":"57","percentage":200,"percentage_step":0}},
            {"entity_id":"humidifier.room","state":"off"},{"entity_id":"humidifier.hidden","state":"off"},
            {"entity_id":"cover.room","state":"open"},{"entity_id":"cover.diag","state":"closed"},
            {"entity_id":"input_boolean.room","state":"off"},{"entity_id":"input_boolean.cfg","state":"off"}
        ]"""))
        val state = store.home(true, emptyMap())
        assertEquals(5, state.entities.size)
        assertEquals("bedroom", state.entities.single { it.kind == HomeKind.CLIMATE }.areaId)
        val fan = state.entities.single { it.kind == HomeKind.FAN }
        assertNull(fan.areaId)
        assertNull((fan.controls as Fan).percentage)
        assertTrue(fan.controls.speeds.isEmpty())
        assertNull(fan.controls.primaryCommand())
        assertTrue(store.home(false, emptyMap()).entities.all { it.availability == Availability.STALE })
        val candidates = controlCardCandidates(state.entities)
        assertEquals(setOf("climate", "fan"), candidates.map { it.providerType }.toSet())
        val fanCard = candidates.single { it.providerType == "fan" }
        val target = checkNotNull(controlCardTarget(fanCard.configurationJson, HomeKind.FAN))
        assertEquals(fan, target.resolve(state.entities, HomeKind.FAN))
        assertNull(controlCardTarget(fanCard.configurationJson, HomeKind.CLIMATE))
        assertNull(target.copy(sourceId = "other").resolve(state.entities, HomeKind.FAN))
        assertNull(target.copy(identity = target.identity.copy(entryId = "other")).resolve(state.entities, HomeKind.FAN))
        store.event(JSONObject("""{"entity_id":"fan.room","new_state":null}"""))
        assertNull(target.resolve(store.home(true, emptyMap()).entities, HomeKind.FAN))
        assertEquals("switch", store.switches("server").cardCandidates().single().providerType)
    }
}
