/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@androidx.compose.runtime.Composable
internal fun ImmortalCalendarWidget(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val is24h = androidx.compose.runtime.remember { android.text.format.DateFormat.is24HourFormat(context) }
  val url = androidx.compose.runtime.remember { ScreensaverConfig.load(context).calendarUrl }
  val events by androidx.compose.runtime.produceState(
      initialValue = emptyList<CalendarFeed.Event>(), url) {
    value = if (url.isNullOrBlank()) emptyList()
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CalendarFeed.fetch(url) }
  }
  val now = androidx.compose.runtime.remember { System.currentTimeMillis() }
  val groups = androidx.compose.runtime.remember(events, now) { groupEventsByDay(events, now, maxDays = 3) }

  androidx.compose.foundation.layout.Box(
      modifier =
          modifier
              .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
              .background(androidx.compose.ui.graphics.Color(0x1EFFFFFF))
              .border(
                  1.dp,
                  androidx.compose.ui.graphics.Color(0x38FFFFFF),
                  androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
              .padding(18.dp)) {
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
      androidx.compose.material3.Text(
          "Calendar",
          color = androidx.compose.ui.graphics.Color.White,
          fontSize = 20.sp,
          fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
      androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
      when {
        url.isNullOrBlank() -> {
          androidx.compose.material3.Text(
              "No calendar connected. Add a calendar link in Settings → Screensaver.",
              color = androidx.compose.ui.graphics.Color(0xE6FFFFFF),
              fontSize = 15.sp,
              maxLines = 2,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        groups.isEmpty() -> {
          androidx.compose.material3.Text(
              "No upcoming events.",
              color = androidx.compose.ui.graphics.Color(0xE6FFFFFF),
              fontSize = 15.sp)
        }
        else -> {
          androidx.compose.foundation.lazy.LazyColumn(
              verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            groups.forEach { group ->
              item(key = "group_${group.label}") {
                androidx.compose.material3.Text(
                    group.label.uppercase(Locale.getDefault()),
                    color = androidx.compose.ui.graphics.Color(0x8CFFFFFF),
                    fontSize = 12.sp)
              }
              items(group.events.size, key = { idx -> "event_${group.label}_${group.events[idx].startMillis}_${idx}" }) { idx ->
                androidx.compose.material3.Text(
                    calendarEventLine(group.events[idx], now, is24h),
                    color = androidx.compose.ui.graphics.Color(0xE6FFFFFF),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
              }
            }
          }
        }
      }
    }
  }
}
