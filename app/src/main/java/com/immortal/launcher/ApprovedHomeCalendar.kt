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
  val nextRefresh = remember(url, use24) { longArrayOf(0L) }
  val rows by produceHomeState(emptyList<HomePresentationCalendar.Row>(), url, use24) {
    if (nextRefresh[0] == 0L) value = emptyList()
    while (true) {
      val remaining = nextRefresh[0] - SystemClock.elapsedRealtime()
      if (remaining > 0) delay(remaining)
      value = if (url.isNullOrBlank()) emptyList() else withContext(Dispatchers.IO) {
        HomePresentationCalendar.prepare(CalendarFeed.fetch(url.orEmpty()), System.currentTimeMillis(), use24)
      }
      nextRefresh[0] = SystemClock.elapsedRealtime() + 30L * 60 * 1000
    }
  }
  Column(modifier.padding(30.dp)) {
    ApprovedHomeWidgetHeading("Calendar", "calendar") { context.openApprovedHomeSettings("calendar") }
    Spacer(Modifier.height(22.dp))
    LazyColumn(Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(23.dp)) {
      when {
        url.isNullOrBlank() -> item(key = "setup", contentType = "empty") {
          Column(verticalArrangement = Arrangement.spacedBy(23.dp)) {
          Text("Add your calendar", color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 34.sp)
          Text("See your upcoming events here.", color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
          ApprovedHomeTextButton("Add calendar") { context.openApprovedHomeSettings("calendar-url") }
          }
        }
        rows.isEmpty() -> item(key = "empty", contentType = "empty") {
          Text("No upcoming events", color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
        }
        else -> items(rows, key = { it.key }, contentType = { "event" }) { row ->
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
    }
  }
}
