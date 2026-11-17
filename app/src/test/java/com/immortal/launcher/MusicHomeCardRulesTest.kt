/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicHomeCardRulesTest {
  private val home = MusicHomeCardInputs(hasCurrent = true, musicOpen = false, customizing = false, dismissed = false)

  @After fun reset() = MusicHomeCardState.restore()

  @Test fun showsOnHomeWhenASongIsCurrentPlayingOrPaused() {
    assertTrue(MusicHomeCardRules.shows(home))
  }

  @Test fun hiddenWithNothingQueuedWhileMusicIsOpenWhileCustomizingOrDismissed() {
    assertFalse(MusicHomeCardRules.shows(home.copy(hasCurrent = false)))
    assertFalse(MusicHomeCardRules.shows(home.copy(musicOpen = true)))
    assertFalse(MusicHomeCardRules.shows(home.copy(customizing = true)))
    assertFalse(MusicHomeCardRules.shows(home.copy(dismissed = true)))
  }

  @Test fun aDismissalLastsUntilMusicPlaysAgain() {
    assertTrue(MusicHomeCardRules.afterPlaying(dismissed = true, playing = false))
    assertFalse(MusicHomeCardRules.afterPlaying(dismissed = true, playing = true))
    assertFalse(MusicHomeCardRules.afterPlaying(dismissed = false, playing = false))
  }

  @Test fun closeThenPlayElsewhereBringsTheCardBack() {
    MusicHomeCardState.dismiss()
    assertTrue(MusicHomeCardState.dismissed.value)
    MusicHomeCardState.onPlaying(false)
    assertTrue(MusicHomeCardState.dismissed.value)
    MusicHomeCardState.onPlaying(true)
    assertFalse(MusicHomeCardState.dismissed.value)
  }

  @Test fun theChipsPlayRestoresTheCard() {
    MusicHomeCardState.dismiss()
    MusicHomeCardState.restore()
    assertFalse(MusicHomeCardState.dismissed.value)
  }

  @Test fun theCardsPlayerMeetsYouTubesMinimumAndTheToastsClearIt() {
    assertTrue(MusicHomeCardDims.PLAYER_W >= YtPlayerSize.MIN_CSS_PX && MusicHomeCardDims.PLAYER_H >= 208)
    assertEquals(MusicHomeCardDims.PAD * 2 + MusicHomeCardDims.PLAYER_W + MusicHomeCardDims.GAP + MusicHomeCardDims.SIDE_W,
        MusicHomeCardDims.WIDTH)
    assertTrue(MusicHomeCardDims.TOAST_RESERVE > MusicHomeCardDims.WIDTH)
    assertTrue(MusicHomeCardDims.SKIP >= 64 && MusicHomeCardDims.PLAY >= 64 && MusicHomeCardDims.CLOSE >= 64)
  }
}
