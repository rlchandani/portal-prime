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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class TodayCalendarState(
    val url: String? = null,
    val event: CalendarFeed.Event? = null,
    val unavailable: Boolean = false,
    val refreshing: Boolean = false,
    val kicker: String = "",
    val detail: String = "",
) {
  fun forSource(source: String?): TodayCalendarState =
      if (url == source) this else TodayCalendarState(source, refreshing = !source.isNullOrBlank())
  val message: String? get() = when {
    url.isNullOrBlank() -> "Add your calendar"
    event != null -> null
    refreshing -> "Loading calendar…"
    unavailable -> "Calendar unavailable"
    else -> "No upcoming events"
  }
}

/** Main-thread ownership of one explicit retry; never persists or launches work itself. */
internal class TodayCalendarRefreshGate {
  var revision by mutableIntStateOf(0)
    private set
  private var pendingSource by mutableStateOf<String?>(null)

  fun pending(source: String?): Boolean = !source.isNullOrBlank() && pendingSource == source

  fun request(state: TodayCalendarState, source: String?): Boolean {
    if (source.isNullOrBlank() || state.url != source || !state.unavailable || state.refreshing || pending(source))
      return false
    pendingSource = source
    revision++
    return true
  }

  /** Consumed before IO, so a clock-format/lifecycle restart cannot force it twice. */
  fun consume(source: String?): Boolean {
    val owned = pending(source)
    pendingSource = null
    return owned
  }
}

internal fun prepareTodayCalendarState(
    url: String?, result: CalendarFeed.Result, nowMillis: Long, use24: Boolean,
    timeZone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault(),
): TodayCalendarState {
  if (url.isNullOrBlank()) return TodayCalendarState(url)
  val next = result.events.asSequence().filter { it.endMillis > nowMillis }.minByOrNull { it.startMillis }
  if (next == null) return TodayCalendarState(url, unavailable = result.unavailable)
  fun day(millis: Long) = Calendar.getInstance(timeZone, locale).apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
  }
  val today = day(nowMillis)
  val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
  val nextWeek = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 7) }
  val time = SimpleDateFormat(if (use24) "HH:mm" else "h:mm a", locale).apply { this.timeZone = timeZone }
  fun dateLabel(millis: Long): String {
    val date = day(millis)
    return when {
      date.timeInMillis == today.timeInMillis -> "Today"
      date.timeInMillis == tomorrow.timeInMillis -> "Tomorrow"
      date.timeInMillis > today.timeInMillis && date.timeInMillis < nextWeek.timeInMillis ->
          SimpleDateFormat("EEEE", locale).apply { this.timeZone = timeZone }.format(Date(millis))
      else -> SimpleDateFormat(if (date.get(Calendar.YEAR) == today.get(Calendar.YEAR))
          "EEE, MMM d" else "EEE, MMM d, yyyy", locale).apply { this.timeZone = timeZone }.format(Date(millis))
    }
  }
  val startedEarlier = next.startMillis < today.timeInMillis
  val context = if (startedEarlier) "Today" else dateLabel(next.startMillis)
  val kicker = when {
    next.allDay -> context
    startedEarlier -> "Today · In progress"
    else -> "$context · ${time.format(Date(next.startMillis))}"
  }
  val endDay = day(next.endMillis).timeInMillis
  val referenceDay = maxOf(today.timeInMillis, day(next.startMillis).timeInMillis)
  val detail = if (next.allDay) "All-day" else "Until " +
      (if (endDay != referenceDay) dateLabel(next.endMillis) + " · " else "") +
      time.format(Date(next.endMillis))
  return TodayCalendarState(url, next, unavailable = result.unavailable, kicker = kicker,
      detail = if (result.unavailable) "Saved event · $detail" else detail)
}

/** Only the calendar portion; also rendered with public fixtures by the test APK. */
@Composable
internal fun TodayCalendarSection(state: TodayCalendarState, onRetry: () -> Unit, onAdd: () -> Unit) {
  Column {
    val event = state.event
    if (event != null) {
      Text(state.kicker, color = ApprovedHomePalette.Secondary,
          fontSize = 18.sp, lineHeight = 24.sp, maxLines = 2,
          overflow = TextOverflow.Ellipsis)
      Row(Modifier.fillMaxWidth().padding(top = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.width(4.dp).height(67.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(ApprovedHomePalette.Accent))
        Column(Modifier.weight(1f)) {
          Text(event.title, color = ApprovedHomePalette.Text,
              fontSize = 24.sp, lineHeight = 32.sp,
              fontWeight = FontWeight.Medium, maxLines = 2,
              overflow = TextOverflow.Ellipsis)
          Text(state.detail, color = ApprovedHomePalette.Secondary,
              fontSize = 21.sp, lineHeight = 29.sp, maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(top = 6.dp))
        }
      }
    } else {
      Text(state.message.orEmpty(), color =
          if (state.url.isNullOrBlank()) ApprovedHomePalette.Text else ApprovedHomePalette.Secondary,
          fontSize = if (state.url.isNullOrBlank()) 26.sp else 22.sp,
          lineHeight = if (state.url.isNullOrBlank()) 34.sp else 30.sp)
    }
    if (state.url.isNullOrBlank()) {
      Text("See your upcoming events here.", color = ApprovedHomePalette.Secondary,
          fontSize = 22.sp, lineHeight = 30.sp, modifier = Modifier.padding(top = 12.dp))
      ApprovedHomeTextButton("Add calendar", onClick = onAdd)
    } else if (state.unavailable) {
      ApprovedHomeTextButton(if (state.refreshing) "Refreshing…" else "Try again",
          enabled = !state.refreshing, onClick = onRetry)
    }
  }
}
