/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MusicQueuePresentationTest {
  private val currentItem = MusicQueueItem("current", "Track", artist = "Supplied artist")

  private fun snapshot(playback: PlaybackState = PlaybackState.PLAYING) =
      MusicPlayerSnapshot(
          connection = MusicConnection.READY,
          sourceId = "ma:family",
          track = MusicTrack("current", "ma:family", "Track", artist = "Track artist"),
          playback = playback,
      )

  @Test fun selectedRowRetainsQueueArtistSeparatelyFromPlaybackStatus() {
    for ((state, label) in listOf(
        PlaybackState.PLAYING to "Playing",
        PlaybackState.PAUSED to "Paused",
        PlaybackState.STOPPED to "Stopped",
        PlaybackState.IDLE to "Ready",
    )) {
      val row = musicQueueRowPresentation(snapshot(state), currentItem)
      assertTrue(row.current)
      assertEquals("Supplied artist", row.artist)
      assertEquals(label, row.playbackLabel)
    }
  }

  @Test fun onlyActualPlayingAndPausedStatesHavePlaybackIndicators() {
    for ((state, indicator) in listOf(
        PlaybackState.PLAYING to PlaybackState.PLAYING,
        PlaybackState.PAUSED to PlaybackState.PAUSED,
        PlaybackState.STOPPED to null,
        PlaybackState.IDLE to null,
    )) {
      assertEquals(state.name, indicator,
          musicQueueRowPresentation(snapshot(state), currentItem).playbackIndicator)
    }
  }

  @Test fun otherRowsKeepTheirArtistWithoutBorrowingSelectedTrackStatus() {
    val row = musicQueueRowPresentation(snapshot(),
        currentItem.copy(id = "next", artist = "Next artist"))
    assertFalse(row.current)
    assertEquals("Next artist", row.artist)
    assertNull(row.playbackLabel)
    assertNull(row.playbackIndicator)
  }

  @Test fun pendingCommandShowsPendingStatusWithoutClaimingItsPlaybackResult() {
    val pending = snapshot().copy(pending =
        PendingMusicCommand(1, "ma:family", MusicCommand.PAUSE, 100))
    val row = musicQueueRowPresentation(pending, currentItem)
    assertEquals("Supplied artist", row.artist)
    assertEquals("Change pending", row.playbackLabel)
    assertNull(row.playbackIndicator)
  }

  @Test fun restrictedLocalPlaybackDoesNotPresentAnActivePlaybackIndicator() {
    val local = snapshot().copy(sourceId = "local:player",
        track = snapshot().track!!.copy(sourceId = "local:player"), audioOwnedByOther = true)
    val row = musicQueueRowPresentation(local, currentItem)
    assertEquals("Supplied artist", row.artist)
    assertEquals("Playback restricted", row.playbackLabel)
    assertNull(row.playbackIndicator)
  }

  @Test fun interruptedMaStreamStillShowsItsAuthoritativePlaybackState() {
    val row = musicQueueRowPresentation(snapshot().copy(audioOwnedByOther = true), currentItem)
    assertEquals("Playing", row.playbackLabel)
    assertEquals(PlaybackState.PLAYING, row.playbackIndicator)
  }

  @Test fun lastKnownTrackDoesNotLookLikeCurrentPlayback() {
    val row = musicQueueRowPresentation(
        snapshot().copy(connection = MusicConnection.OFFLINE), currentItem)
    assertEquals("Last known track", row.playbackLabel)
    assertNull(row.playbackIndicator)
  }

  @Test fun unboundOrMismatchedTrackDoesNotMarkAQueueRowCurrent() {
    val ready = snapshot()
    for (unbound in listOf(
        ready.copy(track = null),
        ready.copy(track = ready.track!!.copy(sourceId = "ma:other")),
        ready.copy(sourceId = "", track = ready.track!!.copy(sourceId = "")),
        ready.copy(track = ready.track!!.copy(id = "")),
    )) {
      val row = musicQueueRowPresentation(unbound, currentItem)
      assertFalse(row.current)
      assertEquals("Supplied artist", row.artist)
      assertNull(row.playbackLabel)
      assertNull(row.playbackIndicator)
    }
    val blankId = musicQueueRowPresentation(ready, currentItem.copy(id = ""))
    assertFalse(blankId.current)
    assertNull(blankId.playbackLabel)
    assertNull(blankId.playbackIndicator)
  }

  @Test fun absentArtistDoesNotGetReplacedWithPlaybackText() {
    val row = musicQueueRowPresentation(snapshot(), currentItem.copy(artist = ""))
    assertEquals("", row.artist)
    assertEquals("Playing", row.playbackLabel)
  }
}
