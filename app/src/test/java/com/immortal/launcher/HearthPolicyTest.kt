/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ui.clock.SplitFlapGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decode edges, easing, night warmth, orbit, layout, split-flap fit and drawn weather. */
class HearthPolicyTest {
  @Test fun decodeEdgesFollowThePanelLongEdge() {
    assertEquals(2400, PhotoEdges.decodeEdge(2160)) // Portal+: unchanged from today
    assertEquals(1472, PhotoEdges.decodeEdge(1280)) // Portal Mini, either orientation
    assertEquals(PhotoEdges.MIN_DECODE_EDGE, PhotoEdges.decodeEdge(300))
    assertEquals(PhotoEdges.MAX_DECODE_EDGE, PhotoEdges.decodeEdge(0))
    assertEquals(1400, PhotoEdges.partnerEdge(2160))
    assertEquals(832, PhotoEdges.partnerEdge(1280))
  }

  @Test fun decodeEdgeNeverExceedsTheBudgetCeiling() {
    for (edge in listOf(1, 800, 1280, 1920, 2160, 3840, Int.MAX_VALUE / 2))
      assertTrue(PhotoEdges.decodeEdge(edge) <= SlideshowDecodeBudget.MAX_EDGE)
  }

  @Test fun cubicEaseMatchesKnownCurveValues() {
    val linear = CubicEase(0f, 0f, 1f, 1f)
    assertEquals(.3f, linear.getInterpolation(.3f), 1e-3f)
    assertEquals(0f, HearthEasing.SOFT.getInterpolation(0f), 0f)
    assertEquals(1f, HearthEasing.SOFT.getInterpolation(1f), 0f)
    assertEquals(0f, HearthEasing.SOFT.getInterpolation(Float.NaN), 0f)
    // CSS ease (.25,.1,.25,1) at x=.5 is 0.8024.
    assertEquals(.8024f, CubicEase(.25f, .1f, .25f, 1f).getInterpolation(.5f), 2e-3f)
    // The drift curve front-loads its motion (no linear "tell" at the end).
    assertTrue(HearthEasing.DRIFT.getInterpolation(.5f) > .8f)
  }

  @Test fun easedCurvesAreMonotone() {
    for (curve in listOf(HearthEasing.SOFT, HearthEasing.DRIFT, HearthEasing.OUT)) {
      var last = 0f
      for (i in 0..200) {
        val v = curve.getInterpolation(i / 200f)
        assertTrue(v >= last - 1e-4f)
        last = v
      }
    }
  }

  @Test fun settleFinishesWellInsideTheDefaultInterval() {
    assertTrue(HearthEasing.SETTLE_MS < ScreensaverConfig.DEFAULT_INTERVAL * 1000L)
    assertTrue(HearthEasing.DISSOLVE_MS < HearthEasing.SETTLE_MS)
  }

  @Test fun eveningLevelRampsAfterSunsetAndBeforeSunrise() {
    val sunrise = 7 * 60
    val sunset = 17 * 60
    assertEquals(0f, HearthNight.level(12 * 60, sunrise, sunset), 0f)
    assertEquals(0f, HearthNight.level(sunset, sunrise, sunset), 0f)
    assertEquals(.5f, HearthNight.level(sunset + HearthNight.DUSK_MINUTES / 2, sunrise, sunset), .02f)
    assertEquals(1f, HearthNight.level(23 * 60, sunrise, sunset), 0f)
    assertEquals(1f, HearthNight.level(3 * 60, sunrise, sunset), 0f) // across midnight
    assertTrue(HearthNight.level(sunrise - 10, sunrise, sunset) in 0f..0.5f)
    assertEquals(0f, HearthNight.level(sunrise + 1, sunrise, sunset), 0f)
  }

  @Test fun eveningMultiplyIsNeutralByDayAndWarmAtNight() {
    assertEquals(0xFFFFFFFF.toInt(), HearthNight.multiplyColor(0f))
    val night = HearthNight.multiplyColor(1f)
    val r = night shr 16 and 255
    val b = night and 255
    assertTrue("warm: red stays above blue", r > b)
    assertTrue(kotlin.math.abs(r - (255 * .58f).toInt()) <= 2)
    assertEquals(0xFFFFFFFF.toInt(), HearthNight.duskMultiply(0f))
    assertTrue((HearthNight.duskMultiply(1f) and 255) < (HearthNight.duskMultiply(1f) shr 16 and 255))
    assertTrue(HearthNight.nightInk(.6f))
    assertFalse(HearthNight.nightInk(.2f))
  }

  @Test fun orbitStaysWithinItsRadiusAndIsContinuous() {
    var previous = HearthOrbit.x(0L, 12f, HearthOrbit.NIGHT_PERIOD_X_MS)
    for (t in 0L..HearthOrbit.NIGHT_PERIOD_X_MS step 60_000L) {
      val x = HearthOrbit.x(t, 12f, HearthOrbit.NIGHT_PERIOD_X_MS)
      val y = HearthOrbit.y(t, 9f, HearthOrbit.NIGHT_PERIOD_Y_MS)
      assertTrue(kotlin.math.abs(x) <= 12f && kotlin.math.abs(y) <= 9f)
      // A minute's step is a couple of dp: invisible, but never static all night.
      assertTrue(kotlin.math.abs(x - previous) < 2.5f)
      previous = x
    }
  }

  @Test fun bottomCornersStackBelowAThousandDpOrInPortrait() {
    assertFalse(HearthLayout.stackBottom(1920f, 1219f)) // Portal+
    assertTrue(HearthLayout.stackBottom(800f, 1220f))   // Mini portrait
    assertFalse(HearthLayout.stackBottom(1280f, 740f))  // Mini landscape
    assertTrue(HearthLayout.stackBottom(960f, 600f))
  }

  @Test fun calendarWidthHonoursSizeAndViewport() {
    assertEquals(430f, HearthLayout.calendarWidthDp(1f, 1920f), 0f)
    assertEquals(430f * 1.22f, HearthLayout.calendarWidthDp(1.22f, 1920f), .01f)
    assertEquals(400f - 2 * HearthLayout.EDGE_DP, HearthLayout.calendarWidthDp(1f, 400f), 0f)
    assertEquals(0f, HearthLayout.calendarWidthDp(1f, 10f), 0f)
  }

  @Test fun splitFlapFitsPortraitPanelsAndKeepsThePortalPlusFace() {
    val plus = SplitFlapGeometry.fit(2160f, 1440f)
    // Landscape panels keep today's exact proportions.
    assertEquals(2160f * .18f, plus.cardWidth, 0f)
    assertEquals(1440f * .55f, plus.cardHeight, 0f)
    assertEquals(1440f * .55f * .7f, plus.digitSize, 0f)
    val mini = SplitFlapGeometry.fit(800f, 1280f)
    // A bold digit is about 0.6 em wide; it must fit the card with a margin.
    assertTrue(mini.digitSize * .6f < mini.cardWidth * .9f)
    assertTrue(mini.cardHeight <= mini.cardWidth * 2.1f + .01f)
    assertTrue(mini.digitSize < mini.cardHeight)
  }

  @Test fun weatherTextBecomesADrawnGlyph() {
    assertEquals(HearthWeather.CLEAR to "72°", HearthWeather.parse("☀️ 72°"))
    assertEquals(HearthWeather.RAIN to "9°", HearthWeather.parse("🌧️ 9°"))
    assertEquals(HearthWeather.UNKNOWN to "12°", HearthWeather.parse("12°"))
    assertNull(HearthWeather.parse("  "))
  }
}
