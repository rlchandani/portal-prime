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

class ControlCenterRoomsEnergyTest {
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

  @Test fun energyNow_findsFlowsAndConsumers() {
    val e = listOf(
        CcEntity("sensor.solar_power", "300", mapOf("device_class" to "power", "unit_of_measurement" to "W")),
        CcEntity("sensor.grid_power", "400", mapOf("device_class" to "power", "unit_of_measurement" to "W")),
        CcEntity("sensor.battery_power", "1.6", mapOf("device_class" to "power", "unit_of_measurement" to "kW")),
        CcEntity("sensor.battery_level", "68", mapOf("device_class" to "battery")),
        CcEntity("sensor.dryer_power", "1200", mapOf("device_class" to "power")),
        CcEntity("sensor.tv_power", "0.5", mapOf("device_class" to "power")),
    ).associateBy { it.id }
    val now = CcEnergy.now(e)
    assertEquals(300.0, now.solarW!!, 0.1); assertEquals(1600.0, now.batteryW!!, 0.1)
    assertEquals(2300.0, now.homeW!!, 0.1)
    assertEquals(68, now.batteryPct)
    assertEquals(listOf("sensor.dryer_power"), now.consumers.map { it.entityId })
    assertTrue(now.hasFlow)
    assertFalse(CcEnergy.now(emptyMap()).hasFlow)
    assertNull(CcEnergy.now(emptyMap()).homeW)
    assertEquals("2.3", CcEnergy.kw(2300.0)); assertEquals("—", CcEnergy.kw(null))
  }

  @Test fun energyPrefsAndSeries() {
    val prefs = CcEnergy.prefs(JSONObject("""{"energy_sources":[
      {"type":"grid","flow_from":[{"stat_energy_from":"sensor.grid_in","stat_cost":"sensor.grid_cost"}],"flow_to":[{"stat_energy_to":"sensor.grid_out"}]},
      {"type":"solar","stat_energy_from":"sensor.solar"},
      {"type":"battery","stat_energy_from":"sensor.bat_out","stat_energy_to":"sensor.bat_in"}],
      "device_consumption":[{"stat_consumption":"sensor.dryer_energy"}]}"""))
    assertTrue(prefs.configured)
    assertEquals(listOf("sensor.grid_in", "sensor.grid_out", "sensor.solar", "sensor.bat_in", "sensor.bat_out", "sensor.grid_cost", "sensor.dryer_energy"), prefs.all)
    val stats = CcEnergy.stats(JSONObject("""{"sensor.grid_in":[{"start":1000,"change":1.0},{"start":2000,"change":0.5}],
      "sensor.solar":[{"start":1000,"change":2.0},{"start":2000,"change":null}],
      "sensor.grid_out":[{"start":1000,"change":0.5}],"sensor.grid_cost":[{"start":1000,"change":0.4}]}"""))
    val series = CcEnergy.series(prefs, stats, listOf(1000L, 2000L))
    assertEquals(listOf(2.5, 0.5), series.use)
    assertEquals(2.0, series.solarTotal, 0.001)
    assertEquals(0.4, series.cost!!, 0.001)
    assertEquals(0.5, series.selfPowered!!, 0.001)
    assertFalse(CcEnergy.prefs(null).configured)
    assertTrue(CcEnergy.stats("nope").isEmpty())
    assertNull(CcEnergySeries(emptyList(), emptyList(), emptyList(), emptyList(), null).selfPowered)
  }

  @Test fun buckets_alignToLocalMidnight() {
    val zone = TimeZone.getTimeZone("UTC")
    val today = ControlCenter.buckets(1_794_943_503_000L, zone, week = false)
    assertEquals(24, today.size)
    assertEquals(1_794_873_600_000L, today.first())
    assertEquals(3_600_000L, today[1] - today[0])
    val week = ControlCenter.buckets(1_794_943_503_000L, zone, week = true)
    assertEquals(7, week.size)
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
