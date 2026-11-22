/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import java.util.Calendar

/**
 * The alarm's repeat days as Jarvis says them ("weekdays", "mon, wed and fri", "once") ⇄ Hearthpane's
 * `Calendar.SUNDAY..SATURDAY` set (empty = one-off). Pure.
 */
internal object AlarmDays {
  val WEEKDAYS = (Calendar.MONDAY..Calendar.FRIDAY).toSet()
  val WEEKENDS = setOf(Calendar.SATURDAY, Calendar.SUNDAY)
  val EVERY_DAY = (Calendar.SUNDAY..Calendar.SATURDAY).toSet()
  private val NAMES = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
  private val SHORT = mapOf("sun" to 1, "mon" to 2, "tue" to 3, "tues" to 3, "wed" to 4, "thu" to 5, "thur" to 5,
      "thurs" to 5, "fri" to 6, "sat" to 7)
  private val ONCE = setOf("", "once", "one off", "one-off", "one time", "never", "no", "none", "just once")
  private val FILLER = setOf("and", "on", "every", "each", "s")

  /** Null when the words name no day we understand. */
  fun parse(raw: String?): Set<Int>? {
    val text = raw?.lowercase()?.trim().orEmpty()
    if (text in ONCE) return emptySet()
    when (text.removePrefix("every ").removePrefix("on ").trim()) {
      "weekday", "weekdays", "workday", "workdays", "weekday mornings", "monday to friday", "mon-fri" -> return WEEKDAYS
      "weekend", "weekends" -> return WEEKENDS
      "day", "daily", "everyday", "every day", "all days", "all week" -> return EVERY_DAY
    }
    val tokens = text.split(Regex("[^a-z]+")).filter { it.isNotEmpty() && it !in FILLER }
    if (tokens.isEmpty()) return null
    val days = tokens.map { day(it) ?: return null }
    return days.toSet()
  }

  private fun day(token: String): Int? {
    val t = token.removeSuffix("s").ifEmpty { token }
    NAMES.indexOf(t).takeIf { it >= 0 }?.let { return it + 1 }
    return SHORT[t] ?: SHORT[token]
  }

  /** "Monday" for [Calendar.MONDAY]. */
  fun name(day: Int): String = NAMES[(day - 1).coerceIn(0, NAMES.size - 1)].replaceFirstChar(Char::uppercase)

  /** "weekdays", "every day", "Mondays and Thursdays"; "" for a one-off. */
  fun spoken(days: Set<Int>): String = when (days) {
    emptySet<Int>() -> ""
    EVERY_DAY -> "every day"
    WEEKDAYS -> "on weekdays"
    WEEKENDS -> "on weekends"
    else -> "on " + HearthpaneLists.spoken(days.sortedBy { (it + 5) % 7 }.map { name(it) + "s" })
  }
}
