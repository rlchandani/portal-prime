/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.MusicPlayerHostKind.HOME_CARD
import com.immortal.launcher.MusicPlayerHostKind.MUSIC_SCREEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlayerVisibilityTest {
  private val music = MusicPlayerHosts().withAttached(MUSIC_SCREEN, true).withActive(MUSIC_SCREEN, true)

  private fun steps(start: MusicPlayerHosts, vararg moves: (MusicPlayerHosts) -> MusicPlayerHosts): List<PlayerHostEffect> {
    var s = start
    return moves.map { move -> move(s).let { next -> s.effectTo(next).also { s = next } } }
  }

  @Test fun visibleOnlyWhenAHostIsComposedAndInTheForeground() {
    assertTrue(music.visible)
    assertFalse(music.withAttached(MUSIC_SCREEN, false).visible)
    assertFalse(music.withActive(MUSIC_SCREEN, false).visible)
    assertFalse(MusicPlayerHosts().visible)
    // Composed in one host, foreground in another: still hidden.
    assertFalse(MusicPlayerHosts().withAttached(HOME_CARD, true).withActive(MUSIC_SCREEN, true).visible)
  }

  @Test fun openingMusicShowsThePlayerOnceComposedAndResumed() {
    assertEquals(listOf(PlayerHostEffect.None, PlayerHostEffect.Show(MUSIC_SCREEN)),
        steps(MusicPlayerHosts(), { it.withAttached(MUSIC_SCREEN, true) }, { it.withActive(MUSIC_SCREEN, true) }))
    assertEquals(listOf(PlayerHostEffect.None, PlayerHostEffect.Show(MUSIC_SCREEN)),
        steps(MusicPlayerHosts(), { it.withActive(MUSIC_SCREEN, true) }, { it.withAttached(MUSIC_SCREEN, true) }))
  }

  @Test fun leavingMusicForHomeHidesForOneFrameThenShowsTheCard() {
    // Music's surface leaves composition before Home's card attaches: Hide then Show, and the engine's
    // swap wait (MusicHostHandoff) means no pause in between.
    assertEquals(listOf(PlayerHostEffect.Hide, PlayerHostEffect.None, PlayerHostEffect.Show(HOME_CARD)),
        steps(music, { it.withAttached(MUSIC_SCREEN, false).withActive(MUSIC_SCREEN, false) },
            { it.withActive(HOME_CARD, true) }, { it.withAttached(HOME_CARD, true) }))
  }

  @Test fun bothComposedTheBetterRankedHostTakesThePlayer() {
    val card = MusicPlayerHosts().withAttached(HOME_CARD, true).withActive(HOME_CARD, true)
    assertEquals(PlayerHostEffect.Move(HOME_CARD, MUSIC_SCREEN),
        card.effectTo(card.withAttached(MUSIC_SCREEN, true).withActive(MUSIC_SCREEN, true)))
    val both = card.withAttached(MUSIC_SCREEN, true).withActive(MUSIC_SCREEN, true)
    assertEquals(PlayerHostEffect.Move(MUSIC_SCREEN, HOME_CARD), both.effectTo(both.withAttached(MUSIC_SCREEN, false)))
  }

  @Test fun homeOpeningAnAppHidesTheCardAndComingBackShowsIt() {
    val card = MusicPlayerHosts().withAttached(HOME_CARD, true).withActive(HOME_CARD, true)
    assertEquals(listOf(PlayerHostEffect.Hide, PlayerHostEffect.Show(HOME_CARD)),
        steps(card, { it.withActive(HOME_CARD, false) }, { it.withActive(HOME_CARD, true) }))
  }

  @Test fun anotherAppOrTheScreensaverHidesAndReturningShows() {
    assertEquals(listOf(PlayerHostEffect.Hide, PlayerHostEffect.Show(MUSIC_SCREEN)),
        steps(music, { it.withActive(MUSIC_SCREEN, false) }, { it.withActive(MUSIC_SCREEN, true) }))
  }

  @Test fun repeatedSignalsAreNoOps() {
    assertEquals(PlayerHostEffect.None, music.effectTo(music.withActive(MUSIC_SCREEN, true)))
    val hidden = music.withActive(MUSIC_SCREEN, false)
    assertEquals(PlayerHostEffect.None, hidden.effectTo(hidden.withActive(MUSIC_SCREEN, false)))
    assertNull(hidden.shownIn)
  }

  @Test fun aHideWaitsLongEnoughForAFrameSwapButNotNoticeably() {
    assertTrue(MusicHostHandoff.SWAP_MS in 100L..500L)
  }
}
