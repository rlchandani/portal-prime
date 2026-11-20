/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
// WiFi signal indicator
// ─────────────────────────────────────────────────────────────────────────────

/** What the forecast widget currently has to show. */
private sealed interface ForecastState {
  /** First fetch still in flight — render nothing so the bar doesn't flash. */
  object Loading : ForecastState
  /** Tried and failed with nothing cached — show a friendly note instead. */
  object Unavailable : ForecastState
  data class Ready(val forecast: Weather.Forecast) : ForecastState
}

/** Minute ticking is scoped to this chrome; the dashboard never reads this clock state. */
@Composable
internal fun ApprovedHomeGreeting(
    welcome: WelcomeConfig.Settings,
    use24: Boolean,
    compact: Boolean = false,
    layout: LayoutClass = LayoutClass.LARGE,
) {
  val now by produceState(initialValue = Date()) {
    while (true) {
      delay(60_000L - System.currentTimeMillis() % 60_000L)
      value = Date()
    }
  }
  val timeFmt = remember(use24) { SimpleDateFormat(if (use24) "H:mm" else "h:mm", Locale.getDefault()) }
  val dayFmt = remember { SimpleDateFormat("EEEE", Locale.getDefault()) }
  val dateFmt = remember { SimpleDateFormat("MMMM d", Locale.getDefault()) }
  val zone = TimeZone.getDefault()
  timeFmt.timeZone = zone
  dayFmt.timeZone = zone
  dateFmt.timeZone = zone
  val hour = java.util.Calendar.getInstance(zone).apply { time = now }.get(java.util.Calendar.HOUR_OF_DAY)
  val greeting = when (hour) {
    in 5..11 -> welcome.greetingMorning
    in 12..16 -> welcome.greetingAfternoon
    in 17..21 -> welcome.greetingEvening
    else -> welcome.greetingNight
  }
  val greetingText = if (welcome.userName.isBlank()) greeting.trimEnd('.', '!') + "."
      else greeting.trimEnd('.', '!') + ", " + welcome.userName
  if (layout.compact) {
    CompactHomeGreeting(greetingText, timeFmt.format(now), dayFmt.format(now), dateFmt.format(now),
        offline = compact, portrait = layout.compactPortrait)
    return
  }
  // Minimum, not fixed, height: a fixed 96dp clipped the subtitle's descenders on device.
  Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 68.dp else 96.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(greetingText, color = ApprovedHomePalette.Text, fontSize = 52.sp, lineHeight = 58.sp,
          fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (!compact) Text("Everything within reach.", color = ApprovedHomePalette.Secondary, fontSize = 24.sp,
          lineHeight = 32.sp, modifier = Modifier.padding(top = 4.dp))
    }
    Text(timeFmt.format(now), color = ApprovedHomePalette.Text, fontSize = 80.sp, lineHeight = 88.sp,
        fontWeight = FontWeight.Normal, letterSpacing = (-2).sp)
    Text(dayFmt.format(now) + "\n" + dateFmt.format(now), color = ApprovedHomePalette.Secondary,
        fontSize = 24.sp, lineHeight = 34.sp, modifier = Modifier.padding(start = 24.dp))
  }
}

/**
 * The compact greeting. Upright (800 dp): the greeting on its own line above the clock and date,
 * so neither truncates the other. Turned (1280×740 dp): one row, smaller, to leave the widgets height.
 * Every line is single-line; nothing can wrap mid-word.
 */
@Composable
private fun CompactHomeGreeting(greeting: String, time: String, day: String, date: String, offline: Boolean,
                                portrait: Boolean) {
  if (portrait) {
    Column(Modifier.fillMaxWidth()) {
      Text(greeting, color = ApprovedHomePalette.Text, fontSize = 36.sp, lineHeight = 44.sp,
          fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(time, color = ApprovedHomePalette.Text, fontSize = 56.sp, lineHeight = 64.sp,
            fontWeight = FontWeight.Normal, letterSpacing = (-1.5).sp, maxLines = 1, softWrap = false)
        Text("$day, $date", color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 20.dp))
      }
      if (!offline) Text("Everything within reach.", color = ApprovedHomePalette.Secondary, fontSize = 20.sp,
          lineHeight = 28.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  } else {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(greeting, color = ApprovedHomePalette.Text, fontSize = 40.sp, lineHeight = 48.sp,
          fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      Text(time, color = ApprovedHomePalette.Text, fontSize = 60.sp, lineHeight = 66.sp,
          fontWeight = FontWeight.Normal, letterSpacing = (-1.5).sp, maxLines = 1, softWrap = false,
          modifier = Modifier.padding(start = 24.dp))
      Text(day + "\n" + date, color = ApprovedHomePalette.Secondary, fontSize = 20.sp, lineHeight = 26.sp,
          maxLines = 2, softWrap = false, modifier = Modifier.padding(start = 20.dp))
    }
  }
}
