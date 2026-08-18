/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure entity→tile categorization for the HA dashboard (no Context, no network). */
class HaDashboardModelTest {

  private fun detail(id: String, state: String = "on", bri: Int? = null, cur: Float? = null) =
      HaDashboardApi.Detail(
          entityId = id,
          domain = id.substringBefore('.'),
          friendlyName = id.substringAfter('.'),
          state = state,
          brightnessPct = bri,
          currentTemp = cur,
          targetTemp = if (cur != null) cur - 1 else null,
          hvacAction = if (cur != null) "heating" else null,
          mediaTitle = null,
          mediaArtist = null,
          unit = if (id.startsWith("sensor")) "°F" else "",
      )

  @Test
  fun `dimmable light becomes a dimmer tile, plain switch a toggle`() {
    val tiles = HaDashboardModel.tilesFor(listOf(detail("light.ceiling", bri = 60), detail("switch.plug")))
    assertEquals(HaDashboardModel.Kind.DIMMER, tiles[0].kind)
    assertEquals(HaDashboardModel.Kind.TOGGLE, tiles[1].kind)
  }

  @Test
  fun `climate becomes a thermostat tile`() {
    val tiles = HaDashboardModel.tilesFor(listOf(detail("climate.main", state = "heat", cur = 72f)))
    assertEquals(HaDashboardModel.Kind.CLIMATE, tiles[0].kind)
  }

  @Test
  fun `media and sensor map to their tiles`() {
    val tiles = HaDashboardModel.tilesFor(listOf(detail("media_player.speaker"), detail("sensor.temp")))
    assertEquals(HaDashboardModel.Kind.MEDIA, tiles[0].kind)
    assertEquals(HaDashboardModel.Kind.SENSOR, tiles[1].kind)
  }

  @Test
  fun `lock and cover are toggles, unknown domains are dropped`() {
    val tiles = HaDashboardModel.tilesFor(
        listOf(detail("lock.front"), detail("cover.garage"), detail("update.firmware")))
    assertEquals(listOf(HaDashboardModel.Kind.TOGGLE, HaDashboardModel.Kind.TOGGLE), tiles.map { it.kind })
  }

  @Test
  fun `scenes are not entities of a room — only controllable, sensor, media survive`() {
    val tiles = HaDashboardModel.tilesFor(listOf(detail("scene.evening"), detail("light.x", bri = 10)))
    assertEquals(listOf(HaDashboardModel.Kind.DIMMER), tiles.map { it.kind })
  }

  @Test
  fun `lit count counts on lights and switches only`() {
    val details = listOf(
        detail("light.a", state = "on", bri = 40),
        detail("light.b", state = "off"),
        detail("switch.c", state = "on"),
        detail("sensor.temp", state = "72"), // not a light
        detail("climate.d", state = "heat", cur = 70f),
    )
    assertEquals(2, HaDashboardModel.litCount(details))
  }

  @Test
  fun `tile ordering puts climate and media ahead of small tiles`() {
    val tiles = HaDashboardModel.tilesFor(
        listOf(detail("sensor.temp"), detail("climate.main", cur = 70f),
               detail("light.x", bri = 50), detail("media_player.s")))
    // big tiles (climate, media, dimmer span-2) lead; single sensor trails
    assertEquals(HaDashboardModel.Kind.SENSOR, tiles.last().kind)
  }
}
