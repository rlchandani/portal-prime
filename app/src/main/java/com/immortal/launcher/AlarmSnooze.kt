/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.AlarmManager
import android.os.SystemClock

/** Nine-minute snooze and its existing exact-to-inexact fallback, shared with checks. */
internal object AlarmSnooze {
  const val MINUTES = 9

  fun schedule(
      exact: (Int, Long) -> Unit,
      fallback: (Int, Long) -> Unit,
      elapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
  ) {
    val at = elapsedRealtimeMillis + MINUTES * 60_000L
    runCatching { exact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at) }
        .onFailure { runCatching { fallback(AlarmManager.ELAPSED_REALTIME_WAKEUP, at) } }
  }
}
