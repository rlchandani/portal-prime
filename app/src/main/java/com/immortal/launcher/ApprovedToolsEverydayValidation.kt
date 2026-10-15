/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Calendar
import java.util.Locale

/** Validation at the Tools save boundary; the existing Config APIs remain the storage owners. */
internal object ApprovedToolsEverydayValidation {
  /** Keep incomplete/invalid text as a draft; only validated numbers cross the save boundary. */
  fun number(value: String, min: Int, max: Int): Int? =
      value.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
          ?.toIntOrNull()?.takeIf { it in min..max }

  fun stepNumber(
      value: String, fallback: Int, min: Int, max: Int, increase: Boolean, wrap: Boolean = false,
  ): String {
    val current = number(value, min, max) ?: fallback.coerceIn(min, max)
    return (if (increase) {
      if (current < max) current + 1 else if (wrap) min else max
    } else {
      if (current > min) current - 1 else if (wrap) max else min
    }).toString()
  }

  fun countdownDraftError(
      name: String, month: String, day: String, year: String, yearly: Boolean,
  ): String? {
    countdownError(name, 1, 1, 0)?.let { return it }
    val m = number(month, 1, 12) ?: return "Enter a month from 1 to 12."
    val d = number(day, 1, 31) ?: return "Enter a day from 1 to 31."
    val y = if (yearly) 0 else number(year, 2000, 2100) ?: return "Enter a year from 2000 to 2100."
    return countdownError(name, m, d, y)
  }

  fun timerDurationMillis(hours: String, minutes: String, seconds: String): Long? {
    fun component(value: String, max: Int): Int? =
        value.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
            ?.toIntOrNull()?.takeIf { it in 0..max }
    val h = component(hours, 23) ?: return null
    val m = component(minutes, 59) ?: return null
    val s = component(seconds, 59) ?: return null
    return ((h * 3600L + m * 60L + s) * 1000L).takeIf { it > 0L }
  }

  /** A yearly date (year 0) is valid if it occurs in any year, including February 29. */
  fun isRealCountdownDate(month: Int, day: Int, year: Int): Boolean {
    if (year != 0 && year !in 2000..2100) return false
    return CountdownDates.isRealDate(month, day, year)
  }

  fun countdownError(name: String, month: Int, day: Int, year: Int): String? = when {
    name.isBlank() -> "Enter a name."
    name.trim().length > 80 -> "Use a name with 80 characters or fewer."
    !isRealCountdownDate(month, day, year) -> "Choose a valid date for this month and year."
    else -> null
  }

  /** Legacy positive years remain calculable even when outside the new-event save range. */
  fun countdownDaysUntil(month: Int, day: Int, year: Int, today: Calendar): Int? =
      CountdownDates.daysUntil(month, day, year, today)

  fun converterValue(input: String): Double? =
      parseConverterNumber(input)

  fun timerClock(remainingMillis: Long): String {
    val ms = remainingMillis.coerceAtLeast(0L)
    val seconds = ms / 1000L + if (ms % 1000L == 0L) 0L else 1L
    return if (seconds >= 3600L)
      String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
  }
}
