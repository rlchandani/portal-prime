/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SlideshowPixelBudgetTest {
  @Test fun sharedReferencesCountOnceAndDecodeScratchIsReservedBeforeAdmission() {
    val budget = SlideshowPixelBudget(48)
    val image = Any()
    val entry = SlideshowPixelBudget.Entry(image, 12)
    budget.setOwner("stage", listOf(entry))
    budget.setOwner("history", listOf(entry))
    assertEquals(12, budget.used())
    val token = budget.reserve(24) { true }!!
    assertEquals(36, budget.used())
    budget.commit(token, "preparation", listOf(entry, SlideshowPixelBudget.Entry(Any(), 12)))
    assertEquals(24, budget.used())
  }
  @Test fun lookaheadWaitsUntilOutgoingLayerReleasesPixels() {
    val budget = SlideshowPixelBudget(48)
    val images = (0..2).map { SlideshowPixelBudget.Entry(Any(), 12) }
    budget.setOwner("live", images)
    val started = CountDownLatch(1)
    val admitted = CountDownLatch(1)
    val thread = Thread {
      started.countDown()
      val token = budget.reserve(24) { true }
      assertNotNull(token)
      admitted.countDown()
      budget.release(token!!)
    }.apply { start() }
    assertTrue(started.await(2, TimeUnit.SECONDS))
    assertFalse(admitted.await(100, TimeUnit.MILLISECONDS))
    budget.setOwner("live", images.take(2))
    assertTrue(admitted.await(2, TimeUnit.SECONDS))
    thread.join()
    assertEquals(24, budget.used())
  }
  @Test fun interruptedOrObsoleteReservationDoesNotRetainCapacity() {
    val budget = SlideshowPixelBudget(48)
    budget.setOwner("full", listOf(SlideshowPixelBudget.Entry(Any(), 48)))
    assertNull(budget.reserve(24) { false })
    assertEquals(48, budget.used())
    budget.setOwner("full", emptyList())
    assertEquals(0, budget.used())
  }
}
