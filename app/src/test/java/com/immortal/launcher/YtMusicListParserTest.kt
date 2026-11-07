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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMusicListParserTest {
  private fun item(
      i: Any? = 0,
      t: Any? = "Low tide, early",
      s: Any? = "Marrow & Vale",
      d: Any? = "4:12",
      art: Any? = "https://lh3.googleusercontent.com/abc=w120-h120",
      p: Any? = false,
      k: Any? = "song",
      pl: Any? = true,
  ): JSONObject = JSONObject().apply {
    put("i", i ?: JSONObject.NULL); put("t", t ?: JSONObject.NULL); put("s", s ?: JSONObject.NULL)
    put("d", d ?: JSONObject.NULL); put("art", art ?: JSONObject.NULL); put("p", p ?: JSONObject.NULL)
    put("k", k ?: JSONObject.NULL); put("pl", pl ?: JSONObject.NULL)
  }

  /** Builds the wire string with `v` and `type` first, exactly like bridge.js. */
  private fun msg(
      items: List<JSONObject> = listOf(item()),
      kind: Any? = "queue",
      g: Any? = 3,
      here: Any? = true,
      so: Any? = false,
      q: Any? = "",
      v: Any? = 1,
  ): String {
    val rest = JSONObject().apply {
      put("k", kind ?: JSONObject.NULL); put("g", g ?: JSONObject.NULL)
      put("here", here ?: JSONObject.NULL); put("so", so ?: JSONObject.NULL)
      put("q", q ?: JSONObject.NULL); put("items", JSONArray(items))
    }.toString().removePrefix("{")
    return "{\"v\":${JSONObject.wrap(v)},\"type\":\"list\",$rest"
  }

  @Test fun validQueueMessage_mapsEveryField() {
    val list = YtMusicListParser.parse(msg(listOf(item(p = true), item(i = 1, t = "Embers", d = "1:02:03",
        k = "playlist", pl = false))))!!
    assertEquals(YtMusicTab.QUEUE, list.tab)
    assertEquals(3, list.generation)
    assertTrue(list.onPage)
    assertFalse(list.signedOut)
    assertEquals(2, list.items.size)
    val first = list.items[0]
    assertEquals(0, first.index)
    assertEquals("Low tide, early", first.title)
    assertEquals("Marrow & Vale", first.subtitle)
    assertEquals("4:12", first.duration)
    assertEquals("https://lh3.googleusercontent.com/abc=w120-h120", first.artUrl)
    assertTrue(first.playing)
    assertTrue(first.playable)
    assertEquals(YtMusicItemKind.SONG, first.kind)
    assertEquals("1:02:03", list.items[1].duration)
    assertEquals(YtMusicItemKind.PLAYLIST, list.items[1].kind)
    assertFalse(list.items[1].playable)
  }

  @Test fun isListMessage_onlyMatchesTheListPrefix() {
    assertTrue(YtMusicListParser.isListMessage(msg()))
    assertFalse(YtMusicListParser.isListMessage("""{"v":1,"t":"Song","s":"playing"}"""))
    assertFalse(YtMusicListParser.isListMessage(null))
  }

  @Test fun librarySearchKinds_andFlags() {
    val lib = YtMusicListParser.parse(msg(kind = "library", here = false, so = true))!!
    assertEquals(YtMusicTab.LIBRARY, lib.tab)
    assertFalse(lib.onPage)
    assertTrue(lib.signedOut)
    val search = YtMusicListParser.parse(msg(kind = "search", q = "  rain   on a\ttin roof "))!!
    assertEquals(YtMusicTab.SEARCH, search.tab)
    assertEquals("rain on a tin roof", search.query)
  }

  @Test fun emptyItems_isAValidEmptyList() {
    val list = YtMusicListParser.parse(msg(items = emptyList()))!!
    assertTrue(list.items.isEmpty())
  }

  @Test fun rejectsBadEnvelopes() {
    assertNull(YtMusicListParser.parse(null))
    assertNull(YtMusicListParser.parse(""))
    assertNull(YtMusicListParser.parse("not json"))
    assertNull(YtMusicListParser.parse(msg(v = 2)))
    assertNull(YtMusicListParser.parse(msg(kind = "home")))
    assertNull(YtMusicListParser.parse(msg(kind = 7)))
    assertNull(YtMusicListParser.parse(msg(g = -1)))
    assertNull(YtMusicListParser.parse(msg(g = "3")))
    assertNull(YtMusicListParser.parse(msg(g = 1.5)))
    assertNull(YtMusicListParser.parse("""{"v":1,"type":"list","k":"queue","g":1,"items":{}}"""))
    assertNull(YtMusicListParser.parse("""{"v":1,"type":"nowplaying","k":"queue","g":1,"items":[]}"""))
    assertNull(YtMusicListParser.parse("[1,2,3]"))
  }

  @Test fun rejectsOversizedMessage() {
    val huge = msg(listOf(item(t = "x".repeat(YtMusicListParser.MAX_MESSAGE_CHARS))))
    assertNull(YtMusicListParser.parse(huge))
  }

  @Test fun dropsInvalidItems_keepsTheRest() {
    val list = YtMusicListParser.parse(msg(listOf(
        item(i = 0),
        item(i = -1),
        item(i = YtMusicListParser.MAX_INDEX + 1),
        item(i = "2"),
        item(i = 2.5),
        item(i = 3, t = "   "),
        item(i = 4, t = 42),
        item(i = 0, t = "duplicate index"),
        item(i = 5, t = "Survivor"),
    )))!!
    assertEquals(listOf(0, 5), list.items.map { it.index })
  }

  @Test fun nonHttpsOrForeignArt_isDropped_itemKept() {
    val list = YtMusicListParser.parse(msg(listOf(
        item(i = 0, art = "http://lh3.googleusercontent.com/a"),
        item(i = 1, art = "javascript:alert(1)"),
        item(i = 2, art = "https://evil.example.com/a.jpg"),
        item(i = 3, art = "https://user@i.ytimg.com/vi/x/default.jpg"),
        item(i = 4, art = 12),
        item(i = 5, art = "https://i.ytimg.com/vi/x/sddefault.jpg"),
    )))!!
    assertEquals(6, list.items.size)
    assertEquals(listOf("", "", "", "", "", "https://i.ytimg.com/vi/x/sddefault.jpg"),
        list.items.map { it.artUrl })
  }

  @Test fun capsItemCount() {
    val many = (0 until YtMusicListParser.MAX_ITEMS + 25).map { item(i = it, t = "Song $it", art = "") }
    val list = YtMusicListParser.parse(msg(many))!!
    assertEquals(YtMusicListParser.MAX_ITEMS, list.items.size)
    assertEquals(YtMusicListParser.MAX_ITEMS - 1, list.items.last().index)
  }

  @Test fun sanitizesAndTruncatesText() {
    val list = YtMusicListParser.parse(msg(listOf(item(
        t = "Low\u0000 tide​,\n early" + "y".repeat(500),
        s = "Marrow‮ & Vale",
    ))))!!
    val it = list.items.single()
    assertTrue(it.title.startsWith("Low tide, earlyy"))
    assertEquals(YtMusicListParser.MAX_ITEM_TEXT_CHARS, it.title.length)
    assertEquals("Marrow & Vale", it.subtitle)
  }

  @Test fun hostileTypes_fallBackSafely() {
    val list = YtMusicListParser.parse(msg(listOf(item(
        s = listOf("a"), d = "4:12; alert(1)", p = "true", k = "<script>", pl = "yes",
    ))))!!
    val it = list.items.single()
    assertEquals("", it.subtitle)
    assertEquals("", it.duration)
    assertFalse(it.playing)
    assertEquals(YtMusicItemKind.OTHER, it.kind)
    assertTrue("playable defaults to true unless explicitly false", it.playable)
  }

  @Test fun onlyFirstPlayingRow_isMarkedPlaying() {
    val list = YtMusicListParser.parse(msg(listOf(item(i = 0, p = true), item(i = 1, p = true))))!!
    assertEquals(listOf(true, false), list.items.map { it.playing })
  }

  @Test fun durationFormat_isStrict() {
    fun d(v: String) = YtMusicListParser.parse(msg(listOf(item(d = v))))!!.items.single().duration
    assertEquals("0:59", d("0:59"))
    assertEquals("12:05", d("12:05"))
    assertEquals("", d("123:05"))
    assertEquals("", d("4:5"))
    assertEquals("", d("LIVE"))
  }

  @Test fun playScript_embedsOnlyFixedKindAndNumbers() {
    assertEquals("window.__portalYtm&&window.__portalYtm.playItem('search',4,9);",
        YtMusicListParser.playScript(YtMusicTab.SEARCH, 4, 9))
    assertNull(YtMusicListParser.playScript(YtMusicTab.QUEUE, -1, 0))
    assertNull(YtMusicListParser.playScript(YtMusicTab.QUEUE, YtMusicListParser.MAX_INDEX + 1, 0))
    assertNull(YtMusicListParser.playScript(YtMusicTab.QUEUE, 1, -2))
  }

  @Test fun watchScript_namesTheTab() {
    assertEquals("window.__portalYtm&&window.__portalYtm.watch('library');",
        YtMusicListParser.watchScript(YtMusicTab.LIBRARY))
  }
}
