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

/** Prepared on IO: composition only reads immutable rows and lazily renders the viewport. */
internal object HomePresentationCalendar {
  data class Row(val key: String, val title: String, val kicker: String)

  fun prepare(
      events: List<CalendarFeed.Event>,
      nowMillis: Long,
      use24: Boolean,
      timeZone: TimeZone = TimeZone.getDefault(),
      locale: Locale = Locale.getDefault(),
  ): List<Row> {
    val day = Calendar.getInstance(timeZone, locale).apply {
      timeInMillis = nowMillis
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    // Calendar.add keeps the three-day window correct across daylight-saving changes.
    val starts = LongArray(4) {
      val start = day.timeInMillis
      day.add(Calendar.DAY_OF_YEAR, 1)
      start
    }
    val time = SimpleDateFormat(if (use24) "HH:mm" else "h:mm a", locale).apply {
      this.timeZone = timeZone
    }
    val weekday = SimpleDateFormat("EEEE", locale).apply { this.timeZone = timeZone }
    val duplicates = HashMap<String, Int>()
    // Filter before sorting so a long feed does not allocate/sort groups for unrelated days.
    return events.asSequence()
        .filter { it.startMillis >= starts[0] && it.startMillis < starts[3] }
        .sortedBy { it.startMillis }
        .map { event ->
          val label = when {
            event.startMillis < starts[1] -> "Today"
            event.startMillis < starts[2] -> "Tomorrow"
            else -> weekday.format(Date(event.startMillis))
          }
          val eventTime = if (event.allDay) "All-day" else time.format(Date(event.startMillis))
          // Length-prefix fields to distinguish titles/locations containing separators;
          // ordinal also retains exact duplicate occurrences without colliding lazy keys.
          val identity = listOf(event.startMillis.toString(), event.endMillis.toString(),
              event.title, event.allDay.toString(), event.location.orEmpty())
              .joinToString("") { "${it.length}:$it" }
          val ordinal = duplicates.getOrDefault(identity, 0)
          duplicates[identity] = ordinal + 1
          Row("$identity:$ordinal", event.title, "$label · $eventTime")
        }.toList()
  }
}
