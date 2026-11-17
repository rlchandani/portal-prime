/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import java.util.TimeZone

/**
 * Gathers [BriefingInputs] from the existing features. Every call does disk or network work:
 * call from a background dispatcher only. [local] is quick (prefs and small files) and feeds the
 * first frame; [weather] and [events] hit the network and fill in when they answer.
 */
internal object BriefingLoader {
  private const val TAG = "ImmortalBriefing"

  fun local(context: Context, nowMs: Long): BriefingInputs {
    val app = context.applicationContext
    val zone = TimeZone.getDefault()
    val welcome = runCatching { WelcomeConfig.load(app) }.getOrNull()
    val settings = BriefingConfig.load(app)
    return BriefingInputs(
        nowMs = nowMs,
        zone = zone,
        use24Hour = ImmortalSettings.use24HourClock(app),
        fahrenheit = ImmortalSettings.useFahrenheit(app),
        greeting = welcome?.greetingMorning ?: BriefingCopy.DEFAULT_GREETING,
        name = welcome?.userName.orEmpty(),
        listItems = guarded("shared list", emptyList()) {
          HouseholdListStore.current(app).open().map { it.text.value }
        },
        timers = guarded("timers", emptyList()) { timers(app, nowMs) },
        routines = guarded("routines", emptyList()) { routines(app) },
        speakOn = settings.speak,
        quiet = guarded("quiet hours", true) { ChimeConfig.isQuietNow(app, BriefingPolicy.minuteOfDay(nowMs, zone)) },
    )
  }

  /** Current conditions plus today's high/low and rain chance, or null when unavailable. */
  fun weather(context: Context): BriefingWeather? = guarded("weather", null) {
    val now = Weather.fetchCurrent(context.applicationContext) ?: return@guarded null
    val today = Weather.fetchForecast(context.applicationContext)?.days?.firstOrNull()
    BriefingWeather(now.temp, now.code, today?.hi, today?.lo, today?.rainChance)
  }

  /** The calendar feed's events, empty when it can't be read, null when no calendar is added. */
  fun events(context: Context, nowMs: Long): List<CalendarFeed.Event>? {
    val url = ScreensaverConfig.load(context.applicationContext).calendarUrl
    if (url.isNullOrBlank()) return null
    return guarded("calendar", emptyList()) { CalendarFeed.read(url, nowMs).events }
  }

  private fun timers(context: Context, nowMs: Long): List<BriefingTimer> =
      SharedTimers.snapshot(context)
          .filter { it.state == SharedTimerState.RUNNING || it.state == SharedTimerState.PAUSED }
          .map { BriefingTimer(it.name, it.room, it.remaining(nowMs), it.paused) }

  /** Routines still to come today (not yet run, not past their end), by start time. */
  private fun routines(context: Context): List<BriefingRoutine> {
    RoutineEngine.init(context)
    val repo = RoutineEngine.repo
    val nowLocal = repo.clock.nowLocalMs()
    val today = RoutineDates.day(nowLocal)
    val runs = repo.runs()
    return repo.doc().routines.filter { RoutineSchedule.nextOccurrence(it, runs, nowLocal)?.day == today }
        .map { BriefingRoutine(it.name, it.start) }
  }

  /** One failing source must not blank the whole briefing; log it and show that part as empty. */
  private inline fun <T> guarded(what: String, fallback: T, block: () -> T): T =
      runCatching(block).getOrElse { Log.w(TAG, "briefing $what unavailable", it); fallback }
}
