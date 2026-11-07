/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import com.immortal.launcher.ui.components.OnResume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class HomeCalendarState(
    val url: String? = null,
    val rows: List<HomePresentationCalendar.Row> = emptyList(),
    val unavailable: Boolean = false,
    val refreshing: Boolean = false,
)

/** Uses the existing feed and grouping rules, rendered with the approved card anatomy. */
@Composable
internal fun ApprovedHomeCalendar(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var url by remember { mutableStateOf(ScreensaverConfig.load(context).calendarUrl) }
  var use24 by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  OnResume {
    url = ScreensaverConfig.load(context).calendarUrl
    use24 = ImmortalSettings.use24HourClock(context)
  }
  var refreshRequest by remember { mutableIntStateOf(0) }
  var requestPending by remember { mutableStateOf(false) }
  val nextRefresh = remember(url, use24, refreshRequest) { longArrayOf(0L) }
  val state by produceHomeState(HomeCalendarState(), url, use24, refreshRequest) {
    val sourceUrl = url
    val clock24 = use24
    if (sourceUrl.isNullOrBlank()) {
      value = HomeCalendarState(url = sourceUrl)
      requestPending = false
      return@produceHomeState
    }
    while (true) {
      val remaining = nextRefresh[0] - SystemClock.elapsedRealtime()
      if (remaining > 0) delay(remaining)
      value = if (value.url == sourceUrl) value.copy(refreshing = true, unavailable = value.unavailable && value.rows.isNotEmpty())
          else HomeCalendarState(url = sourceUrl, refreshing = true)
      val forceRefresh = requestPending && nextRefresh[0] == 0L
      value = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val result = CalendarFeed.read(sourceUrl, now, forceRefresh = forceRefresh)
        HomeCalendarState(sourceUrl, HomePresentationCalendar.prepare(result.events, now, clock24),
            unavailable = result.unavailable)
      }
      requestPending = false
      nextRefresh[0] = SystemClock.elapsedRealtime() + 30L * 60 * 1000
    }
  }
  // A changed source must never briefly show the previous calendar's rows.
  val current = if (state.url == url) state else HomeCalendarState(url, refreshing = !url.isNullOrBlank())
  val rows = current.rows
  val busy = current.refreshing || requestPending
  Column(modifier.padding(30.dp)) {
    ApprovedHomeWidgetHeading("Calendar", "calendar") { context.openApprovedHomeSettings("calendar") }
    Spacer(Modifier.height(22.dp))
    LazyColumn(Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(23.dp)) {
      if (!url.isNullOrBlank() && current.unavailable) {
        item(key = "unavailable", contentType = "status") {
          Text(if (rows.isEmpty()) "Calendar unavailable" else "Couldn’t refresh. Showing saved events.",
              color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
        }
      }
      when {
        url.isNullOrBlank() -> item(key = "setup", contentType = "empty") {
          Column(verticalArrangement = Arrangement.spacedBy(23.dp)) {
          Text("Add your calendar", color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 34.sp)
          Text("See your upcoming events here.", color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
          ApprovedHomePillButton("Add calendar") { context.openApprovedHomeSettings("calendar-url") }
          }
        }
        rows.isEmpty() && !current.unavailable -> item(key = "empty", contentType = "empty") {
          Text(if (current.refreshing) "Loading calendar…" else "No upcoming events", color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
        }
        rows.isNotEmpty() -> items(rows, key = { it.key }, contentType = { "event" }) { row ->
            Column {
              Text(row.kicker,
                  color = ApprovedHomePalette.Secondary, fontSize = 18.sp, lineHeight = 24.sp)
              Spacer(Modifier.height(10.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.width(4.dp).height(67.dp).clip(RoundedCornerShape(2.dp))
                    .background(ApprovedHomePalette.Accent))
                Text(row.title, color = ApprovedHomePalette.Text, fontSize = 24.sp, lineHeight = 32.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
              }
            }
        }
      }
      if (!url.isNullOrBlank()) {
        item(key = "refresh", contentType = "action") {
          ApprovedHomeTextButton(
              if (busy) "Refreshing…" else if (current.unavailable) "Try again" else "Refresh",
              enabled = !busy,
          ) {
            if (!busy && !requestPending) {
              requestPending = true
              refreshRequest++
            }
          }
        }
      }
    }
  }
}
