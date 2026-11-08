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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicHandoffCodecTest {
  private fun roundTrip(q: MusicHandoffQueue) = MusicHandoffCodec.decode(MusicHandoffCodec.encode(q))

  @Test fun queueRoundTripsWithIndexPositionRepeatAndLabel() {
    val queue = ytQueueOf(4, start = 2).withRepeat(YtRepeat.ALL)
    val sent = MusicHandoffCodec.fromQueue(queue, 95_000)!!
    val back = roundTrip(sent)!!
    assertEquals(sent, back)
    val adopted = back.toQueue()
    assertEquals(queue.entries.map { it.track }, adopted.entries.map { it.track })
    assertEquals(2, adopted.currentIndex)
    assertEquals(YtRepeat.ALL, adopted.repeat)
    assertEquals("Playlist · Road trip", adopted.sourceLabel)
    assertEquals(95_000, back.positionMs)
  }

  @Test fun shuffleKeepsPlayOrderAndCanStillUnshuffle() {
    val shuffled = ytQueueOf(6, start = 3).shuffled(seed = 7)
    val back = roundTrip(MusicHandoffCodec.fromQueue(shuffled, 0)!!)!!.toQueue()
    assertEquals(shuffled.entries.map { it.track }, back.entries.map { it.track })
    assertTrue(back.isShuffled)
    assertEquals(ytQueueOf(6).entries.map { it.track }, back.unshuffled().entries.map { it.track })
  }

  @Test fun longQueuesSendAWindowAroundTheCurrentSong() {
    val queue = ytQueueOf(500, start = 300)
    val sent = MusicHandoffCodec.fromQueue(queue, 0)!!
    assertEquals(MusicHandoffCodec.MAX_TRACKS, sent.tracks.size)
    assertEquals(queue.current!!.track, sent.tracks[sent.index])
    assertEquals(MusicHandoffCodec.KEEP_BEFORE, sent.index)
    val nearEnd = MusicHandoffCodec.fromQueue(ytQueueOf(500, start = 499), 0)!!
    assertEquals(ytTrack(500), nearEnd.tracks.last())
    assertEquals(nearEnd.tracks.lastIndex, nearEnd.index)
  }

  @Test fun unplayableSongsAreLeftBehindButTheCurrentOneGoes() {
    var queue = ytQueueOf(4, start = 1)
    queue = queue.markUnplayable(queue.entries[0].id, "embed").markUnplayable(queue.entries[1].id, "embed")
    val sent = MusicHandoffCodec.fromQueue(queue, 0)!!
    assertEquals(listOf(ytTrack(2), ytTrack(3), ytTrack(4)), sent.tracks)
    assertEquals(0, sent.index)
  }

  @Test fun emptyQueueHasNothingToSend() {
    assertNull(MusicHandoffCodec.fromQueue(YtQueue.EMPTY, 0))
  }

  @Test fun textAndUrlsAreCappedAndCleaned() {
    val long = "x".repeat(1_000)
    val track = YtTrack("abcdefghijk", "Ti\u0000tle$long", long, "http://insecure/x.jpg", 1_000)
    val q = MusicHandoffQueue(listOf(track), 0, 0, YtRepeat.OFF, null, long)
    val back = roundTrip(q)!!
    assertEquals(MusicHandoffCodec.MAX_TEXT, back.tracks[0].title.length)
    assertTrue('\u0000' !in back.tracks[0].title)
    assertEquals("", back.tracks[0].thumbnailUrl)
    assertEquals(MusicHandoffCodec.MAX_TEXT, back.label.length)
  }

  @Test fun encodedMaximumStaysUnderTheBodyLimit() {
    val long = "é".repeat(MusicHandoffCodec.MAX_TEXT)
    val url = "https://i.ytimg.com/" + "a".repeat(MusicHandoffCodec.MAX_URL - 20)
    val tracks = (1..MusicHandoffCodec.MAX_TRACKS).map { YtTrack("vid%08d".format(it), long, long, url, 1L) }
    val text = MusicHandoffCodec.encode(MusicHandoffQueue(tracks, 0, 0, YtRepeat.OFF, null, long))
    assertTrue(text.toByteArray(Charsets.UTF_8).size <= MusicHandoffCodec.MAX_BODY_BYTES)
  }

  private fun payload(tracks: JSONArray, index: Int = 0, extra: (JSONObject) -> Unit = {}) =
      JSONObject().put("v", 1).put("i", index).put("pos", 0).put("tracks", tracks).also(extra).toString()

  private fun trackJson(id: String) = JSONObject().put("id", id).put("t", "T").put("a", "A")

  @Test fun rejectsTooManyTracksBadIdsBadIndexAndUnknownVersion() {
    val tooMany = JSONArray((0..MusicHandoffCodec.MAX_TRACKS).map { trackJson("vid%08d".format(it)) })
    assertNull(MusicHandoffCodec.decode(payload(tooMany)))
    assertNull(MusicHandoffCodec.decode(payload(JSONArray(listOf(trackJson("../../etc"))))))
    assertNull(MusicHandoffCodec.decode(payload(JSONArray(listOf(trackJson("vid00000001"))), index = 3)))
    assertNull(MusicHandoffCodec.decode(payload(JSONArray(), index = 0)))
    assertNull(MusicHandoffCodec.decode(payload(JSONArray(listOf(trackJson("vid00000001")))) { it.put("v", 9) }))
    assertNull(MusicHandoffCodec.decode("not json"))
    assertNull(MusicHandoffCodec.decode(null))
  }

  @Test fun positionIsClampedAndABadShuffleOrderIsDropped() {
    val ok = JSONArray(listOf(trackJson("vid00000001"), trackJson("vid00000002")))
    val decoded = MusicHandoffCodec.decode(payload(ok) {
      it.put("pos", Long.MAX_VALUE).put("u", JSONArray(listOf(0, 0)))
    })
    assertNotNull(decoded)
    assertEquals(MusicHandoffCodec.MAX_POSITION_MS, decoded!!.positionMs)
    assertNull(decoded.shuffleOrder)
    assertEquals(0L, MusicHandoffCodec.decode(payload(ok) { it.put("pos", -9) })!!.positionMs)
  }
}
