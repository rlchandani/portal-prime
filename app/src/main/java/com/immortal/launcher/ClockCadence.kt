/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/** Scheduling for standalone clocks, independent of Android views and lifecycle. */
object ClockCadence {
  private const val DRIFT_INTERVAL_MS = 3_000L

  // Keep the existing seconds cadence; these faces have no blinking separator.
  fun nextClockDelayMs(nowMs: Long, showSeconds: Boolean): Long =
      if (showSeconds) 500L else untilNextBoundary(nowMs, 60_000L)

  /** Seconds ticks already wake the clock; only minute clocks need a drift timer. */
  fun nextDriftDelayMs(nowMs: Long, showSeconds: Boolean): Long? =
      if (showSeconds) null else untilNextBoundary(nowMs, DRIFT_INTERVAL_MS)

  /** Monotonic uptime keeps wall-clock/timezone changes from disturbing the throttle. */
  fun shouldApplyDrift(nowUptimeMs: Long, lastDriftUptimeMs: Long?): Boolean =
      lastDriftUptimeMs == null || nowUptimeMs - lastDriftUptimeMs >= DRIFT_INTERVAL_MS

  private fun untilNextBoundary(nowMs: Long, periodMs: Long): Long =
      periodMs - Math.floorMod(nowMs, periodMs)
}
