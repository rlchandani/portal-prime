/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JSON-shaping for the HA dashboard (no Context, no network). */
class HaDashboardApiTest {

  // ---- area template response ---------------------------------------------------

  @Test
  fun `parses the area template response into rooms`() {
    val body =
        """[{"id":"living_room","name":"Living Room","entities":["light.ceiling","climate.main","sensor.temp"]},
            {"id":"bedroom","name":"Bedroom","entities":["light.bedside"]}]"""
    val areas = HaDashboardApi.parseAreas(body)
    assertEquals(2, areas.size)
    assertEquals("living_room", areas[0].id)
    assertEquals("Living Room", areas[0].name)
    assertEquals(listOf("light.ceiling", "climate.main", "sensor.temp"), areas[0].entityIds)
  }

  @Test
  fun `malformed area response yields empty`() {
    assertEquals(emptyList<HaDashboardApi.Area>(), HaDashboardApi.parseAreas("not json"))
    assertEquals(emptyList<HaDashboardApi.Area>(), HaDashboardApi.parseAreas("""{"weird":1}"""))
  }

  @Test
  fun `areas with no entities are dropped`() {
    val body = """[{"id":"attic","name":"Attic","entities":[]},{"id":"den","name":"Den","entities":["light.x"]}]"""
    val areas = HaDashboardApi.parseAreas(body)
    assertEquals(listOf("den"), areas.map { it.id })
  }

  // ---- rich entity attributes ----------------------------------------------------

  @Test
  fun `light attributes surface brightness percent`() {
    val o = org.json.JSONObject(
        """{"entity_id":"light.ceiling","state":"on",
            "attributes":{"friendly_name":"Ceiling","brightness":158}}""")
    val d = HaDashboardApi.parseDetail(o)
    assertEquals(62, d.brightnessPct) // 158/255 ≈ 62%
    assertEquals("light", d.domain)
  }

  @Test
  fun `climate attributes surface temps and action`() {
    val o = org.json.JSONObject(
        """{"entity_id":"climate.main","state":"heat",
            "attributes":{"friendly_name":"Thermostat","current_temperature":72.4,
                          "temperature":71.0,"hvac_action":"heating"}}""")
    val d = HaDashboardApi.parseDetail(o)
    assertEquals(72.4f, d.currentTemp!!, 1e-3f)
    assertEquals(71.0f, d.targetTemp!!, 1e-3f)
    assertEquals("heating", d.hvacAction)
  }

  @Test
  fun `missing attributes stay null`() {
    val o = org.json.JSONObject("""{"entity_id":"switch.plug","state":"off","attributes":{}}""")
    val d = HaDashboardApi.parseDetail(o)
    assertNull(d.brightnessPct)
    assertNull(d.currentTemp)
  }

  // ---- history → sparkline -------------------------------------------------------

  @Test
  fun `history response reduces to a float series`() {
    // /api/history/period minimal_response shape: outer array per entity, inner state list.
    val body = """[[{"state":"64.2"},{"state":"65.0"},{"state":"unknown"},{"state":"71.9"}]]"""
    assertEquals(listOf(64.2f, 65.0f, 71.9f), HaDashboardApi.parseHistory(body))
  }

  @Test
  fun `empty or bad history yields empty series`() {
    assertEquals(emptyList<Float>(), HaDashboardApi.parseHistory("[]"))
    assertEquals(emptyList<Float>(), HaDashboardApi.parseHistory("nope"))
  }
}
