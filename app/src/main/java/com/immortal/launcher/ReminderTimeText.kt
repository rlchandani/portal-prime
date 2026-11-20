/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Reads a typed or spoken clock time: "18:30", "6:30 pm", "6pm", "6 PM", "0630", "noon",
 * "midnight". A bare "6" is 6:00 (24-hour); Jarvis resolves "at 6" to a 24-hour time itself. Pure.
 */
internal object ReminderTimeText {
  private val CLOCK = Regex("^(\\d{1,2})(?:[:.h]?(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.|a|p)?$")

  data class Clock(val hour: Int, val minute: Int)

  fun parse(raw: String?): Clock? {
    val text = raw?.trim()?.lowercase()?.replace(Regex("\\s+"), " ") ?: return null
    when (text) {
      "noon", "midday" -> return Clock(12, 0)
      "midnight" -> return Clock(0, 0)
    }
    val m = CLOCK.matchEntire(text) ?: return null
    var hour = m.groupValues[1].toInt()
    val minute = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
    val half = m.groupValues[3].firstOrNull()
    if (minute !in 0..59) return null
    if (half != null) {
      if (hour !in 1..12) return null
      hour = when {
        half == 'a' && hour == 12 -> 0
        half == 'p' && hour != 12 -> hour + 12
        else -> hour
      }
    }
    return Clock(hour, minute).takeIf { hour in 0..23 }
  }
}
