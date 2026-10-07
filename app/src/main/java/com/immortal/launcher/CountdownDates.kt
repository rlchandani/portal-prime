/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone

/** Pure Gregorian date math shared by Tools and the Home countdown widgets. */
internal object CountdownDates {
  private const val DAY_MS = 86_400_000L

  /** Year 0 is yearly; positive years are not restricted by a new-event editor's range. */
  fun isRealDate(month: Int, day: Int, year: Int): Boolean {
    if (month !in 1..12 || day !in 1..31 || year < 0) return false
    return midnight(if (year == 0) 2000 else year, month, day) != null
  }

  /**
   * Count local date boundaries using UTC representations of their year/month/day fields.
   * Actual local days may be shorter or longer than 24 hours. A yearly February 29 advances
   * to an actual leap day, including the eight-year gap across a non-leap century.
   *
   * Invalid legacy dates return null so callers can choose their compatibility policy.
   */
  fun daysUntil(month: Int, day: Int, year: Int, today: Calendar): Int? {
    if (!isRealDate(month, day, year)) return null
    val thisYear = today.get(Calendar.YEAR)
    val from = midnight(thisYear, today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH))
        ?: return null
    val target = if (year > 0) {
      midnight(year, month, day) ?: return null
    } else {
      (0..8).firstNotNullOfOrNull { offset ->
        val candidateYear = thisYear.toLong() + offset
        if (candidateYear > Int.MAX_VALUE) null
        else midnight(candidateYear.toInt(), month, day)?.takeIf { it >= from }
      } ?: return null
    }
    return ((target - from) / DAY_MS).toInt()
  }

  private fun midnight(year: Int, month: Int, day: Int): Long? = runCatching {
    GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
      // Interpret all positive legacy years consistently as Gregorian calendar dates.
      gregorianChange = Date(Long.MIN_VALUE)
      clear()
      isLenient = false
      set(year, month - 1, day)
    }.timeInMillis
  }.getOrNull()
}
