/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YtLibraryCacheTest {
  private fun body(title: String) = """{"items":[{"id":"PL1","snippet":{"title":"$title"},"contentDetails":{"itemCount":1}}]}"""

  private class Server {
    var title = "First"
    var down = false
  }

  private fun setup(clock: FakeClock, cache: MemoryCacheStore, server: Server): Pair<YouTubeMusicLibrary, FakeTransport> {
    val transport = FakeTransport { req ->
      when {
        req.url.startsWith(YtEndpoints.TOKEN) -> ok(tokenGrant("AT", expiresIn = 86_400 * 60))
        server.down -> YtHttpResponse(503, "{}")
        else -> ok(body(server.title))
      }
    }
    return newLibrary(transport, clock, MemorySecureStore(storedAccounts("UC-A" to "RT-A")), cache) to transport
  }

  private fun titles(emissions: List<YtResult<YtLoaded<List<YtPlaylist>>>>) =
      emissions.map { r -> if (r is YtResult.Ok) r.value.value.single().title to r.value.isRefreshing else r to false }

  @Test fun freshCacheIsServedWithoutNetwork() = runBlocking {
    val clock = FakeClock()
    val cache = MemoryCacheStore()
    val server = Server()
    val (lib, transport) = setup(clock, cache, server)
    assertEquals(listOf("First" to false), titles(lib.playlists().toList()))
    clock.now += 9 * 60_000L
    server.title = "Second"
    assertEquals(listOf("First" to false), titles(lib.playlists().toList()))
    assertEquals(1, transport.count("/playlists"))
  }

  @Test fun staleCacheIsEmittedFirstThenRefreshed() = runBlocking {
    val clock = FakeClock()
    val cache = MemoryCacheStore()
    val server = Server()
    setup(clock, cache, server).first.playlists().toList()
    clock.now += 11 * 60_000L
    server.title = "Second"
    val (lib, _) = setup(clock, cache, server) // new instance: disk cache only
    assertEquals(listOf("First" to true, "Second" to false), titles(lib.playlists().toList()))
  }

  @Test fun failedRefreshAfterStaleCacheReportsTheError() = runBlocking {
    val clock = FakeClock()
    val cache = MemoryCacheStore()
    val server = Server()
    val (lib, _) = setup(clock, cache, server)
    lib.playlists().toList()
    clock.now += 11 * 60_000L
    server.down = true
    val out = lib.playlists().toList()
    assertEquals("First" to true, titles(out).first())
    assertEquals(YtResult.Failed(503, YtProblem.SERVER), out.last())
  }

  @Test fun forceRefreshBypassesAFreshCache() = runBlocking {
    val clock = FakeClock()
    val server = Server()
    val (lib, transport) = setup(clock, MemoryCacheStore(), server)
    lib.playlists().toList()
    server.title = "Second"
    assertEquals(listOf("First" to true, "Second" to false), titles(lib.playlists(forceRefresh = true).toList()))
    assertEquals(2, transport.count("/playlists"))
  }

  @Test fun entriesOlderThanThirtyDaysAreDiscarded() = runBlocking {
    val clock = FakeClock()
    val cache = MemoryCacheStore()
    val server = Server()
    setup(clock, cache, server).first.playlists().toList()
    clock.now += 30L * 86_400_000L + 1
    server.down = true
    val out = setup(clock, cache, server).first.playlists().toList()
    assertEquals(listOf(YtResult.Failed(503, YtProblem.SERVER)), out)
    assertTrue("expired entry deleted", cache.entries.keys.none { it.endsWith("playlists") })
  }

  @Test fun cacheIsPerAccount() = runBlocking {
    val clock = FakeClock()
    val cache = MemoryCacheStore()
    val server = Server()
    val (lib, _) = setup(clock, cache, server)
    lib.playlists().toList()
    assertTrue(cache.entries.keys.any { it.startsWith("a.UC-A.") })
  }

  @Test fun fileCacheStoreRoundTripsAndDeletesByPrefix() {
    val dir = Files.createTempDirectory("ytcache").toFile()
    try {
      val store = YtFileCacheStore(dir)
      store.write("a.UC-A.playlists", "one")
      store.write("a.UC-A.items.PL/../x", "two")
      store.write("a.UC-B.playlists", "three")
      assertEquals("one", store.read("a.UC-A.playlists"))
      assertEquals("two", store.read("a.UC-A.items.PL/../x"))
      assertTrue(dir.listFiles()!!.all { it.parentFile == dir })
      store.deletePrefix("a.UC-A.")
      assertNull(store.read("a.UC-A.playlists"))
      assertNull(store.read("a.UC-A.items.PL/../x"))
      assertEquals("three", store.read("a.UC-B.playlists"))
      assertNull(store.read("missing"))
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test fun fileCacheStoreToleratesMissingDirectory() {
    val store = YtFileCacheStore(File("/nonexistent-yt-cache-dir/sub"))
    assertNull(store.read("k"))
    store.write("k", "v") // best effort, no throw
    store.deletePrefix("k")
  }
}
