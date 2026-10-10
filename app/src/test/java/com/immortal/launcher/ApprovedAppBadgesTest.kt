/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Notification
import android.service.notification.StatusBarNotification
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

/** Real badge retrieval/aggregation; doubles stop at Android's blocking notification getter. */
class ApprovedAppBadgesTest {
  @Test fun notificationRetrievalRunsOffCallerThreadAndReturnsToCaller() = withBoundListener { service ->
    val retrievalThread = AtomicReference<Thread>()
    Mockito.`when`(service.activeNotifications).thenAnswer {
      retrievalThread.set(Thread.currentThread())
      arrayOf(notification("fixture.chat"))
    }
    runBlocking {
      val caller = Thread.currentThread()
      assertEquals(mapOf("fixture.chat" to 1), loadApprovedAppBadges())
      assertNotNull("The real notification getter must be read", retrievalThread.get())
      assertNotSame("Notification retrieval must leave the caller thread", caller, retrievalThread.get())
      assertSame("Publication resumes on the caller", caller, Thread.currentThread())
    }
  }

  @Test fun blockedNotificationRetrievalLeavesCallerAvailableForAnotherAction() = withBoundListener { service ->
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val anotherAction = CountDownLatch(1)
    Mockito.`when`(service.activeNotifications).thenAnswer {
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
      arrayOf(notification("fixture.chat"))
    }
    Executors.newSingleThreadExecutor { Thread(it, "badge-test-caller") }
        .asCoroutineDispatcher().use { caller ->
          runBlocking {
            val read = launch(caller) { loadApprovedAppBadges() }
            var action: kotlinx.coroutines.Job? = null
            try {
              assertTrue("Notification retrieval must enter", entered.await(5, TimeUnit.SECONDS))
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

  @Test fun cancelledBlockedRetrievalCannotPublishReturnedBadges() = withBoundListener { service ->
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val retained = mapOf("fixture.retained" to 2)
    val published = AtomicReference<Map<String, Int>>(retained)
    val publications = AtomicInteger()
    Mockito.`when`(service.activeNotifications).thenAnswer {
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
      arrayOf(notification("fixture.fresh"))
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
              assertTrue("Notification retrieval must enter", entered.await(5, TimeUnit.SECONDS))
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

  @Test fun eachReadUsesFreshNotificationsAndPreservesClearableSummaryFiltering() = withBoundListener { service ->
    Mockito.`when`(service.activeNotifications).thenReturn(
        arrayOf(
            notification("fixture.chat"),
            notification("fixture.chat"),
            notification("fixture.music"),
            notification("fixture.chat", clearable = false, flags = Notification.FLAG_ONGOING_EVENT),
            notification("fixture.music", flags = Notification.FLAG_GROUP_SUMMARY),
        ),
        arrayOf(notification("fixture.download")),
    )
    runBlocking {
      assertEquals(mapOf("fixture.chat" to 2, "fixture.music" to 1), loadApprovedAppBadges())
      assertEquals(mapOf("fixture.download" to 1), loadApprovedAppBadges())
    }
  }

  @Test fun unboundListenerReturnsEmptyBadges() = withSelectedListener(null) {
    runBlocking { assertEquals(emptyMap<String, Int>(), loadApprovedAppBadges()) }
  }

  @Test fun unavailableNotificationsPreserveEmptyBadgeFallback() = withBoundListener { service ->
    Mockito.`when`(service.activeNotifications).thenThrow(SecurityException("generated-test-access-unavailable"))
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

  private fun notification(packageName: String, clearable: Boolean = true, flags: Int = 0): StatusBarNotification {
    val notification = Mockito.mock(Notification::class.java).apply { this.flags = flags }
    return Mockito.mock(StatusBarNotification::class.java).also {
      Mockito.`when`(it.packageName).thenReturn(packageName)
      Mockito.`when`(it.isClearable).thenReturn(clearable)
      Mockito.`when`(it.notification).thenReturn(notification)
    }
  }
}
