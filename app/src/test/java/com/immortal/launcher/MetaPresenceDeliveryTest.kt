/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class MetaPresenceDeliveryTest {
  private class Fixture {
    var time = 0L
    val events = mutableListOf<Boolean>()
    val delivery = MetaPresenceDelivery(MetaPresence.Tracker(), { time }, events::add)
    fun signal(signal: MetaPresence.Signal, at: Long) {
      time = at
      delivery.onSignal(signal)
    }
    fun sample(at: Long) {
      time = at
      delivery.sample()
    }
  }

  private fun withDelivery(test: (Fixture) -> Unit) {
    val fixture = Fixture()
    fixture.delivery.start()
    try { test(fixture) } finally { fixture.delivery.stop() }
  }

  @Test fun silentIdleBecomesAbsentAfterTheExistingGrace() = withDelivery { f ->
    f.signal(MetaPresence.Signal.BEAT, 0)
    f.signal(MetaPresence.Signal.IDLE, 10_000)
    f.sample(10_000 + MetaPresence.IDLE_GRACE_MS)
    assertEquals(listOf(true), f.events)
    f.sample(10_000 + MetaPresence.IDLE_GRACE_MS + 1)
    assertEquals(listOf(true, false), f.events)
  }

  @Test fun aFreshBeatCancelsPendingIdle() = withDelivery { f ->
    f.signal(MetaPresence.Signal.BEAT, 0)
    f.signal(MetaPresence.Signal.IDLE, 10_000)
    f.signal(MetaPresence.Signal.BEAT, 20_000)
    f.sample(10_000 + MetaPresence.IDLE_GRACE_MS + 1)
    assertEquals(listOf(true), f.events)
  }

  @Test fun expiredBeatWithoutIdleNeverPublishesAbsence() = withDelivery { f ->
    f.signal(MetaPresence.Signal.BEAT, 0)
    f.sample(MetaPresence.BEAT_EXPIRY_MS + 1)
    assertEquals(listOf(true), f.events)
  }

  @Test fun idleWithoutAPriorBeatNeverPublishesAbsence() = withDelivery { f ->
    f.signal(MetaPresence.Signal.IDLE, 10_000)
    f.sample(10_000 + MetaPresence.IDLE_GRACE_MS + 1)
    assertEquals(emptyList<Boolean>(), f.events)
  }

  @Test fun repeatedSamplesDoNotRepublishTheSameVerdict() = withDelivery { f ->
    f.signal(MetaPresence.Signal.BEAT, 0)
    f.signal(MetaPresence.Signal.IDLE, 10_000)
    repeat(3) { f.sample(10_000 + MetaPresence.IDLE_GRACE_MS + 1) }
    assertEquals(listOf(true, false), f.events)
  }

  @Test fun stoppedDeliveryRejectsSignalsAndSamples() = withDelivery { f ->
    f.signal(MetaPresence.Signal.BEAT, 0)
    f.signal(MetaPresence.Signal.IDLE, 10_000)
    f.delivery.stop()
    f.sample(10_000 + MetaPresence.IDLE_GRACE_MS + 1)
    f.signal(MetaPresence.Signal.BEAT, 100_000)
    assertEquals(listOf(true), f.events)
  }

  @Test fun aRetiredTimerCannotSampleANewRun() = withDelivery { f ->
    f.signal(MetaPresence.Signal.BEAT, 0)
    f.signal(MetaPresence.Signal.IDLE, 10_000)
    f.delivery.stop()
    f.delivery.start()
    f.time = 10_000 + MetaPresence.IDLE_GRACE_MS + 1
    f.delivery.sample(owner = 1L)
    assertEquals(listOf(true), f.events)
    f.delivery.sample()
    assertEquals(listOf(true, false), f.events)
  }
}
