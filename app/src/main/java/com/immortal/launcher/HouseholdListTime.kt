/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** "6:29 AM" / "yesterday" / "today 7:02 AM" wording for who/when lines. Pure; zone passed in. */
internal object HouseholdListTime {
  private fun dayStart(ms: Long, zone: TimeZone): Long =
      Calendar.getInstance(zone).apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

  private fun fmt(pattern: String, ms: Long, zone: TimeZone) =
      SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.format(Date(ms))

  /** Row meta: "just now", "6:29 AM", "yesterday", "Monday". */
  fun short(ms: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val today = dayStart(now, zone)
    return when {
      now - ms in 0 until 60_000 -> "just now"
      ms >= today -> fmt("h:mm a", ms, zone)
      ms >= dayStart(today - 1, zone) -> "yesterday"
      else -> fmt("EEEE", ms, zone)
    }
  }

  /** Details: "just now", "today 7:02 AM", "yesterday 9:10 PM", "Monday 7:14 AM". */
  fun long(ms: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val today = dayStart(now, zone)
    val time = fmt("h:mm a", ms, zone)
    return when {
      now - ms in 0 until 60_000 -> "just now"
      ms >= today -> "today $time"
      ms >= dayStart(today - 1, zone) -> "yesterday $time"
      else -> "${fmt("EEEE", ms, zone)} $time"
    }
  }
}
