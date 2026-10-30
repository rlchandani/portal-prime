/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.SlideshowPresentationPolicy.Bounds
import org.junit.Assert.*
import org.junit.Test

class SlideshowPresentationPolicyTest {
  @Test fun galleryPrintInsetsAWholeStaticPhotoWithoutChangingAspect() {
    val plan = SlideshowPresentationPolicy.galleryPrint(1200, 800, 1000, 600)
    assertEquals(Bounds(24, 24, 976, 576), plan.area)
    assertEquals(.69f, plan.path.start.scale, .00001f)
    assertEquals(62f, plan.path.start.x, .001f)
    assertEquals(0f, plan.path.start.y, .001f)
    assertWhole(plan, 1200, 800)
  }

  @Test fun tinyBoundsKeepAtLeastOnePixelInsteadOfCreatingNegativeLayoutSizes() {
    listOf(1 to 1, 2 to 3, 3 to 2, 12 to 8).forEach { (w, h) ->
      val plan = SlideshowPresentationPolicy.galleryPrint(4000, 3000, w, h)
      assertTrue(plan.area.width > 0 && plan.area.height > 0)
      assertTrue(plan.area.left >= 0 && plan.area.top >= 0)
      assertTrue(plan.area.right <= w && plan.area.bottom <= h)
      assertWhole(plan, 4000, 3000)
    }
    assertEquals(Bounds(0, 0, 1, 1),
        SlideshowPresentationPolicy.galleryPrint(100, 100, 1, 1).area)
  }

  @Test fun hugeBoundsCapTheMatteAndDoNotOverflowAreaOrScale() {
    val plan = SlideshowPresentationPolicy.galleryPrint(
        Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
    assertEquals(Bounds(56, 56, Int.MAX_VALUE - 56, Int.MAX_VALUE - 56), plan.area)
    assertWhole(plan, Int.MAX_VALUE, Int.MAX_VALUE)
    assertEquals(Bounds(56, 56, 3944, 2944),
        SlideshowPresentationPolicy.galleryPrint(6000, 4000, 4000, 3000).area)
  }

  @Test fun portraitPanoramaAndExtremeAspectRatiosRemainEntirelyVisible() {
    listOf(1000 to 2000, 4000 to 500, 1 to Int.MAX_VALUE, Int.MAX_VALUE to 1)
        .forEach { (w, h) ->
          assertWhole(SlideshowPresentationPolicy.galleryPrint(w, h, 1280, 800), w, h)
        }
  }

  @Test fun invalidDimensionsReturnAnEmptyStaticPlan() {
    listOf(
        intArrayOf(0, 100, 100, 100), intArrayOf(100, -1, 100, 100),
        intArrayOf(100, 100, 0, 100), intArrayOf(100, 100, 100, Int.MIN_VALUE)
    ).forEach { d ->
      val plan = SlideshowPresentationPolicy.galleryPrint(d[0], d[1], d[2], d[3])
      assertEquals(Bounds(0, 0, 0, 0), plan.area)
      assertEquals(SlideshowGeometry.Transform(0f, 0f, 0f), plan.path.start)
      assertEquals(plan.path.start, plan.path.end)
    }
  }

  @Test fun malformedDensityCannotCreateAnUnboundedOrMissingMatte() {
    listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f).forEach { density ->
      assertEquals(Bounds(56, 56, 3944, 2944),
          SlideshowPresentationPolicy.galleryPrint(6000, 4000, 4000, 3000, density).area)
    }
    assertEquals(Bounds(224, 224, 9776, 9776),
        SlideshowPresentationPolicy.galleryPrint(10000, 10000, 10000, 10000, Float.MAX_VALUE).area)
  }

  @Test fun measuredOverlayReservesSpaceEvenWhenThePhotoHasNoFaceMetadata() {
    val plan = SlideshowPresentationPolicy.galleryPrint(1200, 800, 1000, 600,
        occupied = listOf(Bounds(0, 0, 1000, 200)))
    assertEquals(Bounds(16, 216, 984, 584), plan.area)
    assertWhole(plan, 1200, 800)
  }

  @Test fun reservationChoosesUsefulPhotoSizeRatherThanOnlyRectangleArea() {
    val overlays = listOf(Bounds(400, 0, 600, 600))
    val portrait = SlideshowPresentationPolicy.galleryPrint(1000, 2000, 1000, 800,
        occupied = overlays)
    val panorama = SlideshowPresentationPolicy.galleryPrint(4000, 500, 1000, 800,
        occupied = overlays)
    assertEquals(Bounds(16, 16, 384, 784), portrait.area)
    assertEquals(Bounds(8, 608, 992, 792), panorama.area)
    assertWhole(portrait, 1000, 2000)
    assertWhole(panorama, 4000, 500)
  }

  @Test fun savedManualZoomAndFocusWinWithoutRewritingTheAdjustment() {
    val adjustment = SlideshowAdjustment(2f, .2f, .8f)
    val plan = SlideshowPresentationPolicy.galleryPrint(800, 1200, 600, 400,
        occupied = listOf(Bounds(0, 0, 600, 300)), adjustment = adjustment)
    assertEquals(Bounds(16, 16, 584, 384), plan.area)
    assertEquals(.6133333f, plan.path.start.scale, .00001f)
    assertEquals(38.6667f, plan.path.start.x, .001f)
    assertEquals(-368f, plan.path.start.y, .001f)
    assertEquals(plan.path.start, plan.path.end)
    assertEquals(SlideshowAdjustment(2f, .2f, .8f), adjustment)
  }

  @Test fun coveredOrExcessiveOverlayMetadataFallsBackWithoutTraversingAnUnboundedList() {
    val covered = SlideshowPresentationPolicy.galleryPrint(1200, 800, 1000, 600,
        occupied = listOf(Bounds(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)))
    assertEquals(Bounds(24, 24, 976, 576), covered.area)
    assertWhole(covered, 1200, 800)
    val excessive = object : AbstractList<Bounds>() {
      override val size = Int.MAX_VALUE
      override fun get(index: Int): Bounds = error("oversized metadata must not be traversed")
    }
    val bounded = SlideshowPresentationPolicy.galleryPrint(1200, 800, 1000, 600,
        occupied = excessive)
    assertEquals(Bounds(24, 24, 976, 576), bounded.area)
    assertWhole(bounded, 1200, 800)
  }

  @Test fun overlappingAndOffscreenOverlaysLeaveAValidUnobstructedPrintWhenSpaceExists() {
    val plan = SlideshowPresentationPolicy.galleryPrint(1200, 800, 1000, 600,
        occupied = listOf(Bounds(-100, -100, 250, 600), Bounds(750, 0, 1100, 600),
            Bounds(750, 0, 1100, 600), Bounds(400, 700, 600, 800), Bounds(300, 300, 200, 200)))
    assertEquals(Bounds(270, 20, 730, 580), plan.area)
    assertWhole(plan, 1200, 800)
  }

  private fun assertWhole(plan: SlideshowPresentationPolicy.Plan, iw: Int, ih: Int) {
    val t = plan.path.start
    assertTrue(plan.path.whole)
    assertEquals(t, plan.path.end)
    assertTrue(t.scale.isFinite() && t.scale > 0f && t.x.isFinite() && t.y.isFinite())
    val tolerance = maxOf(.02, maxOf(plan.area.width, plan.area.height) * 1e-6)
    assertTrue(t.x >= -tolerance && t.y >= -tolerance)
    assertTrue(t.x.toDouble() + iw.toDouble() * t.scale <= plan.area.width + tolerance)
    assertTrue(t.y.toDouble() + ih.toDouble() * t.scale <= plan.area.height + tolerance)
  }
}
