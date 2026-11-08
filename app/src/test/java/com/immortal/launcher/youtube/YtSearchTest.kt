/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YtSearchTest {
  /** 2026-11-08 00:00 PST (UTC-8), i.e. Google's quota reset instant. */
  private val ptMidnight = 1_794_124_800_000L
  /** 2026-07-01 00:00 PDT (UTC-7): the reset follows daylight time too. */
  private val ptSummerMidnight = 1_782_889_200_000L

  private val results =
      """{"items":[{"id":{"videoId":"s1"},"snippet":{"title":"Hit &amp; Run","channelTitle":"Band","thumbnails":{"high":{"url":"h"}}}}]}"""

  private fun setup(clock: FakeClock, cache: MemoryCacheStore = MemoryCacheStore(), search: () -> YtHttpResponse = { ok(results) }):
      Pair<YouTubeMusicLibrary, FakeTransport> {
    val transport = FakeTransport { req -> if (req.url.startsWith(YtEndpoints.TOKEN)) ok(tokenGrant("AT", expiresIn = 86_400 * 3)) else search() }
    return newLibrary(transport, clock, MemorySecureStore(storedAccounts("UC-A" to "RT-A")), cache) to transport
  }

  @Test fun searchSendsMusicEmbeddableSyndicatedParametersAndMapsTracks() = runBlocking {
    val (lib, transport) = setup(FakeClock())
    val page = (lib.search("  Hit Run ") as YtResult.Ok).value
    assertEquals(listOf(YtTrack("s1", "Hit & Run", "Band", "h")), page.tracks)
    assertFalse(page.fromCache)
    assertEquals(99, page.remainingToday)
    val req = transport.requests.last()
    assertEquals("snippet", req.param("part"))
    assertEquals("video", req.param("type"))
    assertEquals("10", req.param("videoCategoryId"))
    assertEquals("true", req.param("videoEmbeddable"))
    assertEquals("true", req.param("videoSyndicated"))
    assertEquals("25", req.param("maxResults"))
    assertEquals("Hit Run", req.param("q"))
  }

  @Test fun blankAndOneCharacterQueriesNeverCallTheApi() = runBlocking {
    val (lib, transport) = setup(FakeClock())
    for (q in listOf("", "   ", "a", " b ")) {
      assertEquals(YtResult.Failed(0, YtProblem.INVALID_REQUEST), lib.search(q))
    }
    assertEquals(0, transport.count("/search"))
    assertEquals(100, lib.searchesRemainingToday())
  }

  @Test fun cacheHitByNormalizedQueryDoesNotCountAndExpiresAfter24h() = runBlocking {
    val clock = FakeClock(ptMidnight + 60_000L)
    val (lib, transport) = setup(clock)
    lib.search("Daft Punk")
    val again = (lib.search("  daft   PUNK ") as YtResult.Ok).value
    assertTrue(again.fromCache)
    assertEquals(1, transport.count("/search"))
    assertEquals(99, lib.searchesRemainingToday())
    clock.now += 24 * 3_600_000L + 1
    assertFalse((lib.search("daft punk") as YtResult.Ok).value.fromCache)
    assertEquals(2, transport.count("/search"))
  }

  @Test fun diskCacheAndBudgetSurviveANewInstance() = runBlocking {
    val clock = FakeClock(ptMidnight + 60_000L)
    val cache = MemoryCacheStore()
    setup(clock, cache).first.search("daft punk")
    val (lib, transport) = setup(clock, cache)
    assertTrue((lib.search("Daft Punk") as YtResult.Ok).value.fromCache)
    assertEquals(0, transport.count("/search"))
    assertEquals(99, lib.searchesRemainingToday())
  }

  @Test fun dailyCapOfOneHundredThenRestingButCachedStillServed() = runBlocking {
    val clock = FakeClock(ptMidnight + 60_000L)
    val (lib, transport) = setup(clock)
    repeat(100) { assertTrue(lib.search("query $it") is YtResult.Ok) }
    assertEquals(0, lib.searchesRemainingToday())
    assertEquals(YtResult.Resting, lib.search("brand new"))
    assertEquals(100, transport.count("/search"))
    val cached = (lib.search("query 97") as YtResult.Ok).value
    assertTrue(cached.fromCache)
    assertEquals(0, cached.remainingToday)
  }

  @Test fun budgetResetsAtPacificMidnight() = runBlocking {
    for (midnight in listOf(ptMidnight, ptSummerMidnight)) {
      val clock = FakeClock(midnight - 120_000L)
      val (lib, _) = setup(clock)
      repeat(100) { lib.search("query $it") }
      assertEquals(YtResult.Resting, lib.search("late night"))
      clock.now = midnight - 1
      assertEquals(0, lib.searchesRemainingToday())
      clock.now = midnight
      assertEquals(100, lib.searchesRemainingToday())
      assertTrue(lib.search("late night") is YtResult.Ok)
      assertEquals(99, lib.searchesRemainingToday())
    }
  }

  @Test fun quotaExceededFromGoogleRestsForTheRestOfTheDay() = runBlocking {
    val clock = FakeClock(ptMidnight + 60_000L)
    val quota = YtHttpResponse(403, """{"error":{"code":403,"errors":[{"domain":"youtube.quota","reason":"quotaExceeded"}]}}""")
    val (lib, transport) = setup(clock) { quota }
    assertEquals(YtResult.Resting, lib.search("anything"))
    assertEquals(0, lib.searchesRemainingToday())
    assertEquals(YtResult.Resting, lib.search("something else"))
    assertEquals(1, transport.count("/search"))
  }

  @Test fun offlineSearchIsOfflineAndNotCounted() = runBlocking {
    val (lib, transport) = setup(FakeClock())
    transport.offline = true
    assertEquals(YtResult.Offline, lib.search("daft punk"))
    assertEquals(100, lib.searchesRemainingToday())
  }

  @Test fun searchWithoutAccountNeedsSignInAndIsNotCounted() = runBlocking {
    val lib = newLibrary(FakeTransport())
    assertEquals(YtResult.NeedsSignIn, lib.search("daft punk"))
    assertEquals(100, lib.searchesRemainingToday())
  }

  @Test fun cacheEvictsOldestQueriesBeyondSixtyEntries() = runBlocking {
    val (lib, transport) = setup(FakeClock(ptMidnight + 60_000L))
    repeat(61) { lib.search("query $it") }
    assertTrue((lib.search("query 60") as YtResult.Ok).value.fromCache)
    assertTrue((lib.search("query 1") as YtResult.Ok).value.fromCache)
    assertFalse("oldest evicted", (lib.search("query 0") as YtResult.Ok).value.fromCache)
    assertEquals(62, transport.count("/search"))
  }

  @Test fun pacificDayKeys() {
    assertEquals("2026-11-07", YtPacificDay.key(ptMidnight - 1))
    assertEquals("2026-11-08", YtPacificDay.key(ptMidnight))
    assertEquals("2026-06-30", YtPacificDay.key(ptSummerMidnight - 1))
    assertEquals("2026-07-01", YtPacificDay.key(ptSummerMidnight))
  }
}
