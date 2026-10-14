/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito

/** Exercise the real connection/cache lifecycle, mocking only Android capability retrieval. */
class RemoteGestureCapabilityTest {
  private lateinit var logs: MockedStatic<Log>

  @Before fun resetConnection() {
    logs = Mockito.mockStatic(Log::class.java)
    RemoteInput.unregister()
  }

  @After fun disconnect() {
    try { RemoteInput.unregister() } finally { logs.close() }
  }

  @Test fun registrationIsLazyAndOneHundredWarmCallsReadServiceInfoOnce() {
    val service = service(true)
    RemoteInput.register(service)
    Mockito.verify(service, Mockito.never()).serviceInfo
    repeat(100) { assertTrue(RemoteInput.gesturesAvailable()) }
    Mockito.verify(service, Mockito.times(1)).serviceInfo
  }

  @Test fun successfulFalseCapabilityIsCachedAndUnrelatedBitsDoNotGrantGestures() {
    val service = Mockito.mock(AccessibilityService::class.java)
    val unsupported = info(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT)
    Mockito.`when`(service.serviceInfo).thenReturn(unsupported)
    RemoteInput.register(service)
    repeat(20) { assertFalse(RemoteInput.gesturesAvailable()) }
    Mockito.verify(service, Mockito.times(1)).serviceInfo
  }

  @Test fun registeringTheSameServiceAgainStartsAFreshCapabilityEpoch() {
    val service = Mockito.mock(AccessibilityService::class.java)
    val unsupported = info(0)
    val supported = info(AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES)
    Mockito.`when`(service.serviceInfo).thenReturn(unsupported, supported)
    RemoteInput.register(service)
    assertFalse(RemoteInput.gesturesAvailable())
    RemoteInput.register(service)
    Mockito.verify(service, Mockito.times(1)).serviceInfo
    repeat(5) { assertTrue(RemoteInput.gesturesAvailable()) }
    Mockito.verify(service, Mockito.times(2)).serviceInfo
  }

  @Test fun disconnectRemovesCapabilityAndReconnectionReadsAgain() {
    val service = Mockito.mock(AccessibilityService::class.java)
    val supported = info(AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES)
    val unsupported = info(0)
    Mockito.`when`(service.serviceInfo).thenReturn(supported, unsupported)
    RemoteInput.register(service)
    assertTrue(RemoteInput.gesturesAvailable())
    RemoteInput.unregister()
    assertFalse(RemoteInput.available())
    repeat(5) { assertFalse(RemoteInput.gesturesAvailable()) }
    Mockito.verify(service, Mockito.times(1)).serviceInfo
    RemoteInput.register(service)
    repeat(5) { assertFalse(RemoteInput.gesturesAvailable()) }
    Mockito.verify(service, Mockito.times(2)).serviceInfo
  }

  @Test fun nullServiceInfoIsNotCachedAndTheNextCallCanRecover() {
    val service = Mockito.mock(AccessibilityService::class.java)
    val supported = info(AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES)
    Mockito.`when`(service.serviceInfo).thenReturn(null, supported)
    RemoteInput.register(service)
    assertFalse(RemoteInput.gesturesAvailable())
    repeat(5) { assertTrue(RemoteInput.gesturesAvailable()) }
    Mockito.verify(service, Mockito.times(2)).serviceInfo
  }

  @Test fun failedServiceInfoReadReturnsFalseWithoutPoisoningTheNextCall() {
    val service = Mockito.mock(AccessibilityService::class.java)
    val supported = info(AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES)
    Mockito.`when`(service.serviceInfo)
        .thenThrow(SecurityException("generated-capability-access-unavailable"))
        .thenReturn(supported)
    RemoteInput.register(service)
    assertFalse(RemoteInput.gesturesAvailable())
    repeat(5) { assertTrue(RemoteInput.gesturesAvailable()) }
    Mockito.verify(service, Mockito.times(2)).serviceInfo
  }

  @Test fun eightConcurrentCallersShareOneCapabilityLookup() {
    assertConcurrentLookup(8)
  }

  @Test fun sixteenConcurrentCallersShareOneCapabilityLookup() {
    assertConcurrentLookup(16)
  }

  @Test fun replacementDuringBlockedLookupRejectsOldResultAndKeepsNewCache() {
    assertBlockedConnectionChange(replace = true)
  }

  @Test fun disconnectDuringBlockedLookupRejectsOldResultAndAllowsFreshConnection() {
    assertBlockedConnectionChange(replace = false)
  }

  private fun assertConcurrentLookup(callers: Int) {
    val service = Mockito.mock(AccessibilityService::class.java)
    val capability = info(AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES)
    val ready = CountDownLatch(callers)
    val start = CountDownLatch(1)
    val attempting = CountDownLatch(callers)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val reads = AtomicInteger()
    Mockito.`when`(service.serviceInfo).thenAnswer {
      reads.incrementAndGet()
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
      capability
    }
    RemoteInput.register(service)
    val executor = Executors.newFixedThreadPool(callers)
    try {
      val results = (0 until callers).map {
        executor.submit(Callable<Boolean> {
          ready.countDown()
          check(start.await(5, TimeUnit.SECONDS))
          attempting.countDown()
          RemoteInput.gesturesAvailable()
        })
      }
      assertTrue("All callers must reach the start gate", ready.await(3, TimeUnit.SECONDS))
      start.countDown()
      assertTrue("All callers must attempt retrieval", attempting.await(3, TimeUnit.SECONDS))
      assertTrue("The shared lookup must enter", entered.await(3, TimeUnit.SECONDS))
      release.countDown()
      results.forEach { assertTrue(it.get(3, TimeUnit.SECONDS)) }
      assertEquals("One lookup per registered connection", 1, reads.get())
      assertTrue(RemoteInput.gesturesAvailable())
      assertEquals("The concurrent result remains cached", 1, reads.get())
    } finally {
      start.countDown()
      release.countDown()
      executor.shutdownNow()
      assertTrue("Capability workers must stop", executor.awaitTermination(5, TimeUnit.SECONDS))
    }
  }

  private fun assertBlockedConnectionChange(replace: Boolean) {
    val old = Mockito.mock(AccessibilityService::class.java)
    val oldCapability = info(AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES)
    val replacement = service(false)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    Mockito.`when`(old.serviceInfo).thenAnswer {
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
      oldCapability
    }
    RemoteInput.register(old)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val obsolete = executor.submit(Callable<Boolean> { RemoteInput.gesturesAvailable() })
      assertTrue("Old connection lookup must enter", entered.await(3, TimeUnit.SECONDS))
      executor.submit(Callable<Unit> {
        if (replace) RemoteInput.register(replacement) else RemoteInput.unregister()
      }).get(2, TimeUnit.SECONDS)
      assertEquals(replace, RemoteInput.available())
      assertFalse("An old blocked read must not grant capability to the current connection",
          executor.submit(Callable<Boolean> { RemoteInput.gesturesAvailable() }).get(2, TimeUnit.SECONDS))
      release.countDown()
      assertFalse("The obsolete lookup must return false", obsolete.get(3, TimeUnit.SECONDS))
      if (!replace) RemoteInput.register(replacement)
      repeat(5) { assertFalse(RemoteInput.gesturesAvailable()) }
      Mockito.verify(old, Mockito.times(1)).serviceInfo
      Mockito.verify(replacement, Mockito.times(1)).serviceInfo
    } finally {
      release.countDown()
      executor.shutdownNow()
      assertTrue("Blocked lookup workers must stop", executor.awaitTermination(5, TimeUnit.SECONDS))
    }
  }

  private fun service(gestures: Boolean): AccessibilityService =
      Mockito.mock(AccessibilityService::class.java).also { service ->
        val flags = if (gestures) AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES else 0
        val capability = info(flags)
        Mockito.`when`(service.serviceInfo).thenReturn(capability)
      }

  private fun info(capabilities: Int): AccessibilityServiceInfo =
      Mockito.mock(AccessibilityServiceInfo::class.java).also {
        Mockito.`when`(it.capabilities).thenReturn(capabilities)
      }
}
