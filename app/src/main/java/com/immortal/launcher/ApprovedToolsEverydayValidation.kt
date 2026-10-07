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
      input.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

  fun timerClock(remainingMillis: Long): String {
    val ms = remainingMillis.coerceAtLeast(0L)
    val seconds = ms / 1000L + if (ms % 1000L == 0L) 0L else 1L
    return if (seconds >= 3600L)
      String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
  }
}
