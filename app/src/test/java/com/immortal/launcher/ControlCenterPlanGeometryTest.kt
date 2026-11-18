/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterPlanGeometryTest {
  private val kitchen = CcRoom("kitchen", "Kitchen", CcRect(10, 2, 16, 14))
  private val living = CcRoom("living", "Living", CcRect(10, 16, 28, 12))
  private val drive = CcRoom("drive", "Driveway", CcRect(0, 0, 9, 30), outdoor = true)
  private val floor = CcFloor("g", "Ground floor", listOf(kitchen, living, drive))

  @Test fun boundsAreTheUnionOfRooms() {
    assertEquals(CcMapBox(0f, 0f, 38f, 30f), CcPlanGeo.bounds(floor))
  }

  @Test fun emptyFloorFallsBackToTheWholeGrid() {
    assertEquals(CcMapBox(0f, 0f, CcPlan.GRID_W.toFloat(), CcPlan.GRID_H.toFloat()), CcPlanGeo.bounds(CcFloor("x", "X")))
    assertEquals(CcMapBox(0f, 0f, CcPlan.GRID_W.toFloat(), CcPlan.GRID_H.toFloat()), CcPlanGeo.bounds(null))
  }

  @Test fun fitKeepsAspectAndCentres() {
    val box = CcMapBox(0f, 0f, 48f, 30f)
    val fit = CcPlanGeo.fit(box, 960f, 1200f, margin = 0f)
    assertEquals(20f, fit.scale, 1e-4f)
    // Wide plan in a tall canvas: centred vertically.
    assertEquals(0f, fit.x(0f), 1e-3f)
    assertEquals(960f, fit.x(48f), 1e-3f)
    assertEquals((1200f - 600f) / 2f, fit.y(0f), 1e-3f)
    assertEquals(24f, fit.cellX(fit.x(24f)), 1e-4f)
    assertEquals(15f, fit.cellY(fit.y(15f)), 1e-4f)
  }

  @Test fun fitHonoursMarginAndMaxScale() {
    val box = CcMapBox(0f, 0f, 10f, 10f)
    val fit = CcPlanGeo.fit(box, 1000f, 1000f, margin = 5f)
    assertEquals(50f, fit.scale, 1e-4f)
    val capped = CcPlanGeo.fit(box, 1000f, 1000f, margin = 0f, maxScale = 30f)
    assertEquals(30f, capped.scale, 1e-4f)
    assertEquals(500f, capped.x(5f), 1e-3f)
  }

  @Test fun fitOfZeroSizeIsSafe() {
    assertEquals(CcPlanFit(1f, 0f, 0f), CcPlanGeo.fit(CcMapBox(0f, 0f, 10f, 10f), 0f, 100f))
  }

  @Test fun openingSitsOnTheRightWall() {
    val r = CcRect(10, 2, 16, 14)
    assertEquals(CcMapLine(14f, 2f, 22f, 2f), CcPlanGeo.opening(r, CcSide.N, 0.5f, 0.5f))
    assertEquals(CcMapLine(14f, 16f, 22f, 16f), CcPlanGeo.opening(r, CcSide.S, 0.5f, 0.5f))
    assertEquals(CcMapLine(10f, 2f, 10f, 9f), CcPlanGeo.opening(r, CcSide.W, 0.25f, 0.5f))
    assertEquals(CcMapLine(26f, 9f, 26f, 16f), CcPlanGeo.opening(r, CcSide.E, 0.75f, 0.5f))
  }

  @Test fun openingIsClampedToTheWall() {
    val l = CcPlanGeo.opening(CcRect(0, 0, 10, 10), CcSide.N, 0f, 0.4f)
    assertEquals(0f, l.x1, 1e-4f)
    assertEquals(2f, l.x2, 1e-4f)
  }

  @Test fun doorSwingsIntoTheRoom() {
    val north = CcMapLine(0f, 0f, 4f, 0f)
    val end = CcPlanGeo.doorSwing(north, CcSide.N)
    assertTrue("swings down into the room", end.y > 3.5f)
    assertEquals(4f, kotlin.math.hypot(end.x, end.y), 1e-3f)
    val east = CcPlanGeo.doorSwing(CcMapLine(10f, 0f, 10f, 4f), CcSide.E)
    assertTrue("east wall swings left", end.x >= 0f && east.x < 10f)
  }

  @Test fun chipAnchorIsInsideTheRoom() {
    val west = CcPlanGeo.chipAnchor(CcMapLine(10f, 2f, 10f, 8f), CcSide.W)
    assertEquals(CcMapPt(10f + CcPlanGeo.CHIP_INSET_CELLS, 2f), west)
    val south = CcPlanGeo.chipAnchor(CcMapLine(2f, 10f, 6f, 10f), CcSide.S)
    assertEquals(CcMapPt(2f, 10f - CcPlanGeo.CHIP_INSET_CELLS), south)
  }

  @Test fun roomAtPrefersIndoorThenSmallest() {
    val big = CcRoom("big", "Big", CcRect(0, 0, 20, 20))
    val small = CcRoom("small", "Small", CcRect(2, 2, 4, 4))
    val yard = CcRoom("yard", "Yard", CcRect(0, 0, 4, 4), outdoor = true)
    val f = CcFloor("f", "F", listOf(yard, big, small))
    assertEquals("small", CcPlanGeo.roomAt(f, 3f, 3f)?.id)
    assertEquals("big", CcPlanGeo.roomAt(f, 1f, 1f)?.id)
    assertEquals("big", CcPlanGeo.roomAt(f, 10f, 10f)?.id)
    assertNull(CcPlanGeo.roomAt(f, 30f, 3f))
    assertEquals("drive", CcPlanGeo.roomAt(floor, 4f, 20f)?.id)
  }

  @Test fun deviceAndCentrePoints() {
    assertEquals(CcMapPt(14f, 9f), CcPlanGeo.device(CcRect(10, 2, 16, 14), 0.25f, 0.5f))
    assertEquals(CcMapPt(18f, 9f), CcPlanGeo.center(CcRect(10, 2, 16, 14)))
  }

  @Test fun glowGrowsWithBrightness() {
    assertEquals(CcPlanGeo.GLOW_CELLS * 0.35f, CcPlanGeo.glowRadius(0f), 1e-4f)
    assertEquals(CcPlanGeo.GLOW_CELLS * 1.1f, CcPlanGeo.glowRadius(1f), 1e-4f)
    assertEquals(CcPlanGeo.glowRadius(1f), CcPlanGeo.glowRadius(3f), 1e-4f)
  }

  @Test fun nearestFindsTheClosestWithinRadius() {
    val pts = listOf("a" to CcMapPt(0f, 0f), "b" to CcMapPt(3f, 0f), "c" to CcMapPt(10f, 0f))
    assertEquals("b", ccNearest(pts, 2.2f, 0f, 2f))
    assertEquals("a", ccNearest(pts, 0.5f, 0f, 2f))
    assertNull(ccNearest(pts, 6.5f, 0f, 2f))
    assertNull(ccNearest(emptyList(), 0f, 0f, 2f))
  }
}
