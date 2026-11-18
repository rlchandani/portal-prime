/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.EdFixture.kitchen
import com.immortal.launcher.EdFixture.live
import com.immortal.launcher.EdFixture.living
import com.immortal.launcher.EdFixture.reg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterEditorModelTest {
  private val now = 1_000L

  @Test fun start_emptyPlan_isFirstBuildOnStepOne() {
    val st = CcEd.start(CcPlan.EMPTY, reg)
    assertTrue(st.first)
    assertEquals(CcEdStep.FOUND, st.step)
    assertEquals(CcEdTool.DRAW, st.tool)
    assertEquals("ground", st.floorId)
    assertEquals("Step 1 of 5", st.stepLabel)
  }

  @Test fun start_existingPlan_editsRoomsWithoutWizard() {
    val st = CcEd.start(CcPlan(listOf(CcFloor("ground", "Ground", listOf(kitchen)))), reg)
    assertFalse(st.first)
    assertEquals(CcEdStep.ROOMS, st.step)
    assertEquals(CcEdTool.MOVE, st.tool)
    assertEquals("Rooms", st.stepLabel)
  }

  @Test fun floors_keepPlanFloorsHaNoLongerLists() {
    val plan = CcPlan(listOf(CcFloor("attic", "Attic", listOf(kitchen))))
    assertEquals(listOf("ground", "up", "attic"), CcEd.floors(reg, plan).map { it.id })
  }

  @Test fun steps_nextPreviousAndContinueGate() {
    val st = CcEd.next(CcEd.start(null, reg))
    assertEquals(CcEdStep.ROOMS, st.step)
    assertFalse(CcEd.canContinue(st))
    assertEquals(CcEdStep.FOUND, CcEd.previous(st).step)
    assertEquals(CcEdStep.FOUND, CcEd.previous(CcEd.previous(st)).step)
    val withRoom = EdFixture.withRooms(kitchen)
    assertTrue(CcEd.canContinue(withRoom))
    val ready = generateSequence(withRoom) { CcEd.next(it) }.take(5).last()
    assertEquals(CcEdStep.READY, ready.step)
    assertEquals(ready, CcEd.next(ready))
  }

  @Test fun drawn_tooSmall_isIgnoredWithMessage() {
    val st = CcEd.drawn(EdFixture.withRooms(), CcRect(1, 1, 1, 5), reg, now)
    assertNull(st.pendingRect)
    assertNotNull(st.message)
  }

  @Test fun drawn_asksForAreaThenAssigns() {
    val asked = CcEd.drawn(EdFixture.withRooms(), CcRect(2, 2, 6, 5), reg, now)
    assertEquals(CcRect(2, 2, 6, 5), asked.pendingRect)
    assertEquals(listOf("kitchen", "living"), CcEd.unassignedAreas(asked, reg).map { it.id })
    val done = CcEd.assign(asked, reg.areas[0], reg, now)
    assertNull(done.pendingRect)
    assertEquals("kitchen", done.selectedRoom)
    assertTrue(done.dirty)
    assertEquals(CcRect(2, 2, 6, 5), done.plan.room("kitchen")!!.rect)
    assertEquals("Ground", done.plan.floors.single().name)
  }

  @Test fun drawn_withSelectedArea_placesAtOnce() {
    val st = CcEd.selectArea(EdFixture.withRooms(), "living")
    assertEquals("living", st.selectedArea)
    val done = CcEd.drawn(st, CcRect(5, 5, 8, 6), reg, now)
    assertNotNull(done.plan.room("living"))
    assertNull(done.selectedArea)
    assertEquals(null, CcEd.selectArea(st, "living").selectedArea)
  }

  @Test fun nameRoom_withoutAreas_usesLocalIds() {
    val pending = EdFixture.withRooms().copy(pendingRect = CcRect(1, 1, 4, 4))
    assertEquals(pending.pendingRect, CcEd.nameRoom(pending, "   ", reg, now).pendingRect)
    val one = CcEd.nameRoom(pending, " Studio ", reg, now)
    assertEquals("Studio", one.plan.room("room_1")!!.name)
    assertEquals("room_2", CcEd.nextRoomId(one.plan))
    assertEquals(pending, CcEd.nameRoom(pending.copy(pendingRect = null), "x", reg, now).copy(pendingRect = pending.pendingRect))
    assertNull(CcEd.cancelPending(pending).pendingRect)
  }

  @Test fun suggest_onlyUndrawnAreas_andAccept() {
    val st = CcEd.suggest(EdFixture.withRooms(kitchen), reg)
    assertEquals(listOf("living"), st.suggestions.map { it.id })
    val accepted = CcEd.acceptSuggestion(st, "living", reg, now)
    assertTrue(accepted.suggestions.isEmpty())
    assertNotNull(accepted.plan.room("living"))
    assertEquals(st, CcEd.acceptSuggestion(st, "nope", reg, now))
    val all = CcEd.acceptAll(CcEd.suggest(EdFixture.withRooms(), reg), reg, now)
    assertEquals(2, all.plan.floors.single().rooms.size)
    val noAreas = CcEd.suggest(EdFixture.withRooms(), CcRegistry())
    assertTrue(noAreas.suggestions.isEmpty())
    assertNotNull(noAreas.message)
  }

  @Test fun areaStatus_placedOutdoorToDraw() {
    val st = EdFixture.withRooms(kitchen)
    assertEquals(CcAreaStatus.PLACED, CcEd.areaStatus(st, reg.areas[0]))
    assertEquals(CcAreaStatus.TO_DRAW, CcEd.areaStatus(st, reg.areas[1]))
    assertEquals(CcAreaStatus.OUTDOOR, CcEd.areaStatus(st, CcHaArea("patio", "Patio", "ground")))
  }

  @Test fun moveResizeDelete_returnNewPlans() {
    val st = EdFixture.withRooms(kitchen, living)
    val moved = CcEd.moveRoom(st, "kitchen", 2, 3, reg, now)
    assertEquals(CcRect(3, 4, 10, 8), moved.plan.room("kitchen")!!.rect)
    assertEquals(CcRect(1, 1, 10, 8), st.plan.room("kitchen")!!.rect)
    assertFalse(CcEd.moveRoom(st, "kitchen", 0, 0, reg, now).dirty)
    assertEquals(CcRect(1, 1, 2, 2), CcEd.resizeRoom(st, "kitchen", 0, 1, reg, now).plan.room("kitchen")!!.rect)
    val deleted = CcEd.deleteRoom(CcEd.selectRoom(st, "kitchen"), "kitchen", reg, now)
    assertNull(deleted.plan.room("kitchen"))
    assertNull(deleted.selectedRoom)
    assertEquals(st, CcEd.deleteRoom(st, "missing", reg, now))
  }

  @Test fun autoPlaceDevices_skipsOpeningsAndArmsFlyIn() {
    val st = CcEd.goStep(EdFixture.withRooms(kitchen, living), CcEdStep.DEVICES)
    assertEquals(listOf("light.kitchen", "light.living", "light.porch"), CcEd.unplacedDevices(st, reg, live))
    assertEquals(2, CcEd.autoPlaceable(CcEd.unplacedDevices(st, reg, live), st, reg))
    val placed = CcEd.autoPlaceDevices(st, reg, live, now)
    assertEquals(setOf("light.kitchen", "light.living"), placed.flyIds)
    assertEquals(1, placed.flySeq)
    assertTrue(placed.plan.floors.single().openings.isEmpty())
    assertEquals(2 to 3, CcEd.deviceProgress(placed, reg, live))
    val again = CcEd.autoPlaceDevices(placed, reg, live, now)
    assertEquals(1, again.flySeq)
    assertNotNull(again.message)
  }

  @Test fun snapOpenings_putsSensorsOnWallsOnly() {
    val st = CcEd.goStep(EdFixture.withRooms(kitchen, living), CcEdStep.OPENINGS)
    assertEquals(listOf("binary_sensor.front_door", "binary_sensor.kitchen_window"), CcEd.unplacedOpenings(st, reg, live))
    val snapped = CcEd.snapOpenings(st, reg, live, now)
    assertEquals(2, snapped.plan.floors.single().openings.size)
    assertTrue(snapped.plan.floors.single().rooms.all { it.devices.isEmpty() })
    assertNotNull(CcEd.snapOpenings(snapped, reg, live, now).message)
  }

  @Test fun drop_intoRoom_offCanvasAndMiss() {
    val st = EdFixture.withRooms(kitchen)
    val dropped = CcEd.drop(st, "light.porch", 5f, 5f, reg, live, "Porch", now)
    assertEquals("Porch → Kitchen", dropped.message)
    assertEquals("kitchen", dropped.plan.roomOf("light.porch")!!.id)
    val missed = CcEd.drop(st, "light.porch", 40f, 25f, reg, live, "Porch", now)
    assertEquals("Drop it inside a room", missed.message)
    assertFalse(missed.dirty)
    val unplaced = CcEd.drop(dropped, "light.porch", null, null, reg, live, "Porch", now)
    assertNull(unplaced.plan.roomOf("light.porch"))
    assertEquals(st, CcEd.drop(st, "light.porch", null, null, reg, live, "Porch", now))
  }

  @Test fun moveOpening_resnapsToNearestWall() {
    val st = CcEd.snapOpenings(EdFixture.withRooms(kitchen, living), reg, live, now)
    val moved = CcEd.moveOpening(st, "binary_sensor.front_door", 15f, 1.2f, reg, now)
    val o = moved.plan.floors.single().openings.first { it.entityId == "binary_sensor.front_door" }
    assertEquals(CcSide.N, o.side)
    val empty = EdFixture.withRooms()
    assertEquals(empty, CcEd.moveOpening(empty, "x.y", 1f, 1f, reg, now))
  }

  @Test fun summaryAndSave_dropEmptyFloorsAndStamp() {
    val st = EdFixture.withRooms(kitchen.copy(devices = listOf(CcPlaced("light.kitchen", 0.5f, 0.5f))))
    val plan = st.plan.copy(floors = st.plan.floors + CcFloor("up", "Upstairs"))
    assertEquals(CcPlanSummary(1, 1, 0), CcEd.summary(plan))
    val saved = CcEd.forSave(st.copy(plan = plan), 42L)
    assertEquals(listOf("ground"), saved.floors.map { it.id })
    assertEquals(42L, saved.updatedAt)
  }

  @Test fun setFloorAndTool_clearTransientState() {
    val st = EdFixture.withRooms(kitchen).copy(selectedRoom = "kitchen", pendingRect = CcRect(1, 1, 3, 3))
    val other = CcEd.setFloor(st, "up")
    assertEquals("up", other.floorId)
    assertNull(other.selectedRoom)
    assertNull(other.pendingRect)
    assertEquals("Upstairs", CcEd.floor(other, reg).name)
    assertNull(CcEd.setTool(st, CcEdTool.MOVE).pendingRect)
  }

  @Test fun fallbackRegistry_listsLiveEntitiesWithoutAreas() {
    val e = CcEntity("binary_sensor.door", "off", mapOf("device_class" to "door"))
    val fb = CcEd.fallbackRegistry(mapOf(e.id to e))
    assertEquals(setOf("binary_sensor.door"), fb.entities)
    assertEquals("door", fb.entityClass["binary_sensor.door"])
    assertTrue(fb.areas.isEmpty())
    assertEquals(listOf("home"), CcEd.floors(fb, CcPlan.EMPTY).map { it.id })
  }

  @Test fun foundCounts_floorsAreasAndDevices() {
    assertEquals(CcFoundCounts(floors = 2, areas = 3, withArea = 5, withoutArea = 1), CcEdFound.counts(reg, live))
    assertEquals(CcFoundCounts(0, 0, 0, 1), CcEdFound.counts(CcRegistry(entities = setOf("light.a")), live))
  }
}
