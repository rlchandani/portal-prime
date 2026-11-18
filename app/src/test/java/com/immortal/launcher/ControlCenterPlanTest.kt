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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterPlanTest {
  private val kitchen = CcRoom("kitchen", "Kitchen", CcRect(2, 2, 10, 8), devices = listOf(CcPlaced("light.k", 0.5f, 0.5f)))
  private val plan = CcPlan(listOf(CcFloor("g", "Ground", listOf(kitchen), listOf(CcOpening("binary_sensor.door", "kitchen", CcSide.S, 0.4f, 0.3f)))), 7L)

  @Test fun json_roundTrips() {
    val back = CcPlanJson.decode(CcPlanJson.encode(plan))
    assertEquals(plan, back)
    assertEquals(plan, CcPlanJson.decodeString(CcPlanJson.encode(plan).toString()))
    assertTrue(CcPlanJson.fits(plan))
  }

  @Test fun json_rejectsWrongVersionAndDropsBadParts() {
    assertNull(CcPlanJson.decode(JSONObject("""{"v":99,"floors":[]}""")))
    assertNull(CcPlanJson.decode(null))
    assertNull(CcPlanJson.decodeString("{bad"))
    val p = CcPlanJson.decode(JSONObject("""{"v":1,"floors":[{"id":"g","rooms":[
      {"id":"a","name":"A","r":[-5,0,100,3],"d":[{"e":"nodot"},{"e":"light.x","x":7,"y":-1}]},{"id":"b","r":[1,2]}],
      "openings":[{"e":"binary_sensor.d","room":"zzz","side":"N"},{"e":"binary_sensor.w","room":"a","side":"Q"},{"e":"binary_sensor.v","room":"a","side":"E","span":0}]}]}"""))!!
    val room = p.floors.single().rooms.single()
    assertEquals(CcRect(0, 0, CcPlan.GRID_W, 3), room.rect)
    assertEquals(listOf(CcPlaced("light.x", 1f, 0f)), room.devices)
    assertEquals("g", p.floors.single().name)
    val op = p.floors.single().openings.single()
    assertEquals("binary_sensor.v", op.entityId)
    assertTrue(op.span > 0f)
  }

  @Test fun lookups() {
    assertEquals("kitchen", plan.roomOf("light.k")!!.id)
    assertNull(plan.roomOf("light.none"))
    assertEquals(kitchen, plan.room("kitchen"))
    assertEquals(setOf("light.k", "binary_sensor.door"), plan.entityIds)
    assertEquals("g", plan.floor(null)!!.id)
    assertFalse(plan.isEmpty)
    assertTrue(CcPlan.EMPTY.isEmpty)
  }

  @Test fun rect_geometry() {
    val r = CcRect(2, 2, 4, 4)
    assertTrue(r.contains(3f, 3f)); assertFalse(r.contains(7f, 3f))
    assertTrue(r.overlaps(CcRect(5, 5, 2, 2))); assertFalse(r.overlaps(CcRect(6, 2, 2, 2)))
    assertEquals(CcRect(CcPlan.GRID_W - 1, 0, 1, 1), CcRect(99, -3, 0, 0).clamped())
  }

  @Test fun store_newestWins() {
    val older = plan.copy(updatedAt = 1)
    val newer = plan.copy(updatedAt = 9)
    assertEquals(newer, CcPlanStore.newest(older, newer))
    assertEquals(newer, CcPlanStore.newest(newer, older))
    assertEquals(older, CcPlanStore.newest(null, older))
    assertEquals(older, CcPlanStore.newest(older, null))
    assertNull(CcPlanStore.newest(null, null))
  }
}
