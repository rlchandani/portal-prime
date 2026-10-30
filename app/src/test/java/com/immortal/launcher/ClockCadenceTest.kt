/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockCadenceTest {
  @Test
  fun secondsOff_waitsForNextMinuteIncludingExactBoundary() {
    val cases = listOf(
        0L to 60_000L,
        1L to 59_999L,
        30_000L to 30_000L,
        59_999L to 1L,
        60_000L to 60_000L,
        60_001L to 59_999L,
    )
    for ((now, expected) in cases) {
      assertEquals("now=$now", expected, ClockCadence.nextClockDelayMs(now, false))
    }
  }

  @Test
  fun secondsOff_crossesHourAndDateBoundaries() {
    assertEquals(1L, ClockCadence.nextClockDelayMs(3_599_999L, false))
    assertEquals(1L, ClockCadence.nextClockDelayMs(86_399_999L, false))
    assertEquals(60_000L, ClockCadence.nextClockDelayMs(86_400_000L, false))
  }

  @Test
  fun secondsOff_realignsAfterDelayedCallbacksAndWallClockChanges() {
    assertEquals(59_877L, ClockCadence.nextClockDelayMs(120_123L, false))
    assertEquals(17_655L, ClockCadence.nextClockDelayMs(42_345L, false))
    assertEquals(17_655L, ClockCadence.nextClockDelayMs(3_642_345L, false))
    assertEquals(1L, ClockCadence.nextClockDelayMs(-1L, false))
    assertEquals(60_000L, ClockCadence.nextClockDelayMs(-60_000L, false))
  }

  @Test
  fun secondsOn_retainsExistingHalfSecondCadence() {
    for (now in listOf(-1L, 0L, 499L, 500L, 59_999L, 60_000L, Long.MAX_VALUE)) {
      assertEquals(500L, ClockCadence.nextClockDelayMs(now, true))
    }
  }

  @Test
  fun secondsOff_schedulesOneClockRefreshPerMinute() {
    var now = 12_345L
    var callbacks = 0
    while (true) {
      now += ClockCadence.nextClockDelayMs(now, false)
      if (now >= 3_612_345L) break
      callbacks++
    }
    assertEquals(60, callbacks)
  }

  @Test
  fun drift_hasItsOwnThreeSecondBoundaryWithoutZeroDelayLoops() {
    assertEquals(3_000L, ClockCadence.nextDriftDelayMs(0L, false))
    assertEquals(2_999L, ClockCadence.nextDriftDelayMs(1L, false))
    assertEquals(1L, ClockCadence.nextDriftDelayMs(2_999L, false))
    assertEquals(3_000L, ClockCadence.nextDriftDelayMs(3_000L, false))
    assertEquals(2_877L, ClockCadence.nextDriftDelayMs(6_123L, false))
    assertEquals(1L, ClockCadence.nextDriftDelayMs(-1L, false))
  }

  @Test
  fun secondsOn_driftNeverSchedulesAnotherTimer() {
    for (now in listOf(0L, 499L, 2_999L, 3_000L, 59_999L)) {
      assertNull(ClockCadence.nextDriftDelayMs(now, true))
    }
  }

  @Test
  fun reusedTick_appliesDriftOnlyAfterThreeSecondsOfUptime() {
    assertTrue(ClockCadence.shouldApplyDrift(123L, null))
    assertFalse(ClockCadence.shouldApplyDrift(123L, 123L))
    assertFalse(ClockCadence.shouldApplyDrift(3_122L, 123L))
    assertTrue(ClockCadence.shouldApplyDrift(3_123L, 123L))
    assertTrue(ClockCadence.shouldApplyDrift(9_000L, 123L))
  }

  @Test
  fun secondsOn_oneMinuteUses120ExistingTicksFor20DriftMoves() {
    var now = 0L
    var lastDrift = 0L
    var callbacks = 0
    var moves = 0
    while (now < 60_000L) {
      now += ClockCadence.nextClockDelayMs(now, true)
      callbacks++
      if (ClockCadence.shouldApplyDrift(now, lastDrift)) {
        lastDrift = now
        moves++
      }
    }
    assertEquals(120, callbacks)
    assertEquals(20, moves)
    assertNull(ClockCadence.nextDriftDelayMs(now, true))
  }
}
