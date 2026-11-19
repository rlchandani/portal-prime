/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.SlideshowGeometry.Region
import com.immortal.launcher.SlideshowGeometry.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumFramingTest {
  private val plus = 2160 to 1440
  private val miniPortrait = 800 to 1220
  private val miniLandscape = 1280 to 740

  private fun plan(
      iw: Int, ih: Int, viewport: Pair<Int, Int>, regions: List<Region>? = null,
      keepPeople: Boolean = true, fill: Boolean = true, motion: Boolean = true,
      focus: PremiumFraming.Focus? = null, reverse: Boolean = false,
      target: PremiumFraming.Target = PremiumFraming.Target.DEFAULT,
  ) = PremiumFraming.plan(iw, ih, viewport.first, viewport.second, regions, keepPeople, fill, motion,
      focus, target, reverse)

  private fun covers(t: Transform, iw: Int, ih: Int, vw: Int, vh: Int) {
    assertTrue("left edge shows: $t", t.x <= 1e-3f)
    assertTrue("top edge shows: $t", t.y <= 1e-3f)
    assertTrue("right edge shows: $t", t.x + iw * t.scale >= vw - 1e-2f)
    assertTrue("bottom edge shows: $t", t.y + ih * t.scale >= vh - 1e-2f)
  }

  private fun keeps(t: Transform, region: Region, iw: Int, ih: Int, vw: Int, vh: Int) {
    val w = region.right - region.left
    val h = region.bottom - region.top
    val left = t.x + maxOf(0f, region.left - w * .2f) * iw * t.scale
    val top = t.y + maxOf(0f, region.top - h * .3f) * ih * t.scale
    val right = t.x + minOf(1f, region.right + w * .2f) * iw * t.scale
    val bottom = t.y + minOf(1f, region.bottom + h * .2f) * ih * t.scale
    assertTrue("face clipped: $t", left >= -1e-2f && top >= -1e-2f && right <= vw + 1e-2f && bottom <= vh + 1e-2f)
  }

  @Test fun mismatchIsSymmetricAndUnboundedForInvalidSizes() {
    assertEquals(1.0, PremiumFraming.mismatch(3000, 2000, 2160, 1440), 1e-9)
    assertEquals(PremiumFraming.mismatch(2000, 3000, 2160, 1440), PremiumFraming.mismatch(3000, 2000, 1440, 2160), 1e-9)
    assertEquals(Double.MAX_VALUE, PremiumFraming.mismatch(0, 10, 10, 10), 0.0)
  }

  @Test fun closeAspectLandscapesCoverThePortalPlusEvenWithoutFaceAnalysis() {
    for ((iw, ih) in listOf(3000 to 2000, 2400 to 1800, 2400 to 1350)) {
      val f = plan(iw, ih, plus)
      assertFalse("$iw x $ih", f.contained)
      covers(f.motion.start, iw, ih, plus.first, plus.second)
      covers(f.motion.end, iw, ih, plus.first, plus.second)
    }
  }

  @Test fun portraitsOnThePortalPlusAreShownWholeAtTheInsetOverTheirBackdrop() {
    val f = plan(1600, 2400, plus)
    assertTrue(f.contained)
    val contain = minOf(2160f / 1600, 1440f / 2400)
    assertEquals(contain * PremiumFraming.INSET, f.motion.start.scale, 1e-4f)
    assertEquals(contain * PremiumFraming.INSET_ZOOM_TO, f.motion.end.scale, 1e-4f)
    // Centred at both ends of the path.
    for (t in listOf(f.motion.start, f.motion.end)) {
      assertEquals(2160f, 2 * t.x + 1600 * t.scale, .05f)
      assertEquals(1440f, 2 * t.y + 2400 * t.scale, .05f)
    }
  }

  @Test fun miniPortraitFillsWithPortraitsAndContainsLandscapes() {
    assertFalse(plan(1500, 2000, miniPortrait).contained)
    assertFalse(plan(1600, 2400, miniPortrait).contained)
    assertTrue(plan(2400, 1600, miniPortrait).contained)
    assertTrue(plan(2400, 1350, miniPortrait).contained)
  }

  @Test fun miniLandscapeBehavesLikeTheWideRule() {
    assertFalse(plan(2400, 1350, miniLandscape).contained)
    assertTrue(plan(1600, 2400, miniLandscape).contained)
  }

  @Test fun fitSettingAlwaysShowsTheWholePhoto() {
    assertTrue(plan(3000, 2000, plus, fill = false).contained)
  }

  @Test fun knownFacesStayInFrameAcrossTheWholeSettlePath() {
    val face = Region(.05f, .3f, .2f, .55f)
    val f = plan(2400, 1350, plus, regions = listOf(face))
    assertFalse(f.contained)
    for (step in 0..20) {
      val t = f.motion.at(step / 20f)
      covers(t, 2400, 1350, plus.first, plus.second)
      keeps(t, face, 2400, 1350, plus.first, plus.second)
    }
  }

  @Test fun facesThatCannotFitPreferTheWholePhotoOverCroppingAPerson() {
    // A near-3:2 photo (no mismatch) with faces at both edges: no cover window holds both.
    assertTrue(PremiumFraming.mismatch(2400, 1500, plus.first, plus.second) < PremiumFraming.MISMATCH)
    val f = plan(2400, 1500, plus, regions = listOf(Region(0f, .3f, .1f, .6f), Region(.9f, .3f, 1f, .6f)))
    assertTrue(f.contained)
  }

  @Test fun facesAreIgnoredWhenKeepPeopleIsOff() {
    val f = plan(2400, 1500, plus, regions = listOf(Region(0f, .3f, .1f, .6f), Region(.9f, .3f, 1f, .6f)),
        keepPeople = false)
    assertFalse(f.contained)
  }

  @Test fun corruptRegionsFallBackToTheWholePhoto() {
    assertTrue(plan(3000, 2000, plus, regions = listOf(Region(Float.NaN, 0f, .2f, .2f))).contained)
    assertTrue(plan(3000, 2000, plus, regions = List(300) { Region(.1f, .1f, .2f, .2f) }).contained)
  }

  @Test fun subjectLeansTowardTheTargetWithoutRevealingAnEdge() {
    val f = plan(2400, 1350, plus, focus = PremiumFraming.Focus(.8f, .5f), motion = false)
    val t = f.motion.start
    covers(t, 2400, 1350, plus.first, plus.second)
    val subjectX = t.x + .8f * 2400 * t.scale
    // Pulled toward 58% of the width as far as coverage allows (a centred crop puts it at ~75%).
    val centred = (plus.first - 2400 * t.scale) / 2 + .8f * 2400 * t.scale
    assertTrue("subject $subjectX vs centred $centred", subjectX <= centred + .5f)
  }

  @Test fun facesOutrankSaliency() {
    val face = Region(.1f, .3f, .2f, .5f)
    val withFace = plan(2400, 1350, plus, regions = listOf(face), focus = PremiumFraming.Focus(.9f, .5f), motion = false)
    val faceOnly = plan(2400, 1350, plus, regions = listOf(face), focus = null, motion = false)
    assertEquals(faceOnly.motion.start, withFace.motion.start)
  }

  @Test fun settleIsAGentleZoomInWithAlternatingGlide() {
    val a = plan(3000, 2000, plus)
    val b = plan(3000, 2000, plus, reverse = true)
    val cover = maxOf(2160f / 3000, 1440f / 2000)
    assertEquals(cover * PremiumFraming.ZOOM_FROM, a.motion.start.scale, 1e-4f)
    assertEquals(cover * PremiumFraming.ZOOM_TO, a.motion.end.scale, 1e-4f)
    assertNotEquals(a.motion.end.x, b.motion.end.x)
  }

  @Test fun motionOffIsStill() {
    val f = plan(3000, 2000, plus, motion = false)
    assertEquals(f.motion.start, f.motion.end)
    assertEquals(maxOf(2160f / 3000, 1440f / 2000), f.motion.start.scale, 1e-4f)
  }

  @Test fun innerRectIsTheSmallerEndOfAContainedPath() {
    val f = plan(1600, 2400, plus)
    val r = f.innerRect(1600, 2400)
    val s = f.motion.start.scale
    assertEquals(f.motion.start.x, r[0], 1e-3f)
    assertEquals(1600 * s, r[2] - r[0], 1e-2f)
    val reversed = plan(1600, 2400, plus, reverse = true).innerRect(1600, 2400)
    assertEquals(r[2] - r[0], reversed[2] - reversed[0], 1e-2f)
  }

  @Test fun degenerateSizesProduceAnEmptyContainedFrame() {
    val f = plan(0, 100, plus)
    assertTrue(f.contained)
    assertEquals(0f, f.motion.start.scale)
  }

  @Test fun regionFocusUsesTheFaceCentroid() {
    val focus = PremiumFraming.regionFocus(listOf(Region(.4f, .4f, .6f, .6f)))!!
    assertEquals(.5f, focus.x, 1e-3f)
    assertEquals(.5f, focus.y, 1e-3f)
  }
}
