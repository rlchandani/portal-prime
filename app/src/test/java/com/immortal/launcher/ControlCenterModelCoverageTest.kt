/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CcEntity] attribute reads from untrusted HA attributes, [CcKind] mapping, [CcRegistry] and [CcUiState] derivations. */
class ControlCenterModelCoverageTest {
  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>) = CcEntity(id, state, mapOf(*attrs))

  @Test fun wrongTypedAttributesReadAsAbsent() {
    val junk = e("light.x", "on", "friendly_name" to 5, "device_class" to 1, "brightness" to "high", "rgb_color" to listOf(1, "g", 3),
        "color_temp_kelvin" to "warm", "supported_color_modes" to "rgb", "temperature" to "hot", "current_position" to "half",
        "volume_level" to "loud", "supported_features" to "all", "code_format" to 4, "changed_by" to " ", "entity_id" to "scene.a")
    assertEquals("X", junk.name)
    assertNull(junk.deviceClass)
    assertNull(junk.brightnessPct)
    assertNull(junk.rgb)
    assertNull(junk.colorTempK)
    assertTrue(junk.colorModes.isEmpty())
    assertNull(junk.targetTemp)
    assertNull(junk.volume)
    assertEquals(0, junk.supportedFeatures)
    assertNull(junk.codeFormat)
    assertNull(junk.changedBy)
    assertTrue(junk.members.isEmpty())
    assertNull(junk.position)
    assertEquals("a non-number position falls back to the state", 100, e("cover.c", "open", "current_position" to "half").position)
  }

  @Test fun rgbNeedsThreeNumbersAndIsClamped() {
    assertNull(e("light.x", "on", "rgb_color" to listOf(1, 2)).rgb)
    assertNull(e("light.x", "on", "rgb_color" to listOf(null, 2, 3)).rgb)
    assertNull(e("light.x", "on", "rgb_color" to listOf(1, 2, null)).rgb)
    assertEquals(0xFF00FF, e("light.x", "on", "rgb_color" to listOf(300, -4, 999)).rgb)
  }

  @Test fun colourAndDimmingCapabilities() {
    val hs = e("light.x", "on", "supported_color_modes" to listOf("hs"), "min_color_temp_kelvin" to 2000)
    assertTrue(hs.supportsColor)
    assertTrue(hs.supportsColorTemp)
    assertTrue(hs.dimmable)
    assertFalse(e("light.x", "on", "supported_color_modes" to listOf("hs")).supportsColorTemp)
    val onoff = e("light.x", "on", "supported_color_modes" to listOf("onoff"))
    assertFalse(onoff.dimmable)
    assertTrue(e("light.x", "on", "brightness" to 3).dimmable)
    assertEquals(CcEntity.DEFAULT_MIN_K, onoff.minKelvin)
    assertEquals(CcEntity.DEFAULT_MAX_K, onoff.maxKelvin)
  }

  @Test fun temperatureRangesGuessFahrenheitFromTheSetpoint() {
    assertEquals(CcEntity.DEFAULT_MIN_TEMP_F, e("climate.c", "heat", "temperature" to 68).minTemp, 0.0)
    assertEquals(CcEntity.DEFAULT_MAX_TEMP_F, e("climate.c", "heat", "current_temperature" to 70).maxTemp, 0.0)
    assertEquals(CcEntity.DEFAULT_MIN_TEMP, e("climate.c", "heat", "temperature" to 21).minTemp, 0.0)
    assertEquals(CcEntity.DEFAULT_MAX_TEMP, e("climate.c", "heat").maxTemp, 0.0)
    assertEquals(55, e("climate.c", "heat", "humidity" to 55).humidity)
    assertEquals(40, e("climate.c", "heat", "current_humidity" to 40, "humidity" to 55).humidity)
    assertEquals(listOf("heat"), e("climate.c", "heat", "hvac_modes" to listOf("heat", 3)).hvacModes)
  }

  @Test fun openStatesPerDomain() {
    assertTrue(e("cover.c", "closing").isOpen)
    assertFalse(e("cover.c", "closed").isOpen)
    assertTrue(e("valve.v", "opening").isOpen)
    assertFalse(e("valve.v", "closing").isOpen)
    assertTrue(e("lock.l", "unlocking").isOpen)
    assertFalse(e("lock.l", "locked").isOpen)
    assertTrue(e("binary_sensor.g", "on", "device_class" to "garage").isOpen)
    assertFalse(e("binary_sensor.m", "on", "device_class" to "motion").isOpen)
    assertFalse(e("light.l", "on").isOpen)
    assertNull(e("cover.c", "stopped").position)
    assertEquals(0, e("cover.c", "closed").position)
  }

  @Test fun namesAndAvailability() {
    assertEquals("Front door", e("lock.front_door", "x", "friendly_name" to " ").name)
    assertEquals("", e("nodomain", "x").domain)
    assertTrue(e("light.a", "").unavailable)
    assertTrue(e("light.a", "unknown").unavailable)
    assertEquals("Song", e("media_player.m", "playing", "media_album_name" to "Song").mediaArtist)
    assertEquals("Artist", e("media_player.m", "playing", "media_artist" to "Artist", "media_album_name" to "Song").mediaArtist)
    assertEquals(9, e("sensor.b", "9", "battery_level" to 9.7).battery)
  }

  @Test fun kindsForEveryDomainAndClass() {
    val cases = mapOf(
        e("input_boolean.dryer_on", "on") to CcKind.APPLIANCE, e("script.s", "off") to CcKind.SCRIPT, e("person.p", "home") to CcKind.PERSON,
        e("camera.c", "idle") to CcKind.CAMERA, e("weather.w", "sunny") to CcKind.OTHER,
        e("binary_sensor.b", "on", "device_class" to "battery") to CcKind.BATTERY,
        e("binary_sensor.g", "on", "device_class" to "gas") to CcKind.SMOKE,
        e("binary_sensor.p", "on", "device_class" to "presence") to CcKind.MOTION,
        e("binary_sensor.oven_door_hint", "on") to CcKind.APPLIANCE, e("binary_sensor.x", "on") to CcKind.SENSOR,
        e("sensor.h", "50", "device_class" to "humidity") to CcKind.HUMIDITY,
        e("sensor.e", "5", "device_class" to "energy") to CcKind.ENERGY,
        e("sensor.b", "5", "device_class" to "battery") to CcKind.BATTERY,
        e("sensor.washing_left", "5") to CcKind.APPLIANCE, e("sensor.x", "5") to CcKind.SENSOR)
    cases.forEach { (entity, kind) -> assertEquals(entity.id, kind, CcKind.of(entity)) }
    assertTrue(CcKind.APPLIANCE.controllable)
    assertFalse(CcKind.SENSOR.controllable)
  }

  @Test fun connectionReasonsForEveryPhase() {
    assertTrue(CcConnection(CcPhase.LIVE).canControl)
    assertNull(CcConnection(CcPhase.LIVE).disabledReason)
    CcPhase.entries.filter { it != CcPhase.LIVE }.forEach {
      assertFalse(it.name, CcConnection(it).canControl)
      assertTrue(it.name, CcConnection(it).disabledReason!!.isNotBlank())
    }
  }

  @Test fun registryLookups() {
    val r = CcRegistry(areas = listOf(CcHaArea("k", "Kitchen", "g"), CcHaArea("o", "Office", null)),
        entityArea = mapOf("light.a" to "k", "light.b" to "missing", "binary_sensor.m" to "k"),
        entityClass = mapOf("binary_sensor.d" to "door"), fetchedClass = mapOf("binary_sensor.w" to "window", "binary_sensor.d" to "x"),
        entities = setOf("binary_sensor.d", "binary_sensor.z", "light.a"), diagnostics = setOf("binary_sensor.t"))
    assertEquals("Kitchen", r.areaName("light.a"))
    assertNull(r.areaName("light.b"))
    assertNull(r.areaName("light.none"))
    assertEquals("door", r.classOf("binary_sensor.d"))
    assertEquals("window", r.classOf("binary_sensor.w"))
    assertEquals(listOf("binary_sensor.t", "binary_sensor.z"), r.classless)
    assertEquals(mapOf("k" to listOf("binary_sensor.m")), r.areaSensors)
    assertEquals(listOf("Office"), r.areasOn(null).map { it.name })
  }

  // --- the UI snapshot ----------------------------------------------------------------------

  @Test fun equalityIgnoresTheEntityMapButNotItsVersionOrMinute() {
    val a = CcUiState(entities = mapOf("light.a" to e("light.a", "on")), entitiesVersion = 1, now = 60_000L)
    val sameMinute = a.copy(entities = emptyMap(), now = 119_999L)
    assertEquals(a, sameMinute)
    assertEquals(a.hashCode(), sameMinute.hashCode())
    assertNotEquals(a, a.copy(now = 120_000L))
    assertNotEquals(a, a.copy(entitiesVersion = 2))
    assertNotEquals(a, a.copy(debug = "Dry run"))
    assertNotEquals(a, a.copy(registry = CcRegistry()))
    assertFalse(a.equals("state"))
    assertTrue(a == a)
  }

  @Test fun readyNeedsAPlanAndDataOrAnAnswer() {
    assertFalse(CcUiState().ready)
    assertFalse(CcUiState(plan = CcPlan.EMPTY).ready)
    assertTrue(CcUiState(plan = CcPlan.EMPTY, connection = CcConnection(CcPhase.UNREACHABLE)).ready)
    assertTrue(CcUiState(plan = CcPlan.EMPTY, entities = mapOf("light.a" to e("light.a", "on"))).ready)
  }

  @Test fun kidsModeHidesTheAlarmPanelAndHiddenAlertsButKeepsCriticals() {
    val panel = e("alarm_control_panel.home", "disarmed")
    val garage = CcEntity("cover.garage", "open", mapOf("device_class" to "garage"), lastChanged = 1L)
    val leak = CcEntity("binary_sensor.leak", "on", mapOf("device_class" to "moisture"), lastChanged = 1L)
    val kids = ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS)
    val s = CcUiState(entities = listOf(panel, garage, leak).associateBy { it.id }, now = 60 * 60_000L)
    assertEquals(panel, s.alarm)
    assertTrue(s.alerts().any { it.entityId == garage.id })
    val k = s.copy(settings = kids)
    assertNull(k.alarm)
    assertFalse(k.alerts().any { it.entityId == garage.id })
    assertTrue(k.alerts().any { it.entityId == leak.id })
    val dismissed = s.copy(dismissed = s.alerts().associate { it.id to Long.MAX_VALUE })
    assertTrue(dismissed.alerts().isEmpty())
  }

  @Test fun fahrenheitFromATemperatureSensorOrTheWeather() {
    assertTrue(CcUiState(entities = mapOf("sensor.t" to e("sensor.t", "70", "device_class" to "temperature", "unit_of_measurement" to "°F"))).fahrenheit)
    assertFalse(CcUiState(entities = mapOf("sensor.t" to e("sensor.t", "21", "device_class" to "temperature", "unit_of_measurement" to "°C"))).fahrenheit)
    assertTrue(CcUiState(entities = mapOf("weather.w" to e("weather.w", "sunny", "temperature_unit" to "°F"))).fahrenheit)
    assertFalse(CcUiState(entities = mapOf("weather.w" to e("weather.w", "sunny"))).fahrenheit)
    assertFalse(CcUiState().fahrenheit)
    val w = e("weather.w", "sunny")
    assertEquals(w, CcUiState(entities = mapOf(w.id to w)).warm().weather)
  }

  @Test fun sceneRunProgressAndRoomOrder() {
    val run = CcSceneRun("scene.a", "A", listOf("r"), startedAt = 1_000L, durationMs = 4_000L)
    assertEquals(0f, run.progress(0L), 0f)
    assertEquals(0.5f, run.progress(3_000L), 1e-6f)
    assertEquals(1f, run.progress(99_000L), 0f)
    assertFalse(run.finished(4_999L))
    assertTrue(run.finished(5_000L))
    assertEquals(CcSceneRun.MIN_MS, CcSceneRun.duration(1))
    assertEquals(CcSceneRun.MAX_MS, CcSceneRun.duration(100))
    assertTrue(CcSceneRun.rooms(null, listOf("light.a")).isEmpty())
    val right = CcRoom("right", "R", CcRect(5, 0, 2, 2), devices = listOf(CcPlaced("light.a", 0f, 0f)))
    val left = CcRoom("left", "L", CcRect(0, 0, 2, 2), devices = listOf(CcPlaced("light.b", 0f, 0f)))
    val none = CcRoom("none", "N", CcRect(9, 0, 2, 2))
    assertEquals(listOf("left", "right"), CcSceneRun.rooms(CcPlan(listOf(CcFloor("g", "G", listOf(right, none, left)))), listOf("light.a", "light.b")))
  }
}
