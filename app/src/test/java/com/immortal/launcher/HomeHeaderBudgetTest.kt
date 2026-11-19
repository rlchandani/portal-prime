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

class HomeHeaderBudgetTest {
  private val busiest = HomeHeaderItems(musicChip = true, miniPlayer = true, mic = true, customize = true)
  private val typical = HomeHeaderItems(musicChip = true, miniPlayer = false, mic = true, customize = true)

  @Test fun theChipIsCompactAndFixed() {
    assertEquals(198, MusicV3HomeChipDims.WIDTH)
    assertTrue(MusicV3HomeChipDims.WIDTH <= 200)
    assertTrue("touch targets", MusicV3HomeChipDims.BUTTON >= 64)
    assertTrue(MusicV3HomeChipDims.THUMB < MusicV3HomeChipDims.HEIGHT)
  }

  @Test fun theButtonsAlwaysFitAtEveryCheckedWidth() {
    for (width in HomeHeaderBudget.CHECKED_WIDTHS) for (items in listOf(busiest, typical)) {
      val plan = HomeHeaderBudget.plan(width, items)
      assertTrue("$width $items -> $plan", HomeHeaderBudget.width(plan, items) <= width)
    }
  }

  @Test fun thePortalKeepsEverythingButTheMiniPlayerWithTheChip() {
    val plan = HomeHeaderBudget.plan(1920, typical)
    assertEquals(HomeHeaderPlan(wordmark = true, room = true, connectedLabel = true, miniPlayer = false, musicChip = true),
        plan)
    assertFalse(HomeHeaderBudget.plan(1920, busiest).miniPlayer)
  }

  @Test fun aSmallTabletKeepsTheMusicControlsAndDropsTextFirst() {
    val plan = HomeHeaderBudget.plan(1280, typical)
    assertTrue(plan.musicChip)
    assertFalse(plan.connectedLabel)
    assertFalse(plan.room)
  }

  @Test fun theChipIsTheLastThingToGo() {
    // The narrowest width the regular tier serves (below it the compact tier takes over).
    val plan = HomeHeaderBudget.plan(HomeHeaderBudget.COMPACT_BELOW, typical)
    assertFalse(plan.compact)
    assertFalse(plan.musicChip)
    assertFalse(plan.wordmark || plan.room || plan.connectedLabel || plan.miniPlayer)
  }

  @Test fun theMiniUprightGetsTheCompactTier() {
    for (items in listOf(busiest, typical, busiest.copy(homeControl = true), typical.copy(homeControl = true))) {
      val plan = HomeHeaderBudget.plan(800, items)
      assertTrue("$items -> $plan", plan.compact)
      assertTrue("$items -> $plan", HomeHeaderBudget.width(plan, items) <= 800)
      assertFalse(plan.wordmark || plan.room || plan.connectedLabel || plan.miniPlayer)
    }
  }

  @Test fun theCompactTierKeepsTheMusicChipAndDropsWifiFirst() {
    val withHc = typical.copy(homeControl = true)
    val plan = HomeHeaderBudget.plan(800, withHc)
    assertTrue("the chip survives at 800 even with Home Control", plan.musicChip)
    assertFalse(plan.wifi)
    val noHc = HomeHeaderBudget.plan(800, typical)
    assertTrue(noHc.musicChip)
    assertTrue("room for the Wi-Fi glyph without Home Control", noHc.wifi)
  }

  @Test fun theCompactWidthCountsFiftySixDpButtonsAndTheOverflow() {
    val bare = HomeHeaderItems(musicChip = false, miniPlayer = false, mic = false, customize = false)
    val none = HomeHeaderPlan(wordmark = false, room = false, connectedLabel = false, miniPlayer = false,
        musicChip = false, compact = true, wifi = false)
    // Screensaver, overflow (phone/sleep/theme), music, search, settings.
    assertEquals(2 * 24 + 5 * 56 + 4 * 12, HomeHeaderBudget.width(none, bare))
    // The pencil (Customize), voice and Home Control each add one 56 dp button.
    val all = bare.copy(mic = true, customize = true, homeControl = true)
    assertEquals(2 * 24 + 8 * 56 + 7 * 12, HomeHeaderBudget.width(none, all))
    assertEquals(56, HomeHeaderBudget.COMPACT_BUTTON)
  }

  @Test fun theMiniTurnedKeepsTheRegularTier() {
    // 1280×740 is COMPACT_LANDSCAPE for the grid, but its header width is the existing 1280 case.
    assertEquals(LayoutClass.COMPACT_LANDSCAPE, LayoutClass.of(1280, 740))
    for (items in listOf(busiest, typical.copy(homeControl = true))) {
      val plan = HomeHeaderBudget.plan(1280, items)
      assertFalse(plan.compact)
      assertTrue(HomeHeaderBudget.width(plan, items) <= 1280)
    }
  }

  @Test fun everyCheckedWidthIncludesTheMini() {
    assertEquals(listOf(1920, 1280, 800), HomeHeaderBudget.CHECKED_WIDTHS)
  }

  @Test fun anEmptyHeaderWidthCountsEdgesGapsAndButtons() {
    val bare = HomeHeaderItems(musicChip = false, miniPlayer = false, mic = false, customize = false)
    val none = HomeHeaderPlan(wordmark = false, room = false, connectedLabel = false, miniPlayer = false, musicChip = false)
    // 7 buttons + wifi = 8 pieces, 7 gaps (the leading group takes the free width; no spacer).
    assertEquals(2 * 24 + 7 * 68 + 22 + 7 * 16, HomeHeaderBudget.width(none, bare))
  }

  @Test fun theHomeControlButtonFitsAndKeepsThePortalHeaderWhole() {
    val withHc = typical.copy(homeControl = true)
    assertEquals(HomeHeaderPlan(wordmark = true, room = true, connectedLabel = true, miniPlayer = false, musicChip = true),
        HomeHeaderBudget.plan(1920, withHc))
    for (width in HomeHeaderBudget.CHECKED_WIDTHS) for (items in listOf(busiest.copy(homeControl = true), withHc)) {
      val plan = HomeHeaderBudget.plan(width, items)
      assertTrue("$width $items -> $plan", HomeHeaderBudget.width(plan, items) <= width)
    }
    val bare = HomeHeaderItems(musicChip = false, miniPlayer = false, mic = false, customize = false)
    val none = HomeHeaderPlan(wordmark = false, room = false, connectedLabel = false, miniPlayer = false, musicChip = false)
    assertEquals(HomeHeaderBudget.width(none, bare) + 68 + 16, HomeHeaderBudget.width(none, bare.copy(homeControl = true)))
  }
}
