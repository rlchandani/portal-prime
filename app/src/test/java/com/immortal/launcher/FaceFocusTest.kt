/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure focal-point math behind face-aware screensaver framing (no Context, no ML Kit). */
class FaceFocusTest {

  private fun box(l: Int, t: Int, r: Int, b: Int) = FaceFocus.Box(l, t, r, b)

  @Test
  fun `no faces yields null focus`() {
    assertNull(FaceFocus.focalPoint(emptyList(), 1000, 1000))
  }

  @Test
  fun `single face centers on that face`() {
    val f = FaceFocus.focalPoint(listOf(box(100, 200, 300, 400)), 1000, 1000)!!
    assertEquals(0.2f, f.fx, 1e-4f) // face center x = 200/1000
    assertEquals(0.3f, f.fy, 1e-4f) // face center y = 300/1000
  }

  @Test
  fun `two nearby faces center on the union`() {
    val f = FaceFocus.focalPoint(listOf(box(100, 100, 300, 300), box(300, 100, 500, 300)), 1000, 1000)!!
    assertEquals(0.3f, f.fx, 1e-4f) // union spans 100..500
    assertEquals(0.2f, f.fy, 1e-4f)
  }

  @Test
  fun `far-apart faces pull toward the largest`() {
    // Union of a big face top-left and a small face bottom-right would center on empty
    // middle ground; the blend must land closer to the big face than the union center.
    val big = box(0, 0, 400, 400)       // center (0.2, 0.2)
    val small = box(900, 900, 1000, 1000) // center (0.95, 0.95); union center (0.5, 0.5)
    val f = FaceFocus.focalPoint(listOf(big, small), 1000, 1000)!!
    assert(f.fx < 0.5f) { "fx ${f.fx} should lean toward the dominant face" }
    assert(f.fy < 0.5f) { "fy ${f.fy} should lean toward the dominant face" }
  }

  @Test
  fun `focus is clamped into the unit square`() {
    // A detector box can poke past the bitmap edge; the focal point must not.
    val f = FaceFocus.focalPoint(listOf(box(-200, -100, 100, 50)), 1000, 1000)!!
    assert(f.fx in 0f..1f && f.fy in 0f..1f)
  }

  @Test
  fun `tiny faces are ignored as noise`() {
    // A 1%-of-width "face" is almost always a false positive in a wall-display photo.
    assertNull(FaceFocus.focalPoint(listOf(box(500, 500, 508, 508)), 1000, 1000))
  }

  @Test
  fun `degenerate image dims yield null`() {
    assertNull(FaceFocus.focalPoint(listOf(box(0, 0, 100, 100)), 0, 0))
  }
}
