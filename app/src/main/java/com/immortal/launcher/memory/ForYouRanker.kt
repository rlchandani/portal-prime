/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * The habit half of the "For you right now" chips (plan §3.6, slice 2), from the silent [UsageCounts] only: what this
 * scope tends to open on this Portal around this hour, on this kind of day. Interest chips (a team playing tonight)
 * need live data and come with slice 3, so a score here is `0.6 × habit`.
 *
 * A surface qualifies when, among weekdays (or weekend days, matching today), its opens within an hour either side
 * of now are at least [MIN_LIFT] times what an even spread over the day would give, with at least [MIN_OPENS] opens
 * over at least [MIN_WEEKS] different weeks. Each open counts `2^(-age / HALF_LIFE_DAYS)`. Feedback then multiplies
 * ([ForYouFeedback.factor]) or removes it ("Don't suggest this", ✕ for a while). Pure.
 */
internal object ForYouRanker {
  data class Suggestion(val surface: String, val score: Double, val opens: Int)

  const val HALF_LIFE_DAYS = 21
  const val MIN_OPENS = 4
  const val MIN_WEEKS = 2
  const val MIN_LIFT = 3.0
  /** Lift at which the habit counts fully (all of a day's opens inside the 3-hour window is 8). */
  const val FULL_LIFT = 6.0
  /** Opens (decayed) at which the habit is about two-thirds sure. */
  const val SUPPORT_SCALE = 4.0
  const val HABIT_WEIGHT = 0.6
  /** Below this (after fatigue) a suggestion isn't shown. */
  const val MIN_SCORE = 0.05
  private const val WINDOW_HOURS = 1
  private const val HOURS_PER_DAY = 24

  /** Surfaces a chip can open. Home and Settings are where you already are; Hearthpane's own package is ambiguous. */
  fun eligible(surface: String): Boolean = when {
    surface == MemoryHandoff.JARVIS_SURFACE -> true
    surface == SCREEN_MUSIC || surface == SCREEN_CALLS -> true
    surface == OWN_APP -> false
    else -> surface.startsWith(APP_PREFIX)
  }

  /** [scope]'s suggestions for [nowMs], best first. */
  fun rank(counts: UsageCounts, feedback: ForYouFeedback, scope: MemoryScope, nowMs: Long, zone: ZoneId): List<Suggestion> {
    val at = Instant.ofEpochMilli(nowMs).atZone(zone)
    val today = at.toLocalDate().toEpochDay()
    val weekend = isWeekend(today)
    val mine = counts.pruned(nowMs, zone).buckets
        .filter { it.scope == scope.token && eligible(it.surface) && isWeekend(it.day) == weekend }
    return mine.groupBy { it.surface }.mapNotNull { (surface, buckets) ->
      if (feedback.suppressed(scope, surface) || feedback.snoozed(scope, surface, nowMs)) return@mapNotNull null
      val habit = habit(buckets, today, at.hour) ?: return@mapNotNull null
      val score = HABIT_WEIGHT * habit.strength * feedback.factor(scope, surface, nowMs)
      Suggestion(surface, score, habit.opens).takeIf { score >= MIN_SCORE }
    }.sortedWith(compareByDescending<Suggestion> { it.score }.thenBy { it.surface })
  }

  /** The weight of an open [ageDays] old. */
  fun decay(ageDays: Long): Double = 2.0.pow(-ageDays.toDouble() / HALF_LIFE_DAYS)

  private class Habit(val strength: Double, val opens: Int)

  /** One surface's buckets (same day class) → its habit strength around [hour], or null if it isn't one. */
  private fun habit(buckets: List<UsageCounts.Bucket>, today: Long, hour: Int): Habit? {
    val near = buckets.filter { hourDistance(it.hour, hour) <= WINDOW_HOURS }
    val opens = near.sumOf { it.count }
    val weeks = near.map { (today - it.day) / DAYS_PER_WEEK }.distinct().size
    if (opens < MIN_OPENS || weeks < MIN_WEEKS) return null
    val window = near.sumOf { it.count * decay(today - it.day) }
    val total = buckets.sumOf { it.count * decay(today - it.day) }
    val expected = total * (2 * WINDOW_HOURS + 1) / HOURS_PER_DAY
    val lift = if (expected > 0) window / expected else 0.0
    if (lift < MIN_LIFT) return null
    return Habit((1 - exp(-window / SUPPORT_SCALE)) * min(1.0, lift / FULL_LIFT), opens)
  }

  private fun hourDistance(a: Int, b: Int): Int = abs(a - b).let { min(it, HOURS_PER_DAY - it) }

  private fun isWeekend(epochDay: Long): Boolean =
      LocalDate.ofEpochDay(epochDay).dayOfWeek.let { it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY }

  private const val DAYS_PER_WEEK = 7L
  const val APP_PREFIX = "app:"
  const val SCREEN_MUSIC = "screen:music"
  const val SCREEN_CALLS = "screen:calls"
  private const val OWN_APP = "app:com.immortal.launcher"
}
