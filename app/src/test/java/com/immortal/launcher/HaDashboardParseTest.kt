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

class HaDashboardParseTest {

  @Test fun `the house template answer becomes rooms with details and scenes`() {
    val body = """{"areas":[
      {"id":"living_room","name":"Living Room","entities":[
        {"entity_id":"light.ceiling","state":"off","attributes":{"friendly_name":"Ceiling light","supported_color_modes":["brightness"]}},
        {"entity_id":"light.lamp","state":"on","attributes":{"friendly_name":"Floor lamp","supported_color_modes":["onoff"]}},
        {"entity_id":"climate.main","state":"heat","attributes":{"friendly_name":"Thermostat","current_temperature":20.5,"temperature":21,"hvac_action":"heating","target_temp_step":0.5}},
        {"entity_id":"sensor.temp","state":"20.5","attributes":{"friendly_name":"Temperature","unit_of_measurement":"°C","device_class":"temperature"}}]},
      {"id":"garage","name":"","entities":[]}],
      "scenes":[{"id":"scene.night","name":"Night"},{"id":"scene.movie_time","name":""},{"id":"light.bogus","name":"x"}]}"""
    val house = HaDashboardParse.house(body)!!
    assertEquals(listOf("living_room", "garage"), house.areas.map { it.id })
    assertEquals("garage", house.areas[1].name) // blank name falls back to the id
    val d = house.areas[0].details
    assertEquals(0, d[0].brightnessPct) // off but dimmable: a dimmer at 0%
    assertNull(d[1].brightnessPct) // onoff only: a plain toggle
    assertEquals(0.5f, d[2].tempStep)
    assertEquals("temperature", d[3].deviceClass)
    assertEquals(listOf("Night", "movie time"), house.scenes.map { it.name })
  }

  @Test fun `a body of the wrong shape is no house, not an empty one`() {
    assertNull(HaDashboardParse.house("[]"))
    assertNull(HaDashboardParse.house("nope"))
    assertNull(HaDashboardParse.house("""{"scenes":[]}"""))
    assertEquals(emptyList<HaDashboardApi.Scene>(), HaDashboardParse.house("""{"areas":[]}""")!!.scenes)
  }

  @Test fun `brightness is a percentage, a null brightness on a dimmable light is 0`() {
    val on = HaDashboardParse.detail(org.json.JSONObject(
        """{"entity_id":"light.a","state":"on","attributes":{"brightness":255}}"""))
    assertEquals(100, on.brightnessPct)
    val off = HaDashboardParse.detail(org.json.JSONObject(
        """{"entity_id":"light.a","state":"off","attributes":{"brightness":null}}"""))
    assertEquals(0, off.brightnessPct)
  }
}
