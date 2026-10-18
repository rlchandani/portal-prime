/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Calendar

/** Resolve the chosen wall-clock time independently on each candidate date. */
internal fun nextLocalAlarmTrigger(
    enabled: Boolean,
    hour: Int,
    minute: Int,
    days: Set<Int>,
    from: Calendar,
): Long? {
  if (!enabled) return null
  // Eight dates include next week's occurrence when today's chosen time has passed.
  for (i in 0..7) {
    val candidate = from.clone() as Calendar
    candidate.add(Calendar.DAY_OF_YEAR, i)
    // A DST gap today must not change the requested time on a later date.
    candidate.set(Calendar.HOUR_OF_DAY, hour)
    candidate.set(Calendar.MINUTE, minute)
    candidate.set(Calendar.SECOND, 0)
    candidate.set(Calendar.MILLISECOND, 0)
    if (candidate.timeInMillis <= from.timeInMillis) continue
    if (days.isEmpty() || candidate.get(Calendar.DAY_OF_WEEK) in days) {
      return candidate.timeInMillis
    }
  }
  return null
}
