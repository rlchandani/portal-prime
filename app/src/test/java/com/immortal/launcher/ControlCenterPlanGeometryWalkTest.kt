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

class ControlCenterPlanGeometryWalkTest {
  private val a = CcMapPt(0f, 0f)
  private val b = CcMapPt(10f, 0f)
  private val c = CcMapPt(10f, 10f)

  @Test fun walkIndexSplitsProgressEvenly() {
    assertEquals(-1, CcPlanGeo.walkIndex(0, 0.5f))
    assertEquals(0, CcPlanGeo.walkIndex(3, 0f))
    assertEquals(0, CcPlanGeo.walkIndex(3, 0.32f))
    assertEquals(1, CcPlanGeo.walkIndex(3, 0.34f))
    assertEquals(2, CcPlanGeo.walkIndex(3, 0.99f))
    assertEquals(2, CcPlanGeo.walkIndex(3, 1f))
    assertEquals(2, CcPlanGeo.walkIndex(3, 7f))
    assertEquals(0, CcPlanGeo.walkIndex(3, -1f))
  }

  @Test fun walkLightRestsThenGlides() {
    val centres = listOf(a, b, c)
    assertEquals(a, CcPlanGeo.walkPoint(centres, 0.1f))
    // Halfway through the glide from a to b (slot 0 spans 0..1/3; glide starts at 0.7 of it).
    val mid = CcPlanGeo.walkPoint(centres, (0.7f + 0.15f) / 3f)!!
    assertEquals(5f, mid.x, 0.01f)
    assertEquals(0f, mid.y, 0.01f)
    assertEquals(c, CcPlanGeo.walkPoint(centres, 1f))
  }

  @Test fun walkLightHidesOnAnotherFloor() {
    assertNull(CcPlanGeo.walkPoint(listOf(a, null, c), 0.5f))
    assertNull(CcPlanGeo.walkPoint(emptyList(), 0.5f))
    // The next room being on another floor: the light stays put.
    assertEquals(a, CcPlanGeo.walkPoint(listOf(a, null), 0.45f))
  }

  @Test fun visitedRoomsAccumulate() {
    val rooms = listOf("hall", "kitchen", "living")
    assertTrue(CcPlanGeo.walkVisited(rooms, 0.1f).isEmpty())
    assertEquals(setOf("hall"), CcPlanGeo.walkVisited(rooms, 0.4f))
    assertEquals(setOf("hall", "kitchen"), CcPlanGeo.walkVisited(rooms, 0.9f))
    assertEquals(rooms.toSet(), CcPlanGeo.walkVisited(rooms, 1f))
    assertTrue(CcPlanGeo.walkVisited(emptyList(), 1f).isEmpty())
  }
}
