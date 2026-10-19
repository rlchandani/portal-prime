/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MusicRemoteProtocolTest {
  private val leader = MusicRoom("ma:family", "Family", true, volume = 42,
      groupId = "ma:family", canGroupWith = setOf("ma:kitchen"), volumeControllable = true)
  private val follower = MusicRoom("ma:living", "Living", true,
      groupId = "ma:family", removableFromGroup = true)
  private val kitchen = MusicRoom("ma:kitchen", "Kitchen", true)
  private fun snapshot() = MusicPlayerSnapshot(connection = MusicConnection.READY,
      sourceId = "ma:living", controlPlayerId = "ma:family",
      track = MusicTrack("track-1", "ma:living", "Song", "Artist", "Album",
          artUrl = "https://private/art?token=secret", durationMs = 120_000),
      playback = PlaybackState.PAUSED, positionMs = 1_000, positionAtElapsedMs = 10_000,
      capabilities = MusicCapabilities(play = true, pause = true, next = true, previous = true,
          seek = true, queue = true, lyrics = true, rooms = true, volume = true),
      queue = listOf(MusicQueueItem("queue-1", "Next song"),
          MusicQueueItem("disabled", "Unavailable", available = false)),
      lyrics = listOf(MusicLyricLine(0, "First line")),
      rooms = listOf(leader, follower, kitchen))
  private fun body(command: String): JSONObject = JSONObject()
      .put("command", command).put("sourceRevision", 7L).put("sourceId", "ma:living")
      .apply {
        if (command == "toggle-room") put("wasInGroup", false)
        if (command in setOf("play", "pause", "next", "previous", "seek", "queue-item"))
          put("trackId", "track-1")
      }
  private fun decode(body: JSONObject, s: MusicPlayerSnapshot = snapshot()) =
      MusicRemoteProtocol.decode(body, s, 7)
  private fun accepted(action: MusicPlayerUiAction, body: JSONObject,
      s: MusicPlayerSnapshot = snapshot()) {
    assertEquals(MusicRemoteProtocol.Decoded.Accepted(action), decode(body, s))
  }
  private fun rejected(status: Int, code: String, body: JSONObject,
      s: MusicPlayerSnapshot = snapshot()) {
    assertEquals(MusicRemoteProtocol.Decoded.Rejected(status, code), decode(body, s))
  }

  @Test fun statePublishesReadSafeMetadataAndMonotonicPosition() {
    val s = snapshot().copy(playback = PlaybackState.PLAYING, problem = "cookie=secret")
    val json = MusicRemoteProtocol.state(s, 7, 12_000)
    assertEquals(1, json.getInt("schemaVersion"))
    assertEquals(7L, json.getLong("sourceRevision"))
    assertEquals("ma:living", json.getString("sourceId"))
    assertEquals("ready", json.getString("connection"))
    assertEquals("playing", json.getString("playback"))
    assertEquals(3_000L, json.getLong("positionMs"))
    assertEquals("ma:family", json.getString("controlPlayerId"))
    assertTrue(json.isNull("pending"))
    val track = json.getJSONObject("track")
    assertEquals("track-1", track.getString("id"))
    assertEquals("Song", track.getString("title"))
    assertEquals("Artist", track.getString("artist"))
    assertEquals("Album", track.getString("album"))
    assertEquals(120_000L, track.getLong("durationMs"))
    val text = json.toString()
    for (forbidden in listOf("secret", "cookie", "artUrl", "artBitmap", "sessionToken",
        "localSessionToken", "problem")) assertFalse(forbidden, text.contains(forbidden))
  }

  @Test fun stateBoundsCollectionsAndMetadataWithoutMutatingSnapshot() {
    val s = snapshot().copy(
        track = snapshot().track!!.copy(title = "x".repeat(5_000)),
        queue = List(501) { MusicQueueItem("q$it", "Title", artUrl = "private-art") },
        rooms = List(65) { MusicRoom("ma:$it", "Room", true) },
        lyrics = List(1001) { MusicLyricLine(it.toLong(), "Line") })
    val json = MusicRemoteProtocol.state(s, 7, 0)
    assertEquals(500, json.getJSONArray("queue").length())
    assertEquals("q499", json.getJSONArray("queue").getJSONObject(499).getString("id"))
    assertEquals(64, json.getJSONArray("rooms").length())
    assertEquals(1000, json.getJSONArray("lyrics").length())
    assertEquals(4096, json.getJSONObject("track").getString("title").length)
    assertFalse(json.toString().contains("private-art"))
    assertEquals(501, s.queue.size)
  }

  @Test fun stateDoesNotInventDurationOrPositionForLiveAndUnknownTracks() {
    for (track in listOf(snapshot().track!!.copy(isLive = true),
        snapshot().track!!.copy(durationMs = null), snapshot().track!!.copy(durationMs = 0))) {
      val json = MusicRemoteProtocol.state(snapshot().copy(track = track), 7, 20_000)
      assertTrue(json.isNull("positionMs"))
      assertTrue(json.getJSONObject("track").isNull("durationMs"))
      assertFalse(json.getJSONObject("capabilities").getBoolean("seek"))
    }
  }

  @Test fun stateCapabilitiesFollowSourceCapabilitiesRatherThanHardwareGuesses() {
    val json = MusicRemoteProtocol.state(snapshot().copy(capabilities = MusicCapabilities()), 7, 0)
    val caps = json.getJSONObject("capabilities")
    for (key in listOf("play", "pause", "next", "previous", "seek", "queue", "lyrics",
        "volume", "rooms")) assertFalse(key, caps.getBoolean(key))
    assertTrue(caps.getBoolean("selectPlayer"))
  }

  @Test fun stateDisablesControlsWhilePendingOfflineOrAudioIsOwnedElsewhere() {
    val pending = PendingMusicCommand(99, "ma:living", MusicCommand.PAUSE, 10)
    for (s in listOf(snapshot().copy(pending = pending),
        snapshot().copy(audioOwnedByOther = true),
        snapshot().copy(connection = MusicConnection.OFFLINE))) {
      val json = MusicRemoteProtocol.state(s, 7, 0)
      val caps = json.getJSONObject("capabilities")
      val keys = caps.keys()
      while (keys.hasNext()) assertFalse(caps.getBoolean(keys.next()))
      val rooms = json.getJSONArray("rooms")
      for (i in 0 until rooms.length()) {
        val room = rooms.getJSONObject(i)
        for (key in listOf("canSelect", "canJoin", "canRemove", "canSetVolume"))
          assertFalse(room.getBoolean(key))
      }
    }
    val json = MusicRemoteProtocol.state(snapshot().copy(pending = pending), 7, 0)
    assertEquals("pause", json.getJSONObject("pending").getString("command"))
    assertEquals(99L, json.getJSONObject("pending").getLong("requestId"))
  }

  @Test fun stateRoomControlsUseAuthoritativeLeaderAndRoomPolicy() {
    val rooms = MusicRemoteProtocol.state(snapshot(), 7, 0).getJSONArray("rooms")
    assertFalse(rooms.getJSONObject(0).getBoolean("canRemove"))
    assertTrue(rooms.getJSONObject(0).getBoolean("canSetVolume"))
    assertTrue(rooms.getJSONObject(1).getBoolean("canRemove"))
    assertFalse(rooms.getJSONObject(1).getBoolean("canJoin"))
    assertTrue(rooms.getJSONObject(2).getBoolean("canJoin"))
    assertFalse(rooms.getJSONObject(2).getBoolean("canSetVolume"))
  }

  @Test fun explicitPlayAndPauseOnlyToggleWhenPlaybackMustChange() {
    accepted(MusicPlayerUiAction.TogglePlayback, body("play"))
    accepted(MusicPlayerUiAction.TogglePlayback, body("pause"),
        snapshot().copy(playback = PlaybackState.PLAYING))
    for (state in listOf(PlaybackState.IDLE, PlaybackState.STOPPED))
      accepted(MusicPlayerUiAction.TogglePlayback, body("play"), snapshot().copy(playback = state))
  }

  @Test fun explicitMatchingPlaybackIsAnIdempotentNoOp() {
    assertEquals(MusicRemoteProtocol.Decoded.AlreadyApplied, decode(body("pause")))
    assertEquals(MusicRemoteProtocol.Decoded.AlreadyApplied,
        decode(body("play"), snapshot().copy(playback = PlaybackState.PLAYING)))
    assertEquals("already_applied", MusicRemoteProtocol.Decoded.AlreadyApplied.code)
  }

  @Test fun pauseNeverStartsIdleOrStoppedPlaybackOrAdvertisesThatControl() {
    for (playback in listOf(PlaybackState.IDLE, PlaybackState.STOPPED)) {
      val s = snapshot().copy(playback = playback)
      rejected(409, "unavailable", body("pause"), s)
      assertFalse(MusicRemoteProtocol.state(s, 7, 0).getJSONObject("capabilities").getBoolean("pause"))
    }
  }

  @Test fun invalidSourceIdentityDisablesAdvertisedRoomControls() {
    val source = "ma:" + "x".repeat(4096)
    val s = snapshot().copy(sourceId = source, track = snapshot().track!!.copy(sourceId = source))
    val json = MusicRemoteProtocol.state(s, 7, 0)
    assertFalse(json.getJSONObject("capabilities").getBoolean("selectPlayer"))
    val rooms = json.getJSONArray("rooms")
    for (i in 0 until rooms.length()) assertFalse(rooms.getJSONObject(i).getBoolean("canSelect"))
  }

  @Test fun commandsCannotSelectItemsBeyondPublishedCollectionBounds() {
    val s = snapshot().copy(queue = List(501) { MusicQueueItem("q$it", "Song") },
        rooms = List(65) { MusicRoom("ma:r$it", "Room", true, volumeControllable = true) })
    rejected(409, "unavailable", body("queue-item").put("queueItemId", "q500"), s)
    rejected(409, "unavailable", body("select-player").put("roomId", "ma:r64"), s)
    rejected(409, "unavailable", body("volume").put("roomId", "ma:r64").put("percent", 50), s)
  }

  @Test fun noOpDoesNotRequireUnavailableMutationCapability() {
    assertEquals(MusicRemoteProtocol.Decoded.AlreadyApplied,
        decode(body("pause"), snapshot().copy(capabilities = MusicCapabilities())))
  }

  @Test fun nextPreviousSeekAndQueueDecodeToExistingUiActions() {
    accepted(MusicPlayerUiAction.Next, body("next"))
    accepted(MusicPlayerUiAction.Previous, body("previous"))
    for (position in listOf(0L, 120_000L))
      accepted(MusicPlayerUiAction.Seek(position), body("seek").put("positionMs", position))
    accepted(MusicPlayerUiAction.SelectQueueItem("queue-1"),
        body("queue-item").put("queueItemId", "queue-1"))
  }

  @Test fun volumeGroupAndSelectionDecodeToExistingUiActions() {
    for (percent in listOf(0, 100))
      accepted(MusicPlayerUiAction.SetRoomVolume("ma:family", percent),
          body("volume").put("roomId", "ma:family").put("percent", percent))
    for (room in listOf("ma:living", "ma:kitchen"))
      accepted(MusicPlayerUiAction.ToggleRoom(room),
          body("toggle-room").put("roomId", room).put("leaderId", "ma:family")
              .put("wasInGroup", room == "ma:living"))
    accepted(MusicPlayerUiAction.SelectPlayer("ma:kitchen"),
        body("select-player").put("roomId", "ma:kitchen"))
  }

  @Test fun unknownCommandsUnknownKeysAndMissingKeysAreBadRequests() {
    for (request in listOf(body("shuffle"), body("toggle"), body("play").put("token", "x"),
        body("play").put("positionMs", 0), body("play").apply { remove("command") },
        body("play").apply { remove("sourceRevision") },
        body("play").apply { remove("sourceId") },
        body("play").apply { remove("trackId") },
        body("seek"), body("volume"), body("toggle-room").put("roomId", "ma:kitchen"))) {
      rejected(400, "bad_request", request)
    }
  }

  @Test fun unchangedLeaderCannotInvertTheObservedGroupOperation() {
    val join = body("toggle-room").put("roomId", "ma:kitchen").put("leaderId", "ma:family")
    val joined = snapshot().copy(rooms = listOf(leader, follower,
        kitchen.copy(groupId = "ma:family", removableFromGroup = true)))
    rejected(409, "stale", join, joined)
    val remove = body("toggle-room").put("roomId", "ma:living").put("leaderId", "ma:family")
        .put("wasInGroup", true)
    val left = snapshot().copy(rooms = listOf(leader, follower.copy(
        groupId = null, removableFromGroup = false), kitchen))
    rejected(409, "stale", remove, left)
  }

  @Test fun groupMembershipMustBeAnExplicitBoolean() {
    val request = body("toggle-room").put("roomId", "ma:kitchen").put("leaderId", "ma:family")
    for (invalid in listOf(JSONObject.NULL, "false", 0, JSONArray(), JSONObject()))
      rejected(400, "bad_request", JSONObject(request.toString()).put("wasInGroup", invalid))
    rejected(400, "bad_request", request.apply { remove("wasInGroup") })
  }

  @Test fun terminalReadbackExposesOnlyCorrelatedFixedOutcome() {
    val s = snapshot().copy(
        problem = "Private backend token or endpoint",
        lastCommand = CompletedMusicCommand(31, MusicCommand.PAUSE, MusicCommandOutcome.FAILED))
    val wire = MusicRemoteProtocol.state(s, 7, 1_000)
    val result = wire.getJSONObject("lastCommand")
    assertEquals(setOf("requestId", "command", "outcome"), result.keys().asSequence().toSet())
    assertEquals(31L, result.getLong("requestId"))
    assertEquals("pause", result.getString("command"))
    assertEquals("failed", result.getString("outcome"))
    assertFalse(wire.toString().contains("Private backend"))
    val pending = MusicRemoteProtocol.state(s.copy(lastCommand = null,
        pending = PendingMusicCommand(32, s.sourceId, MusicCommand.PAUSE, 1_000)), 7, 1_000)
    assertEquals(32L, pending.getJSONObject("pending").getLong("requestId"))
    assertTrue(pending.isNull("lastCommand"))
  }

  @Test fun identityFieldsRejectNullEmptyOversizedAndCoercedValues() {
    for (key in listOf("command", "sourceId", "trackId")) {
      for (value in listOf(JSONObject.NULL, "", " ", 1, true, JSONArray(),
          JSONObject(), "x".repeat(4097)))
        rejected(400, "bad_request", body("play").put(key, value))
    }
    for ((command, key) in listOf("queue-item" to "queueItemId", "select-player" to "roomId")) {
      for (value in listOf(JSONObject.NULL, "", 5, "x".repeat(4097)))
        rejected(400, "bad_request", body(command).put(key, value))
    }
  }

  @Test fun revisionRejectsNumericCoercionFractionsAndOverflow() {
    for (value in listOf("7", 7.0, true, JSONObject.NULL, -1L,
        java.math.BigInteger("9223372036854775808"))) {
      rejected(400, "bad_request", body("play").put("sourceRevision", value))
    }
  }

  @Test fun staleSourceRevisionAndTrackAreConflictsEvenForNoOps() {
    for (request in listOf(body("pause").put("sourceRevision", 6),
        body("pause").put("sourceId", "ma:other"),
        body("pause").put("trackId", "old-track"))) rejected(409, "stale", request)
  }

  @Test fun everyTrackBearingCommandRequiresCurrentTrackIdentity() {
    val requests = listOf(body("play"), body("pause"), body("next"), body("previous"),
        body("seek").put("positionMs", 0), body("queue-item").put("queueItemId", "queue-1"))
    for (request in requests) {
      rejected(409, "stale", request.put("trackId", "other"))
      rejected(409, "unavailable", request.put("trackId", "track-1"),
          snapshot().copy(track = null))
    }
  }

  @Test fun trackFromAnotherSourceCannotEnableCommandsOrMetadata() {
    val s = snapshot().copy(track = snapshot().track!!.copy(sourceId = "ma:other"))
    rejected(409, "unavailable", body("play"), s)
    val json = MusicRemoteProtocol.state(s, 7, 0)
    assertTrue(json.isNull("track"))
    assertFalse(json.getJSONObject("capabilities").getBoolean("play"))
  }

  @Test fun pendingAudioOwnershipAndDisconnectedStatesRejectEveryMutation() {
    val requests = listOf(body("pause"), body("play"), body("next"),
        body("seek").put("positionMs", 0), body("queue-item").put("queueItemId", "queue-1"),
        body("volume").put("roomId", "ma:family").put("percent", 10),
        body("toggle-room").put("roomId", "ma:kitchen").put("leaderId", "ma:family"),
        body("select-player").put("roomId", "ma:kitchen"))
    val states = listOf(snapshot().copy(audioOwnedByOther = true),
        snapshot().copy(pending = PendingMusicCommand(1, "ma:living", MusicCommand.PLAY, 0))) +
        MusicConnection.entries.filter { it != MusicConnection.READY }.map {
          snapshot().copy(connection = it)
        }
    for (s in states) for (request in requests) rejected(409, "unavailable", request, s)
  }

  @Test fun missingCapabilitiesRejectRequestedMutations() {
    val s = snapshot().copy(capabilities = MusicCapabilities())
    for (request in listOf(body("play"), body("next"), body("previous"),
        body("seek").put("positionMs", 0), body("queue-item").put("queueItemId", "queue-1"),
        body("volume").put("roomId", "ma:family").put("percent", 10),
        body("toggle-room").put("roomId", "ma:kitchen").put("leaderId", "ma:family")))
      rejected(409, "unavailable", request, s)
    accepted(MusicPlayerUiAction.SelectPlayer("ma:kitchen"),
        body("select-player").put("roomId", "ma:kitchen"), s)
  }

  @Test fun seekRejectsNonIntegerNegativeAndBeyondDurationPositions() {
    for (position in listOf("0", 1.5, 0.0, true, JSONObject.NULL, -1L,
        120_001L, Long.MAX_VALUE))
      rejected(400, "bad_request", body("seek").put("positionMs", position))
  }

  @Test fun seekRejectsLiveUnknownAndNonpositiveDuration() {
    for (track in listOf(snapshot().track!!.copy(isLive = true),
        snapshot().track!!.copy(durationMs = null), snapshot().track!!.copy(durationMs = 0),
        snapshot().track!!.copy(durationMs = -1)))
      rejected(409, "unavailable", body("seek").put("positionMs", 0),
          snapshot().copy(track = track))
  }

  @Test fun liveMediaCannotAdvertiseOrDecodeNextAndPrevious() {
    val s = snapshot().copy(track = snapshot().track!!.copy(isLive = true))
    for (command in listOf("next", "previous")) {
      rejected(409, "unavailable", body(command), s)
      assertFalse(MusicRemoteProtocol.state(s, 7, 0).getJSONObject("capabilities").getBoolean(command))
    }
  }

  @Test fun queueItemsMustExistAndBeAvailable() {
    for (id in listOf("missing", "disabled"))
      rejected(409, "unavailable", body("queue-item").put("queueItemId", id))
  }

  @Test fun volumeRejectsWrongTypesAndOutOfRangePercent() {
    for (value in listOf("50", 50.0, true, JSONObject.NULL, -1, 101, Long.MAX_VALUE))
      rejected(400, "bad_request", body("volume").put("roomId", "ma:family").put("percent", value))
  }

  @Test fun volumeRequiresAvailableControllableRoom() {
    for (room in listOf("missing", "ma:kitchen"))
      rejected(409, "unavailable", body("volume").put("roomId", room).put("percent", 20))
    rejected(409, "unavailable", body("volume").put("roomId", "ma:family").put("percent", 20),
        snapshot().copy(rooms = listOf(leader.copy(available = false))))
  }

  @Test fun groupRequiresExactObservedLeader() {
    rejected(409, "stale", body("toggle-room").put("roomId", "ma:kitchen").put("leaderId", "ma:other"))
    rejected(409, "stale", body("toggle-room").put("roomId", "ma:kitchen").put("leaderId", "ma:family"),
        snapshot().copy(controlPlayerId = "ma:new"))
    rejected(409, "unavailable", body("toggle-room").put("roomId", "ma:kitchen").put("leaderId", "ma:family"),
        snapshot().copy(controlPlayerId = null))
  }

  @Test fun groupCannotMutateLeaderStaticMembersOrIncompatibleRooms() {
    for (room in listOf("ma:family", "missing"))
      rejected(409, "unavailable", body("toggle-room").put("roomId", room).put("leaderId", "ma:family")
          .put("wasInGroup", room == "ma:family"))
    val s = snapshot().copy(rooms = listOf(leader.copy(canGroupWith = emptySet()),
        follower.copy(removableFromGroup = false), kitchen))
    for (room in listOf("ma:living", "ma:kitchen"))
      rejected(409, "unavailable", body("toggle-room").put("roomId", room).put("leaderId", "ma:family")
          .put("wasInGroup", room == "ma:living"), s)
  }

  @Test fun selectionRequiresAvailableCanonicalMaPlayerAndMaSource() {
    val request = body("select-player").put("roomId", "ma:kitchen")
    rejected(409, "unavailable", request, snapshot().copy(rooms = listOf(kitchen.copy(available = false))))
    rejected(409, "unavailable", request.put("roomId", "raw"),
        snapshot().copy(rooms = listOf(kitchen.copy(id = "raw"))))
    val local = snapshot().copy(sourceId = "local:music", track = null)
    rejected(409, "unavailable", body("select-player").put("sourceId", "local:music")
        .put("roomId", "ma:kitchen"), local)
  }
}
