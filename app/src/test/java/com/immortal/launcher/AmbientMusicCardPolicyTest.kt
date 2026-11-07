/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.AmbientMusicCardPolicy.CONTROLS_TIMEOUT_MS
import com.immortal.launcher.AmbientMusicCardPolicy.PAUSED_LEAVES_FRAME_AFTER_MS
import com.immortal.launcher.AmbientMusicCardPolicy.PROGRESS_TICK_MS
import com.immortal.launcher.AmbientMusicCardVisibility.HIDDEN
import com.immortal.launcher.AmbientMusicCardVisibility.VISIBLE_PAUSED_DIM
import com.immortal.launcher.AmbientMusicCardVisibility.VISIBLE_PLAYING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientMusicCardPolicyTest {
  private val playing = NowPlayingState(PlaybackState.PLAYING, title = "Low tide, early",
      artist = "Marrow & Vale", durationMs = 200_000L, positionMs = 50_000L,
      positionUpdatedElapsedMs = 1_000L, mediaId = "a")
  private val paused = playing.copy(state = PlaybackState.PAUSED)

  // --- visibility ------------------------------------------------------------
  @Test fun nullStoppedIdleAndUntitledStatesAreHidden() {
    assertEquals(HIDDEN, AmbientMusicCardPolicy.visibility(null, 5_000L, 0L))
    assertEquals(HIDDEN, AmbientMusicCardPolicy.visibility(playing.copy(state = PlaybackState.STOPPED), 5_000L, 0L))
    assertEquals(HIDDEN, AmbientMusicCardPolicy.visibility(playing.copy(state = PlaybackState.IDLE), 5_000L, 0L))
    assertEquals(HIDDEN, AmbientMusicCardPolicy.visibility(playing.copy(title = "  "), 5_000L, 0L))
  }

  @Test fun playingIsVisibleRegardlessOfHowLongAgoItChanged() {
    assertEquals(VISIBLE_PLAYING, AmbientMusicCardPolicy.visibility(playing, 10_000_000L, 0L))
  }

  @Test fun pausedDimsUntilTenMinutesThenLeavesTheFrame() {
    assertEquals(10 * 60_000L, PAUSED_LEAVES_FRAME_AFTER_MS)
    val changed = 1_000L
    assertEquals(VISIBLE_PAUSED_DIM,
        AmbientMusicCardPolicy.visibility(paused, changed + PAUSED_LEAVES_FRAME_AFTER_MS - 1, changed))
    assertEquals(HIDDEN,
        AmbientMusicCardPolicy.visibility(paused, changed + PAUSED_LEAVES_FRAME_AFTER_MS, changed))
  }

  @Test fun revealedControlsKeepAStalePausedCardOnTheFrame() {
    assertEquals(VISIBLE_PAUSED_DIM, AmbientMusicCardPolicy.visibility(
        paused, PAUSED_LEAVES_FRAME_AFTER_MS * 3, 0L, controlsRevealed = true))
    assertEquals(HIDDEN, AmbientMusicCardPolicy.visibility(
        null, 1L, 0L, controlsRevealed = true))
  }

  @Test fun aClockThatRunsBackwardsDoesNotHideAPausedCard() {
    assertEquals(VISIBLE_PAUSED_DIM, AmbientMusicCardPolicy.visibility(paused, 500L, 9_000L))
  }

  // --- state-change clock ----------------------------------------------------
  @Test fun changeTimeAdvancesOnlyWhenPlaybackOrTrackChanges() {
    assertEquals(42L, AmbientMusicCardPolicy.stateChangedAt(playing, playing.copy(positionMs = 99L), 42L, 9_000L))
    assertEquals(9_000L, AmbientMusicCardPolicy.stateChangedAt(playing, paused, 42L, 9_000L))
    assertEquals(9_000L, AmbientMusicCardPolicy.stateChangedAt(playing, playing.copy(mediaId = "b"), 42L, 9_000L))
    assertEquals(9_000L, AmbientMusicCardPolicy.stateChangedAt(playing, playing.copy(mediaId = "", title = "Other"), 42L, 9_000L))
    assertEquals(9_000L, AmbientMusicCardPolicy.stateChangedAt(paused, null, 42L, 9_000L))
  }

  @Test fun firstSightingOfAPausedTrackUsesItsPositionStampWhenPlausible() {
    val pausedLongAgo = paused.copy(positionUpdatedElapsedMs = 2_000L)
    assertEquals(2_000L, AmbientMusicCardPolicy.stateChangedAt(null, pausedLongAgo, 0L, 9_000L))
    // A stamp from the future or absent falls back to now.
    assertEquals(9_000L, AmbientMusicCardPolicy.stateChangedAt(null, paused.copy(positionUpdatedElapsedMs = 20_000L), 0L, 9_000L))
    assertEquals(9_000L, AmbientMusicCardPolicy.stateChangedAt(null, paused.copy(positionUpdatedElapsedMs = 0L), 0L, 9_000L))
    assertEquals(9_000L, AmbientMusicCardPolicy.stateChangedAt(null, playing, 0L, 9_000L))
  }

  // --- progress ---------------------------------------------------------------
  @Test fun progressInterpolatesWhilePlaying() {
    assertEquals(0.30f, AmbientMusicCardPolicy.progressFraction(playing, 11_000L)!!, 0.0001f)
  }

  @Test fun progressHoldsWhilePausedAndClampsToTheTrack() {
    assertEquals(0.25f, AmbientMusicCardPolicy.progressFraction(paused, 99_000L)!!, 0.0001f)
    assertEquals(1f, AmbientMusicCardPolicy.progressFraction(playing, 10_000_000L)!!, 0f)
    assertEquals(0f, AmbientMusicCardPolicy.progressFraction(playing.copy(positionMs = -5L), 1_000L)!!, 0f)
    // A position stamp after "now" never rewinds the line.
    assertEquals(0.25f, AmbientMusicCardPolicy.progressFraction(playing, 0L)!!, 0.0001f)
  }

  @Test fun progressWithoutAStampUsesTheRawPosition() {
    assertEquals(0.25f, AmbientMusicCardPolicy.progressFraction(
        playing.copy(positionUpdatedElapsedMs = 0L), 99_000L)!!, 0.0001f)
  }

  @Test fun liveOrUnknownDurationHasNoProgressLine() {
    assertNull(AmbientMusicCardPolicy.progressFraction(playing.copy(durationMs = 0L), 1_000L))
    assertNull(AmbientMusicCardPolicy.progressFraction(null, 1_000L))
  }

  // --- controls ---------------------------------------------------------------
  @Test fun controlsShowForEightSecondsAfterTheLastTouch() {
    assertEquals(8_000L, CONTROLS_TIMEOUT_MS)
    assertFalse(AmbientMusicCardPolicy.controlsVisible(null, 5_000L))
    assertTrue(AmbientMusicCardPolicy.controlsVisible(1_000L, 1_000L + CONTROLS_TIMEOUT_MS - 1))
    assertFalse(AmbientMusicCardPolicy.controlsVisible(1_000L, 1_000L + CONTROLS_TIMEOUT_MS))
  }

  // --- scheduling -------------------------------------------------------------
  @Test fun onlyAVisiblePlayingCardWithADurationTicksEverySecond() {
    assertEquals(PROGRESS_TICK_MS, AmbientMusicCardPolicy.nextRefreshDelayMs(playing, 5_000L, 0L, null))
    assertNull(AmbientMusicCardPolicy.nextRefreshDelayMs(playing.copy(durationMs = 0L), 5_000L, 0L, null))
    assertNull(AmbientMusicCardPolicy.nextRefreshDelayMs(null, 5_000L, 0L, null))
  }

  @Test fun pausedCardWakesOnceWhenItIsDueToLeave() {
    assertEquals(PAUSED_LEAVES_FRAME_AFTER_MS - 4_000L,
        AmbientMusicCardPolicy.nextRefreshDelayMs(paused, 5_000L, 1_000L, null))
    assertNull(AmbientMusicCardPolicy.nextRefreshDelayMs(paused, PAUSED_LEAVES_FRAME_AFTER_MS + 2_000L, 1_000L, null))
  }

  @Test fun revealedControlsWakeWhenTheyTimeOut() {
    // Paused card with controls: only the controls deadline matters.
    assertEquals(3_000L, AmbientMusicCardPolicy.nextRefreshDelayMs(paused, 6_000L, 5_000L, 1_000L))
    // Playing card: the 1s tick is sooner.
    assertEquals(PROGRESS_TICK_MS, AmbientMusicCardPolicy.nextRefreshDelayMs(playing, 6_000L, 5_000L, 1_000L))
    // Playing card without duration still wakes to hide the controls.
    assertEquals(3_000L, AmbientMusicCardPolicy.nextRefreshDelayMs(playing.copy(durationMs = 0L), 6_000L, 0L, 1_000L))
  }

  // --- touch ------------------------------------------------------------------
  @Test fun tapTargetsAreDecidedFromTheDownAndUpPoints() {
    assertTrue(AmbientMusicCardPolicy.isTap(10f, 10f, 40f, 50f))
    assertFalse(AmbientMusicCardPolicy.isTap(10f, 10f, 200f, 10f))
  }

  @Test fun tapRoutingRevealsHidesOrPassesThrough() {
    val p = AmbientMusicCardPolicy
    assertEquals(AmbientMusicCardTap.REVEAL_CONTROLS, p.routeTap(cardVisible = true, controlsVisible = false, onCard = true))
    assertEquals(AmbientMusicCardTap.PASS_THROUGH, p.routeTap(cardVisible = true, controlsVisible = false, onCard = false))
    assertEquals(AmbientMusicCardTap.KEEP_CONTROLS, p.routeTap(cardVisible = true, controlsVisible = true, onCard = true))
    assertEquals(AmbientMusicCardTap.HIDE_CONTROLS, p.routeTap(cardVisible = true, controlsVisible = true, onCard = false))
    assertEquals(AmbientMusicCardTap.PASS_THROUGH, p.routeTap(cardVisible = false, controlsVisible = true, onCard = true))
  }

  @Test fun playPauseFollowsTheCurrentPlaybackState() {
    assertEquals(AmbientMusicCommand.PAUSE, AmbientMusicCardPolicy.playPauseCommand(playing))
    assertEquals(AmbientMusicCommand.PLAY, AmbientMusicCardPolicy.playPauseCommand(paused))
  }

  @Test fun commandsGoToTheTransportForTheState() {
    val calls = mutableListOf<String>()
    val transport = object : MusicTransport {
      override fun play() { calls += "play" }
      override fun pause() { calls += "pause" }
      override fun next() { calls += "next" }
      override fun previous() { calls += "previous" }
      override fun seekTo(positionMs: Long) { calls += "seek" }
    }
    assertTrue(AmbientMusicCardPolicy.send(AmbientMusicCommand.PAUSE, transport))
    assertTrue(AmbientMusicCardPolicy.send(AmbientMusicCommand.PLAY, transport))
    assertTrue(AmbientMusicCardPolicy.send(AmbientMusicCommand.NEXT, transport))
    assertTrue(AmbientMusicCardPolicy.send(AmbientMusicCommand.PREVIOUS, transport))
    assertFalse(AmbientMusicCardPolicy.send(AmbientMusicCommand.NEXT, null))
    assertEquals(listOf("pause", "play", "next", "previous"), calls)
  }

  @Test fun aThrowingTransportIsReportedNotPropagated() {
    val broken = object : MusicTransport {
      override fun play() = error("gone")
      override fun pause() = error("gone")
      override fun next() = error("gone")
      override fun previous() = error("gone")
      override fun seekTo(positionMs: Long) = error("gone")
    }
    assertFalse(AmbientMusicCardPolicy.send(AmbientMusicCommand.PLAY, broken))
  }

  @Test fun pausedSubtitleSaysPaused() {
    assertEquals("Paused · Marrow & Vale", AmbientMusicCardPolicy.subtitle(paused))
    assertEquals("Marrow & Vale", AmbientMusicCardPolicy.subtitle(playing))
    assertEquals("Paused", AmbientMusicCardPolicy.subtitle(paused.copy(artist = "")))
    assertEquals("", AmbientMusicCardPolicy.subtitle(playing.copy(artist = "")))
  }
}
