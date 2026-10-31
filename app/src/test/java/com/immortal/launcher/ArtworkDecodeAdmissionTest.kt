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

class ArtworkDecodeAdmissionTest {
  @Test fun localProviderReadsAndDecodesNeverOverlap() {
    val admission = ArtworkDecodeAdmission()
    val firstEntered = CountDownLatch(1)
    val attempted = CountDownLatch(1)
    val secondEntered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val active = AtomicInteger()
    val peak = AtomicInteger()
    val workers = Executors.newFixedThreadPool(2)
    fun work(first: Boolean): Int {
      val count = active.incrementAndGet()
      peak.updateAndGet { maxOf(it, count) }
      try {
        if (first) {
          firstEntered.countDown()
          check(release.await(3, TimeUnit.SECONDS))
        } else secondEntered.countDown()
        return if (first) 1 else 2
      } finally { active.decrementAndGet() }
    }
    try {
      val first = workers.submit<Int> { admission.run { work(true) } }
      assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
      val second = workers.submit<Int> {
        attempted.countDown()
        admission.run { work(false) }
      }
      assertTrue(attempted.await(1, TimeUnit.SECONDS))
      assertFalse("a second provider must not open while the first owns decode admission",
          secondEntered.await(150, TimeUnit.MILLISECONDS))
      release.countDown()
      assertEquals(1, first.get(1, TimeUnit.SECONDS))
      assertEquals(2, second.get(1, TimeUnit.SECONDS))
      assertEquals(1, peak.get())
    } finally {
      release.countDown()
      workers.shutdownNow()
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS))
    }
  }

  @Test fun cancellingAQueuedRequestNeverOpensItsProvider() {
    val admission = ArtworkDecodeAdmission()
    val firstEntered = CountDownLatch(1)
    val queuedStarted = CountDownLatch(1)
    val queuedExited = CountDownLatch(1)
    val release = CountDownLatch(1)
    val opens = AtomicInteger()
    val workers = Executors.newFixedThreadPool(2)
    try {
      val first = workers.submit<Int> {
        admission.run {
          firstEntered.countDown()
          check(release.await(3, TimeUnit.SECONDS))
          1
        }
      }
      assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
      val queued = workers.submit<Int> {
        queuedStarted.countDown()
        try { admission.run { opens.incrementAndGet() } }
        finally { queuedExited.countDown() }
      }
      assertTrue(queuedStarted.await(1, TimeUnit.SECONDS))
      queued.cancel(true)
      assertTrue("cancelled worker must exit while the first still holds admission",
          queuedExited.await(1, TimeUnit.SECONDS))
      assertEquals(0, opens.get())
      release.countDown()
      assertEquals(1, first.get(1, TimeUnit.SECONDS))
      assertEquals(0, opens.get())
    } finally {
      release.countDown()
      workers.shutdownNow()
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS))
    }
  }

  @Test fun preInterruptedWorkerDoesNotBeginReading() {
    var read = false
    Thread.currentThread().interrupt()
    try {
      ArtworkDecodeAdmission().run { read = true }
      fail("interrupted admission must throw")
    } catch (_: InterruptedException) {
      assertFalse(read)
    } finally { Thread.interrupted() }
  }

  @Test fun failureReleasesAdmissionForAnotherWorker() {
    val admission = ArtworkDecodeAdmission()
    val failure = IllegalStateException("synthetic provider failure")
    val workers = Executors.newSingleThreadExecutor()
    try {
      try {
        admission.run { throw failure }
        fail("provider failure expected")
      } catch (actual: IllegalStateException) { assertSame(failure, actual) }
      assertEquals(42, workers.submit<Int> { admission.run { 42 } }.get(1, TimeUnit.SECONDS))
    } finally {
      workers.shutdownNow()
      assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS))
    }
  }
}
