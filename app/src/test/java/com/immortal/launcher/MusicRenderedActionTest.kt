/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class MusicRenderedActionTest {
  private fun ready() = MusicPlayerSnapshot(
      connection = MusicConnection.READY, sourceId = "ma:family", presentationRevision = 7,
      track = MusicTrack("track-1", "ma:family", "Song", durationMs = 120_000),
      playback = PlaybackState.PLAYING, controlPlayerId = "ma:family",
      capabilities = MusicCapabilities(play = true, pause = true, seek = true, rooms = true),
      rooms = listOf(
          MusicRoom("ma:family", "Family", true, canGroupWith = setOf("ma:living")),
          MusicRoom("ma:living", "Living", true, canGroupWith = setOf("ma:family"))))
  private fun allowed(old: MusicPlayerSnapshot, now: MusicPlayerSnapshot,
      action: MusicPlayerUiAction, revision: Long = 7) =
      musicRenderedActionIsCurrent(old, now, revision, action)

  @Test fun unchangedControlsRemainValidWhileProgressAdvances() {
    val old = ready()
    val now = old.copy(positionMs = 8_000, positionAtElapsedMs = 10_000)
    listOf(MusicPlayerUiAction.TogglePlayback, MusicPlayerUiAction.Next,
        MusicPlayerUiAction.Previous, MusicPlayerUiAction.Seek(5_000),
        MusicPlayerUiAction.SelectQueueItem("queue-2")).forEach { assertTrue(allowed(old, now, it)) }
  }
  @Test fun aReplacementTrackRejectsEveryTrackBoundAction() {
    val old = ready()
    val now = old.copy(track = old.track!!.copy(id = "track-2"))
    listOf(MusicPlayerUiAction.TogglePlayback, MusicPlayerUiAction.Next,
        MusicPlayerUiAction.Previous, MusicPlayerUiAction.Seek(5_000),
        MusicPlayerUiAction.SelectQueueItem("queue-2")).forEach { assertFalse(allowed(old, now, it)) }
  }
  @Test fun displayedPauseCannotBecomePlay() {
    val old = ready()
    assertFalse(allowed(old, old.copy(playback = PlaybackState.PAUSED),
        MusicPlayerUiAction.TogglePlayback))
  }
  @Test fun displayedPlayCannotBecomePause() {
    val old = ready().copy(playback = PlaybackState.PAUSED)
    assertFalse(allowed(old, old.copy(playback = PlaybackState.PLAYING),
        MusicPlayerUiAction.TogglePlayback))
  }
  @Test fun sameSourceAndTrackOnAReplacementConnectionAreRejected() {
    val old = ready()
    assertFalse(allowed(old, old.copy(presentationRevision = 8), MusicPlayerUiAction.Next, 8))
    assertFalse(allowed(old, old, MusicPlayerUiAction.Next, 8))
  }
  @Test fun differentSourcesCannotReceiveOldCommands() {
    val old = ready()
    assertFalse(allowed(old, old.copy(sourceId = "ma:other"), MusicPlayerUiAction.SetRoomVolume("ma:living", 20)))
  }
  @Test fun aReplacementLocalSessionIsRejectedEvenForTheSamePackageAndTrack() {
    val first = mock(android.media.session.MediaSession.Token::class.java)
    val second = mock(android.media.session.MediaSession.Token::class.java)
    val old = ready().copy(sourceId = "local:player", localSessionToken = first)
    assertFalse(allowed(old, old.copy(localSessionToken = second), MusicPlayerUiAction.Seek(1_000)))
  }
  @Test fun changedLeaderRejectsTheDisplayedGroupAction() {
    val old = ready()
    assertFalse(allowed(old, old.copy(controlPlayerId = "ma:other"),
        MusicPlayerUiAction.ToggleRoom("ma:living")))
  }
  @Test fun displayedJoinCannotTurnIntoRemove() {
    val old = ready()
    val now = old.copy(rooms = old.rooms.map {
      if (it.id == "ma:living") it.copy(groupId = "ma:family", removableFromGroup = true) else it
    })
    assertFalse(allowed(old, now, MusicPlayerUiAction.ToggleRoom("ma:living")))
  }
  @Test fun roomVolumeDoesNotDependOnAnUnrelatedTrackChange() {
    val old = ready()
    assertTrue(allowed(old, old.copy(track = old.track!!.copy(id = "track-2")),
        MusicPlayerUiAction.SetRoomVolume("ma:living", 20)))
  }
}
