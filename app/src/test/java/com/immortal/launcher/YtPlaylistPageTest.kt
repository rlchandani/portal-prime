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

/** The cue-only playlist page: official API, nocookie host, validated ids in and out. */
class YtPlaylistPageTest {
  private val origin = "https://com.immortal.launcher"

  @Test fun html_cuesThePlaylistThroughTheOfficialApi() {
    val html = YtPlaylistPage.html(origin, "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG")!!
    assertTrue(html.contains("cuePlaylist({list:'PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG',listType:'playlist'})"))
    assertTrue(html.contains("getPlaylist()"))
    assertTrue(html.contains("host:'https://www.youtube-nocookie.com'"))
    assertTrue(html.contains("https://www.youtube.com/iframe_api"))
    assertTrue(html.contains("autoplay:0"))
  }

  @Test fun html_refusesUnsafeIds() {
    assertNull(YtPlaylistPage.html(origin, "PL');alert(1);//xx"))
    assertNull(YtPlaylistPage.html(origin, "short"))
    assertNull(YtPlaylistPage.html("http://x", "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"))
  }

  @Test fun parse_keepsValidDistinctIdsUpToTheCap() {
    val ids = JSONArray((1..260).map { ytTrack(it).videoId } + listOf("bad", ytTrack(1).videoId))
    val msg = YtPlaylistPage.parse(JSONObject().put("v", 1).put("e", "ids").put("ids", ids).toString())
    assertEquals(MusicShareQueue.MAX_TRACKS, (msg as YtPlaylistPage.Message.Ids).videoIds.size)
  }

  @Test fun parse_errorsAndJunk() {
    assertEquals(YtPlaylistPage.Message.Failed(150), YtPlaylistPage.parse("""{"v":1,"e":"error","c":150}"""))
    assertEquals(YtPlaylistPage.Message.Failed(-1), YtPlaylistPage.parse("""{"v":1,"e":"ids","ids":["bad"]}"""))
    assertNull(YtPlaylistPage.parse("""{"v":2,"e":"ids","ids":[]}"""))
    assertNull(YtPlaylistPage.parse("nope"))
    assertNull(YtPlaylistPage.parse(null))
    assertNotNull(YtPlaylistPage.parse("""{"v":1,"e":"error"}"""))
  }
}
