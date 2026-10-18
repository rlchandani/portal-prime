/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.AlarmManager
import android.os.SystemClock

/** Shared idle duration and short recent-input window; calendar sleep schedules stay separate. */
internal object SleepElapsedTiming {
  const val USER_WAKE_GRACE_MS = 4000L
  const val IDLE_ALARM_TYPE = AlarmManager.ELAPSED_REALTIME_WAKEUP

  fun clockTime(): Long = SystemClock.elapsedRealtime()

  fun idleDeadline(minutes: Int, elapsedRealtimeMillis: Long = clockTime()): Long =
      elapsedRealtimeMillis + minutes * 60_000L

  /** No recorded wake: retain the original short-circuit without reading an Android clock. */
  fun recentlyWoken(lastWakeAt: Long): Boolean =
      lastWakeAt > 0L && recentlyWoken(lastWakeAt, clockTime())

  fun recentlyWoken(lastWakeAt: Long, elapsedRealtimeMillis: Long): Boolean =
      lastWakeAt > 0L && elapsedRealtimeMillis - lastWakeAt in 0..USER_WAKE_GRACE_MS
}
