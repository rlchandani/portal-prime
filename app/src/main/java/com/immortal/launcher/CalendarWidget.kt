/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun calendarEventLine(e: CalendarFeed.Event, nowMillis: Long, is24h: Boolean): String {
  if (e.allDay) return "All day  ${e.title}"
  val pattern = if (is24h) "HH:mm" else "h:mm a"
  return "${SimpleDateFormat(pattern, Locale.getDefault()).format(Date(e.startMillis))}  ${e.title}"
}

internal fun calendarEventTime(e: CalendarFeed.Event, is24h: Boolean): String {
  if (e.allDay) return "All-day"
  val pattern = if (is24h) "HH:mm" else "h:mm a"
  return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(e.startMillis))
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

/** Event pip colors, rotated per row (iOS calendar-category feel). */
private val PIP_COLORS =
    listOf(Color(0xFF0A84FF), Color(0xFF30D158), Color(0xFFFF9F0A), Color(0xFFFF375F), Color(0xFF5E5CE6))

/**
 * Dusk Aurora calendar: red weekday caps + a big date numeral (like the iOS calendar widget),
 * then color-pipped upcoming events grouped by day. Renders bare content — the widget grid's
 * LiquidGlass shell provides the card surface (the old version drew a second background inside
 * the shell, which is why it read lighter than every other widget).
 */
@Composable
internal fun ImmortalCalendarWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val is24h = remember { android.text.format.DateFormat.is24HourFormat(context) }
  val url = remember { ScreensaverConfig.load(context).calendarUrl }
  val events by produceState(initialValue = emptyList<CalendarFeed.Event>(), url) {
    value = if (url.isNullOrBlank()) emptyList()
        else withContext(Dispatchers.IO) { CalendarFeed.fetch(url) }
  }
  val now = remember { System.currentTimeMillis() }
  val groups = remember(events, now) { groupEventsByDay(events, now, maxDays = 3) }
  val cal = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
  val weekday = remember(now) {
    SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(now)).uppercase(Locale.getDefault())
  }

  Box(modifier = modifier.padding(18.dp)) {
    Column(Modifier.fillMaxSize()) {
      // iOS-calendar header: red weekday caps over a big thin date numeral.
      Text(
          weekday,
          color = Color(0xFFFF6961),
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.5.sp)
      Text(
          cal.get(Calendar.DAY_OF_MONTH).toString(),
          color = Color.White,
          fontSize = 40.sp,
          fontWeight = FontWeight.Light,
          letterSpacing = (-1).sp)
      Spacer(Modifier.height(8.dp))
      when {
        url.isNullOrBlank() -> {
          Text(
              "No calendar connected. Add a calendar link in Settings → Screensaver.",
              color = Color(0xB3FFFFFF),
              fontSize = 14.sp,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis)
        }
        groups.isEmpty() -> {
          Text("No upcoming events", color = Color(0xB3FFFFFF), fontSize = 14.sp)
        }
        else -> {
          var pip = 0
          LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            groups.forEach { group ->
              // "Today" is implied by the header date; later days still get their label.
              if (group.label != "Today") {
                item(key = "group_${group.label}") {
                  Text(
                      group.label.uppercase(Locale.getDefault()),
                      color = Color(0x73FFFFFF),
                      fontSize = 11.sp,
                      fontWeight = FontWeight.Bold,
                      letterSpacing = 1.sp,
                      modifier = Modifier.padding(top = 3.dp))
                }
              }
              items(group.events.size, key = { idx -> "event_${group.label}_${group.events[idx].startMillis}_${idx}" }) { idx ->
                val e = group.events[idx]
                val color = PIP_COLORS[(pip + idx) % PIP_COLORS.size]
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Box(
                      Modifier.width(3.5.dp).height(30.dp)
                          .clip(RoundedCornerShape(2.dp)).background(color))
                  Spacer(Modifier.width(9.dp))
                  Column {
                    Text(
                        e.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(
                        calendarEventTime(e, is24h),
                        color = Color(0x8CFFFFFF),
                        fontSize = 12.sp,
                        maxLines = 1)
                  }
                }
              }
              pip += group.events.size
            }
          }
        }
      }
    }
  }
}
