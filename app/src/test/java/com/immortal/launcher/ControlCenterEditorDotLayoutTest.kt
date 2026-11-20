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
import kotlin.math.hypot

/**
 * Mock-fidelity CC-5: device dots in a large room no longer pile up. Canvas sizes (dp) follow the
 * editor's layouts: the large two-pane layout, the ten-inch / turned Mini (compact landscape, side
 * panel beside the canvas) and the upright Mini (compact portrait, canvas above the panel).
 */
class ControlCenterEditorDotLayoutTest {
  private data class Canvas(val name: String, val w: Float, val h: Float)

  private val canvases = listOf(
      Canvas("large", 1298f, 1060f),
      Canvas("compact landscape (10-inch, Mini turned)", 658f, 640f),
      Canvas("Mini upright", 740f, 520f),
      Canvas("Mini upright, short", 740f, 400f),
  )

  private fun geom(c: Canvas) = CcEdGeom(c.w, c.h, CANVAS_TOP.toFloat(), 20f, 20f)
  private fun spec(c: Canvas) = CcEdDotSpec.of(geom(c).cell, DOT_DP.toFloat(), DOT_GAP_DP.toFloat())
  private val eps = 1e-3f

  /** Devices piled on one spot, the way the old 24-slot cycle (and many drops) left them. */
  private fun piled(n: Int) = List(n) { CcPlaced("light.l$it", 0.2f + (it % 4) * 0.2f, 0.2f) }

  private val great = CcRoom("great", "Great room", CcRect(2, 2, 22, 14))
  private val kitchen = CcRoom("kitchen", "Kitchen", CcRect(26, 2, 10, 8))

  private fun floorOf(vararg rooms: CcRoom) = CcFloor("ground", "Ground", rooms.toList())

  private fun assertSeparated(where: String, pts: List<Pair<Float, Float>>, min: Float) {
    for (i in pts.indices) for (j in i + 1 until pts.size) {
      val d = hypot(pts[i].first - pts[j].first, pts[i].second - pts[j].second)
      assertTrue("$where: dots $i and $j are ${d} cells apart, need $min", d >= min - eps)
    }
  }

  @Test fun noTwoDotsOverlapAndAllStayInsideTheRoomOnEveryLayout() {
    for (c in canvases) for (n in listOf(1, 5, 12, 24, 25, 40, 80)) {
      val s = spec(c)
      val room = great.copy(devices = piled(n))
      val layout = CcEdDots.layout(floorOf(room), s)
      val shown = layout.dots.filterNot { it.ring }
      val where = "${c.name}, $n devices"
      assertSeparated(where, shown.map { it.gx to it.gy } + layout.clusters.map { it.gx to it.gy }, s.pitch)
      (shown.map { it.gx to it.gy } + layout.clusters.map { it.gx to it.gy }).forEach { (x, y) ->
        assertTrue("$where: x $x", x >= room.rect.x + s.radius - eps && x <= room.rect.right - s.radius + eps)
        assertTrue("$where: y $y", y >= room.rect.y + s.radius - eps && y <= room.rect.bottom - s.radius + eps)
      }
      // Every device is either a dot or counted in the cluster, exactly once.
      val all = shown.map { it.entityId } + layout.clusters.flatMap { it.hidden }
      assertEquals(where, room.devices.map { it.entityId }.sorted(), all.sorted())
    }
  }

  @Test fun theOld24SlotCycleNoLongerStacksDotsOnTheSameSpot() {
    val ids = List(30) { "light.l$it" }
    val stacked = great.copy(devices = ids.mapIndexed { i, id -> CcPlanEdit.deviceSlot(i).let { (x, y) -> CcPlaced(id, x, y) } })
    // Before: slot 24 sits exactly on slot 0.
    assertEquals(CcPlanEdit.deviceSlot(0), CcPlanEdit.deviceSlot(24))
    val s = spec(canvases[0])
    val layout = CcEdDots.layout(floorOf(stacked), s)
    assertSeparated("large", layout.dots.map { it.gx to it.gy }, s.pitch)
  }

  @Test fun tooManyForTheRoomBecomesAPlusNClusterThatHoldsTheRest() {
    val c = canvases[2] // Mini upright: the tightest canvas
    val s = spec(c)
    val small = kitchen.copy(devices = piled(40))
    val layout = CcEdDots.layout(floorOf(small), s)
    val cluster = layout.clusters.single()
    assertEquals("kitchen", cluster.roomId)
    assertEquals("+${cluster.hidden.size}", cluster.label)
    assertEquals(40, layout.dots.size + cluster.hidden.size)
    // The cluster sits in the bottom-right slot and keeps the room's last devices.
    assertTrue(layout.dots.all { it.gy <= cluster.gy + eps })
    assertEquals(small.devices.takeLast(cluster.hidden.size).map { it.entityId }, cluster.hidden)
  }

  @Test fun aRoomThatFitsHasNoCluster() {
    val layout = CcEdDots.layout(floorOf(great.copy(devices = piled(6)), kitchen.copy(devices = piled(2))), spec(canvases[0]))
    assertTrue(layout.clusters.isEmpty())
    assertEquals(8, layout.dots.size)
  }

  @Test fun anOpenClusterRingsItsDevicesWithoutOverlapAndOnTheBoard() {
    for (c in canvases) {
      val s = spec(c)
      // Bottom-right corner of the board: some ring spots fall off it and are skipped, never stacked.
      val corner = CcRoom("corner", "Corner", CcRect(40, 24, 8, 6), devices = piled(40))
      val closed = CcEdDots.layout(floorOf(corner), s)
      val open = CcEdDots.layout(floorOf(corner), s, expandedRoom = "corner")
      val cluster = open.clusters.single()
      assertTrue(cluster.expanded)
      val ring = open.dots.filter { it.ring }
      assertEquals(c.name, cluster.hidden, ring.map { it.entityId })
      assertSeparated("${c.name} ring", ring.map { it.gx to it.gy } + (cluster.gx to cluster.gy), s.pitch)
      ring.forEach {
        assertTrue(it.gx >= s.radius - eps && it.gx <= CcPlan.GRID_W - s.radius + eps)
        assertTrue(it.gy >= s.radius - eps && it.gy <= CcPlan.GRID_H - s.radius + eps)
      }
      // The room's own dots don't move when the ring opens.
      assertEquals(closed.dots, open.dots.filterNot { it.ring })
    }
  }

  @Test fun dotsKeepClearOfTheRoomLabelWhenTheresRoom() {
    val s = spec(canvases[0])
    val room = great.copy(devices = List(6) { CcPlaced("light.l$it", 0.5f, 0.5f) })
    val label = 4f to 1.2f
    val layout = CcEdDots.layout(floorOf(room), s, labels = mapOf("great" to label))
    val cx = room.rect.x + room.rect.w / 2f
    val cy = room.rect.y + room.rect.h / 2f
    layout.dots.forEach {
      val onLabel = kotlin.math.abs(it.gx - cx) < label.first + s.radius && kotlin.math.abs(it.gy - cy) < label.second + s.radius
      assertFalse("${it.entityId} covers the label", onLabel)
    }
  }

  @Test fun earlierDevicesKeepTheirSpotAndEachLandsNearItsSavedPlace() {
    val s = spec(canvases[0])
    val a = CcPlaced("light.a", 0.1f, 0.1f)
    val b = CcPlaced("light.b", 0.9f, 0.9f)
    val layout = CcEdDots.layout(floorOf(great.copy(devices = listOf(a, b))), s)
    val da = layout.dots.first { it.entityId == "light.a" }
    val db = layout.dots.first { it.entityId == "light.b" }
    assertTrue(da.fx < 0.25f && da.fy < 0.3f)
    assertTrue(db.fx > 0.75f && db.fy > 0.7f)
  }

  @Test fun aTinyRoomStillShowsSomethingInsideIt() {
    val s = spec(canvases[2])
    val tiny = CcRoom("closet", "Closet", CcRect(30, 20, 1, 1), devices = piled(3))
    val layout = CcEdDots.layout(floorOf(tiny), s)
    val cluster = layout.clusters.single()
    assertTrue(layout.dots.isEmpty())
    assertEquals(3, cluster.hidden.size)
    assertEquals(30.5f, cluster.gx, eps)
  }

  @Test fun tapOpensClosesAndAnywhereElseCloses() {
    val s = spec(canvases[2])
    val floor = floorOf(kitchen.copy(devices = piled(40)))
    val layout = CcEdDots.layout(floor, s)
    val cl = layout.clusters.single()
    assertEquals(true to "kitchen", CcEdDots.tap(layout, null, cl.gx, cl.gy, 1f))
    val open = CcEdDots.layout(floor, s, "kitchen")
    assertEquals(true to null, CcEdDots.tap(open, "kitchen", cl.gx, cl.gy, 1f))
    assertEquals(false to null, CcEdDots.tap(open, "kitchen", 1f, 28f, 1f))
  }

  @Test fun dragPicksUpTheDotWhereItIsDrawnIncludingFromAnOpenRing() {
    for (c in canvases) {
      val s = spec(c)
      val g = geom(c)
      val tol = g.cells(HIT_PX)
      val floor = floorOf(kitchen.copy(devices = piled(40)), great.copy(devices = List(20) { CcPlaced("switch.s$it", 0.5f, 0.5f) }))
      val st = EdFixture.withRooms().copy(step = CcEdStep.DEVICES)
      val layout = CcEdDots.layout(floor, s, "kitchen")
      val ring = layout.dots.filter { it.ring }
      layout.dots.forEach { d ->
        // An open ring is drawn over the room, so it wins where it covers a room dot.
        val covered = !d.ring && ring.any { hypot(it.gx - d.gx, it.gy - d.gy) <= tol }
        if (!covered) assertEquals(c.name, CcEdDrag.Device(d.entityId, d.gx, d.gy), CcEdGesture.begin(st, floor, d.gx, d.gy, tol, layout))
      }
      assertTrue(ring.isNotEmpty())
      // A closed cluster isn't a device: a press on it grabs nothing (its neighbours are a pitch away).
      val closed = CcEdDots.layout(floor, s)
      val cl = closed.clusters.first { it.roomId == "kitchen" }
      assertNull(c.name, CcEdGesture.begin(st, floor, cl.gx, cl.gy, tol, closed))
    }
  }

  @Test fun aRingDeviceDragsOntoAnotherRoomAndLeavesTheCluster() {
    val s = spec(canvases[2])
    val kitchenFull = kitchen.copy(devices = piled(40))
    val living = CcRoom("living", "Living room", CcRect(2, 12, 20, 14))
    val base = EdFixture.withRooms()
    val st = base.copy(step = CcEdStep.DEVICES, plan = CcPlan(listOf(CcFloor("ground", "Ground", listOf(kitchenFull, living)))))
    val floor = CcEd.floor(st, EdFixture.reg)
    val open = CcEdDots.layout(floor, s, "kitchen")
    val ringDot = open.dots.first { it.ring }
    val tol = geom(canvases[2]).cells(HIT_PX)
    val drag = assertNotNullDrag(CcEdGesture.begin(st, floor, ringDot.gx, ringDot.gy, tol, open))
    val moved = CcEdGesture.update(drag, 10f, 18f)
    val after = CcEdGesture.commit(st, moved, EdFixture.reg, EdFixture.live, 1L)
    assertEquals("living", after.plan.roomOf(ringDot.entityId)!!.id)
    val relaid = CcEdDots.layout(CcEd.floor(after, EdFixture.reg), s, "kitchen")
    assertFalse(relaid.clusters.single { it.roomId == "kitchen" }.hidden.contains(ringDot.entityId))
    assertTrue(relaid.dots.any { it.entityId == ringDot.entityId && it.roomId == "living" && !it.ring })
  }

  @Test fun withoutALayoutTheSavedSpotsStillWork() {
    val floor = floorOf(kitchen.copy(devices = listOf(CcPlaced("light.kitchen", 0.5f, 0.5f))))
    val st = EdFixture.withRooms().copy(step = CcEdStep.DEVICES)
    assertEquals(CcEdDrag.Device("light.kitchen", 31f, 6f), CcEdGesture.begin(st, floor, 31f, 6f, 1f))
  }

  @Test fun theSpecFollowsTheCanvasCell() {
    val s = CcEdDotSpec.of(cellPx = 18f, dotPx = 36f, gapPx = 10f)
    assertEquals(2f, s.dotCells, eps)
    assertEquals(46f / 18f, s.pitch, eps)
    assertTrue(CcEdDotSpec.of(0f, 36f, 10f).dotCells.isFinite())
  }

  private fun assertNotNullDrag(d: CcEdDrag?): CcEdDrag {
    assertNotNull(d)
    return d!!
  }

  private companion object {
    /** The canvas's finger tolerance ([HIT_DP] there), in dp. */
    const val HIT_PX = 34f
  }
}
