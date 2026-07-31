/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/** Pure parsing of Home Assistant `/api/states` entity objects (no network). */
class HaClientTest {

  private fun obj(json: String) = JSONObject(json)

  @Test
  fun `light on with friendly name is toggleable and on`() {
    val s = HaClient.parseState(obj("""{"entity_id":"light.kitchen","state":"on","attributes":{"friendly_name":"Kitchen"}}"""))
    assertEquals("light.kitchen", s.entityId)
    assertEquals("Kitchen", s.friendlyName)
    assertTrue(s.on)
    assertTrue(s.toggleable)
  }

  @Test
  fun `switch off is toggleable and off`() {
    val s = HaClient.parseState(obj("""{"entity_id":"switch.fan","state":"off","attributes":{"friendly_name":"Fan"}}"""))
    assertFalse(s.on)
    assertTrue(s.toggleable)
  }

  @Test
  fun `sensor is not toggleable`() {
    val s = HaClient.parseState(obj("""{"entity_id":"sensor.temp","state":"21.5","attributes":{"friendly_name":"Temp"}}"""))
    assertFalse(s.toggleable)
  }

  @Test
  fun `missing friendly name falls back to entity id`() {
    val s = HaClient.parseState(obj("""{"entity_id":"light.hall","state":"on","attributes":{}}"""))
    assertEquals("light.hall", s.friendlyName)
  }

  @Test
  fun `media player playing counts as on`() {
    val s = HaClient.parseState(obj("""{"entity_id":"media_player.tv","state":"playing","attributes":{}}"""))
    assertTrue(s.on)
    assertFalse(s.toggleable) // media_player isn't in the toggleable set
  }

  @Test
  fun `scene is activatable but not toggleable`() {
    val s = HaClient.parseState(obj("""{"entity_id":"scene.movie_night","state":"","attributes":{"friendly_name":"Movie Night"}}"""))
    assertTrue(s.activatable)
    assertFalse(s.toggleable)
    assertEquals("Movie Night", s.friendlyName)
  }

  @Test
  fun `script is toggleable, not activatable`() {
    val s = HaClient.parseState(obj("""{"entity_id":"script.bedtime","state":"off","attributes":{}}"""))
    assertTrue(s.toggleable)
    assertFalse(s.activatable)
  }
}
