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

class MaMusicProtocolTest {
  private fun player(
      id: String = "family",
      features: List<String> = listOf("pause", "volume_set", "set_members"),
      source: String? = "queue-shared",
      state: String = "playing",
  ) = JSONObject()
      .put("player_id", id).put("provider", "snapcast")
      .put("type", "player").put("name", id).put("available", true)
      .put("enabled", true).put("playback_state", state)
      .put("supported_features", JSONArray(features))
      .put("active_source", source ?: JSONObject.NULL)
      .put("synced_to", JSONObject.NULL).put("active_group", JSONObject.NULL)
      .put("group_members", JSONArray()).put("static_group_members", JSONArray())
      .put("can_group_with", JSONArray()).put("source_list", JSONArray())
      .put("volume_control", "native").put("volume_level", 30).put("volume_muted", false)
      .put("elapsed_time", 12.25).put("elapsed_time_last_updated", 1_700_000_000.0)
      .put("current_media", JSONObject.NULL)
      .put("future_field", JSONObject().put("anything", true))

  private fun item(id: String = "item-1", queueId: String = "queue-shared") = JSONObject()
      .put("queue_id", queueId).put("queue_item_id", id)
      .put("name", "Artist - Song").put("duration", 180)
      .put("available", true).put("index", 0)
      .put("media_item", JSONObject().put("name", "Song").put("media_type", "track")
          .put("uri", "library://track/23")
          .put("artists", JSONArray().put(JSONObject().put("name", "Artist")))
          .put("album", JSONObject().put("name", "Album")))
      .put("image", JSONObject().put("type", "thumb").put("provider", "url")
          .put("path", "https://example.org/cover.jpg").put("remotely_accessible", true))

  private fun queue(id: String = "queue-shared") = JSONObject()
      .put("queue_id", id).put("display_name", "Shared queue")
      .put("active", true).put("available", true).put("items", 2)
      .put("state", "playing").put("elapsed_time", 12.25)
      .put("elapsed_time_last_updated", 1_700_000_000.0)
      .put("current_index", 0).put("shuffle_enabled", false).put("repeat_mode", "off")
      .put("current_item", item(queueId = id)).put("next_item", item("item-2", id))

  private fun reply(value: Any?, id: String = "read"): String =
      JSONObject().put("message_id", id).put("result", value ?: JSONObject.NULL)
          .put("partial", false).toString()

  private fun selection(
      players: List<JSONObject> = listOf(player()),
      q: JSONObject? = queue(),
      entries: List<JSONObject> = listOf(item(), item("item-2")),
  ) = MaMusicProtocol.select(
      sourceId = "ma:family",
      players = MaMusicProtocol.parsePlayers(reply(JSONArray(players))),
      queue = MaMusicProtocol.parseQueue(reply(q)),
      items = if (q == null) null else MaMusicProtocol.parseQueueItems(
          reply(JSONArray(entries)), q.getString("queue_id")),
      observedAtElapsedMs = 7_000,
  )

  private fun <T> success(result: MaMusicProtocol.Reply<T>): T {
    assertTrue("Expected success, got $result", result is MaMusicProtocol.Reply.Success)
    return (result as MaMusicProtocol.Reply.Success).value
  }

  private fun failed(result: MaMusicProtocol.Reply<*>): MaMusicProtocol.Reply.Failure {
    assertTrue("Expected failure, got $result", result is MaMusicProtocol.Reply.Failure)
    return result as MaMusicProtocol.Reply.Failure
  }

  @Test fun mapsReleasedPlayerAndQueueFieldsWithoutEquatingPlayerAndQueueIds() {
    val selected = selection()
    val s = selected.snapshot
    assertEquals(MusicConnection.READY, s.connection)
    assertEquals("ma:family", s.sourceId)
    assertEquals("queue-shared", selected.queueId)
    assertEquals("ma:family", s.controlPlayerId)
    assertEquals("item-1", s.track?.id)
    assertEquals("ma:family", s.track?.sourceId)
    assertEquals("Song", s.track?.title)
    assertEquals("Artist", s.track?.artist)
    assertEquals("Album", s.track?.album)
    assertEquals(180_000L, s.track?.durationMs)
    assertEquals(12_250L, s.positionMs)
    assertEquals(7_000L, s.positionAtElapsedMs)
    assertEquals(listOf("item-1", "item-2"), s.queue.map { it.id })
    assertEquals("ma:family", s.rooms.single().id)
    assertEquals(30, s.rooms.single().volume)
    assertFalse(s.capabilities.lyrics)
    assertFalse(s.capabilities.favorite)
    assertTrue(s.lyrics.isEmpty())
  }

  @Test fun queueControlsAreServerQueueActionsEvenWithoutNativeSeekAndSkipFeatures() {
    val selected = selection(players = listOf(player(features = emptyList())))
    assertTrue(selected.snapshot.can(MusicCommand.SEEK))
    assertTrue(selected.snapshot.can(MusicCommand.NEXT))
    val seek = MaMusicProtocol.seekCommand("12", selected, 34_999)!!
    assertEquals("player_queues/seek", seek.getString("command"))
    assertEquals("queue-shared", seek.getJSONObject("args").getString("queue_id"))
    assertEquals(34, seek.getJSONObject("args").getInt("position"))
    assertFalse(seek.getJSONObject("args").has("player_id"))
  }

  @Test fun nativeSourceNeedsReportedActionFlagsAndFeatures() {
    val p = player(source = "line-in")
        .put("supported_features", JSONArray(listOf("pause", "seek", "next_previous")))
        .put("source_list", JSONArray().put(JSONObject()
            .put("id", "line-in").put("name", "Line in")
            .put("can_play_pause", true).put("can_seek", false)
            .put("can_next_previous", false)))
        .put("current_media", JSONObject().put("uri", "native:1").put("title", "Native")
            .put("media_type", "track").put("duration", 150))
    val selected = selection(players = listOf(p), q = null)
    assertTrue(selected.snapshot.can(MusicCommand.PAUSE))
    assertFalse(selected.snapshot.can(MusicCommand.SEEK))
    assertFalse(selected.snapshot.can(MusicCommand.NEXT))
    assertFalse(selected.snapshot.can(MusicCommand.OPEN_QUEUE))
    assertEquals("players/cmd/pause",
        MaMusicProtocol.transportCommand("3", selected, MusicCommand.PAUSE)!!.getString("command"))
    assertNull(MaMusicProtocol.seekCommand("4", selected, 20_000))
    assertFalse(selection(players = listOf(p.put("source_list", JSONArray())), q = null)
        .snapshot.capabilities.play)
  }

  @Test fun resolvesSyncLeaderButKeepsSelectedCanonicalSourceAndRoomIds() {
    val child = player().put("synced_to", "leader").put("active_source", "wrong")
    val leader = player("leader").put("group_members", JSONArray(listOf("leader", "family")))
    val selected = selection(listOf(child, leader))
    assertEquals(MusicConnection.READY, selected.snapshot.connection)
    assertEquals("ma:family", selected.snapshot.sourceId)
    assertEquals("ma:family", selected.snapshot.track?.sourceId)
    assertEquals("ma:leader", selected.snapshot.rooms.first { it.id == "ma:family" }.groupId)
    assertEquals("queue-shared",
        MaMusicProtocol.transportCommand("8", selected, MusicCommand.PLAY)!!
            .getJSONObject("args").getString("queue_id"))
  }

  @Test fun cannotBecomeReadyFromHelloAuthAckMissingPlayerOrUnresolvedLeader() {
    for (json in listOf("{}", """{"server_version":"2.10.7","schema_version":65}""",
        reply(JSONObject().put("authenticated", true)), reply(JSONObject.NULL))) {
      val selected = MaMusicProtocol.select("ma:family", MaMusicProtocol.parsePlayers(json))
      assertNotEquals(MusicConnection.READY, selected.snapshot.connection)
      assertFalse(selected.snapshot.can(MusicCommand.PLAY))
    }
    assertEquals(MusicConnection.OFFLINE, MaMusicProtocol.select("ma:family",
        MaMusicProtocol.parsePlayers(reply(JSONArray()))).snapshot.connection)
    val missing = selection(listOf(player().put("synced_to", "missing")))
    assertEquals(MusicConnection.INCOMPATIBLE, missing.snapshot.connection)
    val loop = selection(listOf(player().put("synced_to", "leader"),
        player("leader").put("synced_to", "family")))
    assertEquals(MusicConnection.INCOMPATIBLE, loop.snapshot.connection)
  }

  @Test fun malformedKnownFieldsNeverCoerceToSuccess() {
    val fields = mapOf<String, Any>(
        "available" to "true", "enabled" to 1, "playback_state" to 7,
        "name" to true, "player_id" to 123, "supported_features" to "pause",
        "volume_level" to "30", "volume_muted" to "false", "elapsed_time" to "12",
        "active_source" to 12, "current_media" to JSONArray(), "can_group_with" to true,
        "group_members" to "leader", "source_list" to false,
    )
    for ((key, value) in fields) {
      failed(MaMusicProtocol.parsePlayers(reply(JSONArray().put(player().put(key, value)))))
    }
    failed(MaMusicProtocol.parsePlayers(reply(JSONArray().put(
        player().put("supported_features", JSONArray().put(1))))))
    failed(MaMusicProtocol.parsePlayers(reply(JSONArray().put(player()
        .put("source_list", JSONArray().put(JSONObject().put("id", "queue-shared")
            .put("name", "MA").put("can_play_pause", "true")))))))
    for ((key, value) in mapOf<String, Any>("items" to "2", "active" to "true",
        "state" to 1, "current_index" to 0.5, "elapsed_time" to -1,
        "current_item" to "item-1", "repeat_mode" to false)) {
      failed(MaMusicProtocol.parseQueue(reply(queue().put(key, value))))
    }
    for ((key, value) in mapOf<String, Any>("queue_item_id" to 9, "name" to 5,
        "duration" to "180", "available" to "true", "media_item" to "track",
        "image" to "https://example.org/art")) {
      failed(MaMusicProtocol.parseQueueItems(
          reply(JSONArray().put(item().put(key, value))), "queue-shared"))
    }
  }

  @Test fun explicitErrorAndAuthResultTakePrecedenceOverAnyApparentResult() {
    for (code in listOf(20, 21, 23)) {
      val json = JSONObject().put("message_id", "read").put("error_code", code)
          .put("details", "Sensitive server detail").put("result", JSONArray().put(player()))
      val parsed = MaMusicProtocol.parsePlayers(json.toString())
      assertEquals(MusicConnection.AUTH_REQUIRED, failed(parsed).connection)
      assertFalse(failed(parsed).problem.contains("Sensitive"))
      assertEquals(MusicConnection.AUTH_REQUIRED,
          MaMusicProtocol.select("ma:family", parsed).snapshot.connection)
    }
    failed(MaMusicProtocol.parsePlayers(
        """{"message_id":"read","error_code":"20","result":[]}"""))
    assertEquals(MusicConnection.INCOMPATIBLE, failed(MaMusicProtocol.parsePlayers(
        """{"message_id":"read","error_code":22}""")).connection)
    success(MaMusicProtocol.parseAuthReply(reply(JSONObject().put("authenticated", true))))
    assertEquals(MusicConnection.AUTH_REQUIRED, failed(MaMusicProtocol.parseAuthReply(
        reply(JSONObject().put("authenticated", false)))).connection)
    failed(MaMusicProtocol.parseAuthReply(reply(JSONObject().put("authenticated", "true"))))
    failed(MaMusicProtocol.parseAuthReply(reply(JSONObject().put("success", true))))
    success(MaMusicProtocol.parseCommandReply(reply(null)))
    failed(MaMusicProtocol.parseCommandReply(reply(false)))
    failed(MaMusicProtocol.parseCommandReply(reply(JSONObject().put("success", false))))
  }

  @Test fun responseCorrelationPartialRepliesAndWrongQueueNeverPublishValidData() {
    failed(MaMusicProtocol.parsePlayers(reply(JSONArray().put(player())), "other"))
    failed(MaMusicProtocol.parsePlayers(
        JSONObject(reply(JSONArray().put(player()))).put("partial", true).toString()))
    failed(MaMusicProtocol.parsePlayers(
        JSONObject(reply(JSONArray().put(player()))).put("partial", "false").toString()))
    failed(MaMusicProtocol.parseQueueItems(reply(JSONArray().put(item(queueId = "other"))),
        "queue-shared"))
    assertEquals(MusicConnection.INCOMPATIBLE, selection(q = queue("other")).snapshot.connection)
    failed(MaMusicProtocol.parseQueue(reply(queue().put("current_item", item(queueId = "other")))))
    assertEquals(MusicConnection.INCOMPATIBLE, MaMusicProtocol.select("ma:family",
        MaMusicProtocol.parsePlayers(reply(JSONArray().put(player()))),
        MaMusicProtocol.parseQueue(reply(queue())),
        MaMusicProtocol.parseQueueItems(reply(JSONArray().put(item("foreign", "other"))),
            "other")).snapshot.connection)
  }

  @Test fun deduplicatesAndBoundsRoomAndQueueIdsWithoutChangingRawQueueIds() {
    val players = JSONArray().put(player()).put(player())
    repeat(100) { players.put(player("room-$it")) }
    val parsed = success(MaMusicProtocol.parsePlayers(reply(players)))
    assertEquals(64, parsed.size)
    assertEquals(64, parsed.map { it.playerId }.toSet().size)
    val entries = JSONArray().put(item()).put(item())
    repeat(600) { entries.put(item("raw:$it")) }
    val items = success(MaMusicProtocol.parseQueueItems(reply(entries), "queue-shared"))
    assertEquals(500, items.items.size)
    assertEquals(500, items.items.map { it.item.id }.toSet().size)
    assertEquals("raw:498", items.items.last().item.id)
    assertEquals("queue-shared", items.queueId)
    failed(MaMusicProtocol.parsePlayers(reply(JSONArray().put(player("x".repeat(257))))))
    failed(MaMusicProtocol.parseQueueItems(
        reply(JSONArray().put(item("x".repeat(257)))), "queue-shared"))
    failed(MaMusicProtocol.parsePlayers(reply(JSONArray().put(player("")))))
  }

  @Test fun rejectsOversizedOrDeepMalformedPayloadAndKeepsUnknownFieldsTolerant() {
    failed(MaMusicProtocol.parsePlayers("[".repeat(100) + "]".repeat(100)))
    failed(MaMusicProtocol.parsePlayers(
        """{"message_id":"read","result":[],"unknown":""" +
            "[".repeat(100) + "0" + "]".repeat(100) + "}"))
    failed(MaMusicProtocol.parsePlayers(reply(JSONArray()) + " trailing"))
    failed(MaMusicProtocol.parsePlayers("{"))
    failed(MaMusicProtocol.parsePlayers(" ".repeat(2_100_000)))
    assertEquals(1, success(MaMusicProtocol.parsePlayers(
        reply(JSONArray().put(player().put("future_flag", "not interpreted"))))).size)
  }

  @Test fun emptyUnavailableAndRadioQueuesHaveNoInventedSeekOrSkipActions() {
    val empty = queue().put("items", 0).put("current_index", JSONObject.NULL)
        .put("current_item", JSONObject.NULL).put("next_item", JSONObject.NULL)
        .put("state", "idle").put("active", false)
    val selected = selection(q = empty, entries = emptyList())
    assertNull(selected.snapshot.track)
    assertFalse(selected.snapshot.can(MusicCommand.PLAY))
    assertFalse(selected.snapshot.can(MusicCommand.SEEK))
    val radio = item().put("duration", JSONObject.NULL)
    radio.getJSONObject("media_item").put("media_type", "radio")
    val live = selection(q = queue().put("current_item", radio))
    assertTrue(live.snapshot.track!!.isLive)
    assertNull(live.snapshot.track!!.durationMs)
    assertNull(MaMusicProtocol.seekCommand("1", live, 10_000))
    assertNull(MaMusicProtocol.transportCommand("2", live, MusicCommand.NEXT))
    assertEquals(MusicConnection.OFFLINE,
        selection(listOf(player().put("available", false))).snapshot.connection)
    assertEquals(MusicConnection.OFFLINE,
        selection(q = queue().put("available", false)).snapshot.connection)
  }

  @Test fun malformedDurationPositionAndNamesAreRejectedOrSafelyBounded() {
    for (duration in listOf(-1, 1e30, "180")) {
      failed(MaMusicProtocol.parseQueue(reply(
          queue().put("current_item", item().put("duration", duration)))))
    }
    failed(MaMusicProtocol.parseQueue(reply(queue().put("elapsed_time", 1e30))))
    val q = queue().put("elapsed_time", 999.0)
    assertEquals(180_000L, selection(q = q).snapshot.positionMs)
    val huge = item()
    huge.getJSONObject("media_item").put("name", "x".repeat(5000))
    val bounded = selection(q = queue().put("current_item", huge)).snapshot.track!!
    assertTrue(bounded.title.length <= 1024)
  }

  @Test fun commandPayloadsUseTheExactReleasedQueueSchemaAndRefuseBadArguments() {
    val selected = selection(q = queue().put("current_index", 1).put("elapsed_time", 8)
        .put("items", 3))
    for ((action, name) in mapOf(MusicCommand.PLAY to "play", MusicCommand.PAUSE to "pause",
        MusicCommand.NEXT to "next", MusicCommand.PREVIOUS to "previous")) {
      val command = MaMusicProtocol.transportCommand("19", selected, action)!!
      assertEquals("19", command.getString("message_id"))
      assertEquals("player_queues/$name", command.getString("command"))
      assertEquals(1, command.getJSONObject("args").length())
      assertEquals("queue-shared", command.getJSONObject("args").getString("queue_id"))
    }
    val playItem = MaMusicProtocol.queueItemCommand("20", selected, "item-2")!!
    assertEquals("player_queues/play_index", playItem.getString("command"))
    assertEquals("item-2", playItem.getJSONObject("args").getString("index"))
    assertEquals("queue-shared", playItem.getJSONObject("args").getString("queue_id"))
    assertNull(MaMusicProtocol.queueItemCommand("21", selected, "absent"))
    assertNull(MaMusicProtocol.seekCommand("22", selected, -1))
    assertNull(MaMusicProtocol.seekCommand("23", selected, 180_001))
    assertNull(MaMusicProtocol.transportCommand("24", selected, MusicCommand.FAVORITE))
    assertNull(MaMusicProtocol.transportCommand("", selected, MusicCommand.PLAY))
    val unavailable = selection(listOf(player().put("available", false)))
    assertNull(MaMusicProtocol.transportCommand("25", unavailable, MusicCommand.PLAY))
  }

  @Test fun unavailableQueueEntriesCannotBeSelectedEvenWhenListed() {
    val selected = selection(entries = listOf(item(), item("item-2").put("available", false)))
    assertEquals(2, selected.snapshot.queue.size)
    assertNull(MaMusicProtocol.queueItemCommand("30", selected, "item-2"))
  }

  @Test fun roomVolumeUsesItsOwnPlayerIdAndSupportedControlOnly() {
    val selected = selection(listOf(player(),
        player("living").put("volume_level", 40),
        player("fixed", features = emptyList()).put("volume_control", "none"),
        player("offline").put("available", false)))
    val volume = MaMusicProtocol.volumeCommand("31", selected, "ma:living", 57)!!
    assertEquals("players/cmd/volume_set", volume.getString("command"))
    assertEquals("living", volume.getJSONObject("args").getString("player_id"))
    assertEquals(57, volume.getJSONObject("args").getInt("volume_level"))
    assertFalse(volume.getJSONObject("args").has("queue_id"))
    for ((room, level) in listOf("ma:fixed" to 10, "ma:offline" to 10, "living" to 10,
        "ma:missing" to 10, "ma:living" to -1, "ma:living" to 101)) {
      assertNull(MaMusicProtocol.volumeCommand("32", selected, room, level))
    }
  }

  @Test fun groupingRequiresTargetFeatureCompatibilityAndRemovableMembership() {
    val leader = player().put("can_group_with", JSONArray(listOf("living", "bedroom")))
        .put("group_members", JSONArray(listOf("family", "bedroom", "fixed")))
        .put("static_group_members", JSONArray(listOf("fixed")))
    val selected = selection(listOf(leader, player("living"), player("bedroom"),
        player("fixed"), player("incompatible")))
    assertTrue(selected.snapshot.can(MusicCommand.CHANGE_ROOMS))
    assertTrue(selected.snapshot.rooms.first { it.id == "ma:bedroom" }.removableFromGroup)
    assertFalse(selected.snapshot.rooms.first { it.id == "ma:fixed" }.removableFromGroup)
    assertFalse(selected.snapshot.rooms.first { it.id == "ma:family" }.removableFromGroup)
    assertEquals(setOf("ma:living", "ma:bedroom"),
        selected.snapshot.rooms.first { it.id == "ma:family" }.canGroupWith)
    val group = MaMusicProtocol.groupCommand("40", selected,
        add = setOf("ma:living"), remove = setOf("ma:bedroom"))!!
    assertEquals("players/cmd/set_members", group.getString("command"))
    assertEquals("family", group.getJSONObject("args").getString("target_player"))
    assertEquals(listOf("living"), group.getJSONObject("args")
        .getJSONArray("player_ids_to_add").let { a -> (0 until a.length()).map(a::getString) })
    assertEquals(listOf("bedroom"), group.getJSONObject("args")
        .getJSONArray("player_ids_to_remove").let { a -> (0 until a.length()).map(a::getString) })
    assertFalse(group.getJSONObject("args").has("player_id"))
    assertNull(MaMusicProtocol.groupCommand("41", selected, add = setOf("ma:incompatible")))
    assertNull(MaMusicProtocol.groupCommand("42", selected, remove = setOf("ma:fixed")))
    assertNull(MaMusicProtocol.groupCommand("43", selected, remove = setOf("ma:family")))
    assertNull(MaMusicProtocol.groupCommand("44", selected,
        add = setOf("ma:living"), remove = setOf("ma:living")))
    assertNull(MaMusicProtocol.groupCommand("45", selected))
    val unsupported = selection(listOf(leader.put("supported_features", JSONArray()), player("living")))
    assertNull(MaMusicProtocol.groupCommand("46", unsupported, add = setOf("ma:living")))
  }

  @Test fun queueReadCommandsAreBoundedAndNeverPrefixRawQueueId() {
    val active = MaMusicProtocol.activeQueueCommand("50", "ma:family")!!
    assertEquals("player_queues/get_active_queue", active.getString("command"))
    assertEquals("family", active.getJSONObject("args").getString("player_id"))
    val get = MaMusicProtocol.queueStateCommand("51", "raw:queue")!!
    assertEquals("raw:queue", get.getJSONObject("args").getString("queue_id"))
    val page = MaMusicProtocol.queueItemsCommand("52", "raw:queue")!!
    assertEquals(500, page.getJSONObject("args").getInt("limit"))
    assertEquals(0, page.getJSONObject("args").getInt("offset"))
    assertNull(MaMusicProtocol.queueItemsCommand("53", "raw:queue", limit = 501))
    assertNull(MaMusicProtocol.queueItemsCommand("54", "raw:queue", offset = -1))
    assertNull(MaMusicProtocol.activeQueueCommand("55", "family"))
    assertEquals("players/all", MaMusicProtocol.playersCommand("56")!!.getString("command"))
  }

  @Test fun audioSourceActionsUsePlayerForwardingInsteadOfSkippingItsWrapperQueue() {
    val p = player(features = listOf("pause", "seek", "next_previous"))
        .put("source_list", JSONArray().put(JSONObject().put("id", "queue-shared")
            .put("name", "External session").put("can_play_pause", true)
            .put("can_seek", true).put("can_next_previous", true)))
    val external = item()
    external.getJSONObject("media_item").put("media_type", "audio_source")
    val selected = selection(listOf(p), queue().put("current_item", external))
    val next = MaMusicProtocol.transportCommand("60", selected, MusicCommand.NEXT)!!
    assertEquals("players/cmd/next", next.getString("command"))
    assertEquals("family", next.getJSONObject("args").getString("player_id"))
    val seek = MaMusicProtocol.seekCommand("61", selected, 10_000)!!
    assertEquals("players/cmd/seek", seek.getString("command"))
    assertTrue(selected.snapshot.can(MusicCommand.OPEN_QUEUE))
  }

  @Test fun canonicalNullsAndWrongTypedTimingFieldsNeverUseAnAliasAsSuccess() {
    for (key in listOf("name", "playback_state")) {
      val p = player().put("display_name", "Family").put("state", "playing")
          .put(key, JSONObject.NULL)
      failed(MaMusicProtocol.parsePlayers(reply(JSONArray().put(p))))
    }
    failed(MaMusicProtocol.parsePlayers(reply(JSONArray().put(
        player().put("elapsed_time_last_updated", "1700000000")))))
    failed(MaMusicProtocol.parseQueue(reply(queue().put("elapsed_time_last_updated", "1700000000"))))
    failed(MaMusicProtocol.parseQueue(reply(queue().put("playback_speed", "1.0"))))
    failed(MaMusicProtocol.parsePlayers(reply(JSONArray().put(
        player().put("hide_in_ui", true).put("private", "false")))))
    val alias = player().put("display_name", "Family").put("state", "playing")
    alias.remove("name")
    alias.remove("playback_state")
    assertEquals("Family", success(MaMusicProtocol.parsePlayers(reply(JSONArray().put(alias)))).single().name)
  }

  @Test fun oversizedArtworkAndUriIdentifiersAreNeverSilentlyTruncatedIntoOtherResources() {
    val entry = item()
    entry.getJSONObject("image").put("path", "https://example.org/" + "x".repeat(5000))
    val parsed = success(MaMusicProtocol.parseQueueItems(reply(JSONArray().put(entry)), "queue-shared"))
    assertEquals("", parsed.items.single().item.artUrl)
    failed(MaMusicProtocol.parsePlayers(reply(JSONArray().put(player()
        .put("current_media", JSONObject().put("uri", "x".repeat(5000)).put("title", "Title"))))))
  }

  @Test fun readonlyPayloadsAreDetachedFromJsonObjectsAfterParsing() {
    val p = player().put("can_group_with", JSONArray().put("living"))
    val raw = reply(JSONArray().put(p))
    val parsed = MaMusicProtocol.parsePlayers(raw)
    p.put("name", "Changed").put("supported_features", JSONArray())
    val selected = MaMusicProtocol.select("ma:family", parsed)
    assertEquals("family", selected.snapshot.rooms.single().name)
    assertEquals(MusicConnection.READY, selected.snapshot.connection)
    assertFalse(selected.snapshot.can(MusicCommand.PLAY))
  }
  @Test fun populatedInactiveIdleQueueRetainsIdentityAndCanSelectItsItem() {
    val selected = selection(players = listOf(player(state = "idle")),
        q = queue().put("active", false).put("state", "idle")
            .put("current_index", JSONObject.NULL).put("current_item", JSONObject.NULL)
            .put("next_item", JSONObject.NULL))
    assertNull(selected.snapshot.track)
    assertEquals("queue-shared", selected.snapshot.queueId)
    assertTrue(selected.snapshot.can(MusicCommand.SELECT_QUEUE_ITEM))
    val request = MaMusicProtocol.queueItemCommand("choose", selected, "item-2")!!
    assertEquals("queue-shared", request.getJSONObject("args").getString("queue_id"))
    assertEquals("item-2", request.getJSONObject("args").getString("index"))
  }

  @Test fun queuePauseNeedsActualPauseSupportInsteadOfAdvertisingAStopFallback() {
    val selected = selection(listOf(player(features = emptyList())))
    assertTrue(selected.snapshot.can(MusicCommand.PLAY))
    assertFalse(selected.snapshot.can(MusicCommand.PAUSE))
    assertNull(MaMusicProtocol.transportCommand("70", selected, MusicCommand.PAUSE))
  }

  @Test fun positionsUseServerUnixTimestampAndAnchorAtTheSuppliedMonotonicObservation() {
    val players = MaMusicProtocol.parsePlayers(reply(JSONArray().put(player())))
    val q = queue().put("elapsed_time_last_updated", 1_700_000_000.5).put("playback_speed", 1.25)
    val selected = MaMusicProtocol.select("ma:family", players,
        MaMusicProtocol.parseQueue(reply(q)), observedAtElapsedMs = 7_000,
        observedAtUnixMs = 1_700_000_002_500)
    assertEquals(14_750L, selected.snapshot.positionMs)
    assertEquals(7_000L, selected.snapshot.positionAtElapsedMs)
    val paused = MaMusicProtocol.select("ma:family", players,
        MaMusicProtocol.parseQueue(reply(q.put("state", "paused"))),
        observedAtUnixMs = 1_700_000_100_000)
    assertEquals(12_250L, paused.snapshot.positionMs)
    val future = MaMusicProtocol.select("ma:family", players,
        MaMusicProtocol.parseQueue(reply(q.put("state", "playing"))),
        observedAtUnixMs = 1_699_999_999_000)
    assertEquals(12_250L, future.snapshot.positionMs)
  }

  @Test fun nativePositionsAlsoUseServerTimeWithoutReadingAClockInTheAdapter() {
    val p = player(source = "native").put("elapsed_time_last_updated", 1_700_000_000.0)
        .put("current_media", JSONObject().put("uri", "native:1").put("title", "Native")
            .put("media_type", "track").put("duration", 150))
    val selected = MaMusicProtocol.select("ma:family",
        MaMusicProtocol.parsePlayers(reply(JSONArray().put(p))),
        observedAtElapsedMs = 7_000, observedAtUnixMs = 1_700_000_005_000)
    assertEquals(17_250L, selected.snapshot.positionMs)
  }

  @Test fun permissiveJsonExtensionsCannotCreateAReadyPlayer() {
    val validPlayer = player().toString()
    for (json in listOf(
        """{"message_id":"read","result":[$validPlayer],true:null}""",
        """{"message_id":"read","result":[$validPlayer,]}""",
        """{"message_id":"read","result":[$validPlayer],}""",
        """{"message_id":"read","result":[$validPlayer], "extension":NaN}""",
        """{'message_id':'read','result':[$validPlayer]}""",
    )) {
      failed(MaMusicProtocol.parsePlayers(json))
    }
  }

  @Test fun confirmedPlayerImageProxyOnlyFillsTheExactCurrentQueueItemCover() {
    val proxy = "http://music.local:8095/imageproxy/opaque-id?size=800"
    val entry = item()
    entry.getJSONObject("image").put("remotely_accessible", false).put("provider", "library")
    val media = JSONObject().put("uri", "http://music.local/stream/one")
        .put("title", "Song").put("queue_item_id", "item-1").put("source_id", "queue-shared")
        .put("image_url", proxy)
    val p = player().put("current_media", media)
    val selected = selection(listOf(p), queue().put("current_item", entry),
        listOf(entry, item("item-2")))
    assertEquals(proxy, selected.snapshot.track?.artUrl)
    assertEquals(proxy, selected.snapshot.queue.first().artUrl)
    assertEquals("https://example.org/cover.jpg", selected.snapshot.queue.last().artUrl)
    media.put("queue_item_id", "item-2")
    val staleTrack = selection(listOf(p), queue().put("current_item", entry), listOf(entry))
    assertEquals("", staleTrack.snapshot.track?.artUrl)
    assertEquals("", staleTrack.snapshot.queue.single().artUrl)
    media.put("queue_item_id", "item-1").put("source_id", "other-queue")
    assertEquals("", selection(listOf(p), queue().put("current_item", entry))
        .snapshot.track?.artUrl)
    media.put("source_id", "queue-shared").remove("queue_item_id")
    media.put("uri", "item-1")
    assertEquals(proxy, selection(listOf(p), queue().put("current_item", entry)).snapshot.track?.artUrl)
    media.put("uri", "unrelated-current-id")
    assertEquals("", selection(listOf(p), queue().put("current_item", entry)).snapshot.track?.artUrl)
  }

  @Test fun nullForKnownNonNullableFieldsIsRejectedInsteadOfUsingAConvenientDefault() {
    for (key in listOf("elapsed_time", "elapsed_time_last_updated", "playback_speed",
        "repeat_mode", "display_name")) {
      failed(MaMusicProtocol.parseQueue(reply(queue().put(key, JSONObject.NULL))))
    }
    for (key in listOf("provider", "type", "volume_control")) {
      failed(MaMusicProtocol.parsePlayers(reply(JSONArray().put(player().put(key, JSONObject.NULL)))))
    }
    val entry = item()
    entry.getJSONObject("media_item").put("name", JSONObject.NULL)
    failed(MaMusicProtocol.parseQueueItems(reply(JSONArray().put(entry)), "queue-shared"))
  }

  @Test fun volumeCapabilityIsReportedPerRoomEvenWhenUnsupportedRoomsHaveALevel() {
    val selected = selection(listOf(player(),
        player("living").put("volume_level", 40),
        player("fixed", features = emptyList()).put("volume_control", "none"),
        player("no-control").put("volume_control", "none"),
        player("offline").put("available", false)))
    val rooms = selected.snapshot.rooms.associateBy { it.id }
    assertTrue(rooms.getValue("ma:family").volumeControllable)
    assertTrue(rooms.getValue("ma:living").volumeControllable)
    assertEquals(30, rooms.getValue("ma:fixed").volume)
    assertFalse(rooms.getValue("ma:fixed").volumeControllable)
    assertFalse(rooms.getValue("ma:no-control").volumeControllable)
    assertFalse(rooms.getValue("ma:offline").volumeControllable)
  }
}
