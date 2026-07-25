/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Container regions for icon placement during drag operations.
 */
enum class DropContainer {
  GRID,
  DOCK,
  NONE
}

/**
 * Target location for a drag-and-drop operation.
 *
 * @property container The container region (GRID, DOCK, or NONE)
 * @property index Insertion index within the container (0..size); -1 for NONE
 */
data class DropTarget(
  val container: DropContainer,
  val index: Int
)

/**
 * Performs hit-testing to determine where a drag gesture should insert an icon.
 *
 * Given the finger's window position and the bounds of all icon slots plus container regions,
 * returns which container the finger is over and the insertion index for live reflow.
 *
 * **Rules:**
 * - If `point` is within `dockBounds` → container DOCK; index = nearest dock slot by horizontal
 *   center, clamped to [0..dockSlots.size]. Inserts BEFORE a slot if left of its center, AFTER
 *   if right.
 * - Else if `point` is within `gridBounds` → container GRID; index = nearest grid slot by 2D
 *   distance (Euclidean to center), clamped to [0..gridSlots.size]. The caller interprets this
 *   as insertion index (e.g., over slot N → insert at N; past the last slot → append).
 * - Otherwise → DropContainer.NONE, index -1.
 *
 * @param point The finger position in window coordinates
 * @param gridBounds The grid container region in window coordinates
 * @param dockBounds The dock container region in window coordinates
 * @param gridSlots List of each grid slot's bounds in window coordinates (row-major order)
 * @param dockSlots List of each dock slot's bounds in window coordinates (left-to-right order)
 * @return The target container and insertion index
 */
fun hitTest(
  point: Offset,
  gridBounds: Rect,
  dockBounds: Rect,
  gridSlots: List<Rect>,
  dockSlots: List<Rect>
): DropTarget {
  // Check dock first (typically overlaps grid visually, but dock takes priority)
  if (dockBounds.contains(point)) {
    if (dockSlots.isEmpty()) {
      return DropTarget(DropContainer.DOCK, 0)
    }
    val index = findNearestSlotHorizontal(point, dockSlots).coerceIn(0, dockSlots.size)
    return DropTarget(DropContainer.DOCK, index)
  }

  // Check grid
  if (gridBounds.contains(point)) {
    if (gridSlots.isEmpty()) {
      return DropTarget(DropContainer.GRID, 0)
    }
    val index = findNearestSlot2D(point, gridSlots).coerceIn(0, gridSlots.size)
    return DropTarget(DropContainer.GRID, index)
  }

  // Neither region
  return DropTarget(DropContainer.NONE, -1)
}

/**
 * Finds the nearest slot index based on horizontal (x-axis) center distance.
 * Returns the slot index if point is left of the slot center, or slot+1 if right.
 */
private fun findNearestSlotHorizontal(point: Offset, slots: List<Rect>): Int {
  var minDist = Float.MAX_VALUE
  var nearestIdx = 0

  slots.forEachIndexed { idx, rect ->
    val centerX = rect.center.x
    val dist = kotlin.math.abs(point.x - centerX)
    if (dist < minDist) {
      minDist = dist
      nearestIdx = idx
    }
  }

  // Insert before this slot if left of center, after if right
  val slotCenterX = slots[nearestIdx].center.x
  return if (point.x < slotCenterX) nearestIdx else nearestIdx + 1
}

/**
 * Finds the nearest slot index based on 2D Euclidean distance to slot centers.
 * Returns the index of the slot whose center is closest to the point.
 */
private fun findNearestSlot2D(point: Offset, slots: List<Rect>): Int {
  var minDist = Float.MAX_VALUE
  var nearestIdx = 0

  slots.forEachIndexed { idx, rect ->
    val center = rect.center
    val dx = point.x - center.x
    val dy = point.y - center.y
    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
    if (dist < minDist) {
      minDist = dist
      nearestIdx = idx
    }
  }

  return nearestIdx
}
