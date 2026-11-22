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

  @Test fun everyTileWellShowsItsDomainGlyph() {
    assertEquals("bulb", HaEntityLook.icon(detail("light.x", bri = 40)))
    assertEquals("lamp", HaEntityLook.icon(detail("light.plain")))
    assertEquals("plug", HaEntityLook.icon(detail("switch.plug")))
    assertEquals("fan", HaEntityLook.icon(detail("fan.ceiling")))
    assertEquals("blinds", HaEntityLook.icon(detail("cover.blinds")))
  }

  @Test fun `sensors need a meaningful class or a temperature unit`() {
    val battery = detail("sensor.phone").copy(unit = "%", deviceClass = "battery")
    val humidity = detail("sensor.hum").copy(unit = "%", deviceClass = "humidity")
    val unclassedTemp = detail("sensor.temp") // °F, no class
    val door = detail("binary_sensor.front").copy(deviceClass = "door")
    val motion = detail("binary_sensor.hall").copy(deviceClass = "motion")
    val kinds = HaDashboardModel.tilesFor(listOf(battery, humidity, unclassedTemp, door, motion)).map { it.detail.entityId }
    assertEquals(listOf("sensor.hum", "sensor.temp", "binary_sensor.front"), kinds)
  }

  @Test fun `a room shows at most twelve tiles, big ones kept`() {
    val many = (1..20).map { detail("switch.s$it") } + detail("climate.main", cur = 70f)
    val tiles = HaDashboardModel.tilesFor(many)
    assertEquals(HaDashboardApi.MAX_ENTITIES_PER_ROOM, tiles.size)
    assertEquals(HaDashboardModel.Kind.CLIMATE, tiles.first().kind)
  }

  @Test fun `spans follow the spec`() {
    assertEquals(HaDashboardModel.Span(2, 1), HaDashboardModel.span(HaDashboardModel.Kind.DIMMER))
    assertEquals(HaDashboardModel.Span(2, 1), HaDashboardModel.span(HaDashboardModel.Kind.MEDIA))
    assertEquals(HaDashboardModel.Span(2, 2), HaDashboardModel.span(HaDashboardModel.Kind.CLIMATE))
    assertEquals(HaDashboardModel.Span(1, 1), HaDashboardModel.span(HaDashboardModel.Kind.TOGGLE))
    assertEquals(HaDashboardModel.Span(1, 1), HaDashboardModel.span(HaDashboardModel.Kind.SENSOR))
  }

  @Test fun `room glyphs come from the area id or name`() {
    assertEquals("sofa", HaDashboardModel.roomIcon("living_room", "Living Room"))
    assertEquals("pot", HaDashboardModel.roomIcon("kitchen", "Kitchen"))
    assertEquals("guest", HaDashboardModel.roomIcon("guest_bedroom", "Guest Bedroom"))
    assertEquals("bed", HaDashboardModel.roomIcon("master", "Main suite"))
    assertEquals("car", HaDashboardModel.roomIcon("garage", "Garage"))
    assertEquals("tree", HaDashboardModel.roomIcon("x", "Back patio"))
    assertEquals("home", HaDashboardModel.roomIcon("zz", "Attic"))
  }

  @Test fun `temperature humidity and the lit list`() {
    val details = listOf(
        detail("sensor.temp", state = "20.5").copy(unit = "°C"),
        detail("sensor.batt", state = "80").copy(unit = "%", deviceClass = "battery"),
        detail("sensor.hum", state = "44").copy(unit = "%", deviceClass = "humidity"),
        detail("light.a", state = "on"), detail("light.b", state = "off"))
    assertEquals("sensor.temp", HaDashboardModel.temperature(details)?.entityId)
    assertEquals("sensor.hum", HaDashboardModel.humidity(details)?.entityId)
    assertEquals(listOf("light.a"), HaDashboardModel.litEntities(details).map { it.entityId })
    assertEquals(null, HaDashboardModel.temperature(listOf(detail("sensor.temp", state = "unavailable"))))
  }
}
