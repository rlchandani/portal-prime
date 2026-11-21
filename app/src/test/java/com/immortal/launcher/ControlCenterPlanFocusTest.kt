/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The room highlight drawn apart from the plan's passes: what it dims, where its edge sits, how it breathes. */
class ControlCenterPlanFocusTest {
  private val rects = mapOf("office" to Rect(0f, 0f, 100f, 80f), "kitchen" to Rect(100f, 0f, 220f, 80f))
  private fun focus(id: String?, pulse: Boolean = false) = CcPlanFocus(rects, 10f, CcDark, id, pulse, 2f)

  @Test fun noHighlightDimsNothingAndDoesNotAnimate() {
    val f = focus(null)
    assertFalse(f.shows)
    assertFalse(f.veiled("office"))
    assertFalse(f.veiled("kitchen"))
  }

  @Test fun aHighlightedRoomDimsEveryOtherRoom() {
    val f = focus("office")
    assertTrue(f.shows)
    assertFalse("the open room stays bright", f.veiled("office"))
    assertTrue(f.veiled("kitchen"))
    assertTrue("a room of another floor is dimmed like any other", f.veiled("garage"))
  }

  @Test fun aRoomOnAnotherFloorHasNoEdgeHere() {
    val f = focus("garage")
    assertFalse("no edge animates on this floor", f.shows)
    assertTrue(f.veiled("office"))
  }

  @Test fun theEdgeIsInsetByAFifthOfACell() {
    assertEquals(Rect(2f, 2f, 98f, 78f), focus("office").inner(rects.getValue("office")))
  }

  @Test fun theEdgeBreathesBetweenItsMinimumAndFullAlpha() {
    val f = focus("office")
    // Four breaths per clock period: t = 0 is the low point, 1/8 the peak.
    assertEquals(CcEdgeLook(0.55f, 1f), f.edgeAt(0f))
    assertEquals(1f, f.edgeAt(0.125f).alpha, 1e-5f)
    assertEquals(f.edgeAt(0.1f), f.edgeAt(0.35f))
    assertEquals("width never changes while breathing", 1f, f.edgeAt(0.2f).widthScale)
  }

  @Test fun jarvisPointingSwellsTheEdgeInsteadOfBreathing() {
    val f = focus("office", pulse = true)
    val widest = f.edgeAt(0f)
    assertEquals(2f, widest.widthScale, 1e-5f)
    assertEquals("the mock's hlp: opacity .6 at the widest", 0.6f, widest.alpha, 1e-5f)
    val settled = f.edgeAt(0.125f)
    assertEquals(1f, settled.widthScale, 1e-5f)
    assertEquals(1f, settled.alpha, 1e-5f)
  }
}
