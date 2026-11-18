/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterPlanEditTest {
  private val reg = CcRegistry(
      floors = listOf(CcHaFloor("g", "Ground", 0), CcHaFloor("empty", "Loft", 2)),
      areas = listOf(CcHaArea("kitchen", "Kitchen", "g"), CcHaArea("garage", "Garage", "g"), CcHaArea("drive", "Driveway", "g"),
          CcHaArea("attic", "Attic", null)),
      entityArea = mapOf("light.k" to "kitchen", "binary_sensor.kdoor" to "kitchen", "cover.gd" to "garage", "sensor.ktemp" to "kitchen",
          "sensor.diag" to "kitchen"),
      entityClass = mapOf("binary_sensor.kdoor" to "door", "cover.gd" to "garage", "sensor.ktemp" to "temperature", "binary_sensor.wild" to "window"),
      entities = setOf("light.k", "binary_sensor.kdoor", "cover.gd", "sensor.ktemp", "sensor.diag", "light.loose", "binary_sensor.wild"))

  @Test fun floors_skipEmptyAndAddOtherRooms() {
    val floors = CcPlanEdit.floorsFor(reg)
    assertEquals(listOf("g", CcPlanEdit.UNASSIGNED_FLOOR), floors.map { it.id })
    assertEquals(listOf("attic"), CcPlanEdit.areasFor(reg, CcPlanEdit.UNASSIGNED_FLOOR).map { it.id })
    assertEquals(listOf("Home"), CcPlanEdit.floorsFor(CcRegistry(areas = listOf(CcHaArea("a", "A", null)))).map { it.name })
  }

  @Test fun suggest_fillsGridWithoutOverlap() {
    val rooms = CcPlanEdit.suggestRooms(CcPlanEdit.areasFor(reg, "g"))
    assertEquals(3, rooms.size)
    rooms.forEach { a -> rooms.filter { it !== a }.forEach { b -> assertFalse(a.rect.overlaps(b.rect)) } }
    assertTrue(rooms.single { it.id == "drive" }.outdoor)
    assertFalse(rooms.single { it.id == "kitchen" }.outdoor)
    assertTrue(CcPlanEdit.suggestRooms(emptyList()).isEmpty())
    val many = CcPlanEdit.suggestRooms((1..11).map { CcHaArea("a$it", "Room $it", "g") })
    assertEquals(11, many.size)
    assertTrue(many.all { it.rect.right <= CcPlan.GRID_W && it.rect.bottom <= CcPlan.GRID_H })
  }

  @Test fun autoPlace_putsDevicesInRoomsAndOpeningsOnWalls() {
    val floor = CcFloor("g", "Ground", CcPlanEdit.suggestRooms(CcPlanEdit.areasFor(reg, "g")))
    val placed = CcPlanEdit.autoPlace(floor, reg, emptyMap())
    val kitchen = placed.rooms.single { it.id == "kitchen" }
    assertEquals(setOf("light.k", "sensor.ktemp"), kitchen.devices.map { it.entityId }.toSet())
    assertEquals(setOf("binary_sensor.kdoor", "cover.gd"), placed.openings.map { it.entityId }.toSet())
    assertEquals(CcSide.W, placed.openings.single { it.entityId == "cover.gd" }.side)
    assertEquals(placed, CcPlanEdit.autoPlace(placed, reg, emptyMap()))
    val plan = CcPlan(listOf(placed))
    assertEquals(listOf("binary_sensor.wild", "light.loose"), CcPlanEdit.unplaced(plan, placed, reg, emptyMap()))
    assertEquals(4 to 6, CcPlanEdit.progress(plan, placed, reg, emptyMap()))
  }

  @Test fun planWorthy_filtersNoise() {
    assertTrue(CcPlanEdit.planWorthy("light.k", reg, null))
    assertTrue(CcPlanEdit.planWorthy("sensor.ktemp", reg, null))
    assertFalse(CcPlanEdit.planWorthy("sensor.diag", reg, null))
    assertFalse(CcPlanEdit.planWorthy("automation.x", reg, null))
    assertTrue(CcPlanEdit.isOpening("binary_sensor.kdoor", reg, null))
    assertFalse(CcPlanEdit.isOpening("light.k", reg, null))
  }

  @Test fun drop_placesInRoomUnderPointOrSnapsToWall() {
    val room = CcRoom("kitchen", "Kitchen", CcRect(10, 10, 10, 10))
    val floor = CcFloor("g", "G", listOf(room))
    val dropped = CcPlanEdit.dropDevice(floor, "light.loose", 15f, 12f, reg, null)!!
    val d = dropped.rooms.single().devices.single()
    assertEquals(0.5f, d.fx, 0.01f); assertEquals(0.2f, d.fy, 0.01f)
    assertNull(CcPlanEdit.dropDevice(floor, "light.loose", 1f, 1f, reg, null))
    val moved = CcPlanEdit.dropDevice(dropped, "light.loose", 11f, 19f, reg, null)!!
    assertEquals(1, moved.rooms.single().devices.size)
    val wall = CcPlanEdit.dropDevice(floor, "binary_sensor.wild", 19.5f, 15f, reg, null)!!
    assertEquals(CcSide.E, wall.openings.single().side)
    assertEquals(CcSide.N, CcPlanEdit.nearestWall("x.y", room, 15f, 10.2f).side)
    assertEquals(CcSide.S, CcPlanEdit.nearestWall("x.y", room, 15f, 19.9f).side)
    assertEquals(CcSide.W, CcPlanEdit.nearestWall("x.y", room, 10.1f, 15f).side)
  }

  @Test fun rooms_drawMoveResizeDelete() {
    assertEquals(CcRect(2, 3, 4, 5), CcPlanEdit.rectFrom(6f, 8f, 2f, 3f))
    assertEquals(CcRect(2, 3, 1, 1), CcPlanEdit.rectFrom(2f, 3f, 2f, 3f))
    val r = CcRoom("a", "A", CcRect(0, 0, 4, 4))
    var f = CcPlanEdit.putRoom(CcFloor("g", "G"), r)
    f = CcPlanEdit.moveRoom(f, "a", 3, 2)
    assertEquals(CcRect(3, 2, 4, 4), f.rooms.single().rect)
    f = CcPlanEdit.moveRoom(f, "a", -10, 100)
    assertEquals(CcRect(0, CcPlan.GRID_H - 4, 4, 4), f.rooms.single().rect)
    f = CcPlanEdit.resizeRoom(f, "a", 8, 2)
    assertEquals(8, f.rooms.single().rect.w)
    val withOpening = f.copy(openings = listOf(CcOpening("binary_sensor.d", "a", CcSide.N, 0.5f, 0.3f)))
    val gone = CcPlanEdit.deleteRoom(withOpening, "a")
    assertTrue(gone.rooms.isEmpty() && gone.openings.isEmpty())
    assertTrue(CcPlanEdit.removeEntity(withOpening, "binary_sensor.d").openings.isEmpty())
  }

  @Test fun replaceFloor_addsOrReplacesAndStamps() {
    val p = CcPlanEdit.replaceFloor(CcPlan.EMPTY, CcFloor("g", "G"), 5L)
    assertEquals(1, p.floors.size); assertEquals(5L, p.updatedAt)
    val p2 = CcPlanEdit.replaceFloor(p, CcFloor("g", "Renamed"), 6L)
    assertEquals("Renamed", p2.floors.single().name)
    assertNotNull(p2.floor("g"))
  }

  @Test fun interest_foregroundAndBackground() {
    val fg = CcInterest.foreground(reg, null)!!
    assertTrue("light.k" in fg && "cover.gd" in fg && "sensor.ktemp" in fg)
    assertFalse("sensor.diag" in fg)
    val bg = CcInterest.background(reg)!!
    assertEquals(setOf("binary_sensor.kdoor", "cover.gd", "binary_sensor.wild"), bg)
    assertNull(CcInterest.foreground(null, null))
    assertNull(CcInterest.foreground(null, CcPlan(listOf(CcFloor("g", "G", listOf(CcRoom("r", "R", CcRect(0, 0, 1, 1),
        devices = listOf(CcPlaced("light.z", 0f, 0f)))))))))
    assertNull(CcInterest.background(null))
  }
}
