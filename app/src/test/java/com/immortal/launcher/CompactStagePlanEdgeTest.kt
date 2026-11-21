/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.CompactStagePlan.Ground
import com.immortal.launcher.CompactStagePlan.Style
import com.immortal.launcher.ScreensaverLayout.Box
import com.immortal.launcher.SlideshowGeometry.Region
import com.immortal.launcher.SlideshowGeometry.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge branches of [CompactStagePlan] beyond the phase-2 rule tests: invalid input, face-aware
 * fills, panorama sweeps both ways and still, overlay clearing, and the cover-clamped drift.
 */
class CompactStagePlanEdgeTest {
  private val cp = LayoutClass.COMPACT_PORTRAIT
  private val cl = LayoutClass.COMPACT_LANDSCAPE
  private val landscape = 1472 to 981 // 3:2
  private val portrait = 981 to 1472 // 2:3
  private val square = 1200 to 1200
  private val panorama = 1472 to 490 // 3:1

  private fun size(cls: LayoutClass) = if (cls == cp) 800 to 1280 else 1280 to 800

  private fun plan(
      style: Style?, cls: LayoutClass, vararg photos: Pair<Int, Int>, motion: Boolean = true,
      parity: Boolean = false, regions: List<Region> = emptyList(), occupied: List<Box> = emptyList(),
      fill: Boolean = true, width: Int = size(cls).first, height: Int = size(cls).second, density: Float = 1f,
  ) = CompactStagePlan.plan(style, cls, photos.toList(), width, height, density, motion, parity, backdrop = true,
      regions = regions, occupied = occupied, fill = fill)

  private fun still(m: SlideshowGeometry.Motion) = m.start == m.end

  // ---- Modes and pairing ---------------------------------------------------------------------

  @Test fun everyNativeModeMapsToItsStyleAndOthersKeepTheShippedPresentation() {
    assertEquals(Style.SMART, CompactStagePlan.style(ScreensaverConfig.MODE_SMART))
    assertEquals(Style.PAIR, CompactStagePlan.style(ScreensaverConfig.MODE_PORTRAITS))
    assertEquals(Style.WHOLE, CompactStagePlan.style(ScreensaverConfig.MODE_WHOLE))
    assertEquals(Style.PRINT, CompactStagePlan.style(ScreensaverConfig.MODE_GALLERY_PRINT))
    assertEquals(Style.SPREAD, CompactStagePlan.style(ScreensaverConfig.MODE_GALLERY_SPREAD))
    assertNull(CompactStagePlan.style(ScreensaverConfig.MODE_CLASSIC))
    assertNull(CompactStagePlan.style(""))
  }

  @Test fun aPortraitNeedsPositiveSidesAndToBeTaller() {
    assertTrue(CompactStagePlan.portrait(2, 3))
    assertFalse(CompactStagePlan.portrait(3, 3))
    assertFalse(CompactStagePlan.portrait(0, 3))
    assertFalse(CompactStagePlan.portrait(3, 2))
  }

  @Test fun onlyTheStylesThatPairAcceptAPartnerOnEachClass() {
    // No style (a shipped mode) never refuses a pair: the shipped presentation decides.
    assertTrue(CompactStagePlan.pairs(null, cp, square, square))
    assertFalse("single-photo styles never pair upright", CompactStagePlan.pairs(Style.WHOLE, cp, landscape, landscape))
    assertFalse(CompactStagePlan.pairs(Style.PRINT, cp, landscape, landscape))
    assertFalse("turned, Smart frame never pairs", CompactStagePlan.pairs(Style.SMART, cl, portrait, portrait))
    assertFalse("turned pairs need two portraits", CompactStagePlan.pairs(Style.PAIR, cl, portrait, landscape))
    assertTrue(CompactStagePlan.pairs(Style.SPREAD, cl, portrait, portrait))
    assertFalse("upright stacks need two landscapes", CompactStagePlan.pairs(Style.SMART, cp, landscape, panorama))
  }

  // ---- Guards ------------------------------------------------------------------------------

  @Test fun invalidInputKeepsTheShippedPresentation() {
    assertNull(plan(null, cp, landscape))
    assertNull(plan(Style.SMART, cp))
    assertNull(plan(Style.SMART, cp, landscape, width = 0))
    assertNull(plan(Style.SMART, cp, landscape, height = -1))
    assertNull(plan(Style.SMART, cp, landscape, density = 0f))
    assertNull("an undecoded photo has no size to plan", plan(Style.SMART, cp, landscape, 0 to 981))
    assertNull(plan(Style.SMART, cp, 1472 to 0))
    assertNull("turned galleries ship unchanged", plan(Style.PRINT, cl, landscape))
    assertNull(plan(Style.SPREAD, cl, portrait, portrait))
  }

  // ---- Smart frame single rule -------------------------------------------------------------

  @Test fun aFaceTheCropWouldCutKeepsThePhotoWhole() {
    // Turned, a 3:2 photo fills by losing a sliver of height; a face spanning it can't survive that.
    val tall = listOf(Region(.4f, .02f, .6f, .98f))
    val p = plan(Style.SMART, cl, landscape, regions = tall)!!
    assertEquals(Ground.BACKDROP, p.ground)
    assertEquals(6f, p.tiles.single().cornerDp)
    val plain = plan(Style.SMART, cl, landscape)!!
    assertEquals("without faces the same photo fills", Ground.BLACK, plain.ground)
  }

  @Test fun aSafeFaceFillsAroundItsOwnFraming() {
    val face = listOf(Region(.05f, .05f, .2f, .2f))
    val faced = plan(Style.SMART, cl, landscape, regions = face, motion = false)!!
    val centred = plan(Style.SMART, cl, landscape, motion = false)!!
    assertEquals(Ground.BLACK, faced.ground)
    val (w, h) = size(cl)
    assertEquals(Box(0, 0, w, h), faced.tiles.single().box)
    assertTrue("still", still(faced.tiles.single().motion))
    assertTrue("the framing moves toward the face (up)",
        faced.tiles.single().motion.start.y > centred.tiles.single().motion.start.y)
  }

  @Test fun fillDriftsTheOtherWayOnTheOtherParity() {
    val a = plan(Style.SMART, cl, landscape, parity = false)!!.tiles.single().motion
    val b = plan(Style.SMART, cl, landscape, parity = true)!!.tiles.single().motion
    assertEquals(a.start, b.start)
    assertNotEquals(a.end, b.end)
  }

  @Test fun aPanoramaSweepsAtFullHeightEitherWayOrRestsCentredWhenStill() {
    val (w, h) = size(cl)
    val s = h.toFloat() / panorama.second
    val travel = w - panorama.first * s
    val forward = plan(Style.SMART, cl, panorama, parity = false)!!.tiles.single().motion
    assertEquals(Transform(s, 0f, 0f), forward.start)
    assertEquals(Transform(s, travel, 0f), forward.end)
    val back = plan(Style.SMART, cl, panorama, parity = true)!!.tiles.single().motion
    assertEquals(forward.start, back.end)
    assertEquals(forward.end, back.start)
    val rest = plan(Style.SMART, cl, panorama, motion = false)!!.tiles.single().motion
    assertEquals(Transform(s, travel / 2f, 0f), rest.start)
    assertTrue(still(rest))
  }

  @Test fun aWholeCardIsStillWithMotionOff() {
    val p = plan(Style.SMART, cp, square, motion = false)!!
    assertEquals(Ground.BACKDROP, p.ground)
    assertTrue(still(p.tiles.single().motion))
    val moving = plan(Style.SMART, cp, square)!!
    assertFalse(still(moving.tiles.single().motion))
  }

  @Test fun turnedWholePhotoMismatchSitsInTheTurnedZone() {
    val p = plan(Style.WHOLE, cl, portrait)!!
    val box = p.tiles.single().box
    val (w, h) = size(cl)
    assertEquals(Ground.BACKDROP, p.ground)
    assertTrue(box.left >= 56 && box.right <= w - 56 && box.top >= 32 && box.bottom <= h - 32)
  }

  // ---- Fit keeps stacked and paired cards still even with motion on --------------------------

  @Test fun fitCardsStayStillWithMotionOn() {
    val stack = plan(Style.SMART, cp, landscape, landscape, fill = false)!!
    assertEquals(2, stack.tiles.size)
    assertTrue(stack.tiles.all { still(it.motion) })
    val pair = plan(Style.PAIR, cl, portrait, portrait, fill = false)!!
    assertEquals(2, pair.tiles.size)
    assertTrue(pair.tiles.all { still(it.motion) })
    val moving = plan(Style.PAIR, cl, portrait, portrait)!!
    assertTrue(moving.tiles.none { still(it.motion) })
    assertEquals("the second tile drifts the other way", -moving.tiles[0].motion.start.y.compareTo(moving.tiles[0].motion.end.y),
        moving.tiles[1].motion.start.y.compareTo(moving.tiles[1].motion.end.y))
  }

  // ---- clearTop ---------------------------------------------------------------------------

  private val zone = Box(40, 48, 760, 998)

  @Test fun clearTopIgnoresOverlaysThatDontHangIntoTheZoneTop() {
    val h = 1280
    val cases = listOf(
        Box(0, 0, 800, 40), // wholly above the zone
        Box(40, 48 + zone.height / 4 + 1, 760, 600), // starts below the zone's top quarter
        Box(40, 0, 760, h / 2), // reaches the lower half (the clock band)
        Box(0, 0, 30, 200), // left of the zone
        Box(770, 0, 800, 200), // right of the zone
    )
    for (overlay in cases) assertEquals("$overlay", zone, CompactStagePlan.clearTop(zone, listOf(overlay), h, 1f))
    assertEquals(zone, CompactStagePlan.clearTop(zone, emptyList(), h, 1f))
  }

  @Test fun clearTopMovesBelowTheLowestOverlayPlusAGap() {
    val overlays = listOf(Box(40, 20, 400, 150), Box(300, 30, 760, 210))
    val cleared = CompactStagePlan.clearTop(zone, overlays, 1280, 2f)
    assertEquals(210 + 32, cleared.top)
    assertEquals(zone.copy(top = 242), cleared)
  }

  @Test fun clearTopKeepsAtLeastHalfTheZone() {
    val deep = Box(40, 40, 760, 630)
    val cleared = CompactStagePlan.clearTop(zone, listOf(deep), 1280, 1f)
    assertEquals(zone.top + zone.height / 2, cleared.top)
  }

  @Test fun clearTopLeavesAOnePixelZoneAlone() {
    val sliver = Box(40, 48, 760, 49)
    assertEquals(sliver, CompactStagePlan.clearTop(sliver, listOf(Box(40, 0, 760, 100)), 1280, 1f))
  }

  // ---- contain and drift ------------------------------------------------------------------

  @Test fun containCentresAndNeverCollapsesToZero() {
    assertEquals(Box(10, 20 + 25, 110, 20 + 75), CompactStagePlan.contain(200, 100, Box(10, 20, 110, 120)))
    val dot = CompactStagePlan.contain(10_000, 1, Box(0, 0, 10, 10))
    assertEquals(1, dot.height)
    assertEquals(10, dot.width)
  }

  @Test fun driftFollowsAFaceAwareBaseAndStillCovers() {
    val (pw, ph) = landscape
    val cover = maxOf(400f / pw, 400f / ph)
    // A base framing pushed to the left edge of the photo: the drift keeps that offset.
    val base = Transform(cover, 0f, 0f)
    val m = CompactStagePlan.drift(pw, ph, 400, 400, 1f, 1.04f, 0f, 0f, 0f, 0f, base)
    assertEquals(0f, m.start.x, 1e-3f)
    val centred = CompactStagePlan.drift(pw, ph, 400, 400, 1f, 1.04f, 0f, 0f, 0f, 0f)
    assertTrue(centred.start.x < m.start.x)
    for (t in listOf(m.start, m.end)) {
      assertTrue(t.x <= 1e-3f && t.x + pw * t.scale >= 400 - 1e-2f)
      assertTrue(t.y <= 1e-3f && t.y + ph * t.scale >= 400 - 1e-2f)
    }
  }

  @Test fun driftIgnoresABaseScaleThatWouldNotCover() {
    val (pw, ph) = landscape
    val small = Transform(.01f, 0f, 0f)
    val m = CompactStagePlan.drift(pw, ph, 400, 400, 1f, 1f, 0f, 0f, 0f, 0f, small)
    assertEquals(maxOf(400f / pw, 400f / ph), m.start.scale, 1e-6f)
  }
}
