/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import java.util.TimeZone

/** When the Good morning card shows and when the briefing opens by itself. Pure; JVM-tested. */
internal object BriefingPolicy {
  /** The Good morning card shows on Home from 06:00 until 11:00. */
  const val CARD_START_MINUTE = 6 * 60
  const val CARD_END_MINUTE = 11 * 60
  private const val DAY_MS = 86_400_000L
  private const val MINUTES_PER_DAY = 1_440
  private const val MS_PER_MINUTE = 60_000L

  fun localDay(nowMs: Long, zone: TimeZone): Long = Math.floorDiv(nowMs + zone.getOffset(nowMs), DAY_MS)

  fun minuteOfDay(nowMs: Long, zone: TimeZone): Int =
      (Math.floorMod(nowMs + zone.getOffset(nowMs), DAY_MS) / MS_PER_MINUTE).toInt() % MINUTES_PER_DAY

  fun cardVisible(settings: BriefingConfig.Settings, nowMs: Long, zone: TimeZone, dismissedDay: Long?): Boolean =
      settings.enabled &&
          minuteOfDay(nowMs, zone) in CARD_START_MINUTE until CARD_END_MINUTE &&
          dismissedDay != localDay(nowMs, zone)

  /** After the alarm or wake light: once a day, only with "Show at alarm time". */
  fun opensAfterWake(settings: BriefingConfig.Settings, nowMs: Long, zone: TimeZone, lastAutoDay: Long?): Boolean =
      settings.enabled && settings.triggerMode == BriefingTrigger.ALARM && lastAutoDay != localDay(nowMs, zone)
}

/**
 * Per-day bookkeeping (not settings): the day the Good morning card was dismissed and the day the
 * briefing last opened by itself, so the alarm and the sunrise light don't both open it.
 */
internal object BriefingDayMarks {
  private const val PREFS = "immortal_briefing_days"
  private const val KEY_DISMISSED = "card_dismissed_day"
  private const val KEY_AUTO = "auto_shown_day"
  private const val NONE = Long.MIN_VALUE

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun dismissedDay(c: Context): Long? = prefs(c).getLong(KEY_DISMISSED, NONE).takeIf { it != NONE }

  fun lastAutoDay(c: Context): Long? = prefs(c).getLong(KEY_AUTO, NONE).takeIf { it != NONE }

  fun markDismissed(c: Context, day: Long) = prefs(c).edit().putLong(KEY_DISMISSED, day).apply()

  fun markAutoShown(c: Context, day: Long) = prefs(c).edit().putLong(KEY_AUTO, day).apply()
}
