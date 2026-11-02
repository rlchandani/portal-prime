/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Calendar
import java.util.TimeZone

/** Recompute the scheduler's next occurrence and round any partial minute up. */
internal fun homeAlarmRemainingMinutes(
    config: AlarmConfig.Config,
    nowMs: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
): Long? {
  if (!config.enabled) return null
  val from = Calendar.getInstance(timeZone).apply { timeInMillis = nowMs }
  val next = AlarmConfig.nextTrigger(config, from) ?: return null
  val remainingMs = next - nowMs
  return remainingMs / 60_000L + if (remainingMs % 60_000L == 0L) 0L else 1L
}

/** Wait for the next wall-clock minute, with a positive delay even before the epoch. */
internal fun homeAlarmWidgetRefreshDelayMs(nowMs: Long): Long =
    60_000L - Math.floorMod(nowMs, 60_000L)
