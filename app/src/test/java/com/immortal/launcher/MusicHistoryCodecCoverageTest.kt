/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared play history: hostile peer fields, clock limits, change messages and stored-list parsing. */
class MusicHistoryCodecCoverageTest {
  private val now = 1_800_000_000_000L
  private val play = MusicPlay("dQw4w9WgXcQ", "Song", "Artist", "https://i.example.invalid/t.jpg", now - 1_000, "Kitchen")

  private fun wire() = MusicHistoryCodec.encodeWire(play)

  @Test fun roundTrips() {
    assertEquals(play, MusicHistoryCodec.decodeWire(wire(), now))
  }

  @Test fun idAndTimeRejections() {
    assertNull(MusicHistoryCodec.decodeWire(wire().put("v", "short"), now))
    assertNull(MusicHistoryCodec.decodeWire(wire().apply { remove("v") }, now))
    assertNull(MusicHistoryCodec.decodeWire(wire().put("at", "1"), now))
    assertNull(MusicHistoryCodec.decodeWire(wire().apply { remove("at") }, now))
    assertNull(MusicHistoryCodec.decodeWire(wire().put("at", 0), now))
    assertNull(MusicHistoryCodec.decodeWire(wire().put("at", now + MusicHistoryCodec.MAX_FUTURE_MS + 1), now))
    assertEquals(now + MusicHistoryCodec.MAX_FUTURE_MS,
        MusicHistoryCodec.decodeWire(wire().put("at", now + MusicHistoryCodec.MAX_FUTURE_MS), now)!!.playedAtMs)
  }

  @Test fun untrustedTextsAreCleanedOrDropped() {
    val p = MusicHistoryCodec.decodeWire(wire().put("th", "http://plain.example.invalid/t.jpg").put("t", 5)
        .put("r", "").apply { remove("a") }, now)!!
    assertEquals("", p.thumb)
    assertEquals("", p.room)
    assertEquals("", p.artist)
    assertEquals("", MusicHistoryCodec.decodeWire(wire().put("th", "https://" + "x".repeat(300)), now)!!.thumb)
    val bidi = MusicHistoryCodec.decodeWire(wire().put("t", "a‮b"), now)!!
    assertTrue(!bidi.title.contains('‮'))
  }

  @Test fun encodeClipsLongFieldsAndDropsLongThumbs() {
    val long = play.copy(title = "t".repeat(200), artist = "a".repeat(100), thumb = "https://" + "x".repeat(400), room = "r".repeat(60))
    val o = MusicHistoryCodec.encodeWire(long)
    assertEquals(MusicHistoryCodec.MAX_TITLE, o.getString("t").length)
    assertEquals(MusicHistoryCodec.MAX_ARTIST, o.getString("a").length)
    assertEquals("", o.getString("th"))
    assertEquals(MusicHistoryCodec.MAX_ROOM, o.getString("r").length)
  }

  @Test fun changeMessages() {
    val clear = MusicHistoryCodec.Change.Clear(now)
    val remove = MusicHistoryCodec.Change.Remove("dQw4w9WgXcQ")
    assertEquals(clear, MusicHistoryCodec.decodeChange(MusicHistoryCodec.encodeChange(clear)))
    assertEquals(remove, MusicHistoryCodec.decodeChange(MusicHistoryCodec.encodeChange(remove)))
    assertNull(MusicHistoryCodec.decodeChange(JSONObject().put("action", "clear").put("at", 0)))
    assertNull(MusicHistoryCodec.decodeChange(JSONObject().put("action", "clear").put("at", "5")))
    assertNull(MusicHistoryCodec.decodeChange(JSONObject().put("action", "clear")))
    assertNull(MusicHistoryCodec.decodeChange(JSONObject().put("action", "remove").put("v", "bad")))
    assertNull(MusicHistoryCodec.decodeChange(JSONObject().put("action", "nuke")))
    assertNull(MusicHistoryCodec.decodeChange(JSONObject()))
  }

  @Test fun storedListParsing() {
    assertEquals(emptyList<MusicPlay>(), MusicHistoryCodec.decodeList(null, now))
    assertEquals(emptyList<MusicPlay>(), MusicHistoryCodec.decodeList("  ", now))
    assertEquals(emptyList<MusicPlay>(), MusicHistoryCodec.decodeList("{not an array", now))
    val text = JSONArray().put(wire()).put("junk").put(JSONObject().put("v", "bad")).toString()
    assertEquals(listOf(play), MusicHistoryCodec.decodeList(text, now))
    assertEquals(listOf(play), MusicHistoryCodec.decodeList(MusicHistoryCodec.encodeList(listOf(play)), now))
  }

  @Test fun phoneJsonFallsBackToTheYouTubeThumbnail() {
    val o = MusicHistoryCodec.phoneJson(play.copy(thumb = " "))
    assertEquals(MusicShareQueue.thumbnailFor(play.videoId), o.getString("thumb"))
    assertEquals(play.thumb, MusicHistoryCodec.phoneJson(play).getString("thumb"))
    assertEquals("Kitchen", o.getString("room"))
  }
}
