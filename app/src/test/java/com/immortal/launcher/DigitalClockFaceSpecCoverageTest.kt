/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.DigitalClockFaceSpec.Face
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Digital clock face rules: style and size tables, fitting, backdrops, segments, flip and hands. */
class DigitalClockFaceSpecCoverageTest {
  @Test fun everyStyleMapsToItsFaceAndUnknownIsClassic() {
    assertEquals(Face.CLASSIC, DigitalClockFaceSpec.face(DigitalClockConfig.STYLE_CLASSIC))
    assertEquals(Face.BOLD, DigitalClockFaceSpec.face(DigitalClockConfig.STYLE_BOLD))
    assertEquals(Face.NEON, DigitalClockFaceSpec.face(DigitalClockConfig.STYLE_NEON))
    assertEquals(Face.LED, DigitalClockFaceSpec.face(DigitalClockConfig.STYLE_SEGMENT))
    assertEquals(Face.FLIP, DigitalClockFaceSpec.face(DigitalClockConfig.STYLE_FLIP))
    assertEquals(Face.ANALOG, DigitalClockFaceSpec.face(DigitalClockConfig.STYLE_ANALOG))
    assertEquals(Face.CLASSIC, DigitalClockFaceSpec.face(""))
    assertEquals(Face.CLASSIC, DigitalClockFaceSpec.face("BOLD"))
  }

  @Test fun timeSizeGrowsWithEachSizeAndUnknownIsLarge() {
    val sizes = listOf(DigitalClockConfig.SIZE_SMALL, DigitalClockConfig.SIZE_MEDIUM,
        DigitalClockConfig.SIZE_LARGE, DigitalClockConfig.SIZE_XL).map(DigitalClockFaceSpec::timeSizeDp)
    assertEquals(listOf(150f, 200f, 250f, 320f), sizes)
    assertEquals(250f, DigitalClockFaceSpec.timeSizeDp("huge"), 0f)
  }

  @Test fun flipHeightGrowsWithEachSizeAndUnknownIsLarge() {
    val fractions = listOf(DigitalClockConfig.SIZE_SMALL, DigitalClockConfig.SIZE_MEDIUM,
        DigitalClockConfig.SIZE_LARGE, DigitalClockConfig.SIZE_XL).map(DigitalClockFaceSpec::flipHeightFraction)
    assertEquals(listOf(.26f, .33f, .40f, .48f), fractions)
    assertEquals(.40f, DigitalClockFaceSpec.flipHeightFraction(""), 0f)
    assertTrue(fractions.all { it in 0f..1f })
  }

  @Test fun fitOnlyShrinksAndIgnoresUnmeasuredInput() {
    assertEquals(100f, DigitalClockFaceSpec.fit(100f, measured = 0f, room = 50f), 0f)
    assertEquals(100f, DigitalClockFaceSpec.fit(100f, measured = 80f, room = 0f), 0f)
    assertEquals(100f, DigitalClockFaceSpec.fit(100f, measured = -1f, room = -1f), 0f)
    assertEquals(100f, DigitalClockFaceSpec.fit(100f, measured = 80f, room = 200f), 0f)
    assertEquals(100f, DigitalClockFaceSpec.fit(100f, measured = 80f, room = 80f), 0f)
    assertEquals(50f, DigitalClockFaceSpec.fit(100f, measured = 200f, room = 100f), 1e-4f)
  }

  @Test fun redAndGradientBackdropsIgnoreTheFace() {
    Face.values().forEach { face ->
      val red = DigitalClockFaceSpec.backdrop(face, DigitalClockConfig.BG_RED)
      assertEquals(red.inner, red.outer)
      assertFalse(red.radial)
      val gradient = DigitalClockFaceSpec.backdrop(face, DigitalClockConfig.BG_GRADIENT)
      assertFalse(gradient.radial)
      assertEquals(DigitalClockFaceSpec.backdrop(Face.CLASSIC, DigitalClockConfig.BG_GRADIENT), gradient)
    }
  }

  @Test fun blackBackdropIsTheFacesOwnRoom() {
    fun black(face: Face) = DigitalClockFaceSpec.backdrop(face, DigitalClockConfig.BG_BLACK)
    assertFalse(black(Face.BOLD).radial)
    assertTrue(black(Face.NEON).radial)
    assertFalse(black(Face.LED).radial)
    assertEquals(black(Face.LED).inner, black(Face.LED).outer)
    assertTrue(black(Face.ANALOG).radial)
    assertTrue(black(Face.CLASSIC).radial)
    assertEquals(black(Face.CLASSIC), black(Face.FLIP))
    assertEquals(black(Face.CLASSIC), DigitalClockFaceSpec.backdrop(Face.CLASSIC, "unknown"))
    // Every face's black room is opaque.
    Face.values().forEach { assertEquals(0xFF, black(it).inner ushr 24) }
  }

  @Test fun segmentsLightTheRightBarsAndOnlyKnownOnes() {
    assertEquals(7, DigitalClockFaceSpec.segments('8').length)
    assertEquals("bc", DigitalClockFaceSpec.segments('1'))
    assertFalse(DigitalClockFaceSpec.segments('0').contains('g'))
    assertEquals("", DigitalClockFaceSpec.segments(' '))
    assertEquals("", DigitalClockFaceSpec.segments(':'))
    ('0'..'9').forEach { c ->
      val lit = DigitalClockFaceSpec.segments(c)
      assertTrue("$c", lit.isNotEmpty() && lit.all { it in DigitalClockFaceSpec.SEGMENT_PATHS.keys })
      assertEquals("$c has no repeats", lit.length, lit.toSet().size)
    }
    assertEquals(('a'..'g').toSet(), DigitalClockFaceSpec.SEGMENT_PATHS.keys)
  }

  @Test fun flipKeepsMockProportionsAndShrinksToFit() {
    val wide = DigitalClockFaceSpec.flip(100f, maxWidth = 0f)
    assertEquals(100f, wide.cardH, 0f)
    assertEquals(74f, wide.cardW, 1e-3f)
    assertEquals(4 * 74f + 3 * 7.4f + 22.2f, wide.total, 1e-3f)
    assertEquals(wide, DigitalClockFaceSpec.flip(100f, maxWidth = wide.total))
    val narrow = DigitalClockFaceSpec.flip(100f, maxWidth = wide.total / 2)
    assertEquals(50f, narrow.cardH, 1e-3f)
    assertEquals(wide.total / 2, narrow.total, 1e-2f)
  }

  @Test fun flipProgressStaggersAndClamps() {
    assertEquals(0f, DigitalClockFaceSpec.flipProgress(0, -100), 0f)
    assertEquals(0f, DigitalClockFaceSpec.flipProgress(0, 0), 0f)
    assertEquals(.5f, DigitalClockFaceSpec.flipProgress(0, DigitalClockFaceSpec.FLIP_MS / 2), 1e-3f)
    assertEquals(1f, DigitalClockFaceSpec.flipProgress(0, DigitalClockFaceSpec.FLIP_MS), 0f)
    assertEquals(1f, DigitalClockFaceSpec.flipProgress(0, 10_000), 0f)
    assertEquals(0f, DigitalClockFaceSpec.flipProgress(3, 3 * DigitalClockFaceSpec.FLIP_STAGGER_MS), 0f)
    assertEquals(1f, DigitalClockFaceSpec.flipProgress(3,
        3 * DigitalClockFaceSpec.FLIP_STAGGER_MS + DigitalClockFaceSpec.FLIP_MS), 0f)
  }

  @Test fun handAnglesSweepAndWrapAtTwelve() {
    val noon = DigitalClockFaceSpec.handAngles(12, 0, 0, 0)
    assertEquals(0f, noon[0], 0f); assertEquals(0f, noon[1], 0f); assertEquals(0f, noon[2], 0f)
    val threeThirty = DigitalClockFaceSpec.handAngles(15, 30, 0, 0)
    assertEquals(105f, threeThirty[0], 1e-3f)
    assertEquals(180f, threeThirty[1], 1e-3f)
    val sweep = DigitalClockFaceSpec.handAngles(0, 0, 10, 500)
    assertEquals(63f, sweep[2], 1e-3f)
    assertEquals(10.5f * 6f / 60f, sweep[1], 1e-4f)
  }
}
