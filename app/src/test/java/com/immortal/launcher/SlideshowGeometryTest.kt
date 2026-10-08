/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.SlideshowGeometry.Region
import com.immortal.launcher.SlideshowGeometry.Transform
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SlideshowGeometryTest {
  @Test fun missingRegionsPreserveTheWholePortraitEvenWhenFillAndMotionAreRequested() {
    val plan = SlideshowGeometry.plan(1000, 2000, 1500, 1000, emptyList(), true, true)
    assertTrue(plan.whole)
    assertEquals(Transform(.5f, 500f, 0f), plan.start)
    assertEquals(plan.start, plan.end)
  }

  @Test fun wholePhotoSelectionWinsOverCredibleRegions() {
    val plan = SlideshowGeometry.plan(2000, 1000, 1000, 1000,
        listOf(Region(.4f, .4f, .6f, .6f)), false, true)
    assertTrue(plan.whole)
    assertEquals(Transform(.5f, 0f, 250f), plan.start)
    assertEquals(plan.start, plan.end)
  }

  @Test fun anyInvalidRegionFallsBackInsteadOfDiscardingAPossiblePerson() {
    val bad = listOf(
        Region(Float.NaN, .1f, .2f, .3f), Region(0f, 0f, Float.POSITIVE_INFINITY, 1f),
        Region(-.1f, .1f, .3f, .4f), Region(.1f, .1f, 1.1f, .4f),
        Region(.1f, .1f, .1f, .4f), Region(.3f, .4f, .1f, .2f))
    bad.forEach { region ->
      val plan = SlideshowGeometry.plan(2000, 1000, 1000, 1000,
          listOf(Region(.4f, .4f, .6f, .6f), region), true, true)
      assertTrue("$region", plan.whole)
      assertEquals(Transform(.5f, 0f, 250f), plan.start)
    }
  }

  @Test fun aWideGroupThatCannotFitAFullScreenCropIsContained() {
    val plan = SlideshowGeometry.plan(3000, 1000, 1000, 1000,
        listOf(Region(.02f, .2f, .12f, .4f), Region(.88f, .2f, .98f, .4f)), true, true)
    assertTrue(plan.whole)
    assertEquals(plan.start, plan.end)
    assertWholeVisible(plan.start, 3000, 1000, 1000, 1000)
  }

  @Test fun paddingCanMakeAnOtherwisePossibleCropUnsafe() {
    // Raw width .48 fits the .5 visible fraction; 20% padding on each side does not.
    assertTrue(SlideshowGeometry.plan(2000, 1000, 1000, 1000,
        listOf(Region(.26f, .3f, .74f, .5f)), true, true).whole)
  }

  @Test fun credibleCentralFaceAllowsFillWithAtMostThreePercentExtraZoom() {
    val plan = SlideshowGeometry.plan(2000, 1000, 1000, 1000,
        listOf(Region(.4f, .4f, .6f, .6f)), true, true)
    assertFalse(plan.whole)
    assertEquals(1f, plan.start.scale, .00001f)
    assertTrue(plan.end.scale > plan.start.scale)
    assertTrue(plan.end.scale <= plan.start.scale * 1.03f + .00001f)
    for (step in 0..1000) {
      val transform = plan.at(step / 1000f)
      assertCover(transform, 2000, 1000, 1000, 1000)
      // Hand-derived padding: .04 on sides/bottom and .06 above the box.
      assertRegionVisible(transform, Region(.36f, .34f, .64f, .64f), 2000, 1000, 1000, 1000)
    }
  }

  @Test fun edgeFaceConstrainsTranslationThroughoutThePath() {
    val plan = SlideshowGeometry.plan(1000, 2000, 1500, 1000,
        listOf(Region(.3f, 0f, .7f, .16f)), true, true)
    assertFalse(plan.whole)
    for (step in 0..1000) {
      val transform = plan.at(step / 1000f)
      assertCover(transform, 1000, 2000, 1500, 1000)
      assertRegionVisible(transform, Region(.22f, 0f, .78f, .192f), 1000, 2000, 1500, 1000)
    }
  }

  @Test fun allPeopleInAGroupRemainProtectedIncludingASmallDistantFace() {
    val regions = listOf(Region(.35f, .25f, .5f, .45f), Region(.57f, .3f, .58f, .32f))
    val plan = SlideshowGeometry.plan(2000, 1000, 1000, 1000, regions, true, true)
    assertFalse(plan.whole)
    for (step in 0..1000) {
      val transform = plan.at(step / 1000f)
      assertRegionVisible(transform, Region(.32f, .19f, .53f, .49f), 2000, 1000, 1000, 1000)
      assertRegionVisible(transform, Region(.568f, .294f, .582f, .324f), 2000, 1000, 1000, 1000)
    }
  }

  @Test fun disablingMotionKeepsTheSafeFillCompositionStatic() {
    val plan = SlideshowGeometry.plan(2000, 1000, 1000, 1000,
        listOf(Region(.4f, .4f, .6f, .6f)), true, false)
    assertFalse(plan.whole)
    assertEquals(plan.start, plan.end)
  }

  @Test fun tightProtectedBoundsReduceZoomInsteadOfClippingOrRejectingSafeFill() {
    val plan = SlideshowGeometry.plan(2000, 1000, 1000, 1000,
        listOf(Region(.25f, .3f, .6f, .5f)), true, true)
    assertFalse(plan.whole)
    assertTrue(plan.end.scale > 1f)
    assertTrue(plan.end.scale < 1.021f)
    for (step in 0..1000) {
      assertRegionVisible(plan.at(step / 1000f), Region(.18f, .24f, .67f, .54f),
          2000, 1000, 1000, 1000)
    }
  }

  @Test fun excessiveMetadataUsesTheConservativeWholeImageFallback() {
    val plan = SlideshowGeometry.plan(2000, 1000, 1000, 1000,
        List(257) { Region(.4f, .4f, .6f, .6f) }, true, true)
    assertTrue(plan.whole)
    assertWholeVisible(plan.start, 2000, 1000, 1000, 1000)
  }

  @Test fun reverseSwapsEndpointsAndPreservesTheSameSafePath() {
    val regions = listOf(Region(.4f, .4f, .6f, .6f))
    val forward = SlideshowGeometry.plan(2000, 1000, 1000, 1000, regions, true, true)
    val reverse = SlideshowGeometry.plan(2000, 1000, 1000, 1000, regions, true, true, true)
    assertEquals(forward.start, reverse.end)
    assertEquals(forward.end, reverse.start)
    for (step in 0..100) {
      val a = forward.at(step / 100f)
      val b = reverse.at(1f - step / 100f)
      assertEquals(a.scale, b.scale, .00001f)
      assertEquals(a.x, b.x, .001f)
      assertEquals(a.y, b.y, .001f)
    }
  }

  @Test fun interpolationSharesOneClampedProgressForScaleAndTranslation() {
    val motion = SlideshowGeometry.Motion(Transform(1f, 10f, 20f), Transform(2f, 30f, 60f), false)
    assertEquals(Transform(1.25f, 15f, 30f), motion.at(.25f))
    assertEquals(motion.start, motion.at(-1f))
    assertEquals(motion.end, motion.at(2f))
    assertEquals(motion.start, motion.at(Float.NaN))
    assertEquals(motion.start, motion.at(Float.NEGATIVE_INFINITY))
    assertEquals(motion.end, motion.at(Float.POSITIVE_INFINITY))
  }

  @Test fun invalidDimensionsReturnAFiniteEmptyWholeImagePlan() {
    listOf(
        intArrayOf(0, 100, 100, 100), intArrayOf(100, -1, 100, 100),
        intArrayOf(100, 100, 0, 100), intArrayOf(100, 100, 100, Int.MIN_VALUE)
    ).forEach { dims ->
      val plan = SlideshowGeometry.plan(dims[0], dims[1], dims[2], dims[3], emptyList(), true, true)
      assertTrue(plan.whole)
      assertEquals(Transform(0f, 0f, 0f), plan.start)
      assertEquals(plan.start, plan.end)
    }
  }

  @Test fun hugeDimensionsAndExtremeAspectRatiosCannotOverflowContain() {
    listOf(
        intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, 2160, 1440),
        intArrayOf(Int.MAX_VALUE, 1, 1, Int.MAX_VALUE),
        intArrayOf(1, Int.MAX_VALUE, Int.MAX_VALUE, 1)
    ).forEach { dims ->
      val plan = SlideshowGeometry.plan(dims[0], dims[1], dims[2], dims[3], emptyList(), true, true)
      assertWholeVisible(plan.start, dims[0], dims[1], dims[2], dims[3])
      assertEquals(plan.start, plan.end)
    }
  }

  @Test fun deterministicAspectAndRegionSweepPreservesTheEntirePath() {
    val random = Random(741)
    repeat(200) {
      val iw = random.nextInt(20, 10000)
      val ih = random.nextInt(20, 10000)
      val vw = random.nextInt(20, 4000)
      val vh = random.nextInt(20, 4000)
      val left = random.nextFloat() * .8f
      val top = random.nextFloat() * .8f
      val region = Region(left, top, left + .15f, top + .15f)
      val plan = SlideshowGeometry.plan(iw, ih, vw, vh, listOf(region), true, true)
      for (step in 0..100) {
        val transform = plan.at(step / 100f)
        if (plan.whole) assertWholeVisible(transform, iw, ih, vw, vh) else {
          assertCover(transform, iw, ih, vw, vh)
          assertRegionVisible(transform, region, iw, ih, vw, vh)
        }
      }
    }
  }

  private fun assertWholeVisible(t: Transform, iw: Int, ih: Int, vw: Int, vh: Int) {
    assertTrue(t.scale.isFinite() && t.x.isFinite() && t.y.isFinite())
    assertTrue(t.scale > 0f)
    assertTrue(t.x >= -.02 && t.y >= -.02)
    assertTrue(t.x.toDouble() + iw.toDouble() * t.scale <= vw.toDouble() + maxOf(.02, vw * 1e-6))
    assertTrue(t.y.toDouble() + ih.toDouble() * t.scale <= vh.toDouble() + maxOf(.02, vh * 1e-6))
  }

  private fun assertCover(t: Transform, iw: Int, ih: Int, vw: Int, vh: Int) {
    assertTrue(t.x <= .02 && t.y <= .02)
    assertTrue(t.x.toDouble() + iw * t.scale.toDouble() >= vw - .02)
    assertTrue(t.y.toDouble() + ih * t.scale.toDouble() >= vh - .02)
  }

  private fun assertRegionVisible(t: Transform, box: Region, iw: Int, ih: Int, vw: Int, vh: Int) {
    assertTrue("left $box at $t", t.x.toDouble() + box.left * iw.toDouble() * t.scale >= -.02)
    assertTrue("top $box at $t", t.y.toDouble() + box.top * ih.toDouble() * t.scale >= -.02)
    assertTrue("right $box at $t", t.x.toDouble() + box.right * iw.toDouble() * t.scale <= vw + .02)
    assertTrue("bottom $box at $t", t.y.toDouble() + box.bottom * ih.toDouble() * t.scale <= vh + .02)
  }
}
