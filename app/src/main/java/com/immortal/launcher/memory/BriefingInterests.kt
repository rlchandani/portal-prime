/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * The personal briefing's "Your teams / Your markets" line (plan §3.7, slice 3): the followed teams' game today, live
 * score or last result ([SportsCard]), and the followed stocks' moves once the market has opened (else when it
 * opens). Only for the recognised adult's own briefing, behind the same switch. Pure.
 */
internal object BriefingInterests {
  const val HEADING = "Your teams & markets"
  const val MAX_TEAMS = 2
  const val MAX_TICKERS = 3
  private val MARKET_OPEN: LocalTime = LocalTime.of(9, 30)

  /** "Your teams: Warriors vs Celtics, tonight at 5:30; Arsenal 2–1 Spurs, final", or null with nothing to say. */
  fun teams(games: List<SportsCard.Game>): String? {
    val parts = games.mapNotNull(::phrase).take(MAX_TEAMS)
    return if (parts.isEmpty()) null else "Your teams: " + parts.joinToString("; ")
  }

  private fun phrase(g: SportsCard.Game): String? {
    val them = g.them ?: return null
    val us = g.us
    val score = if (us.score != null && them.score != null) "${us.name} ${us.score}–${them.score} ${them.name}" else null
    return when (g.phase) {
      SportsCard.Phase.LIVE -> score?.let { "$it, live" } ?: "${us.name} vs ${them.name}, live now"
      SportsCard.Phase.FINAL -> score?.let { "$it, final" }
      SportsCard.Phase.NEXT -> "${us.name} vs ${them.name}, ${whenText(g.status)}"
      SportsCard.Phase.NONE -> null
    }
  }

  /** "Tonight · 5:30" → "tonight at 5:30"; "Sun · 5:30" → "Sun at 5:30". */
  private fun whenText(status: String): String {
    val day = status.substringBefore(" · ")
    val time = status.substringAfter(" · ", "")
    val word = if (day in RELATIVE_DAYS) day.lowercase(Locale.US) else day
    return if (time.isEmpty()) word else "$word at $time"
  }

  /**
   * "Your markets: NVDA +2.4%, AMD −1.1%" once today's [moves] are known; before the open on a weekday "Your markets:
   * NVDA, AMD · open at 6:30 am" (local time); otherwise just the names. Null without followed stocks.
   */
  fun markets(tickers: List<String>, moves: List<ForYouInterests.Move>, nowMs: Long, zone: ZoneId, use24: Boolean): String? {
    val shown = tickers.take(MAX_TICKERS)
    if (shown.isEmpty()) return null
    val bySymbol = moves.associateBy { it.symbol }
    if (ForYouInterests.wantsQuotes(nowMs) && shown.any { it in bySymbol }) {
      return "Your markets: " + shown.mapNotNull { s -> bySymbol[s]?.let { "$s ${pct(it.changePct)}" } }.joinToString(", ")
    }
    val et = Instant.ofEpochMilli(nowMs).atZone(ForYouInterests.MARKET_ZONE)
    val weekday = et.dayOfWeek != DayOfWeek.SATURDAY && et.dayOfWeek != DayOfWeek.SUNDAY
    val open = et.with(MARKET_OPEN)
    val names = shown.joinToString(", ")
    if (!weekday || !et.isBefore(open)) return "Your markets: $names"
    val local = open.withZoneSameInstant(zone)
    val time = if (use24) local.format(DateTimeFormatter.ofPattern("H:mm", Locale.US))
        else local.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)).lowercase(Locale.US)
    return "Your markets: $names · open at $time"
  }

  private fun pct(p: Double): String = (if (p >= 0) "+" else "−") + String.format(Locale.US, "%.1f%%", abs(p))

  private val RELATIVE_DAYS = setOf("Tonight", "Today", "Tomorrow", "Yesterday")
}
