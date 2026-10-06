/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ApprovedHomeInventorySnapshotTest {
  @Test fun simultaneousHomeAndAppsLoads_scanOnceAndShareTheCompleteSnapshot() {
    val cache = HomePresentationInventoryCache<String>()
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val secondStarted = CountDownLatch(1)
    val duplicateScan = CountDownLatch(1)
    val scans = AtomicInteger()
    val executor = Executors.newFixedThreadPool(2)
    try {
      val home = executor.submit<List<String>> {
        cache.load {
          scans.incrementAndGet()
          firstStarted.countDown()
          check(releaseFirst.await(5, TimeUnit.SECONDS))
          listOf("Browser", "VLC")
        }
      }
      assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
      val apps = executor.submit<List<String>> {
        secondStarted.countDown()
        cache.load {
          scans.incrementAndGet()
          duplicateScan.countDown()
          listOf("Browser", "VLC")
        }
      }
      assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
      assertFalse("Apps must join Home's in-flight scan", duplicateScan.await(200, TimeUnit.MILLISECONDS))
      releaseFirst.countDown()
      assertSame(home.get(2, TimeUnit.SECONDS), apps.get(2, TimeUnit.SECONDS))
      assertEquals(1, scans.get())
    } finally {
      releaseFirst.countDown()
      executor.shutdownNow()
    }
  }

  @Test fun repeatedLoads_reuseListAndIconIdentityUntilExplicitInvalidation() {
    val cache = HomePresentationInventoryCache<Any>()
    var scans = 0
    val loader = { scans++; listOf(Any()) }
    val first = cache.load(loader)
    repeat(20) { assertSame(first, cache.load(loader)) }
    assertSame(first, cache.peek())
    assertEquals(1, scans)
    cache.invalidate()
    assertNull(cache.peek())
    val second = cache.load(loader)
    assertNotSame(first.first(), second.first())
    assertEquals(2, scans)
  }

  @Test fun loaderListMutation_cannotChangePublishedInventory() {
    val cache = HomePresentationInventoryCache<String>()
    val input = mutableListOf("Browser", "VLC")
    val first = cache.load { input }
    input.clear()
    assertEquals(listOf("Browser", "VLC"), first)
  }

  @Test fun packageInvalidation_neverWaitsForSlowLoaderAndRejectsStaleResult() {
    val cache = HomePresentationInventoryCache<String>()
    val started = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      var scans = 0
      val loading = executor.submit<List<String>> {
        cache.load {
          scans++
          if (scans == 1) {
            started.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            listOf("Removed package")
          } else listOf("New package")
        }
      }
      assertTrue(started.await(5, TimeUnit.SECONDS))
      // This completes while the loader is blocked; holding the lock during IO would time out.
      executor.submit { cache.invalidate() }.get(2, TimeUnit.SECONDS)
      finish.countDown()
      assertEquals(listOf("New package"), loading.get(5, TimeUnit.SECONDS))
      assertEquals(listOf("New package"), cache.peek())
      assertEquals(2, scans)
    } finally {
      finish.countDown()
      executor.shutdownNow()
    }
  }
}
