/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Picture routines: calendar math on local wall-clock milliseconds (UTC millis plus the zone
 * offset). Pure and API-24 safe (no java.time): epoch days, ISO dates, Monday-first weekdays and
 * the prototype's 12-hour clock strings.
 */
internal object RoutineDates {
  const val DAY_MS = 86_400_000L
  const val MINUTE_MS = 60_000L
  const val DAY_MIN = 1440
  val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
  private val ISO = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

  fun day(localMs: Long): Int = Math.floorDiv(localMs, DAY_MS).toInt()
  fun minuteOfDay(localMs: Long): Int = (Math.floorMod(localMs, DAY_MS) / MINUTE_MS).toInt()
  fun localMs(day: Int, minuteOfDay: Int): Long = day * DAY_MS + minuteOfDay * MINUTE_MS
  /** Monday = 0 … Sunday = 6 (1970-01-01 was a Thursday). */
  fun weekday(day: Int): Int = Math.floorMod(day + 3, 7)

  fun isoOf(day: Int): String {
    val (y, m, d) = civil(day)
    return "%04d-%02d-%02d".format(y, m, d)
  }

  /** The epoch day of a strict `YYYY-MM-DD` date, or null for anything else. */
  fun epochDayOf(text: String): Int? {
    val m = ISO.matchEntire(text) ?: return null
    val (y, mo, d) = m.destructured.toList().map(String::toInt)
    if (mo !in 1..12 || d < 1 || d > daysInMonth(y, mo)) return null
    return daysFromCivil(y, mo, d)
  }

  /** "7:15" — the Portal's 12-hour clock without a suffix. */
  fun clock12(minuteOfDay: Int): String {
    val m = Math.floorMod(minuteOfDay, DAY_MIN)
    return "${(m / 60 + 11) % 12 + 1}:%02d".format(m % 60)
  }

  /** "7:15 AM" — the phone's style. */
  fun time12(minuteOfDay: Int): String =
      clock12(minuteOfDay) + if (Math.floorMod(minuteOfDay, DAY_MIN) < 720) " AM" else " PM"

  private fun daysInMonth(y: Int, m: Int): Int = when (m) {
    2 -> if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
  }

  // Howard Hinnant's days_from_civil / civil_from_days.
  private fun daysFromCivil(y0: Int, m: Int, d: Int): Int {
    val y = if (m <= 2) y0 - 1 else y0
    val era = Math.floorDiv(y, 400)
    val yoe = y - era * 400
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
  }

  private fun civil(z0: Int): Triple<Int, Int, Int> {
    val z = z0 + 719468
    val era = Math.floorDiv(z, 146097)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return Triple(yoe + era * 400 + if (m <= 2) 1 else 0, m, d)
  }
}
