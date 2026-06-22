/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerStoreTest {

  @Test
  fun running_remainingCountsDownFromEndsAt() {
    val now = 1_000_000L
    val s = TimerStore.State(running = true, endsAt = now + 90_000L, remainingMs = 120_000L)

    assertEquals(90_000L, s.remaining(now))
    assertEquals(30_000L, s.remaining(now + 60_000L))
    // Never reports negative time, and isn't considered active once it hits zero.
    assertEquals(0L, s.remaining(now + 120_000L))
    assertTrue(s.active(now))
    assertFalse(s.active(now + 90_000L))
  }

  @Test
  fun paused_remainingIsFrozenAndIndependentOfTime() {
    val s = TimerStore.State(running = false, endsAt = 0L, remainingMs = 45_000L)

    assertEquals(45_000L, s.remaining(0L))
    assertEquals(45_000L, s.remaining(10_000_000L))
    assertTrue(s.active(10_000_000L))
  }

  @Test
  fun cleared_isInactive() {
    val s = TimerStore.State()
    assertEquals(0L, s.remaining(System.currentTimeMillis()))
    assertFalse(s.active(System.currentTimeMillis()))
  }
}
