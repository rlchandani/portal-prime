/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** YouTube Data API replies with missing, mistyped or placeholder fields. */
class YtParsersCoverageTest {
  private fun items(vararg entries: Any?, next: Any? = null) = JSONObject().apply {
    put("items", JSONArray().apply { entries.forEach { put(it ?: JSONObject.NULL) } })
    next?.let { put("nextPageToken", it) }
  }
  private fun thumbs(vararg sizes: Pair<String, String>) = JSONObject().apply {
    sizes.forEach { (size, url) -> put(size, JSONObject().put("url", url)) }
  }

  @Test fun missingOrNonArrayItemsGiveEmptyPages() {
    assertEquals(YtPage(emptyList<YtPlaylist>(), null), YtParsers.playlists(JSONObject()))
    assertEquals(YtPage(emptyList<YtPlaylist>(), null), YtParsers.playlists(JSONObject().put("items", "x").put("nextPageToken", "")))
    assertEquals("p2", YtParsers.subscriptions(JSONObject().put("nextPageToken", "p2")).nextPageToken)
    assertTrue(YtParsers.searchResults(JSONObject().put("items", JSONObject())).isEmpty())
    assertTrue(YtParsers.videoFacts(JSONObject()).isEmpty())
    assertTrue(YtParsers.videoCategories(JSONObject()).isEmpty())
  }

  @Test fun playlistsSkipEntriesWithoutIdOrSnippetAndReadOptionalCount() {
    val page = YtParsers.playlists(items(
        "not an object",
        JSONObject().put("snippet", JSONObject().put("title", "No id")),
        JSONObject().put("id", "PL0"),
        JSONObject().put("id", "PL1").put("snippet", JSONObject().put("title", "Rock &amp; Roll")),
        JSONObject().put("id", "PL2").put("snippet", JSONObject().put("title", "Counted").put("thumbnails", thumbs("default" to "d")))
            .put("contentDetails", JSONObject().put("itemCount", 12)),
        JSONObject().put("id", "PL3").put("snippet", JSONObject()).put("contentDetails", JSONObject()),
        next = "tok"))
    assertEquals(listOf(YtPlaylist("PL1", "Rock & Roll", null, null), YtPlaylist("PL2", "Counted", "d", 12), YtPlaylist("PL3", "", null, null)), page.items)
    assertEquals("tok", page.nextPageToken)
  }

  @Test fun playlistItemsDropUnavailableAndOwnerlessVideos() {
    fun item(videoId: String?, title: String, owner: String?, viaResource: Boolean = false) = JSONObject().apply {
      val snippet = JSONObject().put("title", title)
      owner?.let { snippet.put("videoOwnerChannelTitle", it) }
      if (viaResource) videoId?.let { snippet.put("resourceId", JSONObject().put("videoId", it)) }
      else videoId?.let { put("contentDetails", JSONObject().put("videoId", it)) }
      put("snippet", snippet)
    }
    val page = YtParsers.playlistItems(items(
        item("v1", "Song", "Artist - Topic"),
        item("v2", "Deleted video", "Someone"),
        item("v3", "Private video", "Someone"),
        item("v4", "Song", null),
        item(null, "Song", "Artist"),
        item("", "Song", "Artist"),
        item("v5", "Other", "Band", viaResource = true),
        JSONObject().put("contentDetails", JSONObject().put("videoId", "v6")),
    ))
    assertEquals(listOf(YtTrack("v1", "Song", "Artist", null), YtTrack("v5", "Other", "Band", null)), page.items)
  }

  @Test fun subscriptionsNeedAChannelId() {
    val page = YtParsers.subscriptions(items(
        JSONObject().put("snippet", JSONObject().put("title", "No resource")),
        JSONObject().put("snippet", JSONObject().put("title", "Empty").put("resourceId", JSONObject().put("channelId", ""))),
        JSONObject(),
        JSONObject().put("snippet", JSONObject().put("title", "Chan &lt;3").put("resourceId", JSONObject().put("channelId", "UC1"))
            .put("thumbnails", thumbs("maxres" to "max", "medium" to "med"))),
    ))
    assertEquals(listOf(YtArtist("UC1", "Chan <3", "med")), page.items)
  }

  @Test fun searchResultsRequireVideoIdAndSnippet() {
    val results = YtParsers.searchResults(items(
        JSONObject().put("id", JSONObject().put("kind", "youtube#channel")).put("snippet", JSONObject()),
        JSONObject().put("id", JSONObject().put("videoId", "v1")),
        JSONObject().put("id", "v2").put("snippet", JSONObject()),
        JSONObject().put("id", JSONObject().put("videoId", "v3")).put("snippet", JSONObject().put("title", "&quot;Hit&quot; &#39;99&#39;")
            .put("channelTitle", "Singer - Topic").put("thumbnails", thumbs("high" to "", "standard" to "std"))),
    ))
    assertEquals(listOf(YtTrack("v3", "\"Hit\" '99'", "Singer", "std")), results)
  }

  @Test fun videoFactsToleratesMissingDetailsAndStatus() {
    val facts = YtParsers.videoFacts(items(
        JSONObject().put("id", "a").put("contentDetails", JSONObject().put("duration", "PT3M")).put("status", JSONObject().put("embeddable", true)),
        JSONObject().put("id", "b"),
        JSONObject().put("id", "c").put("contentDetails", JSONObject().put("duration", "garbage")).put("status", JSONObject()),
        JSONObject().put("contentDetails", JSONObject().put("duration", "PT1S")),
        JSONObject().put("id", "d").put("status", JSONObject().put("embeddable", "yes")),
    ))
    assertEquals(mapOf("a" to YtVideoFacts(180_000, true), "b" to YtVideoFacts(null, false),
        "c" to YtVideoFacts(null, false), "d" to YtVideoFacts(null, false)), facts)
  }

  @Test fun videoCategoriesDefaultToEmptyCategory() {
    val categories = YtParsers.videoCategories(items(
        JSONObject().put("id", "a").put("snippet", JSONObject().put("categoryId", "10")),
        JSONObject().put("id", "b"),
        JSONObject().put("snippet", JSONObject().put("categoryId", "10")),
    ))
    assertEquals(mapOf("a" to "10", "b" to ""), categories)
  }

  @Test fun relatedPlaylistReadsOnlyTheFirstChannel() {
    val json = items(JSONObject().put("contentDetails", JSONObject().put("relatedPlaylists", JSONObject().put("likes", "LL").put("uploads", ""))),
        JSONObject().put("contentDetails", JSONObject().put("relatedPlaylists", JSONObject().put("uploads", "UU2"))))
    assertEquals("LL", YtParsers.relatedPlaylist(json, "likes"))
    assertNull(YtParsers.relatedPlaylist(json, "uploads"))
    assertNull(YtParsers.relatedPlaylist(json, "favorites"))
    assertNull(YtParsers.relatedPlaylist(items(JSONObject()), "likes"))
    assertNull(YtParsers.relatedPlaylist(JSONObject(), "likes"))
  }

  @Test fun channelIdentityHandlesChannelLessAndSnippetLessAccounts() {
    assertNull(YtParsers.channelIdentity(JSONObject()))
    assertNull(YtParsers.channelIdentity(items(JSONObject().put("snippet", JSONObject().put("title", "x")))))
    assertEquals(YtAccount("UC1", "", null), YtParsers.channelIdentity(items(JSONObject().put("id", "UC1"))))
    assertEquals(YtAccount("UC2", "Me & you", "t"), YtParsers.channelIdentity(items(
        JSONObject().put("id", "UC2").put("snippet", JSONObject().put("title", "Me &amp; you").put("thumbnails", thumbs("default" to "t"))))))
  }

  @Test fun durationParsingEdgeCases() {
    assertEquals(3_723_000L, YtParsers.durationMs("PT1H2M3S"))
    assertEquals(86_401_000L, YtParsers.durationMs(" P1DT1S "))
    assertEquals(0L, YtParsers.durationMs("PT0S"))
    assertEquals(86_400_000L, YtParsers.durationMs("P1D"))
    for (bad in listOf("", "P", "PT", "1H", "PT1.5S", "PT-1S", "PT1S1M", "pt1s", "P1W")) assertNull(bad, YtParsers.durationMs(bad))
  }

  @Test fun unescapeDecodesAmpersandLastSoEntitiesAreNotDoubleDecoded() {
    assertEquals("plain", YtParsers.unescapeHtml("plain"))
    assertEquals("&lt;", YtParsers.unescapeHtml("&amp;lt;"))
    assertEquals("<a> & \"b\" 'c'", YtParsers.unescapeHtml("&lt;a&gt; &amp; &quot;b&quot; &#39;c&#39;"))
    assertEquals("& alone", YtParsers.unescapeHtml("& alone"))
  }

  @Test fun thumbnailFallsBackThroughSizesAndIgnoresMalformed() {
    val snippet = JSONObject().put("title", "t").put("resourceId", JSONObject().put("channelId", "UC"))
        .put("thumbnails", JSONObject().put("high", "not an object").put("medium", JSONObject()).put("default", JSONObject().put("url", "def")))
    assertEquals("def", YtParsers.subscriptions(items(JSONObject().put("snippet", snippet))).items.single().thumbnailUrl)
    val none = JSONObject().put("resourceId", JSONObject().put("channelId", "UC")).put("thumbnails", "x")
    assertNull(YtParsers.subscriptions(items(JSONObject().put("snippet", none))).items.single().thumbnailUrl)
    assertFalse(YtParsers.subscriptions(items(JSONObject().put("snippet", none))).items.isEmpty())
  }
}
