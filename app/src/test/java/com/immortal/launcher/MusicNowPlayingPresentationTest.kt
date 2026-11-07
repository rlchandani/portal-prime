/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.MusicNowPlayingPresentation.MusicScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicNowPlayingPresentationTest {
  private val p = MusicNowPlayingPresentation

  private fun np(state: PlaybackState, title: String = "Low tide, early") =
      NowPlayingState(state = state, title = title, artist = "Marrow & Vale")

  // --- live position --------------------------------------------------------
  @Test
  fun livePosition_advancesWhilePlaying() {
    assertEquals(85_000L, p.livePositionMs(80_000L, 1_000L, 6_000L, 252_000L, playing = true))
  }

  @Test
  fun livePosition_frozenWhilePaused() {
    assertEquals(80_000L, p.livePositionMs(80_000L, 1_000L, 60_000L, 252_000L, playing = false))
  }

  @Test
  fun livePosition_clampsToDuration() {
    assertEquals(252_000L, p.livePositionMs(250_000L, 0L + 1L, 999_999L, 252_000L, playing = true))
  }

  @Test
  fun livePosition_unknownDurationIsNotClampedAbove() {
    assertEquals(70_000L, p.livePositionMs(10_000L, 1L, 60_001L, 0L, playing = true))
  }

  @Test
  fun livePosition_clockBehindUpdateDoesNotRewind() {
    assertEquals(80_000L, p.livePositionMs(80_000L, 10_000L, 5_000L, 252_000L, playing = true))
  }

  @Test
  fun livePosition_missingUpdateStampDoesNotExtrapolate() {
    assertEquals(80_000L, p.livePositionMs(80_000L, 0L, 5_000_000L, 252_000L, playing = true))
  }

  @Test
  fun livePosition_negativeClampsToZero() {
    assertEquals(0L, p.livePositionMs(-500L, 0L, 0L, 252_000L, playing = false))
  }

  @Test
  fun livePosition_fromState() {
    val s = NowPlayingState(PlaybackState.PLAYING, title = "t", durationMs = 100_000L,
        positionMs = 10_000L, positionUpdatedElapsedMs = 2_000L)
    assertEquals(13_000L, p.livePositionMs(s, nowElapsedMs = 5_000L))
    assertEquals(10_000L, p.livePositionMs(s.copy(state = PlaybackState.PAUSED), 5_000L))
  }

  // --- labels ---------------------------------------------------------------
  @Test
  fun formatTime_minutesAndSeconds() {
    assertEquals("0:00", p.formatTime(0L))
    assertEquals("0:09", p.formatTime(9_999L))
    assertEquals("1:25", p.formatTime(85_000L))
    assertEquals("59:59", p.formatTime(3_599_000L))
  }

  @Test
  fun formatTime_hours() {
    assertEquals("1:00:00", p.formatTime(3_600_000L))
    assertEquals("2:03:04", p.formatTime((2 * 3600 + 3 * 60 + 4) * 1000L))
  }

  @Test
  fun formatTime_negativeIsZero() {
    assertEquals("0:00", p.formatTime(-1_000L))
  }

  @Test
  fun statusLine_playingAndPaused() {
    assertEquals("Playing on Family Area", p.statusLine(playing = true, roomName = "Family Area"))
    assertEquals("Paused on Family Area", p.statusLine(playing = false, roomName = " Family Area "))
  }

  @Test
  fun statusLine_blankRoomFallsBack() {
    assertEquals("Playing on this Portal", p.statusLine(playing = true, roomName = "  "))
  }

  @Test
  fun displayTitle_fallback() {
    assertEquals("Unknown track", p.displayTitle(""))
    assertEquals("Unknown track", p.displayTitle("   "))
    assertEquals("Embers", p.displayTitle(" Embers "))
  }

  // --- seek -----------------------------------------------------------------
  @Test
  fun seekKnob_requiresCanSeekAndDuration() {
    assertTrue(p.showSeekKnob(canSeek = true, durationMs = 1L))
    assertFalse(p.showSeekKnob(canSeek = true, durationMs = 0L))
    assertFalse(p.showSeekKnob(canSeek = false, durationMs = 100_000L))
  }

  @Test
  fun progressFraction_boundsAndUnknownDuration() {
    assertEquals(0.5f, p.progressFraction(50L, 100L), 1e-6f)
    assertEquals(1f, p.progressFraction(500L, 100L), 1e-6f)
    assertEquals(0f, p.progressFraction(-5L, 100L), 1e-6f)
    assertEquals(0f, p.progressFraction(50L, 0L), 1e-6f)
  }

  @Test
  fun seekTarget_mapsAndClampsFraction() {
    assertEquals(126_000L, p.seekTargetMs(0.5f, 252_000L))
    assertEquals(252_000L, p.seekTargetMs(1.7f, 252_000L))
    assertEquals(0L, p.seekTargetMs(-1f, 252_000L))
    assertEquals(0L, p.seekTargetMs(Float.NaN, 252_000L))
  }

  // --- screen state ---------------------------------------------------------
  @Test
  fun screenState_firstRunWhenNeverSeen() {
    assertEquals(MusicScreenState.FIRST_RUN, p.screenState(null, hasEverSeenSession = false))
  }

  @Test
  fun screenState_nothingPlayingWhenSeenBefore() {
    assertEquals(MusicScreenState.NOTHING_PLAYING, p.screenState(null, hasEverSeenSession = true))
  }

  @Test
  fun screenState_playingAndPaused() {
    assertEquals(MusicScreenState.PLAYING, p.screenState(np(PlaybackState.PLAYING), false))
    assertEquals(MusicScreenState.PAUSED, p.screenState(np(PlaybackState.PAUSED), true))
  }

  @Test
  fun screenState_inactiveStatesAreIdle() {
    assertEquals(MusicScreenState.NOTHING_PLAYING, p.screenState(np(PlaybackState.STOPPED), true))
    assertEquals(MusicScreenState.FIRST_RUN, p.screenState(np(PlaybackState.IDLE), false))
    assertEquals(MusicScreenState.NOTHING_PLAYING,
        p.screenState(np(PlaybackState.PLAYING, title = " "), true))
  }
}
