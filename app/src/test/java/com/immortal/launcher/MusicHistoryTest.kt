/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicHistoryTest {
  private val minute = 60_000L
  private fun id(n: Int) = "vid" + n.toString().padStart(8, '0')
  private fun play(n: Int, at: Long, room: String = "Living Area", title: String = "Song $n") =
      MusicPlay(id(n), title, "Artist $n", "https://i.ytimg.com/vi/${id(n)}/hqdefault.jpg", at, room)

  @Test fun recordKeepsNewestFirst() {
    val history = MusicHistory.record(MusicHistory.record(emptyList(), play(1, 1_000)), play(2, 2_000))
    assertEquals(listOf(id(2), id(1)), history.map { it.videoId })
  }

  @Test fun replayWithinTenMinutesUpdatesTheTimestamp() {
    val first = MusicHistory.record(emptyList(), play(1, 0))
    val again = MusicHistory.record(first, play(1, 9 * minute))
    assertEquals(1, again.size)
    assertEquals(9 * minute, again.single().playedAtMs)
  }

  @Test fun replayAfterTenMinutesAddsAnEntry() {
    val first = MusicHistory.record(emptyList(), play(1, 0))
    assertEquals(2, MusicHistory.record(first, play(1, 11 * minute)).size)
  }

  @Test fun sameVideoInAnotherRoomIsItsOwnEntry() {
    val history = MusicHistory.record(MusicHistory.record(emptyList(), play(1, 0)), play(1, minute, room = "Kitchen"))
    assertEquals(2, history.size)
    assertEquals(1, MusicHistory.listing(history).size)
    assertEquals("Kitchen", MusicHistory.listing(history).single().room)
  }

  @Test fun anOlderReplayArrivingLateNeverMovesTimeBackwards() {
    val history = MusicHistory.record(emptyList(), play(1, 5 * minute))
    assertEquals(5 * minute, MusicHistory.record(history, play(1, 2 * minute)).single().playedAtMs)
  }

  @Test fun aBlankTitleNeverWipesAKnownOne() {
    val history = MusicHistory.record(emptyList(), play(1, 0))
    assertEquals("Song 1", MusicHistory.record(history, play(1, minute, title = "")).single().title)
  }

  @Test fun duplicateBroadcastIsANoOp() {
    val history = MusicHistory.record(emptyList(), play(1, 0))
    assertEquals(history, MusicHistory.record(history, play(1, 0)))
  }

  @Test fun capsAtTwoHundredDroppingTheOldest() {
    val history = (1..205).fold(emptyList<MusicPlay>()) { acc, n -> MusicHistory.record(acc, play(n, n * 20 * minute)) }
    assertEquals(MusicHistory.MAX_ENTRIES, history.size)
    assertEquals(id(205), history.first().videoId)
    assertEquals(id(6), history.last().videoId)
  }

  @Test fun listingDedupesByVideoNewestWins() {
    val history = listOf(play(1, 30 * minute), play(2, 20 * minute), play(1, 0))
    assertEquals(listOf(30 * minute, 20 * minute), MusicHistory.listing(history).map { it.playedAtMs })
  }

  @Test fun removeAndClear() {
    val history = listOf(play(2, 30 * minute), play(1, 20 * minute), play(1, 0))
    assertEquals(listOf(id(2)), MusicHistory.remove(history, id(1)).map { it.videoId })
    assertEquals(listOf(id(2)), MusicHistory.clearedAt(history, 25 * minute).map { it.videoId })
    assertTrue(MusicHistory.clearedAt(history, 30 * minute).isEmpty())
  }

  @Test fun agoReadsNaturally() {
    val now = 10L * 24 * 60 * minute
    assertEquals("just now", MusicHistory.ago(now - 30_000, now))
    assertEquals("just now", MusicHistory.ago(now + minute, now))
    assertEquals("5 min ago", MusicHistory.ago(now - 5 * minute, now))
    assertEquals("2 h ago", MusicHistory.ago(now - 150 * minute, now))
    assertEquals("yesterday", MusicHistory.ago(now - 30 * 60 * minute, now))
    assertEquals("3 days ago", MusicHistory.ago(now - 3 * 24 * 60 * minute, now))
    assertEquals("Living Area · 2 h ago", MusicHistory.where(play(1, now - 120 * minute), now))
    assertEquals("5 min ago", MusicHistory.where(play(1, now - 5 * minute, room = ""), now))
  }

  @Test fun detectorCountsTheRealSongOnceAndNeverAnAd() {
    val queue = YtQueue().playNow(listOf(YtTrack(id(1), "Song", "Artist")), 0, "")
    val entry = queue.current!!
    val playing = YtEngineState(queue = queue, status = YtPlayerStatus.PLAYING, playerVideoId = id(1))
    assertSame(entry, MusicPlayDetector.started(playing, null))
    assertNull(MusicPlayDetector.started(playing, entry.id))
    assertNull(MusicPlayDetector.started(playing.copy(playerVideoId = "adadadadada"), null))
    assertNull(MusicPlayDetector.started(playing.copy(playerVideoId = null), null))
    assertNull(MusicPlayDetector.started(playing.copy(status = YtPlayerStatus.BUFFERING), null))
    assertNull(MusicPlayDetector.started(YtEngineState(), null))
  }

  @Test fun wireRoundTripsAndFitsOneDatagram() {
    val long = play(1, 1_000).copy(title = "t".repeat(500), artist = "a".repeat(500), room = "r".repeat(100),
        thumb = "https://x/" + "y".repeat(400))
    val body = MusicHistoryCodec.encodeWire(long)
    val wire = HouseholdMessage.encodeOrNull(HouseholdMessage(MusicHistoryCodec.PLAYED, "portal-1", 1_000,
        "abcdef012345", body), ByteArray(32) { 1 })
    assertTrue("must fit one datagram", wire != null)
    val decoded = MusicHistoryCodec.decodeWire(body, 1_000)!!
    assertEquals(MusicHistoryCodec.MAX_TITLE, decoded.title.length)
    assertEquals("", decoded.thumb)
    assertEquals(play(2, 5), MusicHistoryCodec.decodeWire(MusicHistoryCodec.encodeWire(play(2, 5)), 10))
  }

  @Test fun wireRejectsBadIdsFutureTimesAndOddThumbs() {
    val good = MusicHistoryCodec.encodeWire(play(1, 1_000))
    assertNull(MusicHistoryCodec.decodeWire(JSONObject(good.toString()).put("v", "short"), 1_000))
    assertNull(MusicHistoryCodec.decodeWire(JSONObject(good.toString()).put("at", "1000"), 1_000))
    assertNull(MusicHistoryCodec.decodeWire(good, 1_000 - MusicHistoryCodec.MAX_FUTURE_MS - 1))
    assertEquals("", MusicHistoryCodec.decodeWire(JSONObject(good.toString()).put("th", "javascript:x"), 1_000)!!.thumb)
  }

  @Test fun changesRoundTrip() {
    val clear = MusicHistoryCodec.Change.Clear(99)
    val remove = MusicHistoryCodec.Change.Remove(id(3))
    assertEquals(clear, MusicHistoryCodec.decodeChange(MusicHistoryCodec.encodeChange(clear)))
    assertEquals(remove, MusicHistoryCodec.decodeChange(MusicHistoryCodec.encodeChange(remove)))
    assertNull(MusicHistoryCodec.decodeChange(JSONObject().put("action", "remove").put("v", "x")))
    assertNull(MusicHistoryCodec.decodeChange(JSONObject().put("action", "nuke")))
  }

  @Test fun storedListRoundTripsAndToleratesJunk() {
    val history = listOf(play(2, 2_000), play(1, 1_000))
    assertEquals(history, MusicHistoryCodec.decodeList(MusicHistoryCodec.encodeList(history), 5_000))
    assertTrue(MusicHistoryCodec.decodeList("not json", 0).isEmpty())
    assertTrue(MusicHistoryCodec.decodeList(null, 0).isEmpty())
    assertEquals(1, MusicHistoryCodec.decodeList("""[{"v":"bad"},${MusicHistoryCodec.encodeWire(play(1, 1))}]""", 5).size)
  }

  @Test fun phoneRowUsesTheStandardThumbnailWhenMissing() {
    val json = MusicHistoryCodec.phoneJson(play(1, 7).copy(thumb = ""))
    assertEquals(MusicShareQueue.thumbnailFor(id(1)), json.getString("thumb"))
    assertEquals(setOf("videoId", "title", "artist", "thumb", "playedAtMs", "room"), json.keys().asSequence().toSet())
  }
}
