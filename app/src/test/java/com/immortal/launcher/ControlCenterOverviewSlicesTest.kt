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

/** The small immutable slices the overview hands to its children: equal inputs must give equal slices. */
class ControlCenterOverviewSlicesTest {
  private val garage = CcEntity("cover.garage_door", "open", mapOf("device_class" to "garage"))
  private val lock = CcEntity("lock.front", "locked")
  private val plan = CcPlan(listOf(CcFloor("g", "Ground floor", listOf(
      CcRoom("garage", "Garage", CcRect(0, 0, 10, 10), devices = listOf(CcPlaced(garage.id, 0f, .5f))),
      CcRoom("hall", "Hall", CcRect(10, 0, 6, 10), devices = listOf(CcPlaced("climate.down", .5f, .5f)))))))
  private fun alert(id: String, action: CcAlertAction, sev: CcSeverity = CcSeverity.WARNING) =
      CcAlert("$id@1", sev, CcKind.GARAGE, id, "t", "b", 1L, action)

  @Test fun attentionRowsCarryRoomAndClose() {
    val ents = mapOf(garage.id to garage, lock.id to lock)
    val slice = CcSideLogic.attention(listOf(alert(garage.id, CcAlertAction.CLOSE), alert("sensor.x", CcAlertAction.SNOOZE, CcSeverity.INFO)),
        ents, plan, ControlCenterConfig.Settings())
    assertEquals(2, slice.rows.size)
    assertTrue(slice.rows[0].close)
    assertEquals("garage", slice.rows[0].roomId)
    assertFalse(slice.rows[1].close)
    assertNull(slice.rows[1].entity)
    assertEquals("", slice.allClear)
  }

  @Test fun attentionCapsRowsAndIsEqualForEqualInput() {
    val alerts = (1..6).map { alert("cover.g$it", CcAlertAction.REMIND) }
    val a = CcSideLogic.attention(alerts, emptyMap(), null, ControlCenterConfig.Settings())
    assertEquals(CcSideLogic.ATTENTION_ROWS, a.rows.size)
    assertEquals(a, CcSideLogic.attention(alerts.toList(), emptyMap(), null, ControlCenterConfig.Settings()))
  }

  @Test fun emptyAttentionHasAnHonestLineAndRespectsKidsHiding() {
    val ents = mapOf(lock.id to lock)
    assertEquals("Door locked", CcSideLogic.attention(emptyList(), ents, plan, ControlCenterConfig.Settings()).allClear)
    val kids = ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS)
    assertEquals("Nothing needs you right now", CcSideLogic.attention(emptyList(), ents, plan, kids).allClear)
  }

  @Test fun sceneTilesDescribeAndPick() {
    val night = CcEntity("scene.good_night", "x", mapOf("friendly_name" to "Good night", "entity_id" to listOf(garage.id)))
    val tiles = CcSideLogic.tiles(listOf(CcEntity("scene.dinner", "x"), night), plan, kids = false)
    assertEquals(listOf("scene.good_night", "scene.dinner"), tiles.map { it.entity.id })
    assertEquals(CcSceneTone.NIGHT, tiles[0].tone)
    assertEquals("Garage", tiles[0].subtitle)
    assertEquals(tiles, CcSideLogic.tiles(listOf(CcEntity("scene.dinner", "x"), night), plan, kids = false))
  }

  @Test fun legendPerLens() {
    val climate = CcEntity("climate.down", "heat", mapOf("friendly_name" to "Downstairs", "hvac_action" to "heating", "current_temperature" to 70))
    val ents = mapOf(climate.id to climate, garage.id to garage)
    val model = CcPlanModel.build(plan.floors[0], ents, ControlCenterConfig.Settings(), 0L)
    assertEquals(CcLegend(CcLens.LIVE, lightsOn = 0), CcOverviewText.legend(model, CcLens.LIVE, ents, true))
    assertEquals(CcLegend(CcLens.ENERGY), CcOverviewText.legend(model, CcLens.ENERGY, ents, true))
    assertEquals(CcLegend(CcLens.CLIMATE, cold = "61°", warm = "75°", climate = "Downstairs heating"),
        CcOverviewText.legend(model, CcLens.CLIMATE, ents, true))
  }

  @Test fun equalStateBuildsAnEqualModel() {
    val ents = mapOf(garage.id to garage)
    val a = CcPlanModel.build(plan.floors[0], ents, ControlCenterConfig.Settings(), 60_000L)
    val b = CcPlanModel.build(plan.floors[0], HashMap(ents), ControlCenterConfig.Settings(), 60_000L)
    assertEquals(a, b)
  }

  @Test fun lruKeepsTheMostRecentFloors() {
    val lru = CcLru<String, Int>(2)
    var built = 0
    assertEquals(1, lru.getOrPut("g") { ++built })
    assertEquals(2, lru.getOrPut("u") { ++built })
    assertEquals(1, lru.getOrPut("g") { ++built }) // reused, and now most recent
    lru.getOrPut("b") { ++built } // evicts "u", the least recently used
    assertEquals(3, built)
    assertNull(lru["u"])
    assertEquals(1, lru["g"])
    assertEquals(2, lru.size)
    lru.put("g", 9)
    assertEquals(9, lru["g"])
  }

  @Test fun modelKeysCompareByValue() {
    val f = plan.floors[0]
    val s = ControlCenterConfig.Settings()
    assertEquals(CcModelKey(f, 3, s, 10, emptyMap()), CcModelKey(f.copy(), 3, s.copy(), 10, HashMap()))
    assertFalse(CcModelKey(f, 3, s, 10, emptyMap()) == CcModelKey(f, 4, s, 10, emptyMap()))
    assertEquals(CcPxKey(f, 100, 50, 2f), CcPxKey(f.copy(), 100, 50, 2f))
  }

  @Test fun kidsModelHidesWhileFullModeReusesEntities() {
    val ents = mapOf(lock.id to lock)
    val floor = CcFloor("g", "G", listOf(CcRoom("hall", "Hall", CcRect(0, 0, 4, 4), devices = listOf(CcPlaced(lock.id, .5f, .5f)))))
    assertEquals(1, CcPlanModel.build(floor, ents, ControlCenterConfig.Settings(), 0).badges.size)
    assertTrue(CcPlanModel.build(floor, ents, ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS), 0).badges.isEmpty())
  }
}
