/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CI guard against sideways overflow on the Portal Mini (800 × 1220 dp upright, 1280 × 740 dp
 * turned): every adaptive header/row plan fits its width at font scale 1 and 1.15, and the
 * Portal+ (1920 × 1219 dp) keeps its shipped layout. See also [CallsHeaderLayoutTest],
 * [ToolsCategoryLayoutTest] and [CcCompactHeaderPillsTest].
 */
class MiniOverflowGuardTest {
  private val upright = LayoutClass.of(800, 1220)
  private val turned = LayoutClass.of(1280, 740)
  private val portalPlus = LayoutClass.of(1920, 1219)
  private val scales = listOf(1f, 1.15f)

  @Test fun theAddWidgetBarFitsTheMiniUprightAndKeepsItsLabelElsewhere() {
    val narrow = HomeAddBarLayout.of(800)
    assertFalse(narrow.backLabel)
    assertTrue("bar ${HomeAddBarLayout.width(narrow)} dp", HomeAddBarLayout.width(narrow) <= 800 - 2 * HomeAddBarLayout.WINDOW_MARGIN_DP)
    for (w in listOf(1280, 1920)) {
      val dims = HomeAddBarLayout.of(w)
      assertEquals("the shipped bar at $w", HomeAddBarLayout.FULL, dims)
      assertTrue(HomeAddBarLayout.width(dims) <= w - 2 * HomeAddBarLayout.WINDOW_MARGIN_DP)
    }
    assertTrue("touch targets stay ≥ 64 dp", HomeAddBarLayout.NARROW.shapeWidth >= 64)
  }

  @Test fun theGuestSetupStacksItsPreviewOnlyWhenTheSplitDoesNotFit() {
    // Settings' single pane on the Mini upright is ~744 dp; the turned Mini's content pane ~747.
    assertFalse(GuestSetupLayout.sideBySide(upright, 744.0))
    assertFalse(GuestSetupLayout.sideBySide(turned, 747.0))
    assertTrue("the Portal+ keeps the shipped split", GuestSetupLayout.sideBySide(portalPlus, 1100.0))
    assertTrue("a wide compact pane keeps it too", GuestSetupLayout.sideBySide(turned, 1100.0))
  }

  @Test fun dinnerPresetWordsGetRoomOnEveryWindow() {
    assertEquals(DinnerPresetLayout.LARGE, DinnerPresetLayout.of(portalPlus))
    assertEquals(3, DinnerPresetLayout.LARGE.columns)
    // The sheet's pane: 800 − 48 margin − 80 padding upright; 1280 − 48 − 80 turned.
    val uprightText = DinnerPresetLayout.textWidth(DinnerPresetLayout.of(upright), 672)
    val turnedText = DinnerPresetLayout.textWidth(DinnerPresetLayout.of(turned), 1152)
    // "downstairs" (the longest word) at the tile's size, generously 0.6 em a character.
    assertTrue("upright $uprightText dp", uprightText >= 10 * DinnerPresetLayout.PORTRAIT.textSp * 0.6)
    assertTrue("turned $turnedText dp", turnedText >= 10 * DinnerPresetLayout.LANDSCAPE.textSp * 0.6)
  }

  @Test fun theHomeMusicCardFitsEveryWindow() {
    for (w in listOf(800, 1280, 1920)) {
      val side = MusicHomeCardDims.sideWidth(w, inset = 24)
      assertTrue("card at $w", MusicHomeCardDims.widthOf(MusicHomeCardMode.CARD, side) <= w - 48)
    }
  }

  @Test fun followMeAnswersNeverClip() {
    val c = MusicStageDims.COMPACT
    for (scale in scales) {
      // The strip beside the player with the sheet open, Mini upright (~344 dp).
      val strip = MusicPillFit.answer(344.0, c, 16.0, keepDp = 160.0, fontScale = scale)
      assertEquals(MusicPillFit.Answer.ICONS, strip)
      assertTrue(160 + 16 + 2 * c.target + 16 <= 344)
      // The idle card: ~316 dp upright, ~273 dp turned.
      for (inside in listOf(316.0, 273.0)) {
        val a = MusicPillFit.answer(inside, c, 14.0, fontScale = scale)
        val used = when (a) {
          MusicPillFit.Answer.PILLS -> MusicPillFit.pill(MusicV3StageCopy.CONTINUE, c, true, scale) + 14 +
              MusicPillFit.pill(MusicV3StageCopy.NOT_NOW, c, fontScale = scale)
          MusicPillFit.Answer.CONTINUE_PILL -> MusicPillFit.pill(MusicV3StageCopy.CONTINUE, c, true, scale) + 14 + c.target
          MusicPillFit.Answer.ICONS -> 2.0 * c.target + 14
        }
        assertTrue("$a uses $used of $inside at $scale", used <= inside)
      }
    }
    assertEquals("the Portal+ strip keeps both pills", MusicPillFit.Answer.PILLS,
        MusicPillFit.answer(824.0, MusicStageDims.LARGE, 28.0, keepDp = 160.0))
  }

  @Test fun musicSheetTabsStayClearOfTheCloseButton() {
    val c = MusicStageDims.COMPACT
    for (scale in scales) {
      val clear = 728.0 - 2 * (c.target + 12)
      val short = MusicPillFit.shortTabs(clear, c, scale)
      assertTrue("upright tabs at $scale", MusicPillFit.tabsWidth(c, short, scale) <= clear)
      assertFalse("turned keeps the full labels", MusicPillFit.shortTabs(1208.0 - 2 * (c.target + 12), c, scale))
    }
  }

  @Test fun musicHeadingActionsMoveUnderTheTitleWhenNarrow() {
    val c = MusicStageDims.COMPACT
    val pills = listOf(MusicPillFit.pill("Play", c, true), MusicPillFit.pill("Shuffle", c, true), MusicPillFit.pill("See all", c))
    val title = MusicPillFit.headingWidth(LIKED_LABEL, c)
    assertFalse("upright sheet", MusicPillFit.actionsBeside(728.0, title + 16, 110.0, pills, 12.0))
    assertTrue("turned sheet", MusicPillFit.actionsBeside(1208.0, title + 16, 110.0, pills, 12.0))
    val big = MusicStageDims.LARGE
    val bigPills = listOf(MusicPillFit.pill("Play", big, true), MusicPillFit.pill("Shuffle", big, true), MusicPillFit.pill("See all", big))
    assertTrue("Portal+", MusicPillFit.actionsBeside(1700.0, MusicPillFit.headingWidth(LIKED_LABEL, big) + 16, 110.0, bigPills, 12.0))
  }

  @Test fun teslaTileLabelsFitTheMiniTiles() {
    // The Mini's charging tiles are ~128 units inside; labels at 18 sp, generously 0.6 em a character.
    for (label in listOf(CcTeslaTileCopy.POWER, CcTeslaTileCopy.ADDED, CcTeslaTileCopy.TO_FULL, CcTeslaTileCopy.LAST))
      assertTrue(label, CcTeslaTileCopy.short(label).length * 18 * 0.6 <= 128)
    assertTrue(CcTeslaTileCopy.short(CcTeslaTileCopy.LIMIT).length * 18 * 0.6 <= 84)
  }
}
