/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.YtProblem
import com.immortal.launcher.youtube.YtResult
import com.immortal.launcher.youtube.YtSearchPage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.immortal.launcher.youtube.YtTrack as YtDataTrack

/** The phone routes and commands of the 2026-11-08 music round (plan A.6). */
class YtRemoteMusicRoundTest {
  private val vid = "abcdefghijk"
  private fun decode(json: String) = YtRemoteProtocol.decode(JSONObject(json))
  private fun ok(json: String) = (decode(json) as YtRemoteProtocol.Decoded.Ok).command
  private fun invalid(json: String) = (decode(json) as YtRemoteProtocol.Decoded.Invalid).code
  private val room = YtRemoteRoom(quiet = false, quietUntil = null, routine = false, inCall = false,
      account = null, volume = null)

  @Test fun subRoutesUsePhoneSafeNames() {
    YtRemoteRoute.install()
    listOf(YtRemoteLibraryRoutes.HISTORY, YtRemoteLibraryRoutes.SUGGEST, YtRemoteLibraryRoutes.SEARCH).forEach {
      assertTrue(it, it.removePrefix("/remote/").matches(Regex("[a-z]+(?:/[a-z]+)*")))
      assertTrue(it, RemoteFeatureRoutes.lookup(it) != null)
    }
    assertEquals(listOf("/remote/ytmusic/history", "/remote/ytmusic/suggest", "/remote/ytmusic/search"),
        listOf(YtRemoteLibraryRoutes.HISTORY, YtRemoteLibraryRoutes.SUGGEST, YtRemoteLibraryRoutes.SEARCH))
  }

  @Test fun decodesPlayTrack() {
    val command = ok("""{"cmd":"playtrack","videoId":"$vid","title":"Hello","artist":"Adele","thumb":"https://t/x.jpg"}""")
    assertEquals(YtRemoteCommand.PlayTrack(YtTrack(vid, "Hello", "Adele", "https://t/x.jpg"), playAnyway = false), command)
    assertTrue(command.startsPlayback)
    val anyway = ok("""{"cmd":"playtrack","videoId":"$vid","title":"Hello","artist":"","playAnyway":true}""")
    assertTrue(anyway.playAnyway)
    assertEquals(MusicShareQueue.thumbnailFor(vid), (anyway as YtRemoteCommand.PlayTrack).track.thumbnailUrl)
    assertEquals("invalid_video", invalid("""{"cmd":"playtrack","videoId":"nope"}"""))
  }

  @Test fun playTrackSanitizesText() {
    val command = ok("""{"cmd":"playtrack","videoId":"$vid","title":"   ","artist":"${"a".repeat(500)}",
        "thumb":"javascript:alert(1)"}""") as YtRemoteCommand.PlayTrack
    assertEquals("Song", command.track.title)
    assertEquals(YtRemoteProtocol.MAX_TEXT, command.track.artist.length)
    assertEquals(MusicShareQueue.thumbnailFor(vid), command.track.thumbnailUrl)
  }

  @Test fun decodesQueueTrack() {
    val next = ok("""{"cmd":"queuetrack","videoId":"$vid","title":"T","artist":"A","thumb":"","position":"next"}""")
    assertEquals(YtRemoteCommand.QueueTrack(YtTrack(vid, "T", "A", MusicShareQueue.thumbnailFor(vid)), next = true), next)
    assertFalse(next.startsPlayback)
    val end = ok("""{"cmd":"queuetrack","videoId":"$vid","title":"T","artist":"A","position":"end"}""")
    assertFalse((end as YtRemoteCommand.QueueTrack).next)
    assertEquals("invalid_position", invalid("""{"cmd":"queuetrack","videoId":"$vid","position":"middle"}"""))
    assertEquals("invalid_video", invalid("""{"cmd":"queuetrack","position":"end"}"""))
  }

  @Test fun decodesSleep() {
    assertEquals(YtRemoteCommand.Sleep(MusicSleepChoice.OFF), ok("""{"cmd":"sleep","minutes":0}"""))
    assertEquals(YtRemoteCommand.Sleep(MusicSleepChoice.MIN_15), ok("""{"cmd":"sleep","minutes":15}"""))
    assertEquals(YtRemoteCommand.Sleep(MusicSleepChoice.MIN_30), ok("""{"cmd":"sleep","minutes":30}"""))
    assertEquals(YtRemoteCommand.Sleep(MusicSleepChoice.MIN_60), ok("""{"cmd":"sleep","minutes":60}"""))
    assertEquals(YtRemoteCommand.Sleep(MusicSleepChoice.END_OF_SONG), ok("""{"cmd":"sleep","minutes":-1}"""))
    listOf("45", "\"15\"", "15.5", "-2", "null").forEach {
      assertEquals(it, "invalid_minutes", invalid("""{"cmd":"sleep","minutes":$it}"""))
    }
    assertFalse(YtRemoteCommand.Sleep(MusicSleepChoice.MIN_15).startsPlayback)
  }

  @Test fun newCommandsNeverConflict() {
    val s = YtEngineState()
    assertNull(YtRemoteProtocol.conflict(YtRemoteCommand.Sleep(MusicSleepChoice.MIN_15), s))
    assertNull(YtRemoteProtocol.conflict(YtRemoteCommand.PlayTrack(YtTrack(vid, "T", "A"), false), s))
    assertNull(YtRemoteProtocol.conflict(YtRemoteCommand.QueueTrack(YtTrack(vid, "T", "A"), true), s))
  }

  @Test fun stateReportsSleepAndNightCap() {
    val idle = YtRemoteProtocol.state(YtEngineState(), 0, room)
    assertTrue(idle.isNull("sleepUntilMs"))
    assertFalse(idle.getBoolean("sleepEndOfSong"))
    assertTrue(idle.isNull("nightCap"))
    val timed = YtRemoteProtocol.state(YtEngineState(sleep = MusicSleepTimer(MusicSleepChoice.MIN_30, 1_800_000)), 0,
        room.copy(nightCap = MusicNightCap(active = true, maxLevel = 6)))
    assertEquals(1_800_000L, timed.getLong("sleepUntilMs"))
    assertFalse(timed.getBoolean("sleepEndOfSong"))
    assertTrue(timed.getJSONObject("nightCap").getBoolean("active"))
    assertEquals(6, timed.getJSONObject("nightCap").getInt("maxLevel"))
    val end = YtRemoteProtocol.state(YtEngineState(sleep = MusicSleepTimer(MusicSleepChoice.END_OF_SONG, null)), 0, room)
    assertTrue(end.isNull("sleepUntilMs"))
    assertTrue(end.getBoolean("sleepEndOfSong"))
  }

  @Test fun historyListingShape() {
    val play = MusicPlay(vid, "Hello", "Adele", "https://t/1", 42, "Kitchen")
    val json = YtRemoteLibraryProtocol.history(listOf(play))
    assertTrue(json.getBoolean("ok"))
    assertEquals(1, json.getInt("total"))
    val row = json.getJSONArray("history").getJSONObject(0)
    assertEquals(vid, row.getString("videoId"))
    assertEquals(42L, row.getLong("playedAtMs"))
    assertEquals("Kitchen", row.getString("room"))
  }

  @Test fun historyActions() {
    assertEquals(YtRemoteLibraryProtocol.HistoryAction.Clear, YtRemoteLibraryProtocol.historyAction(JSONObject("""{"action":"clear"}""")))
    assertEquals(YtRemoteLibraryProtocol.HistoryAction.Remove(vid),
        YtRemoteLibraryProtocol.historyAction(JSONObject("""{"action":"remove","videoId":"$vid"}""")))
    assertEquals(YtRemoteLibraryProtocol.HistoryAction.Invalid("invalid_video"),
        YtRemoteLibraryProtocol.historyAction(JSONObject("""{"action":"remove"}""")))
    assertEquals(YtRemoteLibraryProtocol.HistoryAction.Invalid("unknown_action"),
        YtRemoteLibraryProtocol.historyAction(JSONObject("{}")))
  }

  @Test fun queryBounds() {
    assertEquals("a", YtRemoteLibraryProtocol.query(JSONObject("""{"q":" a "}"""), 1))
    assertNull(YtRemoteLibraryProtocol.query(JSONObject("""{"q":"a"}"""), 2))
    assertNull(YtRemoteLibraryProtocol.query(JSONObject("""{"q":5}"""), 1))
    assertNull(YtRemoteLibraryProtocol.query(JSONObject().put("q", "x".repeat(101)), 1))
  }

  @Test fun suggestionsShape() {
    val json = YtRemoteLibraryProtocol.suggestions(listOf(MusicSuggestion("adele", MusicSuggestionKind.SEARCH),
        MusicSuggestion("Hello", MusicSuggestionKind.SONG, vid, "Adele", "https://t/1")))
    val rows = json.getJSONArray("suggestions")
    assertEquals(setOf("text", "kind"), rows.getJSONObject(0).keys().asSequence().toSet())
    assertEquals("song", rows.getJSONObject(1).getString("kind"))
    assertEquals(vid, rows.getJSONObject(1).getString("videoId"))
    assertEquals("Adele", rows.getJSONObject(1).getString("artist"))
  }

  @Test fun searchRepliesMapEveryOutcome() {
    val page = YtSearchPage(listOf(YtDataTrack(vid, "Hello", "Adele", null, 295_000)), remainingToday = 41, fromCache = false)
    val ok = YtRemoteLibraryProtocol.search(YtResult.Ok(page), 41)
    assertEquals(200, ok.status)
    assertEquals(41, ok.body.getInt("quotaLeft"))
    val track = ok.body.getJSONArray("tracks").getJSONObject(0)
    assertEquals(MusicShareQueue.thumbnailFor(vid), track.getString("thumb"))
    assertEquals(295_000L, track.getLong("durationMs"))
    val quota = YtRemoteLibraryProtocol.search(YtResult.Resting, 0)
    assertEquals(429, quota.status)
    assertEquals("quota", quota.body.getString("error"))
    assertEquals(503, YtRemoteLibraryProtocol.search(YtResult.Offline, 3).status)
    assertEquals(409, YtRemoteLibraryProtocol.search(YtResult.NeedsSignIn, 3).status)
    assertEquals(400, YtRemoteLibraryProtocol.search(YtResult.Failed(400, YtProblem.INVALID_REQUEST), 3).status)
    assertEquals(502, YtRemoteLibraryProtocol.search(YtResult.Failed(500, YtProblem.SERVER), 3).status)
  }

  @Test fun durationIsOmittedWhenUnknown() {
    val page = YtSearchPage(listOf(YtDataTrack(vid, "Hello", "Adele", "https://t/1")), 9, fromCache = true)
    val track = YtRemoteLibraryProtocol.search(YtResult.Ok(page), 9).body.getJSONArray("tracks").getJSONObject(0)
    assertFalse(track.has("durationMs"))
    assertEquals("https://t/1", track.getString("thumb"))
  }
}
