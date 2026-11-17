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

class MusicPlayerVisibilityTest {
  private val onScreen = MusicPlayerVisibility(hosted = true, foreground = true)

  private fun steps(start: MusicPlayerVisibility, vararg moves: (MusicPlayerVisibility) -> MusicPlayerVisibility):
      List<PlayerVisibilityEffect> {
    var s = start
    return moves.map { move -> move(s).let { next -> s.effectTo(next).also { s = next } } }
  }

  @Test fun visibleOnlyWhenComposedAndInTheForeground() {
    assertTrue(onScreen.visible)
    assertFalse(onScreen.withHosted(false).visible)
    assertFalse(onScreen.withForeground(false).visible)
    assertFalse(MusicPlayerVisibility().visible)
  }

  @Test fun openingMusicShowsThePlayerOnceComposedAndResumed() {
    assertEquals(listOf(PlayerVisibilityEffect.NONE, PlayerVisibilityEffect.SHOW),
        steps(MusicPlayerVisibility(), { it.withHosted(true) }, { it.withForeground(true) }))
    assertEquals(listOf(PlayerVisibilityEffect.NONE, PlayerVisibilityEffect.SHOW),
        steps(MusicPlayerVisibility(), { it.withForeground(true) }, { it.withHosted(true) }))
  }

  @Test fun closingMusicStartsTheGraceOnce() {
    // The surface's onDispose (foreground false) runs before AndroidView's onRelease (hosted false).
    assertEquals(listOf(PlayerVisibilityEffect.START_GRACE, PlayerVisibilityEffect.NONE),
        steps(onScreen, { it.withForeground(false) }, { it.withHosted(false) }))
    assertEquals(listOf(PlayerVisibilityEffect.START_GRACE, PlayerVisibilityEffect.NONE),
        steps(onScreen, { it.withHosted(false) }, { it.withForeground(false) }))
  }

  @Test fun anotherAppOrTheScreensaverOverMusicStartsTheGraceAndReturningCancelsIt() {
    assertEquals(listOf(PlayerVisibilityEffect.START_GRACE, PlayerVisibilityEffect.SHOW),
        steps(onScreen, { it.withForeground(false) }, { it.withForeground(true) }))
  }

  @Test fun reopeningMusicWithinTheGraceShowsTheSamePlayer() {
    assertEquals(
        listOf(PlayerVisibilityEffect.START_GRACE, PlayerVisibilityEffect.NONE,
            PlayerVisibilityEffect.NONE, PlayerVisibilityEffect.SHOW),
        steps(onScreen, { it.withForeground(false) }, { it.withHosted(false) },
            { it.withHosted(true) }, { it.withForeground(true) }))
  }

  @Test fun repeatedSignalsAreNoOps() {
    assertEquals(PlayerVisibilityEffect.NONE, onScreen.effectTo(onScreen.withForeground(true)))
    val hidden = onScreen.withForeground(false)
    assertEquals(PlayerVisibilityEffect.NONE, hidden.effectTo(hidden.withForeground(false)))
  }
}
