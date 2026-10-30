/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MusicRoomPresentationTest {
  private val leader = MusicRoom("ma:family", "Family", true,
      groupId = "ma:family", canGroupWith = setOf("ma:kitchen"))
  private val follower = MusicRoom("ma:living", "Living", true,
      groupId = "ma:family", removableFromGroup = true)
  private val kitchen = MusicRoom("ma:kitchen", "Kitchen", true)
  private fun state() = MusicPlayerSnapshot(connection = MusicConnection.READY,
      sourceId = follower.id, controlPlayerId = leader.id,
      capabilities = MusicCapabilities(rooms = true), rooms = listOf(leader, follower, kitchen))

  @Test fun selectedFollowerIsIdentifiedSeparatelyFromTheControlLeader() {
    val selected = musicRoomPresentation(state(), follower)
    assertTrue(selected.selected)
    assertEquals("Selected", selected.selectionLabel)
    assertEquals("Selected player · In current group", selected.status)
    val control = musicRoomPresentation(state(), leader)
    assertFalse(control.selected)
    assertEquals("Use player", control.selectionLabel)
    assertFalse(musicRoomPresentation(state(), kitchen).selected)
  }

  @Test fun missingOrBlankSelectionDoesNotChooseAnArbitraryRoom() {
    assertFalse(musicRoomPresentation(state().copy(sourceId = "ma:missing"), leader).selected)
    assertFalse(musicRoomPresentation(state().copy(sourceId = ""), kitchen.copy(id = "")).selected)
  }

  @Test fun selectedOfflinePlayerRetainsSelectionWithoutClaimingAvailability() {
    val room = follower.copy(available = false)
    val result = musicRoomPresentation(state(), room)
    assertTrue(result.selected)
    assertEquals("Selected", result.selectionLabel)
    assertEquals("Selected player · Offline", result.status)
    assertNull(result.groupLabel)
    assertEquals("Grouping unavailable while this player is offline.", result.groupUnavailableReason)
  }

  @Test fun compatiblePlayerOffersJoinUsingTheConfirmedLeader() {
    val result = musicRoomPresentation(state(), kitchen)
    assertEquals("Join group", result.groupLabel)
    assertNull(result.groupUnavailableReason)
  }

  @Test fun unsupportedPairDoesNotAdvertiseAJoinAction() {
    val room = kitchen.copy(id = "ma:bedroom")
    val result = musicRoomPresentation(state(), room)
    assertNull(result.groupLabel)
    assertEquals("Cannot group with the current player.", result.groupUnavailableReason)
    assertTrue(musicRoomActions(state(), room).canSelect)
  }

  @Test fun removableMemberOffersRemoveAndStaticMemberExplainsItsLimit() {
    val result = musicRoomPresentation(state(), follower)
    assertEquals("Remove", result.groupLabel)
    assertNull(result.groupUnavailableReason)
    val fixed = musicRoomPresentation(state(), follower.copy(removableFromGroup = false))
    assertNull(fixed.groupLabel)
    assertEquals("This player cannot be removed from the group.", fixed.groupUnavailableReason)
    assertNull(musicRoomPresentation(state(), leader).groupLabel)
  }

  @Test fun offlinePendingAndConnectingHaveDistinctTemporaryReasons() {
    val cases = listOf(
        state() to kitchen.copy(available = false) to
            "Grouping unavailable while this player is offline.",
        state().copy(pending = PendingMusicCommand(1, follower.id, MusicCommand.PAUSE, 0)) to
            kitchen to "Grouping unavailable while a change is pending.",
        state().copy(connection = MusicConnection.CONNECTING) to kitchen to
            "Grouping unavailable until music is ready.",
        state().copy(connection = MusicConnection.OFFLINE) to kitchen to
            "Reconnect to music to manage groups.",
    )
    for ((input, reason) in cases) {
      val (snapshot, room) = input
      val result = musicRoomPresentation(snapshot, room)
      assertNull(result.groupLabel)
      assertEquals(reason, result.groupUnavailableReason)
    }
  }

  @Test fun absentGroupingCapabilityDoesNotClaimAPlayerIsIncompatible() {
    val snapshot = state().copy(capabilities = MusicCapabilities())
    val result = musicRoomPresentation(snapshot, kitchen)
    assertNull(result.groupLabel)
    assertEquals("Group controls unavailable for the current player.", result.groupUnavailableReason)
    assertTrue(musicRoomActions(snapshot, kitchen).canSelect)
  }

  @Test fun missingLeaderOrMismatchedTrackWaitsForInformation() {
    val missing = musicRoomPresentation(state().copy(controlPlayerId = "ma:missing"), kitchen)
    assertNull(missing.groupLabel)
    assertEquals("Waiting for group information.", missing.groupUnavailableReason)
    val mismatch = musicRoomPresentation(state().copy(
        track = MusicTrack("track", "ma:other", "Track")), kitchen)
    assertNull(mismatch.groupLabel)
    assertEquals("Waiting for matching player information.", mismatch.groupUnavailableReason)
  }

  @Test fun interruptionExplainsBlockedJoinButPreservesRemoval() {
    val snapshot = state().copy(audioOwnedByOther = true)
    val result = musicRoomPresentation(snapshot, kitchen)
    assertNull(result.groupLabel)
    assertEquals("Joining is unavailable during a playback interruption.", result.groupUnavailableReason)
    assertEquals("Remove", musicRoomPresentation(snapshot, follower).groupLabel)
  }

  @Test fun noUnreadyConnectionOffersGroupCommands() {
    for (connection in MusicConnection.entries.filter { it != MusicConnection.READY }) {
      val result = musicRoomPresentation(state().copy(connection = connection), kitchen)
      assertNull(result.groupLabel)
      assertNotNull(result.groupUnavailableReason)
    }
  }
}
