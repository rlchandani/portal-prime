/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PremiumFraming.letterboxesOnBackdrop]: a single photo shown whole (letterboxed) sits on its own
 * blurred backdrop rather than the flat matte on every panel — the landscape Portal+ now included,
 * not only the portrait Portal Mini. The crop choice is unchanged: a cover-filling photo (fill, no
 * forced-whole) never asks for the backdrop.
 */
class PremiumFramingBackdropTest {
  // Portal+ (LARGE) is landscape; the Portal Mini upright is portrait.
  private val portalPlus = 1920 to 1219
  private val miniPortrait = 800 to 1280
  private val portrait = 981 to 1472 // 2:3
  private val landscape = 1472 to 981 // 3:2
  private val nearMatch = 1600 to 1200 // 4:3, close to the Portal+ aspect

  private fun decide(photo: Pair<Int, Int>, viewport: Pair<Int, Int>, fill: Boolean, forceWhole: Boolean) =
      PremiumFraming.letterboxesOnBackdrop(photo.first, photo.second, viewport.first, viewport.second,
          fill, forceWhole)

  // ---- Portal+ (landscape) — the fix: a letterboxed photo gets the blurred backdrop -------------

  @Test fun portalPlusLetterboxUnderFitSelectsBackdrop() {
    // "fit" always letterboxes; a badly-mismatched portrait on the wide Portal+ leaves real bars.
    assertTrue(decide(portrait, portalPlus, fill = false, forceWhole = false))
  }

  @Test fun portalPlusNoLongerNeedsPortraitOrientation() {
    // The old rule required height > width (Mini only). A landscape viewport now qualifies too.
    assertTrue("landscape viewport, mismatched photo, fit",
        decide(portrait, portalPlus, fill = false, forceWhole = false))
  }

  @Test fun portalPlusFillStillCrops() {
    // The crop behaviour is unchanged: under "fill" a mismatched photo cover-fills, no backdrop.
    assertFalse(decide(portrait, portalPlus, fill = true, forceWhole = false))
  }

  @Test fun portalPlusNearMatchKeepsMatte() {
    // Thin bars (mismatch within MISMATCH) stay on the matte even under "fit".
    assertFalse(decide(nearMatch, portalPlus, fill = false, forceWhole = false))
  }

  // ---- Portal Mini (portrait) — unchanged behaviour -------------------------------------------

  @Test fun miniForcedWholeSelectsBackdropEvenUnderFill() {
    // A portrait panel forces a badly-mismatched photo whole even when the user picked "fill".
    assertTrue(decide(landscape, miniPortrait, fill = true, forceWhole = true))
  }

  @Test fun miniLetterboxUnderFitSelectsBackdrop() {
    assertTrue(decide(landscape, miniPortrait, fill = false, forceWhole = false))
  }

  @Test fun nearMatchKeepsMatteOnMiniToo() {
    assertFalse(decide(nearMatch, miniPortrait.let { it.second to it.first }, fill = false, forceWhole = false))
  }
}
