/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtRemoteProtocolTest {
  private fun decode(json: String) = YtRemoteProtocol.decode(JSONObject(json))
  private fun ok(json: String) = (decode(json) as YtRemoteProtocol.Decoded.Ok).command
  private fun invalid(json: String) = (decode(json) as YtRemoteProtocol.Decoded.Invalid).code

  private val room = YtRemoteRoom(quiet = false, quietUntil = null, routine = false, inCall = false,
      account = "Rohit", volume = RemoteMedia.VolumeState(7, 15, muted = false, canSet = true))

  @Test fun routeRegistersUnderAPhoneSafeName() {
    YtRemoteRoute.install()
    assertTrue(RemoteFeatureRoutes.lookup(YtRemoteRoute.PATH) === YtRemoteRoute)
    assertTrue(YtRemoteRoute.PATH.removePrefix("/remote/").matches(Regex("[a-z]+(?:/[a-z]+)*")))
  }

  @Test fun decodesTransportCommands() {
    assertEquals(YtRemoteCommand.Play(playAnyway = false), ok("""{"cmd":"play"}"""))
    assertEquals(YtRemoteCommand.Play(playAnyway = true), ok("""{"cmd":"play","playAnyway":true}"""))
    assertEquals(YtRemoteCommand.Pause, ok("""{"cmd":"pause"}"""))
    assertEquals(YtRemoteCommand.Next, ok("""{"cmd":"next"}"""))
    assertEquals(YtRemoteCommand.Previous, ok("""{"cmd":"previous"}"""))
    assertEquals(YtRemoteCommand.Seek(65_000), ok("""{"cmd":"seek","positionMs":65000}"""))
  }

  @Test fun decodesQueueCommands() {
    assertEquals(YtRemoteCommand.Jump(4, playAnyway = false), ok("""{"cmd":"jump","entryId":4}"""))
    assertEquals(YtRemoteCommand.Remove(4), ok("""{"cmd":"remove","entryId":4}"""))
    assertEquals(YtRemoteCommand.PlayNext(4), ok("""{"cmd":"playnext","entryId":4}"""))
  }

  @Test fun rejectsUnknownAndMalformedCommands() {
    assertEquals("unknown_cmd", invalid("""{"cmd":"rm -rf"}"""))
    assertEquals("unknown_cmd", invalid("""{}"""))
    assertEquals("invalid_position", invalid("""{"cmd":"seek"}"""))
    assertEquals("invalid_position", invalid("""{"cmd":"seek","positionMs":-1}"""))
    assertEquals("invalid_position", invalid("""{"cmd":"seek","positionMs":1.5}"""))
    assertEquals("invalid_position", invalid("""{"cmd":"seek","positionMs":"10"}"""))
    assertEquals("invalid_entry", invalid("""{"cmd":"jump"}"""))
    assertEquals("invalid_entry", invalid("""{"cmd":"remove","entryId":0}"""))
  }

  @Test fun onlyPlayAndJumpStartPlayback() {
    assertTrue(YtRemoteCommand.Play(false).startsPlayback)
    assertTrue(YtRemoteCommand.Jump(1, false).startsPlayback)
    assertFalse(YtRemoteCommand.Next.startsPlayback)
    assertFalse(YtRemoteCommand.Pause.startsPlayback)
  }

  @Test fun conflictsNeedAQueueAndAKnownEntry() {
    val empty = YtEngineState()
    assertEquals("nothing_queued", YtRemoteProtocol.conflict(YtRemoteCommand.Play(false), empty))
    val queued = YtEngineState(queue = ytQueueOf(3))
    assertNull(YtRemoteProtocol.conflict(YtRemoteCommand.Play(false), queued))
    assertNull(YtRemoteProtocol.conflict(YtRemoteCommand.Remove(2), queued))
    assertEquals("unknown_entry", YtRemoteProtocol.conflict(YtRemoteCommand.Jump(99, false), queued))
    assertEquals("unknown_entry", YtRemoteProtocol.conflict(YtRemoteCommand.PlayNext(99), queued))
    assertNull(YtRemoteProtocol.conflict(YtRemoteCommand.Pause, empty))
  }

  @Test fun idleStateStillReportsVolumeAndAccount() {
    val json = YtRemoteProtocol.state(YtEngineState(), nowElapsedMs = 1_000, room = room)
    assertEquals("idle", json.getString("state"))
    assertTrue(json.isNull("current"))
    assertEquals(0, json.getJSONArray("upNext").length())
    assertEquals(7, json.getJSONObject("volume").getInt("level"))
    assertEquals(15, json.getJSONObject("volume").getInt("max"))
    assertEquals("Rohit", json.getString("account"))
    assertTrue(json.getBoolean("signedIn"))
    assertFalse(json.getBoolean("quiet"))
    assertTrue(json.isNull("quietUntil"))
  }

  @Test fun playingStateExtrapolatesPositionAndListsUpNext() {
    val s = YtEngineState(queue = ytQueueOf(4, start = 1), status = YtPlayerStatus.PLAYING, foreground = true,
        positionMs = 10_000, positionAtElapsedMs = 5_000, durationMs = 200_000)
    val json = YtRemoteProtocol.state(s, nowElapsedMs = 8_000, room = room)
    assertEquals("playing", json.getString("state"))
    assertEquals("playing", json.getString("room"))
    assertTrue(json.getBoolean("onScreen"))
    assertEquals(13_000, json.getLong("positionMs"))
    assertEquals(200_000, json.getLong("durationMs"))
    val current = json.getJSONObject("current")
    assertEquals("Song 2", current.getString("title"))
    assertEquals("Artist 2", current.getString("artist"))
    assertEquals(2L, current.getLong("entryId"))
    assertTrue(current.getString("thumb").startsWith("https://"))
    val upNext = json.getJSONArray("upNext")
    assertEquals(listOf("Song 3", "Song 4"), (0 until upNext.length()).map { upNext.getJSONObject(it).getString("title") })
    assertEquals(2, json.getInt("upNextTotal"))
    assertEquals("off", json.getString("repeat"))
  }

  @Test fun pausedStateDoesNotTick() {
    val s = YtEngineState(queue = ytQueueOf(2), status = YtPlayerStatus.PAUSED, positionMs = 10_000,
        positionAtElapsedMs = 5_000, durationMs = 200_000)
    val json = YtRemoteProtocol.state(s, nowElapsedMs = 60_000, room = room)
    assertEquals("paused", json.getString("state"))
    assertEquals(10_000, json.getLong("positionMs"))
  }

  @Test fun upNextIsCapped() {
    val s = YtEngineState(queue = ytQueueOf(YtRemoteProtocol.MAX_UP_NEXT + 10))
    val json = YtRemoteProtocol.state(s, nowElapsedMs = 0, room = room)
    assertEquals(YtRemoteProtocol.MAX_UP_NEXT, json.getJSONArray("upNext").length())
    assertEquals(YtRemoteProtocol.MAX_UP_NEXT + 9, json.getInt("upNextTotal"))
  }

  @Test fun quietHoursAndHaltAreReported() {
    val quiet = room.copy(quiet = true, quietUntil = "7:00 AM", account = null, volume = null)
    val s = YtEngineState(queue = ytQueueOf(1), halt = YtHalt.EndOfQueue)
    val json = YtRemoteProtocol.state(s, nowElapsedMs = 0, room = quiet)
    assertTrue(json.getBoolean("quiet"))
    assertEquals("quiet", json.getString("room"))
    assertEquals("7:00 AM", json.getString("quietUntil"))
    assertEquals("end_of_queue", json.getJSONObject("halt").getString("code"))
    assertTrue(json.isNull("volume"))
    assertTrue(json.isNull("account"))
    assertFalse(json.getBoolean("signedIn"))
  }

  @Test fun unplayableEntriesCarryTheirReason() {
    val q = ytQueueOf(3).markUnplayable(3, "Embedding is off")
    val json = YtRemoteProtocol.state(YtEngineState(queue = q), nowElapsedMs = 0, room = room)
    val upNext = json.getJSONArray("upNext")
    assertTrue(upNext.getJSONObject(0).isNull("unplayable"))
    assertEquals("Embedding is off", upNext.getJSONObject(1).getString("unplayable"))
  }
}
