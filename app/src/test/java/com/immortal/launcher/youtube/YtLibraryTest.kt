/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YtLibraryTest {
  private fun item(videoId: String, title: String, owner: String? = "Artist - Topic") =
      """{"snippet":{"title":"$title",${if (owner != null) "\"videoOwnerChannelTitle\":\"$owner\"," else ""}
        "thumbnails":{"medium":{"url":"https://i/$videoId.jpg"}}},"contentDetails":{"videoId":"$videoId"}}"""

  private fun page(items: List<String>, next: String? = null) =
      """{"items":[${items.joinToString(",")}]${if (next != null) ",\"nextPageToken\":\"$next\"" else ""}}"""

  /** Signed-in library whose API calls are answered by [api] (token endpoint handled here). */
  private fun library(api: (YtHttpRequest) -> YtHttpResponse): Pair<YouTubeMusicLibrary, FakeTransport> {
    val transport = FakeTransport { req -> if (req.url.startsWith(YtEndpoints.TOKEN)) ok(tokenGrant("AT")) else api(req) }
    return newLibrary(transport, secure = MemorySecureStore(storedAccounts("UC-A" to "RT-A"))) to transport
  }

  private fun <T> YtResult<YtLoaded<T>>.value(): T = (this as YtResult.Ok).value.value

  @Test fun playlistsFollowPagingAndMapFields() = runBlocking {
    val (lib, transport) = library { req ->
      when (req.param("pageToken")) {
        null -> ok("""{"items":[{"id":"PL1","snippet":{"title":"One","thumbnails":{"high":{"url":"h1"}}},"contentDetails":{"itemCount":4}}],"nextPageToken":"P2"}""")
        "P2" -> ok("""{"items":[{"id":"PL2","snippet":{"title":"Two"},"contentDetails":{"itemCount":0}}]}""")
        else -> YtHttpResponse(400, "{}")
      }
    }
    val playlists = lib.playlists().last().value()
    assertEquals(listOf(YtPlaylist("PL1", "One", "h1", 4), YtPlaylist("PL2", "Two", null, 0)), playlists)
    val first = transport.requests.first { it.url.contains("/playlists") }
    assertEquals("snippet,contentDetails", first.param("part"))
    assertEquals("true", first.param("mine"))
    assertEquals("50", first.param("maxResults"))
    assertTrue(first.url.startsWith(YtEndpoints.API_BASE))
  }

  @Test fun playlistItemsSkipDeletedAndPrivateAndStripTopicSuffix() = runBlocking {
    val (lib, transport) = library { req ->
      if (req.param("pageToken") == null) ok(page(listOf(item("v1", "Song 1"), item("gone", "Deleted video", owner = null)), "N"))
      else ok(page(listOf(item("p", "Private video", owner = null), item("v2", "Song 2", owner = "Band"))))
    }
    val tracks = lib.playlistTracks("PL1").last().value()
    assertEquals(listOf(
        YtTrack("v1", "Song 1", "Artist", "https://i/v1.jpg"),
        YtTrack("v2", "Song 2", "Band", "https://i/v2.jpg")), tracks)
    assertEquals("PL1", transport.requests.last().param("playlistId"))
  }

  @Test fun likedSongsUseLmWhenItHasItems() = runBlocking {
    val (lib, transport) = library { req ->
      if (req.param("playlistId") == "LM") ok(page(listOf(item("m1", "Music like"))))
      else YtHttpResponse(500, "{}")
    }
    assertEquals(listOf("m1"), lib.likedSongs().last().value().map { it.videoId })
    assertEquals(0, transport.count("/channels"))
  }

  @Test fun likedSongsFallBackToRelatedLikesWhenLmIsEmptyOrMissing() = runBlocking {
    for (lm in listOf(ok(page(emptyList())), YtHttpResponse(404, "{}"))) {
      val (lib, transport) = library { req ->
        when {
          req.param("playlistId") == "LM" -> lm
          req.url.contains("/channels") -> ok("""{"items":[{"contentDetails":{"relatedPlaylists":{"likes":"LL","uploads":"UU1"}}}]}""")
          req.param("playlistId") == "LL" -> ok(page(listOf(item("l1", "Liked"))))
          else -> YtHttpResponse(500, "{}")
        }
      }
      assertEquals(listOf("l1"), lib.likedSongs().last().value().map { it.videoId })
      assertEquals("contentDetails", transport.requests.first { it.url.contains("/channels") }.param("part"))
    }
  }

  @Test fun artistsComeFromSubscriptionsAndUploadsFromTheChannel() = runBlocking {
    val (lib, transport) = library { req ->
      when {
        req.url.contains("/subscriptions") -> ok("""{"items":[{"snippet":{"title":"Band","resourceId":{"kind":"youtube#channel","channelId":"UC-BAND"},"thumbnails":{"default":{"url":"t"}}}}]}""")
        req.url.contains("/channels") -> ok("""{"items":[{"contentDetails":{"relatedPlaylists":{"uploads":"UU-BAND"}}}]}""")
        req.param("playlistId") == "UU-BAND" -> ok(page(listOf(item("u1", "New single", owner = "Band"))))
        else -> YtHttpResponse(500, "{}")
      }
    }
    assertEquals(listOf(YtArtist("UC-BAND", "Band", "t")), lib.artists().last().value())
    assertEquals(listOf("u1"), lib.artistTracks("UC-BAND").last().value().map { it.videoId })
    assertEquals("UC-BAND", transport.requests.first { it.url.contains("/channels") }.param("id"))
  }

  @Test fun playableAddsDurationsAndDropsNonEmbeddable() = runBlocking {
    val (lib, transport) = library {
      ok("""{"items":[
        {"id":"a","contentDetails":{"duration":"PT1H2M3S"},"status":{"embeddable":true}},
        {"id":"b","contentDetails":{"duration":"PT4M"},"status":{"embeddable":false}}]}""")
    }
    val input = listOf(YtTrack("a", "A", "x", null), YtTrack("b", "B", "x", null), YtTrack("c", "C", "x", null))
    val out = (lib.playable(input) as YtResult.Ok).value
    assertEquals(listOf(YtTrack("a", "A", "x", null, 3_723_000L)), out)
    val req = transport.requests.last()
    assertEquals("a,b,c", req.param("id"))
    assertEquals("contentDetails,status", req.param("part"))
  }

  @Test fun isoDurationParsing() {
    assertEquals(3_723_000L, YtParsers.durationMs("PT1H2M3S"))
    assertEquals(45_000L, YtParsers.durationMs("PT45S"))
    assertEquals(86_400_000L + 1000L, YtParsers.durationMs("P1DT1S"))
    assertNull(YtParsers.durationMs("garbage"))
    assertNull(YtParsers.durationMs(""))
  }

  @Test fun serverErrorsAreTyped() = runBlocking {
    assertEquals(YtResult.Failed(503, YtProblem.SERVER), library { YtHttpResponse(503, "x") }.first.playlists().last())
    assertEquals(YtResult.Failed(404, YtProblem.NOT_FOUND), library { YtHttpResponse(404, "{}") }.first.playlistTracks("X").last())
    assertEquals(YtResult.Failed(200, YtProblem.BAD_RESPONSE), library { ok("<html>") }.first.playlists().last())
    val quota = YtHttpResponse(403, """{"error":{"errors":[{"reason":"quotaExceeded"}]}}""")
    assertEquals(YtResult.Resting, library { quota }.first.playlists().last())
  }
}
