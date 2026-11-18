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
    val plan = HomeHeaderBudget.plan(900, typical)
    assertFalse(plan.musicChip)
    assertFalse(plan.wordmark || plan.room || plan.connectedLabel || plan.miniPlayer)
  }

  @Test fun anEmptyHeaderWidthCountsEdgesGapsAndButtons() {
    val bare = HomeHeaderItems(musicChip = false, miniPlayer = false, mic = false, customize = false)
    val none = HomeHeaderPlan(wordmark = false, room = false, connectedLabel = false, miniPlayer = false, musicChip = false)
    // 7 buttons + spacer + wifi = 9 pieces, 8 gaps.
    assertEquals(2 * 24 + 7 * 68 + 22 + 8 * 16, HomeHeaderBudget.width(none, bare))
  }
}
