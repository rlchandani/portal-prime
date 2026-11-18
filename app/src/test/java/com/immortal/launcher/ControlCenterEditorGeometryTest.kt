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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterEditorGeometryTest {
  private val now = 5L
  private val door = CcOpening("binary_sensor.front_door", "living", CcSide.S, 0.5f, 0.3f)
  private val floor = CcFloor("ground", "Ground", listOf(kitchen.copy(devices = listOf(CcPlaced("light.kitchen", 0.5f, 0.5f))), living), listOf(door))

  @Test fun geom_aspectFitsAndRoundTrips() {
    val g = CcEdGeom(1200f, 1000f, insetTop = 100f, insetBottom = 0f, insetSide = 0f)
    assertEquals(25f, g.cell, 0.001f)
    assertEquals(0f, g.originX, 0.001f)
    assertEquals(100f + (900f - 750f) / 2f, g.originY, 0.001f)
    assertEquals(12f, g.gx(g.px(12f)), 0.001f)
    assertEquals(7f, g.gy(g.py(7f)), 0.001f)
    assertEquals(2f, g.cells(50f), 0.001f)
    assertTrue(g.onBoard(g.px(10f), g.py(10f)))
    assertFalse(g.onBoard(g.px(10f), 10f))
    assertTrue(CcEdGeom(0f, 0f).cell > 0f)
  }

  @Test fun hits_handleDeviceOpeningRooms() {
    assertTrue(CcEdGeo.onHandle(kitchen.rect, 11.2f, 9.1f, 0.5f))
    assertFalse(CcEdGeo.onHandle(kitchen.rect, 9f, 9f, 0.5f))
    assertEquals("light.kitchen", CcEdGeo.deviceAt(floor, 6.2f, 5.1f, 1f))
    assertNull(CcEdGeo.deviceAt(floor, 2f, 2f, 1f))
    assertEquals("binary_sensor.front_door", CcEdGeo.openingAt(floor, 17f, 9.2f, 0.5f))
    assertNull(CcEdGeo.openingAt(floor, 12f, 9f, 0.5f))
    assertEquals("living", CcEdGeo.roomAt(floor, 15f, 5f)!!.id)
    assertEquals("living", CcEdGeo.nearestRoom(floor, 30f, 5f)!!.id)
    assertNull(CcEdGeo.nearestRoom(CcFloor("x", "x"), 1f, 1f))
  }

  @Test fun segment_eachSide() {
    val r = CcRect(0, 0, 10, 20)
    assertEquals(CcWallSeg(4f, 0f, 6f, 0f), CcEdGeo.segment(r, CcOpening("a.b", "r", CcSide.N, 0.5f, 0.2f)))
    assertEquals(CcWallSeg(4f, 20f, 6f, 20f), CcEdGeo.segment(r, CcOpening("a.b", "r", CcSide.S, 0.5f, 0.2f)))
    assertEquals(CcWallSeg(0f, 0f, 0f, 4f), CcEdGeo.segment(r, CcOpening("a.b", "r", CcSide.W, 0.05f, 0.3f)))
    assertEquals(CcWallSeg(10f, 8f, 10f, 12f), CcEdGeo.segment(r, CcOpening("a.b", "r", CcSide.E, 0.5f, 0.2f)))
    assertEquals(5f, CcEdGeo.distanceToSegment(0f, 5f, CcWallSeg(0f, 0f, 0f, 0f)), 0.001f)
  }

  @Test fun moveOpening_keepsSpanAndClampsIntoRoom() {
    val moved = CcEdGeo.moveOpening(floor, door.entityId, 30f, 3f)!!
    val o = moved.openings.single()
    assertEquals(CcSide.E, o.side)
    assertEquals("living", o.roomId)
    assertEquals(0.3f, o.span, 0.001f)
  }

  @Test fun openingKind_fromClass() {
    assertEquals(CcEdOpening.WINDOW, CcEdGeo.openingKind("binary_sensor.kitchen_window", reg, null))
    assertEquals(CcEdOpening.DOOR, CcEdGeo.openingKind("binary_sensor.front_door", reg, null))
    assertEquals(CcEdOpening.GARAGE, CcEdGeo.openingKind("cover.garage", reg, null))
  }

  @Test fun flyOffset_staggersAndLands() {
    assertEquals(1f, CcEdGeo.flyOffset(0f, 0, 3), 0.001f)
    assertEquals(0f, CcEdGeo.flyOffset(1f, 2, 3), 0.001f)
    assertTrue(CcEdGeo.flyOffset(0.3f, 2, 3) > CcEdGeo.flyOffset(0.3f, 0, 3))
    assertEquals(950, CcEdGeo.flyDurationMs(3))
    assertEquals(900, CcEdGeo.flyDurationMs(0))
  }

  @Test fun begin_dependsOnStepAndTool() {
    val rooms = EdFixture.withRooms(kitchen, living)
    val f = CcEd.floor(rooms, reg)
    assertTrue(CcEdGesture.begin(rooms, f, 30f, 20f, 1f) is CcEdDrag.Draw)
    val move = rooms.copy(tool = CcEdTool.MOVE)
    assertEquals("kitchen", (CcEdGesture.begin(move, f, 3f, 3f, 1f) as CcEdDrag.Move).roomId)
    assertNull(CcEdGesture.begin(move, f, 40f, 25f, 1f))
    val sel = rooms.copy(selectedRoom = "kitchen")
    assertTrue(CcEdGesture.begin(sel, f, 11f, 9f, 1f) is CcEdDrag.Resize)
    assertNull(CcEdGesture.begin(rooms.copy(pendingRect = CcRect(1, 1, 3, 3)), f, 30f, 20f, 1f))
    assertTrue(CcEdGesture.begin(rooms.copy(step = CcEdStep.DEVICES), floor, 6f, 5f, 1f) is CcEdDrag.Device)
    assertTrue(CcEdGesture.begin(rooms.copy(step = CcEdStep.OPENINGS), floor, 17f, 9f, 1f) is CcEdDrag.Opening)
    assertNull(CcEdGesture.begin(rooms.copy(step = CcEdStep.FOUND), floor, 6f, 5f, 1f))
  }

  @Test fun update_clampsAndCarriesDevices() {
    val d = CcEdGesture.update(CcEdDrag.Draw(1f, 1f, 1f, 1f), 99f, -3f) as CcEdDrag.Draw
    assertEquals(CcPlan.GRID_W.toFloat(), d.bx, 0.001f)
    assertEquals(0f, d.by, 0.001f)
    assertEquals(d, CcEdGesture.update(d, null, null))
    assertEquals(CcEdDrag.Device("a.b", null, null), CcEdGesture.update(CcEdDrag.Device("a.b", 1f, 1f), null, null))
    assertEquals(CcEdDrag.Opening("a.b", 2f, 3f), CcEdGesture.update(CcEdDrag.Opening("a.b", 1f, 1f), 2f, 3f))
  }

  @Test fun preview_andHotRoom() {
    val moved = CcEdGesture.preview(floor, CcEdDrag.Move("kitchen", 2f, 2f, 4.4f, 2.6f))
    assertEquals(CcRect(3, 2, 10, 8), moved.rooms.first { it.id == "kitchen" }.rect)
    val resized = CcEdGesture.preview(floor, CcEdDrag.Resize("kitchen", kitchen.rect, 1.5f, 1.5f))
    assertEquals(CcRect(1, 1, 2, 2), resized.rooms.first { it.id == "kitchen" }.rect)
    val carried = CcEdGesture.preview(floor, CcEdDrag.Device("light.kitchen", null, null))
    assertTrue(carried.rooms.all { it.devices.isEmpty() })
    assertEquals(floor, CcEdGesture.preview(floor, null))
    assertEquals("living", CcEdGesture.hotRoom(floor, CcEdDrag.Device("light.porch", 15f, 4f)))
    assertNull(CcEdGesture.hotRoom(floor, CcEdDrag.Device("light.porch", null, null)))
  }

  @Test fun commit_routesToReducer() {
    val st = EdFixture.withRooms(kitchen, living)
    assertEquals(CcRect(30, 10, 6, 6), CcEdGesture.commit(st, CcEdDrag.Draw(30f, 10f, 36f, 16f), reg, live, now).pendingRect)
    assertEquals(CcRect(2, 1, 10, 8), CcEdGesture.commit(st, CcEdDrag.Move("kitchen", 1f, 1f, 2f, 1f), reg, live, now).plan.room("kitchen")!!.rect)
    assertEquals(CcRect(1, 1, 4, 3), CcEdGesture.commit(st, CcEdDrag.Resize("kitchen", kitchen.rect, 5f, 4f), reg, live, now).plan.room("kitchen")!!.rect)
    assertEquals("living", CcEdGesture.commit(st, CcEdDrag.Device("light.porch", 15f, 4f), reg, live, now).plan.roomOf("light.porch")!!.id)
    val snapped = CcEd.snapOpenings(st, reg, live, now)
    val moved = CcEdGesture.commit(snapped, CcEdDrag.Opening("binary_sensor.front_door", 11.2f, 4f), reg, live, now)
    assertTrue(moved.plan.floors.single().openings.any { it.entityId == "binary_sensor.front_door" })
  }

  @Test fun tap_acceptsSuggestionOrTogglesSelection() {
    val st = CcEd.suggest(EdFixture.withRooms(kitchen), reg)
    val sugg = st.suggestions.single()
    val f = CcEd.floor(st, reg)
    val accepted = CcEdGesture.tap(st, f, sugg.rect.x + 0.5f, sugg.rect.y + 0.5f, reg, now)
    assertTrue(accepted.suggestions.isEmpty())
    val plain = EdFixture.withRooms(kitchen)
    val selected = CcEdGesture.tap(plain, CcEd.floor(plain, reg), 3f, 3f, reg, now)
    assertEquals("kitchen", selected.selectedRoom)
    assertNull(CcEdGesture.tap(selected, CcEd.floor(selected, reg), 3f, 3f, reg, now).selectedRoom)
    val devices = plain.copy(step = CcEdStep.DEVICES)
    assertEquals(devices, CcEdGesture.tap(devices, CcEd.floor(devices, reg), 3f, 3f, reg, now))
  }
}
