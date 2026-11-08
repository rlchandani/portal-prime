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

/** `POST /remote/musicshare` validation: ids, actions, text caps and the thumbnail allowlist. */
class MusicShareRequestTest {
  private fun body(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }
  private fun video(vararg extra: Pair<String, Any?>) =
      body("kind" to "video", "id" to "dQw4w9WgXcQ", "action" to "now", "from" to "Rohit's phone", *extra)
  private fun ok(b: JSONObject) = (MusicShareRequests.parse(b) as MusicShareRequests.Parsed.Ok).request
  private fun invalid(b: JSONObject) = (MusicShareRequests.parse(b) as MusicShareRequests.Parsed.Invalid).code

  @Test fun validVideo_parsesWithDefaults() {
    val r = ok(video())
    assertEquals(MusicShareKind.VIDEO, r.kind)
    assertEquals(MusicShareAction.NOW, r.action)
    assertEquals("Rohit's phone", r.from)
    assertFalse(r.shuffle)
    assertFalse(r.playAnyway)
    assertNull(r.thumb)
  }

  @Test fun badVideoIds_areRejected() {
    assertEquals("invalid_id", invalid(video("id" to "abc")))
    assertEquals("invalid_id", invalid(video("id" to "dQw4w9WgXc'")))
    assertEquals("invalid_id", invalid(video("id" to "abcdefghijklmnopqrstuvwxyz")))
    // Passes the wire pattern but isn't a real 11-character id the queue could hold.
    assertEquals("invalid_id", invalid(video("id" to "abcdefgh")))
    assertEquals("invalid_list", invalid(video("listId" to "short")))
  }

  @Test fun playlists_needAListIdAndMixesAreRefused() {
    val list = body("kind" to "playlist", "id" to "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG", "action" to "next", "shuffle" to true)
    assertTrue(ok(list).shuffle)
    assertEquals("OLAK5uy_kNbMC4n5UVbm2GdMEpT_cZ3NsL8vJxz5k", ok(list.put("id", "OLAK5uy_kNbMC4n5UVbm2GdMEpT_cZ3NsL8vJxz5k")).id)
    assertEquals("mix_not_supported", invalid(list.put("id", "RDAMVMdQw4w9WgXcQ")))
    assertEquals("invalid_list", invalid(list.put("id", "PL\"><script>")))
  }

  @Test fun unknownKindOrAction_isRejected() {
    assertEquals("invalid_kind", invalid(video("kind" to "album")))
    assertEquals("invalid_action", invalid(video("action" to "replace")))
    assertEquals("invalid_action", invalid(video("action" to JSONObject.NULL)))
  }

  @Test fun text_isCleanedAndCapped() {
    val r = ok(video("title" to "  Salt\u0000water\n  hours  " + "x".repeat(300), "from" to "x".repeat(99), "artist" to 42))
    assertTrue(r.title.startsWith("Saltwater hours"))
    assertEquals(MusicShareRequests.MAX_TITLE, r.title.length)
    assertEquals(MusicShareRequests.MAX_FROM, r.from.length)
    assertEquals("", r.artist)
    assertEquals("A phone", ok(video("from" to "   ")).from)
  }

  @Test fun thumbnails_areHttpsAndAllowlistedOnly() {
    assertEquals("https://i.ytimg.com/vi/x/hq.jpg", MusicShareRequests.thumb("https://i.ytimg.com/vi/x/hq.jpg"))
    assertEquals("https://lh3.googleusercontent.com/a=w120", MusicShareRequests.thumb("https://lh3.googleusercontent.com/a=w120"))
    assertEquals("https://yt3.ggpht.com/a", MusicShareRequests.thumb("https://yt3.ggpht.com/a"))
    assertNull(MusicShareRequests.thumb("http://i.ytimg.com/vi/x/hq.jpg"))
    assertNull(MusicShareRequests.thumb("https://evil.example/i.ytimg.com.jpg"))
    assertNull(MusicShareRequests.thumb("https://i.ytimg.com@evil.example/x"))
    assertNull(MusicShareRequests.thumb("https://i.ytimg.com:8443/x"))
    assertNull(MusicShareRequests.thumb("https://i.ytimg.com/" + "a".repeat(600)))
    assertNull(ok(video("thumb" to "javascript:alert(1)")).thumb)
  }

  @Test fun shareId_isKeptOnlyWhenWellFormed() {
    assertEquals("s1abc", ok(video("shareId" to "s1abc")).shareId)
    assertNull(ok(video("shareId" to "../x")).shareId)
  }
}
