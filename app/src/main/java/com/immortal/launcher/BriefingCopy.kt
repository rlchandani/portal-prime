/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Hearthpane wording for the morning briefing. Pure (no Android) so the JVM tests cover it. */
internal object BriefingCopy {
  const val DEFAULT_GREETING = "Good morning"
  const val CARD_TITLE = "Good morning"
  const val CARD_SUBTITLE = "Your morning briefing"
  const val DISMISS = "Dismiss"
  const val NOON_MINUTE = 12 * 60

  private const val MS_PER_MINUTE = 60_000L
  private const val MINUTES_PER_HOUR = 60

  /** Weather code (WMO, Open-Meteo) to a short description. */
  fun condition(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mostly clear"
    2 -> "Partly cloudy"
    3 -> "Cloudy"
    in 45..48 -> "Foggy"
    in 51..57 -> "Drizzle"
    in 61..67 -> "Rain"
    in 71..77 -> "Snow"
    in 80..82 -> "Showers"
    in 85..86 -> "Snow showers"
    in 95..99 -> "Thunderstorms"
    else -> "Mixed weather"
  }

  fun clock(ms: Long, zone: TimeZone, locale: Locale, use24Hour: Boolean): String =
      SimpleDateFormat(if (use24Hour) "H:mm" else "h:mm a", locale).apply { timeZone = zone }.format(Date(ms))

  fun clockOfMinute(minute: Int, use24Hour: Boolean): String {
    val h = minute / MINUTES_PER_HOUR
    val m = minute % MINUTES_PER_HOUR
    if (use24Hour) return "%d:%02d".format(Locale.ROOT, h, m)
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "%d:%02d %s".format(Locale.ROOT, h12, m, if (h < 12) "AM" else "PM")
  }

  fun dayLine(ms: Long, zone: TimeZone, locale: Locale): String =
      SimpleDateFormat("EEEE, MMMM d", locale).apply { timeZone = zone }.format(Date(ms))

  /** "4 min left", "1 hr 5 min left", "Less than a minute left". */
  fun remaining(ms: Long): String {
    if (ms < MS_PER_MINUTE) return "Less than a minute left"
    val minutes = (ms + MS_PER_MINUTE - 1) / MS_PER_MINUTE
    val h = minutes / MINUTES_PER_HOUR
    val m = minutes % MINUTES_PER_HOUR
    return when {
      h == 0L -> "$m min left"
      m == 0L -> "$h hr left"
      else -> "$h hr $m min left"
    }
  }

  /** Spoken form of [remaining]: "4 minutes", "1 hour and 5 minutes". */
  fun spokenRemaining(ms: Long): String {
    if (ms < MS_PER_MINUTE) return "less than a minute"
    val minutes = (ms + MS_PER_MINUTE - 1) / MS_PER_MINUTE
    val h = minutes / MINUTES_PER_HOUR
    val m = minutes % MINUTES_PER_HOUR
    val hours = plural(h, "hour")
    val mins = plural(m, "minute")
    return when {
      h == 0L -> mins
      m == 0L -> hours
      else -> "$hours and $mins"
    }
  }

  fun plural(n: Long, word: String): String = if (n == 1L) "1 $word" else "$n ${word}s"

  fun plural(n: Int, word: String): String = plural(n.toLong(), word)

  /** "milk", "milk and eggs", "milk, eggs and bread". */
  fun joinNames(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
  }

  /** "Good morning, Sam" / "Good morning" from the Welcome Greeting phrase and name. */
  fun greeting(phrase: String, name: String): String {
    val base = phrase.trim().trimEnd('.', '!').ifBlank { DEFAULT_GREETING }
    return if (name.isBlank()) base else "$base, ${name.trim()}"
  }
}
