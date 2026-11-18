/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterParserTest {
  private val light = """{"entity_id":"light.kitchen","state":"on","attributes":{"friendly_name":"Kitchen","brightness":128,
    "rgb_color":[255,120,0],"supported_color_modes":["color_temp","hs"],"color_temp_kelvin":2700},
    "last_changed":"2026-11-17T19:25:03.123456+00:00","context":{"id":"x","user_id":"u1"}}"""

  @Test fun fullState_parsesTypedAttributes() {
    val e = CcEntityParser.fromFull(JSONObject(light))!!
    assertEquals("light", e.domain)
    assertEquals("Kitchen", e.name)
    assertEquals(50, e.brightnessPct)
    assertEquals(0xFF7800, e.rgb)
    assertEquals(2700, e.colorTempK)
    assertTrue(e.supportsColor)
    assertTrue(e.isOn)
    assertEquals("u1", e.userId)
    assertEquals(1_794_943_503_123L, e.lastChanged)
  }

  @Test fun fullState_rejectsBadIds() {
    assertNull(CcEntityParser.fromFull(JSONObject("""{"entity_id":"nodot","state":"on"}""")))
  }

  @Test fun isoMs_handlesZuluAndNoFraction() {
    assertEquals(1_794_943_503_000L, CcEntityParser.isoMs("2026-11-17T19:25:03Z"))
    assertEquals(1_794_943_503_000L, CcEntityParser.isoMs("2026-11-17T19:25:03+00:00"))
    assertEquals(0L, CcEntityParser.isoMs("garbage"))
    assertEquals(0L, CcEntityParser.isoMs(null))
  }

  @Test fun compressedEvent_addsChangesAndRemoves() {
    val add = JSONObject("""{"a":{"cover.garage":{"s":"closed","a":{"device_class":"garage","friendly_name":"Garage door"},"c":"ctx","lc":1763407503.5},
      "lock.front":{"s":"locked","a":{"changed_by":"Keypad"},"c":{"id":"c","user_id":"u2"},"lc":1763407000}}}""")
    val (m1, t1) = CcEntityParser.applyEvent(emptyMap(), add)
    assertEquals(setOf("cover.garage", "lock.front"), t1)
    assertEquals(1_763_407_503_500L, m1["cover.garage"]!!.lastChanged)
    assertEquals("u2", m1["lock.front"]!!.userId)
    assertNull(m1["cover.garage"]!!.userId)
    val diff = JSONObject("""{"c":{"cover.garage":{"+":{"s":"open","lc":1763407600,"a":{"current_position":100}}},
      "lock.front":{"-":{"a":["changed_by"]}}},"r":["sensor.gone"]}""")
    val (m2, t2) = CcEntityParser.applyEvent(m1, diff)
    assertEquals(setOf("cover.garage", "lock.front"), t2)
    val g = m2["cover.garage"]!!
    assertEquals("open", g.state)
    assertEquals(100, g.position)
    assertEquals("garage", g.deviceClass)
    assertEquals(CcKind.GARAGE, CcKind.of(g))
    assertNull(m2["lock.front"]!!.changedBy)
    val (m3, t3) = CcEntityParser.applyEvent(m2, JSONObject("""{"r":["lock.front"]}"""))
    assertEquals(setOf("lock.front"), t3)
    assertFalse(m3.containsKey("lock.front"))
  }

  @Test fun diff_withoutStateKeepsOldState() {
    val e = CcEntity("light.a", "on", mapOf("brightness" to 255))
    val next = CcEntityParser.applyDiff(e, JSONObject("""{"+":{"a":{"brightness":51}}}"""))
    assertEquals("on", next.state)
    assertEquals(20, next.brightnessPct)
  }

  @Test fun registry_inheritsDeviceAreaAndFiltersHiddenAndDiagnostic() {
    val floors = CcRegistryParser.floors(JSONArray("""[{"floor_id":"up","name":"Upstairs","level":1},{"floor_id":"g","name":"Ground","level":0},{"name":"x"}]"""))
    assertEquals(listOf("g", "up"), floors.map { it.id })
    val areas = CcRegistryParser.areas(JSONArray("""[{"area_id":"kitchen","name":"Kitchen","floor_id":"g"},{"area_id":"attic","name":"Attic","floor_id":null}]"""))
    assertNull(areas[1].floorId)
    val devices = CcRegistryParser.deviceAreas(JSONArray("""[{"id":"d1","area_id":"kitchen"},{"id":"d2","area_id":null}]"""))
    val reg = CcRegistryParser.registry(floors, areas, devices, JSONArray("""[
      {"entity_id":"light.k","device_id":"d1","area_id":null},
      {"entity_id":"light.own","device_id":"d1","area_id":"attic"},
      {"entity_id":"sensor.diag","device_id":"d1","entity_category":"diagnostic"},
      {"entity_id":"switch.hidden","hidden_by":"user"},
      {"entity_id":"binary_sensor.door","original_device_class":"door","disabled_by":null}]"""))
    assertEquals("kitchen", reg.areaOf("light.k"))
    assertEquals("attic", reg.areaOf("light.own"))
    assertEquals("door", reg.entityClass["binary_sensor.door"])
    assertTrue("light.k" in reg.entities)
    assertFalse("sensor.diag" in reg.entities)
    assertFalse("switch.hidden" in reg.entities)
    assertEquals(listOf("kitchen"), reg.areasOn("g").map { it.id })
  }

  @Test fun kinds_coverTheDomains() {
    assertEquals(CcKind.LEAK, CcKind.of(CcEntity("binary_sensor.l", "on", mapOf("device_class" to "moisture"))))
    assertEquals(CcKind.WINDOW, CcKind.of(CcEntity("binary_sensor.w", "on", mapOf("device_class" to "window"))))
    assertEquals(CcKind.APPLIANCE, CcKind.of(CcEntity("sensor.washer_state", "run")))
    assertEquals(CcKind.POWER, CcKind.of(CcEntity("sensor.p", "1", mapOf("device_class" to "power"))))
    assertEquals(CcKind.COVER, CcKind.of(CcEntity("cover.blind", "open")))
    assertEquals(CcKind.OTHER, CcKind.of(CcEntity("update.x", "on")))
    assertTrue(CcKind.LIGHT.controllable)
    assertFalse(CcKind.TEMPERATURE.controllable)
  }

  @Test fun entity_openAndFallbacks() {
    assertTrue(CcEntity("lock.f", "unlocked").isOpen)
    assertTrue(CcEntity("binary_sensor.d", "on", mapOf("device_class" to "door")).isOpen)
    assertFalse(CcEntity("binary_sensor.m", "on", mapOf("device_class" to "motion")).isOpen)
    assertEquals(100, CcEntity("cover.c", "open").position)
    assertEquals("Living room lamp", CcEntity("light.living_room_lamp", "off").name)
    assertTrue(CcEntity("light.x", "unavailable").unavailable)
  }

  @Test fun connection_onlyLiveCanControl() {
    assertTrue(CcConnection(CcPhase.LIVE).canControl)
    CcPhase.entries.filter { it != CcPhase.LIVE }.forEach { p ->
      assertFalse(CcConnection(p).canControl)
      assertTrue(CcConnection(p).disabledReason!!.isNotBlank())
    }
  }
}
