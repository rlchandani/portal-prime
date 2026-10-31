/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ArtworkLoadCoordinatorTest {
  @Test fun cachedCoverReturnsWhileAnotherDownloadIsStillBlocked() {
    val coordinator = ArtworkLoadCoordinator()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    val ready = Any()
    try {
      val slow = executor.submit<Any?> {
        coordinator.fetch({ null }, {
          entered.countDown()
          check(release.await(3, TimeUnit.SECONDS))
          Any()
        }, {})
      }
      assertTrue(entered.await(1, TimeUnit.SECONDS))
      val cached = executor.submit<Any?> {
        coordinator.fetch({ ready }, {
          throw AssertionError("A cached cover must not load")
        }, { throw AssertionError("A cached cover must not publish") })
      }
      assertSame(ready, cached.get(500, TimeUnit.MILLISECONDS))
      assertFalse("slow download must still be held", slow.isDone)
      release.countDown()
      assertNotNull(slow.get(1, TimeUnit.SECONDS))
    } finally {
      release.countDown()
      executor.shutdownNow()
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }
  }

  @Test fun concurrentRequestsForTheSameCoverLoadOnlyOnce() {
    val coordinator = ArtworkLoadCoordinator()
    val cache = ConcurrentHashMap<String, Any>()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val secondMiss = CountDownLatch(1)
    val loads = AtomicInteger()
    val executor = Executors.newFixedThreadPool(2)
    val cover = Any()
    fun fetch(second: Boolean = false) = coordinator.fetch({
      cache["cover"].also { if (second && it == null) secondMiss.countDown() }
    }, {
      loads.incrementAndGet()
      entered.countDown()
      check(release.await(3, TimeUnit.SECONDS))
      cover
    }, { cache["cover"] = it })
    try {
      val first = executor.submit<Any?> { fetch() }
      assertTrue(entered.await(1, TimeUnit.SECONDS))
      val second = executor.submit<Any?> { fetch(second = true) }
      assertTrue("second request must miss before the first publishes",
          secondMiss.await(1, TimeUnit.SECONDS))
      release.countDown()
      assertSame(cover, first.get(1, TimeUnit.SECONDS))
      assertSame(cover, second.get(1, TimeUnit.SECONDS))
      assertEquals(1, loads.get())
    } finally {
      release.countDown()
      executor.shutdownNow()
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }
  }

  @Test fun differentMissingCoversNeverLoadConcurrently() {
    val coordinator = ArtworkLoadCoordinator()
    val entered = CountDownLatch(1)
    val attempted = CountDownLatch(1)
    val secondLoadEntered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val active = AtomicInteger()
    val peak = AtomicInteger()
    val executor = Executors.newFixedThreadPool(2)
    fun load(blocked: Boolean): Any {
      val count = active.incrementAndGet()
      peak.updateAndGet { maxOf(it, count) }
      try {
        if (blocked) {
          entered.countDown()
          check(release.await(3, TimeUnit.SECONDS))
        } else secondLoadEntered.countDown()
        return Any()
      } finally { active.decrementAndGet() }
    }
    try {
      val first = executor.submit<Any?> { coordinator.fetch({ null }, { load(true) }, {}) }
      assertTrue(entered.await(1, TimeUnit.SECONDS))
      val second = executor.submit<Any?> {
        attempted.countDown()
        coordinator.fetch({ null }, { load(false) }, {})
      }
      assertTrue(attempted.await(1, TimeUnit.SECONDS))
      assertFalse("missing covers must retain serialized decoding",
          secondLoadEntered.await(150, TimeUnit.MILLISECONDS))
      release.countDown()
      assertNotNull(first.get(1, TimeUnit.SECONDS))
      assertNotNull(second.get(1, TimeUnit.SECONDS))
      assertEquals(1, peak.get())
    } finally {
      release.countDown()
      executor.shutdownNow()
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }
  }

  @Test fun failedLoadDoesNotPublishOrPreventTheNextAttempt() {
    val coordinator = ArtworkLoadCoordinator()
    var publications = 0
    assertNull(coordinator.fetch<Any>({ null }, { null }, { publications++ }))
    val cover = Any()
    assertSame(cover, coordinator.fetch({ null }, { cover }, { publications++ }))
    assertEquals(1, publications)
  }

  @Test fun thrownLoaderReleasesAdmissionForOtherRequests() {
    val coordinator = ArtworkLoadCoordinator()
    val failure = IllegalStateException("synthetic decode failure")
    try {
      coordinator.fetch<Any>({ null }, { throw failure }, {
        throw AssertionError("Failed artwork must not publish")
      })
      fail("loader exception expected")
    } catch (actual: IllegalStateException) {
      assertSame(failure, actual)
    }
    val cover = Any()
    assertSame(cover, coordinator.fetch({ null }, { cover }, {}))
  }
}
