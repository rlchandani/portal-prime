/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.components.OnResume
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The glanceable "Today" strip for the left column: a frosted-glass card with three quick rows —
 * next calendar event, weather feels-like + hi/lo, and the moon phase with today's sunrise/sunset.
 * All data is already fetched elsewhere (weather + sun times keyless from Open-Meteo; moon phase is
 * pure date math). Re-reads the clock format on resume so times match the user's 12/24h setting.
 */
@Composable
internal fun TodayStripWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var use24 by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  var fahrenheit by remember { mutableStateOf(ImmortalSettings.useFahrenheit(context)) }
  OnResume {
        use24 = ImmortalSettings.use24HourClock(context)
        fahrenheit = ImmortalSettings.useFahrenheit(context)
  }
  val current by
      produceHomeState<Weather.Current?>(initialValue = null, fahrenheit) {
        while (true) {
          value = withContext(Dispatchers.IO) { Weather.fetchCurrent(context) }
          homeRefreshDelay(if (value == null) 60_000L else 30L * 60 * 1000)
        }
      }
  val forecast by
      produceHomeState<Weather.Forecast?>(initialValue = null, fahrenheit) {
        value = withContext(Dispatchers.IO) { Weather.fetchForecast(context) }
      }
  val sun by
      produceHomeState<Weather.SunTimes?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { Weather.fetchSunTimes(context) }
      }
  var calendarUrl by remember { mutableStateOf(ScreensaverConfig.load(context).calendarUrl) }
  OnResume { calendarUrl = ScreensaverConfig.load(context).calendarUrl }
  val calendarRefresh = remember { TodayCalendarRefreshGate() }
  val nextCalendarRefresh = remember(calendarUrl, use24, calendarRefresh.revision) { longArrayOf(0L) }
  val calendarState by produceHomeState(TodayCalendarState(), calendarUrl, use24, calendarRefresh.revision) {
    val sourceUrl = calendarUrl
    val clock24 = use24
    if (sourceUrl.isNullOrBlank()) {
      value = TodayCalendarState(sourceUrl)
      calendarRefresh.consume(sourceUrl)
      return@produceHomeState
    }
    while (true) {
      val remaining = nextCalendarRefresh[0] - android.os.SystemClock.elapsedRealtime()
      if (remaining > 0) delay(remaining)
      value = value.forSource(sourceUrl).copy(refreshing = true)
      val forceRefresh = calendarRefresh.consume(sourceUrl)
      value = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        prepareTodayCalendarState(sourceUrl,
            CalendarFeed.read(sourceUrl, now, forceRefresh = forceRefresh), now, clock24)
      }
      nextCalendarRefresh[0] = android.os.SystemClock.elapsedRealtime() + 30L * 60 * 1000
    }
  }
  val currentCalendar = calendarState.forSource(calendarUrl)
  val calendarBusy = currentCalendar.refreshing || calendarRefresh.pending(calendarUrl)



  // Nearest upcoming countdown (birthday/trip) from Settings > Countdown, plus the next alarm and
  // open to-do count. Re-read on resume so newly-added items show. Each is null/absent when empty,
  // so the dashboard rows self-hide rather than showing dead chrome.
  var countdown by remember { mutableStateOf<Pair<String, String>?>(null) }
  var alarmLine by remember { mutableStateOf<Pair<String, String>?>(null) }
  var todoOpen by remember { mutableStateOf(0) }
  val refreshTodayLines = {
    countdown = runCatching {
      CountdownConfig.loadSorted(context)
          .firstOrNull { it.daysUntil() >= 0 }
          ?.let { "${it.emoji} ${it.label}" to it.phrase() }
    }.getOrNull()
    alarmLine = runCatching {
      // The soonest of every alarm: the main one and the ones Jarvis added.
      MoreAlarms.all(context).mapNotNull { a -> AlarmConfig.nextTrigger(a.config)?.let { a.config to it } }
          .minByOrNull { it.second }?.let { (cfg, next) ->
            val fmt = SimpleDateFormat(if (use24) "EEE HH:mm" else "EEE h:mm a", Locale.getDefault())
            cfg.label.ifBlank { "Alarm" } to fmt.format(java.util.Date(next))
          }
    }.getOrNull()
    todoOpen = runCatching { TodoStore.load(context).count { !it.done } }.getOrDefault(0)
  }
  LaunchedEffect(Unit) { refreshTodayLines() }
  OnResume { refreshTodayLines() }

  val today = forecast?.days?.firstOrNull()
  val moonName by produceHomeState(initialValue = moonPhase(System.currentTimeMillis()).second) {
    while (true) {
      value = moonPhase(System.currentTimeMillis()).second
      delay(60L * 60 * 1000)
    }
  }
  val sunPair = sun?.formatted(use24)
  ImmortalWidgetShell(title = "Today", openPage = "calendar", modifier = modifier) {
    // Calendar setup is offered by the Calendar widget and this card's Settings action; Today only
    // shows calendar content once one is configured (approved Home v2, change 1).
    if (HomeRefinements.todayShowsCalendar(calendarUrl)) {
      TodayCalendarSection(currentCalendar.copy(refreshing = calendarBusy), onRetry = {
        if (calendarRefresh.request(currentCalendar.copy(refreshing = calendarBusy), calendarUrl)) {
          nextCalendarRefresh[0] = 0L
        }
      }, onAdd = { context.openApprovedHomeSettings("calendar-url") })
      Spacer(Modifier.height(20.dp))
    }
    TodayFactsGrid(remember(current, sunPair, today, moonName) {
      HomeRefinements.todayFacts(temp = current?.temp, feelsLike = current?.feelsLike, sun = sunPair,
          high = today?.hi, low = today?.lo, moon = moonName)
    })
    alarmLine?.let { (label, whenStr) ->
      Spacer(Modifier.height(14.dp)); TodayRow("⏰", label, whenStr)
    }
    if (todoOpen > 0) {
      Spacer(Modifier.height(14.dp)); TodayRow("✓", if (todoOpen == 1) "1 to-do" else "$todoOpen to-dos", "open")
    }
    countdown?.let { (label, phrase) ->
      Spacer(Modifier.height(14.dp)); TodayRow("⏳", label, phrase)
    }
  }

}

/** Today's facts as a two-column grid of small label over value (approved Home v2, change 3). */
@Composable
private fun TodayFactsGrid(facts: List<HomeRefinements.TodayFact>) {
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    facts.chunked(2).forEach { row ->
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        row.forEach { fact ->
          Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(fact.label, color = ApprovedHomePalette.Secondary, fontSize = 18.sp, lineHeight = 24.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Two lines so long values ("Waning crescent", localized times) wrap instead of clipping.
            Text(fact.value, color = ApprovedHomePalette.Text, fontSize = 24.sp, lineHeight = 30.sp,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
        }
        if (row.size == 1) Spacer(Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun TodayRow(glyph: String, text: String, trailing: String?) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(glyph, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp,
        modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
    Text(
        text,
        color = ApprovedHomePalette.Text,
        fontSize = 26.sp,
        lineHeight = 35.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    if (trailing != null) {
      Text(trailing, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp,
          maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
    }
  }
}

/**
 * Moon phase (glyph + name) from the epoch, via the standard synodic-month approximation
 * (29.53059 days from a known new moon). Pure date math — no network, good to ~1 day.
 */
private fun moonPhase(nowMillis: Long): Pair<String, String> {
  val synodic = 29.530588853
  val knownNew = 947182440000L // 2000-01-06 18:14 UTC, a reference new moon
  val days = (nowMillis - knownNew).toDouble() / 86_400_000.0
  val age = ((days % synodic) + synodic) % synodic
  return when {
    age < 1.84 -> "🌑" to "New moon"
    age < 5.53 -> "🌒" to "Waxing crescent"
    age < 9.22 -> "🌓" to "First quarter"
    age < 12.91 -> "🌔" to "Waxing gibbous"
    age < 16.61 -> "🌕" to "Full moon"
    age < 20.30 -> "🌖" to "Waning gibbous"
    age < 23.99 -> "🌗" to "Last quarter"
    age < 27.68 -> "🌘" to "Waning crescent"
    else -> "🌑" to "New moon"
  }
}
