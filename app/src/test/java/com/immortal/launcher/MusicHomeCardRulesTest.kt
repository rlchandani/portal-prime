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

  @After fun reset() {
    MusicHomeCardState.restore()
    MusicHomeCardState.expand()
    MusicHomeCardState.place(null)
  }

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
    assertTrue(MusicHomeCardDims.SKIP >= 64 && MusicHomeCardDims.PLAY >= 64 && MusicHomeCardDims.CLOSE >= 64)
  }

  @Test fun closeMinimizesToTheTileAndExpandRestoresTheCardWithoutTouchingPlayback() {
    assertEquals(MusicHomeCardMode.CARD, MusicHomeCardRules.mode(MusicHomeCardState.minimized.value))
    MusicHomeCardState.minimize()
    assertEquals(MusicHomeCardMode.TILE, MusicHomeCardRules.mode(MusicHomeCardState.minimized.value))
    assertFalse("minimizing never hides the card", MusicHomeCardState.dismissed.value)
    assertTrue(MusicHomeCardRules.shows(home))
    MusicHomeCardState.expand()
    assertEquals(MusicHomeCardMode.CARD, MusicHomeCardRules.mode(MusicHomeCardState.minimized.value))
  }

  @Test fun theTileStaysMinimizedAcrossAPausedHideAndPlayAgain() {
    MusicHomeCardState.minimize()
    MusicHomeCardState.dismiss()
    MusicHomeCardState.onPlaying(true)
    assertFalse(MusicHomeCardState.dismissed.value)
    assertTrue(MusicHomeCardState.minimized.value)
  }

  @Test fun onlyAPausedCardOrTileAutoHides() {
    assertTrue(MusicHomeCardRules.autoHides(playing = false))
    assertFalse(MusicHomeCardRules.autoHides(playing = true))
  }

  @Test fun theTileIsThePlayerAtYouTubesMinimumWithTheRailBesideIt() {
    assertTrue(MusicHomeCardDims.TILE_PLAYER >= YtPlayerSize.MIN_CSS_PX)
    assertEquals(MusicHomeCardDims.TILE_PLAYER, MusicHomeCardDims.TILE_H)
    assertEquals(MusicHomeCardDims.TILE_PLAYER + MusicHomeCardDims.RAIL_GAP + MusicHomeCardDims.RAIL_W,
        MusicHomeCardDims.TILE_W)
    assertTrue(MusicHomeCardDims.RAIL_W >= 64)
    assertEquals(MusicHomeCardDims.TILE_W, MusicHomeCardDims.widthOf(MusicHomeCardMode.TILE))
    assertEquals(MusicHomeCardDims.WIDTH, MusicHomeCardDims.widthOf(MusicHomeCardMode.CARD))
    assertEquals(MusicHomeCardDims.HEIGHT, MusicHomeCardDims.heightOf(MusicHomeCardMode.CARD))
  }

  @Test fun theCardAndTheTileRememberSeparatePositions() {
    assertEquals(MusicFloatKind.CARD, MusicHomeCardMode.CARD.kind)
    assertEquals(MusicFloatKind.TILE, MusicHomeCardMode.TILE.kind)
    assertTrue(MusicFloatKind.CARD.key != MusicFloatKind.TILE.key)
  }
}
