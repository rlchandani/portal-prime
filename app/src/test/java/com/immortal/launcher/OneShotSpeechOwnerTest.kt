/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OneShotSpeechOwnerTest {
  private class Fixture {
    val engine = Any()
    var ready = 0
    var released = 0
    val owner = OneShotSpeechOwner<Any>({ assertTrue(it === engine); ready++ },
        { assertTrue(it === engine); released++ })
  }

  @Test fun readinessBeforeConstructorReturnsIsDelivered() {
    val f = Fixture(); f.owner.initialized(true); f.owner.created(f.engine)
    assertEquals(1, f.ready); f.owner.finish(); assertEquals(1, f.released)
  }
  @Test fun failureBeforeConstructorReturnsReleasesReturnedEngine() {
    val f = Fixture(); f.owner.initialized(false); f.owner.created(f.engine)
    assertEquals(0, f.ready); assertEquals(1, f.released)
  }
  @Test fun ordinaryReadinessDeliversOnce() {
    val f = Fixture(); f.owner.created(f.engine); f.owner.initialized(true)
    assertEquals(1, f.ready); f.owner.finish(); assertEquals(1, f.released)
  }
  @Test fun ordinaryInitFailureReleasesWithoutDelivery() {
    val f = Fixture(); f.owner.created(f.engine); f.owner.initialized(false)
    assertEquals(0, f.ready); assertEquals(1, f.released)
  }
  @Test fun CancellationBeforeAttachmentStillOwnsReturnedEngine() {
    val f = Fixture(); f.owner.finish(); f.owner.created(f.engine); f.owner.initialized(true)
    assertEquals(0, f.ready); assertEquals(1, f.released)
  }
  @Test fun duplicateInitCannotReplayAnAnnouncement() {
    val f = Fixture(); f.owner.created(f.engine); f.owner.initialized(true); f.owner.initialized(true)
    assertEquals(1, f.ready); f.owner.finish()
  }
  @Test fun doneErrorStopAndCancellationReleaseOnlyOnce() {
    val f = Fixture(); f.owner.created(f.engine); f.owner.initialized(true)
    repeat(4) { f.owner.finish() }; assertEquals(1, f.released)
  }
  @Test fun requestRejectionReleasesWithoutProgressCallback() {
    val f = Fixture(); f.owner.created(f.engine); f.owner.initialized(true); f.owner.submitted(false)
    assertEquals(1, f.released)
  }
  @Test fun acceptedSubmissionRemainsAliveUntilTerminalEvent() {
    val f = Fixture(); f.owner.created(f.engine); f.owner.initialized(true); f.owner.submitted(true)
    assertEquals(0, f.released); f.owner.finish(); assertEquals(1, f.released)
  }
  @Test fun deliveryCanCancelReentrantly() {
    var released = 0
    lateinit var owner: OneShotSpeechOwner<Any>
    owner = OneShotSpeechOwner({ owner.finish() }, { released++ })
    owner.created(Any()); owner.initialized(true); owner.finish(); assertEquals(1, released)
  }
  @Test fun failedDeliveryStillReleases() {
    var released = 0
    val owner = OneShotSpeechOwner<Any>({ error("public fixture delivery error") }, { released++ })
    owner.created(Any()); owner.initialized(true); assertEquals(1, released)
  }
  @Test fun failedReleaseIsNotRetriedByDuplicateCallbacks() {
    var released = 0
    val owner = OneShotSpeechOwner<Any>({}, { released++; error("public fixture release error") })
    owner.created(Any()); owner.initialized(false); owner.finish(); assertEquals(1, released)
  }
  @Test fun concurrentCancellationWaitsForInFlightSubmission() {
    val entered = CountDownLatch(1); val leave = CountDownLatch(1)
    val released = AtomicInteger()
    val owner = OneShotSpeechOwner<Any>({ entered.countDown(); assertTrue(leave.await(2, TimeUnit.SECONDS)) },
        { released.incrementAndGet() })
    owner.created(Any())
    val callback = Thread { owner.initialized(true) }
    callback.start()
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS)); owner.finish()
      assertEquals(0, released.get())
    } finally { leave.countDown(); callback.join(2000) }
    assertTrue(!callback.isAlive); assertEquals(1, released.get())
  }
  @Test fun concurrentDuplicateTerminalEventsHaveOneOwner() {
    val released = AtomicInteger()
    val owner = OneShotSpeechOwner<Any>({}, { released.incrementAndGet() })
    owner.created(Any()); owner.initialized(true)
    val callbacks = List(8) { Thread { repeat(100) { owner.finish() } } }
    callbacks.forEach { it.start() }; callbacks.forEach { it.join(2000) }
    assertTrue(callbacks.none { it.isAlive }); assertEquals(1, released.get())
  }
}
