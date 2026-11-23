/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.BriefingCopy
import com.immortal.launcher.memory.MemoryDictionary
import com.immortal.launcher.memory.MemoryFact
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * `hearthpane.sports` for Jarvis: "how did the Warriors do", "what's the Arsenal score", "when do the Giants play
 * next", and, with Jarvis's memory on, "how did my team do" → the team's live score, last result, next game and
 * record from ESPN's keyless public scoreboards ([SportsFeed]), so it works on every Jarvis model, with or without a
 * Search key. The team comes from the user's words (matched against the memory dictionary, then ESPN's team list) or,
 * when none is named, from the teams Jarvis has learned the person follows (at most [MAX_TEAMS]). Arguments are
 * untrusted (model output). Pure apart from its seams, so every rule is JVM-tested.
 */
internal class HearthpaneSports(
    private val dictionary: () -> MemoryDictionary?,
    /** The teams memory knows this person (or the household) follows, most sure first; null when memory is off. */
    private val followed: () -> List<MemoryFact>?,
    /** Each ESPN path ([SportsFeed]) fetched side by side within [budgetMs]; a late or failed one is null. */
    private val fetch: (paths: List<String>, budgetMs: Long) -> List<String?>,
    private val now: () -> Long,
    private val zone: () -> TimeZone,
    private val locale: () -> Locale,
    private val use24: () -> Boolean,
) {
  /** A team to look up: what to call it, where ESPN keeps its league, and the words to find it by. */
  private data class Target(val label: String, val league: String, val fromMemory: Boolean)

  private data class Found(val target: Target, val team: SportsFeed.Team)

  fun invoke(argsJson: String?): JSONObject {
    val args = try { JSONObject(argsJson ?: "{}") } catch (_: JSONException) { return error("arguments must be a JSON object") }
    return runCatching { answer(args) }.getOrElse { error("scores unavailable") }
  }

  private fun answer(args: JSONObject): JSONObject {
    val said = (args.opt("team") as? String)?.trim()?.take(MAX_SAID).orEmpty()
    val leagueSaid = (args.opt("league") as? String)?.trim()?.take(MAX_SAID)
    val targets = if (said.isNotEmpty()) {
      named(said, leagueSaid) ?: return ask(
          "Which league are the ${said} in? I can look up the NBA, NFL, MLB, NHL, WNBA, the Premier League, MLS and " +
              "the big European leagues.", "needs_league")
    } else {
      val known = followed() ?: return ask("Which team? Tell me the team's name.", "needs_team")
      known.take(MAX_TEAMS).mapNotNull(::fromMemory).ifEmpty {
        return ask("Which team? I haven't learned which teams you follow yet.", "needs_team")
      }
    }
    val found = find(targets)
    if (found.isEmpty()) return reply("I couldn't reach the scores just now.")
    val reports = report(found)
    if (reports.isEmpty()) return reply("I couldn't reach the scores just now.")
    return JSONObject().put("ok", true).put("source", "ESPN")
        .put("teams", JSONArray(reports.map { it.first }))
        .put("message", reports.joinToString(" ") { it.second })
  }

  /** The dictionary's team for [said] (narrowed by the league), else ESPN's team list for a named league. */
  private fun named(said: String, leagueSaid: String?): List<Target>? {
    val dict = dictionary()
    val leaguePath = SportsFeed.leaguePath(leagueSaid)
    val entries = dict?.let { d ->
      d.lookup(said, MemoryFact.Kind.TEAM).ifEmpty {
        val want = words(said)
        d.entries.filter { it.kind == MemoryFact.Kind.TEAM && words(it.label).containsAll(want) }
      }
    }.orEmpty().filter { leaguePath == null || pathOf(it) == leaguePath }
    entries.singleOrNull()?.let { e -> return pathOf(e)?.let { listOf(Target(e.label, it, fromMemory = false)) } }
    if (leaguePath != null) return listOf(Target(said, leaguePath, fromMemory = false))
    return null
  }

  private fun fromMemory(fact: MemoryFact): Target? {
    val canonical = fact.id.substringAfter(':')
    val path = SportsFeed.pathFor(canonical, fact.attrs[MemoryDictionary.ATTR_LEAGUE]) ?: return null
    return Target(fact.label.ifBlank { return null }, path, fromMemory = true)
  }

  private fun pathOf(e: MemoryDictionary.Entry): String? = SportsFeed.pathFor(e.canonical, e.attrs[MemoryDictionary.ATTR_LEAGUE])

  /** Each target's ESPN team, from its league's team list (one fetch per league). */
  private fun find(targets: List<Target>): List<Found> {
    val leagues = targets.map { it.league }.distinct()
    val lists = fetch(leagues.map(SportsFeed::teamsPath), TEAMS_BUDGET_MS)
    val teams = leagues.zip(lists).associate { (league, body) -> league to SportsFeed.parseTeams(body) }
    return targets.mapNotNull { t -> SportsFeed.match(t.label, teams[t.league].orEmpty())?.let { Found(t, it) } }
        .distinctBy { it.team.id to it.target.league }
  }

  /** Every team's games in one round: its page, its schedule and its league's scoreboard. */
  private fun report(found: List<Found>): List<Pair<JSONObject, String>> {
    val paths = found.flatMap { f ->
      listOf(SportsFeed.teamPath(f.target.league, f.team.id), SportsFeed.schedulePath(f.target.league, f.team.id),
          SportsFeed.scoreboardPath(f.target.league))
    }
    val distinct = paths.distinct()
    val bodies = distinct.zip(fetch(distinct, GAMES_BUDGET_MS)).toMap()
    return found.mapNotNull { f ->
      val info = SportsFeed.parseTeam(bodies[SportsFeed.teamPath(f.target.league, f.team.id)])
      val schedule = SportsFeed.parseGames(bodies[SportsFeed.schedulePath(f.target.league, f.team.id)])
      val board = SportsFeed.parseGames(bodies[SportsFeed.scoreboardPath(f.target.league)])
      if (info == null && schedule.isEmpty() && SportsFeed.live(board, f.team.id) == null) return@mapNotNull null
      one(f, info, schedule, board)
    }
  }

  private fun one(f: Found, info: SportsFeed.TeamInfo?, schedule: List<SportsFeed.Game>, board: List<SportsFeed.Game>)
      : Pair<JSONObject, String> {
    val id = f.team.id
    val name = f.team.name
    val live = SportsFeed.live(board, id) ?: info?.next?.takeIf { it.state == SportsFeed.State.LIVE }
    val last = SportsFeed.lastResult(schedule + board, id)
    val next = info?.next?.takeIf { it.state == SportsFeed.State.PRE && it.id != live?.id }
        ?: SportsFeed.upcoming((schedule + board).filter { it.id != live?.id }, id, now())
    val json = JSONObject().put("team", name)
    val parts = ArrayList<String>()
    live?.let { g -> json.put("live", game(g, id)); parts += liveLine(g, id, name) }
    if (live == null) last?.let { g -> json.put("last", game(g, id)); parts += lastLine(g, id, name) }
    next?.let { g -> json.put("next", game(g, id)); parts += nextLine(g, id, if (parts.isEmpty()) name else null) }
    info?.record?.let { r ->
      json.put("record", r)
      parts += "They're $r" + (info.standing?.let { ", $it" } ?: "") + "."
    }
    if (parts.isEmpty()) parts += "I couldn't find any $name games just now."
    return json to parts.joinToString(" ")
  }

  private fun liveLine(g: SportsFeed.Game, id: String, name: String): String {
    val us = g.us(id) ?: return ""
    val them = g.them(id) ?: return ""
    return "Live: $name ${us.score ?: "0"}, ${them.name} ${them.score ?: "0"}" + (g.detail.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "") + "."
  }

  private fun lastLine(g: SportsFeed.Game, id: String, name: String): String {
    val us = g.us(id) ?: return ""
    val them = g.them(id) ?: return ""
    val when_ = day(g.startMs)
    val a = us.score
    val b = them.score
    if (a == null || b == null) return "$name played ${them.name} $when_."
    val verb = when {
      us.winner == true -> "beat"
      them.winner == true -> "lost to"
      a == b -> "drew with"
      (a.toIntOrNull() ?: 0) > (b.toIntOrNull() ?: 0) -> "beat"
      else -> "lost to"
    }
    val (high, low) = if ((a.toIntOrNull() ?: 0) >= (b.toIntOrNull() ?: 0)) a to b else b to a
    return "$name $verb ${them.name} $high to $low $when_."
  }

  private fun nextLine(g: SportsFeed.Game, id: String, name: String?): String {
    val us = g.us(id) ?: return ""
    val them = g.them(id) ?: return ""
    val where = if (us.home) "${them.name} at home" else "at ${them.name}"
    val time = BriefingCopy.clock(g.startMs, zone(), locale(), use24())
    return (if (name == null) "Next: " else "$name play next: ") + "$where ${day(g.startMs)} at $time."
  }

  private fun game(g: SportsFeed.Game, id: String): JSONObject {
    val us = g.us(id)
    val them = g.them(id)
    return JSONObject().put("opponent", them?.name).put("home", us?.home == true)
        .put("when", day(g.startMs)).put("time", BriefingCopy.clock(g.startMs, zone(), locale(), use24()))
        .apply { us?.score?.let { put("score", it) } }
        .apply { them?.score?.let { put("opponent_score", it) } }
        .apply { if (g.state != SportsFeed.State.PRE && g.detail.isNotBlank()) put("status", g.detail) }
  }

  /** today, tonight, yesterday, tomorrow, "on Friday" within a week, else "on November 1". */
  private fun day(ms: Long): String {
    val offset = daysBetween(dayStart(now()), dayStart(ms))
    return when {
      offset == 0 -> if (hour(ms) >= EVENING_HOUR) "tonight" else "today"
      offset == -1 -> if (hour(ms) >= EVENING_HOUR) "last night" else "yesterday"
      offset == 1 -> "tomorrow"
      offset in -6..6 -> (if (offset < 0) "last " else "on ") + format("EEEE", ms)
      else -> "on " + format("MMMM d", ms)
    }
  }

  private fun cal(ms: Long): Calendar = Calendar.getInstance(zone(), locale()).apply { timeInMillis = ms }

  private fun hour(ms: Long) = cal(ms).get(Calendar.HOUR_OF_DAY)

  private fun dayStart(ms: Long): Long = cal(ms).apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
  }.timeInMillis

  /** Calendar days between two local midnights, rounded (DST days are 23 or 25 hours). */
  private fun daysBetween(from: Long, to: Long): Int = Math.round((to - from) / DAY_MS.toDouble()).toInt()

  private fun format(pattern: String, ms: Long): String = SimpleDateFormat(pattern, locale()).apply { timeZone = zone() }.format(Date(ms))

  private fun words(s: String): List<String> =
      s.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it != "the" }

  private fun ask(message: String, flag: String) = JSONObject().put("ok", false).put(flag, true).put("message", message)

  private fun reply(message: String) = JSONObject().put("ok", false).put("message", message)

  private fun error(message: String) = JSONObject().put("error", message)

  companion object {
    const val NAME = "hearthpane.sports"
    const val MAX_TEAMS = 3
    private const val MAX_SAID = 60
    const val TEAMS_BUDGET_MS = 1_200L
    const val GAMES_BUDGET_MS = 2_500L
    private const val EVENING_HOUR = 17
    private const val DAY_MS = 24L * 60 * 60 * 1000
  }
}
