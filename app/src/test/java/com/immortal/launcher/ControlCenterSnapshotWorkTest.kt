/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-snapshot work the engine does off the main thread: the side column's slices, the warmed
 * derivations and every floor's plan model (kept while nothing that floor shows changed).
 */
class ControlCenterSnapshotWorkTest {
  private val lamp = CcEntity("light.lamp", "on", mapOf("brightness" to 200))
  private val power = CcEntity("sensor.grid_power", "420", mapOf("unit_of_measurement" to "W", "device_class" to "power"))
  private val night = CcEntity("scene.good_night", "x", mapOf("friendly_name" to "Good night", "entity_id" to listOf(lamp.id)))
  private val cam = CcEntity("camera.porch", "idle", mapOf("friendly_name" to "Porch"))
  private val ground = CcFloor("g", "Ground floor", listOf(CcRoom("living", "Living", CcRect(0, 0, 10, 8), devices = listOf(CcPlaced(lamp.id, .5f, .5f)))))
  private val upper = CcFloor("u", "Upper floor", listOf(CcRoom("bed", "Bedroom", CcRect(0, 0, 8, 8))))
  private val plan = CcPlan(listOf(ground, upper))

  private fun state(vararg es: CcEntity, version: Long = 1L, now: Long = 1_000_000L) =
      CcUiState(entities = es.associateBy { it.id }, plan = plan, now = now, entitiesVersion = version)

  @Test fun sideSlices_matchWhatTheCardsUsedToDerive() {
    val s = state(lamp, night, cam)
    val side = s.side
    assertEquals(CcSideLogic.attention(s.alerts(), s.entities, s.plan, s.settings), side.attention)
    assertEquals(listOf(night.id), side.scenes.tiles.map { it.entity.id })
    assertEquals(listOf(CcFloorTab(cam.id, "Porch")), side.cameras.cams)
    // Equal inputs, equal slices: an unrelated snapshot lets every card skip.
    assertEquals(side, state(lamp, night, cam, version = 2L).side)
  }

  @Test fun kidsHidingCameras_emptiesTheCameraSlice() {
    val kids = ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS, kidsHideCameras = true)
    assertTrue(state(cam).copy(settings = kids).side.cameras.cams.isEmpty())
  }

  @Test fun warm_returnsTheSameSnapshotWithItsDerivationsReady() {
    val s = state(lamp, power, night)
    assertSame(s, s.warm())
    assertSame(s.side, s.side)
    assertEquals(listOf(lamp), s.ofDomain("light"))
  }

  @Test fun planModels_reuseAFloorWhileItsEntitiesAreUnchanged() {
    val cache = CcPlanModelCache()
    val first = cache.of(state(lamp, power))
    assertEquals(setOf("g", "u"), first.keys)
    // Only the power meter (not on any floor) changed: both models are the same instances.
    val second = cache.of(state(lamp, power.copy(state = "510"), version = 2L))
    assertSame(first["g"], second["g"])
    assertSame(first["u"], second["u"])
    // The lamp on the ground floor changed: that floor rebuilds, the other is kept.
    val third = cache.of(state(lamp.copy(state = "off"), power, version = 3L))
    assertNotSame(first["g"], third["g"])
    assertSame(first["u"], third["u"])
    assertEquals(0, third.getValue("g").lightsOn)
  }

  @Test fun planModels_followTheMinuteAndThePlan() {
    val cache = CcPlanModelCache()
    val a = cache.of(state(lamp))
    assertNotSame(a["g"], cache.of(state(lamp, now = 1_000_000L + 60_000L))["g"])
    val fewer = cache.of(state(lamp).copy(plan = CcPlan(listOf(ground))))
    assertEquals(setOf("g"), fewer.keys)
    assertTrue(cache.of(state(lamp).copy(plan = null)).isEmpty())
  }

  @Test fun planModels_matchADirectBuild() {
    val s = state(lamp, power)
    assertEquals(CcPlanModel.build(ground, s.entities, s.settings, s.now, s.appliancesDone), CcPlanModelCache().of(s)["g"])
  }
}
