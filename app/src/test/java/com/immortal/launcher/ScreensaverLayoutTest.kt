/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen-aware screensaver (mock mini-screensaver-20261120): compact rules, LARGE untouched. */
class ScreensaverLayoutTest {
  // Portal+ 2160x1440 px at 1.125 (1920x1280 dp); the Mini 800x1280 dp at density 1 for clarity.
  private val plusW = 2160
  private val plusH = 1371
  private val plusDensity = 1.125f

  @Test fun classesComeFromTheWindowSize() {
    assertEquals(LayoutClass.LARGE, ScreensaverLayout.of(plusW, plusH, plusDensity))
    assertEquals(LayoutClass.LARGE, ScreensaverLayout.of(plusH, plusW, plusDensity))
    assertEquals(LayoutClass.COMPACT_PORTRAIT, ScreensaverLayout.of(800, 1280, 1f))
    assertEquals(LayoutClass.COMPACT_LANDSCAPE, ScreensaverLayout.of(1280, 800, 1f))
    // Mini at its panel density: 1520x2432 px / 1.9 = 800x1280 dp.
    assertEquals(LayoutClass.COMPACT_PORTRAIT, ScreensaverLayout.of(1520, 2432, 1.9f))
    assertEquals(LayoutClass.LARGE, ScreensaverLayout.of(800, 1280, 0f))
  }

  @Test fun largeTypeIsExactlyTheShippedFactor() {
    val shipped = (minOf(plusW, plusH) / plusDensity / 1219f).coerceIn(.72f, 1f) *
        HearthMockUnit.boost(plusW, plusH, plusDensity)
    assertEquals(shipped, ScreensaverLayout.faceScale(LayoutClass.LARGE, plusW, plusH, plusDensity), 0f)
    assertEquals(1f, ScreensaverLayout.faceScale(LayoutClass.COMPACT_PORTRAIT, 800, 1280, 1f), 0f)
    // The Mini no longer shrinks to 0.72x.
    assertEquals(.72f, ScreensaverLayout.shippedFaceScale(800, 1280, 1f), 1e-4f)
  }

  @Test fun nightTypeKeepsTheShippedPortalPlusNumbers() {
    val large = ScreensaverLayout.nightType(LayoutClass.LARGE, plusW, plusH, plusDensity)
    assertEquals(92f, large.clock, 0f); assertEquals(22f, large.date, 0f)
    assertEquals(70f, large.bottom, 0f); assertEquals(8f, large.gap, 0f)
    assertEquals(ScreensaverLayout.shippedFaceScale(plusW, plusH, plusDensity), large.factor, 0f)
    assertEquals(HearthMockUnit.boost(plusW, plusH, plusDensity), large.driftUnit, 0f)
    val upright = ScreensaverLayout.nightType(LayoutClass.COMPACT_PORTRAIT, 800, 1280, 1f)
    assertEquals(96f, upright.clock * upright.factor, 0f)
    assertEquals(24f, upright.date, 0f); assertEquals(64f, upright.bottom, 0f)
    assertEquals(92f, ScreensaverLayout.nightType(LayoutClass.COMPACT_LANDSCAPE, 1280, 800, 1f).clock, 0f)
  }

  @Test fun largeHasNoCompactGridAndShippedBuckets() {
    assertNull(ScreensaverLayout.grid(LayoutClass.LARGE))
    assertNull(ScreensaverLayout.splitBoxes(LayoutClass.LARGE, plusW, plusH, plusDensity))
    assertNull(ScreensaverLayout.wholeZone(LayoutClass.LARGE, plusW, plusH, plusDensity))
    val d = plusDensity
    fun dp(v: Int) = (v * d).toInt()
    for (column in ScreensaverLayout.Column.values()) {
      assertArrayEquals(intArrayOf(dp(40), dp(40), dp(40), dp(80)),
          ScreensaverLayout.bucketMargins(LayoutClass.LARGE, column, true, plusW, d, .5f))
      assertArrayEquals(intArrayOf(dp(40), dp(40), dp(40), dp(40)),
          ScreensaverLayout.bucketMargins(LayoutClass.LARGE, column, false, plusW, d))
    }
    assertEquals(ScreensaverLayout.SHIPPED_SPLIT, ScreensaverLayout.splitType(LayoutClass.LARGE))
  }

  @Test fun compactGridMatchesTheMock() {
    val up = ScreensaverLayout.grid(LayoutClass.COMPACT_PORTRAIT)!!
    assertTrue(up.topStack)
    assertEquals(40, up.sideDp); assertEquals(56, up.bottomDp); assertEquals(3, up.calendarRows)
    val turned = ScreensaverLayout.grid(LayoutClass.COMPACT_LANDSCAPE)!!
    assertFalse(turned.topStack)
    assertEquals(48, turned.sideDp); assertEquals(4, turned.calendarRows); assertEquals(440, turned.nowPlayingMaxDp)
    // HearthLayout's stacking helper agrees: upright stacks, turned does not.
    assertTrue(HearthLayout.stackBottom(800f, 1280f))
    assertFalse(HearthLayout.stackBottom(1280f, 800f))
  }

  @Test fun compactBucketsCapTheClockAndCaption() {
    // Clock bottom-left upright: 40 dp margins, its column ends at 56 % of 800 dp.
    val clock = ScreensaverLayout.bucketMargins(LayoutClass.COMPACT_PORTRAIT, ScreensaverLayout.Column.START,
        true, 800, 1f, .56f)
    assertArrayEquals(intArrayOf(40, 40, 800 - 40 - 448, 56), clock)
    val caption = ScreensaverLayout.bucketMargins(LayoutClass.COMPACT_PORTRAIT, ScreensaverLayout.Column.END,
        true, 800, 1f, .38f)
    assertEquals(800 - 40 - 304, caption[0]); assertEquals(40, caption[2])
    val turned = ScreensaverLayout.bucketMargins(LayoutClass.COMPACT_LANDSCAPE, ScreensaverLayout.Column.START,
        false, 1280, 1f)
    assertArrayEquals(intArrayOf(48, 40, 48, 40), turned)
  }

  @Test fun calendarCardWidths() {
    val up = ScreensaverLayout.grid(LayoutClass.COMPACT_PORTRAIT)!!
    assertEquals(720, ScreensaverLayout.calendarWidthPx(up, 800, 1f))
    assertEquals(1440, ScreensaverLayout.calendarWidthPx(up, 1600, 2f))
    val turned = ScreensaverLayout.grid(LayoutClass.COMPACT_LANDSCAPE)!!
    assertEquals(380, ScreensaverLayout.calendarWidthPx(turned, 1280, 1f))
    // Never wider than the viewport allows (HearthLayout.calendarWidthDp's cap).
    assertEquals(300, ScreensaverLayout.calendarWidthPx(turned, 396, 1f))
    assertEquals(3 to 1, ScreensaverLayout.calendarVisible(up, 4))
    assertEquals(2 to 0, ScreensaverLayout.calendarVisible(up, 2))
    assertEquals(4 to 0, ScreensaverLayout.calendarVisible(turned, 4))
  }

  @Test fun singlePhotoRules() {
    // Upright: a portrait keeps >= 80 % and fills; a 3:2 landscape keeps 42 % and sits whole.
    assertEquals(ScreensaverLayout.Single.FILL, ScreensaverLayout.single(1000, 1500, 800, 1280))
    assertEquals(ScreensaverLayout.Single.FILL, ScreensaverLayout.single(1200, 1600, 800, 1280))
    assertEquals(ScreensaverLayout.Single.WHOLE, ScreensaverLayout.single(1500, 1000, 800, 1280))
    assertEquals(ScreensaverLayout.Single.WHOLE, ScreensaverLayout.single(1000, 1000, 800, 1280))
    assertEquals(ScreensaverLayout.Single.SWEEP, ScreensaverLayout.single(3000, 1000, 800, 1280))
    // Turned: the landscape fills and the portrait sits whole.
    assertEquals(ScreensaverLayout.Single.FILL, ScreensaverLayout.single(1500, 1000, 1280, 800))
    assertEquals(ScreensaverLayout.Single.WHOLE, ScreensaverLayout.single(1000, 1500, 1280, 800))
    assertEquals(.9375, ScreensaverLayout.keep(1000, 1500, 800, 1280), 1e-9)
    assertEquals(0.0, ScreensaverLayout.keep(0, 1500, 800, 1280), 0.0)
  }

  @Test fun zonesRaiseWholePhotosClearOfTheClock() {
    val up = ScreensaverLayout.wholeZone(LayoutClass.COMPACT_PORTRAIT, 800, 1280, 1f)!!
    assertEquals(ScreensaverLayout.Box(32, 40, 768, 990), up)
    val turned = ScreensaverLayout.wholeZone(LayoutClass.COMPACT_LANDSCAPE, 1280, 800, 1f)!!
    assertEquals(ScreensaverLayout.Box(64, 40, 1216, 760), turned)
  }

  @Test fun stackedPairCardsSplitTheUprightScreen() {
    val (a, b) = ScreensaverLayout.stackPair(800, 1280, 1f)
    assertEquals(ScreensaverLayout.Box(24, 24, 776, 24 + 608), a)
    assertEquals(ScreensaverLayout.Box(24, 24 + 608 + 16, 776, 24 + 2 * 608 + 16), b)
    // A 3:2 photo in a 752x608 card keeps 83 % (the mock's figure).
    assertEquals(.8246, ScreensaverLayout.keep(1500, 1000, a.width, a.height), 1e-3)
    assertTrue(ScreensaverLayout.stackable(1500, 1000))
    assertFalse(ScreensaverLayout.stackable(1000, 1500))
    assertFalse(ScreensaverLayout.stackable(3000, 1000))
    assertFalse(ScreensaverLayout.stackable(0, 0))
  }

  @Test fun nightPhotoFitsLikeDayWhenItMismatches() {
    // Portal+ landscape panel: a 3:2 photo still covers (the approved Night photo).
    assertFalse(ScreensaverLayout.nightWhole(1500, 1000, plusW, plusH))
    // A portrait on the Portal+ and a landscape on the upright Mini are shown whole.
    assertTrue(ScreensaverLayout.nightWhole(1000, 1500, plusW, plusH))
    assertTrue(ScreensaverLayout.nightWhole(1500, 1000, 800, 1280))
    assertFalse(ScreensaverLayout.nightWhole(1000, 1500, 800, 1280))
    assertEquals(ScreensaverLayout.Box(28, 60, 772, 1040),
        ScreensaverLayout.nightZone(LayoutClass.COMPACT_PORTRAIT, 800, 1280, 1f))
    assertEquals(ScreensaverLayout.Box(80, 40, 1840, 1120),
        ScreensaverLayout.nightZone(LayoutClass.LARGE, 1920, 1280, 1f))
  }

  @Test fun wholeMotionContainsAndCentresWithAGentleZoom() {
    val zone = ScreensaverLayout.Box(28, 60, 772, 1040)
    val m = ScreensaverLayout.wholeMotion(1500, 1000, zone, motion = true)
    assertEquals(744f / 1500f, m.start.scale, 1e-6f)
    assertEquals(28f, m.start.x, 1e-3f)
    assertEquals(60f + (980f - 496f) / 2f, m.start.y, 1e-3f)
    assertEquals(m.start.scale * ScreensaverLayout.WHOLE_ZOOM, m.end.scale, 1e-6f)
    // The zoom stays centred on the zone.
    assertEquals(400f, m.end.x + 1500 * m.end.scale / 2f, 1e-3f)
    val still = ScreensaverLayout.wholeMotion(1500, 1000, zone, motion = false)
    assertEquals(still.start, still.end)
    val rect = ScreensaverLayout.rectOf(m.start, 1500, 1000)
    assertEquals(772f, rect[2], 1e-3f)
    assertEquals(0f, ScreensaverLayout.wholeMotion(0, 0, zone, true).start.scale, 0f)
  }

  @Test fun compactSplitGlanceBoxesAndType() {
    val (card, panel) = ScreensaverLayout.splitBoxes(LayoutClass.COMPACT_PORTRAIT, 800, 1280, 1f)!!
    assertEquals(ScreensaverLayout.Box(40, 40, 760, 40 + 666), card)
    assertEquals(ScreensaverLayout.Box(52, 706 + 24, 748, 1280 - 44), panel)
    val (tc, tp) = ScreensaverLayout.splitBoxes(LayoutClass.COMPACT_LANDSCAPE, 1280, 800, 1f)!!
    assertEquals(1280 - 40, tc.right); assertEquals(666, tc.width)
    assertEquals(60, tp.left); assertEquals(tc.left - 44, tp.right)
    assertEquals(176f, ScreensaverLayout.splitType(LayoutClass.COMPACT_PORTRAIT).clock, 0f)
    assertEquals(160f, ScreensaverLayout.splitType(LayoutClass.COMPACT_LANDSCAPE).clock, 0f)
    // Labels at least 14 dp on the Mini (were 8.6).
    assertEquals(14f, ScreensaverLayout.splitType(LayoutClass.COMPACT_PORTRAIT).label, 0f)
    // SplitGlancePanel.layout's default is the shipped (LARGE) layout.
    assertEquals(SplitGlancePanel.layout(2160, 1371, 1.125f),
        SplitGlancePanel.layout(2160, 1371, 1.125f, LayoutClass.LARGE))
  }

  @Test fun stackedDigitsOnlyUprightAtLargeAndXl() {
    assertTrue(ScreensaverLayout.stackedDigits(LayoutClass.COMPACT_PORTRAIT, DigitalClockConfig.SIZE_LARGE))
    assertTrue(ScreensaverLayout.stackedDigits(LayoutClass.COMPACT_PORTRAIT, DigitalClockConfig.SIZE_XL))
    assertFalse(ScreensaverLayout.stackedDigits(LayoutClass.COMPACT_PORTRAIT, DigitalClockConfig.SIZE_MEDIUM))
    assertFalse(ScreensaverLayout.stackedDigits(LayoutClass.COMPACT_LANDSCAPE, DigitalClockConfig.SIZE_LARGE))
    assertFalse(ScreensaverLayout.stackedDigits(LayoutClass.LARGE, DigitalClockConfig.SIZE_XL))
    assertEquals(420f, ScreensaverLayout.stackedDigitsDp(DigitalClockConfig.SIZE_LARGE), 0f)
  }

  @Test fun quietClockDriftsWithinItsOrbit() {
    var maxX = 0f
    var maxY = 0f
    for (minute in 0 until 24 * 60) {
      val (x, y) = ScreensaverLayout.quietDrift(minute * 60_000L, 2f)
      maxX = maxOf(maxX, kotlin.math.abs(x)); maxY = maxOf(maxY, kotlin.math.abs(y))
    }
    assertEquals(24f, maxX, .2f)
    assertEquals(18f, maxY, .2f)
    // It only moves at the minute redraw, by under 2 dp: never a visible jump.
    for (minute in 0 until 60) {
      val a = ScreensaverLayout.quietDrift(minute * 60_000L, 2f)
      val b = ScreensaverLayout.quietDrift((minute + 1) * 60_000L, 2f)
      assertTrue(kotlin.math.abs(a.first - b.first) < 4f && kotlin.math.abs(a.second - b.second) < 4f)
    }
  }

  @Test fun onlyAnOrientationFlipCountsAsARotation() {
    assertTrue(ScreensaverLayout.rotated(800, 1280, 1280, 800))
    assertTrue(ScreensaverLayout.rotated(1280, 800, 800, 1280))
    assertFalse(ScreensaverLayout.rotated(800, 1280, 800, 1220))
    assertFalse(ScreensaverLayout.rotated(0, 0, 800, 1280))
    assertFalse(ScreensaverLayout.rotated(1280, 800, 1280, 800))
  }
}
