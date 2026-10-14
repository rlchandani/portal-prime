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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Real IO dispatch/publication; doubles stop at the listener's cached badge snapshot. */
class ApprovedAppBadgesTest {
  @Test fun cachedBadgeReadRunsOffCallerThreadAndReturnsToCaller() = withBoundListener { service ->
    val retrievalThread = AtomicReference<Thread>()
    Mockito.`when`(service.badgeCounts()).thenAnswer {
      retrievalThread.set(Thread.currentThread())
      mapOf("fixture.chat" to 1)
    }
    runBlocking {
      val caller = Thread.currentThread()
      assertEquals(mapOf("fixture.chat" to 1), loadApprovedAppBadges())
      assertNotNull("The bound listener cache must be read", retrievalThread.get())
      assertNotSame("Cache retrieval must leave the caller thread", caller, retrievalThread.get())
      assertSame("Publication resumes on the caller", caller, Thread.currentThread())
    }
  }

  @Test fun blockedCachedBadgeReadLeavesCallerAvailableForAnotherAction() = withBoundListener { service ->
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val anotherAction = CountDownLatch(1)
    Mockito.`when`(service.badgeCounts()).thenAnswer {
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
      mapOf("fixture.chat" to 1)
    }
    Executors.newSingleThreadExecutor { Thread(it, "badge-test-caller") }
        .asCoroutineDispatcher().use { caller ->
          runBlocking {
            val read = launch(caller) { loadApprovedAppBadges() }
            var action: kotlinx.coroutines.Job? = null
            try {
              assertTrue("Cache retrieval must enter", entered.await(5, TimeUnit.SECONDS))
              action = launch(caller) { anotherAction.countDown() }
              assertTrue("The caller must remain available during retrieval",
                  anotherAction.await(1, TimeUnit.SECONDS))
            } finally {
              release.countDown()
              withTimeout(5_000) {
                read.cancelAndJoin()
                action?.cancelAndJoin()
              }
            }
          }
        }
  }

  @Test fun cancelledBlockedCacheReadCannotPublishReturnedBadges() = withBoundListener { service ->
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val retained = mapOf("fixture.retained" to 2)
    val published = AtomicReference<Map<String, Int>>(retained)
    val publications = AtomicInteger()
    Mockito.`when`(service.badgeCounts()).thenAnswer {
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
      mapOf("fixture.fresh" to 1)
    }
    Executors.newSingleThreadExecutor { Thread(it, "badge-test-caller") }
        .asCoroutineDispatcher().use { caller ->
          runBlocking {
            val read = launch(caller) {
              // Match the production assignment immediately following the suspend helper.
              published.set(loadApprovedAppBadges())
              publications.incrementAndGet()
            }
            try {
              assertTrue("Cache retrieval must enter", entered.await(5, TimeUnit.SECONDS))
              read.cancel()
              assertSame(retained, published.get())
              release.countDown()
              withTimeout(5_000) { read.join() }
              assertSame("A cancelled read must retain the previous badges", retained, published.get())
              assertEquals("A cancelled read must not publish", 0, publications.get())
            } finally {
              release.countDown()
              withTimeout(5_000) { read.cancelAndJoin() }
            }
          }
        }
  }

  @Test fun eachReadUsesTheLatestCachedSnapshot() = withBoundListener { service ->
    val initial = mapOf("fixture.chat" to 2, "fixture.music" to 1)
    val updated = mapOf("fixture.download" to 1)
    Mockito.`when`(service.badgeCounts()).thenReturn(initial, updated)
    runBlocking {
      assertSame("Reads must preserve the published cache snapshot", initial, loadApprovedAppBadges())
      assertSame("The next read must observe the latest snapshot", updated, loadApprovedAppBadges())
    }
  }

  @Test fun unboundListenerReturnsEmptyBadges() = withSelectedListener(null) {
    runBlocking { assertEquals(emptyMap<String, Int>(), loadApprovedAppBadges()) }
  }

  @Test fun unavailableCachePreservesEmptyBadgeFallback() = withBoundListener { service ->
    Mockito.`when`(service.badgeCounts()).thenThrow(SecurityException("generated-test-access-unavailable"))
    runBlocking { assertEquals(emptyMap<String, Int>(), loadApprovedAppBadges()) }
  }

  private fun withBoundListener(block: (MediaNotificationListenerService) -> Unit) {
    val service = Mockito.mock(MediaNotificationListenerService::class.java)
    withSelectedListener(service) { block(service) }
  }

  private fun withSelectedListener(service: MediaNotificationListenerService?, block: () -> Unit) {
    // Select the existing listener without invoking Android's bind/session lifecycle.
    val instance = MediaNotificationListenerService::class.java.getDeclaredField("instance")
        .apply { isAccessible = true }
    val previous = instance.get(null)
    try {
      instance.set(null, service)
      block()
    } finally {
      instance.set(null, previous)
    }
  }

}
