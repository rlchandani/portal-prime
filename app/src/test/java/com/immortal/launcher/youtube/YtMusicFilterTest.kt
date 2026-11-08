/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YtMusicFilterTest {
  private fun item(videoId: String) =
      """{"snippet":{"title":"T $videoId","videoOwnerChannelTitle":"Band"},"contentDetails":{"videoId":"$videoId"}}"""

  private fun items(ids: List<String>) = """{"items":[${ids.joinToString(",") { item(it) }}]}"""

  private fun cats(map: Map<String, String>) =
      """{"items":[${map.entries.joinToString(",") { """{"id":"${it.key}","snippet":{"categoryId":"${it.value}"}}""" }}]}"""

  private class World(val lib: YouTubeMusicLibrary, val transport: FakeTransport, val clock: FakeClock, val cache: MemoryCacheStore)

  /** Signed-in library; [playlistItems] maps playlist id → its video ids, [category] maps a video to its category. */
  private fun world(
      playlistItems: Map<String, List<String>>,
      category: (String) -> String? = { "10" },
      cache: MemoryCacheStore = MemoryCacheStore(),
      clock: FakeClock = FakeClock(),
  ): World {
    val transport = FakeTransport { req ->
      when {
        req.url.startsWith(YtEndpoints.TOKEN) -> ok(tokenGrant("AT"))
        req.url.contains("/playlistItems") -> ok(items(playlistItems[req.param("playlistId")].orEmpty()))
        req.url.contains("/videos") -> ok(cats(req.param("id").orEmpty().split(",")
            .mapNotNull { id -> category(id)?.let { id to it } }.toMap()))
        else -> YtHttpResponse(404, "{}")
      }
    }
    val lib = newLibrary(transport, clock = clock, cache = cache,
        secure = MemorySecureStore(storedAccounts("UC-A" to "RT-A", "UC-B" to "RT-B")))
    return World(lib, transport, clock, cache)
  }

  private fun ids(prefix: String, n: Int) = (1..n).map { "$prefix$it" }

  // ---- the rule ----

  @Test fun mostlyMusicNeedsHalfOfTheKnownSample() {
    assertTrue(YtMusicRule.mostlyMusic(listOf("a", "b"), mapOf("a" to "10", "b" to "22")))
    assertFalse(YtMusicRule.mostlyMusic(listOf("a", "b", "c"), mapOf("a" to "10", "b" to "22", "c" to "24")))
    assertTrue(YtMusicRule.mostlyMusic(listOf("a", "b", "c"), mapOf("a" to "10", "b" to "10", "c" to "24")))
  }

  @Test fun emptyOrUnknownSampleCountsAsMusic() {
    assertTrue(YtMusicRule.mostlyMusic(emptyList(), emptyMap()))
    assertTrue(YtMusicRule.mostlyMusic(listOf("a", "b"), mapOf("a" to "", "b" to "")))
    // Unknown items don't count either way: one known non-music item decides.
    assertFalse(YtMusicRule.mostlyMusic(listOf("a", "b"), mapOf("a" to "22")))
  }

  @Test fun onlyTheFirst25ItemsAreSampled() {
    val sample = ids("v", 30)
    val categories = sample.associateWith { if (it.removePrefix("v").toInt() <= 25) "22" else "10" }
    assertFalse(YtMusicRule.mostlyMusic(sample, categories))
  }

  @Test fun likedFilterKeepsMusicAndUnknown() {
    assertTrue(YtMusicRule.keepAsSong("10"))
    assertTrue(YtMusicRule.keepAsSong(null))
    assertTrue(YtMusicRule.keepAsSong(""))
    assertFalse(YtMusicRule.keepAsSong("20"))
  }

  // ---- playlist verdicts ----

  @Test fun verdictsSampleEachPlaylistAndUseVideosSnippetOnly() = runBlocking {
    val w = world(mapOf("PLm" to ids("m", 3), "PLv" to ids("g", 3)), category = { if (it.startsWith("m")) "10" else "20" })
    val verdicts = w.lib.playlistVerdicts(listOf(YtPlaylist("PLm", "Songs", null, 3), YtPlaylist("PLv", "Games", null, 3))).last()
    assertEquals(mapOf("PLm" to true, "PLv" to false), verdicts)
    val sample = w.transport.requests.first { it.url.contains("/playlistItems") }
    assertEquals("25", sample.param("maxResults"))
    val videos = w.transport.requests.filter { it.url.contains("/videos") }
    assertTrue(videos.all { it.param("part") == "snippet" })
    assertEquals(0, w.transport.count("/search"))
  }

  @Test fun videoCallsBatch50IdsAcrossPlaylists() = runBlocking {
    val lists = (1..3).associate { "PL$it" to ids("p${it}x", 25) }
    val w = world(lists)
    w.lib.playlistVerdicts(lists.keys.map { YtPlaylist(it, it, null, 25) }).last()
    val videos = w.transport.requests.filter { it.url.contains("/videos") }
    assertEquals(2, videos.size)
    assertTrue(videos.all { it.param("id")!!.split(",").size <= 50 })
  }

  @Test fun verdictIsCachedUntilTheItemCountChanges() = runBlocking {
    val w = world(mapOf("PL1" to ids("v", 2)), category = { "20" })
    assertEquals(mapOf("PL1" to false), w.lib.playlistVerdicts(listOf(YtPlaylist("PL1", "x", null, 2))).last())
    val before = w.transport.requests.size
    assertEquals(mapOf("PL1" to false), w.lib.playlistVerdicts(listOf(YtPlaylist("PL1", "x", null, 2))).last())
    assertEquals(before, w.transport.requests.size)
    w.lib.playlistVerdicts(listOf(YtPlaylist("PL1", "x", null, 3))).last()
    assertTrue(w.transport.requests.size > before)
  }

  @Test fun verdictExpiresAfter30Days() = runBlocking {
    val w = world(mapOf("PL1" to ids("v", 2)))
    w.lib.playlistVerdicts(listOf(YtPlaylist("PL1", "x", null, 2))).last()
    val before = w.transport.requests.size
    w.clock.now += YtLibraryCache.MAX_AGE_MS + 1
    w.lib.playlistVerdicts(listOf(YtPlaylist("PL1", "x", null, 2))).last()
    assertTrue(w.transport.requests.size > before)
  }

  @Test fun cachedVerdictsAreEmittedFirst() = runBlocking {
    val w = world(mapOf("PL1" to ids("v", 1), "PL2" to ids("w", 1)), category = { "20" })
    w.lib.playlistVerdicts(listOf(YtPlaylist("PL1", "x", null, 1))).last()
    val emissions = w.lib.playlistVerdicts(listOf(YtPlaylist("PL1", "x", null, 1), YtPlaylist("PL2", "y", null, 1))).toList()
    assertEquals(mapOf("PL1" to false), emissions.first())
    assertEquals(mapOf("PL1" to false, "PL2" to false), emissions.last())
  }

  @Test fun failedSampleLeavesNoVerdictSoThePlaylistShows() = runBlocking {
    val w = world(mapOf("PL1" to ids("v", 2)))
    w.transport.offline = true
    assertEquals(emptyMap<String, Boolean>(), w.lib.playlistVerdicts(listOf(YtPlaylist("PL1", "x", null, 2))).last())
  }

  @Test fun emptyPlaylistIsShownWithoutAVideosCall() = runBlocking {
    val w = world(mapOf("PL0" to emptyList()))
    assertEquals(mapOf("PL0" to true), w.lib.playlistVerdicts(listOf(YtPlaylist("PL0", "x", null, 0))).last())
    assertEquals(0, w.transport.count("/videos"))
  }

  // ---- liked songs ----

  @Test fun musicOnlyDropsNonMusicLikesAndCachesCategories() = runBlocking {
    val w = world(emptyMap(), category = { if (it == "vid") "20" else if (it == "gone") null else "10" })
    val liked = listOf(YtTrack("song", "S", "A", null), YtTrack("vid", "V", "A", null), YtTrack("gone", "G", "A", null))
    val kept = (w.lib.musicOnly(liked) as YtResult.Ok).value
    assertEquals(listOf("song", "gone"), kept.map { it.videoId })
    val calls = w.transport.count("/videos")
    w.lib.musicOnly(liked)
    assertEquals(calls, w.transport.count("/videos"))
  }

  @Test fun musicOnlyPassesFailuresThrough() = runBlocking {
    val w = world(emptyMap())
    w.transport.offline = true
    assertEquals(YtResult.Offline, w.lib.musicOnly(listOf(YtTrack("song", "S", "A", null))))
  }

  // ---- manual choices ----

  @Test fun choicesPersistPerAccountAndClearWithNull() = runBlocking {
    val w = world(emptyMap())
    w.lib.setPlaylistChoice("PL1", YtPlaylistChoice.HIDE)
    w.lib.setPlaylistChoice("PL2", YtPlaylistChoice.SHOW)
    assertEquals(mapOf("PL1" to YtPlaylistChoice.HIDE, "PL2" to YtPlaylistChoice.SHOW), w.lib.playlistChoices())
    w.lib.setPlaylistChoice("PL1", null)
    assertEquals(mapOf("PL2" to YtPlaylistChoice.SHOW), w.lib.playlistChoices())
    w.lib.switchAccount("UC-B")
    assertEquals(emptyMap<String, YtPlaylistChoice>(), w.lib.playlistChoices())
  }

  @Test fun choicesDoNotExpireLikeApiData() = runBlocking {
    val w = world(emptyMap())
    w.lib.setPlaylistChoice("PL1", YtPlaylistChoice.HIDE)
    w.clock.now += YtLibraryCache.MAX_AGE_MS * 3
    assertEquals(mapOf("PL1" to YtPlaylistChoice.HIDE), w.lib.playlistChoices())
  }

  @Test fun signOutRemovesThatAccountsChoicesAndVerdictsOnly() = runBlocking {
    val w = world(mapOf("PL1" to ids("v", 1)))
    w.lib.setPlaylistChoice("PL1", YtPlaylistChoice.HIDE)
    w.lib.playlistVerdicts(listOf(YtPlaylist("PL1", "x", null, 1))).last()
    w.lib.switchAccount("UC-B")
    w.lib.setPlaylistChoice("PL9", YtPlaylistChoice.HIDE)
    w.lib.signOut("UC-A")
    assertTrue(w.cache.entries.keys.none { it.startsWith(YtLibraryCache.accountPrefix("UC-A")) })
    assertEquals(mapOf("PL9" to YtPlaylistChoice.HIDE), w.lib.playlistChoices())
  }

  @Test fun corruptChoicesReadAsNone() = runBlocking {
    val cache = MemoryCacheStore()
    cache.entries[YtLibraryCache.accountPrefix("UC-A") + "choices"] = "not json"
    val w = world(emptyMap(), cache = cache)
    assertEquals(emptyMap<String, YtPlaylistChoice>(), w.lib.playlistChoices())
  }
}
