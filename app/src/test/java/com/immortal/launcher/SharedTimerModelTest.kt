/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure state changes: each returns a new timer one sequence step ahead, or null when it doesn't apply. */
class SharedTimerModelTest {
  private val start = 1_000_000L
  private val base = SharedTimerOps.create("a1b2c3d4e5f6", "Pasta", "Kitchen", "kitchen-id", 600_000L, start, "kitchen-id")!!

  @Test fun createStartsRunningAtSequenceOne() {
    assertEquals(SharedTimerState.RUNNING, base.state)
    assertEquals(1L, base.seq)
    assertEquals(start + 600_000L, base.endAtMs)
    assertEquals(600_000L, base.remaining(start))
    assertEquals(0L, base.remaining(start + 700_000L))
  }

  @Test fun createRejectsDurationsOutOfRangeAndNamesBlankAsTimer() {
    assertNull(SharedTimerOps.create("a1b2c3d4", "x", "K", "o", 0L, start, "o"))
    assertNull(SharedTimerOps.create("a1b2c3d4", "x", "K", "o", SharedTimer.MAX_DURATION_MS + 1, start, "o"))
    assertEquals("Timer", SharedTimerOps.create("a1b2c3d4", " ", "K", "o", 1_000L, start, "o")!!.name)
  }

  @Test fun pauseFreezesRemainingAndResumeReanchors() {
    val paused = SharedTimerOps.pause(base, start + 100_000L, "den")!!
    assertEquals(SharedTimerState.PAUSED, paused.state)
    assertEquals(500_000L, paused.remaining(start + 900_000L))
    assertEquals(2L, paused.seq)
    assertEquals("den", paused.by)
    assertNull("pausing twice does nothing", SharedTimerOps.pause(paused, start, "den"))
    val resumed = SharedTimerOps.resume(paused, start + 300_000L, "den")!!
    assertEquals(start + 800_000L, resumed.endAtMs)
    assertEquals(3L, resumed.seq)
    assertNull(SharedTimerOps.resume(resumed, start, "den"))
  }

  @Test fun addMinuteWorksWhileRunningPausedOrRinging() {
    val running = SharedTimerOps.addMinute(base, start + 100_000L, "k")!!
    assertEquals(560_000L, running.remaining(start + 100_000L))
    assertEquals(660_000L, running.durationMs)
    val paused = SharedTimerOps.addMinute(SharedTimerOps.pause(base, start, "k")!!, start, "k")!!
    assertEquals(SharedTimerState.PAUSED, paused.state)
    assertEquals(660_000L, paused.remainingMs)
    val ringing = SharedTimerOps.ring(base, start + 600_000L, "k")!!
    val again = SharedTimerOps.addMinute(ringing, start + 610_000L, "k")!!
    assertEquals(SharedTimerState.RUNNING, again.state)
    assertEquals(60_000L, again.remaining(start + 610_000L))
  }

  @Test fun ringThenStopEndsAndOnlyRingingTimersStop() {
    assertNull("a running timer is cancelled, not stopped", SharedTimerOps.stop(base, start, "k"))
    val ringing = SharedTimerOps.ring(base, start + 600_000L, "k")!!
    assertEquals(0L, ringing.remaining(start + 600_000L))
    assertNull(SharedTimerOps.ring(ringing, start, "k"))
    val done = SharedTimerOps.stop(ringing, start + 601_000L, "den")!!
    assertEquals(SharedTimerState.DONE, done.state)
    assertTrue(!done.state.live)
  }

  @Test fun cancelEndsAnyLiveTimerButNotAnEndedOne() {
    val cancelled = SharedTimerOps.cancel(base, start, "k")!!
    assertEquals(SharedTimerState.CANCELLED, cancelled.state)
    assertNull(SharedTimerOps.cancel(cancelled, start, "k"))
    assertNotNull(SharedTimerOps.cancel(SharedTimerOps.ring(base, start, "k")!!, start, "k"))
  }

  @Test fun operationsNeverMutateTheirInput() {
    val before = base.copy()
    SharedTimerOps.pause(base, start + 1, "x")
    SharedTimerOps.addMinute(base, start + 1, "x")
    assertEquals(before, base)
  }
}
