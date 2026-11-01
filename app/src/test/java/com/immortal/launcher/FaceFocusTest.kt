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

  @Test fun `region unions real face boxes at image edges without adding padding`() {
    val faces = listOf(box(-100, 50, 200, 250), box(650, 300, 1200, 600),
        box(400, 0, 410, 10), box(400, 400, 300, 500), box(-200, -200, -100, -100))
    assertEquals(FaceFocus.Bounds(0f, .1f, 1f, 1f), FaceFocus.boundsFor(faces, 1000, 500))
  }

  @Test fun `region uses four percent of image short side and includes threshold faces`() {
    assertEquals(FaceFocus.Bounds(.07f, .08f, .08f, .12f),
        FaceFocus.boundsFor(listOf(box(70, 20, 80, 30), box(800, 100, 809, 109)), 1000, 250))
  }

  @Test fun `region ignores empty reversed and entirely off-image boxes`() {
    assertNull(FaceFocus.boundsFor(emptyList(), 1000, 1000))
    assertNull(FaceFocus.boundsFor(listOf(box(0, 0, 100, 100)), 0, 1000))
    assertNull(FaceFocus.boundsFor(
        listOf(box(100, 100, 100, 200), box(200, 100, 100, 200), box(1000, 0, 1100, 200)),
        1000, 1000))
  }

  @Test fun `focal point retains old point equality and carries no region`() {
    val point = FaceFocus.focalPoint(listOf(box(100, 200, 300, 400)), 1000, 1000)
    assertEquals(FaceFocus.Focus(.2f, .3f), point)
    assertNull(point!!.bounds)
  }

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

  // ---- people weight ----------------------------------------------------------

  @Test
  fun `no faces weighs zero`() {
    assertEquals(0f, FaceFocus.peopleWeight(emptyList(), 1000, 1000), 1e-4f)
  }

  @Test
  fun `weight grows with face coverage and count`() {
    val one = FaceFocus.peopleWeight(listOf(box(0, 0, 200, 200)), 1000, 1000)
    val big = FaceFocus.peopleWeight(listOf(box(0, 0, 500, 500)), 1000, 1000)
    val two = FaceFocus.peopleWeight(listOf(box(0, 0, 200, 200), box(600, 600, 800, 800)), 1000, 1000)
    assert(big > one) { "bigger face should weigh more ($big vs $one)" }
    assert(two > one) { "two faces should weigh more than one ($two vs $one)" }
  }

  @Test
  fun `weight is capped at one`() {
    val w = FaceFocus.peopleWeight(List(30) { box(0, 0, 900, 900) }, 1000, 1000)
    assert(w <= 1f)
  }

  @Test
  fun `noise faces do not weigh`() {
    assertEquals(0f, FaceFocus.peopleWeight(listOf(box(0, 0, 8, 8)), 1000, 1000), 1e-4f)
  }
}
