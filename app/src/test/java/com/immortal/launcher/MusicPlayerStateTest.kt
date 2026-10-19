/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MusicPlayerStateTest {
  private fun ready(
      track: MusicTrack = MusicTrack("track-1", "ma:family", "A track", durationMs = 240_000),
      state: PlaybackState = PlaybackState.PLAYING,
      capabilities: MusicCapabilities = MusicCapabilities(play = true, pause = true,
          next = true, previous = true, seek = true, queue = true, lyrics = true, rooms = true),
  ) = MusicPlayerSnapshot(connection = MusicConnection.READY, sourceId = "ma:family",
      track = track, playback = state, positionMs = 30_000, positionAtElapsedMs = 1_000,
      capabilities = capabilities)

  @Test fun disconnectedAndBusySourcesRejectEveryCommand() {
    val s = ready()
    for (connection in MusicConnection.entries.filter { it != MusicConnection.READY }) {
      for (command in MusicCommand.entries) assertFalse(s.copy(connection = connection).can(command))
    }
    for (command in MusicCommand.entries) assertFalse(s.copy(audioOwnedByOther = true).can(command))
  }

  @Test fun liveRadioNeverExposesSeekOrTrackSkippingEvenWhenReported() {
    val radio = ready(MusicTrack("radio", "ma:family", "Live radio", isLive = true))
    assertFalse(radio.can(MusicCommand.SEEK))
    assertFalse(radio.can(MusicCommand.NEXT))
    assertFalse(radio.can(MusicCommand.PREVIOUS))
    assertTrue(radio.can(MusicCommand.PAUSE))
    assertNull(radio.visiblePositionMs(100_000))
  }

  @Test fun unknownDurationDisablesSeekingWithoutDisablingPlayback() {
    val s = ready(MusicTrack("track", "ma:family", "Track"))
    assertFalse(s.can(MusicCommand.SEEK))
    assertTrue(s.can(MusicCommand.PAUSE))
    assertNull(s.visiblePositionMs(2_000))
  }

  @Test fun localSessionDoesNotAcquireMusicAssistantCapabilities() {
    val s = ready(capabilities = MusicCapabilities(play = true, pause = true, seek = true))
    assertFalse(s.can(MusicCommand.OPEN_QUEUE))
    assertFalse(s.can(MusicCommand.OPEN_LYRICS))
    assertFalse(s.can(MusicCommand.CHANGE_ROOMS))
    assertFalse(s.can(MusicCommand.NEXT))
    assertTrue(s.can(MusicCommand.SEEK))
  }

  @Test fun emptyOrMismatchedSourceNeverControlsAnotherPlayer() {
    for (command in MusicCommand.entries) {
      assertFalse(ready().copy(sourceId = "").can(command))
      assertFalse(ready().copy(sourceId = "local:other").can(command))
    }
  }

  @Test fun playingProgressUsesMonotonicElapsedTimeAndClampsToDuration() {
    val s = ready()
    assertEquals(32_000L, s.visiblePositionMs(3_000))
    assertEquals(30_000L, s.visiblePositionMs(900))
    assertEquals(240_000L, s.visiblePositionMs(1_000_000))
  }

  @Test fun pausedProgressDoesNotAdvanceOrOverflow() {
    val s = ready(state = PlaybackState.PAUSED)
    assertEquals(30_000L, s.visiblePositionMs(Long.MAX_VALUE))
    assertEquals(240_000L, ready().copy(positionMs = Long.MAX_VALUE)
        .visiblePositionMs(Long.MAX_VALUE))
    assertEquals(0L, s.copy(positionMs = -200).visiblePositionMs(2_000))
  }

  @Test fun lateTrackResultsNeedSourceAndTrackIdentity() {
    val track = ready().track!!
    assertTrue(track.matches("ma:family", "track-1"))
    assertFalse(track.matches("ma:living", "track-1"))
    assertFalse(track.matches("ma:family", "track-2"))
  }

  @Test fun timedLyricsChooseLastReachedLineAndNeverUseFutureLine() {
    val lines = listOf(MusicLyricLine(5_000, "First"), MusicLyricLine(12_000, "Second"))
    assertEquals(-1, musicLyricIndex(lines, 4_999))
    assertEquals(0, musicLyricIndex(lines, 5_000))
    assertEquals(0, musicLyricIndex(lines, 11_999))
    assertEquals(1, musicLyricIndex(lines, 12_000))
    assertEquals(-1, musicLyricIndex(emptyList(), 20_000))
  }

  @Test fun pendingCommandRemainsPendingUntilConfirmedBySameSource() {
    val command = PendingMusicCommand(7, "ma:family", MusicCommand.CHANGE_ROOMS, 1_000)
    assertFalse(command.confirmedBy(7, "ma:living"))
    assertFalse(command.confirmedBy(8, "ma:family"))
    assertTrue(command.confirmedBy(7, "ma:family"))
  }
}
