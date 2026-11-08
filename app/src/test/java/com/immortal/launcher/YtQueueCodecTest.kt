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

class YtQueueCodecTest {
  @Test fun roundTrip_keepsEverything() {
    val base = ytQueueOf(5, start = 2).withRepeat(YtRepeat.ALL)
    val q = base.markUnplayable(base.entries[4].id, "Removed or private").shuffled(11L)
        .addToEnd(ytTrack(7).copy(durationMs = null))
    val back = YtQueueCodec.decode(YtQueueCodec.encode(q, 61_500))!!
    assertEquals(q, back.queue)
    assertEquals(61_500, back.positionMs)
  }

  @Test fun roundTrip_emptyQueue() {
    val back = YtQueueCodec.decode(YtQueueCodec.encode(YtQueue.EMPTY, 0))!!
    assertTrue(back.queue.isEmpty)
  }

  @Test fun decode_rejectsGarbageAndUnknownVersion() {
    assertNull(YtQueueCodec.decode(null))
    assertNull(YtQueueCodec.decode(""))
    assertNull(YtQueueCodec.decode("{not json"))
    val json = JSONObject(YtQueueCodec.encode(ytQueueOf(2), 0)).put("v", 99).toString()
    assertNull(YtQueueCodec.decode(json))
  }

  @Test fun decode_dropsInvalidEntries_andClampsIndex() {
    val json = """{"v":1,"index":7,"label":"L","repeat":"BOGUS","nextId":10,"entries":[
      {"id":1,"vid":"bad id","t":"x","a":"y"},
      {"id":2,"vid":"abcdefghijk","t":"Ok","a":"A","art":"https://i.ytimg.com/a.jpg","d":1000},
      {"id":2,"vid":"abcdefghijk","t":"Dup id","a":"A"}],"position":-5}"""
    val back = YtQueueCodec.decode(json)!!
    assertEquals(listOf("Ok"), back.queue.entries.map { it.track.title })
    assertEquals(0, back.queue.currentIndex)
    assertEquals(YtRepeat.OFF, back.queue.repeat)
    assertEquals(0, back.positionMs)
    assertFalse(back.queue.isShuffled)
  }

  @Test fun decode_keepsNextIdAboveEveryEntryId() {
    val json = """{"v":1,"index":0,"nextId":1,"entries":[{"id":40,"vid":"abcdefghijk","t":"T","a":"A"}]}"""
    val q = YtQueueCodec.decode(json)!!.queue
    assertTrue(q.nextId > 40)
  }

  @Test fun decode_capsTextLength() {
    val long = "x".repeat(5_000)
    val json = """{"v":1,"index":0,"entries":[{"id":1,"vid":"abcdefghijk","t":"$long","a":"A"}]}"""
    assertEquals(YtQueueCodec.MAX_TEXT_CHARS, YtQueueCodec.decode(json)!!.queue.entries[0].track.title.length)
  }
}
