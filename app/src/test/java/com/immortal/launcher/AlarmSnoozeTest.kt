/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.AlarmManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** Public elapsed-time fixtures only; every call bypasses the Android default clock. */
class AlarmSnoozeTest {
  @Test fun schedulesNineMinutesFromElapsedTimeWithWakeupType() {
    val calls = mutableListOf<Pair<Int, Long>>()
    AlarmSnooze.schedule({ type, at -> calls += type to at }, { _, _ -> }, 10_000L)
    assertEquals(listOf(AlarmManager.ELAPSED_REALTIME_WAKEUP to 550_000L), calls)
  }

  @Test fun exactSuccessDoesNotInvokeFallback() {
    var exactCalls = 0
    var fallbackCalls = 0
    AlarmSnooze.schedule({ _, _ -> exactCalls++ }, { _, _ -> fallbackCalls++ }, 10_000L)
    assertEquals(1, exactCalls)
    assertEquals(0, fallbackCalls)
  }

  @Test fun exactFailureInvokesFallbackAfterExactWithIdenticalDeadline() {
    val order = mutableListOf<String>()
    val calls = mutableListOf<Pair<Int, Long>>()
    AlarmSnooze.schedule({ type, at ->
      order += "exact"
      calls += type to at
      throw SecurityException()
    }, { type, at ->
      order += "fallback"
      calls += type to at
    }, 10_000L)
    assertEquals(listOf("exact", "fallback"), order)
    assertEquals(listOf(AlarmManager.ELAPSED_REALTIME_WAKEUP to 550_000L,
        AlarmManager.ELAPSED_REALTIME_WAKEUP to 550_000L), calls)
  }

  @Test fun bothCallbackFailuresAreContainedWithoutRetries() {
    val order = mutableListOf<String>()
    AlarmSnooze.schedule({ _, _ ->
      order += "exact"
      throw SecurityException()
    }, { _, _ ->
      order += "fallback"
      throw IllegalStateException()
    }, 10_000L)
    assertEquals(listOf("exact", "fallback"), order)
  }

  @Test fun repeatedCallsRemainIndependentAfterFailureAndForRepeatedUptime() {
    val order = mutableListOf<String>()
    val deadlines = mutableListOf<Long>()
    AlarmSnooze.schedule({ _, at ->
      order += "exact"
      deadlines += at
      throw SecurityException()
    }, { _, at ->
      order += "fallback"
      deadlines += at
      throw IllegalStateException()
    }, 1_000L)
    for (uptime in listOf(2_000L, 1_000L)) {
      AlarmSnooze.schedule({ _, at -> order += "exact"; deadlines += at },
          { _, _ -> order += "fallback" }, uptime)
    }
    assertEquals(listOf("exact", "fallback", "exact", "exact"), order)
    assertEquals(listOf(541_000L, 541_000L, 542_000L, 541_000L), deadlines)
  }

  @Test fun uptimeBeyondIntRangeRetainsLongDeadline() {
    val calls = mutableListOf<Pair<Int, Long>>()
    AlarmSnooze.schedule({ type, at -> calls += type to at }, { _, _ -> }, 5_000_000_000L)
    assertEquals(listOf(AlarmManager.ELAPSED_REALTIME_WAKEUP to 5_000_540_000L), calls)
  }
}
