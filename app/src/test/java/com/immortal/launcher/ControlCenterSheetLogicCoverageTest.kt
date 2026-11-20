/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CcSheetLogic]: every tile tone and status line, and kids-mode hiding of the garage camera and thermostat. */
class ControlCenterSheetLogicCoverageTest {
  private val now = 10_000_000L
  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>, changed: Long = 0L) =
      CcEntity(id, state, mapOf(*attrs), changed)
  private fun status(id: String, state: String, vararg attrs: Pair<String, Any?>, done: Boolean = false, changed: Long = 0L) =
      CcSheetLogic.status(e(id, state, *attrs, changed = changed), now, done)

  private val kids = ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS)

  @Test fun tonesForEveryKind() {
    assertEquals(CcTileTone.NONE, CcSheetLogic.tone(e("switch.s", "off")))
    assertEquals(CcTileTone.ON, CcSheetLogic.tone(e("fan.f", "on")))
    assertEquals(CcTileTone.WARN, CcSheetLogic.tone(e("lock.l", "jammed")))
    assertEquals(CcTileTone.WARN, CcSheetLogic.tone(e("lock.l", "open")))
    assertEquals(CcTileTone.NONE, CcSheetLogic.tone(e("lock.l", "locking")))
    assertEquals(CcTileTone.WARN, CcSheetLogic.tone(e("cover.g", "open", "device_class" to "garage")))
    assertEquals(CcTileTone.NONE, CcSheetLogic.tone(e("cover.g", "closed", "device_class" to "garage")))
    assertEquals(CcTileTone.WARN, CcSheetLogic.tone(e("binary_sensor.d", "on", "device_class" to "door")))
    assertEquals(CcTileTone.NONE, CcSheetLogic.tone(e("binary_sensor.d", "off", "device_class" to "door")))
    assertEquals(CcTileTone.WARN, CcSheetLogic.tone(e("binary_sensor.smoke", "on", "device_class" to "smoke")))
    assertEquals(CcTileTone.NONE, CcSheetLogic.tone(e("binary_sensor.leak", "off", "device_class" to "moisture")))
    assertEquals(CcTileTone.NONE, CcSheetLogic.tone(e("valve.v", "open")))
    assertEquals(CcTileTone.WARN, CcSheetLogic.tone(e("switch.washer", "off"), done = true))
    assertEquals(CcTileTone.ON, CcSheetLogic.tone(e("switch.washer", "on")))
    assertEquals(CcTileTone.NONE, CcSheetLogic.tone(e("switch.washer", "off")))
    assertEquals(CcTileTone.NONE, CcSheetLogic.tone(e("sensor.t", "20", "device_class" to "temperature")))
  }

  @Test fun powerButtonsIncludeSwitchAppliancesOnly() {
    assertTrue(CcSheetLogic.hasPower(e("switch.washer", "on")))
    assertTrue(CcSheetLogic.hasPower(e("input_boolean.dishwasher", "on")))
    assertFalse(CcSheetLogic.hasPower(e("sensor.washer_power", "3")))
    assertTrue(CcSheetLogic.hasPower(e("light.a", "on")))
    assertTrue(CcSheetLogic.hasPower(e("switch.a", "on")))
    assertFalse(CcSheetLogic.hasPower(e("fan.a", "on")))
  }

  @Test fun statusLinesForTheRemainingKinds() {
    assertEquals("On", status("light.a", "on"))
    assertEquals("On", status("switch.a", "on"))
    assertEquals("Off", status("switch.a", "off"))
    assertEquals("On", status("fan.f", "on"))
    assertEquals("Shut off", status("valve.v", "closed"))
    assertEquals("Opening…", status("valve.v", "opening"))
    assertEquals("Closing…", status("valve.v", "closing"))
    assertEquals("Stuck", status("valve.v", "stuck"))
    assertEquals("Running", status("switch.washer", "on"))
    assertEquals("Off", status("binary_sensor.dryer_running", "off"))
    assertEquals("1.5 kW", status("sensor.oven_power", "1.5", "unit_of_measurement" to "kW"))
    assertEquals("Dry", status("binary_sensor.leak", "off", "device_class" to "moisture"))
    assertEquals("DETECTED", status("binary_sensor.smoke", "on", "device_class" to "smoke"))
    assertEquals("Clear", status("binary_sensor.co", "off", "device_class" to "carbon_monoxide"))
    assertEquals("Closed", status("binary_sensor.d", "off", "device_class" to "door"))
    assertEquals("Motion now", status("binary_sensor.m", "on", "device_class" to "motion"))
    assertEquals("Clear", status("binary_sensor.m", "off", "device_class" to "occupancy"))
    assertEquals("Low", status("binary_sensor.b", "on", "device_class" to "battery"))
    assertEquals("OK", status("binary_sensor.b", "off", "device_class" to "battery"))
    assertEquals("80%", status("sensor.b", "80", "device_class" to "battery", "unit_of_measurement" to "%"))
    assertEquals("Home", status("person.sam", "home"))
    assertEquals("42", status("sensor.count", "42"))
    assertEquals("Not home", status("device_tracker.p", "not_home"))
  }

  @Test fun garageStatusCoversEveryState() {
    val g = arrayOf<Pair<String, Any?>>("device_class" to "garage")
    assertEquals("Opening…", status("cover.g", "opening", *g))
    assertEquals("Closed", status("cover.g", "closed", *g))
    assertEquals("Closed", status("binary_sensor.g", "off", "device_class" to "garage_door"))
    assertEquals("Open", status("cover.g", "open", *g))
    assertEquals("Open", status("binary_sensor.g", "on", "device_class" to "garage_door", changed = now + 1))
    assertEquals("Open · 2 h 5 min", status("cover.g", "open", *g, changed = now - 125 * 60_000L))
    assertEquals("Stopped", status("cover.g", "stopped", *g))
  }

  @Test fun coverMediaAndClimateLines() {
    assertEquals("Opening…", status("cover.b", "opening"))
    assertEquals("Closing…", status("cover.b", "closing"))
    assertEquals("Closed", status("cover.b", "closed"))
    assertEquals("Open", status("cover.b", "open"))
    assertEquals("Stopped", status("cover.b", "stopped"))
    assertEquals("Paused · Song", status("media_player.s", "paused", "media_title" to "Song"))
    assertEquals("Playing", status("media_player.s", "playing", "media_title" to " "))
    assertEquals("Off", status("media_player.s", "off"))
    assertEquals("Off", status("media_player.s", "standby"))
    assertEquals("Idle", status("media_player.s", "idle"))
    assertEquals("Off", status("climate.c", "off", "temperature" to 20))
    assertEquals("Heat", status("climate.c", "heat"))
    assertEquals("Cooling to 22°", status("climate.c", "cool", "temperature" to 22, "hvac_action" to "cooling"))
    assertEquals("Set to 21°", status("climate.c", "auto", "temperature" to 20.6, "hvac_action" to "idle"))
  }

  @Test fun lockAndHvacWords() {
    assertEquals(listOf("Locked", "Unlocked", "Locking…", "Unlocking…", "Jammed", "Open", "Opening…", "Some state"),
        listOf("locked", "unlocked", "locking", "unlocking", "jammed", "open", "opening", "some_state").map(CcSheetLogic::lockText))
    assertEquals(listOf("Heat", "Cool", "Auto", "Off", "Dry", "Eco mode"),
        listOf("heat", "cool", "auto", "off", "dry", "eco_mode").map(CcSheetLogic::hvacLabel))
    assertEquals("", CcSheetLogic.pretty(""))
  }

  @Test fun coverNotesForBlindsAndOtherCovers() {
    assertTrue(CcSheetLogic.coverNote(e("cover.b", "open", "device_class" to "shade")).startsWith("These blinds"))
    assertTrue(CcSheetLogic.coverNote(e("cover.b", "open")).startsWith("This only"))
  }

  @Test fun valueTextUnits() {
    assertEquals("55%", CcSheetLogic.valueText(e("sensor.h", "55", "unit_of_measurement" to "%")))
    assertEquals("68°F", CcSheetLogic.valueText(e("sensor.t", "68", "unit_of_measurement" to "°F")))
    assertEquals("Charging", CcSheetLogic.valueText(e("sensor.s", "charging")))
  }

  @Test fun kidsModeHidesTheGarageCameraAndAHiddenThermostatIsNotTheRooms() {
    val door = e("cover.garage", "open", "device_class" to "garage")
    val cam = e("camera.garage_cam", "idle")
    val s = CcUiState(entities = listOf(door, cam).associateBy { it.id })
    assertEquals(cam.id, CcSheetLogic.garageCamera(s, door))
    assertNull(CcSheetLogic.garageCamera(s.copy(settings = kids), door))
    assertEquals(cam.id, CcSheetLogic.garageCamera(s.copy(settings = kids.copy(kidsHideCameras = false)), door))
    assertNull(CcSheetLogic.garageCamera(CcUiState(entities = mapOf(door.id to door)), door))
    // A placed camera id HA no longer knows is still offered (nothing to hide).
    val room = CcRoom("g", "Garage", CcRect(0, 0, 2, 2), devices = listOf(CcPlaced(door.id, 0f, 0f), CcPlaced("camera.gone", 0f, 0f)))
    val planned = CcUiState(entities = mapOf(door.id to door), plan = CcPlan(listOf(CcFloor("f", "F", listOf(room)))))
    assertEquals("camera.gone", CcSheetLogic.garageCamera(planned, door))
  }

  @Test fun climateForIsNullWithoutAThermostatInTheRoom() {
    val room = CcRoom("r", "Room", CcRect(0, 0, 2, 2), devices = listOf(CcPlaced("light.a", 0f, 0f)))
    val s = CcUiState(entities = mapOf("light.a" to e("light.a", "on")))
    assertNull(CcSheetLogic.climateFor(s, room))
    val c = CcSheetLogic.contents(s.copy(entities = s.entities), room.copy(devices = room.devices + CcPlaced("light.missing", 0f, 0f)))
    assertEquals(listOf("light.a"), c.tiles.map { it.id })
    assertNull(c.climate)
    assertEquals(0, c.hiddenCount)
  }

  @Test fun lightsOnSkipHiddenAndOffAndNonLights() {
    val room = CcRoom("r", "Room", CcRect(0, 0, 2, 2), devices = listOf("light.a", "light.b", "switch.c", "light.gone").map { CcPlaced(it, 0f, 0f) })
    val s = CcUiState(entities = listOf(e("light.a", "on"), e("light.b", "off"), e("switch.c", "on")).associateBy { it.id })
    assertEquals(listOf("light.a"), CcSheetLogic.lightsOn(s, room).map { it.id })
  }

  @Test fun presenceFallsBackToMotionWhenPeopleAreElsewhere() {
    val room = CcRoom("den", "Den", CcRect(0, 0, 2, 2), devices = listOf(CcPlaced("binary_sensor.m", 0f, 0f), CcPlaced("binary_sensor.dead", 0f, 0f)))
    val people = e("person.sam", "den", "friendly_name" to "Sam")
    val m = e("binary_sensor.m", "on", "device_class" to "motion")
    val dead = e("binary_sensor.dead", "unavailable", "device_class" to "motion")
    assertEquals("Sam here" to true, CcSheetLogic.presence(CcUiState(entities = listOf(people, m, dead).associateBy { it.id }), room))
    assertEquals("Motion now" to true, CcSheetLogic.presence(CcUiState(entities = listOf(m, dead).associateBy { it.id }), room))
    assertNull(CcSheetLogic.presence(CcUiState(entities = mapOf(dead.id to dead)), room))
  }
}
