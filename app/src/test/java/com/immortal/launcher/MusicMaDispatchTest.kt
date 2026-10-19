/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MusicMaDispatchTest {
  private val leader = MusicRoom("ma:family", "Family", true,
      groupId = "ma:family", canGroupWith = setOf("ma:kitchen"))
  private val follower = MusicRoom("ma:living", "Living", true,
      groupId = "ma:family", removableFromGroup = true, volume = 20, volumeControllable = true)
  private val kitchen = MusicRoom("ma:kitchen", "Kitchen", true)
  private fun state() = MusicPlayerSnapshot(connection = MusicConnection.READY,
      sourceId = "ma:living", controlPlayerId = "ma:family",
      track = MusicTrack("one", "ma:living", "Song", durationMs = 100_000),
      playback = PlaybackState.PLAYING, positionMs = 20_000,
      capabilities = MusicCapabilities(play = true, pause = true, next = true, previous = true,
          seek = true, queue = true, rooms = true, volume = true),
      rooms = listOf(leader, follower, kitchen), queue = listOf(MusicQueueItem("two", "Two")))

  @Test fun toggleUsesExplicitPauseAndObservedPlaybackConfirmation() {
    val dispatch = musicMaDispatch(state(), MusicPlayerUiAction.TogglePlayback)!!
    assertEquals(MusicCommand.PAUSE, dispatch.command)
    assertEquals(MaMusicClient.Action.Transport(MusicCommand.PAUSE), dispatch.action)
    assertFalse(dispatch.confirmation.matches(state()))
    assertTrue(dispatch.confirmation.matches(state().copy(playback = PlaybackState.PAUSED)))
  }
  @Test fun previousCanConfirmRestartingTheSameTrack() {
    val dispatch = musicMaDispatch(state(), MusicPlayerUiAction.Previous)!!
    assertFalse(dispatch.confirmation.matches(state()))
    assertTrue(dispatch.confirmation.matches(state().copy(positionMs = 0)))
    assertTrue(dispatch.confirmation.matches(state().copy(track = state().track!!.copy(id = "older"))))
    assertFalse(MusicConfirmation.TrackOrRestart("one", 0).matches(state().copy(positionMs = 0)))
  }
  @Test fun seekAndUnavailableQueueTargetsAreRejectedBeforeDispatch() {
    assertNull(musicMaDispatch(state(), MusicPlayerUiAction.Seek(-1)))
    assertNull(musicMaDispatch(state(), MusicPlayerUiAction.Seek(100_001)))
    assertNotNull(musicMaDispatch(state(), MusicPlayerUiAction.Seek(25_000)))
    assertNull(musicMaDispatch(state(), MusicPlayerUiAction.SelectQueueItem("missing")))
    assertNotNull(musicMaDispatch(state(), MusicPlayerUiAction.SelectQueueItem("two")))
    assertNull(musicMaDispatch(state().copy(queue = listOf(
        MusicQueueItem("two", "Two", available = false))), MusicPlayerUiAction.SelectQueueItem("two")))
  }
  @Test fun volumeRequiresAnAvailableSupportedPlayerAndRange() {
    assertNotNull(musicMaDispatch(state(), MusicPlayerUiAction.SetRoomVolume("ma:living", 35)))
    assertNull(musicMaDispatch(state(), MusicPlayerUiAction.SetRoomVolume("ma:living", 101)))
    assertNull(musicMaDispatch(state(), MusicPlayerUiAction.SetRoomVolume("ma:kitchen", 35)))
  }
  @Test fun followerJoinUsesLeaderAndConfirmsWholeGroup() {
    val dispatch = musicMaDispatch(state(), MusicPlayerUiAction.ToggleRoom("ma:kitchen"))!!
    assertEquals(MaMusicClient.Action.Group(add = setOf("ma:kitchen"), expectedLeaderId = "ma:family"), dispatch.action)
    assertFalse(dispatch.confirmation.matches(state()))
    assertTrue(dispatch.confirmation.matches(state().copy(rooms = listOf(leader, follower,
        kitchen.copy(groupId = "ma:family")))))
  }
  @Test fun lastFollowerRemovalConfirmsStandaloneLeader() {
    val dispatch = musicMaDispatch(state(), MusicPlayerUiAction.ToggleRoom("ma:living"))!!
    assertEquals(MaMusicClient.Action.Group(remove = setOf("ma:living"), expectedLeaderId = "ma:family"), dispatch.action)
    assertTrue(dispatch.confirmation.matches(state().copy(rooms = listOf(
        leader.copy(groupId = null), follower.copy(groupId = null), kitchen))))
    assertNull(musicMaDispatch(state().copy(rooms = listOf(leader,
        follower.copy(removableFromGroup = false))), MusicPlayerUiAction.ToggleRoom("ma:living")))
  }
  @Test fun offlineBusyPendingAndLocalStateCannotDispatchMaTransport() {
    for (s in listOf(state().copy(connection = MusicConnection.OFFLINE),
        state().copy(audioOwnedByOther = true), state().copy(sourceId = "local:music"),
        state().copy(pending = PendingMusicCommand(1, "ma:living", MusicCommand.PAUSE, 0)))) {
      assertNull(musicMaDispatch(s, MusicPlayerUiAction.TogglePlayback))
    }
  }
}
