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

class ControlCenterOverviewModelTest {
  private val now = 10_000_000L
  private val min = 60_000L

  private fun e(id: String, state: String, vararg attrs: Pair<String, Any?>, changed: Long = 0L) = CcEntity(id, state, mapOf(*attrs), changed)

  private val garage = CcRoom("garage", "Garage", CcRect(0, 0, 12, 14), devices = listOf(
      CcPlaced("cover.garage_door", 0f, 0.5f), CcPlaced("light.garage", 0.5f, 0.2f), CcPlaced("camera.garage", 0.1f, 0.1f)))
  private val hall = CcRoom("hall", "Hall", CcRect(12, 0, 8, 30), devices = listOf(
      CcPlaced("lock.front", 0.5f, 0.95f), CcPlaced("binary_sensor.hall_motion", 0.5f, 0.5f), CcPlaced("climate.down", 0.2f, 0.9f)))
  private val laundry = CcRoom("laundry", "Laundry", CcRect(0, 14, 12, 8), devices = listOf(
      CcPlaced("switch.washer", 0.5f, 0.5f), CcPlaced("binary_sensor.leak_laundry", 0.4f, 0.6f), CcPlaced("sensor.laundry_power", 0.5f, 0.5f)))
  private val floor = CcFloor("g", "Ground", listOf(garage, hall, laundry), openings = listOf(
      CcOpening("cover.garage_door", "garage", CcSide.W, 0.5f, 0.6f),
      CcOpening("binary_sensor.office_window", "hall", CcSide.E, 0.5f, 0.3f),
      CcOpening("binary_sensor.front", "hall", CcSide.S, 0.5f, 0.3f),
      CcOpening("binary_sensor.ghost", "nowhere", CcSide.N, 0.5f, 0.3f)))

  private val entities = listOf(
      e("cover.garage_door", "open", "device_class" to "garage", "current_position" to 100, changed = now - 17 * min),
      e("light.garage", "on", "brightness" to 255, "rgb_color" to listOf(255, 200, 100)),
      e("camera.garage", "idle"),
      e("lock.front", "locked"),
      e("binary_sensor.hall_motion", "on", "device_class" to "motion"),
      e("climate.down", "heat", "temperature" to 70, "current_temperature" to 68.4, "hvac_action" to "heating"),
      e("switch.washer", "on"),
      e("binary_sensor.leak_laundry", "on", "device_class" to "moisture"),
      e("sensor.laundry_power", "1200", "device_class" to "power", "unit_of_measurement" to "W"),
      e("binary_sensor.office_window", "on", "device_class" to "window"),
      e("binary_sensor.front", "off", "device_class" to "door"),
  ).associateBy { it.id }

  private fun build(settings: ControlCenterConfig.Settings = ControlCenterConfig.Settings(), ents: Map<String, CcEntity> = entities) =
      CcPlanModel.build(floor, ents, settings, now)

  @Test fun openingsResolveKindsAndDropUnknownRooms() {
    val m = build()
    assertEquals(3, m.openings.size)
    val byId = m.openings.associateBy { it.opening.entityId }
    assertEquals(CcMapOpeningKind.GARAGE, byId.getValue("cover.garage_door").kind)
    assertTrue(byId.getValue("cover.garage_door").open)
    assertEquals(1f, byId.getValue("cover.garage_door").progress, 1e-4f)
    assertEquals(CcMapOpeningKind.WINDOW, byId.getValue("binary_sensor.office_window").kind)
    assertEquals(CcMapOpeningKind.DOOR, byId.getValue("binary_sensor.front").kind)
    assertFalse(byId.getValue("binary_sensor.front").open)
  }

  @Test fun garageChipCountsMinutes() {
    val chip = build().chips.single()
    assertEquals("OPEN 17m", chip.text)
    assertEquals(CcPlanGeo.CHIP_INSET_CELLS, chip.x, 1e-4f)
    assertEquals(2.8f, chip.y, 1e-4f)
  }

  @Test fun garageLabels() {
    assertEquals("OPENING", CcPlanModel.garageLabel(e("cover.g", "opening"), now))
    assertEquals("CLOSING", CcPlanModel.garageLabel(e("cover.g", "closing"), now))
    assertEquals("OPEN", CcPlanModel.garageLabel(e("cover.g", "open"), now))
    assertEquals("OPEN 1h 5m", CcPlanModel.garageLabel(e("cover.g", "open", changed = now - 65 * min), now))
    assertEquals("OPEN 0m", CcPlanModel.garageLabel(e("cover.g", "open", changed = now + min), now))
  }

  @Test fun partlyOpenGarageShowsProgress() {
    val ents = entities + ("cover.garage_door" to e("cover.garage_door", "open", "device_class" to "garage", "current_position" to 40))
    assertEquals(0.4f, build(ents = ents).openings.first().progress, 1e-4f)
  }

  @Test fun kidsModeDrawsTheGarageShut() {
    val m = build(ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS))
    assertFalse(m.openings.first { it.kind == CcMapOpeningKind.GARAGE }.open)
    assertTrue(m.chips.isEmpty())
    assertTrue(m.badges.none { it.kind == CcMapBadgeKind.LOCKED || it.kind == CcMapBadgeKind.CAMERA })
  }

  @Test fun pulsesAreOrderedAndCapped() {
    val m = build()
    assertEquals(CcMapPulseTone.LEAK, m.pulses.first().tone)
    assertEquals(listOf(CcMapPulseTone.LEAK, CcMapPulseTone.OPEN, CcMapPulseTone.OPEN, CcMapPulseTone.MOTION), m.pulses.map { it.tone })
    val many = (1..10).map { i -> CcMapOpeningViewFixture.open(i) }
    assertEquals(CcPlanModel.MAX_PULSES, CcPlanModel.pulses(emptyList(), many, emptyMap()).size)
  }

  @Test fun badgesForLocksClimateCameraApplianceLeak() {
    val kinds = build().badges.map { it.kind }.toSet()
    assertEquals(setOf(CcMapBadgeKind.LOCKED, CcMapBadgeKind.CLIMATE, CcMapBadgeKind.CAMERA, CcMapBadgeKind.APPLIANCE, CcMapBadgeKind.LEAK), kinds)
    val climate = build().badges.first { it.kind == CcMapBadgeKind.CLIMATE }
    assertEquals("70°", climate.label)
    assertTrue(climate.hot)
    val unlocked = build(ents = entities + ("lock.front" to e("lock.front", "unlocked"))).badges.first { it.entityId == "lock.front" }
    assertEquals(CcMapBadgeKind.UNLOCKED, unlocked.kind)
    assertEquals("unlock", unlocked.icon)
  }

  @Test fun finishedApplianceIsGreen() {
    val m = CcPlanModel.build(floor, entities + ("switch.washer" to e("switch.washer", "off")), ControlCenterConfig.Settings(), now,
        mapOf("switch.washer" to now - min))
    assertEquals(CcMapBadgeKind.APPLIANCE_DONE, m.badges.first { it.entityId == "switch.washer" }.kind)
  }

  @Test fun lightDotsCarryColourWhenOn() {
    val m = build()
    val dot = m.dots.single()
    assertEquals(0xFFC864, dot.rgb)
    assertEquals(1, m.lightsOn)
    val off = build(ents = entities + ("light.garage" to e("light.garage", "off"))).dots.single()
    assertNull(off.rgb)
  }

  @Test fun applianceIcons() {
    assertEquals("dish", CcPlanModel.applianceIcon("switch.dishwasher"))
    assertEquals("dryer", CcPlanModel.applianceIcon("sensor.dryer"))
    assertEquals("oven", CcPlanModel.applianceIcon("switch.oven"))
    assertEquals("washer", CcPlanModel.applianceIcon("switch.washing_machine"))
  }
}

/** Builds open openings for the pulse cap test. */
private object CcMapOpeningViewFixture {
  fun open(i: Int) = CcOpeningView(CcOpening("binary_sensor.w$i", "r", CcSide.N, 0.5f, 0.3f), CcMapOpeningKind.WINDOW,
      CcMapLine(i.toFloat(), 0f, i + 1f, 0f), open = true, progress = 1f)
}
