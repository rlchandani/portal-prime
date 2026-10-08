/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class SlideshowDecodeBudgetTest {
  @Test fun twelveMegapixelPhotoIsSampledBeforeAnyPixelsAreAllocated() {
    val plan = requireNotNull(SlideshowDecodeBudget.plan(4000, 3000))
    assertEquals(2, plan.sampleSize)
    assertEquals(2000, plan.width)
    assertEquals(1500, plan.height)
    assertTrue(plan.width.toLong() * plan.height * 4 <= 12L * 1024 * 1024)
  }

  @Test fun pixelCeilingAppliesEvenWhenBothEdgesAreBelowTheEdgeLimit() {
    val plan = requireNotNull(SlideshowDecodeBudget.plan(2400, 2400))
    assertEquals(2, plan.sampleSize)
    assertEquals(1200, plan.width)
    assertEquals(1200, plan.height)
  }

  @Test fun anAlreadyBoundedPortalPhotoKeepsItsResolution() {
    val plan = requireNotNull(SlideshowDecodeBudget.plan(2160, 1440))
    assertEquals(1, plan.sampleSize)
    assertEquals(2160, plan.width)
    assertEquals(1440, plan.height)
  }

  @Test fun oddDimensionsAreRoundedUpWhenAdmittingADecode() {
    val plan = requireNotNull(SlideshowDecodeBudget.plan(4801, 101))
    assertEquals(4, plan.sampleSize)
    assertEquals(1201, plan.width)
    assertEquals(26, plan.height)
  }

  @Test fun oneExtraDecodedRowCannotCrossThePixelBudgetThroughTruncation() {
    // Sample 2 would yield 2048*1537 = 3,147,776 pixels, exceeding the 3,145,728 limit.
    val plan = requireNotNull(SlideshowDecodeBudget.plan(4096, 3073))
    assertEquals(4, plan.sampleSize)
    assertEquals(1024, plan.width)
    assertEquals(769, plan.height)
  }

  @Test fun requestedSmallerDerivativeHonorsEdgeAndPixelBoundsTogether() {
    val plan = requireNotNull(SlideshowDecodeBudget.plan(4000, 3000, 640, 200000))
    assertEquals(8, plan.sampleSize)
    assertEquals(500, plan.width)
    assertEquals(375, plan.height)
  }

  @Test fun callerCannotRaiseTheHardPixelOrEdgeCeiling() {
    val plan = requireNotNull(SlideshowDecodeBudget.plan(10000, 10000, Int.MAX_VALUE, Long.MAX_VALUE))
    assertTrue(plan.width <= 2400 && plan.height <= 2400)
    assertTrue(plan.width.toLong() * plan.height <= 3L * 1024 * 1024)
  }

  @Test fun invalidBoundsOrBudgetsAreRejected() {
    assertNull(SlideshowDecodeBudget.plan(0, 100))
    assertNull(SlideshowDecodeBudget.plan(100, -1))
    assertNull(SlideshowDecodeBudget.plan(100, 100, 0))
    assertNull(SlideshowDecodeBudget.plan(100, 100, 100, 0))
    assertNull(SlideshowDecodeBudget.plan(Int.MAX_VALUE, Int.MAX_VALUE, 1, 1))
  }

  @Test fun hugeDimensionsUseLongArithmeticAndRepresentablePowerOfTwoSamples() {
    for ((w, h) in listOf(Int.MAX_VALUE to Int.MAX_VALUE, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
      val plan = requireNotNull(SlideshowDecodeBudget.plan(w, h))
      assertTrue(plan.sampleSize > 0 && plan.sampleSize and (plan.sampleSize - 1) == 0)
      assertTrue(plan.width in 1..2400 && plan.height in 1..2400)
      assertTrue(plan.width.toLong() * plan.height <= 3L * 1024 * 1024)
    }
  }

  @Test fun admissionRejectsActualDecoderOutputsOutsideEitherBound() {
    assertTrue(SlideshowDecodeBudget.fits(2160, 1440))
    assertFalse(SlideshowDecodeBudget.fits(2400, 2400))
    assertFalse(SlideshowDecodeBudget.fits(2401, 1))
    assertFalse(SlideshowDecodeBudget.fits(0, 1))
    assertFalse(SlideshowDecodeBudget.fits(Int.MAX_VALUE, Int.MAX_VALUE))
  }

  @Test fun exifOneThroughEightMapEveryCornerIntoTheUprightImage() {
    // TL, TR, BR, BL, expressed in normalized upright coordinates.
    val expected = listOf(
        listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1),
        listOf(1 to 0, 0 to 0, 0 to 1, 1 to 1),
        listOf(1 to 1, 0 to 1, 0 to 0, 1 to 0),
        listOf(0 to 1, 1 to 1, 1 to 0, 0 to 0),
        listOf(0 to 0, 0 to 1, 1 to 1, 1 to 0),
        listOf(1 to 0, 1 to 1, 0 to 1, 0 to 0),
        listOf(1 to 1, 1 to 0, 0 to 0, 0 to 1),
        listOf(0 to 1, 0 to 0, 1 to 0, 1 to 1))
    val corners = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1)
    for (orientation in 1..8) {
      val transform = SlideshowDecodeBudget.exifTransform(orientation)
      val actual = corners.map { (x, y) ->
        (transform.xx * x + transform.xy * y + transform.offsetX) to
            (transform.yx * x + transform.yy * y + transform.offsetY)
      }
      assertEquals("EXIF $orientation", expected[orientation - 1], actual)
      val size = SlideshowDecodeBudget.uprightSize(2000, 1500, orientation)
      assertEquals(if (orientation >= 5) 1500 else 2000, size?.width)
      assertEquals(if (orientation >= 5) 2000 else 1500, size?.height)
    }
  }

  @Test fun unknownExifOrientationUsesIdentityAndInvalidDimensionsAreRejected() {
    for (orientation in listOf(-1, 0, 9, Int.MAX_VALUE)) {
      assertEquals(SlideshowDecodeBudget.exifTransform(1), SlideshowDecodeBudget.exifTransform(orientation))
      assertEquals(SlideshowDecodeBudget.Size(120, 80), SlideshowDecodeBudget.uprightSize(120, 80, orientation))
    }
    assertNull(SlideshowDecodeBudget.uprightSize(0, 80, 6))
  }
}
