/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MusicRoomPolicyTest {
  private val leader = MusicRoom("ma:family", "Family", true,
      groupId = "ma:family", canGroupWith = setOf("ma:kitchen"))
  private val follower = MusicRoom("ma:living", "Living", true,
      groupId = "ma:family", removableFromGroup = true)
  private val kitchen = MusicRoom("ma:kitchen", "Kitchen", true)
  private fun state() = MusicPlayerSnapshot(connection = MusicConnection.READY,
      sourceId = "ma:living", controlPlayerId = "ma:family",
      capabilities = MusicCapabilities(rooms = true), rooms = listOf(leader, follower, kitchen))

  @Test fun selectingAPlayerDoesNotRequireGroupingSupport() {
    val result = musicRoomActions(state().copy(capabilities = MusicCapabilities()), kitchen)
    assertTrue(result.canSelect)
    assertFalse(result.canJoin)
  }
  @Test fun followerSelectionUsesTheAuthoritativeLeaderForCompatibility() {
    assertTrue(musicRoomActions(state(), kitchen).canJoin)
    assertFalse(musicRoomActions(state(), kitchen.copy(id = "ma:other")).canJoin)
  }
  @Test fun removableFollowerIsInGroupAndCannotBeJoinedAgain() {
    val result = musicRoomActions(state(), follower)
    assertTrue(result.inCurrentGroup)
    assertTrue(result.canRemove)
    assertFalse(result.canJoin)
  }
  @Test fun staticMembersAndLeaderCannotBeRemoved() {
    assertFalse(musicRoomActions(state(), follower.copy(removableFromGroup = false)).canRemove)
    assertFalse(musicRoomActions(state(), leader.copy(removableFromGroup = true)).canRemove)
  }
  @Test fun offlineAndPendingStatesDisableEveryMutation() {
    val states = listOf(state().copy(connection = MusicConnection.OFFLINE),
        state().copy(pending = PendingMusicCommand(
            1, "ma:living", MusicCommand.PAUSE, 0)))
    for (s in states) {
      val result = musicRoomActions(s, kitchen)
      assertFalse(result.canSelect); assertFalse(result.canJoin); assertFalse(result.canRemove)
    }
    assertFalse(musicRoomActions(state(), kitchen.copy(available = false)).canSelect)
  }
  @Test fun interruptedRoomSelectionAndRemovalRemainAvailableButJoiningCannotStartAnotherRoom() {
    val s = state().copy(audioOwnedByOther = true)
    val other = musicRoomActions(s, kitchen)
    assertTrue(other.canSelect)
    assertFalse(other.canJoin)
    val member = musicRoomActions(s, follower)
    assertTrue(member.inCurrentGroup)
    assertTrue(member.canSelect)
    assertTrue(member.canRemove)
    assertFalse(member.canJoin)
    assertFalse(musicRoomActions(s, leader.copy(removableFromGroup = true)).canRemove)
    for (blocked in listOf(s.copy(connection = MusicConnection.OFFLINE),
        s.copy(pending = PendingMusicCommand(1, s.sourceId, MusicCommand.PAUSE, 0)))) {
      val policy = musicRoomActions(blocked, follower)
      assertFalse(policy.canSelect); assertFalse(policy.canRemove); assertFalse(policy.canJoin)
    }
    assertFalse(musicRoomActions(s, kitchen.copy(available = false)).canSelect)
    assertFalse(musicRoomActions(s.copy(capabilities = MusicCapabilities()), follower).canRemove)
  }

  @Test fun unknownLeaderNeverInventsJoinSupport() {
    val result = musicRoomActions(state().copy(controlPlayerId = "ma:missing"), kitchen)
    assertTrue(result.canSelect)
    assertFalse(result.canJoin)
  }
  @Test fun rawOrLocalIdsDoNotEnableMaRoomSelection() {
    assertFalse(musicRoomActions(state(), kitchen.copy(id = "kitchen")).canSelect)
    assertFalse(musicRoomActions(state().copy(sourceId = "local:music"), kitchen).canSelect)
  }
}
