/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.jarvis.SportsFeed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The Sports widget (plan §3.7, slice 3; mock `mocks/jarvis-personalization-20261122`, Home "Sports" card): one card
 * per followed team with its game under way, tonight's game, or the last result, the mock's status line ("Tonight ·
 * 7:30", "Final") and the two sides with their scores. A live score is shown only while its read is younger than
 * [LIVE_STALE_MS]; an older one reads "Live · updating" with no score, so the card is never stale while a game is on.
 *
 * [layout] scales the whole card with its Home slot (type, logos, gaps), and bigger slots show more teams. Pure.
 */
internal object SportsCard {
  const val LIVE_STALE_MS = 60_000L
  const val LIVE_REFRESH_MS = ForYouInterests.LIVE_REFRESH_MS
  const val REFRESH_MS = ForYouInterests.REFRESH_MS
  private const val MIN_REFRESH_MS = 30_000L
  private const val EVENING_HOUR = 17
  private const val WEEK_DAYS = 6L

  enum class Phase { LIVE, NEXT, FINAL, NONE }

  /** One side of a game: [color] six hex digits or empty, [score] null before the game (or while it is stale). */
  data class Side(val name: String, val abbreviation: String, val color: String, val score: String?)

  /** [detail] is the secondary line (the last result before a game, the next game after one). */
  data class Game(
      val key: String, val phase: Phase, val status: String, val us: Side, val them: Side?, val detail: String?,
      val startsAtMs: Long?,
  )

  /** The cards for [teams], read at [readAtMs]. */
  fun games(teams: List<ForYouInterests.TeamGames>, readAtMs: Long, nowMs: Long, zone: ZoneId, use24: Boolean): List<Game> =
      teams.map { game(it, readAtMs, nowMs, zone, use24) }

  /** When the card reads again: often while a game is live (or due to start), at tip-off, else every 10 minutes. */
  fun refreshMs(games: List<Game>, nowMs: Long): Long {
    var next = REFRESH_MS
    if (games.any { it.phase == Phase.LIVE }) next = LIVE_REFRESH_MS
    games.filter { it.phase == Phase.NEXT }.mapNotNull { it.startsAtMs }.forEach { start ->
      next = minOf(next, if (start <= nowMs) LIVE_REFRESH_MS else start - nowMs)
    }
    return next.coerceAtLeast(MIN_REFRESH_MS)
  }

  private fun game(t: ForYouInterests.TeamGames, readAtMs: Long, nowMs: Long, zone: ZoneId, use24: Boolean): Game {
    val fresh = nowMs - readAtMs <= LIVE_STALE_MS
    t.live?.let { g ->
      val them = g.them(t.teamId) ?: return@let
      val status = if (fresh) "Live · ${g.detail}".trimEnd(' ', '·') else "Live · updating"
      return Game(t.key, Phase.LIVE, status, side(t, g.us(t.teamId), fresh) ?: us(t), side(t, them, fresh),
          nextLine(t, g, nowMs, zone, use24), null)
    }
    val next = t.next?.takeIf { it.state == SportsFeed.State.PRE && it.them(t.teamId) != null }
    val last = t.last?.takeIf { it.state == SportsFeed.State.FINAL && it.them(t.teamId) != null }
    val soon = next?.takeIf { it.startMs - nowMs <= ForYouInterests.PRE_GAME_MS && days(it.startMs, nowMs, zone) == 0L }
    val recent = last?.takeIf { nowMs - it.startMs in 0..ForYouInterests.FINAL_MS }
    return when {
      soon != null -> pre(t, soon, last, nowMs, zone, use24)
      recent != null -> final(t, recent, nowMs, zone, use24, "Final")
      next != null -> pre(t, next, last, nowMs, zone, use24)
      last != null -> final(t, last, nowMs, zone, use24, "Final · ${day(last.startMs, nowMs, zone, past = true)}")
      else -> Game(t.key, Phase.NONE, "No games scheduled", us(t), null, null, null)
    }
  }

  private fun pre(t: ForYouInterests.TeamGames, g: SportsFeed.Game, last: SportsFeed.Game?, nowMs: Long, zone: ZoneId,
      use24: Boolean): Game {
    val detail = last?.let { result(t, it)?.let { r -> "Last: $r" } }
    return Game(t.key, Phase.NEXT, "${day(g.startMs, nowMs, zone, past = false)} · ${clock(g.startMs, zone, use24)}",
        us(t), side(t, g.them(t.teamId), false), detail, g.startMs)
  }

  private fun final(t: ForYouInterests.TeamGames, g: SportsFeed.Game, nowMs: Long, zone: ZoneId, use24: Boolean,
      status: String): Game =
      Game(t.key, Phase.FINAL, status, side(t, g.us(t.teamId), true) ?: us(t), side(t, g.them(t.teamId), true),
          nextLine(t, g, nowMs, zone, use24), null)

  private fun nextLine(t: ForYouInterests.TeamGames, current: SportsFeed.Game, nowMs: Long, zone: ZoneId, use24: Boolean): String? {
    val n = t.next?.takeIf { it.state == SportsFeed.State.PRE && it.id != current.id && it.startMs > current.startMs } ?: return null
    val them = n.them(t.teamId) ?: return null
    return "Next: ${day(n.startMs, nowMs, zone, past = false)} vs ${name(t, them)} · ${clock(n.startMs, zone, use24)}"
  }

  /** "won 118–112 vs Lakers", "lost 98–126 vs Grizzlies" (our score first, like the chips). */
  private fun result(t: ForYouInterests.TeamGames, g: SportsFeed.Game): String? {
    val us = g.us(t.teamId) ?: return null
    val them = g.them(t.teamId) ?: return null
    val a = us.score ?: return null
    val b = them.score ?: return null
    val ai = a.toIntOrNull() ?: 0
    val bi = b.toIntOrNull() ?: 0
    val verb = when {
      us.winner == true -> "won"
      them.winner == true -> "lost"
      ai == bi -> "drew"
      ai > bi -> "won"
      else -> "lost"
    }
    return "$verb $a–$b vs ${name(t, them)}"
  }

  private fun us(t: ForYouInterests.TeamGames): Side {
    val team = t.teams[t.teamId]
    return Side(t.name, abbreviation(team?.abbreviation, t.name), team?.color.orEmpty(), null)
  }

  private fun side(t: ForYouInterests.TeamGames, s: SportsFeed.Side?, withScore: Boolean): Side? {
    s ?: return null
    if (s.teamId == t.teamId) return us(t).copy(score = s.score.takeIf { withScore })
    val team = t.teams[s.teamId]
    val name = name(t, s)
    return Side(name, abbreviation(team?.abbreviation, name), team?.color.orEmpty(), s.score.takeIf { withScore })
  }

  private fun name(t: ForYouInterests.TeamGames, s: SportsFeed.Side): String =
      t.shortNames[s.teamId]?.ifBlank { null } ?: s.name

  private fun abbreviation(espn: String?, name: String): String =
      espn?.trim()?.uppercase(Locale.ROOT)?.ifEmpty { null }
          ?: name.filter { it.isLetter() }.take(3).uppercase(Locale.ROOT)

  private fun days(ms: Long, nowMs: Long, zone: ZoneId): Long =
      ChronoUnit.DAYS.between(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(), Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())

  /** "Tonight", "Today", "Tomorrow", "Sat" within the week, else "Nov 29"; for a past game "Yesterday", "Thu". */
  private fun day(ms: Long, nowMs: Long, zone: ZoneId, past: Boolean): String {
    val at = Instant.ofEpochMilli(ms).atZone(zone)
    val d = days(ms, nowMs, zone)
    return when {
      d == 0L && !past -> if (at.hour >= EVENING_HOUR) "Tonight" else "Today"
      d == 0L -> "Today"
      d == 1L && !past -> "Tomorrow"
      d == -1L && past -> "Yesterday"
      kotlin.math.abs(d) <= WEEK_DAYS -> at.format(DateTimeFormatter.ofPattern("EEE", Locale.US))
      else -> at.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
    }
  }

  /** The mock's clock: "7:30" (the day word says when) or "19:30". */
  private fun clock(ms: Long, zone: ZoneId, use24: Boolean): String =
      Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern(if (use24) "H:mm" else "h:mm", Locale.US))

  // ---- Layout: the design canvas is one card at scale 1 (mock Portal+ tokens); everything is multiplied by scale.

  /**
   * One card at scale 1: status line 32 + 12, logos 72 + 8 + name 30, the detail line 12 + 24; wide enough for two
   * 72 dp discs with their names either side of a final score ("118–112" in the 46 sp serif).
   */
  const val NATURAL_WIDTH_DP = 390f
  const val STATUS_DP = 32f
  const val STATUS_GAP_DP = 12f
  const val LOGO_DP = 72f
  const val LOGO_GAP_DP = 8f
  const val NAME_DP = 30f
  const val DETAIL_GAP_DP = 12f
  const val DETAIL_DP = 24f
  const val CELL_GAP_DP = 24f
  const val MIN_SCALE = 0.75f
  const val MAX_SCALE = 2.2f
  private const val MAX_COLUMNS = 2
  private const val MAX_ROWS = 2

  fun naturalHeight(details: Boolean): Float =
      STATUS_DP + STATUS_GAP_DP + LOGO_DP + LOGO_GAP_DP + NAME_DP + if (details) DETAIL_GAP_DP + DETAIL_DP else 0f

  /** [cells] teams in [columns] × [rows]; [scale] multiplies every size; [details] shows the secondary line. */
  data class Layout(val columns: Int, val rows: Int, val cells: Int, val scale: Float, val details: Boolean)

  /**
   * The layout for a widget body of [widthDp] × [heightDp] showing up to [teams] teams: a cell only splits off when
   * it would still hold a whole card at scale 1, then the card grows (or shrinks to [MIN_SCALE], dropping the detail
   * line first) to fill its cell in both directions.
   */
  fun layout(widthDp: Float, heightDp: Float, teams: Int): Layout {
    val maxCols = floor((widthDp + CELL_GAP_DP) / (NATURAL_WIDTH_DP + CELL_GAP_DP)).toInt().coerceIn(1, MAX_COLUMNS)
    val maxRows = floor((heightDp + CELL_GAP_DP) / (naturalHeight(true) + CELL_GAP_DP)).toInt().coerceIn(1, MAX_ROWS)
    val cells = teams.coerceAtLeast(1).coerceAtMost(maxCols * maxRows)
    val cols = minOf(maxCols, cells)
    val rows = ceil(cells / cols.toFloat()).toInt()
    val cellW = (widthDp - CELL_GAP_DP * (cols - 1)) / cols
    val cellH = (heightDp - CELL_GAP_DP * (rows - 1)) / rows
    val details = cellH >= naturalHeight(true) * MIN_SCALE && cellW >= NATURAL_WIDTH_DP * MIN_SCALE
    val scale = minOf(cellW / NATURAL_WIDTH_DP, cellH / naturalHeight(details)).coerceIn(MIN_SCALE, MAX_SCALE)
    return Layout(cols, rows, minOf(cells, teams.coerceAtLeast(1)), scale, details)
  }
}
