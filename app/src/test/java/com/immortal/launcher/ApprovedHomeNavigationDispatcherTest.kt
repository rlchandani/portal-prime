/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ApprovedHomeNavigationDispatcherTest {
  @Test fun onlyAResumedFocusedLiveHostIsEligible() {
    assertTrue(approvedRetainedHomeEligible(true, true, false, false, false))
    assertFalse(approvedRetainedHomeEligible(false, true, false, false, false))
    assertFalse(approvedRetainedHomeEligible(true, false, false, false, false))
    assertFalse(approvedRetainedHomeEligible(true, true, true, false, false))
    assertFalse(approvedRetainedHomeEligible(true, true, false, true, false))
    assertFalse(approvedRetainedHomeEligible(true, true, false, false, true))
  }

  @Test fun oldPauseOrDestructionCannotUnregisterReplacementHost() {
    val hosts = ApprovedHomeHostRegistry<Any>()
    val old = Any()
    val next = Any()
    hosts.resumed(old)
    val stale = hosts.capture()
    hosts.resumed(next)
    hosts.unavailable(old)
    assertNull(hosts.foreground(stale) { true })
    assertSame(next, hosts.foreground(hosts.capture()) { true })
    hosts.unavailable(next)
    assertNull(hosts.foreground(hosts.capture()) { true })
  }

  @Test fun pauseAndResumeOfTheSameActivityInvalidatesTheOldRegistration() {
    val hosts = ApprovedHomeHostRegistry<Any>()
    val home = Any()
    hosts.resumed(home)
    val beforePause = hosts.capture()
    // Eligibility must not accept a request prepared against an earlier foreground session.
    assertNull(hosts.foreground(beforePause) {
      hosts.unavailable(home)
      hosts.resumed(home)
      true
    })
    assertSame(home, hosts.foreground(hosts.capture()) { true })
  }

  @Test fun replacementDuringEligibilityCheckInvalidatesCapturedHost() {
    val hosts = ApprovedHomeHostRegistry<Any>()
    val old = Any()
    hosts.resumed(old)
    assertNull(hosts.foreground(hosts.capture()) { hosts.resumed(Any()); true })
  }

  @Test fun expiredQueuedRequestCannotPrepareOrApplyEvenIfRemovedCallbackRuns() {
    var preparations = 0
    var actions = 0
    val request = ApprovedHomeNavigationRequest(System.nanoTime() - 1) {
      preparations++
      val action: () -> Boolean = { actions++; true }
      action
    }
    assertFalse(request.await())
    request.run()
    assertEquals(0, preparations)
    assertEquals(0, actions)
  }

  @Test fun cancelledQueuedRequestCannotApplyOrFallback() {
    var actions = 0
    val request = ApprovedHomeNavigationRequest(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)) {
      val action: () -> Boolean = { actions++; true }
      action
    }
    assertFalse(request.cancel())
    request.run()
    assertFalse(request.await())
    assertEquals(0, actions)
  }

  @Test fun timedOutPreparationCannotBeginNavigationLater() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    var actions = 0
    val request = ApprovedHomeNavigationRequest(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)) {
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
      val action: () -> Boolean = { actions++; true }
      action
    }
    val worker = Thread { request.run() }.apply { start() }
    try {
      assertTrue(entered.await(1, TimeUnit.SECONDS))
      assertFalse(request.await())
    } finally {
      release.countDown()
      worker.join(2_000)
    }
    assertFalse(worker.isAlive)
    request.run()
    assertEquals(0, actions)
  }

  @Test fun begunNavigationIsAcceptedAtDeadlineAndNeverRunsTwice() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    var actions = 0
    val request = ApprovedHomeNavigationRequest(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)) {
      val action: () -> Boolean = {
        actions++
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
        true
      }
      action
    }
    val worker = Thread { request.run() }.apply { start() }
    try {
      assertTrue(entered.await(1, TimeUnit.SECONDS))
      assertTrue("An action already begun must not invite a duplicate retry", request.await())
      request.run()
      assertEquals(1, actions)
    } finally {
      release.countDown()
      worker.join(2_000)
    }
    assertTrue(request.await())
    assertEquals(1, actions)
  }

  @Test fun interruptedWaitCancelsPendingWorkAndPreservesInterrupt() {
    var actions = 0
    val request = ApprovedHomeNavigationRequest(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)) {
      val action: () -> Boolean = { actions++; true }
      action
    }
    Thread.currentThread().interrupt()
    try {
      assertFalse(request.await())
      assertTrue(Thread.currentThread().isInterrupted)
    } finally { Thread.interrupted() }
    request.run()
    assertEquals(0, actions)
  }

  @Test fun failedActionIsNotRetriedAsFallback() {
    var actions = 0
    val request = ApprovedHomeNavigationRequest(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)) {
      val action: () -> Boolean = { actions++; throw SecurityException("denied") }
      action
    }
    request.run()
    assertFalse(request.await())
    request.run()
    assertEquals(1, actions)
  }
}
