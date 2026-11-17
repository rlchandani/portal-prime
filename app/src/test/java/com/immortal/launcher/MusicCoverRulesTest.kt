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

/** Full-screen alert cards and Home dialogs over the player: pause while shown, resume only if it was playing. */
class MusicCoverRulesTest {
  private fun reduce(s: YtEngineState, a: YtAction) = YtEngineReducer.reduce(s, a)

  private fun playing(): YtEngineState {
    val ready = YtEngineState(status = YtPlayerStatus.UNSTARTED, foreground = true)
    val loaded = reduce(ready, YtAction.PlayNow((1..3).map(::ytTrack), 0, "Mix")).state
    return reduce(loaded, YtPlayerEvent.StateChanged(YtPlayerStatus.PLAYING)).state
  }

  private fun paused() = reduce(reduce(playing(), YtAction.Pause).state, YtPlayerEvent.StateChanged(YtPlayerStatus.PAUSED)).state

  @Test fun aCardOverPlayingMusicPausesAndResumesWhenItCloses() {
    val covered = reduce(playing(), YtAction.Covered(true))
    assertEquals(listOf(YtEffect.Pause, YtEffect.Persist), covered.effects)
    assertTrue(covered.state.covered)
    assertTrue(covered.state.resumeAfterCover)
    assertFalse(covered.state.canPlay)
    val pausedUnder = reduce(covered.state, YtPlayerEvent.StateChanged(YtPlayerStatus.PAUSED)).state
    val closed = reduce(pausedUnder, YtAction.Covered(false))
    assertEquals(listOf(YtEffect.Play), closed.effects)
    assertTrue(closed.state.wantsPlay)
    assertFalse(closed.state.covered)
    assertFalse(closed.state.resumeAfterCover)
  }

  @Test fun aCardOverPausedMusicLeavesItPaused() {
    val covered = reduce(paused(), YtAction.Covered(true))
    assertTrue(covered.effects.isEmpty())
    assertFalse(covered.state.resumeAfterCover)
    assertTrue(reduce(covered.state, YtAction.Covered(false)).effects.isEmpty())
  }

  @Test fun pausingWhileCoveredMeansTheNextPlayIsTheUsers() {
    val covered = reduce(playing(), YtAction.Covered(true)).state
    val userPaused = reduce(covered, YtAction.Pause).state
    assertFalse(userPaused.resumeAfterCover)
    assertTrue(reduce(userPaused, YtAction.Covered(false)).effects.none { it == YtEffect.Play })
  }

  @Test fun leavingTheScreenWhileCoveredCancelsTheResume() {
    val covered = reduce(playing(), YtAction.Covered(true)).state
    val left = reduce(covered, YtAction.Foreground(false)).state
    assertFalse(left.resumeAfterCover)
    val back = reduce(reduce(left, YtAction.Foreground(true)).state, YtAction.Covered(false))
    assertTrue(back.effects.none { it == YtEffect.Play })
  }

  @Test fun playIsRefusedUnderACardAndAPhonePlayWaitsForItToClose() {
    val covered = reduce(paused(), YtAction.Covered(true)).state
    assertTrue(reduce(covered, YtAction.Play).effects.isEmpty())
    val asked = reduce(covered, YtAction.PlayWhenVisible).state
    assertTrue(asked.playWhenVisible)
    // Showing the player again (a host swap) while still covered keeps the request.
    assertTrue(reduce(asked, YtAction.Foreground(true)).state.playWhenVisible)
    assertEquals(listOf(YtEffect.Play), reduce(asked, YtAction.Covered(false)).effects)
  }

  @Test fun aCardDoesNotCancelTheSleepTimer() {
    val timer = MusicSleepTimer(MusicSleepChoice.MIN_30, untilMs = 1_000_000L)
    val armed = reduce(playing(), YtAction.SetSleep(timer)).state
    assertEquals(timer, reduce(armed, YtAction.Covered(true)).state.sleep)
  }

  @Test fun latePlayingUnderACardIsPausedAgain() {
    val covered = reduce(playing(), YtAction.Covered(true)).state
    val late = reduce(covered, YtPlayerEvent.StateChanged(YtPlayerStatus.PLAYING))
    assertTrue(YtEffect.Pause in late.effects)
  }

  @Test fun repeatedCoverSignalsAreNoOps() {
    val covered = reduce(playing(), YtAction.Covered(true)).state
    assertTrue(reduce(covered, YtAction.Covered(true)).effects.isEmpty())
    val open = paused()
    assertTrue(reduce(open, YtAction.Covered(false)).effects.isEmpty())
  }

  @Test fun bottomToastsAvoidTheHomeCardOnly() {
    assertTrue(MusicCoverRules.avoidBottom(MusicPlayerHostKind.HOME_CARD))
    assertFalse(MusicCoverRules.avoidBottom(MusicPlayerHostKind.MUSIC_SCREEN))
    assertFalse(MusicCoverRules.avoidBottom(null))
  }
}
