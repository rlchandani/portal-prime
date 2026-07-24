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

internal fun calendarEventLine(e: CalendarFeed.Event, nowMillis: Long, is24h: Boolean): String {
  if (e.allDay) return "All day  ${e.title}"
  val pattern = if (is24h) "HH:mm" else "h:mm a"
  return "${SimpleDateFormat(pattern, Locale.getDefault()).format(Date(e.startMillis))}  ${e.title}"
}

internal data class CalendarDayGroup(val label: String, val events: List<CalendarFeed.Event>)

internal fun groupEventsByDay(
    events: List<CalendarFeed.Event>, nowMillis: Long, maxDays: Int = 3,
): List<CalendarDayGroup> {
  fun midnight(millis: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); return c.timeInMillis
  }
  val base = midnight(nowMillis)
  fun dayIdx(m: Long) = ((midnight(m) - base) / (24L * 60 * 60 * 1000)).toInt()
  fun label(idx: Int, m: Long) = when (idx) {
    0 -> "Today"; 1 -> "Tomorrow"
    else -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(m))
  }
  return events.sortedBy { it.startMillis }.groupBy { dayIdx(it.startMillis) }.toSortedMap()
      .entries.filter { it.key in 0 until maxDays }
      .map { (idx, evs) -> CalendarDayGroup(label(idx, evs.first().startMillis), evs) }
}
