/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PrimeBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The mock's hero card: a 56sp thin clock over "Good evening, Name", with the current temp
 * (26sp light) + condition bottom-right. Ticks by the minute; honors the 12/24h setting.
 */
@Composable
private fun HeroClockCard(
    greetingWord: String,
    userName: String,
    weather: Weather.Current?,
    modifier: Modifier = Modifier,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val use24 = remember { ImmortalSettings.use24HourClock(context) }
  val fmt = remember(use24) { SimpleDateFormat(if (use24) "H:mm" else "h:mm", Locale.getDefault()) }
  var now by remember { mutableStateOf(Date()) }
  LaunchedEffect(Unit) {
    while (true) {
      now = Date()
      delay(15_000)
    }
  }
  LiquidGlassWidget(modifier = modifier) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
      Column(Modifier.weight(1f)) {
        Text(
            fmt.format(now),
            color = Color.White,
            fontSize = 56.sp,
            fontWeight = FontWeight.ExtraLight,
            letterSpacing = (-2.5).sp,
            lineHeight = 56.sp,
        )
        Text(
            if (userName.isNotBlank()) "$greetingWord, $userName" else greetingWord,
            color = Color.White.copy(alpha = 0.66f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp),
        )
      }
      if (weather != null) {
        Column(horizontalAlignment = Alignment.End) {
          Text(
              "${weather.temp}°",
              color = Color.White,
              fontSize = 26.sp,
              fontWeight = FontWeight.Light,
          )
          Text(
              weatherConditionText(weather.code),
              color = Color.White.copy(alpha = 0.6f),
              fontSize = 12.5.sp,
              maxLines = 1,
          )
        }
      }
    }
  }
}

// Minimal weather condition text without network deps
internal fun weatherConditionText(code: Int): String = when (code) {
  0 -> "Clear Sky"
  in 1..3 -> "Partly Cloudy"
  in 45..48 -> "Foggy"
  in 51..67 -> "Rainy"
  in 71..77 -> "Snowy"
  in 80..82 -> "Showers"
  in 95..99 -> "Thunderstorm"
  else -> "Cloudy"
}

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

// The two swipeable forecast pages, in left-to-right order.
private const val PAGE_HOURLY = 0
private const val PAGE_DAILY = 1

/**
 * Optional home-screen forecast, shown full-width below the app grid when enabled in
 * Immortal Settings ▸ Weather. One network call fetches both views; the user swipes
 * left/right between the hourly and 7-day pages. [mode] picks which page shows first
 * (and jumps to it if the setting changes). [fahrenheit] only keys a re-fetch when the
 * unit changes — the unit itself is resolved inside [Weather.fetchForecast].
 *
 * Failure handling: the fetch retries every minute until it succeeds. While the very
 * first attempt is in flight nothing is drawn (no flash). If it can't be reached and
 * we have no forecast yet, a quiet "unavailable" note replaces the data; once a
 * forecast has loaded, a later failed refresh keeps the last good one on screen rather
 * than blanking it.
 */
@Composable
private fun WeatherWidget(mode: String, fahrenheit: Boolean) {
  val context = androidx.compose.ui.platform.LocalContext.current
  // Keyed on the unit only: switching pages is a local swipe, not a re-fetch.
  val state by
      produceState<ForecastState>(initialValue = ForecastState.Loading, fahrenheit) {
        while (true) {
          val f = withContext(Dispatchers.IO) { Weather.fetchForecast(context) }
          if (f != null) {
            value = ForecastState.Ready(f)
            delay(30L * 60 * 1000) // refresh every 30 min
          } else {
            // Keep showing the last good forecast if we have one; only surface the
            // note when there's nothing to display.
            if (value !is ForecastState.Ready) value = ForecastState.Unavailable
            delay(60L * 1000) // retry in 1 min
          }
        }
      }
  if (state is ForecastState.Loading) return

  val startPage = if (mode == ImmortalSettings.WIDGET_DAILY) PAGE_DAILY else PAGE_HOURLY
  val pagerState = rememberPagerState(initialPage = startPage) { 2 }
  // Follow the setting: if the user changes the default in Immortal Settings, jump to
  // that page when they come back (no-op on first composition, where it already matches).
  LaunchedEffect(mode) {
    if (pagerState.currentPage != startPage) pagerState.scrollToPage(startPage)
  }

  val ready = state as? ForecastState.Ready
  Surface(
      color = Color(0x14FFFFFF),
      shape = RoundedCornerShape(20.dp),
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
  ) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
      // Header: the current page's title, plus a two-dot indicator hinting the swipe.
      Row(
          modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            when {
              ready == null -> "Forecast"
              pagerState.currentPage == PAGE_DAILY -> "7-day forecast"
              else -> "Hourly forecast"
            },
            color = Color(0xFFBFBFBF),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (ready != null) PageDots(selected = pagerState.currentPage, count = 2)
      }
      if (ready != null) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
          // Each page's cells share the width evenly, spanning the whole card.
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (page == PAGE_HOURLY) {
              ready.forecast.hours.forEach { HourCell(it, Modifier.weight(1f)) }
            } else {
              ready.forecast.days.forEach { DayCell(it, Modifier.weight(1f)) }
            }
          }
        }
      } else {
        // Unavailable: no connection / location yet. Retries quietly in the
        // background, so no action is needed from the user.
        Text(
            "Forecast unavailable. It'll appear once your Portal is back online.",
            color = Color(0xFF9A9A9A),
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
      }
    }
  }
}

/** Small page-position dots, hinting the forecast can be swiped between its pages. */
@Composable
private fun PageDots(selected: Int, count: Int) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    repeat(count) { i ->
      val isActive = i == selected
      Box(
          modifier =
              Modifier.size(if (isActive) 6.dp else 4.dp)
                  .clip(androidx.compose.foundation.shape.CircleShape)
                  .background(if (isActive) PrimeBlue else Color(0x55FFFFFF)))
    }
  }
}

@Composable
private fun HourCell(h: Weather.HourForecast, modifier: Modifier = Modifier) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.padding(vertical = 4.dp),
  ) {
    Text(h.label, color = Color(0xFFCFCFCF), fontSize = 13.sp, maxLines = 1)
    Text(Weather.emoji(h.code), fontSize = 26.sp, modifier = Modifier.padding(vertical = 6.dp))
    Text("${h.temp}°", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun DayCell(d: Weather.DayForecast, modifier: Modifier = Modifier) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.padding(vertical = 4.dp),
  ) {
    Text(d.label, color = Color(0xFFCFCFCF), fontSize = 13.sp, maxLines = 1)
    Text(Weather.emoji(d.code), fontSize = 26.sp, modifier = Modifier.padding(vertical = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("${d.hi}°", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
      Text("${d.lo}°", color = Color(0xFF9A9A9A), fontSize = 16.sp)
    }
  }
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
