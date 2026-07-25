/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class DragHitTestTest {

  // Test bounds — grid occupies upper region, dock occupies lower strip
  private val gridBounds = Rect(left = 0f, top = 0f, right = 600f, bottom = 400f)
  private val dockBounds = Rect(left = 0f, top = 450f, right = 600f, bottom = 550f)

  // Grid slots: 3x2 grid (6 slots in row-major order)
  // Row 0: [0,0,100,100], [100,0,200,100], [200,0,300,100]
  // Row 1: [0,100,100,200], [100,100,200,200], [200,100,300,200]
  private val gridSlots = listOf(
    Rect(0f, 0f, 100f, 100f),    // index 0
    Rect(100f, 0f, 200f, 100f),  // index 1
    Rect(200f, 0f, 300f, 100f),  // index 2
    Rect(0f, 100f, 100f, 200f),  // index 3
    Rect(100f, 100f, 200f, 200f),// index 4
    Rect(200f, 100f, 300f, 200f) // index 5
  )

  // Dock slots: 3 horizontal slots
  // [50,450,150,550], [200,450,300,550], [350,450,450,550]
  private val dockSlots = listOf(
    Rect(50f, 450f, 150f, 550f),   // index 0
    Rect(200f, 450f, 300f, 550f),  // index 1
    Rect(350f, 450f, 450f, 550f)   // index 2
  )

  // ─────────────────────────────────────────────────────────────
  // DOCK REGION TESTS
  // ─────────────────────────────────────────────────────────────

  @Test
  fun hitTest_pointInDock_leftOfFirstSlot_returnsIndex0() {
    // Point left of dock slot 0 center (center at x=100, point at x=60)
    val point = Offset(60f, 500f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.DOCK, result.container)
    assertEquals(0, result.index)
  }

  @Test
  fun hitTest_pointInDock_rightOfFirstSlot_returnsIndex1() {
    // Point right of dock slot 0 center (center at x=100, point at x=120)
    val point = Offset(120f, 500f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.DOCK, result.container)
    assertEquals(1, result.index)
  }

  @Test
  fun hitTest_pointInDock_overMiddleSlot_returnsCorrectIndex() {
    // Point left of slot 1 center (center at x=250, point at x=220)
    val point = Offset(220f, 500f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.DOCK, result.container)
    assertEquals(1, result.index)
  }

  @Test
  fun hitTest_pointInDock_rightOfMiddleSlot_returnsIndex2() {
    // Point right of slot 1 center (center at x=250, point at x=270)
    val point = Offset(270f, 500f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.DOCK, result.container)
    assertEquals(2, result.index)
  }

  @Test
  fun hitTest_pointInDock_pastLastSlot_returnsAppendIndex() {
    // Point right of dock slot 2 center (center at x=400, point at x=500)
    val point = Offset(500f, 500f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.DOCK, result.container)
    assertEquals(3, result.index) // dockSlots.size
  }

  @Test
  fun hitTest_pointInDock_emptySlotList_returnsIndex0() {
    val point = Offset(300f, 500f)
    val result = hitTest(point, gridBounds, dockBounds, emptyList(), emptyList())
    assertEquals(DropContainer.DOCK, result.container)
    assertEquals(0, result.index)
  }

  // ─────────────────────────────────────────────────────────────
  // GRID REGION TESTS
  // ─────────────────────────────────────────────────────────────

  @Test
  fun hitTest_pointInGrid_overSlot0_returnsIndex0() {
    // Point inside slot 0 (center at 50,50)
    val point = Offset(50f, 50f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.GRID, result.container)
    assertEquals(0, result.index)
  }

  @Test
  fun hitTest_pointInGrid_overSlot1_returnsIndex1() {
    // Point inside slot 1 (center at 150,50)
    val point = Offset(150f, 50f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.GRID, result.container)
    assertEquals(1, result.index)
  }

  @Test
  fun hitTest_pointInGrid_overSlot4_returnsIndex4() {
    // Point inside slot 4 (center at 150,150)
    val point = Offset(150f, 150f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.GRID, result.container)
    assertEquals(4, result.index)
  }

  @Test
  fun hitTest_pointInGrid_overSlot5_returnsIndex5() {
    // Point inside slot 5 (center at 250,150)
    val point = Offset(250f, 150f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.GRID, result.container)
    assertEquals(5, result.index)
  }

  @Test
  fun hitTest_pointInGrid_betweenSlots_returnsNearestSlot() {
    // Point between slot 0 and slot 1 (at x=90, y=50) — closer to slot 0 center (50,50)
    val point = Offset(90f, 50f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.GRID, result.container)
    assertEquals(0, result.index) // Nearest to slot 0
  }

  @Test
  fun hitTest_pointInGrid_pastLastSlot_returnsAppendIndex() {
    // Point in grid bounds but far from all slots (at 500,300) — nearest is slot 5 (250,150)
    val point = Offset(500f, 300f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.GRID, result.container)
    assertEquals(5, result.index) // Nearest slot is 5
  }

  @Test
  fun hitTest_pointInGrid_emptySlotList_returnsIndex0() {
    val point = Offset(300f, 200f)
    val result = hitTest(point, gridBounds, dockBounds, emptyList(), emptyList())
    assertEquals(DropContainer.GRID, result.container)
    assertEquals(0, result.index)
  }

  // ─────────────────────────────────────────────────────────────
  // NONE REGION TESTS
  // ─────────────────────────────────────────────────────────────

  @Test
  fun hitTest_pointOutsideBothRegions_returnsNone() {
    // Point in gap between grid and dock (y=420)
    val point = Offset(300f, 420f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.NONE, result.container)
    assertEquals(-1, result.index)
  }

  @Test
  fun hitTest_pointFarLeft_returnsNone() {
    // Point outside both regions on the left
    val point = Offset(-50f, 200f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.NONE, result.container)
    assertEquals(-1, result.index)
  }

  @Test
  fun hitTest_pointFarRight_returnsNone() {
    // Point outside both regions on the right
    val point = Offset(700f, 200f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.NONE, result.container)
    assertEquals(-1, result.index)
  }

  @Test
  fun hitTest_pointAboveGrid_returnsNone() {
    // Point above grid region
    val point = Offset(300f, -50f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.NONE, result.container)
    assertEquals(-1, result.index)
  }

  @Test
  fun hitTest_pointBelowDock_returnsNone() {
    // Point below dock region
    val point = Offset(300f, 600f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.NONE, result.container)
    assertEquals(-1, result.index)
  }

  // ─────────────────────────────────────────────────────────────
  // BOUNDARY TESTS
  // ─────────────────────────────────────────────────────────────

  @Test
  fun hitTest_pointExactlyOnGridBoundaryTop_returnsGrid() {
    // Point exactly on top edge of grid bounds (y=0)
    val point = Offset(100f, 0f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.GRID, result.container)
  }

  @Test
  fun hitTest_pointExactlyOnGridBoundaryBottom_returnsNone() {
    // Point exactly on bottom edge of grid bounds (y=400) — exclusive
    val point = Offset(100f, 400f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    // Note: Rect.contains excludes bottom/right edge
    assertEquals(DropContainer.NONE, result.container)
  }

  @Test
  fun hitTest_pointExactlyOnDockBoundaryTop_returnsDock() {
    // Point exactly on top edge of dock bounds (y=450)
    val point = Offset(100f, 450f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    assertEquals(DropContainer.DOCK, result.container)
  }

  @Test
  fun hitTest_pointExactlyOnDockBoundaryBottom_returnsNone() {
    // Point exactly on bottom edge of dock bounds (y=550) — exclusive
    val point = Offset(100f, 550f)
    val result = hitTest(point, gridBounds, dockBounds, gridSlots, dockSlots)
    // Note: Rect.contains excludes bottom/right edge
    assertEquals(DropContainer.NONE, result.container)
  }

  // ─────────────────────────────────────────────────────────────
  // EDGE CASE: OVERLAPPING BOUNDS (dock priority)
  // ─────────────────────────────────────────────────────────────

  @Test
  fun hitTest_pointInOverlappingRegion_dockTakesPriority() {
    // Create overlapping bounds where dock overlaps grid
    val overlappingDock = Rect(left = 0f, top = 350f, right = 600f, bottom = 550f)
    // Point at y=370 is in both grid (0-400) and dock (350-550)
    val point = Offset(100f, 370f)
    val result = hitTest(point, gridBounds, overlappingDock, gridSlots, dockSlots)
    // Dock is checked first, so it should return DOCK
    assertEquals(DropContainer.DOCK, result.container)
  }
}
