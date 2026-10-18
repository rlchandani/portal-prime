/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.AlarmManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Public elapsed-counter fixtures; no Android clocks or scheduling execute on the JVM. */
class SleepElapsedTimingTest {
  @Test
  fun grace_includesWakeInstantAndFourSeconds() {
    assertEquals(4_000L, SleepElapsedTiming.USER_WAKE_GRACE_MS)
    assertTrue(SleepElapsedTiming.recentlyWoken(10_000L, 10_000L))
    assertTrue(SleepElapsedTiming.recentlyWoken(10_000L, 14_000L))
  }

  @Test
  fun grace_expiresAfterFourSeconds() {
    assertFalse(SleepElapsedTiming.recentlyWoken(10_000L, 14_001L))
    assertFalse(SleepElapsedTiming.recentlyWoken(10_000L, 70_000L))
  }

  @Test
  fun absentWake_zeroAndNegativeNeverQualify() {
    // JVM Android clock stubs throw: these default-clock calls must short-circuit.
    assertFalse(SleepElapsedTiming.recentlyWoken(0L))
    assertFalse(SleepElapsedTiming.recentlyWoken(-1L))
    assertFalse(SleepElapsedTiming.recentlyWoken(0L, 0L))
    assertFalse(SleepElapsedTiming.recentlyWoken(0L, 1_000L))
    assertFalse(SleepElapsedTiming.recentlyWoken(-1L, 0L))
    assertFalse(SleepElapsedTiming.recentlyWoken(-1L, -1L))
  }

  @Test
  fun futureWake_isRejected() {
    assertFalse(SleepElapsedTiming.recentlyWoken(10_001L, 10_000L))
    assertFalse(SleepElapsedTiming.recentlyWoken(14_000L, 10_000L))
  }

  @Test
  fun largeUptime_preservesGraceWithoutIntTruncation() {
    assertTrue(SleepElapsedTiming.recentlyWoken(9_000_000_000L, 9_000_000_000L))
    assertTrue(SleepElapsedTiming.recentlyWoken(9_000_000_000L, 9_000_004_000L))
    assertFalse(SleepElapsedTiming.recentlyWoken(9_000_000_000L, 9_000_004_001L))
    assertFalse(SleepElapsedTiming.recentlyWoken(9_000_000_001L, 9_000_000_000L))
  }

  @Test
  fun idleDeadline_addsCanonicalMinutesToLongElapsedCounter() {
    // Fixed expectations are independent of the helper's arithmetic; no new clamping contract.
    for ((minutes, expected) in listOf(
        1 to 9_000_060_000L, 2 to 9_000_120_000L, 7 to 9_000_420_000L,
        30 to 9_001_800_000L, 60 to 9_003_600_000L)) {
      assertEquals(expected, SleepElapsedTiming.idleDeadline(minutes, 9_000_000_000L))
    }
    assertEquals(60_000L, SleepElapsedTiming.idleDeadline(1, 0L))
  }

  @Test
  fun idleAlarm_usesElapsedRealtimeWakeup() {
    assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, SleepElapsedTiming.IDLE_ALARM_TYPE)
    assertTrue(SleepElapsedTiming.IDLE_ALARM_TYPE == AlarmManager.RTC_WAKEUP ||
        SleepElapsedTiming.IDLE_ALARM_TYPE == AlarmManager.ELAPSED_REALTIME_WAKEUP)
  }

  @Test
  fun overnightClassification_respectsGraceAndExistingPrecedence() {
    val recent = SleepElapsedTiming.recentlyWoken(10_000L, 14_000L)
    val expired = SleepElapsedTiming.recentlyWoken(10_000L, 14_001L)
    assertEquals(SleepScheduler.OvernightRedream.LEAVE,
        SleepScheduler.classifyOvernightRedream(true, false, false, recent))
    assertEquals(SleepScheduler.OvernightRedream.REBLANK,
        SleepScheduler.classifyOvernightRedream(true, false, false, expired))
    for (wake in listOf(recent, expired)) {
      assertEquals(SleepScheduler.OvernightRedream.RELAUNCH,
          SleepScheduler.classifyOvernightRedream(false, false, false, wake))
      assertEquals(SleepScheduler.OvernightRedream.RELAUNCH,
          SleepScheduler.classifyOvernightRedream(true, false, true, wake))
      assertEquals(SleepScheduler.OvernightRedream.LEAVE,
          SleepScheduler.classifyOvernightRedream(true, true, true, wake))
    }
  }
}
