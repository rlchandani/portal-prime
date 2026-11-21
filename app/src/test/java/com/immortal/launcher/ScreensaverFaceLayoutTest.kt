/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ScreensaverFaceLayout.Centre
import com.immortal.launcher.ui.clock.SplitFlapGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Photo-frame clock faces and the quiet night clock on the Mini; LARGE keeps the shipped values. */
class ScreensaverFaceLayoutTest {
  private val cp = LayoutClass.COMPACT_PORTRAIT
  private val cl = LayoutClass.COMPACT_LANDSCAPE
  private val large = LayoutClass.LARGE

  // ---- Flip ------------------------------------------------------------------------------------

  @Test fun flipSizesMapToCardFactors() {
    assertEquals(.8f, ScreensaverFaceLayout.flipSizeFactor(76), 0f)
    assertEquals(.9f, ScreensaverFaceLayout.flipSizeFactor(88), 0f)
    assertEquals(1f, ScreensaverFaceLayout.flipSizeFactor(96), 0f)
    // The spec default (100) and anything past Large are Large.
    assertEquals(1f, ScreensaverFaceLayout.flipSizeFactor(100), 0f)
    assertEquals(.8f, ScreensaverFaceLayout.flipSizeFactor(10), 0f)
  }

  @Test fun flipUprightIsHoursOverMinutesTwoCardsPerRow() {
    val f = ScreensaverFaceLayout.flip(cp, 800f, 1280f, 800f, 1280f, 1f, 96)!!
    assertEquals(2, f.rows)
    assertEquals(346f, f.cardW, 1e-3f)
    assertEquals(346f * 1.22f, f.cardH, 1e-3f)
    assertEquals(.7f * f.cardH, f.digit, 1e-3f)
    assertEquals(28f, f.gap, 0f)
    assertEquals(36f, f.rowGap, 0f)
    // Centred horizontally between 40 dp margins, the group raised 40 dp.
    assertEquals(40f, f.lefts[0], 1e-3f)
    assertEquals(800f - 40f, f.lefts[1] + f.cardW, 1e-3f)
    val groupH = 2 * f.cardH + f.rowGap
    assertEquals((1280f - groupH) / 2f - 40f, f.tops[0], 1e-3f)
    assertEquals(f.tops[0] + f.cardH + f.rowGap, f.tops[2], 1e-3f)
    assertEquals(f.lefts[0], f.lefts[2], 0f)
    assertTrue(f.tops[0] > 0f && f.tops[3] + f.cardH < 1280f)
  }

  @Test fun flipTurnedIsOneProportionedRow() {
    val f = ScreensaverFaceLayout.flip(cl, 1280f, 800f, 1280f, 800f, 1f, 96)!!
    assertEquals(1, f.rows)
    val cw = .17f * 1280f
    assertEquals(cw, f.cardW, 1e-3f)
    assertEquals(minOf(.55f * 800f, 1.35f * cw), f.cardH, 1e-3f)
    assertTrue(f.cardH <= 1.35f * f.cardW + 1e-3f)
    assertEquals(.06f * cw, f.gap, 1e-3f)
    assertEquals(.3f * cw, f.colonW, 1e-3f)
    val total = 4 * cw + 2 * f.gap + f.colonW
    assertEquals((1280f - total) / 2f, f.lefts[0], 1e-3f)
    assertEquals((1280f + total) / 2f, f.lefts[3] + f.cardW, 1e-3f)
    assertEquals(f.lefts[1] + f.cardW, f.colonLeft, 1e-3f)
    assertEquals(f.colonLeft + f.colonW, f.lefts[2], 1e-3f)
    assertEquals((800f - f.cardH) / 2f - 30f, f.tops[0], 1e-3f)
  }

  @Test fun flipSizeSettingScalesTheCards() {
    val large = ScreensaverFaceLayout.flip(cp, 800f, 1280f, 800f, 1280f, 1f, 96)!!
    val medium = ScreensaverFaceLayout.flip(cp, 800f, 1280f, 800f, 1280f, 1f, 88)!!
    val small = ScreensaverFaceLayout.flip(cp, 800f, 1280f, 800f, 1280f, 1f, 76)!!
    assertEquals(.9f * large.cardW, medium.cardW, 1e-3f)
    assertEquals(.8f * large.cardH, small.cardH, 1e-3f)
    val turned = ScreensaverFaceLayout.flip(cl, 1280f, 800f, 1280f, 800f, 1f, 76)!!
    assertEquals(.8f * .17f * 1280f, turned.cardW, 1e-3f)
  }

  @Test fun flipSizesFromTheScreenAndCentresInTheLargerView() {
    // The overlay is the screen plus a burn-in margin on every side (here 10 px at density 2).
    val f = ScreensaverFaceLayout.flip(cp, 1620f, 2580f, 1600f, 2560f, 2f, 96)!!
    assertEquals((1600f - 160f - 56f) / 2f, f.cardW, 1e-3f)
    assertEquals((1620f - (2 * f.cardW + f.gap)) / 2f, f.lefts[0], 1e-3f)
  }

  @Test fun flipLargeKeepsTheShippedRow() {
    assertNull(ScreensaverFaceLayout.flip(large, 2160f, 1440f, 2160f, 1440f, 1.125f, 96))
    val plus = SplitFlapGeometry.fit(2160f, 1440f)
    assertEquals(2160f * .18f, plus.cardWidth, 0f)
    assertEquals(1440f * .55f, plus.cardHeight, 0f)
  }

  // ---- Big / Bold ------------------------------------------------------------------------------

  private fun big(scale: Int = 380) = ClockSpec(mode = ClockMode.DIGITAL, font = Face.FONT_SANS_LIGHT,
      sizeScale = scale, position = GridPosition.MIDDLE_CENTER)

  private fun bold(scale: Int = 430) = big(scale).copy(font = "Bebas Neue", showDate = true)

  @Test fun centreFacesAreRecognisedFromTheirSpec() {
    assertEquals(Centre.BIG, ScreensaverFaceLayout.centreKind(big()))
    assertEquals(Centre.BOLD, ScreensaverFaceLayout.centreKind(bold()))
    // Classic / Minimal corner clocks, small centred clocks and flip faces are not centre faces.
    assertNull(ScreensaverFaceLayout.centreKind(big().copy(position = GridPosition.BOTTOM_LEFT)))
    assertNull(ScreensaverFaceLayout.centreKind(big(100)))
    assertNull(ScreensaverFaceLayout.centreKind(big().copy(mode = ClockMode.FLIP)))
    assertNull(ScreensaverFaceLayout.centreKind(big().copy(showSeconds = true)))
  }

  @Test fun catalogFacesAreCentreFaces() {
    assertEquals(listOf(380, 430), listOf("big", "bold").map { FaceCatalog.entryFor(it).sizes[1] })
  }

  @Test fun bigIsStackedUprightAndOneLineTurned() {
    val up = ScreensaverFaceLayout.centreType(cp, Centre.BIG, 380)!!
    assertTrue(up.stacked)
    assertEquals(340f, up.clockDp, 1e-3f)
    assertEquals(.86f, up.lineHeight, 0f)
    assertEquals(0f, up.dateDp, 0f)
    val turned = ScreensaverFaceLayout.centreType(cl, Centre.BIG, 380)!!
    assertTrue(!turned.stacked)
    assertEquals(260f, turned.clockDp, 1e-3f)
  }

  @Test fun boldIsStackedUprightWithAOneLineDateAndCappedTurned() {
    val up = ScreensaverFaceLayout.centreType(cp, Centre.BOLD, 430)!!
    assertTrue(up.stacked)
    assertEquals(470f, up.clockDp, 1e-3f)
    assertEquals(58f, up.dateDp, 1e-3f)
    assertEquals(1f, up.clusterMaxFraction, 0f)
    val turned = ScreensaverFaceLayout.centreType(cl, Centre.BOLD, 430)!!
    assertEquals(300f, turned.clockDp, 1e-3f)
    assertEquals(64f, turned.dateDp, 1e-3f)
    assertEquals(.7f, turned.clusterMaxFraction, 0f)
  }

  @Test fun centreSizesScaleFromTheMediumBaseline() {
    assertEquals(340f * 280f / 380f, ScreensaverFaceLayout.centreType(cp, Centre.BIG, 280)!!.clockDp, 1e-3f)
    assertEquals(260f * 460f / 380f, ScreensaverFaceLayout.centreType(cl, Centre.BIG, 460)!!.clockDp, 1e-3f)
    val small = ScreensaverFaceLayout.centreType(cp, Centre.BOLD, 320)!!
    assertEquals(470f * 320f / 430f, small.clockDp, 1e-3f)
    assertEquals(58f * 320f / 430f, small.dateDp, 1e-3f)
  }

  @Test fun centreLargeKeepsTheShippedGeometry() {
    assertNull(ScreensaverFaceLayout.centreType(large, Centre.BIG, 380))
    assertNull(ScreensaverFaceLayout.centreType(large, Centre.BOLD, 430))
    assertNull(ScreensaverFaceLayout.centreType(cp, null, 380))
  }

  @Test fun turnedBoldClusterStaysWithinSevenTenthsOfTheHeight() {
    val turned = ScreensaverFaceLayout.centreType(cl, Centre.BOLD, 500)!!
    val limit = ScreensaverFaceLayout.clockHeightLimit(turned, 10_000, 800, 1f)
    assertEquals((.7f * 800f - turned.dateDp * ScreensaverFaceLayout.DATE_LINE_HEIGHT).toInt(), limit)
    assertTrue(limit + turned.dateDp * ScreensaverFaceLayout.DATE_LINE_HEIGHT <= .7f * 800f + 1f)
    // A tighter bucket still wins; upright has no cluster cap.
    assertEquals(300, ScreensaverFaceLayout.clockHeightLimit(turned, 300, 800, 1f))
    val up = ScreensaverFaceLayout.centreType(cp, Centre.BOLD, 430)!!
    assertEquals(1200, ScreensaverFaceLayout.clockHeightLimit(up, 1200, 1280, 1f))
  }

  @Test fun stackedLinesSplitHoursFromMinutes() {
    assertEquals("9" to "07", ScreensaverFaceLayout.stackedLines("9:07", ":", false))
    assertEquals("12" to "45", ScreensaverFaceLayout.stackedLines("12:45", ":", false))
    assertEquals("09" to "07", ScreensaverFaceLayout.stackedLines("09.07", ".", false))
    assertEquals("1" to "07", ScreensaverFaceLayout.stackedLines("107", "", false))
    assertEquals("1" to "07", ScreensaverFaceLayout.stackedLines("1:07 PM", ":", true))
    assertEquals("" to "", ScreensaverFaceLayout.stackedLines("", ":", false))
  }

  @Test fun fitsShrinkOnly() {
    assertEquals(1f, ScreensaverFaceLayout.fitScale(100f, 100f, 200f, 200f), 0f)
    assertEquals(.5f, ScreensaverFaceLayout.fitScale(400f, 100f, 200f, 200f), 0f)
    assertEquals(.25f, ScreensaverFaceLayout.fitScale(100f, 800f, 200f, 200f), 0f)
    assertEquals(0f, ScreensaverFaceLayout.fitScale(100f, 100f, -5f, 200f), 0f)
    assertEquals(58f, ScreensaverFaceLayout.fitTextSize(58f, 400f, 720f), 0f)
    assertEquals(58f * 720f / 800f, ScreensaverFaceLayout.fitTextSize(58f, 800f, 720f), 1e-4f)
    assertEquals(58f, ScreensaverFaceLayout.fitTextSize(58f, 0f, 720f), 0f)
  }

  // ---- Quiet night clock -----------------------------------------------------------------------

  @Test fun quietUprightClockIsCappedAndTheDateIsLarger() {
    val q = ScreensaverFaceLayout.quietType(cp, 800f, 1280f, 1f)!!
    // min(.28 x 1280 = 358, .82 x 800 / 2.4 = 273, 250) = 250.
    assertEquals(250f, q.clock, 1e-3f)
    assertEquals(34f, q.date, 0f)
    assertEquals(24f, q.gap, 0f)
    val dense = ScreensaverFaceLayout.quietType(cp, 1520f, 2432f, 1.9f)!!
    assertEquals(250f * 1.9f, dense.clock, 1e-3f)
  }

  @Test fun quietTurnedFitsTheHeight() {
    val q = ScreensaverFaceLayout.quietType(cl, 1280f, 800f, 1f)!!
    assertEquals(minOf(.28f * 800f, .82f * 1280f / 2.4f), q.clock, 1e-3f)
    assertEquals(32f, q.date, 0f)
    assertEquals(24f, q.gap, 0f)
  }

  @Test fun quietLargeKeepsTheShippedType() {
    assertNull(ScreensaverFaceLayout.quietType(large, 2160f, 1371f, 1.125f))
  }
}
