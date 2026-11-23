/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.jarvis.SportsFeed
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The interest half of the "For you right now" chips (plan §3.6 (b), slice 3; mock
 * `mocks/jarvis-personalization-20261122`, Home): ACTIVE teams, tickers and news topics joined with live data. A
 * followed team playing today ("Warriors vs Lakers · 7:30", "Your team plays tonight"), live ("Warriors 78–81
 * Celtics") or just finished ("Warriors won 118–112"); the market about to open or close; a followed stock moving
 * at least [BIG_MOVE_PCT] today ("AMD +4% today"); a followed topic with fresh headlines. A chip's score is
 * `0.4 × interest` ([INTEREST_WEIGHT]) times the same feedback as the habit chips, so it competes with them on the
 * plan's `0.6·habit + 0.4·interest` scale.
 *
 * Feedback: ✕ and impressions tire the chip ([Candidate.surface], one per team phase so a pre-game chip shown all
 * afternoon doesn't tire the live score); "Show less often" and "Don't suggest this" apply to the whole team, stock,
 * topic or the market ([Candidate.base]). Household chips never say "you". Pure.
 */
internal object ForYouInterests {
  const val INTEREST_WEIGHT = 0.4
  const val PREFIX = "interest:"
  const val BIG_MOVE_PCT = 4.0
  const val MAX_TEAMS = 3
  const val MAX_TICKERS = 5
  const val MAX_TOPICS = 3
  const val MAX_MOVES = 2
  /** A game later today is shown from this long before it starts. */
  const val PRE_GAME_MS = 12 * HOUR_MS
  /** A final score stays this long after the game started. */
  const val FINAL_MS = 15 * HOUR_MS
  /** Fresh headlines: the newest within this. */
  const val FRESH_NEWS_MS = 6 * HOUR_MS
  /** Market open/close chips show in the hour before. */
  const val MARKET_LEAD_MS = HOUR_MS
  const val REFRESH_MS = 10 * MINUTE_MS
  /** While a score is live the row re-reads it this often (ESPN's scoreboard is cached 20 s, never served stale). */
  const val LIVE_REFRESH_MS = 30_000L
  const val MARKET_REFRESH_MS = 5 * MINUTE_MS
  private const val MIN_REFRESH_MS = 30_000L
  private const val EVENING_HOUR = 17
  val MARKET_ZONE: ZoneId = ZoneId.of("America/New_York")
  private val MARKET_OPEN: LocalTime = LocalTime.of(9, 30)
  private val MARKET_CLOSE: LocalTime = LocalTime.of(16, 0)

  /** A followed team's games: [name] the short name ("Warriors"), [shortNames] ESPN id → short name for opponents. */
  data class TeamGames(
      val key: String, val teamId: String, val name: String, val live: SportsFeed.Game?, val last: SportsFeed.Game?,
      val next: SportsFeed.Game?, val shortNames: Map<String, String> = emptyMap(),
  )

  /** Today's move for a followed stock. */
  data class Move(val symbol: String, val changePct: Double)

  /** A followed news topic and when its newest headline was published (null when unknown or unreachable). */
  data class Topic(val key: String, val label: String, val newestMs: Long?)

  data class Inputs(
      val teams: List<TeamGames> = emptyList(),
      val tickers: List<String> = emptyList(),
      val moves: List<Move> = emptyList(),
      val topics: List<Topic> = emptyList(),
  )

  data class Candidate(
      val surface: String, val base: String, val title: String, val why: String, val glyph: String,
      val strength: Double, val live: Boolean = false, val startsAtMs: Long? = null,
  )

  enum class Market { CLOSED, OPENS_SOON, OPEN, CLOSES_SOON, CLOSED_TODAY }

  /** Where the US stock market is at [nowMs] (weekdays 9:30–16:00 New York; holidays are not known). */
  fun market(nowMs: Long): Market {
    val et = Instant.ofEpochMilli(nowMs).atZone(MARKET_ZONE)
    if (et.dayOfWeek == DayOfWeek.SATURDAY || et.dayOfWeek == DayOfWeek.SUNDAY) return Market.CLOSED
    val open = et.with(MARKET_OPEN).toInstant().toEpochMilli()
    val close = et.with(MARKET_CLOSE).toInstant().toEpochMilli()
    return when {
      nowMs < open - MARKET_LEAD_MS -> Market.CLOSED
      nowMs < open -> Market.OPENS_SOON
      nowMs < close - MARKET_LEAD_MS -> Market.OPEN
      nowMs < close -> Market.CLOSES_SOON
      else -> Market.CLOSED_TODAY
    }
  }

  /** Whether today's moves mean anything now: the market opened today (New York). */
  fun wantsQuotes(nowMs: Long): Boolean = market(nowMs).let { it == Market.OPEN || it == Market.CLOSES_SOON || it == Market.CLOSED_TODAY }

  /** Every chip the live data supports right now, before feedback. */
  fun candidates(inputs: Inputs, household: Boolean, nowMs: Long, zone: ZoneId, use24: Boolean): List<Candidate> =
      inputs.teams.take(MAX_TEAMS).mapNotNull { team(it, household, nowMs, zone, use24) } +
          market(inputs.tickers, household, nowMs, zone, use24) +
          moves(inputs, household, nowMs) +
          inputs.topics.take(MAX_TOPICS).mapNotNull { topic(it, household, nowMs) }

  /** [candidates] as scored suggestions, minus anything put off or turned down, best first. */
  fun rank(candidates: List<Candidate>, feedback: ForYouFeedback, scope: MemoryScope, nowMs: Long): List<ForYouRanker.Suggestion> =
      candidates.mapNotNull { c ->
        if (feedback.suppressed(scope, c.base) || feedback.suppressed(scope, c.surface)) return@mapNotNull null
        if (feedback.snoozed(scope, c.surface, nowMs)) return@mapNotNull null
        val score = INTEREST_WEIGHT * c.strength * feedback.factor(scope, c.surface, nowMs) * feedback.factor(scope, c.base, nowMs)
        ForYouRanker.Suggestion(c.surface, score, opens = 0).takeIf { score >= ForYouRanker.MIN_SCORE }
      }.sortedWith(compareByDescending<ForYouRanker.Suggestion> { it.score }.thenBy { it.surface })

  /** What a chip shows and where its feedback goes. */
  fun target(c: Candidate): ForYouChips.Target = ForYouChips.Target(c.surface, c.title, c.glyph, why = c.why, feedbackKey = c.base)

  /** When to read again: soon while a score is live, at kick-off, or as the market's countdown moves. */
  fun refreshMs(shown: List<Candidate>, nowMs: Long): Long {
    var next = REFRESH_MS
    if (shown.any { it.live }) next = LIVE_REFRESH_MS
    if (shown.any { it.surface == MARKET_OPEN_SURFACE }) next = minOf(next, MARKET_REFRESH_MS)
    shown.mapNotNull { it.startsAtMs }.filter { it > nowMs }.minOrNull()?.let { next = minOf(next, it - nowMs) }
    return next.coerceAtLeast(MIN_REFRESH_MS)
  }

  fun isInterest(surface: String): Boolean = surface.startsWith(PREFIX)

  /** A fact's canonical part, safe inside a feedback surface. */
  fun keyOf(raw: String): String =
      raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_.-]+"), "-").trim('-').take(MAX_KEY).ifEmpty { "x" }

  private fun team(t: TeamGames, household: Boolean, nowMs: Long, zone: ZoneId, use24: Boolean): Candidate? {
    val base = "${PREFIX}team:${keyOf(t.key)}"
    t.live?.let { g ->
      val us = g.us(t.teamId) ?: return@let
      val them = g.them(t.teamId) ?: return@let
      val a = us.score ?: return@let
      val b = them.score ?: return@let
      return Candidate("$base:live", base, "${t.name} $a–$b ${short(t, them)}",
          if (household) "Live now · followed here" else "Live now · your team", BALL, 1.0, live = true)
    }
    t.next?.takeIf { it.state == SportsFeed.State.PRE && it.startMs - nowMs in -HOUR_MS..PRE_GAME_MS && sameDay(it.startMs, nowMs, zone) }
        ?.let { g ->
          val them = g.them(t.teamId) ?: return@let
          val evening = Instant.ofEpochMilli(g.startMs).atZone(zone).hour >= EVENING_HOUR
          val why = when {
            household -> if (evening) "A team followed here plays tonight" else "A team followed here plays today"
            else -> if (evening) "Your team plays tonight" else "Your team plays today"
          }
          val strength = if (g.startMs - nowMs <= 3 * HOUR_MS) 0.9 else 0.7
          return Candidate("$base:next", base, "${t.name} vs ${short(t, them)} · ${clock(g.startMs, zone, use24)}", why,
              BALL, strength, startsAtMs = g.startMs)
        }
    t.last?.takeIf { it.state == SportsFeed.State.FINAL && nowMs - it.startMs in 0..FINAL_MS }?.let { g ->
      val us = g.us(t.teamId) ?: return@let
      val them = g.them(t.teamId) ?: return@let
      val a = us.score ?: return@let
      val b = them.score ?: return@let
      val ai = a.toIntOrNull() ?: 0
      val bi = b.toIntOrNull() ?: 0
      val verb = when {
        us.winner == true -> "won"
        them.winner == true -> "lost"
        ai == bi -> "drew"
        ai > bi -> "won"
        else -> "lost"
      }
      return Candidate("$base:final", base, "${t.name} $verb $a–$b",
          if (household) "Final score · followed here" else "Final score · your team", BALL, 0.6)
    }
    return null
  }

  private fun short(t: TeamGames, side: SportsFeed.Side): String = t.shortNames[side.teamId]?.ifBlank { null } ?: side.name

  private fun market(tickers: List<String>, household: Boolean, nowMs: Long, zone: ZoneId, use24: Boolean): List<Candidate> {
    if (tickers.isEmpty()) return emptyList()
    val et = Instant.ofEpochMilli(nowMs).atZone(MARKET_ZONE)
    return when (market(nowMs)) {
      Market.OPENS_SOON -> {
        val minutes = ceil((et.with(MARKET_OPEN).toInstant().toEpochMilli() - nowMs) / MINUTE_MS.toDouble() / 5).toInt() * 5
        listOf(Candidate(MARKET_OPEN_SURFACE, MARKET_BASE, "${tickers.first()} opens in $minutes min",
            if (household) "A stock followed here" else "A stock you follow", TREND, 0.5))
      }
      Market.CLOSES_SOON -> {
        val close = et.with(MARKET_CLOSE).withZoneSameInstant(zone)
        listOf(Candidate(MARKET_CLOSE_SURFACE, MARKET_BASE, "Markets close at ${hour(close, use24)}",
            tickers.take(3).joinToString(", "), TREND, 0.45))
      }
      else -> emptyList()
    }
  }

  private fun moves(inputs: Inputs, household: Boolean, nowMs: Long): List<Candidate> {
    if (!wantsQuotes(nowMs)) return emptyList()
    val followed = inputs.tickers.take(MAX_TICKERS).toSet()
    return inputs.moves.filter { it.symbol in followed && it.changePct.isFinite() && abs(it.changePct) >= BIG_MOVE_PCT }
        .sortedByDescending { abs(it.changePct) }.take(MAX_MOVES).map { m ->
          val base = "${PREFIX}ticker:${keyOf(m.symbol)}"
          val pct = abs(m.changePct).roundToInt()
          val strength = (0.75 + (abs(m.changePct) - BIG_MOVE_PCT) / 4 * 0.15).coerceAtMost(0.9)
          Candidate("$base:move", base, "${m.symbol} ${if (m.changePct > 0) "+" else "−"}$pct% today",
              if (household) "A stock followed here" else "A stock you follow", TREND, strength)
        }
  }

  private fun topic(t: Topic, household: Boolean, nowMs: Long): Candidate? {
    val newest = t.newestMs ?: return null
    if (nowMs - newest !in 0..FRESH_NEWS_MS || t.label.isBlank()) return null
    val base = "${PREFIX}news:${keyOf(t.key)}"
    return Candidate("$base:fresh", base, "${t.label} headlines",
        if (household) "A topic followed here" else "A topic you follow", NEWS, 0.5)
  }

  private fun sameDay(a: Long, b: Long, zone: ZoneId): Boolean =
      Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

  /** The mock's clock on a chip: "7:30" (12-hour without AM/PM, the "why" says today or tonight), or "19:30". */
  private fun clock(ms: Long, zone: ZoneId, use24: Boolean): String =
      Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern(if (use24) "H:mm" else "h:mm", Locale.US))

  /** "1 pm", "12:30 pm", or "13:00". */
  private fun hour(at: ZonedDateTime, use24: Boolean): String = when {
    use24 -> at.format(DateTimeFormatter.ofPattern("H:mm", Locale.US))
    at.minute == 0 -> at.format(DateTimeFormatter.ofPattern("h a", Locale.US)).lowercase(Locale.US)
    else -> at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)).lowercase(Locale.US)
  }

  const val BALL = "interest_ball"
  const val TREND = "interest_trend"
  const val NEWS = "interest_news"
  const val MARKET_BASE = "${PREFIX}market"
  const val MARKET_OPEN_SURFACE = "$MARKET_BASE:open"
  const val MARKET_CLOSE_SURFACE = "$MARKET_BASE:close"
  private const val MAX_KEY = 40
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
