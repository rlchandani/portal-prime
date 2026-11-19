/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ControlCenterRoomsTest {
  private val room = CcRoom("living", "Living room", CcRect(0, 0, 10, 10), devices = listOf(
      CcPlaced("light.floor", 0.2f, 0.8f), CcPlaced("light.back", 0.8f, 0.3f), CcPlaced("light.ceiling", 0.5f, 0.5f),
      CcPlaced("sensor.temp", 0.1f, 0.1f), CcPlaced("binary_sensor.motion", 0.5f, 0.2f), CcPlaced("binary_sensor.window", 1f, 0.5f),
      CcPlaced("sensor.tv_power", 0.3f, 0.3f), CcPlaced("climate.down", 0.4f, 0.9f)))
  private val entities = listOf(
      CcEntity("light.floor", "on", mapOf("brightness" to 178, "color_temp_kelvin" to 2700, "color_mode" to "color_temp")),
      CcEntity("light.back", "on", mapOf("brightness" to 102, "rgb_color" to listOf(140, 80, 255), "color_mode" to "rgb")),
      CcEntity("light.ceiling", "off"),
      CcEntity("sensor.temp", "20.6", mapOf("device_class" to "temperature", "unit_of_measurement" to "°C")),
      CcEntity("binary_sensor.motion", "on", mapOf("device_class" to "motion")),
      CcEntity("binary_sensor.window", "on", mapOf("device_class" to "window")),
      CcEntity("sensor.tv_power", "0.3", mapOf("device_class" to "power", "unit_of_measurement" to "kW")),
      CcEntity("climate.down", "heat", mapOf("current_temperature" to 19, "current_humidity" to 44)),
  ).associateBy { it.id }

  @Test fun roomView_summarisesTheRoom() {
    val v = CcRooms.view(room, entities)
    assertEquals(2, v.lightsOn); assertEquals(3, v.lights)
    assertEquals(0x8C50FF, v.glows.single { it.entityId == "light.back" }.rgb)
    assertEquals(0.4f, v.glows.single { it.entityId == "light.back" }.strength, 0.01f)
    assertEquals(20.6, v.temperature!!, 0.01)
    assertEquals(44, v.humidity)
    assertTrue(v.motion)
    assertEquals(1, v.openCount)
    assertEquals(300.0, v.powerW!!, 0.01)
    assertEquals("climate.down", v.climateId)
    assertEquals("21° · 2 lights on", v.caption())
    assertEquals("1 light on", CcRooms.view(room.copy(devices = room.devices.take(1)), entities).caption())
    assertTrue(CcRooms.view(room.copy(devices = emptyList()), entities).caption().isEmpty())
  }

  @Test fun kelvinAndHeat() {
    val warm = CcRooms.kelvinToRgb(2700)
    assertEquals(255, warm shr 16 and 0xff)
    assertTrue((warm and 0xff) < 200)
    assertEquals(255, CcRooms.kelvinToRgb(6600) and 0xff)
    assertEquals(0f, CcRooms.heat(10.0, false)); assertEquals(1f, CcRooms.heat(30.0, false))
    assertEquals(0.5f, CcRooms.heat(68.0, true), 0.01f)
  }

  @Test fun buckets_alignToLocalMidnight() {
    val zone = TimeZone.getTimeZone("UTC")
    val week = ControlCenter.weekBuckets(1_794_943_503_000L, zone)
    assertEquals(7, week.size)
    assertEquals(86_400_000L, week[1] - week[0])
    assertEquals(1_794_873_600_000L, week.last())
    assertEquals("2026-11-17T19:25:03Z", ControlCenter.isoUtc(1_794_943_503_000L))
  }

  @Test fun sceneRun_ordersRoomsAndTimes() {
    val plan = CcPlan(listOf(CcFloor("g", "G", listOf(
        CcRoom("b", "B", CcRect(20, 0, 5, 5), devices = listOf(CcPlaced("light.b", 0f, 0f))),
        CcRoom("a", "A", CcRect(0, 0, 5, 5), devices = listOf(CcPlaced("light.a", 0f, 0f))),
        CcRoom("c", "C", CcRect(10, 0, 5, 5))))))
    assertEquals(listOf("a", "b"), CcSceneRun.rooms(plan, listOf("light.b", "light.a")))
    assertTrue(CcSceneRun.rooms(null, listOf("light.a")).isEmpty())
    assertEquals(CcSceneRun.MIN_MS, CcSceneRun.duration(1))
    assertEquals(CcSceneRun.MAX_MS, CcSceneRun.duration(40))
    val run = CcSceneRun("scene.x", "X", listOf("a"), 1000L, 4000L)
    assertEquals(0.5f, run.progress(3000L)); assertFalse(run.finished(3000L)); assertTrue(run.finished(5000L))
  }
}
