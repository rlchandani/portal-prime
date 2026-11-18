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

class ControlCenterSheetLogicTest {
  private val now = 10_000_000L

  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>, changed: Long = 0L, user: String? = null) =
      CcEntity(id, state, mapOf(*attrs), changed, user)

  private val entities = listOf(
      e("light.floor_lamp", "on", "brightness" to 178, "friendly_name" to "Floor lamp"),
      e("light.ceiling", "off", "friendly_name" to "Ceiling"),
      e("media_player.tv", "playing", "media_title" to "Planet Coasts", "friendly_name" to "TV"),
      e("cover.blinds", "open", "current_position" to 80, "friendly_name" to "Blinds"),
      e("lock.front", "locked", "friendly_name" to "Front door"),
      e("switch.lamp_plug", "on", "friendly_name" to "Plug"),
      e("climate.downstairs", "heat", "temperature" to 70, "current_temperature" to 69, "hvac_action" to "heating"),
      e("binary_sensor.window", "on", "device_class" to "window", "friendly_name" to "Window"),
      e("binary_sensor.motion", "off", "device_class" to "motion"),
      e("camera.living", "idle"),
      e("person.alex", "Living room", "friendly_name" to "Alex", "user_id" to "u1"),
      e("person.mia", "home", "friendly_name" to "Mia"),
      e("scene.movie", "scening", "entity_id" to listOf("light.floor_lamp", "media_player.tv"), "friendly_name" to "Movie time"),
      e("scene.garden", "scening", "entity_id" to listOf("light.garden")),
  ).associateBy { it.id }

  private val room = CcRoom("living", "Living room", CcRect(0, 0, 10, 10),
      devices = listOf("light.ceiling", "light.floor_lamp", "lock.front", "media_player.tv", "cover.blinds", "switch.lamp_plug",
          "climate.downstairs", "binary_sensor.window", "binary_sensor.motion", "camera.living").map { CcPlaced(it, 0.5f, 0.5f) })
  private val plan = CcPlan(listOf(CcFloor("g", "Ground", listOf(room))))
  private val state = CcUiState(entities = entities, plan = plan, now = now, connection = CcConnection(CcPhase.LIVE))

  @Test fun devicesFollowTheSheetOrderAndSensorsAreRows() {
    val c = CcSheetLogic.contents(state, room)
    assertEquals(listOf("light.ceiling", "light.floor_lamp", "media_player.tv", "cover.blinds", "switch.lamp_plug", "lock.front"), c.tiles.map { it.id })
    assertEquals(setOf("binary_sensor.window", "binary_sensor.motion"), c.sensors.map { it.id }.toSet())
    assertEquals("camera.living", c.cameraId)
    assertEquals("climate.downstairs", c.climate?.id)
    assertEquals(0, c.hiddenCount)
  }

  @Test fun kidsModeHidesLocksAndCamerasAndCountsThem() {
    val kids = state.copy(settings = ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS))
    val c = CcSheetLogic.contents(kids, room)
    assertFalse(c.tiles.any { it.id == "lock.front" })
    assertNull(c.cameraId)
    assertEquals(2, c.hiddenCount)
  }

  @Test fun scenesTouchingTheRoomAndLightsOn() {
    assertEquals(listOf("scene.movie"), CcSheetLogic.scenes(state, room).map { it.id })
    assertEquals(listOf("light.floor_lamp"), CcSheetLogic.lightsOn(state, room).map { it.id })
  }

  @Test fun presenceUsesPeopleThenMotion() {
    assertEquals("Alex here" to true, CcSheetLogic.presence(state, room))
    val noPeople = state.copy(entities = entities - "person.alex")
    assertEquals("No one here" to false, CcSheetLogic.presence(noPeople, room))
    val bare = CcRoom("wc", "WC", CcRect(0, 0, 2, 2))
    assertNull(CcSheetLogic.presence(state, bare))
  }

  @Test fun whoPrefersChangedByThenUserId() {
    assertEquals("Sam", CcSheetLogic.who(state, e("lock.x", "locked", "changed_by" to "Sam")))
    assertEquals("Alex", CcSheetLogic.who(state, e("lock.x", "locked", user = "u1")))
    assertNull(CcSheetLogic.who(state, e("lock.x", "locked")))
  }

  @Test fun thermostatRoomsAreWhereItIsPlaced() {
    assertEquals(listOf("living"), CcSheetLogic.thermostatRooms(plan, "climate.downstairs").map { it.id })
    assertTrue(CcSheetLogic.thermostatRooms(null, "climate.downstairs").isEmpty())
  }

  @Test fun garageCameraPrefersTheSameRoom() {
    val garage = e("cover.garage_door", "open", "device_class" to "garage")
    val s = state.copy(entities = entities + (garage.id to garage) + ("camera.garage_cam" to e("camera.garage_cam", "idle")))
    assertEquals("camera.garage_cam", CcSheetLogic.garageCamera(s, garage))
    val placed = s.copy(plan = CcPlan(listOf(CcFloor("g", "G", listOf(room.copy(devices = room.devices + CcPlaced(garage.id, 0f, 0f)))))))
    assertEquals("camera.living", CcSheetLogic.garageCamera(placed, garage))
  }

  @Test fun tileStatusLinesMatchTheMock() {
    assertEquals("70%", CcSheetLogic.status(entities.getValue("light.floor_lamp"), now))
    assertEquals("Off", CcSheetLogic.status(entities.getValue("light.ceiling"), now))
    assertEquals("Playing · Planet Coasts", CcSheetLogic.status(entities.getValue("media_player.tv"), now))
    assertEquals("80% open", CcSheetLogic.status(entities.getValue("cover.blinds"), now))
    assertEquals("Locked", CcSheetLogic.status(entities.getValue("lock.front"), now))
    assertEquals("Heating to 70°", CcSheetLogic.status(entities.getValue("climate.downstairs"), now))
    assertEquals("Open", CcSheetLogic.status(entities.getValue("binary_sensor.window"), now))
    assertEquals("Unavailable", CcSheetLogic.status(e("light.x", "unavailable"), now))
    assertEquals("Open · 17 min", CcSheetLogic.status(e("cover.g", "open", "device_class" to "garage", changed = now - 17 * 60_000), now))
    assertEquals("Closing…", CcSheetLogic.status(e("cover.g", "closing", "device_class" to "garage"), now))
    assertEquals("Water on", CcSheetLogic.status(e("valve.main", "open"), now))
    assertEquals("WATER DETECTED", CcSheetLogic.status(e("binary_sensor.leak", "on", "device_class" to "moisture"), now))
    assertEquals("Done ✓", CcSheetLogic.status(e("sensor.washer_state", "idle"), now, done = true))
    assertEquals("21.5°C", CcSheetLogic.status(e("sensor.t", "21.5", "device_class" to "temperature", "unit_of_measurement" to "°C"), now))
    assertEquals("340 W", CcSheetLogic.status(e("sensor.p", "340", "device_class" to "power", "unit_of_measurement" to "W"), now))
    assertEquals("Paused", CcSheetLogic.status(e("media_player.s", "paused"), now))
    assertEquals("Off", CcSheetLogic.status(e("fan.f", "off"), now))
    assertEquals("50%", CcSheetLogic.status(e("fan.f", "on", "percentage" to 50), now))
  }

  @Test fun tonesAndPowerButtons() {
    assertEquals(CcTileTone.ON, CcSheetLogic.tone(entities.getValue("light.floor_lamp")))
    assertEquals(CcTileTone.LOCKED, CcSheetLogic.tone(entities.getValue("lock.front")))
    assertEquals(CcTileTone.WARN, CcSheetLogic.tone(e("lock.b", "unlocked")))
    assertEquals(CcTileTone.WARN, CcSheetLogic.tone(entities.getValue("binary_sensor.window")))
    assertEquals(CcTileTone.WARN, CcSheetLogic.tone(e("valve.main", "closed")))
    assertEquals(CcTileTone.NONE, CcSheetLogic.tone(entities.getValue("cover.blinds")))
    assertTrue(CcSheetLogic.hasPower(entities.getValue("media_player.tv")))
    assertFalse(CcSheetLogic.hasPower(entities.getValue("cover.blinds")))
  }

  @Test fun durationsAndLabels() {
    assertEquals("0 min", CcSheetLogic.duration(-5))
    assertEquals("2 h", CcSheetLogic.duration(120 * 60_000L))
    assertEquals("1 h 5 min", CcSheetLogic.duration(65 * 60_000L))
    assertEquals("Heat/Cool", CcSheetLogic.hvacLabel("heat_cool"))
    assertEquals("Fan", CcSheetLogic.hvacLabel("fan_only"))
    assertEquals("Jammed", CcSheetLogic.lockText("jammed"))
    assertEquals("moon", sceneIcon("Good night"))
    assertEquals("film", sceneIcon("Movie time"))
  }
}
